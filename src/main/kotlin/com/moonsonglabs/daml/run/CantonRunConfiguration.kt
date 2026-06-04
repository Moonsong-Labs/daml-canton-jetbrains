package com.moonsonglabs.daml.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.runtime.RuntimeValidator
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import org.jdom.Element
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class CantonRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<RunConfigurationOptions>(project, factory, name) {

    var mode: CantonMode = CantonMode.CONFIG
    var workspacePath: String = ""
    var targetPath: String = ""
    var extraArguments: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> =
        CantonRunSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                RuntimeValidator.getInstance(project).requireCantonReadyForRun()
                val cmd = GeneralCommandLine(buildCommandLine())
                    .withCharset(StandardCharsets.UTF_8)
                    .withWorkDirectory(resolveWorkspace().toFile())
                RuntimeEnvironment.applyLocalTools(cmd, DamlProjectSettings.getInstance(project))
                val handler = OSProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }

    fun buildCommandLine(): List<String> {
        val settings = DamlProjectSettings.getInstance(project)
        val binary = settings.cantonBinaryPath.takeIf { it.isNotBlank() } ?: "canton"
        val args = if (binary.endsWith(".jar")) {
            mutableListOf(javaExecutable(), "-jar", binary)
        } else {
            mutableListOf(binary)
        }
        args += CommandLineWords.split(settings.cantonExtraArguments)
        when (mode) {
            CantonMode.CONFIG -> {
                args += "--config"
                args += targetPath
            }
            CantonMode.SCRIPT -> {
                args += "-c"
                args += targetPath
            }
        }
        args += CommandLineWords.split(extraArguments)
        return args
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        val executable = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        val candidate = javaHome?.let { Path.of(it, "bin", executable) }
        return candidate?.takeIf(Files::isExecutable)?.toString() ?: "java"
    }

    fun resolveWorkspace(): Path {
        workspacePath.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return DamlWorkspaceService.getInstance(project).projectRoot()
            ?: throw ExecutionException("Project root not found.")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "mode", mode.name)
        JDOMExternalizerUtil.writeField(element, "workspacePath", workspacePath)
        JDOMExternalizerUtil.writeField(element, "targetPath", targetPath)
        JDOMExternalizerUtil.writeField(element, "extraArguments", extraArguments)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        mode = runCatching {
            CantonMode.valueOf(JDOMExternalizerUtil.readField(element, "mode") ?: CantonMode.CONFIG.name)
        }.getOrDefault(CantonMode.CONFIG)
        workspacePath = JDOMExternalizerUtil.readField(element, "workspacePath") ?: ""
        targetPath = JDOMExternalizerUtil.readField(element, "targetPath") ?: ""
        extraArguments = JDOMExternalizerUtil.readField(element, "extraArguments") ?: ""
    }
}

enum class CantonMode(val presentableName: String) {
    CONFIG("Config"),
    SCRIPT("Script")
}
