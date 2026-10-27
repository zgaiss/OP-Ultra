package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

class NightModeSyncStateTest {
    @Test fun applicationDayOverridesStaleActivityNightAndUndefinedRetainsLastValue() {
        assertFalse(currentDarkMode(Configuration.UI_MODE_NIGHT_NO,Configuration.UI_MODE_NIGHT_YES,true))
        assertTrue(currentDarkMode(0,0,true));assertFalse(currentDarkMode(0,0,false))
    }
    @Test fun queuedNightRequestCannotOverwriteNewDayRequest() {
        val s=NightModeSyncState();val owner=Any();s.observe(owner,true,0);val old=s.next(0)!!
        s.observe(owner,false,1);assertFalse(s.current(old));val day=s.next(1)!!
        assertFalse(day.night);s.complete(old,true,2);assertNull(s.next(2))
        s.complete(day,true,3);assertNotNull(s.next(1003))
    }
    @Test fun absentEventChannelRetriesWithoutMarkingDeliverySuccessful() {
        val s=NightModeSyncState();s.observe(Any(),false,0);s.complete(s.next(0)!!,false,0)
        assertNull(s.next(1999));assertNotNull(s.next(2000))
    }
    @Test fun successfulTransitionSendsThreeBoundedUpdates() {
        val s=NightModeSyncState();s.observe(Any(),false,0)
        for(t in listOf(0L,1000L,4000L)){val r=s.next(t)!!;s.complete(r,true,t)}
        assertNull(s.next(100000));s.observe(null,false,100001);assertNull(s.next(100001))
    }
    @Test fun phoneSwapAndWakeResetDeliveryWithoutOldCompletions() {
        val s=NightModeSyncState();val a=Any();val b=Any();s.observe(a,true,0);val old=s.next(0)!!
        s.observe(b,true,5);val fresh=s.next(5)!!;s.complete(old,true,6);assertTrue(s.current(fresh))
        s.complete(fresh,true,7);s.observe(b,true,10,true);assertNotNull(s.next(10))
    }
}
