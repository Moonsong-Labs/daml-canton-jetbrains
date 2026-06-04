package com.moonsonglabs.daml.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.AbstractBorder
import javax.swing.plaf.basic.BasicScrollBarUI

internal enum class SyncDiagnosticKind {
    TCP,
    CONSOLE
}

internal data class SyncDiagnosticPreset(
    val id: String,
    val group: String,
    val name: String,
    val description: String,
    val kind: SyncDiagnosticKind,
    val scriptBody: String = ""
)

internal data class SyncDiagnosticResponse(
    val exitCode: Int,
    val output: String,
    val durationMillis: Long
) {
    val ok: Boolean get() = exitCode == 0
}

internal object SyncDiagnosticCatalog {
    const val START_MARKER = "=== Managed Canton Synchronizer Diagnostic ==="
    const val END_MARKER = "=== End Managed Canton Synchronizer Diagnostic ==="

    fun builtInPresets(): List<SyncDiagnosticPreset> = listOf(
        SyncDiagnosticPreset(
            id = "ports",
            group = "Reachability",
            name = "Check ports",
            description = "Open TCP connections to sequencer public/admin and mediator admin ports.",
            kind = SyncDiagnosticKind.TCP
        ),
        SyncDiagnosticPreset(
            id = "all-status",
            group = "Canton Admin",
            name = "Synchronizer status",
            description = "Run Canton health.status through the selected sequencer/mediator admin endpoints.",
            kind = SyncDiagnosticKind.CONSOLE,
            scriptBody = "println(health.status)"
        ),
        SyncDiagnosticPreset(
            id = "sequencer-status",
            group = "Canton Admin",
            name = "Sequencer status",
            description = "Run local.health.status for the selected sequencer.",
            kind = SyncDiagnosticKind.CONSOLE,
            scriptBody = "println(local.health.status)"
        ),
        SyncDiagnosticPreset(
            id = "mediator-status",
            group = "Canton Admin",
            name = "Mediator status",
            description = "Run mediator1.health.status for the selected mediator.",
            kind = SyncDiagnosticKind.CONSOLE,
            scriptBody = "println(mediator1.health.status)"
        ),
        SyncDiagnosticPreset(
            id = "participant-route",
            group = "Canton Admin",
            name = "Participant route",
            description = "Run sandbox.health.status to show the diagnostic participant's connected synchronizers.",
            kind = SyncDiagnosticKind.CONSOLE,
            scriptBody = "println(sandbox.health.status)"
        )
    )

    fun script(profile: SandboxProfile, sync: SynchronizerNode, participant: ParticipantNode, preset: SyncDiagnosticPreset): String =
        buildString {
            appendLine("""println("$START_MARKER")""")
            appendLine("""println("Profile: ${scalaString(profile.name)}")""")
            appendLine("""println("Synchronizer: ${scalaString(sync.name)}")""")
            appendLine("""println("Participant probe: ${scalaString(participant.name)}")""")
            appendLine("""println("Sequencer public: grpc://127.0.0.1:${sync.sequencer.publicPort}")""")
            appendLine("""println("Sequencer admin: grpc://127.0.0.1:${sync.sequencer.adminPort}")""")
            appendLine("""println("Mediator admin: grpc://127.0.0.1:${sync.mediator.adminPort}")""")
            appendLine("""println("Action: ${scalaString(preset.name)}")""")
            appendLine("""println("------------------------------------------------")""")
            appendLine(preset.scriptBody)
            appendLine("""println("$END_MARKER")""")
        }

