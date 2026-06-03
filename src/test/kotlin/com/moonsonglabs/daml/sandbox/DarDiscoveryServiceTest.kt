package com.moonsonglabs.daml.sandbox

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class DarDiscoveryServiceTest : BasePlatformTestCase() {
    fun `test dar discovery includes workspace outputs and excludes caches`() {
        val root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve(".daml/dist"))
        Files.createDirectories(root.resolve(".daml/package-database/pkg"))
        Files.createDirectories(root.resolve(".canton-sandboxes/generated/dars"))
        Files.createDirectories(root.resolve("sdk/vault-test/.daml/dist"))
        Files.createDirectories(root.resolve("target"))
        Files.writeString(root.resolve(".daml/dist/main.dar"), "fake")
        Files.writeString(root.resolve(".daml/package-database/pkg/noise.dar"), "fake")
        Files.writeString(root.resolve(".canton-sandboxes/generated/dars/generated.dar"), "fake")
        Files.writeString(root.resolve("sdk/vault-test/.daml/dist/test.dar"), "fake")
        Files.writeString(root.resolve("target/targeted.dar"), "fake")

        val profile = SandboxDefaults.newProfile(root).apply { workspacePath = "." }
        val names = DarDiscoveryService.getInstance(project).discover(profile).map { it.displayName }.toSet()

        assertTrue("main.dar should be discovered", "main.dar" in names)
        assertTrue("test.dar should be discovered", "test.dar" in names)
        assertTrue("targeted.dar should be discovered", "targeted.dar" in names)
        assertFalse("package database DARs are cache noise", "noise.dar" in names)
        assertFalse("generated sandbox DARs should not be rediscovered", "generated.dar" in names)
    }

    fun `test dar metadata parser extracts useful fields`() {
        val metadata = DarMetadataInspector(project).parse(
            """
            {
              "packageName": "private-settlement-bridge",
              "packageVersion": "0.1.0",
              "templates": ["PrivateOffer", "PublicSettlement"],
              "modules": ["PrivateSettlement"]
            }
            """.trimIndent()
        )

        assertEquals("private-settlement-bridge", metadata?.packageName)
        assertEquals("0.1.0", metadata?.packageVersion)
        assertEquals(2, metadata?.templateCount)
        assertEquals(1, metadata?.moduleCount)
    }
}
