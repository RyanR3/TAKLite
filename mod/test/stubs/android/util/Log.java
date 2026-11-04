package android.util;

/** Test stand-in: logs go to stdout. */
public final class Log {
    public static boolean isLoggable(String tag, int level) { return false; }
    public static final int DEBUG = 3;
    public static int d(String t, String m) { return out("D", t, m, null); }
    public static int i(String t, String m) { return out("I", t, m, null); }
    public static int w(String t, String m) { return out("W", t, m, null); }
    public static int w(String t, String m, Throwable e) { return out("W", t, m, e); }
    public static int e(String t, String m) { return out("E", t, m, null); }
    public static int e(String t, String m, Throwable e) { return out("E", t, m, e); }
    private static int out(String l, String t, String m, Throwable e) {
        System.out.println(l + "/" + t + ": " + m + (e == null ? "" : " " + e));
        return 0;
    }
}
