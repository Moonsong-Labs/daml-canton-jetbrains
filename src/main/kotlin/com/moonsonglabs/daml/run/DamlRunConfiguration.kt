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
import com.moonsonglabs.daml.lsp.DamlBinaryLocator
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.runtime.RuntimeValidator
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import org.jdom.Element
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class DamlRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<RunConfigurationOptions>(project, factory, name) {

    var command: DamlCommand = DamlCommand.BUILD
    var workspacePath: String = ""
    var filePath: String = ""
    var scriptName: String = ""
    var darPath: String = ""
    var extraArguments: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> =
        DamlRunSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                RuntimeValidator.getInstance(project).requireDamlReadyForRun()
                val cmd = GeneralCommandLine(buildCommandLine())
                    .withCharset(StandardCharsets.UTF_8)
                    .withWorkDirectory(resolveWorkspace().toFile())
                RuntimeEnvironment.applyLocalTools(cmd, DamlProjectSettings.getInstance(project))
                val handler = OSProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }

    override fun checkConfiguration() {
        validationError(command, scriptName, darPath, additionalArguments())?.let {
            throw com.intellij.execution.configurations.RuntimeConfigurationError(it)
        }
    }

    private fun additionalArguments(): List<String> =
        CommandLineWords.split(DamlProjectSettings.getInstance(project).extraArguments) + CommandLineWords.split(extraArguments)

    fun buildCommandLine(): List<String> {
        val settings = DamlProjectSettings.getInstance(project)
        val workspace = runCatching { resolveWorkspace() }.getOrNull()
        val binary = DamlBinaryLocator.locate(project, workspace)?.binary?.toAbsolutePath()?.toString()
            ?: settings.binaryPath.takeIf { it.isNotBlank() }
            ?: if (settings.useDPMWhenAvailable) "dpm" else "daml"
        val args = mutableListOf(binary)
        when (command) {
            DamlCommand.BUILD -> args += "build"
            DamlCommand.TEST -> args += "test"
            DamlCommand.SCRIPT -> {
                args += "script"
                if (darPath.isNotBlank()) { args += DAR_OPTION; args += darPath }
                if (scriptName.isNotBlank()) {
                    args += "--script-name"
                    args += scriptName
                }
            }
            DamlCommand.START -> args += "start"
        }
        if (filePath.isNotBlank() && command == DamlCommand.TEST) {
            args += "--files"
            args += filePath
        }
        args += additionalArguments()
        return args
    }

    fun resolveWorkspace(): Path {
        workspacePath.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return DamlWorkspaceService.getInstance(project).defaultWorkspace()
            ?: DamlWorkspaceService.getInstance(project).projectRoot()
            ?: throw ExecutionException("Project root not found.")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "command", command.name)
        JDOMExternalizerUtil.writeField(element, "workspacePath", workspacePath)
        JDOMExternalizerUtil.writeField(element, "filePath", filePath)
        JDOMExternalizerUtil.writeField(element, "scriptName", scriptName)
        JDOMExternalizerUtil.writeField(element, "darPath", darPath)
        JDOMExternalizerUtil.writeField(element, "extraArguments", extraArguments)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        command = runCatching {
            DamlCommand.valueOf(JDOMExternalizerUtil.readField(element, "command") ?: DamlCommand.BUILD.name)
        }.getOrDefault(DamlCommand.BUILD)
        workspacePath = JDOMExternalizerUtil.readField(element, "workspacePath") ?: ""
        filePath = JDOMExternalizerUtil.readField(element, "filePath") ?: ""
        scriptName = JDOMExternalizerUtil.readField(element, "scriptName") ?: ""
        darPath = JDOMExternalizerUtil.readField(element, "darPath") ?: ""
        extraArguments = JDOMExternalizerUtil.readField(element, "extraArguments") ?: ""
    }

    companion object {
        internal const val DAR_OPTION = "--dar"

        internal fun validationError(command: DamlCommand, scriptName: String, darPath: String, arguments: List<String>): String? {
            if (command != DamlCommand.SCRIPT) return null
            if (scriptName.isBlank()) return "Choose a script (Module:script)."
            val options = arguments.takeWhile { it != "--" }
            val hasDarArgument = options.indices.any { index ->
                val option = options[index]
                if (option == DAR_OPTION) options.getOrNull(index + 1)?.let { it.isNotBlank() && !it.startsWith('-') } == true
                else option.startsWith("$DAR_OPTION=") && option.substringAfter('=').isNotBlank()
            }
            return if (darPath.isBlank() && !hasDarArgument) "Choose a compiled DAR for CLI Run Script." else null
        }
    }
}

enum class DamlCommand(val presentableName: String) {
    BUILD("Build"),
    TEST("Test"),
    SCRIPT("CLI Run Script"),
    START("Start");

    override fun toString(): String = presentableName
}
