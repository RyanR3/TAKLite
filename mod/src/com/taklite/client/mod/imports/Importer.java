package com.taklite.client.mod.imports;

import com.taklite.client.mod.ModUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads an imported file into {@link Feature}s. Handles:
 * <ul>
 *   <li>TAK data packages (mission packages): a zip with MANIFEST/manifest.xml
 *       listing .cot, .kml, .kmz, .gpx and attachments such as photos;</li>
 *   <li>KMZ and other zips without a manifest (every supported file inside);</li>
 *   <li>single .kml, .gpx and .cot files;</li>
 *   <li>ATAK map source files (customMapSource XML), on their own or in a zip, which
 *       become TAK Lite map sources rather than map items.</li>
 * </ul>
 * Photos attached to a CoT item are copied into {@code attachmentDir} and linked
 * to that item's marker.
 */
final class Importer {

    private static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    static final class Result {
        String name;
        /** Identifies "the same import" so a re-sent package replaces the old copy. */
        String key;
        final List<Feature> features = new ArrayList<>();
        /** Map sources (ATAK map source XML) found in the file, for TAK Lite's Map Sources. */
        final List<com.taklite.client.mod.CustomMapSources.Imported> mapSources = new ArrayList<>();
        int skipped;
        /** "file: reason" for each file left out, for logs and error messages. */
        final List<String> skippedNotes = new ArrayList<>();

        void skip(String file, String reason) {
            skipped++;
            skippedNotes.add(file + " (" + reason + ")");
        }

        boolean isEmpty() {
            return features.isEmpty() && mapSources.isEmpty();
        }

        int size() {
            return features.size() + mapSources.size();
        }
    }

    private long totalRead;

    private Importer() {
    }

    static Result read(File file, String displayName, File attachmentDir) throws IOException {
        return new Importer().readFile(file, displayName, attachmentDir);
    }

    private Result readFile(File file, String displayName, File attachmentDir) throws IOException {
        Result result = new Result();
        result.name = stripExtension(displayName);
        if (isZip(file)) {
            try (ZipFile zip = new ZipFile(file)) {
                readZip(zip, result, attachmentDir);
            }
            if (result.key == null) result.key = "sha256:" + sha256(file);
        } else {
            result.key = "sha256:" + sha256(file);
            try (InputStream in = new FileInputStream(file)) {
                readDocument(in, displayName, result);
            }
        }
        if (result.isEmpty()) {
            StringBuilder msg = new StringBuilder("No map items or map sources found in " + displayName);
            if (!result.skippedNotes.isEmpty()) {
                msg.append(". Skipped: ");
                for (int i = 0; i < result.skippedNotes.size() && i < 5; i++) {
                    if (i > 0) msg.append("; ");
                    msg.append(result.skippedNotes.get(i));
                }
                if (result.skippedNotes.size() > 5) msg.append("; and ").append(result.skippedNotes.size() - 5).append(" more");
            }
            throw new IOException(msg.toString());
        }
        return result;
    }

    private void readZip(ZipFile zip, Result result, File attachmentDir) throws IOException {
        if (zip.size() > MAX_ENTRIES) throw new IOException("Zip has too many files");
        ZipEntry manifest = null;
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            if (entry.getName().equalsIgnoreCase("MANIFEST/manifest.xml")) manifest = entry;
        }
        if (manifest == null) {
            // KMZ or a plain zip: take every file we understand.
            scanAll(zip, result, new HashSet<String>());
            return;
        }

        XmlNode root;
        try (InputStream in = open(zip, manifest)) {
            root = XmlNode.parse(in);
        }
        XmlNode config = root.child("Configuration");
        if (config != null) {
            String name = param(config, "name");
            String uid = param(config, "uid");
            if (name != null && !name.isEmpty()) {
                result.name = hasFileExtension(name) ? stripExtension(name) : name;
            }
            if (uid != null && !uid.isEmpty()) result.key = "package:" + uid;
        }

