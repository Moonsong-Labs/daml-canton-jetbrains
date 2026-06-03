package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JPanel

class TopologyGraphPanelVisualTest {
    @Test
    fun `renders topology example screenshot`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            participants.add(SandboxDefaults.participant(2, portBase))
            participants.add(SandboxDefaults.participant(3, portBase).apply { name = "auditor" })
            synchronizers.add(SandboxDefaults.synchronizer(2, portBase).apply { name = "backup" })
            bindings.clear()
            bindings.add(ParticipantSyncBinding(participants[0].id, synchronizers[0].id, true))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[0].id, true))
            bindings.add(ParticipantSyncBinding(participants[2].id, synchronizers[0].id, false))
            bindings.add(ParticipantSyncBinding(participants[0].id, synchronizers[1].id, false))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[1].id, true))
            bindings.add(ParticipantSyncBinding(participants[2].id, synchronizers[1].id, true))
            darAssignments.add(DarAssignment("/workspace/.daml/dist/lunar-dollar.dar", mutableListOf(participants[0].id, participants[1].id)))
            partyAllocations.add(PartyAllocation("Bob", participants[1].id, synchronizers[0].id))
            partyAllocations.add(PartyAllocation("Auditor", participants[2].id, synchronizers[1].id))
        }

        val panel = TopologyGraphPanel()
        panel.setProfile(profile)
        panel.select(TopologyGraphPanel.Selection.Participant(profile.participants[1].id))
        panel.setSelectionDetails(
            """
            |${TopologyNodeIcons.PARTICIPANT} Participant - investor
            |
            |Uploaded DARs:
            |lunar-dollar.dar
            |
            |Ledger API: grpc://127.0.0.1:5021
            |Admin API: grpc://127.0.0.1:5022
            |JSON API: http://127.0.0.1:7576
            |Health: live=ok ready=ok
            |
            |Connected sync domains:
            |global
            |backup
            |""".trimMargin()
        )
        panel.setSize(panel.preferredSize)
        panel.doLayout()

        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.paint(graphics)
        graphics.dispose()

        val output = Path.of("build", "reports", "managed-sandbox", "topology-example-cyberpunk.png").toAbsolutePath()
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())

        assertTrue("Topology screenshot was not written to $output", Files.isRegularFile(output))
        assertTrue("Topology screenshot appears blank", distinctColors(image) > 10)
    }

    @Test
    fun `renders themed component palette screenshot`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            participants.add(SandboxDefaults.participant(2, portBase))
            synchronizers.add(SandboxDefaults.synchronizer(2, portBase))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[0].id, true))
            bindings.add(ParticipantSyncBinding(participants[0].id, synchronizers[1].id, true))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[1].id, false))
            darAssignments.add(DarAssignment("/workspace/.daml/dist/private-settlement-bridge-0.1.0.dar", mutableListOf(participants[0].id, participants[1].id)))
        }

        val panel = TopologyComponentPalettePanel()
        panel.setProfile(profile)
        panel.select(TopologyGraphPanel.Selection.Synchronizer(profile.synchronizers[0].id))
        panel.setSize(260, panel.preferredSize.height)
        panel.doLayout()

        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.paint(graphics)
        graphics.dispose()

        val output = Path.of("build", "reports", "managed-sandbox", "component-palette-cyberpunk.png").toAbsolutePath()
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())

        assertTrue("Component palette screenshot was not written to $output", Files.isRegularFile(output))
        assertTrue("Component palette screenshot appears blank", distinctColors(image) > 10)
    }

    @Test
    fun `renders combined topology designer screenshot`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            participants.add(SandboxDefaults.participant(2, portBase))
            participants.add(SandboxDefaults.participant(3, portBase).apply { name = "auditor" })
            synchronizers.add(SandboxDefaults.synchronizer(2, portBase))
            bindings.clear()
            bindings.add(ParticipantSyncBinding(participants[0].id, synchronizers[0].id, true))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[0].id, true))
            bindings.add(ParticipantSyncBinding(participants[2].id, synchronizers[1].id, false))
            bindings.add(ParticipantSyncBinding(participants[0].id, synchronizers[1].id, true))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[1].id, true))
        }
        val selection = TopologyGraphPanel.Selection.Synchronizer(profile.synchronizers[0].id)
        val palette = TopologyComponentPalettePanel().apply {
            preferredSize = Dimension(270, 640)
            setProfile(profile)
            select(selection)
        }
        val graph = TopologyGraphPanel().apply {
            setProfile(profile)
            select(selection)
            setSelectionDetails(
                """
                |${TopologyNodeIcons.SYNCHRONIZER} Sync Domain - global
                |
                |Sequencer: globalSequencer
                |Mediator: globalMediator
                |
                |Connected participants:
                |issuer
                |investor
                |""".trimMargin()
            )
        }
        val panel = JPanel(BorderLayout()).apply {
            background = TopologyGraphTheme.canvas
            border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
            add(palette, BorderLayout.WEST)
            add(graph, BorderLayout.CENTER)
            setSize(1480, 640)
            doLayout()
        }

        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.paint(graphics)
        graphics.dispose()

        val output = Path.of("build", "reports", "managed-sandbox", "topology-designer-cyberpunk.png").toAbsolutePath()
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())

        assertTrue("Topology designer screenshot was not written to $output", Files.isRegularFile(output))
        assertTrue("Topology designer screenshot appears blank", distinctColors(image) > 10)
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
