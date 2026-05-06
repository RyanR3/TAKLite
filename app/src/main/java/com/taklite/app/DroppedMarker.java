package com.taklite.app;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

public class DroppedMarker {
    public final String key;
    public final Marker marker;
    public final GeoPoint position;
    public String affiliation;
    public String name;
    public String remarks;
    public boolean transmitted;

    public DroppedMarker(String key, Marker marker, GeoPoint position, String affiliation) {
        this.key = key;
        this.marker = marker;
        this.position = position;
        this.affiliation = affiliation;
        this.name = affiliation + " Marker";
        this.remarks = "";
        this.transmitted = false;
    }
}
