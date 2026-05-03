package com.taklite.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.taklite.app.tak.TakLocationService;

public class PanicAccessibilityService extends AccessibilityService {
    private static final String TAG = "PanicAccessibility";
    private static PanicAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Panic accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used — we only need key event interception
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Panic accessibility service interrupted");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // Only care about volume keys
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        // Only handle if TAK location service is running
        if (!TakLocationService.isRunning()) {
            return false;
        }

        // Only count ACTION_DOWN with no repeat
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.d(TAG, "Volume key detected: " + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? "UP" : "DOWN"));
            TakLocationService.onVolumeKeyFromAccessibility();
        }

        // Return false so volume still changes normally
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "Panic accessibility service destroyed");
    }

    public static boolean isRunning() {
        return instance != null;
    }
}
