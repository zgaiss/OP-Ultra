package com.heavendoor.ultralab;

import org.junit.Test;
import static org.junit.Assert.*;

/** Protect the approved production typography, not independently maintained preview numbers. */
public final class UltraMusicCardStyleTest {
    @Test public void lyricsUseRestrainedMediumHierarchy() {
        assertEquals("sans-serif-medium", UltraMusicCardStyle.LYRIC_TYPEFACE_FAMILY);
        assertEquals(28, UltraMusicCardStyle.CURRENT_SIZE);
        assertEquals(22, UltraMusicCardStyle.NEIGHBOR_SIZE);
        assertEquals(26, UltraMusicCardStyle.TITLE_SIZE);
        assertEquals(19, UltraMusicCardStyle.ARTIST_SIZE);
        assertEquals(70, UltraMusicCardStyle.UNSUNG_ALPHA_PERCENT);
        assertEquals(2, UltraMusicCardStyle.CURRENT_MAX_LINES);
    }

    @Test public void headerAndLyricsKeepSeparateBoundedRegions() {
        assertEquals(480, UltraMusicCardStyle.WIDTH);
        assertEquals(512, UltraMusicCardStyle.HEIGHT);
        assertEquals(88, UltraMusicCardStyle.COVER_SIZE);
        assertEquals(UltraMusicCardStyle.WIDTH,
                UltraMusicCardStyle.TEXT_WIDTH + 2 * UltraMusicCardStyle.INSET);
        assertTrue(UltraMusicCardStyle.COVER_TOP + UltraMusicCardStyle.COVER_SIZE < UltraMusicCardStyle.DIVIDER_Y);
        assertTrue(UltraMusicCardStyle.DIVIDER_Y < UltraMusicCardStyle.LYRIC_TOP);
        assertTrue(UltraMusicCardStyle.LYRIC_BOTTOM < UltraMusicCardStyle.PROGRESS_Y);
        assertTrue(UltraMusicCardStyle.PROGRESS_Y < UltraMusicCardStyle.TIME_BASELINE);
        assertTrue(UltraMusicCardStyle.TIME_BASELINE < UltraMusicCardStyle.HEIGHT);
    }

    @Test public void stableRowsKeepTheirMotionGeometry() {
        assertEquals(280, UltraMusicCardStyle.CURRENT_CENTER_Y);
        assertEquals(92, UltraMusicCardStyle.ROW_GAP);
        assertEquals(1.16f, UltraMusicCardStyle.LINE_SPACING_MULTIPLIER, 0f);
        assertTrue(UltraMusicCardStyle.CURRENT_CENTER_Y - UltraMusicCardStyle.ROW_GAP
                - UltraMusicCardStyle.NEIGHBOR_SIZE / 2f > UltraMusicCardStyle.LYRIC_TOP);
        assertTrue(UltraMusicCardStyle.CURRENT_CENTER_Y + UltraMusicCardStyle.ROW_GAP
                + UltraMusicCardStyle.NEIGHBOR_SIZE / 2f < UltraMusicCardStyle.LYRIC_BOTTOM);
    }
}
