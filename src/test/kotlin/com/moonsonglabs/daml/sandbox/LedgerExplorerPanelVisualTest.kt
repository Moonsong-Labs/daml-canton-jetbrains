package com.moonsonglabs.daml.sandbox

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

class LedgerExplorerPanelVisualTest : BasePlatformTestCase() {
    fun `test renders collapsed explorer screenshot`() {
        val profileService = SandboxProfileService.getInstance(project)
        profileService.loadState(SandboxProfileService.State())
        val profile = explorerProfile()
        profileService.upsert(profile)
        profileService.selectProfile(profile.id)
        val panel = LedgerExplorerPanel(
            project,
            sessions = SandboxSessionService.getInstance(project),
            profiles = profileService
        ) {}

        try {
            panel.setProfile(profile)
            panel.setSession(SandboxSessionState(profileId = profile.id, status = SandboxSessionStatus.RUNNING, message = "Running"))
            panel.privateMethod("renderSnapshot", LedgerExplorerSnapshot::class.java)
                .invoke(panel, explorerSnapshot())
            panel.setSize(1480, 860)
            layoutTree(panel)

            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            panel.paint(graphics)
            graphics.dispose()

            val output = Path.of("build", "reports", "managed-sandbox", "ledger-explorer-collapsed.png").toAbsolutePath()
            Files.createDirectories(output.parent)
            ImageIO.write(image, "png", output.toFile())

            assertTrue("Explorer screenshot was not written to $output", Files.isRegularFile(output))
            assertTrue("Explorer screenshot appears blank", distinctColors(image) > 10)
            assertFalse("Explorer filter sidebar should default collapsed", panel.privateField<Boolean>("sidebarExpanded"))

            panel.privateSet("sidebarExpanded", true)
            panel.privateMethod("updateSidebar").invoke(panel)
            layoutTree(panel)
            val expandedImage = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
            val expandedGraphics = expandedImage.createGraphics()
            panel.paint(expandedGraphics)
            expandedGraphics.dispose()

            val expandedOutput = Path.of("build", "reports", "managed-sandbox", "ledger-explorer-expanded-filters.png").toAbsolutePath()
            ImageIO.write(expandedImage, "png", expandedOutput.toFile())
            assertTrue("Expanded Explorer screenshot was not written to $expandedOutput", Files.isRegularFile(expandedOutput))
            assertTrue("Expanded Explorer screenshot appears blank", distinctColors(expandedImage) > 10)
        } finally {
            panel.dispose()
            profileService.loadState(SandboxProfileService.State())
        }
    }

