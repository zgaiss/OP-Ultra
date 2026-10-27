package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.SecondaryMapNavigationState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class SecondaryMapNavigationStateTest {
    private fun frame(name: String? = null,state: Int? = null) = Iap2Messages.buildRaw(0x5201) {
        name?.let { string(0x13,it) };state?.let { u8(1,it) }
    }
    @Test fun onlyAmapAndExactlyStateOneCanEnableTheOffset() {
        var now=0L;val owner=Any();val state=SecondaryMapNavigationState({now})
        state.attach(owner)
        for (code in 0..255) {
            state.accept(owner,frame("高德地图",code));now+=301
            assertEquals("routeState=$code",code==1,state.shouldOffset())
        }
    }
    @Test fun positiveSettleIgnoresStartupBurstsAndRepeatedActiveUpdatesDoNotExtendTheWait() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        s.accept(owner,frame("高德地图",3));now=50;s.accept(owner,frame(state=0))
        now=100;s.accept(owner,frame(state=1));now=200;s.accept(owner,frame(state=1))
        now=399;assertFalse(s.shouldOffset());now=400;assertTrue(s.shouldOffset())
    }
    @Test fun endingOrLeavingStateOneDisablesImmediatelyWithoutWaitingForANewVideoFrame() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        for (code in listOf(0,2,3,4,5,6)) {
            s.accept(owner,frame("高德地图",1));now+=300;assertTrue(s.shouldOffset())
            s.accept(owner,frame(state=code));assertFalse(s.shouldOffset())
        }
    }
    @Test fun otherAppsUnknownNamesAndMissingSourceNeverEnableTheOffset() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        s.accept(owner,frame(state=1));now+=1000;assertFalse(s.shouldOffset())
        for (name in listOf("Apple Maps","百度地图","Unconfirmed Map","Not AMap","")) {
            s.accept(owner,frame(name,1));now+=1000;assertFalse(s.shouldOffset())
        }
    }
    @Test fun changingMapsDisablesTheOffsetImmediately() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        s.accept(owner,frame("高德地图",1));now=300;assertTrue(s.shouldOffset())
        s.accept(owner,frame("Apple Maps"));assertFalse(s.shouldOffset())
        s.accept(owner,frame("高德地图"));now=1000;assertFalse(s.shouldOffset())
        s.accept(owner,frame(state=1));now=1300;assertTrue(s.shouldOffset())
    }
    @Test fun stationarySilenceAndMusicDoNotInventANavigationEnd() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        s.accept(owner,frame("高德地图",1));now=60_000;assertTrue(s.shouldOffset())
        assertNull(s.accept(owner,Iap2Messages.buildRaw(0x5001) { group(1) { string(7,"Music") } }))
        assertTrue(s.shouldOffset())
    }
    @Test fun newPhonesAndLateCallbacksCannotInheritOrOverwriteAnotherSessionsState() {
        var now=0L;val old=Any();val next=Any();val s=SecondaryMapNavigationState({now})
        s.attach(old);s.accept(old,frame("高德地图",1));now=300;assertTrue(s.shouldOffset())
        s.attach(next);assertFalse(s.shouldOffset())
        assertNull(s.accept(old,frame("高德地图",1)));assertFalse(s.shouldOffset())
        s.accept(next,frame("高德地图",1));now=600;assertTrue(s.shouldOffset())
        assertNull(s.accept(old,frame(state=0)));s.detach(old);assertTrue(s.shouldOffset())
        s.detach(next);assertFalse(s.shouldOffset())
    }
    @Test fun reattachingTheSameControlSessionDoesNotRestartTheSettleTimer() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        s.accept(owner,frame("高德地图",1));now=250;s.attach(owner);now=300;assertTrue(s.shouldOffset())
        s.clear();assertFalse(s.shouldOffset())
        s.attach(owner);s.accept(owner,frame(state=1));now=1000;assertFalse(s.shouldOffset())
    }
    @Test fun malformedOrUnrelatedPacketsCannotActivateAnUnknownSource() {
        var now=0L;val owner=Any();val s=SecondaryMapNavigationState({now});s.attach(owner)
        assertNull(s.accept(owner,Iap2Frame(0x5201,byteArrayOf(0,3,0,1))))
        now=1000;assertFalse(s.shouldOffset())
    }
}
