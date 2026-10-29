package com.heavendoor.ultralab;

/** Single source for the production music card and its reproducible test-data previews. */
final class UltraMusicCardStyle {
    static final int WIDTH = 480, HEIGHT = 512, INSET = 32, TEXT_WIDTH = 416;
    static final int COVER_SIZE = 88, COVER_TOP = 24, HEADER_TEXT_X = 142;
    static final int TITLE_SIZE = 26, TITLE_BASELINE = 58, ARTIST_SIZE = 19, ARTIST_BASELINE = 91;
    static final int DIVIDER_Y = 134, LYRIC_TOP = 172, LYRIC_BOTTOM = 414;
    static final int CURRENT_CENTER_Y = 280, ROW_GAP = 92;
    static final int CURRENT_SIZE = 28, NEIGHBOR_SIZE = 22, CURRENT_MAX_LINES = 2;
    static final int UNSUNG_ALPHA_PERCENT = 70;
    static final float LINE_SPACING_MULTIPLIER = 1.16f;
    static final String LYRIC_TYPEFACE_FAMILY = "sans-serif-medium";
    static final int PROGRESS_Y = 449, TIME_BASELINE = 483;

    private UltraMusicCardStyle() { }
}
