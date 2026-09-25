package com.moonsonglabs.daml.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class DamlSdkPlatformTest {
    @Test
    fun `normalizes the supported OS and architecture names`() {
        assertEquals(MAC_ARM, DamlSdkPlatform.detect(MAC_NAME, "aarch64"))
        assertEquals(MAC_ARM, DamlSdkPlatform.detect("Darwin", DamlSdkPlatform.ARM64))
        assertEquals(DamlSdkPlatform("linux", DamlSdkPlatform.AMD64), DamlSdkPlatform.detect("Linux", "x86_64"))
        assertEquals(DamlSdkPlatform(DamlSdkPlatform.WINDOWS, DamlSdkPlatform.AMD64), DamlSdkPlatform.detect("Windows 11", DamlSdkPlatform.AMD64))
    }

    @Test
    fun `uses Apple silicon even when the IDE runs in an Intel JVM`() {
        val platform = DamlSdkPlatform.detect(MAC_NAME, DamlSdkPlatform.AMD64, macArmHardware = true)
        assertEquals(MAC_ARM, platform)
        assertEquals(listOf("/usr/bin/arch", "-arm64", SHELL, SCRIPT), platform.command(listOf(SHELL, SCRIPT)))
    }

    @Test
    fun `keeps paths with spaces as one process argument`() {
        assertEquals(
            listOf("/usr/bin/arch", "-x86_64", SHELL, SCRIPT),
            DamlSdkPlatform.detect(MAC_NAME, DamlSdkPlatform.AMD64).command(listOf(SHELL, SCRIPT))
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects an unknown architecture instead of guessing Intel`() {
        DamlSdkPlatform.detect(MAC_NAME, "ppc")
    }

    companion object {
        private const val MAC_NAME = "Mac OS X"
        private const val SHELL = "/bin/sh"
        private const val SCRIPT = "/tmp/install directory/install.sh"
        private val MAC_ARM = DamlSdkPlatform(DamlSdkPlatform.MAC_OS, DamlSdkPlatform.ARM64)
    }
}