    private fun scalaString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

internal class SyncDomainDiagnosticRunner(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun run(profile: SandboxProfile, sync: SynchronizerNode, preset: SyncDiagnosticPreset): SyncDiagnosticResponse =
        when (preset.kind) {
            SyncDiagnosticKind.TCP -> runPortCheck(profile, sync, preset)
            SyncDiagnosticKind.CONSOLE -> runConsole(profile, sync, preset)
        }

    private fun runPortCheck(profile: SandboxProfile, sync: SynchronizerNode, preset: SyncDiagnosticPreset): SyncDiagnosticResponse {
        val started = System.nanoTime()
        val checks = listOf(
            Triple("sequencer public", "sequencer-public", sync.sequencer.publicPort),
            Triple("sequencer admin", "sequencer-admin", sync.sequencer.adminPort),
            Triple("mediator admin", "mediator-admin", sync.mediator.adminPort)
        )
        val endpoints = checks.map { (name, kind, port) ->
            val ok = canConnect(port)
            JsonObject().apply {
                addProperty("name", name)
                addProperty("kind", kind)
                addProperty("url", "grpc://127.0.0.1:$port")
                addProperty("status", if (ok) "ok" else "down")
            }
        }
        val exit = if (endpoints.all { it.get("status").asString == "ok" }) 0 else 2
        val duration = elapsedMillis(started)
        return SyncDiagnosticResponse(exit, diagnosticJson(profile, sync, preset, null, exit, duration, endpoints = endpoints), duration)
    }

    private fun runConsole(profile: SandboxProfile, sync: SynchronizerNode, preset: SyncDiagnosticPreset): SyncDiagnosticResponse {
        val participant = diagnosticParticipant(profile, sync)
            ?: throw ExecutionException("Select a profile with at least one participant to query synchronizer admin endpoints.")
        val script = Files.createTempFile("managed-canton-sync-", ".canton")
        val started = System.nanoTime()
        try {
            Files.writeString(script, SyncDiagnosticCatalog.script(profile, sync, participant, preset))
            val command = cantonConsoleCommand(profile, sync, participant, script)
            val output = CapturingProcessHandler(command).runProcess(45_000)
            val raw = listOf(output.stdout, output.stderr)
                .joinToString("\n")
                .let(::stripAnsi)
                .trim()
                .ifBlank {
                    if (output.isTimeout) "Timed out while waiting for Canton admin response."
                    else "No output."
                }
            val exit = if (output.isTimeout) 124 else output.exitCode
            val duration = elapsedMillis(started)
            val diagnosticOutput = extractDiagnosticOutput(raw)
            return SyncDiagnosticResponse(
                exit,
                diagnosticJson(profile, sync, preset, participant, exit, duration, diagnosticOutput),
                duration
            )
        } finally {
            runCatching { Files.deleteIfExists(script) }
        }
    }

    private fun diagnosticParticipant(profile: SandboxProfile, sync: SynchronizerNode): ParticipantNode? {
        val connected = profile.bindings
            .firstOrNull { it.synchronizerId == sync.id && it.connected }
            ?.participantId
            ?.let(profile::participant)
        return connected ?: profile.participants.firstOrNull()
    }

    private fun cantonConsoleCommand(
        profile: SandboxProfile,
        sync: SynchronizerNode,
        participant: ParticipantNode,
        script: Path
    ): GeneralCommandLine {
        val settings = DamlProjectSettings.getInstance(project)
        val base = cantonBaseCommand(settings)
        val command = GeneralCommandLine(
            base + listOf(
                "sandbox-console",
                "--host",
                "127.0.0.1",
                "--port",
                participant.ledgerPort.toString(),
                "--admin-api-port",
                participant.adminPort.toString(),
                "--sequencer-public-port",
                sync.sequencer.publicPort.toString(),
                "--sequencer-admin-port",
                sync.sequencer.adminPort.toString(),
                "--mediator-admin-port",
                sync.mediator.adminPort.toString(),
                "--no-tty",
                "--bootstrap",
                script.toString(),
                "--log-level-stdout",
                "WARN"
            )
        ).withCharset(StandardCharsets.UTF_8)
        profile.generatedPath.takeIf { it.isNotBlank() }
            ?.let { SandboxPaths.generatedRoot(profile, DamlWorkspaceService.getInstance(project).projectRoot()).resolve("local") }
            ?.takeIf(Files::isDirectory)
            ?.let { command.withWorkDirectory(it.toFile()) }
        RuntimeEnvironment.applyLocalTools(command, settings)
        return command
    }

    private fun cantonBaseCommand(settings: DamlProjectSettings): List<String> {
        val override = settings.cantonBinaryPath.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        return when {
            override != null && override.toString().endsWith(".jar") -> listOf(javaExecutable(), "-jar", override.toString())
            override != null -> listOf(override.toString())
            RuntimeEnvironment.findExecutable("canton", settings) != null -> listOf(RuntimeEnvironment.findExecutable("canton", settings)!!.toString())
            else -> listOf(javaExecutable(), "-jar", locateCantonJar(settings).toString())
        }
    }

    private fun locateCantonJar(settings: DamlProjectSettings): Path =
        CantonJarLocator.find(settings)
            ?: throw ExecutionException("canton.jar not found. Set the Canton binary path to canton.jar, or set CANTON_JAR/CANTON_SDK_VERSION.")

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        val binary = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        val candidate = javaHome?.let { Path.of(it, "bin", binary) }
        return candidate?.takeIf(Files::isExecutable)?.toString() ?: "java"
    }

