package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class RouteManeuverStateTest {
    private var now = 0L
    private val owner = Any()
    private fun state() = RouteGuidanceState({ now }).also { it.attach(owner) }
    private fun route(index: Int = 7) = Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,300); u16(0x0d,index) }
    private fun turn(index: Int = 7, type: Int = 2) = Iap2Messages.buildRaw(0x5202) {
        u16(1,index); u8(3,type); string(4,"南海大道"); u8(8,0)
    }
    @Test fun activeIndexSelectsPhoneDirectionAndKeepsSeparateDistances() {
        val s=state();s.accept(owner,route());s.accept(owner,turn())
        assertEquals("右转",s.snapshot().turnText);assertEquals("南海大道",s.snapshot().turnRoad)
        assertEquals(RouteTurnIcon.RIGHT,s.snapshot().turnIcon)
        assertEquals(300L,s.snapshot().nextTurnMeters);assertNull(s.snapshot().remainingMeters)
    }
    @Test fun detailsBeforeActiveListAreSupported() {
        val s=state();s.accept(owner,turn());assertFalse(s.snapshot().active)
        s.accept(owner,route());assertEquals("右转",s.snapshot().turnText)
    }
    @Test fun changingIndexNeverUsesPreviousDirection() {
        val s=state();s.accept(owner,route());s.accept(owner,turn());s.accept(owner,route(8))
        assertNull(s.snapshot().turnText);s.accept(owner,turn(8,1));assertEquals("左转",s.snapshot().turnText)
    }
    @Test fun oneTimeDetailsSurviveWhileRouteUpdatesAreFresh() {
        val s=state();s.accept(owner,route());s.accept(owner,turn())
        now=150000;s.accept(owner,route());assertEquals("右转",s.snapshot().turnText)
    }
    @Test fun endedRouteSourceAndPhoneDoNotRetainInstructions() {
        val s=state();s.accept(owner,route());s.accept(owner,turn())
        s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,0)})
        assertEquals(RouteGuidanceSummary(),s.snapshot());s.accept(owner,route());assertNull(s.snapshot().turnText)
        s.accept(owner,turn());s.attach(Any());assertFalse(s.accept(owner,turn()));assertFalse(s.snapshot().active)
    }
    @Test fun temporaryEmptyListHasBoundedGraceAndRerouteHidesOldDirection() {
        val s=state();s.accept(owner,route());s.accept(owner,turn())
        now=100;s.accept(owner,Iap2Messages.buildRaw(0x5201){bytes(0x0d,byteArrayOf())})
        now=3000;assertEquals("右转",s.snapshot().turnText)
        now=4100;assertNull(s.snapshot().turnText)
        s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,3)})
        assertNull(s.snapshot().turnText)
    }
    @Test fun malformedAndDuplicateUpdatesAreAtomic() {
        val s=state();s.accept(owner,route());s.accept(owner,turn());val before=s.snapshot()
        for(f in listOf(Iap2Messages.buildRaw(0x5202){u16(1,7);u8(3,1);u8(3,2)},
            Iap2Messages.buildRaw(0x5202){u8(1,7);u8(3,1)},
            Iap2Messages.buildRaw(0x5201){bytes(0x0d,byteArrayOf(1))},
            Iap2Frame(0x5202,turn().payload+byteArrayOf(0,3,0,1)))) {
            assertFalse(s.accept(owner,f));assertEquals(before,s.snapshot())
        }
    }
    @Test fun unsupportedTypeNeverInventsTurn() {
        val s=state();s.accept(owner,route());s.accept(owner,turn(type=255))
        assertNull(s.snapshot().turnText);assertNull(s.snapshot().turnIcon);assertEquals(300L,s.snapshot().nextTurnMeters)
    }
    @Test fun everyKnownInstructionHasANonFontIcon() {
        for(type in 0..255){val s=state();s.accept(owner,route());s.accept(owner,turn(type=type));assertEquals("type=$type",s.snapshot().turnText!=null,s.snapshot().turnIcon!=null)}
    }
}
