package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Test stand-in: a folder for files and in-memory preferences. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final File base;
    private final Map<String, SharedPreferences> prefs = new HashMap<>();

    public Context(File base) { this.base = base; }
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { File f = new File(base, "files"); f.mkdirs(); return f; }
    public File getCacheDir() { File f = new File(base, "cache"); f.mkdirs(); return f; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, n -> new MemPrefs());
    }
    public Object getSystemService(Class<?> c) { return null; }
    public android.content.res.AssetManager getAssets() { return null; }

    static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        public String getString(String k, String d) { return m.containsKey(k) ? (String) m.get(k) : d; }
        public boolean getBoolean(String k, boolean d) { return m.containsKey(k) ? (Boolean) m.get(k) : d; }
        public int getInt(String k, int d) { return m.containsKey(k) ? (Integer) m.get(k) : d; }
        public Editor edit() {
            return new Editor() {
                public Editor putString(String k, String v) { m.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
                public Editor putInt(String k, int v) { m.put(k, v); return this; }
                public void apply() { }
            };
        }
    }
}