    private fun canConnect(port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_500)
            }
            true
        }.getOrDefault(false)

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun stripAnsi(text: String): String =
        text.replace(Regex("""\u001B\[[;\d]*[ -/]*[@-~]"""), "")

    private fun extractDiagnosticOutput(raw: String): String {
        val started = raw.substringAfter(SyncDiagnosticCatalog.START_MARKER, raw)
        val bounded = started.substringBefore(SyncDiagnosticCatalog.END_MARKER, started)
        return bounded
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                line.startsWith("Compiling ") ||
                    line.startsWith("WARNING:") ||
                    line.startsWith("Use --enable-native-access") ||
                    line.startsWith("Restricted methods will be blocked")
            }
            .joinToString("\n")
            .trim()
            .ifBlank { raw.takeLast(4_000) }
    }

    private fun diagnosticJson(
        profile: SandboxProfile,
        sync: SynchronizerNode,
        preset: SyncDiagnosticPreset,
        participant: ParticipantNode?,
        exitCode: Int,
        durationMillis: Long,
        rawOutput: String? = null,
        endpoints: List<JsonObject> = emptyList()
    ): String =
        gson.toJson(JsonObject().apply {
            addProperty("ok", exitCode == 0)
            addProperty("exitCode", exitCode)
            addProperty("durationMillis", durationMillis)
            addProperty("profile", profile.name)
            addProperty("synchronizer", sync.name)
            addProperty("diagnostic", preset.name)
            addProperty("transport", if (preset.kind == SyncDiagnosticKind.TCP) "tcp" else "canton-admin-grpc")
            add("endpoints", JsonObject().apply {
                addProperty("sequencerPublic", "grpc://127.0.0.1:${sync.sequencer.publicPort}")
                addProperty("sequencerAdmin", "grpc://127.0.0.1:${sync.sequencer.adminPort}")
                addProperty("mediatorAdmin", "grpc://127.0.0.1:${sync.mediator.adminPort}")
            })
            participant?.let {
                add("participantProbe", JsonObject().apply {
                    addProperty("name", it.name)
                    addProperty("ledgerApi", "grpc://127.0.0.1:${it.ledgerPort}")
                    addProperty("adminApi", "grpc://127.0.0.1:${it.adminPort}")
                    addProperty("jsonApi", "http://127.0.0.1:${it.jsonPort}")
                })
            }
            if (endpoints.isNotEmpty()) {
                add("checks", JsonArray().apply { endpoints.forEach(::add) })
            }
            rawOutput?.let { addProperty("raw", it) }
        })
}

