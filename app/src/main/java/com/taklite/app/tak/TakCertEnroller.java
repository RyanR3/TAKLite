package com.taklite.app.tak;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Handles TAK server certificate enrollment.
 * Flow: username/password -> CSR -> signed cert -> .p12 files
 */
public class TakCertEnroller {

    private static final String TAG = "TakCertEnroller";
    private static final String DEFAULT_P12_PASSWORD = "atakatak";

    public interface EnrollmentCallback {
        void onSuccess(String trustStorePath, String clientCertPath);
        void onError(String message);
    }

    /**
     * Enroll with TAK server to obtain client certificate and trust store.
     * Must be called from a background thread.
     */
    public static void enroll(String serverAddress, int enrollPort,
                               String username, String password,
                               String uid, File filesDir,
                               EnrollmentCallback callback) {
        try {
            // Allow self-signed certs during enrollment
            SSLContext sslCtx = createTrustAllSSLContext();

            // Build Basic Auth header for all requests
            String basicAuth = "Basic " + Base64.encodeToString(
                    (username + ":" + password).getBytes(), Base64.NO_WRAP);

            // Step 1: Get TLS config (O, OU for CSR subject)
            String configUrl = "https://" + serverAddress + ":" + enrollPort + "/Marti/api/tls/config";
            Log.d(TAG, "Step 1: Fetching TLS config from " + configUrl);
            String configXml = httpsGet(configUrl, basicAuth, sslCtx);
            String org = parseNameEntry(configXml, "O", "TAK");
            String orgUnit = parseNameEntry(configXml, "OU", "TAK");
            Log.d(TAG, "TLS config: O=" + org + ", OU=" + orgUnit);

            // Step 2: Generate RSA key pair and CSR
            Log.d(TAG, "Step 2: Generating RSA key pair and CSR");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();

            String csrPem = generateCSR(keyPair, username, org, orgUnit);
            Log.d(TAG, "CSR generated for CN=" + username);

            // Step 3: POST CSR to signClient/v2
            String signUrl = "https://" + serverAddress + ":" + enrollPort
                    + "/Marti/api/tls/signClient/v2?clientUid=" + urlEncode(uid) + "&version=3";
            Log.d(TAG, "Step 3: Signing CSR at " + signUrl);
            String responseJson = httpsPost(signUrl, csrPem, basicAuth, sslCtx);

            JSONObject json = new JSONObject(responseJson);
            String signedCertB64 = json.getString("signedCert");
            String ca0B64 = json.optString("ca0", "");
            String ca1B64 = json.optString("ca1", "");

            Log.d(TAG, "Got signed cert + CA chain from server");

            // Step 4: Build client .p12 (signed cert + private key)
            X509Certificate signedCert = decodeCert(signedCertB64);
            X509Certificate caCert0 = ca0B64.isEmpty() ? null : decodeCert(ca0B64);
            X509Certificate caCert1 = ca1B64.isEmpty() ? null : decodeCert(ca1B64);

            File clientP12 = new File(filesDir, "tak_clientcert.p12");
            buildClientP12(keyPair, signedCert, caCert0, caCert1, clientP12);
            Log.d(TAG, "Client .p12 saved: " + clientP12.getAbsolutePath());

            // Step 5: Build trust store .p12 from CA certs
            File trustP12 = new File(filesDir, "tak_truststore.p12");
            boolean trustBuilt = false;

            // Try fetching truststore from server first
            try {
                String trustUrl = "https://" + serverAddress + ":" + enrollPort + "/api/truststore";
                byte[] trustData = httpsGetBytes(trustUrl, basicAuth, sslCtx);
                if (trustData != null && trustData.length > 0) {
                    FileOutputStream fos = new FileOutputStream(trustP12);
                    fos.write(trustData);
                    fos.close();
                    trustBuilt = true;
                    Log.d(TAG, "Trust store downloaded from /api/truststore");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not download truststore, building from CA certs: " + e.getMessage());
            }

            if (!trustBuilt) {
                buildTrustStoreP12(caCert0, caCert1, trustP12);
                Log.d(TAG, "Trust store built from CA certs");
            }

            callback.onSuccess(trustP12.getAbsolutePath(), clientP12.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Enrollment failed", e);
            callback.onError(e.getMessage());
        }
    }

    // --- CSR Generation using Android's built-in Bouncy Castle ---

    private static String generateCSR(KeyPair keyPair, String cn, String org, String orgUnit) throws Exception {
        // Build the subject DN
        String subject = "CN=" + cn + ",O=" + org + ",OU=" + orgUnit;

        // Use a minimal ASN.1 approach (no external BouncyCastle dependency)
        return buildMinimalCSR(keyPair, subject);
    }

    /**
     * Build a minimal PKCS#10 CSR using raw ASN.1 encoding.
     * This avoids any external dependency on BouncyCastle APIs.
     */
    private static String buildMinimalCSR(KeyPair keyPair, String subject) throws Exception {
        // Parse subject components
        String cn = "", org = "", ou = "";
        for (String part : subject.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                switch (kv[0].trim()) {
                    case "CN": cn = kv[1].trim(); break;
                    case "O": org = kv[1].trim(); break;
                    case "OU": ou = kv[1].trim(); break;
                }
            }
        }

        // Build subject DN in ASN.1 DER
        byte[] cnBytes = buildRDN("2.5.4.3", cn);   // CN OID
        byte[] ouBytes = buildRDN("2.5.4.11", ou);   // OU OID
        byte[] oBytes = buildRDN("2.5.4.10", org);   // O OID

        byte[] subjectDer = buildSequence(concat(cnBytes, ouBytes, oBytes));

        // Encode public key info
        byte[] pubKeyEncoded = keyPair.getPublic().getEncoded(); // SubjectPublicKeyInfo DER

        // Build CertificationRequestInfo:
        // SEQUENCE { version INTEGER(0), subject, subjectPKInfo, [0] attributes }
        byte[] version = new byte[]{0x02, 0x01, 0x00}; // INTEGER 0
        byte[] attributes = new byte[]{(byte) 0xA0, 0x00}; // [0] IMPLICIT empty SET

        byte[] certReqInfo = buildSequence(concat(version, subjectDer, pubKeyEncoded, attributes));

        // Sign the certReqInfo
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(certReqInfo);
        byte[] signature = signer.sign();

        // Build signatureAlgorithm (sha256WithRSAEncryption)
        byte[] sigAlgOid = encodeOID("1.2.840.113549.1.1.11");
        byte[] sigAlg = buildSequence(concat(sigAlgOid, new byte[]{0x05, 0x00})); // OID + NULL

        // Build BIT STRING for signature
        byte[] sigBitString = new byte[signature.length + 1];
        sigBitString[0] = 0x00; // no unused bits
        System.arraycopy(signature, 0, sigBitString, 1, signature.length);
        byte[] sigDer = buildTLV(0x03, sigBitString);

        // Build final CSR: SEQUENCE { certReqInfo, sigAlg, signature }
        byte[] csrDer = buildSequence(concat(certReqInfo, sigAlg, sigDer));

        // Encode as PEM
        String b64 = Base64.encodeToString(csrDer, Base64.DEFAULT);
        return "-----BEGIN CERTIFICATE REQUEST-----\n" + b64 + "-----END CERTIFICATE REQUEST-----";
    }

    // --- ASN.1 DER helpers ---

    private static byte[] buildRDN(String oid, String value) {
        byte[] oidBytes = encodeOID(oid);
        byte[] valBytes = buildTLV(0x0C, value.getBytes()); // UTF8String
        byte[] atv = buildSequence(concat(oidBytes, valBytes));
        byte[] rdnSet = buildTLV(0x31, atv); // SET
        return rdnSet;
    }

    private static byte[] buildSequence(byte[] content) {
        return buildTLV(0x30, content);
    }

    private static byte[] buildTLV(int tag, byte[] value) {
        byte[] length = encodeLength(value.length);
        byte[] result = new byte[1 + length.length + value.length];
        result[0] = (byte) tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(value, 0, result, 1 + length.length, value.length);
        return result;
    }

    private static byte[] encodeLength(int len) {
        if (len < 128) {
            return new byte[]{(byte) len};
        } else if (len < 256) {
            return new byte[]{(byte) 0x81, (byte) len};
        } else {
            return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) (len & 0xFF)};
        }
    }

    private static byte[] encodeOID(String oid) {
        String[] parts = oid.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) nums[i] = Integer.parseInt(parts[i]);

        // First two arcs combined: 40 * first + second
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(40 * nums[0] + nums[1]);
        for (int i = 2; i < nums.length; i++) {
            encodeOIDComponent(bos, nums[i]);
        }
        byte[] oidContent = bos.toByteArray();
        return buildTLV(0x06, oidContent);
    }

    private static void encodeOIDComponent(java.io.ByteArrayOutputStream bos, int value) {
        if (value < 128) {
            bos.write(value);
        } else {
            // Multi-byte encoding
            byte[] temp = new byte[5];
            int pos = 4;
            temp[pos--] = (byte) (value & 0x7F);
            value >>= 7;
            while (value > 0) {
                temp[pos--] = (byte) (0x80 | (value & 0x7F));
                value >>= 7;
            }
            bos.write(temp, pos + 1, 4 - pos);
        }
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }

    // --- HTTPS helpers ---

    private static String httpsGet(String urlStr, String authHeader, SSLContext sslCtx) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code + " from " + urlStr);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private static byte[] httpsGetBytes(String urlStr, String authHeader, SSLContext sslCtx) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code + " from " + urlStr);
        }

        InputStream is = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        is.close();
        return bos.toByteArray();
    }

    private static String httpsPost(String urlStr, String body, String authHeader, SSLContext sslCtx) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes());
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is;
        if (code >= 200 && code < 300) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
            BufferedReader er = new BufferedReader(new InputStreamReader(is));
            StringBuilder esb = new StringBuilder();
            String eline;
            while ((eline = er.readLine()) != null) esb.append(eline);
            er.close();
            throw new Exception("HTTP " + code + ": " + esb.toString());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    // --- Certificate / PKCS12 helpers ---

    private static X509Certificate decodeCert(String base64) throws Exception {
        // Server may return raw base64 without PEM headers
        String cleaned = base64.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(cleaned, Base64.DEFAULT);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static void buildClientP12(KeyPair keyPair, X509Certificate signedCert,
                                        X509Certificate ca0, X509Certificate ca1,
                                        File output) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);

        // Build cert chain
        java.util.List<Certificate> chain = new java.util.ArrayList<>();
        chain.add(signedCert);
        if (ca0 != null) chain.add(ca0);
        if (ca1 != null) chain.add(ca1);

        ks.setKeyEntry("client", keyPair.getPrivate(),
                DEFAULT_P12_PASSWORD.toCharArray(),
                chain.toArray(new Certificate[0]));

        FileOutputStream fos = new FileOutputStream(output);
        ks.store(fos, DEFAULT_P12_PASSWORD.toCharArray());
        fos.close();
    }

    private static void buildTrustStoreP12(X509Certificate ca0, X509Certificate ca1,
                                            File output) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);

        if (ca0 != null) {
            ks.setCertificateEntry("ca0", ca0);
        }
        if (ca1 != null) {
            ks.setCertificateEntry("ca1", ca1);
        }

        FileOutputStream fos = new FileOutputStream(output);
        ks.store(fos, DEFAULT_P12_PASSWORD.toCharArray());
        fos.close();
    }

    // --- Utility ---

    private static String parseNameEntry(String xml, String name, String defaultValue) {
        // Parse: <nameEntry name="O" value="TAK"/>
        String search = "name=\"" + name + "\" value=\"";
        int idx = xml.indexOf(search);
        if (idx < 0) return defaultValue;
        idx += search.length();
        int end = xml.indexOf("\"", idx);
        if (end < 0) return defaultValue;
        return xml.substring(idx, end);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static SSLContext createTrustAllSSLContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx;
    }
}
