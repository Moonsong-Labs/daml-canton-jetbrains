package com.moonsonglabs.daml.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the DAML assistant binary (`daml` or `dpm`) for the active project.
 *
 * Order matches the official VSCode extension's [findAssistantCommand]:
 *   1. user-supplied override path from settings,
 *   2. dpm,
 *   3. daml,
 *   in each case checking PATH first then the well-known per-user install dir.
 *
 * Why: SDK pinning lives in `daml.yaml` and only the assistant binaries honor it; calling
 * `damlc` directly would bypass the per-project SDK selection.
 */
object DamlBinaryLocator {

    data class Resolution(val binary: Path, val flavor: Flavor) {
        enum class Flavor { DAML, DPM }
    }

    fun locate(project: Project, workspaceRoot: Path? = null): Resolution? {
        val settings = DamlProjectSettings.getInstance(project)
        settings.binaryPath.takeIf { it.isNotBlank() }?.let { override ->
            val p = Paths.get(override)
            if (Files.isExecutable(p)) {
                val flavor = if (p.fileName.toString().startsWith("dpm")) Resolution.Flavor.DPM
                             else Resolution.Flavor.DAML
                return Resolution(p, flavor)
            }
            return null
        }

        findOnPath("dpm")?.let { return Resolution(it, Resolution.Flavor.DPM) }
        RuntimeEnvironment.findExecutable("dpm", settings)?.let { return Resolution(it, Resolution.Flavor.DPM) }

        workspaceSdkVersion(workspaceRoot)?.let { sdkVersion ->
            wellKnown(".daml/sdk/$sdkVersion/daml/daml")?.let {
                return Resolution(it, Resolution.Flavor.DAML)
            }
        }

        settings.selectedSdkVersion.takeIf { it.isNotBlank() }?.let { sdkVersion ->
            wellKnown(".daml/sdk/$sdkVersion/daml/daml")?.let {
                return Resolution(it, Resolution.Flavor.DAML)
            }
        }

        findOnPath("daml")?.let { return Resolution(it, Resolution.Flavor.DAML) }
        RuntimeEnvironment.findExecutable("daml", settings)?.let { return Resolution(it, Resolution.Flavor.DAML) }

        return null
    }

    internal fun workspaceSdkVersion(workspaceRoot: Path?): String? {
        if (workspaceRoot == null) return null
        manifestSdkVersion(workspaceRoot.resolve("daml.yaml"))?.let { return it }
        manifestSdkVersion(workspaceRoot.resolve("multi-package.yaml"))?.let { return it }

        val multiPackage = workspaceRoot.resolve("multi-package.yaml")
        if (!Files.isRegularFile(multiPackage)) return null
        val packageRoots = multiPackagePackageRoots(multiPackage, workspaceRoot)
        return packageRoots.asSequence()
            .mapNotNull { manifestSdkVersion(it.resolve("daml.yaml")) }
            .firstOrNull()
    }

    private fun manifestSdkVersion(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        return Files.readAllLines(path).firstNotNullOfOrNull { line ->
            val trimmed = line.trim()
            val keyEnd = trimmed.indexOf(':')
            if (keyEnd <= 0) return@firstNotNullOfOrNull null
            val key = trimmed.substring(0, keyEnd)
            if (key != "sdk-version" && key != "daml-version") return@firstNotNullOfOrNull null
            trimmed.substring(keyEnd + 1)
                .trim()
                .trim('"', '\'')
                .takeIf { it.isNotBlank() }
        }
    }

    private fun multiPackagePackageRoots(multiPackage: Path, workspaceRoot: Path): List<Path> {
        var inPackages = false
        val roots = mutableListOf<Path>()
        Files.readAllLines(multiPackage).forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed == "packages:" -> inPackages = true
                inPackages && trimmed.startsWith("-") -> {
                    val raw = trimmed.removePrefix("-")
                        .trim()
                        .trim('"', '\'')
                    if (raw.isNotBlank()) roots.add(workspaceRoot.resolve(raw).normalize())
                }
                inPackages && trimmed.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") -> {
                    inPackages = false
                }
            }
        }
        return roots
    }

    private fun findOnPath(name: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        val exeNames = if (SystemInfo.isWindows) listOf("$name.exe", "$name.bat", "$name.cmd", name) else listOf(name)
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (exe in exeNames) {
                val p = Paths.get(dir, exe)
                if (Files.isExecutable(p)) return p
            }
        }
        return null
    }

    private fun wellKnown(suffix: String): Path? {
        val home = System.getProperty("user.home") ?: return null
        val p = Paths.get(home, suffix)
        return if (Files.isExecutable(p)) p else null
    }
}
