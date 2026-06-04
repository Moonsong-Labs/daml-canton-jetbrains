package com.moonsonglabs.daml.sdk

import java.nio.file.Files
import java.nio.file.Path

object DamlSdkVersions {
    const val DEFAULT = "3.4.11"

    private val bundled = listOf(DEFAULT)

    fun choices(): List<String> =
        (bundled + installed()).distinct().sortedWith(compareByDescending<String> { it == DEFAULT }.thenByDescending { it })

    fun installed(userHome: String? = System.getProperty("user.home")): List<String> {
        val root = userHome?.let { Path.of(it, ".daml", "sdk") } ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter(Files::isDirectory)
                .map { it.fileName.toString() }
                .filter { it.isNotBlank() }
                .toList()
        }
    }
}
