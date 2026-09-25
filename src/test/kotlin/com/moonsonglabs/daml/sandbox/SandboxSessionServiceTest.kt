package com.moonsonglabs.daml.sandbox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SandboxSessionServiceTest : BasePlatformTestCase() {
    private class Process : SandboxManagedProcess {
        @Volatile private var terminated = false
        @Volatile var observeTermination = false
        val terminationObserved = CountDownLatch(1)
        override var isTerminated: Boolean
            get() {
                if (observeTermination && terminated) terminationObserved.countDown()
                return terminated
            }
            set(value) { terminated = value }
        var exitOnStart: Int? = null
        var refuseStop = false
        var onText: (String) -> Unit = {}
        var onExit: (Int) -> Unit = {}
        override fun start(onText: (String) -> Unit, onExit: (Int) -> Unit) {
            this.onText = onText; this.onExit = onExit
            onText("=== sandbox ready ===\n")
            exitOnStart?.let { exit(it); observeTermination = true }
        }
        fun exit(code: Int) { isTerminated = true; onExit(code) }
        override fun terminate() { if (!refuseStop) isTerminated = true }
        override fun waitFor(timeoutMs: Long) = isTerminated
    }

    private fun service(processes: MutableList<Process>): SandboxSessionService = SandboxSessionService(project).apply {
        readinessPollInterval = Duration.ofMillis(10)
        readinessTimeout = Duration.ofMillis(200)
        stopTimeoutMs = 10
        runtimeOperations = SandboxRuntimeOperations(
            prepare = { profile, cancelled -> if (cancelled()) null else prepared(profile) },
            launch = { Process().also { processes += it } },
            probe = { profile, _ -> EndpointBuilder.participantEndpoints(profile).filter { it.kind == "json" }
                .map { HealthSnapshot(it, true, true, "ready") } }
        )
    }

    private fun prepared(profile: SandboxProfile): SandboxPreparedLaunch {
        val root = Files.createTempDirectory("lifecycle-fixture")
        val runtime = profile.deepCopy().apply { workspacePath = root.toString(); generatedPath = root.resolve("generated").toString() }
        val files = SandboxGenerator(root).generate(runtime)
        return SandboxPreparedLaunch(runtime, files, GeneralCommandLine("unused-test-process"), "3.4.11")
    }

    fun `test stop during validation cancels before process creation`() {
        val processes = mutableListOf<Process>()
        val service = service(processes)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        try {
            service.runtimeOperations = service.runtimeOperations.copy(prepare = { profile, cancelled ->
                entered.countDown(); released.await(5, TimeUnit.SECONDS)
                if (cancelled()) null else prepared(profile)
            })
            service.startLocal(SandboxDefaults.newProfile(null))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.stop(); released.countDown()
            await { service.snapshot().status == SandboxSessionStatus.STOPPED }
            assertTrue(processes.isEmpty())
            assertFalse(service.snapshot().ownsProcess)
        } finally { released.countDown(); service.dispose() }
    }

    fun `test synchronous process exit cannot be overwritten by starting state`() {
        val processes = mutableListOf<Process>()
        val service = service(processes)
        val process = Process().apply { exitOnStart = 1 }
        try {
            service.runtimeOperations = service.runtimeOperations.copy(launch = { process })
            service.startLocal(SandboxDefaults.newProfile(null))
            assertTrue(process.terminationObserved.await(5, TimeUnit.SECONDS))
            assertEquals(SandboxSessionStatus.FAILED, service.snapshot().status)
            assertEquals("Process exited with code 1", service.snapshot().message)
            assertFalse(service.snapshot().ownsProcess)
        } finally { service.dispose() }
    }

    fun `test exit during ready probe preserves failure`() = assertExitDuringProbe(ready = true, exitCode = 1)

    fun `test exit during nonready probe preserves stopped state`() = assertExitDuringProbe(ready = false, exitCode = 0)

    private fun assertExitDuringProbe(ready: Boolean, exitCode: Int) {
        val processes = mutableListOf<Process>()
        val service = service(processes)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val expected = if (exitCode == 0) SandboxSessionStatus.STOPPED else SandboxSessionStatus.FAILED
        try {
            service.runtimeOperations = service.runtimeOperations.copy(probe = { profile, _ ->
                entered.countDown()
                assertTrue(released.await(5, TimeUnit.SECONDS))
                EndpointBuilder.participantEndpoints(profile).filter { it.kind == "json" }
                    .map { HealthSnapshot(it, ready, ready, "probe completed") }
            })
            service.startLocal(SandboxDefaults.newProfile(null))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val process = processes.single()
            process.exit(exitCode)
            process.observeTermination = true
            released.countDown()
            assertTrue(process.terminationObserved.await(5, TimeUnit.SECONDS))
            assertEquals(expected, service.snapshot().status)
            assertEquals("Process exited with code $exitCode", service.snapshot().message)
            assertFalse(service.snapshot().ownsProcess)
        } finally { released.countDown(); service.dispose() }
    }

    fun `test disposal during launch terminates the process returned afterwards`() {
        val processes = mutableListOf<Process>()
        val service = service(processes)
        val process = Process()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        try {
            service.runtimeOperations = service.runtimeOperations.copy(launch = {
                entered.countDown()
                // OS creation may finish after interruption, so force that ordering here.
                while (released.count > 0) {
                    try { released.await() } catch (_: InterruptedException) { }
                }
                process
            })
            service.startLocal(SandboxDefaults.newProfile(null))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.dispose()
            released.countDown()
            await { process.isTerminated }
            assertFalse(service.snapshot().ownsProcess)
        } finally { released.countDown(); service.dispose() }
    }

    fun `test disposal terminates owned process and ignores later callbacks`() {
        val processes = mutableListOf<Process>()
        val service = service(processes)
        try {
            service.startLocal(SandboxDefaults.newProfile(null))
            await { service.snapshot().status == SandboxSessionStatus.RUNNING }
            val process = processes.single()
            service.dispose()
            val disposedState = service.snapshot()
            process.onText("late output after disposal")
            process.onExit(1)
            assertTrue(process.isTerminated)
            assertFalse(disposedState.ownsProcess)
            assertEquals(disposedState, service.snapshot())
        } finally { service.dispose() }
    }

    fun `test late callbacks after restart cannot change new runtime or logs`() {
        val processes = mutableListOf<Process>(); val service = service(processes)
        val profile = SandboxDefaults.newProfile(null)
        try {
            service.startLocal(profile); await { service.snapshot().status == SandboxSessionStatus.RUNNING }
            val first = processes.single(); val initial = service.snapshot()
            val edited = profile.deepCopy().apply { participants.first().name = "edited" }
            assertTrue(initial.hasPendingChanges(edited))
            assertEquals("issuer", initial.launchedProfile!!.participants.first().name)
            service.startLocal(edited)
            await { service.snapshot().status == SandboxSessionStatus.RUNNING && service.snapshot().sessionId != initial.sessionId }
            first.onText("stale old process"); first.onExit(1)
            assertEquals(SandboxSessionStatus.RUNNING, service.snapshot().status)
            assertFalse(service.snapshot().log.contains("stale old process"))
            assertEquals("edited", service.snapshot().launchedProfile!!.participants.first().name)
        } finally { service.dispose() }
    }

    fun `test failed stop retains ownership and refuses replacement until confirmed terminated`() {
        val processes = mutableListOf<Process>(); val service = service(processes)
        val profile = SandboxDefaults.newProfile(null)
        try {
            service.startLocal(profile); await { service.snapshot().status == SandboxSessionStatus.RUNNING }
            processes.single().refuseStop = true
            service.stop(); await { service.snapshot().status == SandboxSessionStatus.FAILED }
            assertTrue(service.snapshot().ownsProcess)
            val preparations = AtomicInteger()
            val operation = service.runtimeOperations.prepare
            service.runtimeOperations = service.runtimeOperations.copy(prepare = { p, c -> preparations.incrementAndGet(); operation(p, c) })
            service.startLocal(profile)
            await { service.snapshot().message == "The previous Canton process has not stopped." }
            assertEquals(0, preparations.get())
            assertEquals(1, processes.size)
            assertTrue(service.snapshot().ownsProcess)
            processes.single().isTerminated = true
            service.startLocal(profile); await { processes.size == 2 && service.snapshot().status == SandboxSessionStatus.RUNNING }
        } finally { service.dispose() }
    }

    fun `test stop during health probe and obsolete cross profile responses are isolated`() {
        val processes = mutableListOf<Process>(); val service = service(processes)
        val profile = SandboxDefaults.newProfile(null)
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        try {
            service.runtimeOperations = service.runtimeOperations.copy(probe = { _, cancelled ->
                entered.countDown(); released.await(5, TimeUnit.SECONDS)
                assertTrue(cancelled()); emptyList()
            })
            service.startLocal(profile); assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.stop(); released.countDown()
            await { service.snapshot().status == SandboxSessionStatus.STOPPED }
            assertFalse(service.snapshot().ownsProcess)
            val before = service.snapshot()
            service.refreshHealth(SandboxDefaults.newProfile(null))
            assertEquals(before, service.snapshot())
        } finally { released.countDown(); service.dispose() }
    }

    fun `test readiness timeout preserves process and graph layout is presentation only`() {
        val processes = mutableListOf<Process>(); val service = service(processes)
        val profile = SandboxDefaults.newProfile(null)
        try {
            service.runtimeOperations = service.runtimeOperations.copy(probe = { _, _ -> emptyList() })
            service.startLocal(profile); await { service.snapshot().status == SandboxSessionStatus.FAILED }
            assertTrue(service.snapshot().ownsProcess)
            val snapshot = service.snapshot()
            profile.topologyPositions.add(TopologyNodePosition(profile.participants.first().id, 10, 20))
            assertFalse(snapshot.hasPendingChanges(profile))
            profile.bindings.first().connected = false
            assertTrue(snapshot.hasPendingChanges(profile))
            assertTrue(snapshot.launchedProfile!!.bindings.first().connected)
            snapshot.launchedProfile!!.participants.first().name = "external mutation"
            assertEquals("issuer", service.snapshot().launchedProfile!!.participants.first().name)
        } finally { service.dispose() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Lifecycle condition did not complete", condition())
    }
}
