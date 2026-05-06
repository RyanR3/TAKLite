package com.taklite.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * ATAK-style radial ring menu that appears around a tapped marker.
 * Dark circular ring with wedge sectors containing icons.
 */
public class RadialMenuView extends View {

    public interface RadialMenuListener {
        void onDelete();
        void onSend();
        void onEdit();
        void onDismiss();
    }

    private static final int NUM_ITEMS = 3;

    private RadialMenuListener listener;
    private float centerX, centerY;
    private float innerRadius, outerRadius;
    private float density;

    private float[] sectorStartAngle = new float[NUM_ITEMS];
    private float[] sectorSweep = new float[NUM_ITEMS];
    private float[] iconAngle = new float[NUM_ITEMS];

    private Bitmap[] icons = new Bitmap[NUM_ITEMS];

    private Paint dimPaint, ringBorderPaint, sectorDividerPaint;
    private Paint iconPaint, pressedPaint;

    private int pressedIndex = -1;

    public RadialMenuView(Context context, float cx, float cy, RadialMenuListener listener) {
        super(context);
        this.listener = listener;
        this.centerX = cx;
        this.centerY = cy;

        density = context.getResources().getDisplayMetrics().density;
        this.innerRadius = 36 * density;
        this.outerRadius = 76 * density;

        // Load icons
        Bitmap rawDelete = BitmapFactory.decodeResource(getResources(), R.drawable.radial_delete);
        Bitmap rawSend = BitmapFactory.decodeResource(getResources(), R.drawable.radial_send);
        Bitmap rawEdit = BitmapFactory.decodeResource(getResources(), R.drawable.radial_edit);

        int iconSize = (int) (20 * density);
        icons[0] = Bitmap.createScaledBitmap(rawDelete, iconSize, iconSize, true);
        icons[1] = Bitmap.createScaledBitmap(rawSend, iconSize, iconSize, true);
        icons[2] = Bitmap.createScaledBitmap(rawEdit, iconSize, iconSize, true);

        // Dim overlay
        dimPaint = new Paint();
        dimPaint.setColor(Color.argb(80, 0, 0, 0));
        dimPaint.setStyle(Paint.Style.FILL);

        // Ring border
        ringBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringBorderPaint.setColor(Color.argb(180, 160, 160, 160));
        ringBorderPaint.setStyle(Paint.Style.STROKE);
        ringBorderPaint.setStrokeWidth(1.5f * density);

        // Sector divider lines
        sectorDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sectorDividerPaint.setColor(Color.argb(100, 160, 160, 160));
        sectorDividerPaint.setStyle(Paint.Style.STROKE);
        sectorDividerPaint.setStrokeWidth(1f * density);

        // Icon paint - no tint, use original icon colors
        iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Pressed sector highlight
        pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pressedPaint.setColor(Color.argb(50, 255, 255, 255));
        pressedPaint.setStyle(Paint.Style.FILL);

        // 3 sectors of 120° each, starting so Delete is top-left, Send top, Edit top-right
        float sectorSize = 360f / NUM_ITEMS;
        float startOffset = -210f;
        for (int i = 0; i < NUM_ITEMS; i++) {
            sectorStartAngle[i] = startOffset + i * sectorSize;
            sectorSweep[i] = sectorSize;
            iconAngle[i] = sectorStartAngle[i] + sectorSize / 2f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Dim background
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

        // Draw ring background
        float midRadius = (innerRadius + outerRadius) / 2f;
        float ringWidth = outerRadius - innerRadius;
        Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringFill.setColor(Color.argb(210, 30, 30, 30));
        ringFill.setStyle(Paint.Style.STROKE);
        ringFill.setStrokeWidth(ringWidth);
        canvas.drawCircle(centerX, centerY, midRadius, ringFill);

        // Draw pressed sector highlight
        if (pressedIndex >= 0) {
            RectF outerRect = new RectF(centerX - outerRadius, centerY - outerRadius,
                    centerX + outerRadius, centerY + outerRadius);
            RectF innerRect = new RectF(centerX - innerRadius, centerY - innerRadius,
                    centerX + innerRadius, centerY + innerRadius);

            Path sectorPath = new Path();
            sectorPath.arcTo(outerRect, sectorStartAngle[pressedIndex], sectorSweep[pressedIndex], true);
            sectorPath.arcTo(innerRect, sectorStartAngle[pressedIndex] + sectorSweep[pressedIndex],
                    -sectorSweep[pressedIndex]);
            sectorPath.close();
            canvas.drawPath(sectorPath, pressedPaint);
        }

        // Draw ring borders
        canvas.drawCircle(centerX, centerY, outerRadius, ringBorderPaint);
        canvas.drawCircle(centerX, centerY, innerRadius, ringBorderPaint);

        // Draw sector dividers
        for (int i = 0; i < NUM_ITEMS; i++) {
            float angle = (float) Math.toRadians(sectorStartAngle[i]);
            float x1 = centerX + innerRadius * (float) Math.cos(angle);
            float y1 = centerY + innerRadius * (float) Math.sin(angle);
            float x2 = centerX + outerRadius * (float) Math.cos(angle);
            float y2 = centerY + outerRadius * (float) Math.sin(angle);
            canvas.drawLine(x1, y1, x2, y2, sectorDividerPaint);
        }

        // Draw icons centered in each sector
        float iconRadius = (innerRadius + outerRadius) / 2f;
        for (int i = 0; i < NUM_ITEMS; i++) {
            float angle = (float) Math.toRadians(iconAngle[i]);
            float ix = centerX + iconRadius * (float) Math.cos(angle);
            float iy = centerY + iconRadius * (float) Math.sin(angle);

            Bitmap icon = icons[i];
            canvas.drawBitmap(icon, ix - icon.getWidth() / 2f, iy - icon.getHeight() / 2f, iconPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressedIndex = hitTest(x, y);
                if (pressedIndex >= 0) invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                int newPressed = hitTest(x, y);
                if (newPressed != pressedIndex) {
                    pressedIndex = newPressed;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                int hit = hitTest(x, y);
                pressedIndex = -1;
                invalidate();
                if (hit == 0 && listener != null) listener.onDelete();
                else if (hit == 1 && listener != null) listener.onSend();
                else if (hit == 2 && listener != null) listener.onEdit();
                else if (listener != null) listener.onDismiss();
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressedIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int hitTest(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        if (dist < innerRadius || dist > outerRadius) {
            return -1;
        }

        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));

        for (int i = 0; i < NUM_ITEMS; i++) {
            float start = normalizeAngle(sectorStartAngle[i]);
            float end = normalizeAngle(sectorStartAngle[i] + sectorSweep[i]);
            float a = normalizeAngle(angle);

            if (start < end) {
                if (a >= start && a < end) return i;
            } else {
                if (a >= start || a < end) return i;
            }
        }
        return -1;
    }

    private float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle < 0) angle += 360;
        return angle;
    }
}
