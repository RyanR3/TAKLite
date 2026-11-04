package com.taklite.client.mod.imports;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny read-only XML tree for KML, GPX, CoT and data package manifests.
 * Element names keep only their local part ("gx:Track" becomes "Track") so
 * callers don't depend on how a file spells its namespace prefixes.
 */
final class XmlNode {

    /** Guards against pathological files; real KML/GPX/CoT is far below these. */
    private static final int MAX_NODES = 2_000_000;
    /** Code walking the tree recurses once per level, so a deeper file could overflow the stack. */
    private static final int MAX_DEPTH = 500;
    /** Comments and processing instructions allowed before the root element. */
    private static final int MAX_PROLOG = 1 << 20;

    final String name;
    final Map<String, String> attrs;
    final List<XmlNode> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    private XmlNode(String name, Map<String, String> attrs) {
        this.name = name;
        this.attrs = attrs;
    }

    /** An element with no attributes or children, e.g. a stand-in for a missing {@code <detail/>}. */
    static XmlNode empty(String name) {
        return new XmlNode(name, Collections.<String, String>emptyMap());
    }

    static XmlNode parse(InputStream input) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input, 16384);
        rejectInternalDtd(in);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(in, null);
            List<XmlNode> stack = new ArrayList<>();
            XmlNode root = null;
            int count = 0;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    if (++count > MAX_NODES) throw new IOException("XML file too large");
                    Map<String, String> attrs = parser.getAttributeCount() == 0
                            ? Collections.<String, String>emptyMap() : new HashMap<String, String>();
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        attrs.put(localName(parser.getAttributeName(i)), parser.getAttributeValue(i));
                    }
                    XmlNode node = new XmlNode(localName(parser.getName()), attrs);
                    if (stack.isEmpty()) {
                        root = node;
                    } else {
                        stack.get(stack.size() - 1).children.add(node);
                    }
                    stack.add(node);
                    if (stack.size() > MAX_DEPTH) throw new IOException("XML nested too deeply");
                } else if (event == XmlPullParser.END_TAG) {
                    stack.remove(stack.size() - 1);
                } else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && !stack.isEmpty()) {
                    stack.get(stack.size() - 1).text.append(parser.getText());
                }
            }
            if (root == null) throw new IOException("Empty XML document");
            return root;
        } catch (XmlPullParserException e) {
            throw new IOException("Not valid XML: " + e.getMessage(), e);
        }
    }

    /**
     * Entity declarations can expand exponentially ("billion laughs"), and none of the
     * formats we read use them. They can only appear in a DOCTYPE's internal subset
     * ({@code <!DOCTYPE x [ ... ]>}), which must come before the root element, so read
     * up to the root element and refuse such a DOCTYPE. A plain DOCTYPE is fine: the
     * parser never fetches external DTDs.
     */
    private static void rejectInternalDtd(BufferedInputStream in) throws IOException {
        in.mark(MAX_PROLOG);
        try {
            byte[] buf = new byte[8192];
            int n = 0;
            while (true) {
                int r = in.read(buf, n, buf.length - n);
                if (r > 0) n += r;
                Boolean ok = scanProlog(buf, n);
                if (ok != null) {
                    if (!ok) throw new IOException("XML files with a DOCTYPE declaration are not supported");
                    return;
                }
                if (r < 0) return; // ends before the root element; the parser reports that
                if (n == buf.length) {
                    if (buf.length == MAX_PROLOG) throw new IOException("Not a usable XML file (too much before the first element)");
                    buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                }
            }
        } finally {
            in.reset();
        }
    }

    /**
     * Looks at the start of an XML file: TRUE once the root element starts, FALSE at a DOCTYPE
     * with an internal subset, null if more bytes are needed. Zero bytes are skipped so the
     * markup is also found in UTF-16 files.
     */
    static Boolean scanProlog(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (bytes[i] != 0) sb.append((char) (bytes[i] & 0xFF));
        }
        String s = sb.toString();
        int i = 0;
        while (true) {
            i = s.indexOf('<', i);
            if (i < 0 || i + 1 >= s.length()) return null;
            int end;
            if (s.startsWith("<?", i)) {
                end = s.indexOf("?>", i + 2);
                if (end < 0) return null;
                i = end + 2;
            } else if (s.startsWith("<!--", i)) {
                end = s.indexOf("-->", i + 4);
                if (end < 0) return null;
                i = end + 3;
            } else if (s.charAt(i + 1) == '!') {
                if (s.length() - i < 9) return null;
                if (!s.startsWith("<!DOCTYPE", i)) return Boolean.FALSE;
                // Up to the closing '>', skipping quoted ids, which may contain '[' or '>'.
                int close = -1;
                char quote = 0;
                for (int j = i + 9; j < s.length() && close < 0; j++) {
                    char c = s.charAt(j);
                    if (quote != 0) {
                        if (c == quote) quote = 0;
                    } else if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '[') {
                        return Boolean.FALSE;
                    } else if (c == '>') {
                        close = j;
                    }
                }
                if (close < 0) return null;
                i = close + 1;
            } else {
                return Boolean.TRUE;
            }
        }
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    String text() {
        return text.toString().trim();
    }

    String attr(String key) {
        return attrs.get(key);
    }

    /** First direct child with this name, or null. */
    XmlNode child(String childName) {
        for (XmlNode c : children) {
            if (c.name.equals(childName)) return c;
        }
        return null;
    }

    /** Text of the first direct child with this name, or null. */
    String childText(String childName) {
        XmlNode c = child(childName);
        return c == null ? null : c.text();
    }

    List<XmlNode> children(String childName) {
        List<XmlNode> out = new ArrayList<>();
        for (XmlNode c : children) {
            if (c.name.equals(childName)) out.add(c);
        }
        return out;
    }

    /** All descendants (depth-first, document order) with this name. */
    List<XmlNode> descendants(String descendantName) {
        List<XmlNode> out = new ArrayList<>();
        collect(descendantName, out);
        return out;
    }

    private void collect(String descendantName, List<XmlNode> out) {
        for (XmlNode c : children) {
            if (c.name.equals(descendantName)) out.add(c);
            c.collect(descendantName, out);
        }
    }
}
