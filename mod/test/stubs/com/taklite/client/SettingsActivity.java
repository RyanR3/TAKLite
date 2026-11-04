package com.taklite.client;

import android.content.Context;

/** Test stand-in: the mock TAK server on this machine, with the generated test certificates. */
public class SettingsActivity {
    private static String dir() { return System.getProperty("taklite.test.dir", "."); }
    public static String getTakServerAddress(Context c) { return "127.0.0.1"; }
    public static String getTakTrustStorePath(Context c) { return dir() + "/certs/truststore.p12"; }
    public static String getTakClientCertPath(Context c) { return dir() + "/certs/client.p12"; }
    public static String getTakCertPassword(Context c) { return "testpass"; }
}
