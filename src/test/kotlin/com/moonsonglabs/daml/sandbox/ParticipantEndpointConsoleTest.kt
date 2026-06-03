package com.moonsonglabs.daml.sandbox

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

class SandboxEndpointCatalogTest {
    @Test
    fun `built-in presets include read and write ledger endpoints`() {
        val presets = SandboxEndpointCatalog.builtInPresets()

        assertTrue(presets.any { it.method == "GET" && it.path == "/v2/packages" })
        assertTrue(presets.any { it.method == "POST" && it.path == "/v2/state/active-contracts" && !it.isMutating })
        assertTrue(presets.any { it.method == "POST" && it.path == "/v2/commands/submit-and-wait" && it.isMutating })
        assertEquals(TopologyGraphTheme.participantBorder, endpointMethodColor("GET"))
        assertEquals(TopologyGraphTheme.warning, endpointRiskColor(SandboxEndpointRisk.WRITE))
    }

    @Test
    fun `request bodies use participant parties offsets and deterministic command ids`() {
        val profile = SandboxDefaults.newProfile(null)
        val participant = profile.participants.first()
        profile.partyAllocations.clear()
        profile.partyAllocations.add(PartyAllocation("Bank", participant.id, SandboxDefaults.SHARED_SYNCHRONIZER_ID))

        val activePreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "active-contracts" }
        val active = JsonParser.parseString(SandboxEndpointCatalog.requestBody(activePreset, profile, participant, 42)).asJsonObject
        assertEquals(42, active.get("activeAtOffset").asInt)
        assertTrue(active.getAsJsonObject("eventFormat").getAsJsonObject("filtersByParty").has("Bank"))

        val commandPreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "submit-wait" }
        val command = JsonParser.parseString(SandboxEndpointCatalog.requestBody(commandPreset, profile, participant)).asJsonObject
        assertEquals("cmd-issuer-submit-wait", command.get("commandId").asString)
        assertEquals("Bank", command.getAsJsonArray("actAs").first().asString)

        val transactionPreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "submit-transaction" }
        val transaction = JsonParser.parseString(SandboxEndpointCatalog.requestBody(transactionPreset, profile, participant)).asJsonObject
        assertEquals("cmd-issuer-submit-transaction", transaction.getAsJsonObject("commands").get("commandId").asString)

        val createPartyPreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "create-party" }
        val createParty = JsonParser.parseString(SandboxEndpointCatalog.requestBody(createPartyPreset, profile, participant)).asJsonObject
        assertTrue(createParty.has("localMetadata"))
        assertTrue(createParty.get("localMetadata").isJsonNull)
        assertEquals("participant_admin", createParty.get("userId").asString)
    }

    @Test
    fun `request bodies prefer generated allocated party ids when available`() {
        val root = Files.createTempDirectory("managed-sandbox-console-test")
        try {
            val profile = SandboxDefaults.newProfile(null)
            val participant = profile.participants.first()
            profile.generatedPath = root.toString()
            profile.partyAllocations.clear()
            profile.partyAllocations.add(PartyAllocation("Bank", participant.id, SandboxDefaults.SHARED_SYNCHRONIZER_ID))
            Files.writeString(
                root.resolve("participants.json"),
                """
                {
                  "party_participants": {
                    "Bank::1220abc": "issuer",
                    "Other::1220abc": "investor"
                  }
                }
                """.trimIndent()
            )

            val activePreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "active-contracts" }
            val active = JsonParser.parseString(SandboxEndpointCatalog.requestBody(activePreset, profile, participant, 42)).asJsonObject
            assertTrue(active.getAsJsonObject("eventFormat").getAsJsonObject("filtersByParty").has("Bank::1220abc"))

            val commandPreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "submit-wait" }
            val command = JsonParser.parseString(SandboxEndpointCatalog.requestBody(commandPreset, profile, participant)).asJsonObject
            assertEquals("Bank::1220abc", command.getAsJsonArray("actAs").first().asString)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `party allocation body marks multi synchronizer participant as needing full synchronizer id`() {
        val profile = SandboxDefaults.newProfile(null)
        val participant = profile.participants.first()
        val privateSync = SandboxDefaults.synchronizer(2, profile.portBase)
        profile.synchronizers.add(privateSync)
        profile.bindings.add(ParticipantSyncBinding(participant.id, privateSync.id, true))

        val createPartyPreset = SandboxEndpointCatalog.builtInPresets().first { it.id == "create-party" }
        val createParty = JsonParser.parseString(SandboxEndpointCatalog.requestBody(createPartyPreset, profile, participant)).asJsonObject

        assertEquals("<full-synchronizer-id>", createParty.get("synchronizerId").asString)
    }

    @Test
    fun `openapi enrichment updates curated descriptions without requiring full dynamic generation`() {
        val enriched = SandboxEndpointCatalog.enrichFromOpenApi(
            """
            openapi: 3.0.3
            paths:
              /v2/packages:
                get:
                  description: Packages from participant OpenAPI
              /v2/commands/submit-and-wait:
                post:
                  description: Submit commands from participant OpenAPI
            """.trimIndent()
        )

        assertEquals(
            "Packages from participant OpenAPI",
            enriched.first { it.path == "/v2/packages" }.description
        )
        assertEquals(
            "Submit commands from participant OpenAPI",
            enriched.first { it.path == "/v2/commands/submit-and-wait" }.description
        )
    }

    @Test
    fun `mutating detection is conservative for command and party writes`() {
        assertFalse(SandboxEndpointCatalog.isMutating("POST", "/v2/state/active-contracts"))
        assertFalse(SandboxEndpointCatalog.isMutating("POST", "/v2/updates?limit=200"))
        assertTrue(SandboxEndpointCatalog.isMutating("POST", "/v2/commands/async/submit"))
        assertTrue(SandboxEndpointCatalog.isMutating("POST", "/v2/parties"))
        assertTrue(SandboxEndpointCatalog.isMutating("PATCH", "/v2/users/alice/identity-provider-id"))
    }
}

