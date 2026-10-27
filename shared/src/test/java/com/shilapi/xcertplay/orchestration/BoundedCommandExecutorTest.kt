package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedCommandExecutorTest {
    @Test fun mergesOnlyContiguousMovesAndPreservesReleaseBarrier() {
        val executor = BoundedCommandExecutor(8)
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val done = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val session = Any()
        try {
            executor.execute { entered.countDown(); unblock.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(executor.submit(session, false, Runnable { events += "down" }))
            repeat(1000) { i -> assertTrue(executor.submit(session, true, Runnable { events += "move:$i" })) }
            assertTrue(executor.submit(session, false, Runnable { events += "up" }))
            assertTrue(executor.submit(session, true, Runnable { events += "next-move" }))
            executor.execute { done.countDown() }
            unblock.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("down", "move:999", "up", "next-move"), events)
        } finally { unblock.countDown(); executor.shutdownNow() }
    }

    @Test fun queueHasHardBudgetAndDifferentSessionIsABarrier() {
        val executor = BoundedCommandExecutor(2)
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        try {
            executor.execute { entered.countDown(); unblock.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(executor.submit(Any(), true, Runnable {}))
            assertTrue(executor.submit(Any(), true, Runnable {}))
            assertFalse(executor.submit(Any(), false, Runnable {}))
            executor.shutdownNow()
            assertFalse(executor.submit(Any(), false, Runnable {}))
        } finally { unblock.countDown(); executor.shutdownNow() }
    }
}
