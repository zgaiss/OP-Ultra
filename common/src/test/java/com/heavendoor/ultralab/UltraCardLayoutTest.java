package com.heavendoor.ultralab;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UltraCardLayoutTest {
    @Test public void exchangingSidesMovesBothPanelsWithoutChangingTheirWidth() {
        assertEquals(62f, UltraCardLayout.speedX(false), 0f);
        assertEquals(1378f, UltraCardLayout.infoX(false), 0f);
        assertEquals(UltraCardLayout.speedX(false), UltraCardLayout.infoX(true), 0f);
        assertEquals(UltraCardLayout.infoX(false), UltraCardLayout.speedX(true), 0f);
    }

    @Test public void hitRegionFollowsInfoCardAndExcludesSpeedAndOutsideEdges() {
        for (boolean swapped : new boolean[] {false, true}) {
            float x = UltraCardLayout.infoX(swapped);
            assertTrue(UltraCardLayout.isInfoCard(swapped, x, 112));
            assertTrue(UltraCardLayout.isInfoCard(swapped, x + 479, 623));
            assertFalse(UltraCardLayout.isInfoCard(swapped, x + 480, 623));
            assertFalse(UltraCardLayout.isInfoCard(swapped, x, 624));
            assertFalse(UltraCardLayout.isInfoCard(swapped, x, 111));
            assertFalse(UltraCardLayout.isInfoCard(swapped, UltraCardLayout.speedX(swapped) + 100, 300));
        }
    }

    @Test public void gestureDirectionAndTapHalvesStayNaturalAfterSwap() {
        for (boolean swapped : new boolean[] {false, true}) {
            float x = UltraCardLayout.infoX(swapped);
            assertEquals(-1, UltraCardLayout.swipeDelta(swapped, x + 240, x + 120));
            assertEquals(1, UltraCardLayout.swipeDelta(swapped, x + 240, x + 360));
            assertEquals(-1, UltraCardLayout.swipeDelta(swapped, x + 50, x + 50));
            assertEquals(1, UltraCardLayout.swipeDelta(swapped, x + 400, x + 400));
        }
    }
}
