package com.moonsonglabs.daml.sandbox

import com.moonsonglabs.daml.settings.DamlProjectSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

object CantonJarLocator {
    fun find(settings: DamlProjectSettings): Path? {
        System.getenv("CANTON_JAR")?.takeIf { it.isNotBlank() }?.let { env ->
            Path.of(env).takeIf(Files::isRegularFile)?.let { return it }
        }
        settings.cantonBinaryPath.takeIf { it.endsWith(".jar") }?.let { configured ->
            Path.of(configured).takeIf(Files::isRegularFile)?.let { return it }
        }
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        settings.selectedSdkVersion.takeIf { it.isNotBlank() }?.let { version ->
            versionCandidates(home, version).firstOrNull(Files::isRegularFile)?.let { return it }
        }
        findSdkJars(home).firstOrNull()?.let { return it }
        return findDpmJars(home).firstOrNull()
    }

    private fun versionCandidates(home: String, version: String): List<Path> = listOf(
        Path.of(home, ".daml", "sdk", version, "canton", "canton.jar"),
        Path.of(home, ".dpm", "cache", "components", "canton-enterprise", version, "lib", "canton-enterprise-$version.jar"),
        Path.of(home, ".dpm", "cache", "components", "canton-open-source", version, "lib", "canton-open-source-$version.jar"),
        Path.of(home, ".dpm", "cache", "components", "canton", version, "lib", "canton-$version.jar")
    )

    private fun findSdkJars(home: String): List<Path> {
        val sdkDir = Path.of(home, ".daml", "sdk")
        if (!Files.isDirectory(sdkDir)) return emptyList()
        return Files.list(sdkDir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .map { it.resolve("canton").resolve("canton.jar") }
                .filter(Files::isRegularFile)
                .toList()
        }
    }

    private fun findDpmJars(home: String): List<Path> {
        val componentsDir = Path.of(home, ".dpm", "cache", "components")
        if (!Files.isDirectory(componentsDir)) return emptyList()
        val jars = mutableListOf<Path>()
        Files.list(componentsDir).use { components ->
            components.filter(Files::isDirectory).forEach { component ->
                Files.list(component).use { versions ->
                    versions.sorted(Comparator.reverseOrder())
                        .filter(Files::isDirectory)
                        .forEach { versionDir ->
                            val libDir = versionDir.resolve("lib")
                            if (Files.isDirectory(libDir)) {
                                Files.list(libDir).use { lib ->
                                    lib.filter(Files::isRegularFile)
                                        .filter { it.fileName.toString().startsWith("canton") && it.fileName.toString().endsWith(".jar") }
                                        .forEach(jars::add)
                                }
                            }
                        }
                }
            }
        }
        return jars.sortedWith(Comparator.reverseOrder())
    }
}
