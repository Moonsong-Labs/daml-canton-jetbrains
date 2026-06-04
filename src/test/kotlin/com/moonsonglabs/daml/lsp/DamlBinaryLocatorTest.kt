package com.moonsonglabs.daml.lsp

import com.moonsonglabs.daml.sdk.DamlSdkVersions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DamlBinaryLocatorTest {
    @Test
    fun workspaceSdkVersionReadsNestedMultiPackageDamlYaml() {
        val root = Files.createTempDirectory("daml-workspace")
        Files.writeString(root.resolve("multi-package.yaml"), "packages:\n  - ./pkg-a\n  - ./pkg-b\n")
        Files.createDirectories(root.resolve("pkg-a"))
        Files.createDirectories(root.resolve("pkg-b"))
        Files.writeString(root.resolve("pkg-a/daml.yaml"), "sdk-version: ${DamlSdkVersions.DEFAULT}\nname: pkg-a\n")
        Files.writeString(root.resolve("pkg-b/daml.yaml"), "sdk-version: 3.4.9\nname: pkg-b\n")

        assertEquals("${DamlSdkVersions.DEFAULT}", DamlBinaryLocator.workspaceSdkVersion(root))
    }

    @Test
    fun workspaceSdkVersionReadsRootAlias() {
        val root = Files.createTempDirectory("daml-workspace")
        Files.writeString(root.resolve("daml.yaml"), "daml-version: \"${DamlSdkVersions.DEFAULT}\"\nname: root\n")

        assertEquals("${DamlSdkVersions.DEFAULT}", DamlBinaryLocator.workspaceSdkVersion(root))
    }
}
