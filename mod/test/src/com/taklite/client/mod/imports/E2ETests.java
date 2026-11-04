package com.taklite.client.mod.imports;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * End-to-end: data packages against the mock TAK server (mutual TLS on HTTPS_PORT)
 * and the plain http server (HTTP_PORT), through the same code path a received
 * b-f-t-r CoT takes. Checks the download rules: https imports on its own, http is
 * accepted from the local network only after the user agrees, everything else is
 * refused; repeats are ignored; redirects aren't followed; a package tapped twice
 * in the server list is downloaded once.
 *
 * Usage: E2ETests FIXTURE_WORKDIR HTTPS_PORT HTTP_PORT   (HTTPS_PORT must be 8443 for the server-list part)
 */
public class E2ETests {

    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failures++;
    }

    private static String cot(String url, String sha, String uid) {
        return "<event version='2.0' uid='" + uid + "' type='b-f-t-r' how='h-e'><point lat='0' lon='0'/><detail>"
                + "<fileshare filename='TestArea.zip' senderUrl='" + url + "' sizeInBytes='100'"
                + (sha == null ? "" : " sha256='" + sha + "'") + " senderCallsign='TEST-1' name='TestArea'/></detail></event>";
    }

    private static void waitForImports(int n, int seconds) throws Exception {
        for (int i = 0; i < seconds * 10 && Imports.list().size() < n; i++) Thread.sleep(100);
        Thread.sleep(800);
    }

    @SuppressWarnings("unchecked")
    private static int inFlight() throws Exception {
        Field f = DataPackages.class.getDeclaredField("inFlight");
        f.setAccessible(true);
        return ((Map<String, ?>) f.get(null)).size();
    }

    @SuppressWarnings("unchecked")
    private static int waitingForScreen() throws Exception {
        Field f = Imports.class.getDeclaredField("waitingForScreen");
        f.setAccessible(true);
        return ((List<?>) f.get(null)).size();
    }

    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        int httpsPort = Integer.parseInt(args[1]);
        int httpPort = Integer.parseInt(args[2]);
        String sha = new String(Files.readAllBytes(new File(work, "package.sha256").toPath())).trim();
        File phone = new File(work, "phone");
        ImportStore.deleteTree(phone);
        Context ctx = new Context(phone);
        Imports.init(ctx);
        // The app talks to the TAK Server API on 8443 (a compile-time constant), so the
        // mock server listens there; the share URLs below name the port explicitly anyway.

        System.out.println("--- https share: imports on its own");
        Imports.onCotReceived(cot("https://127.0.0.1:" + httpsPort + "/Marti/sync/content?hash=" + sha, sha, "s1"));
        waitForImports(1, 30);
        check("imported with 9 items", Imports.list().size() == 1 && Imports.list().get(0).count == 9);
        check("nothing left in flight", inFlight() == 0);
        check("package remembered as received", DataPackages.isImported(ctx, sha));

        System.out.println("--- same share again: ignored while the import exists");
        Imports.onCotReceived(cot("https://127.0.0.1:" + httpsPort + "/Marti/sync/content?hash=" + sha, sha, "s1"));
        Thread.sleep(1500);
        check("still one import", Imports.list().size() == 1);

        System.out.println("--- wrong hash: refused, nothing added");
        String badSha = sha.replace(sha.charAt(0), (char) (sha.charAt(0) == 'a' ? 'b' : 'a'));
        Imports.onCotReceived(cot("https://127.0.0.1:" + httpsPort + "/Marti/sync/content?hash=" + sha, badSha, "s2"));
        Thread.sleep(4000);
        check("corrupt download not imported", Imports.list().size() == 1 && inFlight() == 0);

        System.out.println("--- unresolvable advertised host: retried against the configured server");
        Imports.delete(Imports.list().get(0).id);
        Imports.onCotReceived(cot("https://takserver.internal.invalid:" + httpsPort + "/Marti/sync/content?hash=" + sha, sha, "s3"));
        waitForImports(1, 40);
        check("imported via the configured server", Imports.list().size() == 1);

        System.out.println("--- http share from the internet: refused");
        Imports.onCotReceived(cot("http://203.0.113.10:8080/getfile?file=x", null, "s4"));
        Thread.sleep(1500);
        check("not downloaded", Imports.list().size() == 1 && inFlight() == 0);

        System.out.println("--- http share from the local network: waits for the user");
        Imports.onCotReceived(cot("http://127.0.0.1:" + httpPort + "/package.zip", null, "s5"));
        Thread.sleep(1500);
        check("queued for the map screen, nothing downloaded", Imports.list().size() == 1 && inFlight() == 1 && waitingForScreen() == 1);
        Imports.onCotReceived(cot("http://127.0.0.1:" + httpPort + "/package.zip", null, "s5"));
        Thread.sleep(800);
        check("repeat ignored while waiting", waitingForScreen() == 1);

        System.out.println("--- ftp link: refused");
        Imports.onCotReceived(cot("ftp://127.0.0.1/x.zip", null, "s6"));
        Thread.sleep(500);
        check("ftp not downloaded", Imports.list().size() == 1);

        System.out.println("--- the http download itself (what DOWNLOAD runs)");
        Method dl = DataPackages.class.getDeclaredMethod("download", Context.class, String.class, File.class);
        dl.setAccessible(true);
        File out = new File(phone, "http.zip");
        dl.invoke(null, ctx, "http://127.0.0.1:" + httpPort + "/package.zip", out);
        check("downloaded and intact", Importer.sha256(out).equals(sha));
        try {
            dl.invoke(null, ctx, "http://127.0.0.1:" + httpPort + "/redirect", out);
            check("http redirect not followed", false);
        } catch (InvocationTargetException e) {
            check("http redirect not followed (" + e.getCause().getMessage() + ")", e.getCause().getMessage().contains("302"));
        }

        System.out.println("--- server list and a package tapped twice");
        List<DataPackages.ServerPackage> list = DataPackages.queryServer(ctx);
        check("server lists two packages", list.size() == 2);
        DataPackages.ServerPackage area = null;
        DataPackages.ServerPackage maps = null;
        for (DataPackages.ServerPackage p : list) {
            if (p.name.equals("TestArea")) area = p;
            if (p.name.equals("TestMapPack")) maps = p;
        }
        check("listed package shows as imported", area != null && DataPackages.isImported(ctx, area.hash));
        Imports.delete(Imports.list().get(0).id);
        final int[] results = new int[1];
        DataPackages.importFromServer(ctx, area, (e, err) -> results[0]++);
        DataPackages.importFromServer(ctx, area, (e, err) -> results[0]++);
        waitForImports(1, 30);
        Thread.sleep(500);
        check("both taps told, one download", results[0] == 2 && Imports.list().size() == 1);

        System.out.println("--- map pack from the server");
        final String[] err = new String[1];
        DataPackages.importFromServer(ctx, maps, (e, error) -> err[0] = error);
        waitForImports(2, 30);
        ImportStore.Entry pack = null;
        for (ImportStore.Entry e : Imports.list()) if (e.name.equals("TestMapPack")) pack = e;
        check("map pack imported as 4 map sources, 0 items (" + err[0] + ")", pack != null && pack.mapSources == 4 && pack.count == 0);
        check("map sources stored in preferences",
                ctx.getSharedPreferences("TAKLitePrefs", 0).getString("custom_map_sources", "[]").contains("Test - Quadkey"));

        System.out.println("--- visibility and delete");
        ImportStore.Entry first = Imports.list().get(0);
        Imports.setVisible(first.id, false);
        check("hidden", !Imports.store().get(first.id).visible);
        Imports.delete(first.id);
        check("deleted", Imports.store().get(first.id) == null);

        System.out.println(failures == 0 ? "E2ETests: ALL OK" : "E2ETests: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
