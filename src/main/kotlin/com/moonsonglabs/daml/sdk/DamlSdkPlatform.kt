package com.moonsonglabs.daml.sdk

/** The host target used by the official installer and DPM's platform-specific registry lookup. */
data class DamlSdkPlatform(val os: String, val arch: String) {
    val target: String get() = "$os/$arch"

    // A JetBrains JVM running under Rosetta must not make the installer select Intel on Apple silicon.
    fun command(arguments: List<String>): List<String> =
        if (os == MAC_OS) listOf("/usr/bin/arch", if (arch == ARM64) "-arm64" else "-x86_64") + arguments
        else arguments

    companion object {
        const val MAC_OS = "darwin"
        const val WINDOWS = "windows"
        const val ARM64 = "arm64"
        const val AMD64 = "amd64"
        private const val LINUX = "linux"

        fun detect(osName: String, architecture: String, macArmHardware: Boolean = false): DamlSdkPlatform {
            val os = when {
                osName.startsWith("Mac", ignoreCase = true) || osName.equals(MAC_OS, ignoreCase = true) -> MAC_OS
                osName.startsWith(WINDOWS, ignoreCase = true) -> WINDOWS
                osName.equals(LINUX, ignoreCase = true) -> LINUX
                else -> error("Unsupported operating system: $osName")
            }
            val arch = when {
                os == MAC_OS && macArmHardware -> ARM64
                architecture.lowercase() in setOf(ARM64, "aarch64") -> ARM64
                architecture.lowercase() in setOf(AMD64, "x86_64", "x64") -> AMD64
                else -> error("Unsupported architecture: $architecture")
            }
            require(os != WINDOWS || arch == AMD64) { "DPM does not publish a native Windows ARM64 installer." }
            return DamlSdkPlatform(os, arch)
        }
    }
}
