package com.weblink.sdk;

/**
 * Represents a normalized touch event received from the WebLink car touchscreen.
 */
public class WebLinkTouchPoint {
    public static final int STATE_DOWN = 1;
    public static final int STATE_MOVE = 2;
    public static final int STATE_UP   = 3;

    public final int id;
    public final int posX;
    public final int posY;
    public final int state;
    public final float pressure;

    public WebLinkTouchPoint(int id, int posX, int posY, int state, float pressure) {
        this.id = id;
        this.posX = posX;
        this.posY = posY;
        this.state = state;
        this.pressure = pressure;
    }

    public boolean isDown() {
        return state == STATE_DOWN || state == STATE_MOVE;
    }

    public boolean isUp() {
        return state == STATE_UP;
    }

    @Override
    public String toString() {
        return "TouchPoint{id=" + id + ", x=" + posX + ", y=" + posY +
                ", state=" + (state == 1 ? "DOWN" : (state == 2 ? "MOVE" : "UP")) +
                ", pressure=" + pressure + "}";
    }
}
