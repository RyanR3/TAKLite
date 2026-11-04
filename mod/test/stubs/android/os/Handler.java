package android.os;
/** Test stand-in: runs posted work at once on the calling thread. */
public class Handler {
    public Handler(Looper looper) { }
    public boolean post(Runnable r) { r.run(); return true; }
    public boolean postDelayed(Runnable r, long ms) { return true; }
}
