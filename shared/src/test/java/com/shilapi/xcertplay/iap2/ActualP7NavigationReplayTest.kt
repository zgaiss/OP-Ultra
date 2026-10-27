package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.*
import org.junit.Assert.*
import org.junit.Test

/** Metadata-only reconstruction of the user's 2026-10-24 capture, not a raw wire recording. */
class ActualP7NavigationReplayTest {
    @Test fun recordedStartupAndNavigationEndRespectTheStrictOffsetRule() {
        val records=listOf(
            Triple(0L, null, 0),
            Triple(38L, "高德地图", 3),
            Triple(54L, "高德地图", 0),
            Triple(69L, "高德地图", 3),
            Triple(116L, "高德地图", 0),
            Triple(129L, null, 0),
            Triple(166L, "高德地图", 3),
            Triple(205L, null, 1),
            Triple(582L, null, 1),
            Triple(588L, null, 1),
            Triple(640L, null, 1),
            Triple(685L, null, 1),
            Triple(857L, null, 1),
            Triple(884L, null, 1),
            Triple(933L, null, 1),
            Triple(26477L, null, 1),
            Triple(35597L, null, 1),
            Triple(35629L, "高德地图", 0),
            Triple(35633L, null, 0),
            Triple(35636L, null, 0),
        )
        var clock=0L;val owner=Any();val source=SecondaryMapNavigationState({clock});source.attach(owner)
        var activated=false
        for((ms,name,state) in records) {
            clock=ms
            source.accept(owner,Iap2Messages.buildRaw(0x5201) {
                name?.let { string(0x13,it) };u8(1,state)
            })
            if(state!=1)assertFalse("record at $ms, state=$state",source.shouldOffset())
            if(state==1 && source.shouldOffset())activated=true
        }
        assertTrue("Recorded active route should have enabled the offset",activated)
        assertFalse("Recorded end must restore zero offset",source.shouldOffset())
    }
}
