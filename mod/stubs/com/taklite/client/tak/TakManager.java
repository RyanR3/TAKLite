package com.taklite.client.tak;

/**
 * Compile-time stub only; never packaged. These public methods exist in the stock app,
 * except modForgetUser, which patches/taklite-1.5.0.patch adds.
 */
public class TakManager {

    public static TakManager getInstance() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean isConnected() {
        throw new UnsupportedOperationException("stub");
    }

    public void sendMarker(double lat, double lon, double hae, String affiliation, String name, String remarks) {
        throw new UnsupportedOperationException("stub");
    }

    /** Drops a user or remote marker from TakManager's list (it is not removed from the map). */
    public void modForgetUser(String uid) {
        throw new UnsupportedOperationException("stub");
    }

    /** True while an emergency alert sent from this device hasn't been cancelled. */
    public boolean hasActiveAlert() {
        throw new UnsupportedOperationException("stub");
    }
}
