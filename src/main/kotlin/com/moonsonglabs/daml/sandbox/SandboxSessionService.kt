package com.moonsonglabs.daml.sandbox

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
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
    var message: String = "",
    val sessionId: String = "",
    val launchedProfile: SandboxProfile? = null,
    val ownsProcess: Boolean = false,
    val runtimeVersion: String = ""
) {
    fun belongsTo(profile: SandboxProfile): Boolean = profileId == profile.id
    fun canQuery(profile: SandboxProfile): Boolean = belongsTo(profile) && ownsProcess && sessionId.isNotBlank() && status == SandboxSessionStatus.RUNNING
    fun hasPendingChanges(profile: SandboxProfile): Boolean = belongsTo(profile) && launchedProfile?.let {
        it.runtimeDefinition() != profile.runtimeDefinition()
    } == true
}

@Service(Service.Level.PROJECT)
class SandboxSessionService(private val project: Project) : Disposable {
    private val validator = SandboxRuntimeValidator.getInstance(project)
    private val listeners = CopyOnWriteArrayList<(SandboxSessionState) -> Unit>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "Managed-Canton-Sandbox").apply { isDaemon = true }
    }
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val ledgerExplorer by lazy { SandboxLedgerExplorer(projectRoot = DamlWorkspaceService.getInstance(project).projectRoot()) }
    internal var readinessTimeout = Duration.ofSeconds(90)
    internal var readinessPollInterval = Duration.ofSeconds(1)
    private val lifecycle = SandboxLifecycle()
    private val stateLock = Any()
    @Volatile private var handler: SandboxManagedProcess? = null
    private var state = SandboxSessionState()
    @Volatile
    private var bootstrapReady = false

    internal var runtimeOperations = SandboxRuntimeOperations(::prepareLaunch, ::CantonManagedProcess, ::jsonHealth)
    internal var stopTimeoutMs: Long = 15_000

    fun snapshot(): SandboxSessionState = synchronized(stateLock) {
        state.copy(launchedProfile = state.launchedProfile?.deepCopy(), ownsProcess = handler?.isTerminated == false)
    }

    fun addListener(listener: (SandboxSessionState) -> Unit): Disposable {
        listeners += listener
        listener(snapshot())
        return Disposable { listeners -= listener }
    }

    fun generate(profile: SandboxProfile): SandboxGeneratedFiles = generator().generate(runtimeProfile(profile.deepCopy()))

    fun startLocal(profile: SandboxProfile) {
        val draft = profile.deepCopy()
        lifecycle.submit { generation ->
            runCatching {
                check(stopCurrent(wait = true)) { "The previous Canton process has not stopped." }
                if (!lifecycle.isCurrent(generation)) return@submit
                update(status = SandboxSessionStatus.STARTING, profile = draft, message = "Validating network…")
                val prepared = runtimeOperations.prepare(draft) { !lifecycle.isCurrent(generation) } ?: return@submit
                if (!lifecycle.isCurrent(generation)) return@submit
                synchronized(stateLock) {
                    state = state.copy(sessionId = generation.toString(), launchedProfile = draft.deepCopy(), runtimeVersion = prepared.version, health = emptyList())
                }
                startProcess(prepared.profile, prepared.generated, prepared.command, prepared.generated.localConfig.parent.toString(), generation)

            }.onFailure {
                if (lifecycle.isCurrent(generation)) {
                    appendLog("Failed to start local sandbox: ${it.message}\n")
                    update(status = SandboxSessionStatus.FAILED, message = it.message ?: "Local start failed")
                }
            }
        }
    }

    fun stop() {
        lifecycle.submit {
            if (stopCurrent(wait = true)) update(status = SandboxSessionStatus.STOPPED, message = "Stopped")
        }
    }

    fun clean(profile: SandboxProfile) {
        val draft = profile.deepCopy()
        lifecycle.enqueue {
            if (snapshot().ownsProcess || snapshot().status == SandboxSessionStatus.STARTING) {
                appendLog("Stop the running sandbox before cleaning runtime files.\n")
                return@enqueue
            }
            runCatching { SandboxFileOwnership.clean(generator().generatedRoot(runtimeProfile(draft))) }
                .onSuccess { appendLog(if (it) "Cleaned owned runtime logs.\n" else "No owned runtime files to clean; existing files preserved.\n") }
                .onFailure { appendLog("Clean failed: ${it.message}\n") }
        }
    }

    fun refreshHealth(profile: SandboxProfile) {
        val current = snapshot()
        if (!current.belongsTo(profile) || !current.ownsProcess) return
        val launched = current.launchedProfile ?: return
        executor.execute {
            val health = runtimeOperations.probe(launched) { snapshot().sessionId != current.sessionId || !snapshot().ownsProcess }
            synchronized(stateLock) {
                if (state.sessionId != current.sessionId || state.profileId != current.profileId || handler?.isTerminated != false) return@execute
                state = state.copy(health = health)
            }
            notifyListeners()
        }
    }

    fun clearLog() {
        synchronized(stateLock) { state = state.copy(log = "") }
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

    fun fetchLedgerSnapshot(profile: SandboxProfile, participantName: String, token: String?, selectedParties: Set<String>? = null): LedgerExplorerSnapshot {
        val session = snapshot()
        check(session.canQuery(profile)) { "Start this sandbox before loading ledger data." }
        val launched = session.launchedProfile ?: error("No launched profile")
        val participant = launched.participants.firstOrNull { it.name == participantName }
            ?: throw ExecutionException("Participant $participantName is not part of profile ${profile.name}.")
        return ledgerExplorer.fetch(launched, participant, token, session.sessionId, selectedParties)
    }

    private fun startProcess(
        profile: SandboxProfile,
        generated: SandboxGeneratedFiles,
        command: GeneralCommandLine,
        workDirectory: String,
        generation: Long
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
        val processHandler = runtimeOperations.launch(command)
        bootstrapReady = false
        handler = processHandler
        processHandler.start(onText = { text ->
            if (handler === processHandler && snapshot().sessionId == generation.toString()) {
                if (text.contains("=== sandbox ready ===")) bootstrapReady = true
                appendLog(text)
            }
        }, onExit = { code ->
            if (handler === processHandler && snapshot().sessionId == generation.toString()) {
                val next = if (code == 0) SandboxSessionStatus.STOPPED else SandboxSessionStatus.FAILED
                update(status = next, message = "Process exited with code $code")
            }
        })
        update(status = SandboxSessionStatus.STARTING, message = "Process started; waiting for JSON APIs")
        waitForReadiness(profile, processHandler, generation)
    }

    private fun prepareLaunch(draft: SandboxProfile, cancelled: () -> Boolean): SandboxPreparedLaunch? {
        val runtime = runtimeProfile(draft)
        val validation = validator.validate(runtime, checkDarContents = false)
        if (cancelled()) return null
        check(validation.ok) { validation.checks.filterNot { it.ok }.joinToString("\n") { "${it.name}: ${it.detail}" } }
        val generated = generator().generate(runtime)
        if (cancelled()) return null
        val command = localCantonCommand(generated)
        val settings = DamlProjectSettings.getInstance(project)
        RuntimeEnvironment.applyLocalTools(command, settings)
        val versionCommand = GeneralCommandLine(command.exePath).withParameters(command.parametersList.list.dropLast(5) + "--version")
            .withWorkDirectory(generated.localConfig.parent.toFile())
        RuntimeEnvironment.applyLocalTools(versionCommand, settings)
        val version = runCatching {
            CapturingProcessHandler(versionCommand).runProcess(10_000).stdout.trim().lineSequence().firstOrNull().orEmpty()
        }.getOrDefault("").ifBlank { "Unknown (version probe unavailable)" }
        return if (cancelled()) null else SandboxPreparedLaunch(runtime, generated, command, version)
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

    private fun stopCurrent(wait: Boolean): Boolean {
        update(status = SandboxSessionStatus.STOPPING, message = "Stopping")
        val current = handler
        if (current != null && !current.isTerminated) {
            current.terminate()
            if (wait && !current.waitFor(stopTimeoutMs)) {
                update(status = SandboxSessionStatus.FAILED, message = "Timed out stopping Canton process; check Logs before retrying.")
                return false
            }
        }
        handler = null
        bootstrapReady = false
        return true
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

    private fun waitForReadiness(profile: SandboxProfile, processHandler: SandboxManagedProcess, generation: Long) {
        val endpoints = EndpointBuilder.all(profile)
        val expectedJsonEndpoints = EndpointBuilder.participantEndpoints(profile).count { it.kind == "json" }
        val deadline = System.nanoTime() + readinessTimeout.toNanos()
        var latestHealth: List<HealthSnapshot> = emptyList()

        while (lifecycle.isCurrent(generation) && !processHandler.isTerminated && System.nanoTime() < deadline) {
            latestHealth = runtimeOperations.probe(profile) { !lifecycle.isCurrent(generation) }
            if (!lifecycle.isCurrent(generation)) return
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

        if (lifecycle.isCurrent(generation) && !processHandler.isTerminated) {
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

    private fun jsonHealth(profile: SandboxProfile, cancelled: () -> Boolean): List<HealthSnapshot> =
        EndpointBuilder.participantEndpoints(profile)
            .filter { it.kind == "json" }
            .mapNotNull { endpoint ->
                if (cancelled()) return@mapNotNull null
                val live = httpOk(endpoint.url.trimEnd('/') + "/livez")
                if (cancelled()) return@mapNotNull null
                val ready = httpOk(endpoint.url.trimEnd('/') + "/readyz")
                val connected = if (ready && !cancelled()) runCatching {
                    val response = httpGet(endpoint.url.trimEnd('/') + "/v2/state/connected-synchronizers")
                    if (response?.statusCode() !in 200..299) null else com.google.gson.JsonParser.parseString(response!!.body()).asJsonObject
                        .getAsJsonArray("connectedSynchronizers").map { it.asJsonObject.get("synchronizerAlias").asString }.toSet()
                }.getOrNull() else null
                HealthSnapshot(endpoint, live, ready, "live=${live.statusText()} ready=${ready.statusText()}", connectedSynchronizers = connected)
            }

    private fun Boolean.statusText(): String = if (this) "ok" else "down"

    private fun appendLog(text: String) {
        if (text.isEmpty()) return
        synchronized(stateLock) { state = state.copy(log = (state.log + text).takeLast(200_000)) }
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
        synchronized(stateLock) { state = state.copy(
            profileId = profile?.id ?: state.profileId,
            status = status ?: state.status,
            generated = generated ?: state.generated,
            endpoints = endpoints ?: state.endpoints,
            health = health ?: state.health,
            message = message ?: state.message
        ) }
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = snapshot()
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it(snapshot) }
        }
    }

    override fun dispose() {
        lifecycle.close()
        handler?.takeIf { !it.isTerminated }?.terminate()
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): SandboxSessionService = project.service()
    }
}
