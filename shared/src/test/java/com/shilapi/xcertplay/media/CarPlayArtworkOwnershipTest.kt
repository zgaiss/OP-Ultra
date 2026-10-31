package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import org.junit.Assert.*
import org.junit.Test

class CarPlayArtworkOwnershipTest {
    @Test fun lateSameTransferIdCompletionCannotAttachToNewItem() {
        // Exercise the bridge without starting its Android service or sending media commands.
        val field = CarPlayMediaSessionBridge::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val previous = field.get(null)
        val owner = Any()
        field.set(null, owner)
        try {
            CarPlayMediaSessionBridge.beginMediaSession(owner)
            CarPlayMediaSessionBridge.publish(owner, Iap2NowPlayingState(itemRevision=101,artworkFileTransferId=7))
            val oldEpoch = CarPlayMediaSessionBridge.artworkEpoch(owner,7)
            CarPlayMediaSessionBridge.publish(owner, Iap2NowPlayingState(itemRevision=102,artworkFileTransferId=7))
            val newEpoch = CarPlayMediaSessionBridge.artworkEpoch(owner,7)
            assertNotEquals(oldEpoch,newEpoch)
            CarPlayMediaSessionBridge.publishArtwork(owner,7,byteArrayOf(1),oldEpoch)
            assertFalse(CarPlayMediaSessionBridge.hasArtwork(owner,7))
            CarPlayMediaSessionBridge.publishArtwork(owner,7,byteArrayOf(2),newEpoch)
            assertTrue(CarPlayMediaSessionBridge.hasArtwork(owner,7))
            CarPlayMediaSessionBridge.publish(owner,Iap2NowPlayingState(itemRevision=103,artworkFileTransferId=7))
            CarPlayMediaSessionBridge.publishArtwork(Any(),7,byteArrayOf(3),newEpoch)
            assertFalse(CarPlayMediaSessionBridge.hasArtwork(owner,7))
        } finally {
            CarPlayMediaSessionBridge.endMediaSession(owner)
            field.set(null,previous)
        }
    }
    @Test fun persistentControllerDisconnectAndReconnectResetAllMedia() {
        val field = CarPlayMediaSessionBridge::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val previous = field.get(null); val owner = Any(); field.set(null,owner)
        try {
            CarPlayMediaSessionBridge.beginMediaSession(owner)
            CarPlayMediaSessionBridge.publish(owner,Iap2NowPlayingState(title="same",itemRevision=1,artworkFileTransferId=7))
            val first=CarPlayMediaSessionBridge.ultraRead()
            val epoch=CarPlayMediaSessionBridge.artworkEpoch(owner,7)
            assertTrue(first.connected)
            CarPlayMediaSessionBridge.endMediaSession(owner)
            assertFalse(CarPlayMediaSessionBridge.ultraRead().connected)
            assertEquals("",CarPlayMediaSessionBridge.ultraRead().title)
            CarPlayMediaSessionBridge.publish(owner,Iap2NowPlayingState(title="late"))
            assertEquals("",CarPlayMediaSessionBridge.ultraRead().title)
            CarPlayMediaSessionBridge.beginMediaSession(owner)
            CarPlayMediaSessionBridge.publish(owner,Iap2NowPlayingState(title="same",itemRevision=1,artworkFileTransferId=7))
            val next=CarPlayMediaSessionBridge.ultraRead()
            assertTrue(next.connected); assertNotEquals(first.sessionId,next.sessionId)
            CarPlayMediaSessionBridge.publishArtwork(owner,7,byteArrayOf(1),epoch)
            assertFalse(CarPlayMediaSessionBridge.hasArtwork(owner,7))
        } finally { CarPlayMediaSessionBridge.endMediaSession(owner); field.set(null,previous) }
    }
}