    fun `test renders live private settlement explorer screenshots`() {
        if (System.getenv("RUN_LIVE_EXPLORER_VISUAL") != "true") return

        val exampleRoot = Path.of(System.getenv("PRIVATE_SETTLEMENT_ROOT") ?: "../private-settlement")
            .toAbsolutePath()
            .normalize()
        val profile = explorerProfile().apply {
            id = "private-settlement"
            name = "Private Settlement Bridge"
            workspacePath = exampleRoot.toString()
            generatedPath = exampleRoot.resolve(".canton-sandboxes/private-settlement").toString()
            participants[0].apply {
                id = "issuer"
                ledgerPort = 7411
                adminPort = 7412
                jsonPort = 8575
            }
            participants[1].apply {
                id = "investor"
                ledgerPort = 7421
                adminPort = 7422
                jsonPort = 8576
            }
            participants[2].apply {
                id = "bridge"
                name = "bridge"
                ledgerPort = 7431
                adminPort = 7432
                jsonPort = 8577
            }
        }
        val explorer = SandboxLedgerExplorer()
        val snapshots = profile.participants.associateBy { it.name }.mapValues { (_, participant) ->
            explorer.fetch(profile, participant, token = null)
        }

        val bridgeRows = LedgerExplorerRows.from(snapshots.getValue("bridge"))
        val bridgeActive = bridgeRows.filter { it.kind == "Active" }
        assertEquals(listOf("PublicSettlement"), bridgeActive.map { it.templateName })
        assertEquals("global", bridgeActive.single().syncName)
        assertTrue(bridgeActive.single().parties.any { it.startsWith("BridgePublic::") })

        val bridgeHistory = bridgeRows.filter { it.kind == "Created" || it.kind == "Archived" }
        assertTrue(bridgeHistory.any { it.kind == "Created" && it.templateName == "PrivateOffer" && it.syncName == "privateSync" })
        assertTrue(bridgeHistory.any { it.kind == "Archived" && it.templateName == "PrivateOffer" && it.syncName == "privateSync" })
        assertTrue(bridgeHistory.any { it.kind == "Created" && it.templateName == "PrivateAccepted" && it.syncName == "privateSync" })
        assertTrue(bridgeHistory.any { it.kind == "Archived" && it.templateName == "PrivateAccepted" && it.syncName == "privateSync" })
        assertTrue(bridgeHistory.any { it.kind == "Created" && it.templateName == "PublicSettlement" && it.syncName == "global" })

        assertTrue(LedgerExplorerRows.from(snapshots.getValue("issuer")).none { it.kind == "Active" })
        assertTrue(LedgerExplorerRows.from(snapshots.getValue("investor")).none { it.kind == "Active" })
        writeLiveSummary(snapshots)

        val profileService = SandboxProfileService.getInstance(project)
        profileService.loadState(SandboxProfileService.State())
        profileService.upsert(profile)
        profileService.selectProfile(profile.id)
        val panel = LedgerExplorerPanel(
            project,
            sessions = SandboxSessionService.getInstance(project),
            profiles = profileService
        ) {}

        try {
            panel.setProfile(profile)
            panel.setSession(SandboxSessionState(profileId = profile.id, status = SandboxSessionStatus.RUNNING, message = "Running"))
            panel.privateMethod("renderSnapshot", LedgerExplorerSnapshot::class.java)
                .invoke(panel, snapshots.getValue("bridge"))
            panel.setSize(1480, 860)

            writeScreenshot(panel, "ledger-explorer-live-bridge-active.png")

            selectSegment(panel, "History")
            writeScreenshot(panel, "ledger-explorer-live-bridge-history.png")

            panel.privateMethod("renderSnapshot", LedgerExplorerSnapshot::class.java)
                .invoke(panel, snapshots.getValue("issuer"))
            selectSegment(panel, "Active")
            writeScreenshot(panel, "ledger-explorer-live-issuer-active-empty.png")
        } finally {
            panel.dispose()
            profileService.loadState(SandboxProfileService.State())
        }
    }

    private fun explorerProfile(): SandboxProfile =
        SandboxDefaults.newProfile(null).apply {
            name = "Private Settlement Bridge"
            portBase = 7400
            participants.clear()
            participants.add(SandboxDefaults.participant(1, portBase).apply { name = "issuer" })
            participants.add(SandboxDefaults.participant(2, portBase).apply { name = "investor" })
            participants.add(SandboxDefaults.participant(3, portBase).apply { name = "bridge" })
            synchronizers.clear()
            synchronizers.add(SandboxDefaults.sharedSynchronizer(portBase))
            synchronizers.add(SandboxDefaults.synchronizer(2, portBase).apply { name = "privateSync" })
            partyAllocations.add(PartyAllocation("IssuerPrivate", participants[0].id, synchronizers[1].id))
            partyAllocations.add(PartyAllocation("InvestorPrivate", participants[1].id, synchronizers[1].id))
            partyAllocations.add(PartyAllocation("BridgePrivate", participants[2].id, synchronizers[1].id))
            partyAllocations.add(PartyAllocation("BridgePublic", participants[2].id, synchronizers[0].id))
        }

    private fun explorerSnapshot(): LedgerExplorerSnapshot =
        LedgerExplorerSnapshot(
            participantName = "bridge",
            endpointUrl = "http://127.0.0.1:8577",
            ledgerEnd = 57,
            parties = listOf("BridgePublic", "BridgePrivate", "IssuerPrivate", "InvestorPrivate"),
            activeContracts = listOf(
                contractRow("PublicSettlement", "global", "BridgePublic", 57),
                contractRow("PrivateAccepted", "privateSync", "IssuerPrivate", 56),
                contractRow("PrivateOffer", "privateSync", "InvestorPrivate", 55)
            ),
            archivedContracts = emptyList(),
            events = listOf(
                eventRow("Created", "PublicSettlement", "global", "BridgePublic", 54),
                eventRow("Created", "PrivateAccepted", "privateSync", "IssuerPrivate", 53),
                eventRow("Archived", "PrivateOffer", "privateSync", "InvestorPrivate", 52),
                eventRow("Archived", "PublicSettlement", "global", "BridgePublic", 51)
            ),
            rawActiveResponse = """{"activeContracts":3,"ledgerEnd":57}""",
            rawUpdatesResponse = """{"events":4,"ledgerEnd":57}""",
            warnings = emptyList()
        )

