package com.shilapi.xcertplay

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A bounded log producer. All file work, including rotation and final close, runs off the UI. */
internal class SessionLogFile(
    val file: File,
    private val openOutput: (File) -> OutputStream = { it.outputStream() },
) : Closeable {
    private val lock = Any()
    private val pendingLines = AtomicInteger()
    private val completion = CompletableFuture<Unit>()
    private var output: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var linesSinceFlush = 0
    private var flushTask: ScheduledFuture<*>? = null
    private var closed = false
    private val lineFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun reset(header: String) {
        synchronized(lock) {
            if (closed) return
            writer.execute {
                try {
                    finishOutput()
                    file.parentFile?.mkdirs()
                    if (file.isFile && file.length() > 0L) {
                        val previous = File(file.parentFile, file.name + ".previous")
                        if (previous.exists() && !previous.delete()) throw IOException("Cannot rotate previous session log")
                        if (!file.renameTo(previous)) throw IOException("Cannot rotate session log")
                    }
                    output = openOutput(file).buffered()
                    bytesWritten = 0L
                    writeLine(header)
                } catch (_: IOException) { finishOutput() }
            }
        }
    }

    fun append(line: String) = enqueue { line }
    fun appendTimestamped(message: String, timestampMillis: Long) = enqueue {
        "${lineFormatter.format(Date(timestampMillis))}  $message"
    }

    private fun enqueue(line: () -> String) {
        synchronized(lock) {
            if (closed || pendingLines.get() >= MAX_PENDING_LINES) return
            pendingLines.incrementAndGet()
            writer.execute {
                try { writeLine(line()) }
                catch (_: IOException) { finishOutput() }
                finally { pendingLines.decrementAndGet() }
            }
        }
    }

    private fun writeLine(line: String) {
        var bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size >= MAX_BYTES) {
            var start = bytes.size - (MAX_BYTES - 1)
            while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            bytes = bytes.copyOfRange(start, bytes.size)
        }
        if (bytesWritten + bytes.size + 1 > MAX_BYTES) {
            finishOutput()
            output = openOutput(file).buffered()
            bytesWritten = 0L
        }
        val activeOutput = output ?: return
        activeOutput.write(bytes)
        activeOutput.write('\n'.code)
        bytesWritten += bytes.size + 1
        if (++linesSinceFlush >= FLUSH_LINES) flush()
        else if (flushTask == null) {
            flushTask = writer.schedule({
                flushTask = null
                try { flush() } catch (_: IOException) { finishOutput() }
            }, FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        output?.flush()
        linesSinceFlush = 0
        flushTask?.cancel(false)
        flushTask = null
    }

    private fun finishOutput() {
        flushTask?.cancel(false)
        flushTask = null
        runCatching { output?.close() }
        output = null
        linesSinceFlush = 0
    }

    /** Used from Activity teardown: no disk or executor wait on the calling thread. */
    fun closeAsync() {
        synchronized(lock) {
            if (closed) return
            closed = true
            writer.execute {
                try { finishOutput() }
                finally { completion.complete(Unit) }
            }
        }
    }

    /** A bounded await for non-UI owners and tests; queued final close still completes afterwards. */
    override fun close() {
        closeAsync()
        awaitClosed(CLOSE_WAIT_MILLIS)
    }

    fun awaitClosed(timeoutMillis: Long): Boolean = try {
        completion.get(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) { false }

    private companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_PENDING_LINES = 1024
        const val FLUSH_LINES = 32
        const val FLUSH_INTERVAL_MILLIS = 750L
        const val CLOSE_WAIT_MILLIS = 2000L
        // One ordered writer also serializes old-window close and new-window rotation.
        // Only append tasks are budgeted; reset/close cannot be discarded under log pressure.
        val writer = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "xcertplay-log-writer").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(5, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }
}