internal class SyncDomainEndpointConsole(
    private val project: Project,
    private val sessions: SandboxSessionService,
    private val diagnosticRunner: (SandboxProfile, SynchronizerNode, SyncDiagnosticPreset) -> SyncDiagnosticResponse = { profile, sync, preset ->
        sessions.runSynchronizerDiagnostic(profile, sync, preset)
    },
    private val backgroundExecutor: ((() -> Unit) -> Unit) = { action ->
        ApplicationManager.getApplication().executeOnPooledThread(action)
    }
) : JPanel(BorderLayout(8, 8)) {
    private val presetModel = DefaultListModel<SyncDiagnosticPreset>()
    private val presetList = JBList(presetModel)
    private val syncTitle = JBLabel("Select a sync domain")
    private val endpointDetails = JBLabel("Sequencer and mediator endpoints will appear here.")
    private val resultMeta = JBLabel("No diagnostic run")
    private val resultArea = syncConsoleArea()
    private val sendButton = SyncDiagnosticButton("Run", AllIcons.Actions.Execute) { runSelected() }
    private var profile: SandboxProfile? = null
    private var session: SandboxSessionState = SandboxSessionState()
    private var sync: SynchronizerNode? = null
    private var selectedPreset: SyncDiagnosticPreset? = null

    init {
        name = "SyncDomainEndpointConsole"
        background = TopologyGraphTheme.canvas
        border = BorderFactory.createEmptyBorder()
        setupPresetList()
        add(header(), BorderLayout.NORTH)
        add(body(), BorderLayout.CENTER)
        SyncDiagnosticCatalog.builtInPresets().forEach(presetModel::addElement)
        presetList.selectedIndex = 0
        selectedPreset = presetModel.getElementAt(0)
        updateEnabledState()
    }

    fun setContext(profile: SandboxProfile, session: SandboxSessionState, syncId: String?) {
        this.profile = profile
        this.session = session
        sync = syncId?.let(profile::synchronizer)
        renderSync()
        updateEnabledState()
    }

    fun selectedSyncNameForTest(): String? = sync?.name

    fun selectPresetForTest(id: String) {
        val index = (0 until presetModel.size()).firstOrNull { presetModel.getElementAt(it).id == id } ?: return
        presetList.selectedIndex = index
    }

    fun runSelectedForTest() {
        runSelected()
    }

    private fun setupPresetList() {
        presetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        presetList.cellRenderer = SyncDiagnosticPresetRenderer()
        presetList.background = TopologyGraphTheme.panel
        presetList.foreground = TopologyGraphTheme.text
        presetList.fixedCellHeight = -1
        presetList.addListSelectionListener {
            if (!it.valueIsAdjusting) selectedPreset = presetList.selectedValue
        }
    }

    private fun header(): JComponent =
        syncCard(BorderLayout(8, 4)).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(syncTitle.apply {
                    foreground = TopologyGraphTheme.syncBorder
                    font = font.deriveFont(Font.BOLD, 15f)
                }, BorderLayout.WEST)
                add(sendButton, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(endpointDetails.apply {
                foreground = TopologyGraphTheme.detail
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            }, BorderLayout.CENTER)
        }

    private fun body(): JComponent =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, collection(), response()).apply {
            resizeWeight = 0.0
            dividerLocation = 250
            dividerSize = 8
            border = BorderFactory.createEmptyBorder()
            background = TopologyGraphTheme.canvas
            ui = SyncDiagnosticSplitPaneUI()
        }

    private fun collection(): JComponent =
        syncCard(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(250, 100)
            add(sectionTitle("Synchronizer Diagnostics"), BorderLayout.NORTH)
            add(syncScroll(presetList), BorderLayout.CENTER)
        }

    private fun response(): JComponent =
        syncCard(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(sectionTitle("Result"), BorderLayout.WEST)
                add(resultMeta.apply {
                    foreground = TopologyGraphTheme.detail
                    font = font.deriveFont(Font.BOLD, 12f)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(syncScroll(resultArea), BorderLayout.CENTER)
        }

    private fun renderSync() {
        val current = sync
        if (current == null) {
            syncTitle.text = "Select a sync domain"
            syncTitle.foreground = TopologyGraphTheme.detail
            endpointDetails.text = "Sequencer and mediator endpoints will appear here."
            resultArea.text = "Select a sync domain row to query its sequencer and mediator endpoints."
            return
        }
        val color = networkSyncColor(current.name)
        syncTitle.text = "${TopologyNodeIcons.SYNCHRONIZER}  ${current.name}"
        syncTitle.foreground = color
        endpointDetails.text =
            "sequencer public grpc://127.0.0.1:${current.sequencer.publicPort}    sequencer admin grpc://127.0.0.1:${current.sequencer.adminPort}    mediator admin grpc://127.0.0.1:${current.mediator.adminPort}"
    }

    private fun updateEnabledState() {
        val canRun = session.status == SandboxSessionStatus.RUNNING && sync != null
        sendButton.isEnabled = canRun
        sendButton.toolTipText = if (canRun) "Run diagnostic" else "Start sandbox and select a sync domain"
    }

    private fun runSelected() {
        val currentProfile = profile ?: return
        val currentSync = sync ?: return
        val preset = selectedPreset ?: return
        if (session.status != SandboxSessionStatus.RUNNING) {
            showResult("Start sandbox to query synchronizer endpoints.", error = true)
            return
        }
        resultMeta.text = "Running ${preset.name}..."
        resultMeta.foreground = TopologyGraphTheme.warning
        resultArea.text = ""
        sendButton.isEnabled = false
        backgroundExecutor {
            val response = runCatching { diagnosticRunner(currentProfile, currentSync, preset) }
            SwingUtilities.invokeLater {
                updateEnabledState()
                response.fold(
                    onSuccess = { renderResponse(it) },
                    onFailure = { showResult("Diagnostic failed: ${it.message ?: it::class.java.simpleName}", error = true) }
                )
            }
        }
    }

    private fun renderResponse(response: SyncDiagnosticResponse) {
        resultMeta.text = "exit ${response.exitCode} • ${response.durationMillis} ms"
        resultMeta.foreground = if (response.ok) TopologyGraphTheme.syncBorder else Color(0xFF5C7A)
        resultArea.text = response.output
        resultArea.caretPosition = 0
    }

    private fun showResult(message: String, error: Boolean) {
        resultMeta.text = if (error) "Blocked" else "Ready"
        resultMeta.foreground = if (error) Color(0xFF5C7A) else TopologyGraphTheme.detail
        resultArea.text = message
        resultArea.caretPosition = 0
    }

    private fun syncCard(layout: BorderLayout): JPanel =
        JPanel(layout).apply {
            background = TopologyGraphTheme.panel
            foreground = TopologyGraphTheme.text
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder),
                JBUI.Borders.empty(10)
            )
        }

    private fun sectionTitle(text: String): JLabel =
        JLabel(text).apply {
            foreground = TopologyGraphTheme.text
            font = font.deriveFont(Font.BOLD, 13f)
        }
}

