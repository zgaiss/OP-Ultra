package com.shilapi.xcertplay

import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun openingAnotherUiRetainsOnePreviousTimelineAndBoundsHistory() {
        val path = File(folder.root, "xcertplay.log")
        SessionLogFile(path).use { it.reset("first UI"); it.append("resize evidence") }
        SessionLogFile(path).use { it.reset("second UI"); it.append("restored session") }
        val previous = File(folder.root, "xcertplay.log.previous")
        assertTrue(previous.readText().contains("resize evidence"))
        assertFalse(path.readText().contains("resize evidence"))
        SessionLogFile(path).use { it.reset("third UI") }
        assertTrue(previous.readText().contains("restored session"))
        assertFalse(previous.readText().contains("first UI"))
        assertEquals(2, folder.root.listFiles()!!.size)
    }
    @Test fun asyncCloseNeverWaitsForBlockedFileWorkAndFinalCloseStillRuns() {
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        val log=SessionLogFile(File(folder.root,"blocked.log")) {
            entered.countDown()
            check(unblock.await(5,TimeUnit.SECONDS))
            it.outputStream()
        }
        log.reset("header")
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val before=System.nanoTime()
        log.closeAsync()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<100)
        assertFalse(log.awaitClosed(1))
        unblock.countDown()
        assertTrue(log.awaitClosed(2000))
        assertTrue(log.file.readText().contains("header"))
    }

    @Test fun normalBatchDoesNotFlushEveryLineAndCloseFlushesTail() {
        val flushes=AtomicInteger()
        val data=java.io.ByteArrayOutputStream()
        val log=SessionLogFile(File(folder.root,"batch.log")) {
            object:OutputStream(){
                override fun write(value:Int){data.write(value)}
                override fun write(bytes:ByteArray,offset:Int,length:Int){data.write(bytes,offset,length)}
                override fun flush(){flushes.incrementAndGet()}
            }
        }
        log.reset("header")
        repeat(20){log.append("line-$it")}
        log.close()
        assertTrue(log.awaitClosed(2000))
        assertTrue(flushes.get()<10)
        assertTrue(data.toString("UTF-8").contains("line-19"))
    }
}
