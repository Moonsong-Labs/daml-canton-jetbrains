package com.moonsonglabs.daml.sdk

import com.google.gson.JsonParser
import com.intellij.util.text.VersionComparatorUtil
import java.nio.file.Files
import java.nio.file.Path

object DamlSdkVersions {
    // DPM resolves this tag from its registry; it is not a bundled release number.
    const val DEFAULT = "latest"
    private const val VERSION_KEY = "version"
    private const val REMOTE_KEY = "remote"
    private const val INSTALLED_KEY = "installed"
    private const val ACTIVE_KEY = "active"
    private const val TAGS_KEY = "tags"
    private val stableVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    fun choices(available: List<String> = emptyList(), local: List<String> = installed()): List<String> =
        listOf(DEFAULT) + (available + local).filter { it.isNotBlank() && it != DEFAULT }
            .distinct().sortedWith(VersionComparatorUtil.COMPARATOR.reversed())

    fun parseDpmVersions(json: String): List<String> =
        JsonParser.parseString(json).asJsonArray.mapNotNull { entry ->
            val release = entry.asJsonObject
            val version = release.get(VERSION_KEY)?.asString ?: return@mapNotNull null
            val installed = release.get(INSTALLED_KEY)?.asBoolean == true
            val active = release.get(ACTIVE_KEY)?.asBoolean == true
            val remote = release.get(REMOTE_KEY)?.asBoolean == true
            version.takeIf { installed || active || (remote && stableVersion.matches(version)) }
        }.distinct().sortedWith(VersionComparatorUtil.COMPARATOR.reversed())

    fun resolveDpmLatest(json: String): String =
        JsonParser.parseString(json).asJsonArray.firstOrNull { entry ->
            entry.asJsonObject.getAsJsonArray(TAGS_KEY)?.any { it.asString == DEFAULT } == true
        }?.asJsonObject?.get(VERSION_KEY)?.asString
            ?: error("DPM did not report a release tagged '$DEFAULT'. Refresh releases and select an explicit version.")

    fun installed(userHome: String? = System.getProperty("user.home")): List<String> {
        val root = userHome?.let { Path.of(it, ".daml", "sdk") } ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter(Files::isDirectory)
                .map { it.fileName.toString() }
                .filter { it.isNotBlank() }
                .toList()
        }.sortedWith(VersionComparatorUtil.COMPARATOR.reversed())
    }
}
