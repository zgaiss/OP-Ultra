package com.shilapi.xcertplay.orchestration

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serial control writes with bounded work and tail-only MOVE replacement. */
internal class BoundedCommandExecutor(capacity: Int = 256) : Executor {
    private val lock = Any()
    private val queue = LinkedBlockingDeque<Runnable>(capacity)
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue)

    private inner class Command(val owner: Any?, val replaceable: Boolean, var action: Runnable) : Runnable {
        override fun run() {
            val work = synchronized(lock) { action }
            work.run()
        }
    }

    fun submit(owner: Any, replaceable: Boolean, action: Runnable): Boolean = synchronized(lock) {
        if (executor.isShutdown) return false
        val last = queue.peekLast() as? BoundedCommandExecutor.Command
        if (replaceable && last?.replaceable == true && last.owner === owner) {
            last.action = action
            return true
        }
        try {
            executor.execute(Command(owner, replaceable, action))
            true
        } catch (_: RejectedExecutionException) { false }
    }

    override fun execute(command: Runnable) = synchronized(lock) {
        // Non-touch commands are barriers: never merge a MOVE across them.
        executor.execute(Command(null, false, command))
    }

    fun shutdownNow() { synchronized(lock) { executor.shutdownNow() } }
}
