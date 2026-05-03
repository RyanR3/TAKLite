package com.taklite.app.tak;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public class CotParser {

    private static final String TAG = "CotParser";

    private static final SimpleDateFormat COT_DATE_FORMAT;
    static {
        COT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Parse a CoT XML event string into a TakUser.
     * Returns null if not a position event or parsing fails.
     */
    public static TakUser parse(String xml) {
        if (xml == null || xml.isEmpty()) return null;

        try {
            // Strip XML declaration(s) — TAK servers prepend <?xml ...?> which
            // causes XmlPullParser to fail when it appears after buffering
            xml = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (xml.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            String uid = null;
            String type = null;
            long staleTime = 0;
            double lat = 0, lon = 0, alt = 0;
            String callsign = null;
            String team = null;
            String role = null;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();

                    if ("event".equals(tag)) {
                        uid = parser.getAttributeValue(null, "uid");
                        type = parser.getAttributeValue(null, "type");

                        // Only process position atoms (a-*), skip protocol (t-x-*) and bits (b-*)
                        if (type == null || !type.startsWith("a-")) {
                            return null;
                        }

                        String staleStr = parser.getAttributeValue(null, "stale");
                        if (staleStr != null) {
                            staleTime = parseTime(staleStr);
                        }

                    } else if ("point".equals(tag)) {
                        lat = parseDouble(parser.getAttributeValue(null, "lat"));
                        lon = parseDouble(parser.getAttributeValue(null, "lon"));
                        alt = parseDouble(parser.getAttributeValue(null, "hae"));

                    } else if ("contact".equals(tag)) {
                        callsign = parser.getAttributeValue(null, "callsign");

                    } else if ("__group".equals(tag)) {
                        team = parser.getAttributeValue(null, "name");
                        role = parser.getAttributeValue(null, "role");
                    }
                }
                eventType = parser.next();
            }

            // Validate minimum required fields
            if (uid == null || lat == 0 && lon == 0) {
                return null;
            }

            // Use uid as callsign fallback
            if (callsign == null || callsign.isEmpty()) {
                callsign = uid;
            }
            if (team == null) team = "Cyan";
            if (role == null) role = "Team Member";

            return new TakUser(uid, callsign, lat, lon, alt, team, role, staleTime);

        } catch (Exception e) {
            Log.w(TAG, "Failed to parse CoT: " + e.getMessage());
            return null;
        }
    }

    // --- Alert parsing ---

    public static class AlertMessage {
        public String alertId;
        public String senderCallsign;
        public String alertType;
        public double lat, lon, alt;
        public boolean isCancellation;
        public String linkedUid; // sender's PLI uid from <link>
    }

    /**
     * Parse a CoT XML alert event (b-a-o-tbl or b-a-o-can).
     * Returns null if not an alert event.
     */
    public static AlertMessage parseAlert(String xml) {
        if (xml == null || xml.isEmpty()) return null;

        try {
            xml = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (xml.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            AlertMessage alert = new AlertMessage();

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();

                    if ("event".equals(tag)) {
                        String type = parser.getAttributeValue(null, "type");
                        if (type == null || (!type.equals("b-a-o-tbl") && !type.equals("b-a-o-can"))) {
                            return null;
                        }
                        alert.isCancellation = "b-a-o-can".equals(type);
                        alert.alertId = parser.getAttributeValue(null, "uid");

                    } else if ("point".equals(tag)) {
                        alert.lat = parseDouble(parser.getAttributeValue(null, "lat"));
                        alert.lon = parseDouble(parser.getAttributeValue(null, "lon"));
                        alert.alt = parseDouble(parser.getAttributeValue(null, "hae"));

                    } else if ("contact".equals(tag)) {
                        alert.senderCallsign = parser.getAttributeValue(null, "callsign");

                    } else if ("emergency".equals(tag)) {
                        alert.alertType = parser.getAttributeValue(null, "type");

                    } else if ("link".equals(tag)) {
                        String linkType = parser.getAttributeValue(null, "type");
                        if ("a-f-G-U-C".equals(linkType)) {
                            alert.linkedUid = parser.getAttributeValue(null, "uid");
                        }
                    }
                }
                eventType = parser.next();
            }

            if (alert.alertId == null) return null;
            return alert;

        } catch (Exception e) {
            Log.w(TAG, "Failed to parse alert: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse a t-x-d-d disconnect CoT and return the UID of the disconnecting user.
     * Returns null if not a disconnect event.
     */
    public static String parseDisconnect(String xml) {
        if (xml == null || xml.isEmpty()) return null;

        try {
            xml = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (xml.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            String linkedUid = null;
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("event".equals(tag)) {
                        String type = parser.getAttributeValue(null, "type");
                        if (!"t-x-d-d".equals(type)) return null;
                    } else if ("link".equals(tag)) {
                        linkedUid = parser.getAttributeValue(null, "uid");
                    }
                }
                eventType = parser.next();
            }
            return linkedUid;
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseTime(String timeStr) {
        try {
            synchronized (COT_DATE_FORMAT) {
                return COT_DATE_FORMAT.parse(timeStr).getTime();
            }
        } catch (Exception e) {
            // Try without millis (some servers send without)
            try {
                SimpleDateFormat altFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                altFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                return altFormat.parse(timeStr).getTime();
            } catch (Exception e2) {
                return System.currentTimeMillis() + 120000; // default 2 min stale
            }
        }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