class ParticipantEndpointConsoleTest : BasePlatformTestCase() {
    fun `test console constructs with themed participant metadata`() {
        val profile = twoParticipantProfile()
        val console = console(
            sender = { _, _, path, _, _ ->
                if (path == "/docs/openapi") SandboxHttpResponse(200, "openapi: 3.0.3\npaths: {}", emptyMap(), 3)
                else SandboxHttpResponse(200, """{"ok":true}""", emptyMap(), 7)
            }
        )

        console.setContext(profile, runningState(profile), profile.participants.first().id)
        flushEdt()

        assertEquals(TopologyGraphTheme.canvas, console.background)
        assertEquals("issuer", console.selectedParticipantNameForTest())
        assertTrue(console.privateField<JBLabel>("endpointDetails").text.contains(profile.participants.first().jsonPort.toString()))

        console.setContext(profile, runningState(profile), profile.participants[1].id)
        flushEdt()

        assertEquals("investor", console.selectedParticipantNameForTest())
        assertTrue(console.privateField<JBLabel>("endpointDetails").text.contains(profile.participants[1].jsonPort.toString()))
    }

    fun `test send success displays status duration headers and pretty json`() {
        val profile = twoParticipantProfile()
        var sentPath = ""
        val console = console(
            sender = { _, _, path, _, _ ->
                if (path == "/docs/openapi") {
                    SandboxHttpResponse(200, "openapi: 3.0.3\npaths: {}", emptyMap(), 2)
                } else {
                    sentPath = path
                    SandboxHttpResponse(200, """{"ok":true}""", mapOf("content-type" to listOf("application/json")), 11)
                }
            }
        )
        console.setContext(profile, runningState(profile), profile.participants.first().id)
        console.selectPresetForTest("parties")

        console.sendSelectedForTest()
        flushEdt()

        assertEquals("/v2/parties", sentPath)
        val response = console.privateField<JBTextArea>("responseArea").text
        assertTrue(response.contains("HTTP 200"))
        assertTrue(response.contains("Duration: 11 ms"))
        assertTrue(response.contains("\"ok\": true"))
    }

    fun `test send failure is inline and readable`() {
        val profile = twoParticipantProfile()
        val console = console(
            sender = { _, _, path, _, _ ->
                if (path == "/docs/openapi") SandboxHttpResponse(200, "openapi: 3.0.3\npaths: {}", emptyMap(), 1)
                else error("boom")
            }
        )
        console.setContext(profile, runningState(profile), profile.participants.first().id)
        console.selectPresetForTest("packages")

        console.sendSelectedForTest()
        flushEdt()

        assertTrue(console.privateField<JBTextArea>("responseArea").text.contains("Request failed: boom"))
    }