        Map<String, Feature> byUid = new HashMap<>();
        List<String[]> attachments = new ArrayList<>(); // {zipEntry, owner uid}
        Set<String> tried = new HashSet<>();
        XmlNode contents = root.child("Contents");
        List<XmlNode> items = contents == null ? new ArrayList<XmlNode>() : contents.children("Content");
        for (XmlNode content : items) {
            if ("true".equalsIgnoreCase(content.attr("ignore"))) continue;
            String listed = content.attr("zipEntry");
            ZipEntry entry = listed == null ? null : findEntry(zip, listed);
            if (entry == null || entry.isDirectory()) {
                result.skip(String.valueOf(listed), "listed in manifest but not in the zip");
                continue;
            }
            String entryName = entry.getName();
            String lower = entryName.toLowerCase(Locale.US);
            if (isGeoFile(lower) || lower.endsWith(".xml") || (!isImage(lower) && looksLikeXml(zip, entry))) {
                tried.add(entryName);
                int before = result.features.size();
                readEntry(zip, entry, result);
                for (int i = before; i < result.features.size(); i++) {
                    Feature f = result.features.get(i);
                    if (f.uid != null) byUid.put(f.uid, f);
                }
            } else if (isImage(lower) && param(content, "uid") != null) {
                attachments.add(new String[]{entryName, param(content, "uid")});
            } else {
                tried.add(entryName);
                result.skip(entryName, "unsupported file type");
            }
        }

        if (result.isEmpty()) {
            // The manifest led nowhere (paths that don't match, unusual names): look at
            // every file in the zip instead, as for a KMZ.
            scanAll(zip, result, tried);
        }