private fun syncConsoleArea(): JBTextArea =
    JBTextArea().apply {
        isEditable = false
        lineWrap = false
        wrapStyleWord = false
        background = Color(0x07101D)
        foreground = TopologyGraphTheme.text
        caretColor = TopologyGraphTheme.hover
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(8)
    }

private fun syncScroll(component: JComponent): JBScrollPane =
    JBScrollPane(component).apply {
        border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
        background = TopologyGraphTheme.panel
        viewport.background = TopologyGraphTheme.panel
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.ui = SyncDiagnosticScrollBarUI()
        horizontalScrollBar.ui = SyncDiagnosticScrollBarUI()
    }

private class SyncDiagnosticPresetRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val preset = value as? SyncDiagnosticPreset
        return JPanel(BorderLayout(6, 2)).apply {
            background = if (isSelected) networkAlpha(TopologyGraphTheme.selected, 95) else TopologyGraphTheme.panel
            border = JBUI.Borders.empty(7, 8)
            add(JLabel(preset?.name ?: value.toString()).apply {
                foreground = if (preset?.kind == SyncDiagnosticKind.TCP) TopologyGraphTheme.participantBorder else TopologyGraphTheme.syncBorder
                font = font.deriveFont(Font.BOLD, 12f)
            }, BorderLayout.NORTH)
            add(JLabel(preset?.description.orEmpty()).apply {
                foreground = TopologyGraphTheme.detail
                font = font.deriveFont(Font.PLAIN, 11f)
            }, BorderLayout.CENTER)
        }
    }
}

private class SyncDiagnosticButton(text: String, icon: javax.swing.Icon?, action: () -> Unit) : JButton(text, icon) {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isFocusPainted = false
        foreground = TopologyGraphTheme.text
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = BorderFactory.createCompoundBorder(
            SyncDiagnosticRoundBorder(TopologyGraphTheme.syncBorder, 12),
            JBUI.Borders.empty(5, 12)
        )
        addActionListener { action() }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = when {
            !isEnabled -> Color(0x101A26)
            model.isPressed -> networkAlpha(TopologyGraphTheme.selected, 70)
            model.isRollover -> networkAlpha(TopologyGraphTheme.hover, 34)
            else -> TopologyGraphTheme.panel
        }
        g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
        g2.dispose()
        super.paintComponent(g)
    }
}

private class SyncDiagnosticRoundBorder(private val color: Color, private val radius: Int) : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
}

private class SyncDiagnosticScrollBarUI : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        thumbColor = networkAlpha(TopologyGraphTheme.edge, 130)
        trackColor = TopologyGraphTheme.canvas
    }

    override fun createDecreaseButton(orientation: Int): JButton = zeroButton()
    override fun createIncreaseButton(orientation: Int): JButton = zeroButton()

    private fun zeroButton(): JButton =
        JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
            border = BorderFactory.createEmptyBorder()
        }
}

private class SyncDiagnosticSplitPaneUI : javax.swing.plaf.basic.BasicSplitPaneUI() {
    override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider =
        object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
            init {
                border = BorderFactory.createEmptyBorder()
                background = TopologyGraphTheme.canvas
            }

            override fun paint(g: Graphics) {
                g.color = TopologyGraphTheme.canvas
                g.fillRect(0, 0, width, height)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = networkAlpha(TopologyGraphTheme.edge, 110)
                if (orientation == JSplitPane.HORIZONTAL_SPLIT) {
                    val x = width / 2 - 1
                    g2.fillRoundRect(x, 8, 2, height - 16, 2, 2)
                } else {
                    val y = height / 2 - 1
                    g2.fillRoundRect(8, y, width - 16, 2, 2, 2)
                }
                g2.dispose()
            }
        }
}