    fun `test mutating requests require confirmation before sending`() {
        val profile = twoParticipantProfile()
        var confirmed = false
        var sentWrite = false
        val console = console(
            confirm = {
                confirmed = true
                false
            },
            sender = { _, _, path, _, _ ->
                if (path == "/docs/openapi") SandboxHttpResponse(200, "openapi: 3.0.3\npaths: {}", emptyMap(), 1)
                else {
                    sentWrite = true
                    SandboxHttpResponse(200, """{"ok":true}""", emptyMap(), 1)
                }
            }
        )
        console.setContext(profile, runningState(profile), profile.participants.first().id)
        console.selectPresetForTest("create-party")

        console.sendSelectedForTest()
        flushEdt()

        assertTrue(confirmed)
        assertFalse(sentWrite)
    }

    fun `test stopped sandbox keeps requests visible but blocks sending`() {
        val profile = twoParticipantProfile()
        var sent = false
        val console = console(
            sender = { _, _, _, _, _ ->
                sent = true
                SandboxHttpResponse(200, """{"ok":true}""", emptyMap(), 1)
            }
        )
        console.setContext(profile, SandboxSessionState(profileId = profile.id, status = SandboxSessionStatus.STOPPED), profile.participants.first().id)
        console.selectPresetForTest("packages")

        console.sendSelectedForTest()
        flushEdt()

        assertFalse(sent)
        assertTrue(console.privateField<JBTextArea>("responseArea").text.contains("Start sandbox to send requests"))
    }

    fun `test renders endpoint console screenshot`() {
        val profile = twoParticipantProfile()
        val console = console(
            sender = { _, _, path, _, _ ->
                if (path == "/docs/openapi") SandboxHttpResponse(200, "openapi: 3.0.3\npaths: {}", emptyMap(), 1)
                else SandboxHttpResponse(200, """{"partyDetails":[]}""", emptyMap(), 6)
            }
        )
        console.setContext(profile, runningState(profile), profile.participants.first().id)
        console.selectPresetForTest("parties")
        console.setSize(1180, 640)
        layoutTree(console)

        val image = BufferedImage(console.width, console.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        console.paint(graphics)
        graphics.dispose()

        val output = Path.of("build", "reports", "managed-sandbox", "participant-endpoint-console.png").toAbsolutePath()
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())

        assertTrue("Endpoint console screenshot was not written to $output", Files.isRegularFile(output))
        assertTrue("Endpoint console screenshot appears blank", distinctColors(image) > 10)
    }

    private fun console(
        confirm: (SandboxEndpointPreset) -> Boolean = { true },
        sender: (Endpoint, String, String, String?, String?) -> SandboxHttpResponse
    ): ParticipantEndpointConsole =
        ParticipantEndpointConsole(
            project,
            SandboxSessionService.getInstance(project),
            confirmWrite = confirm,
            requestSender = sender,
            backgroundExecutor = { it() }
        )

    private fun twoParticipantProfile(): SandboxProfile =
        SandboxDefaults.newProfile(null).apply {
            participants.add(SandboxDefaults.participant(2, portBase))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers.first().id, true))
            partyAllocations.add(PartyAllocation("Bob", participants[1].id, synchronizers.first().id))
        }

    private fun runningState(profile: SandboxProfile): SandboxSessionState =
        SandboxSessionState(
            profileId = profile.id,
            status = SandboxSessionStatus.RUNNING,
            endpoints = EndpointBuilder.all(profile),
            health = EndpointBuilder.participantEndpoints(profile)
                .filter { it.kind == "json" }
                .map { HealthSnapshot(it, live = true, ready = true, message = "ready") }
        )

    private inline fun <reified T> Any.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }

    private fun flushEdt() {
        if (!SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeAndWait {}
    }

    private fun layoutTree(container: Container) {
        if (SwingUtilities.isEventDispatchThread()) {
            layoutTreeNow(container)
        } else {
            SwingUtilities.invokeAndWait { layoutTreeNow(container) }
        }
    }

    private fun layoutTreeNow(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTreeNow)
    }

    private fun distinctColors(image: BufferedImage): Int {
        val colors = mutableSetOf<Int>()
        val stepX = (image.width / 80).coerceAtLeast(1)
        val stepY = (image.height / 80).coerceAtLeast(1)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                colors.add(image.getRGB(x, y))
                x += stepX
            }
            y += stepY
        }
        return colors.size
    }
}