        int n = 0;
        for (String[] a : attachments) {
            Feature owner = byUid.get(a[1]);
            if (owner == null || owner.kind != Feature.POINT || owner.image != null) {
                result.skip(a[0], "photo for an item that isn't a marker in this package");
                continue;
            }
            String ext = a[0].substring(a[0].lastIndexOf('.')).toLowerCase(Locale.US);
            String stored = "photo" + (++n) + ext;
            if (!attachmentDir.isDirectory() && !attachmentDir.mkdirs()) {
                throw new IOException("Could not create " + attachmentDir);
            }
            try (InputStream in = open(zip, zip.getEntry(a[0]));
                 OutputStream out = new FileOutputStream(new File(attachmentDir, stored))) {
                copy(in, out);
            }
            owner.image = stored;
        }
    }

    /**
     * Reads every file in the zip that is, or looks like, KML/KMZ/GPX/CoT, except
     * the manifest and files in {@code tried}.
     */
    private void scanAll(ZipFile zip, Result result, Set<String> tried) throws IOException {
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || tried.contains(name) || name.equalsIgnoreCase("MANIFEST/manifest.xml")) continue;
            String lower = name.toLowerCase(Locale.US);
            if (isGeoFile(lower) || lower.endsWith(".xml") || looksLikeXml(zip, entry)) {
                readEntry(zip, entry, result);
            } else if (!isImage(lower)) {
                result.skip(name, "unsupported file type");
            }
        }
    }

    /** Reads one zip entry, recording (not throwing) why it couldn't be used. */
    private void readEntry(ZipFile zip, ZipEntry entry, Result result) throws IOException {
        int before = result.size();
        int skippedBefore = result.skipped;
        try (InputStream in = open(zip, entry)) {
            readDocument(in, entry.getName(), result);
        } catch (IOException e) {
            if (totalRead > MAX_TOTAL_BYTES) throw e;
            result.skip(entry.getName(), "could not read: " + e.getMessage());
            return;
        }
        if (result.size() == before && result.skipped == skippedBefore) {
            result.skip(entry.getName(), "contains nothing drawable");
        }
    }

    /** Entry by the manifest's path, tolerating "\\", "./", a leading "/" and letter case. */
    private static ZipEntry findEntry(ZipFile zip, String path) {
        ZipEntry exact = zip.getEntry(path);
        if (exact != null) return exact;
        String wanted = normalize(path);
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            if (normalize(entry.getName()).equals(wanted)) return entry;
        }
        return null;
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./") || p.startsWith("/")) p = p.substring(p.startsWith("./") ? 2 : 1);
        return p.toLowerCase(Locale.US);
    }

    /** For files without a telling extension: does it start like an XML document? */
    private boolean looksLikeXml(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] head = new byte[256];
            int n = in.read(head);
            if (n <= 0) return false;
            String start = new String(head, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
            if (start.startsWith("\u00ef\u00bb\u00bf")) start = start.substring(3); // UTF-8 BOM
            return start.startsWith("<");
        } catch (IOException e) {
            return false;
        }
    }

    /** One KML, KMZ, GPX or CoT document. */
    private void readDocument(InputStream input, String name, Result result) throws IOException {
        readDocument(input, name, result, 0);
    }

    /** A KMZ inside a data package is one level of nesting; deeper is never legitimate. */
    private static final int MAX_NESTED_ZIPS = 1;

    private void readDocument(InputStream input, String name, Result result, int depth) throws IOException {
        // Go by the content, not the name: ATAK packages can hold a KMZ (a zip) named ".kml".
        BufferedInputStream raw = new BufferedInputStream(input, 16384);
        raw.mark(4);
        byte[] magic = new byte[4];
        int got = 0;
        int n;
        while (got < 4 && (n = raw.read(magic, got, 4 - got)) > 0) got += n;
        raw.reset();
        boolean zipped = got == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        if (zipped) {
            if (depth >= MAX_NESTED_ZIPS) throw new IOException("zip files nested too deeply in " + name);
            // A KMZ inside a data package: use its first .kml.
            try (ZipInputStream zin = new ZipInputStream(raw)) {
                for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                    if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".kml")) {
                        readDocument(new CountingStream(zin), e.getName(), result, depth + 1);
                        return;
                    }
                }
            }
            throw new IOException("No KML inside " + name);
        }
        XmlNode root = XmlNode.parse(raw);
        switch (root.name) {
            case "kml":
                result.features.addAll(KmlParser.parse(root));
                break;
            case "gpx":
                result.features.addAll(GpxParser.parse(root));
                break;
            case "event": {
                Feature f = CotShapes.toFeature(root);
                if (f != null) result.features.add(f);
                else result.skip(name, "CoT " + root.attr("type") + " has nothing to draw");
                break;
            }
            default:
                if (MapSourceParser.isMapSource(root)) {
                    MapSourceParser.parse(root, baseName(name), result);
                } else {
                    result.skip(name, "<" + root.name + "> is not KML, GPX, CoT or a map source");
                }
        }
    }

    private InputStream open(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException(entry.getName() + " is too large");
        return new CountingStream(new BufferedInputStream(zip.getInputStream(entry)));
    }

    /** Stops zip bombs: caps what all entries together may decompress to. */
    private final class CountingStream extends FilterInputStream {
        private long read;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            totalRead += n;
            if (read > MAX_ENTRY_BYTES || totalRead > MAX_TOTAL_BYTES) {
                throw new IOException("Package expands to too much data");
            }
        }
    }

    /** "imagery/mobile/mapsources/Topo.xml" -> "Topo.xml". */
    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String param(XmlNode node, String name) {
        for (XmlNode p : node.children("Parameter")) {
            if (name.equals(p.attr("name"))) return p.attr("value");
        }
        return null;
    }

    /** Package names are often just the file name they were made from ("Trails.kml", "Trails.zip"). */
    private static boolean hasFileExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".zip") || isGeoFile(lower);
    }

    static boolean isGeoFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".kml") || lower.endsWith(".kmz") || lower.endsWith(".gpx") || lower.endsWith(".cot");
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static boolean isZip(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            return in.read(head) == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        }
    }

    static String stripExtension(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        ModUtil.copy(in, out, Long.MAX_VALUE, "file too large");
    }
}
