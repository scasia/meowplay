package com.weblink.sdk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists and transforms raw car stereo touch coordinates into normalized [0.0, 1.0] coordinates.
 */
public class WebLinkTouchCalibration {
    private static final String PREF_NAME = "weblink_touch_calibration";
    private static final String KEY_OFFSET_X = "offset_x";
    private static final String KEY_OFFSET_Y = "offset_y";
    private static final String KEY_SCALE_X = "scale_x";
    private static final String KEY_SCALE_Y = "scale_y";
    private static final String KEY_INVERT_X = "invert_x";
    private static final String KEY_INVERT_Y = "invert_y";

    public int offsetX = 0;
    public int offsetY = 0;
    public float scaleX = 1.0f;
    public float scaleY = 1.0f;
    public boolean invertX = false;
    public boolean invertY = false;

    public void load(Context context) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        offsetX = sp.getInt(KEY_OFFSET_X, 0);
        offsetY = sp.getInt(KEY_OFFSET_Y, 0);
        scaleX = sp.getFloat(KEY_SCALE_X, 1.0f);
        scaleY = sp.getFloat(KEY_SCALE_Y, 1.0f);
        invertX = sp.getBoolean(KEY_INVERT_X, false);
        invertY = sp.getBoolean(KEY_INVERT_Y, false);
    }

    public void save(Context context) {
        if (context == null) return;
        SharedPreferences.Editor ed = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putInt(KEY_OFFSET_X, offsetX);
        ed.putInt(KEY_OFFSET_Y, offsetY);
        ed.putFloat(KEY_SCALE_X, scaleX);
        ed.putFloat(KEY_SCALE_Y, scaleY);
        ed.putBoolean(KEY_INVERT_X, invertX);
        ed.putBoolean(KEY_INVERT_Y, invertY);
        ed.apply();
    }

    public void reset(Context context) {
        offsetX = 0;
        offsetY = 0;
        scaleX = 1.0f;
        scaleY = 1.0f;
        invertX = false;
        invertY = false;
        save(context);
    }

    /**
     * Maps a raw touch point to normalized [0.0, 1.0] screen space.
     * @return double[] { normX, normY }
     */
    public double[] mapNormalized(WebLinkTouchPoint pt, int screenWidth, int screenHeight) {
        int w = screenWidth > 0 ? screenWidth : 800;
        int h = screenHeight > 0 ? screenHeight : 480;

        double adjX = (pt.posX - offsetX) * (double) scaleX;
        double adjY = (pt.posY - offsetY) * (double) scaleY;

        double normX = Math.max(0.0, Math.min(1.0, adjX / (double) w));
        double normY = Math.max(0.0, Math.min(1.0, adjY / (double) h));

        if (invertX) normX = 1.0 - normX;
        if (invertY) normY = 1.0 - normY;

        return new double[]{ normX, normY };
    }
}
