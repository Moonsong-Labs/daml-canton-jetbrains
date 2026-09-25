package com.moonsonglabs.daml.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class DamlSdkVersionsTest {
    @Test
    fun `discovers remote and DPM installed releases without bundling version numbers`() {
        val versions = DamlSdkVersions.parseDpmVersions("""
            [
              {"version":"$OLDER", "remote":true},
              {"version":"$NEWER", "remote":true},
              {"version":"$OLDER", "installed":true},
              {"version":"$PREVIEW", "remote":true},
              {"version":"$PINNED_PREVIEW", "installed":true},
              {"version":"$UNKNOWN"}
            ]
        """.trimIndent())

        assertEquals(listOf(PINNED_PREVIEW, NEWER, OLDER), versions)
        assertFalse(versions.contains(PREVIEW))
        assertFalse(versions.contains(UNKNOWN))
    }

    @Test
    fun `empty machine offers only the remotely resolved latest tag`() {
        assertEquals(listOf(DamlSdkVersions.DEFAULT), DamlSdkVersions.choices(emptyList(), emptyList()))
    }

    @Test
    fun `latest follows the registry tag even when a higher numbered release exists`() {
        val json = """[{"version":"$OLDER", "tags":["${DamlSdkVersions.DEFAULT}"]}, {"version":"$NEWER"}]"""
        assertEquals(OLDER, DamlSdkVersions.resolveDpmLatest(json))
    }

    @Test(expected = IllegalStateException::class)
    fun `does not guess latest when the registry has no latest tag`() {
        DamlSdkVersions.resolveDpmLatest("""[{"version":"$NEWER"}]""")
    }

    @Test
    fun `retains saved versions and sorts numerically rather than lexically`() {
        assertEquals(
            listOf(DamlSdkVersions.DEFAULT, NEWER, OLDER),
            DamlSdkVersions.choices(listOf(OLDER, NEWER, OLDER), listOf(OLDER))
        )
    }

    @Test
    fun `discovers legacy installations without a fixed fallback version`() {
        val home = Files.createTempDirectory("daml-sdk-versions-")
        try {
            val root = home.resolve(".daml/sdk")
            Files.createDirectories(root.resolve(OLDER))
            Files.createDirectories(root.resolve(NEWER))
            Files.writeString(root.resolve("not-a-directory"), "")
            assertEquals(listOf(NEWER, OLDER), DamlSdkVersions.installed(home.toString()))
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    companion object {
        private const val OLDER = "3.5.9"
        private const val NEWER = "3.5.12"
        private const val PREVIEW = "3.6.0-rc1"
        private const val PINNED_PREVIEW = "3.6.0-rc2"
        private const val UNKNOWN = "99.0.0"
    }
}
