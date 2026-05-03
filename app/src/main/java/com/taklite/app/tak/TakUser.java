package com.taklite.app.tak;

public class TakUser {
    private String uid;
    private String callsign;
    private double lat;
    private double lon;
    private double alt;
    private String team;
    private String role;
    private long staleTime;      // epoch millis when this user goes stale
    private long lastUpdateTime; // epoch millis of last update
    private String emergencyType; // null = no emergency
    private boolean emergencyActive;

    public TakUser(String uid, String callsign, double lat, double lon, double alt,
                   String team, String role, long staleTime) {
        this.uid = uid;
        this.callsign = callsign;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.team = team;
        this.role = role;
        this.staleTime = staleTime;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public boolean isStale() {
        return System.currentTimeMillis() > staleTime;
    }

    /** User should be removed from the map entirely (5 min past stale) */
    public boolean isExpired() {
        return System.currentTimeMillis() > staleTime + 300000;
    }

    public String getUid() { return uid; }
    public String getCallsign() { return callsign; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public double getAlt() { return alt; }
    public String getTeam() { return team; }
    public String getRole() { return role; }
    public long getStaleTime() { return staleTime; }
    public long getLastUpdateTime() { return lastUpdateTime; }

    public void setLat(double lat) { this.lat = lat; }
    public void setLon(double lon) { this.lon = lon; }
    public void setAlt(double alt) { this.alt = alt; }
    public void setCallsign(String callsign) { this.callsign = callsign; }
    public void setTeam(String team) { this.team = team; }
    public void setRole(String role) { this.role = role; }
    public void setStaleTime(long staleTime) { this.staleTime = staleTime; }
    public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }

    public String getEmergencyType() { return emergencyType; }
    public void setEmergencyType(String emergencyType) { this.emergencyType = emergencyType; }
    public boolean isEmergencyActive() { return emergencyActive; }
    public void setEmergencyActive(boolean emergencyActive) { this.emergencyActive = emergencyActive; }
}
