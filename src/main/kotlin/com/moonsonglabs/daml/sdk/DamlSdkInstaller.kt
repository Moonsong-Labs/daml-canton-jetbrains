package com.moonsonglabs.daml.sdk

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DamlSdkInstaller(private val project: Project) {

    fun installDpmCli(onFinished: ((String) -> Unit)? = null) {
        object : Task.Backgroundable(project, "Installing DPM CLI", true) {
            override fun run(indicator: ProgressIndicator) {
                RuntimeEnvironment.findExecutable("dpm", DamlProjectSettings.getInstance(project))?.let { existing ->
                    finish("DPM CLI is already installed at $existing.", onFinished) {
                        DamlNotifier.info(project, "DPM CLI is already installed at $existing.")
                    }
                    return
                }

                if (SystemInfo.isWindows) {
                    val message = "Install the DPM CLI with the Windows installer from the Digital Asset docs, then reopen the IDE."
                    finish(message, onFinished) {
                        DamlNotifier.warn(project, "$message<br/>Docs: https://docs.digitalasset.com/build/3.4/dpm/dpm.html")
                    }
                    return
                }

                indicator.text = "Running the official DPM installer"
                val cmd = GeneralCommandLine("sh", "-lc", "curl -sSL https://get.digitalasset.com/install/install.sh | sh -s")
                    .withCharset(StandardCharsets.UTF_8)
                RuntimeEnvironment.applyLocalTools(cmd, DamlProjectSettings.getInstance(project))

                val output = CapturingProcessHandler(cmd).runProcess(15 * 60 * 1000)
                val combinedOutput = listOf(output.stdout, output.stderr)
                    .joinToString("\n")
                    .trim()
                    .takeLast(4000)
                val dpm = RuntimeEnvironment.findExecutable("dpm", DamlProjectSettings.getInstance(project))
                val message = when {
                    output.isTimeout -> "Timed out while installing the DPM CLI."
                    output.exitCode == 0 && dpm != null -> "DPM CLI installed at $dpm. Open a fresh IDE Terminal tab."
                    output.exitCode == 0 -> "DPM installer finished. Open a fresh IDE Terminal tab; if dpm is still missing, check the installer output."
                    combinedOutput.isNotBlank() -> "Failed to install the DPM CLI:<br/><pre>${escapeHtml(combinedOutput)}</pre>"
                    else -> "Failed to install the DPM CLI with exit code ${output.exitCode}."
                }
                finish(message, onFinished) {
                    if (output.exitCode == 0 && !output.isTimeout) {
                        DamlProjectSettings.getInstance(project).useDPMWhenAvailable = true
                        DamlNotifier.info(project, message)
                    } else {
                        DamlNotifier.error(project, message)
                    }
                }
            }
        }.queue()
    }

    fun installSelected(version: String, assistantOverride: String = "", onFinished: ((String) -> Unit)? = null) {
        val normalized = version.trim().ifBlank { DamlSdkVersions.DEFAULT }
        object : Task.Backgroundable(project, "Installing DAML SDK $normalized", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Locating dpm or daml"
                val assistant = findInstaller(assistantOverride)
                if (assistant == null) {
                    val message = "DAML SDK installer not found. Install dpm or the DAML assistant first, then retry from Settings → Languages & Frameworks → DAML."
                    finish(message, onFinished) { DamlNotifier.warn(project, "$message<br/>Docs: https://docs.digitalasset.com/build/3.4/dpm/dpm.html") }
                    return
                }

                indicator.text = "Running ${assistant.fileName} install $normalized"
                val cmd = GeneralCommandLine(assistant.toString(), "install", normalized)
                    .withCharset(StandardCharsets.UTF_8)
                RuntimeEnvironment.applyLocalTools(cmd, DamlProjectSettings.getInstance(project))

                val output = CapturingProcessHandler(cmd).runProcess(15 * 60 * 1000)
                val combinedOutput = listOf(output.stdout, output.stderr)
                    .joinToString("\n")
                    .trim()
                    .takeLast(4000)

                val message = when {
                    output.isTimeout -> "Timed out while installing DAML SDK $normalized."
                    output.exitCode == 0 -> "DAML SDK $normalized installed."
                    combinedOutput.isNotBlank() -> "Failed to install DAML SDK $normalized:<br/><pre>${escapeHtml(combinedOutput)}</pre>"
                    else -> "Failed to install DAML SDK $normalized with exit code ${output.exitCode}."
                }
                finish(message, onFinished) {
                    if (output.exitCode == 0 && !output.isTimeout) {
                        DamlProjectSettings.getInstance(project).selectedSdkVersion = normalized
                        DamlNotifier.info(project, message)
                    } else {
                        DamlNotifier.error(project, message)
                    }
                }
            }
        }.queue()
    }

    private fun findInstaller(assistantOverride: String): Path? {
        assistantOverride.trim().takeIf { it.isNotBlank() }?.let { override ->
            val path = Path.of(override)
            if (Files.isExecutable(path)) return path
        }
        val settings = DamlProjectSettings.getInstance(project)
        if (settings.useDPMWhenAvailable) {
            RuntimeEnvironment.findExecutable("dpm", settings)?.let { return it }
        }
        RuntimeEnvironment.findExecutable("daml", settings)?.let { return it }
        RuntimeEnvironment.findExecutable("dpm", settings)?.let { return it }
        return null
    }

    private fun finish(message: String, onFinished: ((String) -> Unit)?, notify: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            notify()
            onFinished?.invoke(message.replace(Regex("<[^>]+>"), " "))
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    companion object {
        fun getInstance(project: Project): DamlSdkInstaller = project.service()
    }
}
