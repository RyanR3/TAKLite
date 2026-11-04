package com.taklite.client.mod.imports;

import com.taklite.client.mod.CustomMapSources;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unit tests for the import code: XML hardening, the parsers, the data package
 * reader (manifest edge cases), ATAK map source conversion, the local-network
 * rule for http shares. Plain JVM with the stand-ins in test/stubs.
 *
 * Usage: ImportTests FIXTURE_WORKDIR   (from mock/make_fixtures.py)
 */
public class ImportTests {

    private static int failures;
    private static File work;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        work = new File(args[0]);
        xmlGuards();
        parsers();
        packages();
        mapSources();
        localNetwork();
        originText();
        System.out.println(failures == 0 ? "ImportTests: ALL OK" : "ImportTests: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- Data Packages list: the "where it came from" line ----

    private static void originText() {
        check("origin: server with uploader", "From server \u00b7 uploaded by someone@example"
                .equals(ImportStore.Entry.originText("Server, uploaded by someone@example")));
        check("origin: server alone", "From server".equals(ImportStore.Entry.originText("Server")));
        check("origin: sent over TAK", "Sent over TAK by Alpha"
                .equals(ImportStore.Entry.originText("Data package from Alpha")));
        check("origin: file", "Imported from a file".equals(ImportStore.Entry.originText("File")));
        check("origin: anything else as is", "Other".equals(ImportStore.Entry.originText("Other")));
        check("origin: empty or missing", ImportStore.Entry.originText("").isEmpty()
                && ImportStore.Entry.originText(null).isEmpty());
    }

    private static String parse(String xml) {
        return parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String parse(byte[] bytes) {
        try {
            return "OK:" + XmlNode.parse(new ByteArrayInputStream(bytes)).name;
        } catch (IOException e) {
            return "ERR:" + e.getMessage();
        } catch (Throwable t) {
            return "THROW:" + t;
        }
    }

    // ---- XmlNode: what a hostile file may not do ----

    private static void xmlGuards() {
        check("plain kml parses", parse("<?xml version=\"1.0\"?><kml><Document/></kml>").equals("OK:kml"));
        check("BOM and a comment before the root", parse("﻿<!-- hi <!DOCTYPE x [ --><kml/>").equals("OK:kml"));
        check("plain DOCTYPE allowed", parse("<?xml version='1.0'?>\n<!DOCTYPE gpx SYSTEM \"gpx.dtd\">\n<gpx/>").equals("OK:gpx"));
        check("DOCTYPE with quoted bracket allowed", parse("<!DOCTYPE gpx SYSTEM \"a[b>c\"><gpx/>").equals("OK:gpx"));
        check("internal DTD subset refused", parse("<!DOCTYPE kml [<!ENTITY a \"x\">]><kml>&a;</kml>").startsWith("ERR:XML files with a DOCTYPE"));
        StringBuilder big = new StringBuilder("<!--");
        for (int i = 0; i < 20000; i++) big.append("padding ");
        big.append("-->\n<!DOCTYPE kml [<!ENTITY a \"x\">]><kml>&a;</kml>");
        check("DTD subset after a 160 KB comment refused", parse(big.toString()).startsWith("ERR:XML files with a DOCTYPE"));
        check("UTF-16 DTD subset refused", parse("<!DOCTYPE kml [<!ENTITY a \"x\">]><kml/>".getBytes(StandardCharsets.UTF_16)).startsWith("ERR:XML files with a DOCTYPE"));
        check("UTF-16 plain file parses", parse("<kml><a/></kml>".getBytes(StandardCharsets.UTF_16)).equals("OK:kml"));
        StringBuilder huge = new StringBuilder("<!--");
        for (int i = 0; i < 200000; i++) huge.append("padding ");
        huge.append("--><kml/>");
        check("more than 1 MB before the root refused", parse(huge.toString()).startsWith("ERR:Not a usable XML"));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100000; i++) deep.append("<Folder>");
        check("100,000 levels of nesting refused", parse("<kml>" + deep + "</kml>").equals("ERR:XML nested too deeply"));
        StringBuilder ok = new StringBuilder("<kml>");
        for (int i = 0; i < 100; i++) ok.append("<Folder>");
        for (int i = 0; i < 100; i++) ok.append("</Folder>");
        check("100 levels of nesting fine", parse(ok + "</kml>").equals("OK:kml"));
        check("empty document is an error, not a crash", parse("").startsWith("ERR:"));
        String truncated = parse("<kml><Document><Placemark>");
        check("truncated document is read as far as it goes or refused, never a crash",
                truncated.startsWith("OK:") || truncated.startsWith("ERR:"));
    }

    // ---- Parsers on the single-file fixtures ----

    private static Importer.Result read(String path) throws IOException {
        File f = new File(work, path);
        return Importer.read(f, f.getName(), new File(work, "attach"));
    }

    private static void parsers() throws Exception {
        Importer.Result kml = read("single/trails.kml");
        check("KML: line, polygon and point found; ATAK 'center' label skipped", kml.features.size() == 4);
        Feature line = kml.features.get(0);
        check("KML: shared style colour aabbggrr -> ARGB", line.strokeColor == 0xFFFF0000 && line.strokeWidth == 4f);
        check("KML: HTML description reduced to text", line.remarks.equals("Steep\nLoose rock"));
        Feature poly = kml.features.get(1);
        check("KML: polygon fill kept translucent", poly.kind == Feature.POLYGON && (poly.fillColor >>> 24) <= 0x55 && (poly.fillColor >>> 24) > 0);
        check("KML: colour helper", KmlParser.kmlColor("7f00ff00") == 0x7F00FF00 && KmlParser.kmlColor("zz") == null);

        Importer.Result gpx = read("single/track.gpx");
        check("GPX: waypoint, track and route", gpx.features.size() == 3);
        Feature walk = null;
        for (Feature f : gpx.features) if (f.name.equals("Walk")) walk = f;
        check("GPX: Garmin DisplayColor applied to the track", walk != null && walk.strokeColor == 0xFF8B0000);
        check("GPX: waypoint desc kept", gpx.features.get(0).remarks.equals("Locked"));
        check("GPX: hex colour helper", GpxParser.hexColor("#ff0000") == 0xFFFF0000 && GpxParser.hexColor("#80ff0000") == 0x80FF0000);

        Importer.Result cot = read("single/marker.cot");
        check("CoT marker: one point with type, icon and name", cot.features.size() == 1
                && "a-h-G".equals(cot.features.get(0).cotType)
                && "COT_MAPPING_2525C/a-h/a-h-G".equals(cot.features.get(0).iconsetPath)
                && "Checkpoint 1".equals(cot.features.get(0).name));
        Feature closed = CotShapes.toFeature(XmlNode.parse(new ByteArrayInputStream(
                ("<event type='u-d-f' uid='s'><point lat='1' lon='1'/><detail><link point='1,1'/><link point='1,2'/>"
                        + "<link point='2,2'/><link point='1,1'/><fillColor value='1090453504'/></detail></event>").getBytes(StandardCharsets.UTF_8))));
        check("CoT closed freehand -> polygon with fill", closed != null && closed.kind == Feature.POLYGON && closed.fillColor == 1090453504);
        Feature route = CotShapes.toFeature(XmlNode.parse(new ByteArrayInputStream(
                "<event type='b-m-r' uid='r'><point lat='1' lon='1'/><detail><link point='1,1'/><link point='1,2'/><link uid='x' relation='p-p'/></detail></event>".getBytes(StandardCharsets.UTF_8))));
        check("CoT route -> line; parent link without point ignored", route != null && route.kind == Feature.LINE && route.pointCount() == 2);
        Feature circle = CotShapes.toFeature(XmlNode.parse(new ByteArrayInputStream(
                "<event type='u-d-c-c' uid='c'><point lat='50' lon='10'/><detail><shape><ellipse major='100' minor='100' angle='0'/></shape></detail></event>".getBytes(StandardCharsets.UTF_8))));
        check("CoT circle -> 73-point polygon", circle != null && circle.kind == Feature.POLYGON && circle.pointCount() == 73);
        Feature transfer = CotShapes.toFeature(XmlNode.parse(new ByteArrayInputStream(
                "<event type='b-f-t-r' uid='t'><point lat='0' lon='0'/><detail/></event>".getBytes(StandardCharsets.UTF_8))));
        check("CoT file transfer is not a map item", transfer == null);
        Feature pli = CotShapes.toFeature(XmlNode.parse(new ByteArrayInputStream(
                "<event type='a-f-G-U-C' uid='p'><point lat='91' lon='0'/><detail/></event>".getBytes(StandardCharsets.UTF_8))));
        check("CoT with invalid latitude dropped", pli == null);

        Feature f = Feature.path(Feature.LINE, java.util.Arrays.asList(new double[]{1.123456789, 2}, new double[]{3, 4}));
        f.name = "n";
        Feature back = Feature.fromJson(f.toJson());
        check("Feature JSON round trip (coords to 1e-7)", back.kind == Feature.LINE && back.pointCount() == 2
                && Math.abs(back.lat(0) - 1.1234568) < 1e-9 && "n".equals(back.name));
        check("validLatLon", Feature.validLatLon(90, 180) && !Feature.validLatLon(90.1, 0) && !Feature.validLatLon(Double.NaN, 0));
    }

    // ---- Data packages: the manifest edge cases real packages showed ----

    private static String tryRead(String path) {
        try {
            Importer.Result r = read(path);
            return "features=" + r.features.size() + " sources=" + r.mapSources.size() + " key=" + r.key + " name=" + r.name;
        } catch (IOException e) {
            return "ERR:" + e.getMessage();
        }
    }

    private static void packages() throws Exception {
        String pkg = tryRead("package.zip");
        check("package: all four files read (" + pkg + ")", pkg.startsWith("features=") && pkg.contains("features=9 ") && pkg.contains("key=package:pkg-uid-0001") && pkg.contains("name=TestArea"));
        check("A normal", tryRead("edge/A_normal.zip").startsWith("features=4 "));
        check("B backslash manifest paths", tryRead("edge/B_backslash.zip").startsWith("features=4 "));
        check("C manifest path doesn't match: fallback scan", tryRead("edge/C_wrongpath.zip").startsWith("features=4 "));
        check("D nothing drawable: clear error", tryRead("edge/D_nothing.zip").startsWith("ERR:No map items or map sources found"));
        check("E KML without extension", tryRead("edge/E_noext.zip").startsWith("features=4 "));
        check("F KMZ stored as .kml", tryRead("edge/F_kmz_named_kml.zip").startsWith("features=4 "));
        String nested = tryRead("edge/G_nested.zip");
        check("G zip nested three deep refused, no crash (" + nested + ")", nested.startsWith("ERR:") && nested.contains("nested too deeply"));
        check("package name loses .zip", tryRead("edge/A_normal.zip").endsWith("name=Trails"));
        check("stripExtension", Importer.stripExtension("x/Trails.kml").equals("Trails") && Importer.stripExtension("noext").equals("noext"));
    }

    // ---- ATAK map sources ----

    private static void mapSources() throws Exception {
        Importer.Result r = read("mappack.zip");
        check("map pack: 4 sources converted, 3 skipped with reasons (" + r.mapSources.size() + "/" + r.skipped + ")",
                r.mapSources.size() == 4 && r.skipped == 3 && r.features.isEmpty());
        String urls = "";
        for (CustomMapSources.Imported s : r.mapSources) urls += s.name + " => " + s.url + " z" + s.minZoom + "-" + s.maxZoom + "\n";
        check("{$x}{$y}{$z} -> {x}{y}{z}", urls.contains("https://tiles.example.test/basic/{z}/{x}/{y}.png z0-19"));
        check("{$q} -> {q}, entities decoded", urls.contains("https://quad.example.test/tiles/a{q}?g=1&mkt=en-us z1-20"));
        check("{$serverpart} + invertY -> {s:a,b,c} and {-y}", urls.contains("https://{s:a,b,c}.tms.example.test/{z}/{x}/{-y}.png z2-17"));
        check("ArcGIS z/y/x order kept", urls.contains("/tile/{z}/{y}/{x} z0-15"));
        String skips = String.join("; ", r.skippedNotes);
        check("WMS skipped", skips.contains("skip_wms.xml (WMS"));
        check("multi-layer skipped", skips.contains("skip_multilayer.xml (multi-layer"));
        check("EPSG:4326 skipped", skips.contains("skip_epsg4326.xml (uses EPSG:4326"));
        for (CustomMapSources.Imported s : r.mapSources) check("imported source valid: " + s.name, s.problem() == null);
        CustomMapSources.Imported bad = new CustomMapSources.Imported();
        bad.url = "ftp://x/{z}/{x}/{y}";
        check("problem(): scheme", bad.problem() != null);
        bad.url = "https://x/{z}/{x}";
        check("problem(): template", bad.problem() != null);
        bad.url = "https://x/{z}/{x}/{y}";
        bad.minZoom = 5;
        bad.maxZoom = 3;
        check("problem(): zoom range", bad.problem() != null);
    }

    // ---- http shares: local network only ----

    private static void localNetwork() {
        check("192.168.x local", DataPackages.isLocalNetwork("192.168.1.20"));
        check("10.x local", DataPackages.isLocalNetwork("10.0.0.5"));
        check("172.20.x local", DataPackages.isLocalNetwork("172.20.1.1"));
        check("172.32.x not local", !DataPackages.isLocalNetwork("172.32.1.1")); // s6-allow: first address past 172.16/12
        check("public address not local", !DataPackages.isLocalNetwork("203.0.113.10"));
        check("carrier-grade NAT (100.64/10) not local", !DataPackages.isLocalNetwork("100.64.1.1"));
        check("IPv6 unique local (fd00::/8) local", DataPackages.isLocalNetwork("fd00::1"));
        check("link-local local", DataPackages.isLocalNetwork("169.254.10.1"));
        check("loopback local", DataPackages.isLocalNetwork("127.0.0.1"));
        check("empty host not local", !DataPackages.isLocalNetwork(""));
        List<DataPackages.ServerPackage> list;
        try {
            list = DataPackages.parseServerList("{\"resultCount\":2,\"results\":[{\"Name\":\"A\",\"Hash\":\"abc\",\"Size\":10,\"SubmissionUser\":\"u\"},{\"Name\":\"nohash\"}]}");
            check("server list parsed; entries without a hash dropped", list.size() == 1 && list.get(0).name.equals("A") && list.get(0).size == 10);
        } catch (IOException e) {
            check("server list parsed", false);
        }
        try {
            DataPackages.parseServerList("not json");
            check("bad server answer is an IOException", false);
        } catch (IOException e) {
            check("bad server answer is an IOException", true);
        }
    }
}
