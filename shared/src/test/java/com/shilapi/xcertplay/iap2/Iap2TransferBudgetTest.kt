package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.session.Iap2FileTransferHandler
import org.junit.Assert.*
import org.junit.Test

class Iap2TransferBudgetTest {
    @Test fun concurrentArtworkIsBoundedAndCompletionFreesASlot() {
        val handler = Iap2FileTransferHandler()
        for (id in 1..4) assertArrayEquals(byteArrayOf(id.toByte(), 1), handler.handle(setup(id, 1)).response)
        assertArrayEquals(byteArrayOf(5, 6), handler.handle(setup(5, 1)).response)
        assertArrayEquals(byteArrayOf(1, 5), handler.handle(byteArrayOf(1, 0xc0.toByte(), 7)).response)
        assertArrayEquals(byteArrayOf(5, 1), handler.handle(setup(5, 1)).response)
    }

    @Test fun declaredAndUnknownSizesShareATotalBudget() {
        val handler = Iap2FileTransferHandler()
        val sixteenMiB = 16 * 1024 * 1024
        assertArrayEquals(byteArrayOf(1, 1), handler.handle(setup(1, sixteenMiB)).response)
        assertArrayEquals(byteArrayOf(2, 1), handler.handle(setup(2, 0)).response)
        assertArrayEquals(byteArrayOf(3, 6), handler.handle(setup(3, 1)).response)
        handler.handle(byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(3, 1), handler.handle(setup(3, sixteenMiB)).response)
    }

    @Test fun expirationAndCacheHitDoNotKeepReservations() {
        var now = 0L
        val handler = Iap2FileTransferHandler { now }
        repeat(4) { handler.handle(setup(it, 1)) }
        assertArrayEquals(byteArrayOf(8, 5), handler.handle(setup(8, 1)) { true }.response)
        now = 30_001
        assertEquals(4, handler.expire().size)
        assertArrayEquals(byteArrayOf(8, 1), handler.handle(setup(8, 1)).response)
    }

    private fun setup(id: Int, size: Int): ByteArray = ByteArray(12).also { bytes ->
        bytes[0] = id.toByte(); bytes[1] = 4; bytes[11] = 2
        repeat(8) { bytes[2 + it] = (size.toLong() ushr (56 - 8 * it)).toByte() }
    }
}
