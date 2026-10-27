package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.*
import com.shilapi.xcertplay.iap2.wire.*
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class NavigationSourceResearchTest {
    private fun message(source: String? = null, state: Int? = null) = Iap2Messages.buildRaw(0x5201) {
        source?.let { string(0x13,it) };state?.let { u8(1,it) }
    }
    private fun original(wireless: Boolean): Iap2Frame {
        val config=Iap2IdentificationConfig("Synthetic","Synthetic","Synthetic","synthetic-only","0","0",3,
            wireless=if(wireless) Iap2WirelessIdentification("00:11:22:33:44:55","synthetic-network") else null)
        return Iap2IdentificationClient.identificationInformation(config)
    }

    @Test fun componentDeclarationIsAdditiveForBothWiredAndWirelessAndLeavesTheOriginalUntouched() {
        for(wireless in listOf(false,true)) {
            val before=original(wireless);val bytes=before.encodedFrame()
            val after=ExperimentalRouteGuidanceRequests.addComponent(before)
            assertArrayEquals(bytes,before.encodedFrame())
            assertFalse(before.body().has(30));assertTrue(after.body().has(30))
            assertEquals(before.body().asList(),after.body().asList().filter { it.id!=30 })
            val component=Iap2ParameterList.parse(after.body().require(30).payload)
            assertArrayEquals(byteArrayOf(0,42),component.require(0).payload)
            assertArrayEquals("RouteGuidance\u0000".toByteArray(),component.require(1).payload)
            assertArrayEquals(byteArrayOf(0,64),component.require(2).payload)
            assertArrayEquals(byteArrayOf(0,64),component.require(4).payload)
            assertArrayEquals(byteArrayOf(0,8),component.require(6).payload)
        }
    }
    @Test fun subscriptionUsesTheAdvertisedComponentAndRequestedFields() {
        val frame=ExperimentalRouteGuidanceRequests.startUpdates()
        assertEquals(0x5200,frame.messageId)
        assertArrayEquals(byteArrayOf(0,6,0,0,0,42,0,4,0,1,0,4,0,2),frame.payload)
    }
    @Test fun diagnosticOptInLeavesNormalSubscriptionBytesUnchanged() {
        val normal=Iap2WiredControlClient.subscriptions()
        assertEquals(normal,Iap2WiredControlClient.subscriptions(false))
        assertTrue(normal.single { it.messageId==0x5200 }.payload.isEmpty())
        val probe=Iap2WiredControlClient.subscriptions(true)
        assertEquals(normal.filter { it.messageId!=0x5200 },probe.filter { it.messageId!=0x5200 })
        assertEquals(ExperimentalRouteGuidanceRequests.startUpdates(),probe.single { it.messageId==0x5200 })
    }
    @Test fun diagnosticIdentificationFlagDeclaresTheComponentOnlyWhenEnabled() {
        for(wireless in listOf(false,true)) {
            val config=Iap2IdentificationConfig("Synthetic","Synthetic","Synthetic","synthetic-only","0","0",3,
                wireless=if(wireless) Iap2WirelessIdentification("00:11:22:33:44:55","synthetic-network") else null)
            val normal=Iap2IdentificationClient.identificationInformation(config)
            assertFalse(normal.body().has(30))
            val probe=Iap2IdentificationClient.identificationInformation(config.copy(navigationSourceProbe=true))
            assertEquals(ExperimentalRouteGuidanceRequests.addComponent(normal),probe)
        }
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateComponentIsRejected() {
        ExperimentalRouteGuidanceRequests.addComponent(ExperimentalRouteGuidanceRequests.addComponent(original(false)))
    }
    @Test fun amapSourceWithAnActiveRouteCanBeDistinguishedFromBrowsing() {
        val s=ExperimentalNavigationSource()
        assertTrue(s.accept(message("高德地图",0)))
        assertEquals(ExperimentalNavigationSource.FramingHint.CENTERED,s.framingHint())
        assertTrue(s.accept(message(state=1)))
        assertEquals(ExperimentalNavigationSource.FramingHint.AMAP_NAVIGATION,s.framingHint())
    }
    @Test fun sourceNameWithoutRouteStateIsInsufficientToApplyAnOffset() {
        val s=ExperimentalNavigationSource();s.accept(message("高德地图"))
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
    }
    @Test fun routeStateWithoutSourceCannotIdentifyTheMapApplication() {
        val s=ExperimentalNavigationSource();s.accept(message(state=1))
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
    }
    @Test fun endingOrArrivingRemovesTheAmapNavigationSuggestion() {
        for(end in listOf(0,2)) {
            val s=ExperimentalNavigationSource();s.accept(message("AMap",1));s.accept(message(state=end))
            assertEquals(ExperimentalNavigationSource.FramingHint.CENTERED,s.framingHint())
        }
    }
    @Test fun sourceChangesDoNotInheritThePreviousApplicationsNavigationState() {
        val s=ExperimentalNavigationSource();s.accept(message("AMap",1));s.accept(message("Apple Maps"))
        assertNull(s.snapshot().routeState)
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
        s.accept(message(state=1));assertEquals(ExperimentalNavigationSource.FramingHint.CENTERED,s.framingHint())
    }
    @Test fun firstSourceMayFollowTheInitialRouteStateInAnIncrementalPacket() {
        val s=ExperimentalNavigationSource();s.accept(message(state=1));s.accept(message("AMap"))
        assertEquals(ExperimentalNavigationSource.FramingHint.AMAP_NAVIGATION,s.framingHint())
    }
    @Test fun firstSourceCannotReviveAnExpiredUnidentifiedRouteState() {
        var clock=0L;val s=ExperimentalNavigationSource({clock},8000)
        s.accept(message(state=1));clock=9000;s.accept(message("AMap"))
        assertNull(s.snapshot().routeState)
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
    }
    @Test fun encodedControlMessagesSurviveTransportFragmentationAndMediaInterleaving() {
        val messages=listOf(message("AMap",0),message(state=1),
            Iap2Messages.buildRaw(0x5001) { group(1) { string(7,"Music") } },message(state=2))
        val bytes=messages.fold(byteArrayOf()) { data,frame -> data+frame.encodedFrame() }
        for(chunkSize in 1..bytes.size) {
            val decoder=Iap2CsmFramer();val s=ExperimentalNavigationSource()
            val hints=mutableListOf<ExperimentalNavigationSource.FramingHint>()
            for(chunk in bytes.toList().chunked(chunkSize))for(frame in decoder.offer(chunk.toByteArray())) {
                s.accept(frame);hints+=s.framingHint()
            }
            assertEquals(listOf(ExperimentalNavigationSource.FramingHint.CENTERED,
                ExperimentalNavigationSource.FramingHint.AMAP_NAVIGATION,
                ExperimentalNavigationSource.FramingHint.AMAP_NAVIGATION,
                ExperimentalNavigationSource.FramingHint.CENTERED),hints)
        }
    }
    @Test fun switchingMainScreenMusicDoesNotChangeTheIndependentNavigationSource() {
        val s=ExperimentalNavigationSource();s.accept(message("高德地图",1))
        assertFalse(s.accept(Iap2Messages.buildRaw(0x5001) { group(1) { string(7,"Music") } }))
        assertEquals(ExperimentalNavigationSource.App.AMAP,s.snapshot().app)
        assertEquals(ExperimentalNavigationSource.FramingHint.AMAP_NAVIGATION,s.framingHint())
    }
    @Test fun incrementalUpdatesRetainSourceWithoutRetainingRoadNames() {
        var clock=0L;val s=ExperimentalNavigationSource({clock},8000)
        s.accept(message("高德地图",1));clock=7000
        s.accept(Iap2Messages.buildRaw(0x5201) { string(3,"Synthetic road, discarded");u32(10,250) })
        clock=9000
        assertTrue(s.snapshot().fresh);assertEquals("高德地图",s.snapshot().sourceName)
        assertFalse(s.snapshot().toString().contains("Synthetic road"))
    }
    @Test fun staleDataAndNewSessionsNeverClaimAKnownSource() {
        var clock=0L;val s=ExperimentalNavigationSource({clock},8000)
        s.accept(message("高德地图",1));clock=8001
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
        s.clear();assertNull(s.snapshot().sourceName);assertNull(s.snapshot().routeState)
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
    }
    @Test fun malformedMessagesAreAtomicAndCannotRefreshStaleData() {
        var clock=0L;val s=ExperimentalNavigationSource({clock},8000)
        s.accept(message("高德地图",1));clock=9000
        assertFalse(s.accept(Iap2Frame(0x5201,message("Apple Maps",0).payload+byteArrayOf(0,3,0,1))))
        assertEquals("高德地图",s.snapshot().sourceName);assertEquals(1,s.snapshot().routeState)
        assertFalse(s.snapshot().fresh)
    }
    @Test fun duplicatesBadUtf8AndOversizedNamesAreRejected() {
        val s=ExperimentalNavigationSource()
        for(payload in listOf(
            message("AMap",1).payload+message("Apple Maps").payload,
            Iap2Messages.buildRaw(0x5201) { bytes(0x13,byteArrayOf(0xc3.toByte(),0x28)) }.payload,
            Iap2Messages.buildRaw(0x5201) { string(0x13,"x".repeat(129)) }.payload,
            Iap2Messages.buildRaw(0x5201) { bytes(1,byteArrayOf(1,0)) }.payload,
        ))assertFalse(s.accept(Iap2Frame(0x5201,payload)))
        assertNull(s.snapshot().sourceName)
    }
    @Test fun emptySourceClearsTheNavigationSuggestion() {
        val s=ExperimentalNavigationSource();s.accept(message("AMap",1));s.accept(message(""))
        assertEquals(ExperimentalNavigationSource.FramingHint.CENTERED,s.framingHint())
    }
    @Test fun unsupportedSourcesAndLookalikeNamesDoNotGetAnAmapOffset() {
        val s=ExperimentalNavigationSource()
        for(name in listOf("Not AMap","AMap-like","高德地图仿制","Unconfirmed new map")) {
            s.accept(message(name,1));assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
        }
        s.accept(message("AMap",1));s.accept(Iap2Messages.buildRaw(0x5201) { u8(0x14,0) })
        assertEquals(ExperimentalNavigationSource.FramingHint.UNCONFIRMED,s.framingHint())
    }
    @Test fun sourceProbeSummarizesRawNavigationTracesWithoutRoadOrDestinationContent() {
        for(id in listOf("5201","5202","5204")) {
            val raw="IAP2 RX [wired] 0x$id frame=90B body=5 params\n [3] road: PRIVATE ROAD\n raw-body=PRIVATE BYTES"
            val redacted=ExperimentalRouteGuidanceRequests.traceSummary(raw,true)
            assertFalse(redacted.contains("PRIVATE"));assertFalse(redacted.contains('\n'))
            assertTrue(redacted.contains("0x$id"))
        }
    }
    @Test fun normalModeAndUnrelatedMessagesRetainTheirOriginalTraceBehavior() {
        val raw="IAP2 RX [wireless] 0x5201\n normal trace"
        assertEquals(raw,ExperimentalRouteGuidanceRequests.traceSummary(raw,false))
        val music="IAP2 RX [wireless] 0x5001\n Music"
        assertEquals(music,ExperimentalRouteGuidanceRequests.traceSummary(music,true))
    }
}
