package com.moonsonglabs.daml.sandbox

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A new intent cancels previous preparation/polling immediately; process transitions stay serialized. */
internal class SandboxLifecycle(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "Canton-lifecycle").apply { isDaemon = true } }
) : AutoCloseable {
    private val generation = AtomicLong()
    fun submit(action: (Long) -> Unit): Long {
        val id = generation.incrementAndGet()
        executor.execute { if (isCurrent(id)) action(id) }
        return id
    }
    fun enqueue(action: () -> Unit) { executor.execute(action) }
    fun isCurrent(id: Long): Boolean = generation.get() == id && !executor.isShutdown
    override fun close() { generation.incrementAndGet(); executor.shutdownNow() }
}
