package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class HotspotConnectionGateTest {
    @Test fun alreadyEnabledApConnectsWithoutDelay() {
        val g=HotspotConnectionGate();g.begin(100);assertTrue(g.remaining(100)>0)
        g.ready(300);assertEquals(0L,g.remaining(300))
    }
    @Test fun softwareStartedApWaitsEighteenSecondsAfterActualReadiness() {
        val g=HotspotConnectionGate();g.begin(0);g.requested()
        assertTrue(g.remaining(14000)>0);g.ready(15000)
        assertEquals(18000L,g.remaining(15000));assertEquals(1L,g.remaining(32999));assertEquals(0L,g.remaining(33000))
    }
    @Test fun restoredCooldownDoesNotRestartTheEighteenSeconds() {
        val g=HotspotConnectionGate();g.begin(10000,true,5000);g.ready(10100)
        assertEquals(13000L,g.remaining(10000));assertEquals(0L,g.remaining(23000))
    }
    @Test fun expiredCooldownIsNotReintroducedOnWake() {
        val g=HotspotConnectionGate();g.begin(40000,true,1000);g.ready(40100)
        assertEquals(0L,g.remaining(40100))
    }
    @Test fun newStartDiscardsAnOldReadyTimestamp() {
        val g=HotspotConnectionGate();g.begin(40000,true,1000);g.requested();g.ready(42000)
        assertEquals(18000L,g.remaining(42000))
    }
    @Test fun unavailableApOrDeniedPermissionNeverStartsConnection() {
        val g=HotspotConnectionGate();g.begin(0);assertEquals(-1L,g.remaining(60000))
        g.begin(61000);g.fail();assertEquals(-1L,g.remaining(61000))
        g.begin(63000);g.ready(63001);assertEquals(0L,g.remaining(63001))
    }
}
