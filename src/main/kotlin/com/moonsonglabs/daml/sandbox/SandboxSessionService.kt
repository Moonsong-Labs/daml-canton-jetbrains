package com.moonsonglabs.daml.sandbox

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class SandboxSessionState(
    var profileId: String = "",
    var status: SandboxSessionStatus = SandboxSessionStatus.STOPPED,
    var generated: SandboxGeneratedFiles? = null,
    var endpoints: List<Endpoint> = emptyList(),
    var health: List<HealthSnapshot> = emptyList(),
    var log: String = "",
    var message: String = ""
)

@Service(Service.Level.PROJECT)
class SandboxSessionService(private val project: Project) : Disposable {
    private val validator = SandboxRuntimeValidator.getInstance(project)
    private val listeners = CopyOnWriteArrayList<(SandboxSessionState) -> Unit>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "Managed-Canton-Sandbox").apply { isDaemon = true }
    }
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val readinessTimeout = Duration.ofSeconds(90)
    private val readinessPollInterval = Duration.ofSeconds(1)
    private var handler: OSProcessHandler? = null
    private var state = SandboxSessionState()
    @Volatile
    private var bootstrapReady = false

    fun snapshot(): SandboxSessionState = state.copy()

    fun addListener(listener: (SandboxSessionState) -> Unit): Disposable {
        listeners += listener
        listener(snapshot())
        return Disposable { listeners -= listener }
    }

    fun generate(profile: SandboxProfile): SandboxGeneratedFiles {
        val runtimeProfile = runtimeProfile(profile)
        update(status = SandboxSessionStatus.GENERATING, profile = runtimeProfile, message = "Generating sandbox files")
        val generated = generator().generate(runtimeProfile)
        update(status = SandboxSessionStatus.STOPPED, profile = runtimeProfile, generated = generated, message = "Generated ${generated.root}")
        return generated
    }

    fun startLocal(profile: SandboxProfile) {
        executor.execute {
            runCatching {
                stopCurrent(wait = true)
                val runtimeProfile = runtimeProfile(profile)
                val validation = validator.validate(runtimeProfile, checkDarContents = false)
                if (!validation.ok) {
                    val details = validation.checks.filterNot { it.ok }.joinToString("\n") { "${it.name}: ${it.detail}" }
                    throw ExecutionException("${validation.message}\n$details")
                }
                val generated = generate(runtimeProfile)
                val command = localCantonCommand(generated)
                startProcess(runtimeProfile, generated, command, generated.localConfig.parent.toFile().absolutePath)
            }.onFailure {
                appendLog("Failed to start local sandbox: ${it.message}\n")
                update(status = SandboxSessionStatus.FAILED, profile = profile, message = it.message ?: "Local start failed")
            }
        }
    }

    fun stop() {
        executor.execute {
            stopCurrent(wait = true)
            update(status = SandboxSessionStatus.STOPPED, message = "Stopped")
        }
    }

    fun clean(profile: SandboxProfile) {
        executor.execute {
            stopCurrent(wait = true)
            val root = generator().generatedRoot(runtimeProfile(profile))
            runCatching {
                root.resolve("local").resolve("log").toFile().deleteRecursively()
                root.resolve("logs").toFile().deleteRecursively()
                root.resolve("data").toFile().deleteRecursively()
            }.onFailure { appendLog("Clean failed: ${it.message}\n") }
            update(status = SandboxSessionStatus.STOPPED, profile = profile, message = "Cleaned runtime data")
        }
    }

    fun refreshHealth(profile: SandboxProfile) {
        executor.execute {
            val endpoints = EndpointBuilder.all(profile)
            val health = jsonHealth(profile)
            update(profile = profile, endpoints = endpoints, health = health, message = "Refreshed health")
        }
    }

    fun clearLog() {
        state = state.copy(log = "")
        notifyListeners()
    }

    fun runJsonRequest(endpoint: Endpoint, method: String, path: String, token: String?, body: String?): SandboxHttpResponse =
        JsonApiClient().request(method, endpoint.url, path, token, body)

    internal fun runSynchronizerDiagnostic(
        profile: SandboxProfile,
        sync: SynchronizerNode,
        preset: SyncDiagnosticPreset
    ): SyncDiagnosticResponse =
        SyncDomainDiagnosticRunner(project).run(profile, sync, preset)

    fun fetchLedgerSnapshot(profile: SandboxProfile, participantName: String, token: String?): LedgerExplorerSnapshot {
        val participant = profile.participants.firstOrNull { it.name == participantName }
            ?: throw ExecutionException("Participant $participantName is not part of profile ${profile.name}.")
        return SandboxLedgerExplorer(projectRoot = DamlWorkspaceService.getInstance(project).projectRoot()).fetch(profile, participant, token)
    }

    private fun startProcess(
        profile: SandboxProfile,
        generated: SandboxGeneratedFiles,
        command: GeneralCommandLine,
        workDirectory: String
    ) {
        update(
            status = SandboxSessionStatus.STARTING,
            profile = profile,
            generated = generated,
            endpoints = EndpointBuilder.all(profile),
            message = command.commandLineString
        )
        command.withWorkDirectory(workDirectory).withCharset(StandardCharsets.UTF_8)
        RuntimeEnvironment.applyLocalTools(command, DamlProjectSettings.getInstance(project))
        val processHandler = OSProcessHandler(command)
        bootstrapReady = false
        handler = processHandler
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (event.text.contains("=== sandbox ready ===")) {
                    bootstrapReady = true
                }
                appendLog(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                val next = if (event.exitCode == 0) SandboxSessionStatus.STOPPED else SandboxSessionStatus.FAILED
                update(status = next, message = "Process exited with code ${event.exitCode}")
            }
        })
        ProcessTerminatedListener.attach(processHandler)
        processHandler.startNotify()
        update(status = SandboxSessionStatus.STARTING, message = "Process started; waiting for JSON APIs")
        waitForReadiness(profile, processHandler)
    }

    private fun localCantonCommand(generated: SandboxGeneratedFiles): GeneralCommandLine {
        val settings = DamlProjectSettings.getInstance(project)
        val override = settings.cantonBinaryPath.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val base = when {
            override != null && override.toString().endsWith(".jar") -> listOf(javaExecutable(), "-jar", override.toString())
            override != null -> listOf(override.toString())
            RuntimeEnvironment.findExecutable("canton", settings) != null -> listOf(RuntimeEnvironment.findExecutable("canton", settings)!!.toString())
            else -> listOf(javaExecutable(), "-jar", locateCantonJar(settings).toString())
        }
        return GeneralCommandLine(base + listOf(
            "daemon",
            "-c",
            generated.localConfig.fileName.toString(),
            "--bootstrap",
            generated.localBootstrap.fileName.toString()
        ))
    }

    private fun generator(): SandboxGenerator =
        SandboxGenerator(DamlWorkspaceService.getInstance(project).projectRoot())

    private fun runtimeProfile(profile: SandboxProfile): SandboxProfile =
        SandboxProjectPaths.runtimeProfile(profile, DamlWorkspaceService.getInstance(project))

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        val candidate = javaHome?.let { Path.of(it, "bin", if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java") }
        return candidate?.takeIf(Files::isExecutable)?.toString() ?: "java"
    }

    private fun locateCantonJar(settings: DamlProjectSettings): Path {
        return CantonJarLocator.find(settings)
            ?: throw ExecutionException("canton.jar not found. Set the Canton binary path to canton.jar, or set CANTON_JAR/CANTON_SDK_VERSION.")
    }

    private fun stopCurrent(wait: Boolean) {
        update(status = SandboxSessionStatus.STOPPING, message = "Stopping")
        val current = handler
        if (current != null && !current.isProcessTerminated) {
            current.destroyProcess()
            if (wait && !current.waitFor(15_000)) {
                update(status = SandboxSessionStatus.FAILED, message = "Timed out stopping Canton process; check Logs before retrying.")
                return
            }
        }
        handler = null
        bootstrapReady = false
    }

    private fun httpOk(url: String): Boolean {
        val response = httpGet(url) ?: return false
        return response.statusCode() in 200..299
    }

    private fun httpGet(url: String): HttpResponse<String>? =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrNull()

    private fun waitForReadiness(profile: SandboxProfile, processHandler: OSProcessHandler) {
        val endpoints = EndpointBuilder.all(profile)
        val expectedJsonEndpoints = EndpointBuilder.participantEndpoints(profile).count { it.kind == "json" }
        val deadline = System.nanoTime() + readinessTimeout.toNanos()
        var latestHealth: List<HealthSnapshot> = emptyList()

        while (!processHandler.isProcessTerminated && System.nanoTime() < deadline) {
            latestHealth = jsonHealth(profile)
            val readyCount = latestHealth.count { it.live && it.ready }
            val allReady = expectedJsonEndpoints == 0 || (latestHealth.size == expectedJsonEndpoints && readyCount == expectedJsonEndpoints)
            if (allReady && bootstrapReady) {
                update(
                    status = SandboxSessionStatus.RUNNING,
                    profile = profile,
                    endpoints = endpoints,
                    health = latestHealth,
                    message = "Sandbox ready: $readyCount/$expectedJsonEndpoints JSON API(s) serving"
                )
                return
            }
            update(
                status = SandboxSessionStatus.STARTING,
                profile = profile,
                endpoints = endpoints,
                health = latestHealth,
                message = if (allReady) {
                    "Waiting for bootstrap script to finish"
                } else {
                    "Waiting for JSON APIs: $readyCount/$expectedJsonEndpoints ready"
                }
            )
            try {
                Thread.sleep(readinessPollInterval.toMillis())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        if (!processHandler.isProcessTerminated) {
            update(
                status = SandboxSessionStatus.FAILED,
                profile = profile,
                endpoints = endpoints,
                health = latestHealth,
                message = if (bootstrapReady) {
                    "Canton process is running, but JSON APIs did not become ready within ${readinessTimeout.seconds}s. Check Logs."
                } else {
                    "Canton process is running, but bootstrap did not finish within ${readinessTimeout.seconds}s. Check Logs."
                }
            )
        }
    }

    private fun jsonHealth(profile: SandboxProfile): List<HealthSnapshot> =
        EndpointBuilder.participantEndpoints(profile)
            .filter { it.kind == "json" }
            .map { endpoint ->
                val live = httpOk(endpoint.url.trimEnd('/') + "/livez")
                val ready = httpOk(endpoint.url.trimEnd('/') + "/readyz")
                HealthSnapshot(endpoint, live, ready, "live=${live.statusText()} ready=${ready.statusText()}")
            }

    private fun Boolean.statusText(): String = if (this) "ok" else "down"

    private fun appendLog(text: String) {
        if (text.isEmpty()) return
        val combined = (state.log + text).takeLast(200_000)
        state = state.copy(log = combined)
        notifyListeners()
    }

    private fun update(
        status: SandboxSessionStatus? = null,
        profile: SandboxProfile? = null,
        generated: SandboxGeneratedFiles? = null,
        endpoints: List<Endpoint>? = null,
        health: List<HealthSnapshot>? = null,
        message: String? = null
    ) {
        state = state.copy(
            profileId = profile?.id ?: state.profileId,
            status = status ?: state.status,
            generated = generated ?: state.generated,
            endpoints = endpoints ?: state.endpoints,
            health = health ?: state.health,
            message = message ?: state.message
        )
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = snapshot()
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it(snapshot) }
        }
    }

    override fun dispose() {
        stopCurrent(wait = false)
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): SandboxSessionService = project.service()
    }
}
