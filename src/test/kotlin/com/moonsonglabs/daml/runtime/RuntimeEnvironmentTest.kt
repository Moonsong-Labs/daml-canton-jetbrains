package com.moonsonglabs.daml.runtime

import com.moonsonglabs.daml.sdk.DamlSdkVersions
import com.moonsonglabs.daml.settings.DamlProjectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class RuntimeEnvironmentTest {
    @Test
    fun exposesIdeJavaHomeAndLocalToolDirsOnPath() {
        val env = RuntimeEnvironment.ideJavaEnvironment()
        val javaHome = System.getProperty("java.home")

        assertEquals(javaHome, env["JAVA_HOME"])
        val path = env["PATH"].orEmpty()
        assertTrue(path.contains(Path.of(javaHome, "bin").toString()))
        assertTrue(path.contains(Path.of(System.getProperty("user.home"), ".dpm", "bin").toString()))
        assertTrue(path.contains(Path.of(System.getProperty("user.home"), ".daml", "bin").toString()))
        assertFalse(path.contains(Path.of(System.getProperty("user.home"), ".daml", "sdk", DamlSdkVersions.DEFAULT, "daml").toString()))
    }

    @Test
    fun preservesAnExplicitSdkFallback() {
        val version = "3.5.9"
        val home = "/test/home"
        val settings = DamlProjectSettings().apply { selectedSdkVersion = version }
        assertTrue(RuntimeEnvironment.localToolDirectories(settings, home).contains(Path.of(home, ".daml", "sdk", version, "daml")))
    }
}
