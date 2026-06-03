package com.moonsonglabs.daml.lsp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspCommandsCustomizer
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.moonsonglabs.daml.DamlBundle
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.scriptresults.VirtualResourceManager
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DamlLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (!isDamlFile(file)) return

        val workspaceRoot = DamlWorkspaceService.getInstance(project)
            .workspaceFor(file)
            ?.toAbsolutePath()
            ?.normalize()
            ?.takeIf { Files.isRegularFile(it.resolve("daml.yaml")) }
            ?: return

        val workspaceVirtualFile = LocalFileSystem.getInstance().findFileByNioFile(workspaceRoot) ?: return
        serverStarter.ensureServerStarted(DamlLspServerDescriptor(project, workspaceRoot, workspaceVirtualFile))
    }

    companion object {
        internal fun isDamlFile(file: VirtualFile): Boolean =
            file.fileType == DamlFileType || file.extension == "daml"
    }
}

private class DamlLspServerDescriptor(
    project: Project,
    private val workspaceRoot: Path,
    workspaceVirtualFile: VirtualFile
) : LspServerDescriptor(project, "DAML Language Server", workspaceVirtualFile) {

    override val lsp4jServerClass: Class<out LanguageServer> = DamlServerInterface::class.java
    override val lspCustomization: LspCustomization = DamlLspCustomization(project)
    override val lspServerListener: LspServerListener = DamlLspServerListener(project, this)

    override fun isSupportedFile(file: VirtualFile): Boolean {
        if (!DamlLspServerSupportProvider.isDamlFile(file)) return false
        val fileWorkspace = DamlWorkspaceService.getInstance(project)
            .workspaceFor(file)
            ?.toAbsolutePath()
            ?.normalize()
        return fileWorkspace == workspaceRoot
    }

    override fun createCommandLine(): GeneralCommandLine {
        val damlYaml = workspaceRoot.resolve("daml.yaml")
        if (!Files.isRegularFile(damlYaml)) {
            throw startupFailure(DamlBundle.message("daml.notification.no.daml.yaml"))
        }

        val settings = DamlProjectSettings.getInstance(project)
        val resolution = DamlBinaryLocator.locate(project, workspaceRoot)
            ?: throw startupFailure(DamlBundle.message("daml.notification.sdk.notFound"))

        val args = mutableListOf<String>().apply {
            add(resolution.binary.toAbsolutePath().toString())
            add("damlc")
            add("ide")
            add(telemetryFlag(settings.telemetry))
            add("--log-level=${settings.logLevel}")
            settings.extraArguments.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .forEach(::add)
            add("+RTS")
            add("-M4G")
            add("-N")
            add("-RTS")
        }

        thisLogger().info("Starting DAML LSP: " + args.joinToString(" "))
        return GeneralCommandLine(args)
            .withWorkingDirectory(workspaceRoot)
            .withEnvironment(RuntimeEnvironment.localToolEnvironment(settings))
    }

    override fun getLanguageId(file: VirtualFile): String = "daml"

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        DamlLanguageClient(project, handler)

    private fun startupFailure(message: String): ExecutionException {
        DamlNotifier.warn(project, message)
        return ExecutionException(message)
    }

    private fun telemetryFlag(value: String): String = when (value) {
        "opt-in" -> "--telemetry"
        "ignored" -> "--telemetry-ignored"
        else -> "--optOutTelemetry"
    }
}

private class DamlLspCustomization(project: Project) : LspCustomization() {
    private val commandSupport = DamlLspCommandSupport(project)

    override val semanticTokensCustomizer: LspSemanticTokensCustomizer = LspSemanticTokensDisabled

    override val commandsCustomizer: LspCommandsCustomizer = commandSupport
}

private class DamlLspCommandSupport(private val project: Project) : LspCommandsSupport() {
    private val gson = Gson()

    override fun executeCommand(server: LspServer, contextFile: VirtualFile, command: Command) {
        if (command.command != "daml.showResource") {
            super.executeCommand(server, contextFile, command)
            return
        }

        val resource = parse(command.arguments ?: emptyList())
        if (resource == null) {
            DamlNotifier.warn(project, "Unable to open DAML script result: missing resource URI.")
            return
        }
        VirtualResourceManager.getInstance(project).showResource(resource.title, resource.uri)
    }

    private fun parse(args: List<Any?>): Resource? {
        val objects = args.mapNotNull(::asMap)
        for (obj in objects) {
            val uri = obj["uri"] as? String ?: obj["resource"] as? String
            if (uri != null) {
                val title = (obj["title"] as? String) ?: (obj["name"] as? String) ?: "DAML Script Results"
                return Resource(title, uri)
            }
        }
        val strings = args.filterIsInstance<String>()
        val uri = strings.firstOrNull { it.startsWith("daml://") || it.startsWith("file://") } ?: return null
        val title = strings.firstOrNull { it != uri } ?: "DAML Script Results"
        return Resource(title, uri)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?>? = when (value) {
        is Map<*, *> -> value as Map<String, Any?>
        is JsonObject -> gson.fromJson(value, Map::class.java) as Map<String, Any?>
        else -> null
    }

    private data class Resource(val title: String, val uri: String)
}

private class DamlLspServerListener(
    private val project: Project,
    private val descriptor: LspServerDescriptor
) : LspServerListener {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DAML-keepAlive").apply { isDaemon = true }
    }
    @Volatile private var keepAliveTask: ScheduledFuture<*>? = null

    override fun serverInitialized(params: InitializeResult) {
        startKeepAlive()
    }

    override fun serverStopped(shutdownNormally: Boolean) {
        cancelKeepAlive()
        scheduler.shutdownNow()
    }

    private fun startKeepAlive() {
        cancelKeepAlive()
        keepAliveTask = scheduler.scheduleWithFixedDelay({ tick() }, 60, 60, TimeUnit.SECONDS)
    }

    private fun cancelKeepAlive() {
        keepAliveTask?.cancel(false)
        keepAliveTask = null
    }

    private fun tick() {
        if (project.isDisposed) {
            cancelKeepAlive()
            scheduler.shutdownNow()
            return
        }

        try {
            val server = LspServerManager.getInstance(project)
                .getServersForProvider(DamlLspServerSupportProvider::class.java)
                .firstOrNull { it.descriptor === descriptor }
                ?: return

            server.sendRequestSync(120_000) { lsp ->
                (lsp as DamlServerInterface).keepAlive()
            }
        } catch (t: Throwable) {
            thisLogger().warn("DAML keep-alive failed; restarting server", t)
            DamlNotifier.warn(project, DamlBundle.message("daml.notification.server.unresponsive"))
            LspServerManager.getInstance(project)
                .stopAndRestartIfNeeded(DamlLspServerSupportProvider::class.java)
        }
    }
}
