package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayRouteGuidanceView
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceStateTest {
    private var now=0L
    private val owner=Any()
    private fun state()=RouteGuidanceState({now}).also { it.attach(owner) }
    private fun route()=Iap2Messages.buildRaw(0x5201) {
        u8(1,1); string(0x13,"高德地图"); string(4,"深圳大学")
        u64(5,1793018400L); u64(6,1080); u32(7,12600); u32(10,320)
    }
    @Test fun allFiveFieldsDecodeWithDistinctDestinationAndTurnDistances() {
        val s=state();assertTrue(s.accept(owner,route()));val v=s.snapshot()
        assertTrue(v.active);assertEquals("深圳大学",v.destinationName)
        assertEquals(1080L,v.remainingSeconds);assertEquals(12600L,v.remainingMeters)
        assertEquals(1793018400L,v.arrivalEpochSeconds);assertEquals(320L,v.nextTurnMeters)
    }
    @Test fun incrementalDistanceDoesNotDiscardDestinationTimeOrArrival() {
        val s=state();s.accept(owner,route());now=5000
        s.accept(owner,Iap2Messages.buildRaw(0x5201){u32(7,12000)})
        assertEquals("深圳大学",s.snapshot().destinationName);assertEquals(1080L,s.snapshot().remainingSeconds)
        assertEquals(12000L,s.snapshot().remainingMeters)
    }
    @Test fun initialSourceArrivingAfterRouteDoesNotEraseDestination() {
        val s=state()
        s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1);string(4,"深圳大学");u32(7,12000);u64(6,800)})
        now=500
        s.accept(owner,Iap2Messages.buildRaw(0x5201){string(0x13,"高德地图")})
        assertTrue(s.snapshot().active);assertEquals("深圳大学",s.snapshot().destinationName)
        assertEquals(12000L,s.snapshot().remainingMeters);assertEquals(800L,s.snapshot().remainingSeconds)
        assertEquals("received",s.destinationStatus())
    }
    @Test fun initialSourceBetweenNameAndStatePreservesFreshName() {
        val s=state()
        s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"深圳大学")})
        now=100;s.accept(owner,Iap2Messages.buildRaw(0x5201){string(0x13,"高德地图")})
        now=200;s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1)})
        assertEquals("深圳大学",s.snapshot().destinationName)
    }
    @Test fun staleInitialSourceDoesNotReviveOldDestination() {
        val s=state();s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1);string(4,"深圳大学")})
        now=120001;s.accept(owner,Iap2Messages.buildRaw(0x5201){string(0x13,"高德地图");u8(1,1)})
        assertTrue(s.snapshot().active);assertNull(s.snapshot().destinationName)
        assertEquals("not_received",s.destinationStatus())
    }
    @Test fun destinationDiagnosticsDistinguishPlaceholderEmptyAndMissingWithoutText() {
        val s=state();s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1)})
        assertEquals("not_received",s.destinationStatus())
        s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"未知位置")})
        assertEquals("phone_placeholder",s.destinationStatus())
        s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"")})
        assertEquals("phone_empty",s.destinationStatus())
        s.accept(owner,route());assertEquals("received",s.destinationStatus())
        s.detach(owner);assertEquals("inactive",s.destinationStatus())
    }
    @Test fun navigationEndAndArrivalClearAllDetails() {
        for(end in listOf(0,2)){val s=state();s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,end)});assertEquals(RouteGuidanceSummary(),s.snapshot())}
    }
    @Test fun reroutingKeepsSummaryButDoesNotAlterOffsetRule() {
        val s=state();val offset=SecondaryMapNavigationState({now});offset.attach(owner)
        s.accept(owner,route());offset.accept(owner,route());now=400;assertTrue(offset.shouldOffset())
        val reroute=Iap2Messages.buildRaw(0x5201){u8(1,3)};s.accept(owner,reroute);offset.accept(owner,reroute)
        assertTrue(s.snapshot().active);assertEquals("深圳大学",s.snapshot().destinationName);assertFalse(offset.shouldOffset())
    }
    @Test fun changedDestinationDoesNotInheritOldRemainingDistance() {
        val s=state();s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"深圳北站")})
        assertEquals("深圳北站",s.snapshot().destinationName);assertNull(s.snapshot().remainingMeters)
    }
    @Test fun changedSourceNeedsFreshRouteStateAndDoesNotInheritMetadata() {
        val s=state();s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){string(0x13,"Apple Maps")})
        assertFalse(s.snapshot().active);s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1)})
        assertTrue(s.snapshot().active);assertNull(s.snapshot().destinationName)
    }
    @Test fun changedSourceWithCompleteSameMessageIsAppliedAtomically() {
        val s=state();s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){string(0x13,"Apple Maps");u8(1,1);string(4,"图书馆");u32(7,900)})
        assertEquals("图书馆",s.snapshot().destinationName);assertEquals(900L,s.snapshot().remainingMeters);assertNull(s.snapshot().remainingSeconds)
    }
    @Test fun phoneSwapAndLateOldSessionCannotWriteOrClearNewPhone() {
        val s=state();s.accept(owner,route());val next=Any();s.attach(next)
        assertFalse(s.accept(owner,route()));assertFalse(s.snapshot().active)
        s.accept(next,route());s.detach(owner);assertTrue(s.snapshot().active)
        s.detach(next);assertFalse(s.snapshot().active)
    }
    @Test fun secondsRequireEightBytesAndRejectPartialUpdate() {
        val s=state();s.accept(owner,route());val before=s.snapshot()
        assertFalse(s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"错误目的地");u32(6,45)}))
        assertEquals(before,s.snapshot())
    }
    @Test fun malformedTrailingTlvDoesNotPartiallyEndRoute() {
        val s=state();s.accept(owner,route());val stop=Iap2Messages.buildRaw(0x5201){u8(1,0)}
        assertFalse(s.accept(owner,Iap2Frame(0x5201,stop.payload+byteArrayOf(0,3,0,1))));assertTrue(s.snapshot().active)
    }
    @Test fun duplicatesAreRejected() {
        val s=state();s.accept(owner,route());val before=s.snapshot()
        assertFalse(s.accept(owner,Iap2Messages.buildRaw(0x5201){u32(7,12);u32(7,24)}));assertEquals(before,s.snapshot())
    }
    @Test fun malformedStringsCannotEscapeToUiOrChangeCachedData() {
        for(bytes in listOf(byteArrayOf(0xc3.toByte(),0x28),"bad\nname".toByteArray(),byteArrayOf(65,0,66),"a".repeat(513).toByteArray(),"abc\u202e".toByteArray())){
            val s=state();s.accept(owner,route());assertFalse(s.accept(owner,Iap2Messages.buildRaw(0x5201){bytes(4,bytes)}));assertEquals("深圳大学",s.snapshot().destinationName)
        }
    }
    @Test fun utf8NullTerminatorAndPlainUtf8BothSupported() {
        for(name in listOf("深圳大学","深圳大学\u0000")){val s=state();s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1);bytes(4,name.toByteArray())});assertEquals("深圳大学",s.snapshot().destinationName)}
    }
    @Test fun routeQuietForEightSecondsStillShowsValidSummary() {
        val s=state();s.accept(owner,route());now=9000;assertTrue(s.snapshot().active)
    }
    @Test fun routeAndIndividualNumbersExpireWithoutExtrapolation() {
        val s=state();s.accept(owner,route());now=119000;s.accept(owner,Iap2Messages.buildRaw(0x5201){u32(10,10)})
        now=120001;assertTrue(s.snapshot().active);assertNull(s.snapshot().remainingSeconds);assertNull(s.snapshot().remainingMeters);assertEquals(10L,s.snapshot().nextTurnMeters)
        now=240001;assertFalse(s.snapshot().active)
    }
    @Test fun invalidSentinelsClearValuesAndZeroDistanceIsPreserved() {
        val s=state();s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){u64(5,0);bytes(6,ByteArray(8){0xff.toByte()});u32(7,0xffffffffL);u32(10,0)})
        val v=s.snapshot();assertNull(v.arrivalEpochSeconds);assertNull(v.remainingSeconds);assertNull(v.remainingMeters);assertEquals(0L,v.nextTurnMeters)
    }
    @Test fun destinationNeverFallsBackToCurrentRoadField() {
        val s=state();s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1);string(3,"深南大道");u32(10,320)})
        assertNull(s.snapshot().destinationName);assertNull(s.snapshot().remainingMeters);assertEquals(320L,s.snapshot().nextTurnMeters)
    }
    @Test fun unsupportedOrEmptySourceClearsRoute() {
        for(frame in listOf(Iap2Messages.buildRaw(0x5201){u8(0x14,0)},Iap2Messages.buildRaw(0x5201){string(0x13,"")})){
            val s=state();s.accept(owner,route());s.accept(owner,frame);assertFalse(s.snapshot().active)
        }
    }
    @Test fun repeatedAttachSameSessionKeepsRouteButClearDropsIt() {
        val s=state();s.accept(owner,route());s.attach(owner);assertTrue(s.snapshot().active);s.clear();assertFalse(s.snapshot().active)
    }
    @Test fun staleControllerCannotClearNewControllerView() {
        val a=Any();val b=Any();val first=state();val next=state();first.accept(owner,route());next.accept(owner,route())
        CarPlayRouteGuidanceView.attach(a,first);CarPlayRouteGuidanceView.attach(b,next);CarPlayRouteGuidanceView.detach(a)
        assertTrue(CarPlayRouteGuidanceView.current().active);CarPlayRouteGuidanceView.detach(b);assertFalse(CarPlayRouteGuidanceView.current().active)
    }
    @Test fun phonePlaceholderIsNotShownAsARealDestination() {
        for(name in listOf("未知位置","未知地点","未知目的地","Unknown location","unknown destination")) {
            val s=state();s.accept(owner,Iap2Messages.buildRaw(0x5201){u8(1,1);string(4,name);u64(6,240);u32(7,1000)})
            assertTrue(s.snapshot().active);assertNull(s.snapshot().destinationName);assertEquals(1000L,s.snapshot().remainingMeters)
        }
    }
    @Test fun placeholderDoesNotReusePreviousRealDestination() {
        val s=state();s.accept(owner,route());s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"未知位置");u32(7,900)})
        assertNull(s.snapshot().destinationName);assertNull(s.snapshot().remainingSeconds);assertEquals(900L,s.snapshot().remainingMeters)
        s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"未知位置");u64(6,180)})
        assertEquals(900L,s.snapshot().remainingMeters);assertEquals(180L,s.snapshot().remainingSeconds)
        s.accept(owner,Iap2Messages.buildRaw(0x5201){string(4,"深圳大学");u32(7,500)})
        assertEquals("深圳大学",s.snapshot().destinationName);assertEquals(500L,s.snapshot().remainingMeters)
    }

}
