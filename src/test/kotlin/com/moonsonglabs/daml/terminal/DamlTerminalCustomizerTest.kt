package com.moonsonglabs.daml.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Path

class DamlTerminalCustomizerTest {
    @Test
    fun prependsDamlAndDpmDirectoriesToTerminalPath() {
        val envs = mutableMapOf("PATH" to "/usr/bin")

        DamlTerminalCustomizer.prependLocalToolPath(null, envs)

        val path = envs.getValue("PATH").split(File.pathSeparator)
        val home = System.getProperty("user.home")
        val javaHome = System.getProperty("java.home")
        assertTrue(path.indexOf(Path.of(home, ".dpm", "bin").toString()) < path.indexOf("/usr/bin"))
        assertTrue(path.indexOf(Path.of(home, ".daml", "bin").toString()) < path.indexOf("/usr/bin"))
        assertTrue(path.indexOf(Path.of(home, ".daml", "sdk", "3.4.11", "daml").toString()) < path.indexOf("/usr/bin"))
        assertTrue(path.indexOf(Path.of(javaHome, "bin").toString()) < path.indexOf("/usr/bin"))
        assertTrue(envs["JAVA_HOME"] == javaHome)
    }

    @Test
    fun setsJetBrainsShellIntegrationPathPrepend() {
        val envs = mutableMapOf("PATH" to "/usr/bin")

        DamlTerminalCustomizer.prependLocalToolPath(null, envs)

        val prefix = envs.getValue("_INTELLIJ_FORCE_PREPEND_PATH")
        val home = System.getProperty("user.home")
        val javaHome = System.getProperty("java.home")
        assertTrue(prefix.endsWith(File.pathSeparator))
        assertTrue(prefix.contains(Path.of(home, ".dpm", "bin").toString()))
        assertTrue(prefix.contains(Path.of(home, ".daml", "bin").toString()))
        assertTrue(prefix.contains(Path.of(home, ".daml", "sdk", "3.4.11", "daml").toString()))
        assertTrue(prefix.contains(Path.of(javaHome, "bin").toString()))
    }
}
