package com.taklite.client;

/**
 * Compile-time stub only; never packaged. The real class is the stock app's
 * radial menu; the modMove field is added to it by patches/taklite-1.5.0.patch.
 */
public class RadialMenuView extends android.view.View {

    /** Run when the marker radial's fourth slice (Move) is tapped. */
    public Runnable modMove;

    public RadialMenuView(android.content.Context context) {
        super(context);
    }
}
