package com.taklite.app.tak;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
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

    public static class AlertMessage {
        public String alertId;
        public String senderCallsign;
        public String alertType;
        public String linkedUid;
        public double lat;
        public double lon;
        public double alt;
        public boolean isCancellation;
    }

    public static TakUser parse(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            String cleaned = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (cleaned.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(cleaned));

            String uid = null;
            String type = null;
            long staleTime = 0;
            double lat = 0, lon = 0, alt = 0;
            String callsign = null;
            String team = null;
            String role = null;

            for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("event".equals(tag)) {
                        type = parser.getAttributeValue(null, "type");
                        if (type == null || !type.startsWith("a-")) {
                            return null;
                        }
                        uid = parser.getAttributeValue(null, "uid");
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
            }

            if (uid == null) return null;
            if (lat == 0 && lon == 0) return null;
            if (callsign == null || callsign.isEmpty()) callsign = uid;
            if (team == null) team = "Cyan";
            if (role == null) role = "Team Member";

            return new TakUser(uid, callsign, lat, lon, alt, team, role, staleTime);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse CoT: " + e.getMessage());
            return null;
        }
    }

    public static AlertMessage parseAlert(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            String cleaned = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (cleaned.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(cleaned));

            AlertMessage alert = new AlertMessage();

            for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
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
            }

            if (alert.alertId == null) return null;
            return alert;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse alert: " + e.getMessage());
            return null;
        }
    }

    public static String parseDisconnect(String xml) throws XmlPullParserException, IOException {
        if (xml == null || xml.isEmpty()) return null;
        try {
            String cleaned = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (cleaned.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(cleaned));

            String linkedUid = null;

            for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("event".equals(tag)) {
                        String type = parser.getAttributeValue(null, "type");
                        if (!"t-x-d-d".equals(type)) {
                            return null;
                        }
                    } else if ("link".equals(tag)) {
                        linkedUid = parser.getAttributeValue(null, "uid");
                    }
                }
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
            try {
                SimpleDateFormat altFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                altFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                return altFormat.parse(timeStr).getTime();
            } catch (Exception e2) {
                return System.currentTimeMillis() + 120000;
            }
        }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
