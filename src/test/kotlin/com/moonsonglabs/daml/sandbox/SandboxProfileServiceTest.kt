package com.moonsonglabs.daml.sandbox

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class SandboxProfileServiceTest : BasePlatformTestCase() {
    fun testDetectedManagedProfileBecomesTheActiveProfile() {
        val root = Path.of(project.basePath!!)
        resetDetectedProfiles(root)
        Files.createDirectories(root)
        Files.writeString(root.resolve("daml.yaml"), "sdk-version: 3.4.11\nname: detected-sandbox\n")
        Files.writeString(root.resolve("managed-sandbox-profile.json"), detectedProfileJson(root))

        val service = SandboxProfileService.getInstance(project)
        val persistedDefault = SandboxDefaults.newProfile(root).apply {
            id = "persisted-default"
            name = "Managed Canton Sandbox"
        }
        service.loadState(SandboxProfileService.State(mutableListOf(persistedDefault), persistedDefault.id))

        val selected = service.selectedProfile()

        assertEquals("private-settlement", selected.id)
        assertEquals("Private Settlement Bridge", selected.name)
        assertEquals(".", selected.workspacePath)
        assertEquals(".canton-sandboxes/private-settlement", selected.generatedPath)
        assertEquals(".daml/dist/private-settlement-bridge-0.1.0.dar", selected.darAssignments.single().darPath)
        assertEquals(3, selected.participants.size)
        assertEquals(2, selected.synchronizers.size)
        assertTrue(service.profiles().any { it.id == "persisted-default" })
    }

    fun testDetectedRelativeWorkspacePathIsAnchoredToDetectedWorkspace() {
        val root = Path.of(project.basePath!!)
        resetDetectedProfiles(root)
        val workspace = root.resolve("vaultkit")
        Files.createDirectories(workspace.resolve(".daml/dist"))
        Files.writeString(workspace.resolve("daml.yaml"), "sdk-version: 3.4.11\nname: detected-sandbox\n")
        Files.writeString(workspace.resolve(".daml/dist/private-settlement-bridge-0.1.0.dar"), "dar")
        Files.writeString(
            workspace.resolve("managed-sandbox-profile.json"),
            detectedProfileJson(workspace)
                .replace(""""workspacePath": "",""", """"workspacePath": ".",""")
                .replace(
                    workspace.resolve(".daml/dist/private-settlement-bridge-0.1.0.dar").toString(),
                    ".daml/dist/private-settlement-bridge-0.1.0.dar"
                )
        )

        val service = SandboxProfileService.getInstance(project)
        service.loadState(SandboxProfileService.State())
        val selected = service.selectedProfile()

        assertEquals("vaultkit", selected.workspacePath)
        assertEquals(".canton-sandboxes/private-settlement", selected.generatedPath)
        assertEquals(".daml/dist/private-settlement-bridge-0.1.0.dar", selected.darAssignments.single().darPath)
        assertTrue(Files.isRegularFile(root.resolve(selected.workspacePath).resolve(selected.darAssignments.single().darPath)))
    }

    fun testDetectedProfileKeepsExplicitParticipantSyncBindings() {
        val root = Path.of(project.basePath!!)
        resetDetectedProfiles(root)
        Files.createDirectories(root)
        Files.writeString(root.resolve("daml.yaml"), "sdk-version: 3.4.11\nname: detected-sandbox\n")
        Files.writeString(root.resolve("managed-sandbox-profile.json"), detectedProfileJson(root))

        val service = SandboxProfileService.getInstance(project)
        service.loadState(SandboxProfileService.State())
        val selected = service.selectedProfile()

        assertTrue(selected.bindings.any { it.participantId == "bridge" && it.synchronizerId == "global" && it.connected })
        assertFalse(selected.bindings.any { it.participantId == "issuer" && it.synchronizerId == "global" && it.connected })
        assertFalse(selected.bindings.any { it.participantId == "investor" && it.synchronizerId == "global" && it.connected })
    }

    fun testRefreshDetectedProfileReloadsChangedJson() {
        val root = Path.of(project.basePath!!)
        resetDetectedProfiles(root)
        val generated = root.resolve(".canton-sandboxes/private-settlement")
        Files.createDirectories(generated)
        Files.writeString(root.resolve("daml.yaml"), "sdk-version: 3.4.11\nname: detected-sandbox\n")
        val profileJson = generated.resolve("profile.json")
        Files.writeString(profileJson, detectedProfileJson(root))

        val service = SandboxProfileService.getInstance(project)
        service.loadState(SandboxProfileService.State())
        assertEquals(3, service.selectedProfile().participants.size)

        Files.writeString(
            profileJson,
            detectedProfileJson(root).replace(
                """{ "id": "bridge", "name": "bridge", "adminPort": 7432, "ledgerPort": 7431, "jsonPort": 8577 }""",
                """{ "id": "bridge", "name": "bridge", "adminPort": 7432, "ledgerPort": 7431, "jsonPort": 8577 },
            { "id": "auditor", "name": "auditor", "adminPort": 7442, "ledgerPort": 7441, "jsonPort": 8578 }"""
            )
        )

        service.refreshDetectedProfiles()

        assertEquals(4, service.selectedProfile().participants.size)
        assertTrue(service.selectedProfile().participants.any { it.id == "auditor" })
    }

    private fun detectedProfileJson(root: Path): String =
        """
        {
          "id": "private-settlement",
          "name": "Private Settlement Bridge",
          "workspacePath": "",
          "cantonVersion": "3.4.x",
          "portBase": 7400,
          "participants": [
            { "id": "issuer", "name": "issuer", "adminPort": 7412, "ledgerPort": 7411, "jsonPort": 8575 },
            { "id": "investor", "name": "investor", "adminPort": 7422, "ledgerPort": 7421, "jsonPort": 8576 },
            { "id": "bridge", "name": "bridge", "adminPort": 7432, "ledgerPort": 7431, "jsonPort": 8577 }
          ],
          "synchronizers": [
            {
              "id": "global",
              "name": "global",
              "sequencer": { "id": "globalSequencer", "name": "globalSequencer", "publicPort": 7401, "adminPort": 7402 },
              "mediator": { "id": "globalMediator", "name": "globalMediator", "adminPort": 7602 }
            },
            {
              "id": "privateSync",
              "name": "privateSync",
              "sequencer": { "id": "privateSequencer", "name": "privateSequencer", "publicPort": 7451, "adminPort": 7452 },
              "mediator": { "id": "privateMediator", "name": "privateMediator", "adminPort": 7652 }
            }
          ],
          "bindings": [
            { "participantId": "issuer", "synchronizerId": "privateSync", "connected": true },
            { "participantId": "investor", "synchronizerId": "privateSync", "connected": true },
            { "participantId": "bridge", "synchronizerId": "privateSync", "connected": true },
            { "participantId": "bridge", "synchronizerId": "global", "connected": true }
          ],
          "darAssignments": [
            {
              "darPath": "${root.resolve(".daml/dist/private-settlement-bridge-0.1.0.dar")}",
              "participantIds": ["issuer", "investor", "bridge"]
            }
          ],
          "partyAllocations": [
            { "partyHint": "IssuerPrivate", "participantId": "issuer", "synchronizerId": "privateSync" },
            { "partyHint": "InvestorPrivate", "participantId": "investor", "synchronizerId": "privateSync" },
            { "partyHint": "BridgePrivate", "participantId": "bridge", "synchronizerId": "privateSync" },
            { "partyHint": "BridgePublic", "participantId": "bridge", "synchronizerId": "global" }
          ],
          "generatedPath": ""
        }
        """.trimIndent()

    private fun resetDetectedProfiles(root: Path) {
        root.resolve("managed-sandbox-profile.json").toFile().delete()
        root.resolve(".canton-sandboxes").toFile().deleteRecursively()
        root.resolve("vaultkit").toFile().deleteRecursively()
        root.resolve("daml.yaml").toFile().delete()
        root.resolve("multi-package.yaml").toFile().delete()
    }
}
