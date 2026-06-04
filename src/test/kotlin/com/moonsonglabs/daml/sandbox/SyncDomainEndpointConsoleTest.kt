package com.moonsonglabs.daml.sandbox

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

class SyncDiagnosticCatalogTest {
    @Test
    fun `built-in diagnostics cover real synchronizer endpoint checks`() {
        val presets = SyncDiagnosticCatalog.builtInPresets()

        assertTrue(presets.any { it.id == "ports" && it.kind == SyncDiagnosticKind.TCP })
        assertTrue(presets.any { it.id == "all-status" && it.scriptBody.contains("health.status") })
        assertTrue(presets.any { it.id == "sequencer-status" && it.scriptBody.contains("local.health.status") })
        assertTrue(presets.any { it.id == "mediator-status" && it.scriptBody.contains("mediator1.health.status") })
    }

    @Test
    fun `console script uses sandbox-console aliases and selected ports context`() {
        val profile = SandboxDefaults.newProfile(null)
        val sync = profile.synchronizers.first()
        val participant = profile.participants.first()
        val preset = SyncDiagnosticCatalog.builtInPresets().first { it.id == "sequencer-status" }

        val script = SyncDiagnosticCatalog.script(profile, sync, participant, preset)

        assertTrue(script.contains("Sequencer public: grpc://127.0.0.1:${sync.sequencer.publicPort}"))
        assertTrue(script.contains("Sequencer admin: grpc://127.0.0.1:${sync.sequencer.adminPort}"))
        assertTrue(script.contains("Mediator admin: grpc://127.0.0.1:${sync.mediator.adminPort}"))
        assertTrue(script.contains("println(local.health.status)"))
        assertTrue(script.contains(SyncDiagnosticCatalog.END_MARKER))
    }

}

class SyncDomainEndpointConsoleTest : BasePlatformTestCase() {
    fun `test tcp diagnostic returns json envelope`() {
        val profile = SandboxDefaults.newProfile(null)
        val sync = profile.synchronizers.first()
        val preset = SyncDiagnosticCatalog.builtInPresets().first { it.id == "ports" }

        val response = SyncDomainDiagnosticRunner(project).run(profile, sync, preset)
        val json = JsonParser.parseString(response.output).asJsonObject

        assertEquals("tcp", json.get("transport").asString)
        assertEquals(sync.name, json.get("synchronizer").asString)
        assertEquals(3, json.getAsJsonArray("checks").size())
    }

    fun `test console runs selected synchronizer diagnostic`() {
        val profile = SandboxDefaults.newProfile(null)
        val sync = profile.synchronizers.first()
        var capturedSync: SynchronizerNode? = null
        var capturedPreset: SyncDiagnosticPreset? = null
        val console = SyncDomainEndpointConsole(
            project,
            SandboxSessionService.getInstance(project),
            diagnosticRunner = { _, selectedSync, preset ->
                capturedSync = selectedSync
                capturedPreset = preset
                SyncDiagnosticResponse(0, "Status for Sequencer '${selectedSync.sequencer.name}'", 12)
            },
            backgroundExecutor = { it() }
        )

        console.setContext(profile, runningState(profile), sync.id)
        console.selectPresetForTest("sequencer-status")
        console.runSelectedForTest()
        flushEdt()

        assertEquals(sync.id, capturedSync?.id)
        assertEquals("sequencer-status", capturedPreset?.id)
        assertEquals(sync.name, console.selectedSyncNameForTest())
    }

    fun `test stopped sandbox blocks synchronizer diagnostics`() {
        val profile = SandboxDefaults.newProfile(null)
        var called = false
        val console = SyncDomainEndpointConsole(
            project,
            SandboxSessionService.getInstance(project),
            diagnosticRunner = { _, _, _ ->
                called = true
                SyncDiagnosticResponse(0, "unexpected", 1)
            },
            backgroundExecutor = { it() }
        )

        console.setContext(profile, SandboxSessionState(profileId = profile.id, status = SandboxSessionStatus.STOPPED), profile.synchronizers.first().id)
        console.runSelectedForTest()
        flushEdt()

        assertFalse(called)
        assertTrue(console.privateField<JBTextArea>("resultArea").text.contains("Start sandbox"))
    }

    private fun runningState(profile: SandboxProfile): SandboxSessionState =
        SandboxSessionState(
            profileId = profile.id,
            status = SandboxSessionStatus.RUNNING,
            endpoints = EndpointBuilder.all(profile)
        )

    private fun flushEdt() {
        if (!SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeAndWait {}
    }

    private inline fun <reified T> Any.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }
}
