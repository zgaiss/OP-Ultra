package com.heavendoor.ultralab;

/** Positions change; content and map coordinates are never mirrored. */
public final class UltraCardLayout {
    private static final float LEFT = 62, RIGHT = 1378;
    private static final float TOP = 112, WIDTH = 480, HEIGHT = 512;

    private UltraCardLayout() {}

    public static float speedX(boolean swapped) { return swapped ? RIGHT : LEFT; }
    public static float infoX(boolean swapped) { return swapped ? LEFT : RIGHT; }

    public static boolean isInfoCard(boolean swapped, float x, float y) {
        float start = infoX(swapped);
        return x >= start && x < start + WIDTH && y >= TOP && y < TOP + HEIGHT;
    }

    public static int swipeDelta(boolean swapped, float downX, float upX) {
        if (upX < downX - 40) return -1;
        if (upX > downX + 40) return 1;
        return upX < infoX(swapped) + WIDTH / 2 ? -1 : 1;
    }
}
