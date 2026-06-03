package com.moonsonglabs.daml.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.io.File
import java.nio.file.Path

class DamlTerminalCustomizer : LocalTerminalCustomizer() {
    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<out String>,
        envs: MutableMap<String, String>
    ): Array<out String> {
        val settings = DamlProjectSettings.getInstance(project)
        prependLocalToolPath(settings, envs)
        return command
    }

    companion object {
        fun prependLocalToolPath(settings: DamlProjectSettings?, envs: MutableMap<String, String>) {
            val toolDirs = RuntimeEnvironment.localToolAndJavaDirectories(settings)
            val pathKey = envs.keys.firstOrNull { it.equals("PATH", ignoreCase = SystemInfo.isWindows) } ?: "PATH"
            val basePath = envs[pathKey] ?: System.getenv("PATH").orEmpty()
            envs[pathKey] = RuntimeEnvironment.buildPath(
                toolDirs,
                basePath
            )
            RuntimeEnvironment.ideJavaHome()?.let { javaHome ->
                val javaHomeKey = envs.keys.firstOrNull { it.equals("JAVA_HOME", ignoreCase = SystemInfo.isWindows) }
                    ?: "JAVA_HOME"
                envs[javaHomeKey] = javaHome.toString()
            }
            envs["_INTELLIJ_FORCE_PREPEND_PATH"] = terminalShellIntegrationPathPrefix(toolDirs, envs["_INTELLIJ_FORCE_PREPEND_PATH"])
        }

        internal fun terminalShellIntegrationPathPrefix(toolDirs: List<Path>, existing: String?): String {
            val prefix = toolDirs.joinToString(File.pathSeparator, postfix = File.pathSeparator) { it.toString() }
            return prefix + existing.orEmpty()
        }
    }
}
