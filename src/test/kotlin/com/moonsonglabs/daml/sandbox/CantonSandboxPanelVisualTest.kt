package com.moonsonglabs.daml.sandbox

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

class CantonSandboxPanelVisualTest : BasePlatformTestCase() {
    fun `test renders final network topology screenshot`() {
        val root = Path.of(project.basePath!!).toAbsolutePath().normalize()
        val dar = root.resolve(".daml/dist/sample-settlement-bridge-0.1.0.dar")
        Files.createDirectories(dar.parent)
        Files.write(dar, byteArrayOf(0x50, 0x4b, 0x03, 0x04))

        val profile = SandboxDefaults.newProfile(root).apply {
            id = "visual-sample-settlement"
            name = "Sample Settlement Bridge"
            portBase = 7400
            workspacePath = root.toString()
            generatedPath = root.resolve(".canton-sandboxes/sample-settlement").toString()
            participants.clear()
            participants.add(SandboxDefaults.participant(1, portBase).apply { name = "issuer" })
            participants.add(SandboxDefaults.participant(2, portBase).apply { name = "investor" })
            participants.add(SandboxDefaults.participant(3, portBase).apply { name = "bridge" })
            synchronizers.clear()
            synchronizers.add(SandboxDefaults.sharedSynchronizer(portBase))
            synchronizers.add(SandboxDefaults.synchronizer(2, portBase).apply { name = "privateSync" })
            bindings.clear()
            bindings.add(ParticipantSyncBinding(participants[0].id, synchronizers[1].id, true))
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[1].id, true))
            bindings.add(ParticipantSyncBinding(participants[2].id, synchronizers[1].id, true))
            bindings.add(ParticipantSyncBinding(participants[2].id, synchronizers[0].id, true))
            darAssignments.add(DarAssignment(".daml/dist/sample-settlement-bridge-0.1.0.dar", participants.map { it.id }.toMutableList()))
            partyAllocations.add(PartyAllocation("IssuerPrivate", participants[0].id, synchronizers[1].id))
            partyAllocations.add(PartyAllocation("InvestorPrivate", participants[1].id, synchronizers[1].id))
            partyAllocations.add(PartyAllocation("BridgePrivate", participants[2].id, synchronizers[1].id))
            partyAllocations.add(PartyAllocation("BridgePublic", participants[2].id, synchronizers[0].id))
        }

        val profileService = SandboxProfileService.getInstance(project)
        profileService.loadState(SandboxProfileService.State(mutableListOf(profile), profile.id))
        val panel = CantonSandboxPanel(project)

        try {
            panel.setSize(1480, 860)
            layoutTree(panel)

            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            panel.paint(graphics)
            graphics.dispose()

            val output = Path.of("build", "reports", "managed-sandbox", "network-topology-final.png").toAbsolutePath()
            Files.createDirectories(output.parent)
            ImageIO.write(image, "png", output.toFile())

            assertTrue("Network topology screenshot was not written to $output", Files.isRegularFile(output))
            assertTrue("Network topology screenshot appears blank", distinctColors(image) > 10)
        } finally {
            panel.dispose()
            profileService.loadState(SandboxProfileService.State())
        }
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
