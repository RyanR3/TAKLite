import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.KeyConfig;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Zip-aligns, signs (APK signature scheme v2 + v3) and verifies an APK with a
 * PKCS12 keystore, using the apksig library bundled inside jadx. Stands in for
 * zipalign + apksigner when the Android SDK build-tools are not installed.
 *
 * Usage: java -cp jadx-all.jar SignApk.java in.apk out.apk keystore.p12 password alias
 */
public class SignApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: SignApk in.apk out.apk keystore.p12 password alias");
            System.exit(2);
        }
        char[] password = args[3].toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[2])) {
            ks.load(in, password);
        }
        PrivateKey key = (PrivateKey) ks.getKey(args[4], password);
        X509Certificate cert = (X509Certificate) ks.getCertificate(args[4]);
        if (key == null || cert == null) throw new IllegalArgumentException("alias not found: " + args[4]);

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "taklite", new KeyConfig.Jca(key), Collections.singletonList(cert)).build();
        File out = new File(args[1]);
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(args[0]))
                .setOutputApk(out)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();

        ApkVerifier.Result result = new ApkVerifier.Builder(out).build().verify();
        if (!result.isVerified()) {
            result.getErrors().forEach(e -> System.err.println("ERROR: " + e));
            throw new IllegalStateException("signature verification failed: " + out);
        }
        System.out.println("Signed and verified " + out);
    }
}
