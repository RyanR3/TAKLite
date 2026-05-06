package com.taklite.app.tak;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class CotBuilder {
    private static final SimpleDateFormat COT_DATE_FORMAT;
    private static final String COT_VERSION = "2.0";
    private static final String PLI_TYPE = "a-f-G-U-C";
    private static final String PLI_HOW = "m-g";
    private static final long STALE_DURATION_MS = 120000;

    static {
        COT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static String buildPLI(String uid, String callsign, String team, String role,
                                   double lat, double lon, double alt,
                                   double bearing, double speed, int battery) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String start = time;
        String stale = formatTime(now + STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(PLI_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"").append(PLI_HOW).append("\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(start).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<__group name=\"").append(escapeXml(team)).append("\"");
        sb.append(" role=\"").append(escapeXml(role)).append("\" />");
        sb.append("<status battery=\"").append(battery).append("\" />");
        sb.append("<track course=\"").append(bearing).append("\"");
        sb.append(" speed=\"").append(speed).append("\" />");
        sb.append("<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\" />");
        sb.append("<takv device=\"TAKLite\" os=\"Android\"");
        sb.append(" platform=\"TAK Lite\" version=\"1.0\" />");
        sb.append("<uid Droid=\"").append(escapeXml(callsign)).append("\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildAlert(String uid, String callsign, String team, String role,
                                     double lat, double lon, double alt, String alertType) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + 300000);
        String alertId = "alert-" + UUID.randomUUID().toString().substring(0, 8);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"b-a-o-tbl\"");
        sb.append(" uid=\"").append(escapeXml(alertId)).append("\"");
        sb.append(" how=\"m-a\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<emergency type=\"").append(escapeXml(alertType)).append("\">");
        sb.append(escapeXml(callsign));
        sb.append("</emergency>");
        sb.append("<link uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("<remarks source=\"").append(escapeXml(uid)).append("\">");
        sb.append(escapeXml(callsign)).append(" has activated ").append(escapeXml(alertType));
        sb.append("</remarks>");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildAlertCancel(String uid, String callsign, String alertId) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"b-a-o-can\"");
        sb.append(" uid=\"").append(escapeXml(alertId)).append("\"");
        sb.append(" how=\"m-a\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<emergency cancel=\"true\">");
        sb.append(escapeXml(callsign));
        sb.append("</emergency>");
        sb.append("<link uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildMarker(String senderUid, String senderCallsign, String markerUid,
                                      String affiliation, double lat, double lon, double alt,
                                      String name, String remarks) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + 600000); // 10 minutes

        String cotType;
        switch (affiliation.toLowerCase()) {
            case "hostile":  cotType = "a-h-G"; break;
            case "unknown":  cotType = "a-u-G"; break;
            case "neutral":  cotType = "a-n-G"; break;
            case "friendly":
            default:         cotType = "a-f-G"; break;
        }

        String callsign = (name != null && !name.isEmpty()) ? name : affiliation;
        String remarksText = (remarks != null && !remarks.isEmpty()) ? remarks
                : "Dropped by " + senderCallsign;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(cotType).append("\"");
        sb.append(" uid=\"").append(escapeXml(markerUid)).append("\"");
        sb.append(" how=\"h-g-i-g-o\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        sb.append("<remarks source=\"").append(escapeXml(senderUid)).append("\">");
        sb.append(escapeXml(remarksText));
        sb.append("</remarks>");
        sb.append("<link uid=\"").append(escapeXml(senderUid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("<precisionlocation geopointsrc=\"Human\" altsrc=\"DTED0\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String generateUid() {
        return "TAKLite-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String formatTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
