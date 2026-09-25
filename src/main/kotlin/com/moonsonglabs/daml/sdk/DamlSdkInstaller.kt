package com.moonsonglabs.daml.sdk

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DamlSdkInstaller(private val project: Project) {
    data class Catalog(val versions: List<String>, val message: String)

    @Volatile private var cachedVersions: List<String> = emptyList()

    fun refreshVersions(onFinished: (Catalog) -> Unit) {
        object : Task.Backgroundable(project, "Refreshing DAML SDK releases", true) {
            override fun run(indicator: ProgressIndicator) {
                val catalog = try {
                    val platform = platform()
                    val dpm = RuntimeEnvironment.findExecutable(DPM, DamlProjectSettings.getInstance(project))
                        ?: error("Install DPM to load available SDK releases.")
                    indicator.text = "Loading SDK releases for ${platform.target}"
                    val output = run(platform, listOf(dpm.toString(), VERSION_COMMAND, "--all", OUTPUT_OPTION, JSON_FORMAT), indicator)
                    requireSuccess(output, "Loading SDK releases")
                    val versions = DamlSdkVersions.parseDpmVersions(output.stdout)
                    cachedVersions = versions
                    Catalog(versions, "${versions.size} SDK versions available · ${platform.target}")
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    Catalog(cachedVersions, "Could not refresh releases: ${e.message}")
                }
                deliver { onFinished(catalog) }
            }

            override fun onCancel() {
                if (!project.isDisposed) onFinished(Catalog(cachedVersions, "Release refresh cancelled."))
            }
        }.queue()
    }

    fun installDpmCli(onFinished: ((String) -> Unit)? = null) {
        installationTask("Installing DPM CLI", onFinished) { indicator ->
            val platform = platform()
            require(platform.os != DamlSdkPlatform.WINDOWS) {
                "Install or update DPM with the Windows installer: $DOCS_URL"
            }
            val script = Files.createTempFile("daml-dpm-install-", ".sh")
            try {
                indicator.text = "Downloading the official DPM installer for ${platform.target}"
                HttpRequests.request(INSTALLER_URL).connectTimeout(10_000).readTimeout(30_000)
                    .saveToFile(script.toFile(), indicator)
                // Run in the native host architecture, including when the IDE itself is translated.
                val output = run(platform, listOf("/bin/sh", script.toString()), indicator, INSTALL_TIMEOUT)
                requireSuccess(output, "Installing DPM for ${platform.target}")
                val dpm = RuntimeEnvironment.findExecutable(DPM, DamlProjectSettings.getInstance(project))
                    ?: error("The installer finished, but DPM could not be located.")
                requireSuccess(run(platform, listOf(dpm.toString(), VERSION_OPTION), indicator), "Starting DPM")
                "DPM CLI installed and verified for ${platform.target} at $dpm. Open a fresh IDE Terminal tab."
            } finally {
                Files.deleteIfExists(script)
            }
        }
    }

    fun installSelected(version: String, assistantOverride: String = "", onFinished: ((String) -> Unit)? = null) {
        installationTask("Installing DAML SDK", onFinished) { indicator ->
            val platform = platform()
            val requested = version.trim().ifBlank { DamlSdkVersions.DEFAULT }
            val assistant = findInstaller(assistantOverride)
                ?: error("Install DPM first, then retry from Settings → Languages & Frameworks → DAML.")
            val normalized = if (requested == DamlSdkVersions.DEFAULT) {
                if (assistant.fileName.toString().startsWith(DPM)) {
                    val releases = run(platform, listOf(assistant.toString(), VERSION_COMMAND, "--all", OUTPUT_OPTION, JSON_FORMAT), indicator)
                    requireSuccess(releases, "Resolving the latest SDK release")
                    DamlSdkVersions.resolveDpmLatest(releases.stdout)
                } else {
                    HttpRequests.request(LATEST_URL).connectTimeout(10_000).readTimeout(30_000).readString(indicator).trim()
                }
            } else requested
            require(RELEASE_VERSION.matches(normalized)) { "Enter a published SDK version or '${DamlSdkVersions.DEFAULT}'." }
            indicator.text = "Installing DAML SDK $normalized for ${platform.target}"
            requireSuccess(
                run(platform, listOf(assistant.toString(), "install", normalized), indicator, INSTALL_TIMEOUT),
                "Installing DAML SDK $normalized"
            )

            // Download completion does not imply that the compiler can execute (for example, Intel on ARM).
            indicator.text = "Verifying DAML SDK $normalized on ${platform.target}"
            val workspace = Files.createTempDirectory("daml-sdk-check-")
            try {
                Files.writeString(workspace.resolve("daml.yaml"), "sdk-version: $normalized\n")
                // damlc exposes its SDK version in --help; unlike DPM, it has no --version option.
                val output = run(platform, listOf(assistant.toString(), "damlc", "--help"), indicator, workingDirectory = workspace)
                requireSuccess(output, "SDK $normalized was downloaded, but its compiler could not start on ${platform.target}")
            } finally {
                FileUtil.delete(workspace.toFile())
            }
            deliver { DamlProjectSettings.getInstance(project).selectedSdkVersion = normalized }
            "DAML SDK $normalized installed and its compiler verified on ${platform.target}."
        }
    }

    private fun installationTask(title: String, onFinished: ((String) -> Unit)?, install: (ProgressIndicator) -> String) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val message = install(indicator)
                    finish(message, onFinished, success = true)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    finish(e.message ?: "Installation failed.", onFinished, success = false)
                }
            }

            override fun onCancel() {
                if (!project.isDisposed) onFinished?.invoke("Installation cancelled.")
            }
        }.queue()
    }

    private fun run(
        platform: DamlSdkPlatform,
        arguments: List<String>,
        indicator: ProgressIndicator,
        timeout: Int = QUERY_TIMEOUT,
        workingDirectory: Path? = null
    ): ProcessOutput {
        indicator.checkCanceled()
        val command = GeneralCommandLine(platform.command(arguments)).withCharset(StandardCharsets.UTF_8)
        workingDirectory?.let(command::withWorkingDirectory)
        RuntimeEnvironment.applyLocalTools(command, DamlProjectSettings.getInstance(project))
        return CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, timeout).also {
            indicator.checkCanceled()
            if (it.isCancelled) throw ProcessCanceledException()
        }
    }

    private fun platform(): DamlSdkPlatform {
        val macArm = SystemInfo.isMac && CapturingProcessHandler(
            GeneralCommandLine("/usr/sbin/sysctl", "-n", "hw.optional.arm64")
        ).runProcess(5_000).stdout.trim() == "1"
        return DamlSdkPlatform.detect(System.getProperty("os.name"), System.getProperty("os.arch"), macArm)
    }

    private fun findInstaller(assistantOverride: String): Path? {
        assistantOverride.trim().takeIf { it.isNotBlank() }?.let { override ->
            return Path.of(override).takeIf(Files::isExecutable)
                ?: error("The configured DAML binary is not executable: $override")
        }
        val settings = DamlProjectSettings.getInstance(project)
        return RuntimeEnvironment.findExecutable(DPM, settings) ?: RuntimeEnvironment.findExecutable("daml", settings)
    }

    private fun finish(message: String, onFinished: ((String) -> Unit)?, success: Boolean) = deliver {
        val html = escapeHtml(message).replace("\n", "<br/>")
        if (success) DamlNotifier.info(project, html) else DamlNotifier.error(project, html)
        onFinished?.invoke(message)
    }

    private fun deliver(action: () -> Unit) {
        // Preferences is modal: the release picker must update before the user closes it.
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) action() }, ModalityState.any())
    }

    companion object {
        private const val DPM = "dpm"
        private const val VERSION_COMMAND = "version"
        private const val VERSION_OPTION = "--version"
        private const val OUTPUT_OPTION = "--output"
        private const val JSON_FORMAT = "json"
        private const val INSTALL_BASE_URL = "https://get.digitalasset.com/install"
        private const val INSTALLER_URL = "$INSTALL_BASE_URL/install.sh"
        private const val LATEST_URL = "$INSTALL_BASE_URL/latest"
        private const val DOCS_URL = "https://docs.canton.network/sdks-tools/cli-tools/dpm"
        private const val QUERY_TIMEOUT = 60_000
        private const val INSTALL_TIMEOUT = 15 * 60_000
        private val RELEASE_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")

        internal fun requireSuccess(output: ProcessOutput, operation: String) {
            check(!output.isTimeout) { "$operation timed out." }
            check(!output.isCancelled) { "$operation was cancelled." }
            val detail = listOf(output.stdout, output.stderr).joinToString("\n").trim().takeLast(4000)
            check(output.exitCode == 0) { "$operation failed (exit ${output.exitCode}).\n$detail" }
        }

        private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun getInstance(project: Project): DamlSdkInstaller = project.service()
    }
}