    private fun contractRow(template: String, sync: String, party: String, offset: Long): LedgerContractRow =
        LedgerContractRow(
            templateId = "pkg:PrivateSettlement:$template",
            templateName = template,
            contractId = "00${template.lowercase()}$offset".padEnd(34, 'a'),
            offset = offset.toString(),
            synchronizerId = "$sync::1220abc",
            packageName = "private-settlement-bridge",
            createdAt = "2026-05-27T10:40:00Z",
            signatories = listOf("$party::1220party"),
            observers = emptyList(),
            witnessParties = listOf("$party::1220party"),
            createArgument = mapOf(
                "amount" to "125.5000000000",
                "reference" to "trade-${offset}",
                "visibleMemo" to "settlement-ready"
            ),
            rawJson = """
                |{
                |  "template": "$template",
                |  "contractId": "00${template.lowercase()}$offset",
                |  "synchronizer": "$sync",
                |  "party": "$party",
                |  "offset": $offset
                |}
            """.trimMargin()
        )

    private fun eventRow(kind: String, template: String, sync: String, party: String, offset: Long): LedgerEventRow =
        LedgerEventRow(
            kind = kind,
            templateId = "pkg:PrivateSettlement:$template",
            templateName = template,
            contractId = "00${template.lowercase()}$offset".padEnd(34, 'a'),
            offset = offset.toString(),
            synchronizerId = "$sync::1220abc",
            packageName = "private-settlement-bridge",
            witnessParties = listOf("$party::1220party"),
            createArgument = mapOf(
                "amount" to "125.5000000000",
                "reference" to "trade-${offset}",
                "visibleMemo" to "settlement-ready"
            ),
            rawJson = """
                |{
                |  "template": "$template",
                |  "contractId": "00${template.lowercase()}$offset",
                |  "synchronizer": "$sync",
                |  "party": "$party",
                |  "offset": $offset
                |}
            """.trimMargin()
        )

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

    private fun writeScreenshot(panel: LedgerExplorerPanel, fileName: String) {
        layoutTree(panel)
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.paint(graphics)
        graphics.dispose()

        val output = Path.of("build", "reports", "managed-sandbox", fileName).toAbsolutePath()
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())
        assertTrue("Explorer screenshot was not written to $output", Files.isRegularFile(output))
        assertTrue("Explorer screenshot appears blank", distinctColors(image) > 10)
    }

    private fun selectSegment(panel: LedgerExplorerPanel, value: String) {
        val tabs = panel.privateField<Any>("segmentTabs")
        tabs.privateMethod("select", String::class.java, Boolean::class.javaPrimitiveType!!).invoke(tabs, value, false)
        panel.privateSet("selectedSegment", value)
        panel.applyFilters()
    }

    private fun writeLiveSummary(snapshots: Map<String, LedgerExplorerSnapshot>) {
        val output = Path.of("build", "reports", "managed-sandbox", "ledger-explorer-live-summary.txt").toAbsolutePath()
        Files.createDirectories(output.parent)
        val text = buildString {
            snapshots.toSortedMap().forEach { (participant, snapshot) ->
                val rows = LedgerExplorerRows.from(snapshot)
                appendLine("$participant offset=${snapshot.ledgerEnd} parties=${snapshot.parties.map(LedgerExplorerRows::shortParty)}")
                appendLine("  active=${rows.filter { it.kind == "Active" }.map { "${it.templateName}@${it.syncName}:${it.offsetText}" }}")
                appendLine("  history=${rows.filter { it.kind == "Created" || it.kind == "Archived" }.map { "${it.kind}:${it.templateName}@${it.syncName}:${it.offsetText}" }}")
            }
        }
        Files.writeString(output, text)
        assertTrue("Live Explorer summary was not written to $output", Files.isRegularFile(output))
    }

    private inline fun <reified T> Any.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }

    private fun Any.privateSet(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private fun Any.privateMethod(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        val method = javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method
    }
}
