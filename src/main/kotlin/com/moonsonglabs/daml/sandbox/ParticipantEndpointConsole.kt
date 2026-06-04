package com.moonsonglabs.daml.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
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
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.AbstractBorder
import javax.swing.plaf.basic.BasicScrollBarUI

internal enum class SandboxEndpointRisk {
    READ,
    WRITE
}

internal data class SandboxEndpointPreset(
    val id: String,
    val group: String,
    val name: String,
    val method: String,
    val path: String,
    val description: String,
    val risk: SandboxEndpointRisk = SandboxEndpointRisk.READ
) {
    val isMutating: Boolean get() = risk == SandboxEndpointRisk.WRITE
}

internal object SandboxEndpointCatalog {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()

    fun builtInPresets(): List<SandboxEndpointPreset> = listOf(
        SandboxEndpointPreset("livez", "Health", "Live check", "GET", "/livez", "Check whether the JSON API process is alive."),
        SandboxEndpointPreset("readyz", "Health", "Ready check", "GET", "/readyz", "Check whether the participant ledger API is serving."),
        SandboxEndpointPreset("openapi", "Docs", "OpenAPI spec", "GET", "/docs/openapi", "Download the participant-specific OpenAPI document."),
        SandboxEndpointPreset("packages", "Packages", "List packages", "GET", "/v2/packages", "List packages uploaded to the participant."),
        SandboxEndpointPreset("parties", "Parties", "List parties", "GET", "/v2/parties", "List known parties visible to the participant."),
        SandboxEndpointPreset("create-party", "Parties", "Allocate party", "POST", "/v2/parties", "Allocate a local party on the participant.", SandboxEndpointRisk.WRITE),
        SandboxEndpointPreset("user", "Users", "Get participant_admin user", "GET", "/v2/users/participant_admin", "Read participant_admin user details."),
        SandboxEndpointPreset("user-rights", "Users", "List participant_admin rights", "GET", "/v2/users/participant_admin/rights", "List participant_admin rights."),
        SandboxEndpointPreset("ledger-end", "Ledger State", "Ledger end", "GET", "/v2/state/ledger-end", "Read the latest ledger offset."),
        SandboxEndpointPreset("active-contracts", "Ledger State", "Active contracts", "POST", "/v2/state/active-contracts", "Read active contracts for selected parties.", SandboxEndpointRisk.READ),
        SandboxEndpointPreset("updates", "Updates", "Updates", "POST", "/v2/updates?limit=200", "Read transaction events and ACS deltas.", SandboxEndpointRisk.READ),
        SandboxEndpointPreset("submit-wait", "Commands", "Submit and wait", "POST", "/v2/commands/submit-and-wait", "Submit commands and wait for completion.", SandboxEndpointRisk.WRITE),
        SandboxEndpointPreset("submit-transaction", "Commands", "Submit and wait for transaction", "POST", "/v2/commands/submit-and-wait-for-transaction", "Submit commands and wait for transaction details.", SandboxEndpointRisk.WRITE),
        SandboxEndpointPreset("async-submit", "Commands", "Async submit", "POST", "/v2/commands/async/submit", "Submit commands asynchronously.", SandboxEndpointRisk.WRITE)
    )

    fun enrichFromOpenApi(openApi: String, presets: List<SandboxEndpointPreset> = builtInPresets()): List<SandboxEndpointPreset> {
        val descriptions = openApiDescriptions(openApi)
        if (descriptions.isEmpty()) return presets
        return presets.map { preset ->
            descriptions[key(preset.method, preset.path.substringBefore("?"))]
                ?.takeIf { it.isNotBlank() }
                ?.let { preset.copy(description = it) }
                ?: preset
        }
    }

    fun requestBody(
        preset: SandboxEndpointPreset,
        profile: SandboxProfile,
        participant: ParticipantNode,
        ledgerEnd: Long = 0L,
        projectRoot: Path? = null
    ): String =
        when (preset.id) {
            "active-contracts" -> gson.toJson(JsonObject().apply {
                addProperty("activeAtOffset", ledgerEnd)
                add("eventFormat", eventFormat(profile, participant, projectRoot))
            })
            "updates" -> gson.toJson(JsonObject().apply {
                addProperty("beginExclusive", 0)
                addProperty("endInclusive", ledgerEnd)
                add("updateFormat", JsonObject().apply {
                    add("includeTransactions", JsonObject().apply {
                        addProperty("transactionShape", "TRANSACTION_SHAPE_ACS_DELTA")
                        add("eventFormat", eventFormat(profile, participant, projectRoot))
                    })
                })
            })
            "create-party" -> gson.toJson(JsonObject().apply {
                addProperty("partyIdHint", "${participant.name}Party")
                addProperty("identityProviderId", "")
                add("localMetadata", JsonNull.INSTANCE)
                addProperty("userId", "participant_admin")
                if (profile.connectedSynchronizers(participant.id).size > 1) {
                    addProperty("synchronizerId", "<full-synchronizer-id>")
                }
            })
            "submit-wait", "async-submit" -> gson.toJson(commandEnvelope(preset, profile, participant, projectRoot))
            "submit-transaction" -> gson.toJson(JsonObject().apply {
                add("commands", commandEnvelope(preset, profile, participant, projectRoot))
            })
            else -> ""
        }

    fun isMutating(method: String, path: String): Boolean {
        val normalizedMethod = method.uppercase()
        if (normalizedMethod in setOf("PUT", "PATCH", "DELETE")) return true
        if (normalizedMethod != "POST") return false
        return path.startsWith("/v2/commands") || path == "/v2/parties" || path.contains("/rights")
    }

    fun prettyBody(body: String): String =
        runCatching { gson.toJson(JsonParser.parseString(body)) }
            .getOrElse { body }

    fun parties(profile: SandboxProfile, participant: ParticipantNode, projectRoot: Path? = null): List<String> =
        allocatedPartyIds(profile, participant, projectRoot)
            .ifEmpty {
                profile.partyAllocations
                    .filter { it.participantId == participant.id }
                    .map { it.partyHint }
                    .distinct()
            }
            .ifEmpty { listOf("<party-id>") }

    private fun allocatedPartyIds(profile: SandboxProfile, participant: ParticipantNode, projectRoot: Path?): List<String> {
        val root = generatedRoot(profile, projectRoot)
        val path = root.resolve("participants.json")
        if (!Files.isRegularFile(path)) return emptyList()
        val hints = profile.partyAllocations
            .filter { it.participantId == participant.id }
            .map { it.partyHint }
            .distinct()
        return runCatching {
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            json.getAsJsonObject("party_participants")
                ?.entrySet()
                ?.asSequence()
                ?.filter { it.value.asString == participant.id || it.value.asString == participant.name }
                ?.map { it.key }
                ?.filter { party -> hints.isEmpty() || hints.any { hint -> party == hint || party.startsWith("$hint::") } }
                ?.sorted()
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun generatedRoot(profile: SandboxProfile, projectRoot: Path?): Path =
        SandboxPaths.generatedRoot(profile, projectRoot)

    private fun commandEnvelope(
        preset: SandboxEndpointPreset,
        profile: SandboxProfile,
        participant: ParticipantNode,
        projectRoot: Path?
    ): JsonObject {
        val parties = parties(profile, participant, projectRoot)
        val actAs = JsonArray().apply { add(parties.first()) }
        val readAs = JsonArray().apply { parties.drop(1).forEach(::add) }
        return JsonObject().apply {
            addProperty("userId", "participant_admin")
            addProperty("commandId", "cmd-${participant.name}-${preset.id}")
            addProperty("workflowId", "managed-sandbox-console")
            add("actAs", actAs)
            add("readAs", readAs)
            add("commands", JsonArray().apply {
                add(JsonObject().apply {
                    add("CreateCommand", JsonObject().apply {
                        addProperty("templateId", "<package-id>:Module:Template")
                        add("createArguments", JsonObject())
                    })
                })
            })
        }
    }

    private fun eventFormat(profile: SandboxProfile, participant: ParticipantNode, projectRoot: Path?): JsonObject =
        JsonObject().apply {
            add("filtersByParty", JsonObject().apply {
                parties(profile, participant, projectRoot).forEach { party ->
                    add(party, JsonObject().apply { add("cumulative", JsonArray()) })
                }
            })
            addProperty("verbose", true)
        }

    private fun openApiDescriptions(openApi: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var currentPath: String? = null
        var currentMethod: String? = null
        openApi.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val path = Regex("""^['"]?(/[^:'"]+)['"]?:\s*$""").matchEntire(trimmed)?.groupValues?.get(1)
            if (path != null) {
                currentPath = path
                currentMethod = null
                return@forEach
            }
            val method = Regex("""^(get|post|put|patch|delete):\s*$""", RegexOption.IGNORE_CASE)
                .matchEntire(trimmed)
                ?.groupValues
                ?.get(1)
                ?.uppercase()
            if (method != null && currentPath != null) {
                currentMethod = method
                return@forEach
            }
            if (currentPath != null && currentMethod != null && trimmed.startsWith("description:")) {
                result[key(currentMethod!!, currentPath!!)] = trimmed
                    .removePrefix("description:")
                    .trim()
                    .trim('"')
                    .trim('\'')
                currentMethod = null
            }
        }
        return result
    }

    private fun key(method: String, path: String): String = "${method.uppercase()} ${path.substringBefore("?")}"
}

internal fun endpointMethodColor(method: String): Color =
    when (method.uppercase()) {
        "GET" -> TopologyGraphTheme.participantBorder
        "POST" -> TopologyGraphTheme.warning
        "PUT", "PATCH", "DELETE" -> Color(0xFF5C7A)
        else -> TopologyGraphTheme.detail
    }

internal fun endpointRiskColor(risk: SandboxEndpointRisk): Color =
    if (risk == SandboxEndpointRisk.WRITE) TopologyGraphTheme.warning else TopologyGraphTheme.syncBorder

internal class ParticipantEndpointConsole(
    private val project: Project,
    private val sessions: SandboxSessionService,
    private val confirmWrite: (SandboxEndpointPreset) -> Boolean = { preset ->
        Messages.showYesNoDialog(
            project,
            "Send mutating request '${preset.name}' to the selected participant?",
            "Confirm Ledger Write",
            "Send",
            "Cancel",
            AllIcons.General.Warning
        ) == Messages.YES
    },
    private val requestSender: (Endpoint, String, String, String?, String?) -> SandboxHttpResponse = { endpoint, method, path, token, body ->
        sessions.runJsonRequest(endpoint, method, path, token, body)
    },
    private val backgroundExecutor: ((() -> Unit) -> Unit) = { action ->
        ApplicationManager.getApplication().executeOnPooledThread(action)
    }
) : JPanel(BorderLayout(8, 8)) {
    private val presetModel = DefaultListModel<SandboxEndpointPreset>()
    private val presetList = JBList(presetModel)
    private val methodField = JBTextField("GET")
    private val pathField = JBTextField()
    private val tokenField = JBTextField()
    private val headersArea = consoleArea(rows = 4).apply { isEditable = false }
    private val bodyArea = consoleArea(rows = 10)
    private val responseArea = consoleArea(rows = 14).apply { isEditable = false }
    private val responseMeta = JBLabel("No request sent")
    private val participantTitle = JBLabel("No participant selected")
    private val endpointDetails = JBLabel("Ledger/API endpoints will appear here.")
    private val metadataLabel = JBLabel("Endpoint metadata: built-in")
    private val sendButton = consoleButton("Send", AllIcons.Actions.Execute) { sendSelected() }
    private val copyButton = consoleButton("Copy", AllIcons.Actions.Copy) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(responseArea.text), null)
    }
    private var profile: SandboxProfile? = null
    private var session: SandboxSessionState = SandboxSessionState()
    private var participant: ParticipantNode? = null
    private var selectedPreset: SandboxEndpointPreset? = null
    private var lastLedgerEnd: Long = 0
    private var openApiKey: String? = null
    private var openApiInFlightKey: String? = null
    private val writeConfirmations = mutableSetOf<String>()

    init {
        name = "ParticipantEndpointConsole"
        background = TopologyGraphTheme.canvas
        border = BorderFactory.createEmptyBorder()
        setupPresetList()
        add(endpointHeader(), BorderLayout.NORTH)
        add(consoleBody(), BorderLayout.CENTER)
        SandboxEndpointCatalog.builtInPresets().forEach(presetModel::addElement)
        presetList.selectedIndex = 0
        selectedPreset = presetModel.getElementAt(0)
        updateRequestFromPreset()
        updateEnabledState()
    }

    fun setContext(profile: SandboxProfile, session: SandboxSessionState, participantId: String?) {
        val previousKey = "${this.profile?.id}:${this.session.status}:${participant?.id}"
        this.profile = profile
        this.session = session
        participant = participantId
            ?.let(profile::participant)
            ?: participant?.id?.let(profile::participant)
            ?: profile.participants.firstOrNull()
        val nextKey = "${profile.id}:${session.status}:${participant?.id}"
        if (previousKey != nextKey) writeConfirmations.clear()
        renderParticipant()
        updateRequestFromPreset()
        updateEnabledState()
        fetchOpenApiIfAvailable()
    }

    fun selectedParticipantNameForTest(): String? = participant?.name

    fun selectPresetForTest(id: String) {
        val index = (0 until presetModel.size()).firstOrNull { presetModel.getElementAt(it).id == id } ?: return
        presetList.selectedIndex = index
    }

    fun sendSelectedForTest() {
        sendSelected()
    }

    private fun setupPresetList() {
        presetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        presetList.cellRenderer = EndpointPresetRenderer()
        presetList.background = TopologyGraphTheme.panel
        presetList.foreground = TopologyGraphTheme.text
        presetList.fixedCellHeight = -1
        presetList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedPreset = presetList.selectedValue
                updateRequestFromPreset()
            }
        }
    }

    private fun endpointHeader(): JComponent =
        consoleCard(BorderLayout(8, 4)).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(participantTitle.apply {
                    foreground = TopologyGraphTheme.participantBorder
                    font = font.deriveFont(Font.BOLD, 15f)
                }, BorderLayout.WEST)
                add(metadataLabel.apply {
                    foreground = TopologyGraphTheme.detail
                    font = font.deriveFont(Font.PLAIN, 11f)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(endpointDetails.apply {
                foreground = TopologyGraphTheme.detail
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            }, BorderLayout.CENTER)
        }

    private fun consoleBody(): JComponent =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, endpointCollection(), requestResponse()).apply {
            resizeWeight = 0.0
            dividerLocation = 260
            dividerSize = 8
            border = BorderFactory.createEmptyBorder()
            background = TopologyGraphTheme.canvas
            ui = EndpointSplitPaneUI()
        }

    private fun endpointCollection(): JComponent =
        consoleCard(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(260, 100)
            add(sectionTitle("Endpoint Collection"), BorderLayout.NORTH)
            add(themedScroll(presetList), BorderLayout.CENTER)
        }

    private fun requestResponse(): JComponent =
        JSplitPane(JSplitPane.VERTICAL_SPLIT, requestEditor(), responseViewer()).apply {
            resizeWeight = 0.46
            dividerLocation = 260
            dividerSize = 8
            border = BorderFactory.createEmptyBorder()
            background = TopologyGraphTheme.canvas
            ui = EndpointSplitPaneUI()
        }

    private fun requestEditor(): JComponent =
        consoleCard(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(sectionTitle("Request"), BorderLayout.WEST)
                add(sendButton, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(GridBagLayout()).apply {
                background = TopologyGraphTheme.panel
                var y = 0
                addRow("Method", methodField.styledTextField().apply {
                    columns = 8
                    maximumSize = Dimension(120, 32)
                }, y++)
                addRow("Path", pathField.styledTextField(), y++)
                addRow("Token", tokenField.styledTextField().apply { toolTipText = "Optional Authorization: Bearer token" }, y++)
                addRow("Headers", themedScroll(headersArea), y++, weighty = 0.18)
                addRow("Body", themedScroll(bodyArea), y, weighty = 1.0)
            }, BorderLayout.CENTER)
        }

    private fun responseViewer(): JComponent =
        consoleCard(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(sectionTitle("Response"), BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    background = TopologyGraphTheme.panel
                    add(responseMeta.apply {
                        foreground = TopologyGraphTheme.detail
                        font = font.deriveFont(Font.BOLD, 12f)
                    })
                    add(copyButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(themedScroll(responseArea), BorderLayout.CENTER)
        }

    private fun renderParticipant() {
        val current = participant
        if (current == null) {
            participantTitle.text = "No participant selected"
            endpointDetails.text = "Ledger/API endpoints will appear here."
            metadataLabel.text = "Endpoint metadata: built-in"
            return
        }
        val health = session.health.firstOrNull { it.endpoint.nodeId == current.id && it.endpoint.kind == "json" }
        val status = when {
            health?.ready == true -> "ready"
            health?.live == true -> "live"
            session.status == SandboxSessionStatus.RUNNING -> "checking"
            else -> session.status.presentableName.lowercase()
        }
        participantTitle.text = "${TopologyNodeIcons.PARTICIPANT}  ${current.name}  •  $status"
        endpointDetails.text =
            "ledger grpc://127.0.0.1:${current.ledgerPort}    admin grpc://127.0.0.1:${current.adminPort}    json http://127.0.0.1:${current.jsonPort}"
    }

    private fun updateRequestFromPreset() {
        val preset = selectedPreset ?: return
        val currentProfile = profile
        val currentParticipant = participant
        methodField.text = preset.method
        pathField.text = preset.path
        bodyArea.text = if (currentProfile != null && currentParticipant != null) {
            SandboxEndpointCatalog.requestBody(
                preset,
                currentProfile,
                currentParticipant,
                lastLedgerEnd,
                DamlWorkspaceService.getInstance(project).projectRoot()
            )
        } else {
            ""
        }
        bodyArea.caretPosition = 0
        responseMeta.foreground = TopologyGraphTheme.detail
        updateHeadersPreview()
    }

    private fun updateHeadersPreview() {
        val method = methodField.text.trim().ifBlank { selectedPreset?.method ?: "GET" }
        headersArea.text = buildString {
            appendLine("Accept: application/json")
            if (!tokenField.text.isNullOrBlank()) appendLine("Authorization: Bearer ${tokenField.text.trim()}")
            if (method.uppercase() !in setOf("GET", "DELETE")) appendLine("Content-Type: application/json")
        }.trim()
    }

    private fun updateEnabledState() {
        val canSend = session.status == SandboxSessionStatus.RUNNING && participant != null
        sendButton.isEnabled = canSend
        sendButton.toolTipText = if (canSend) "Send request" else "Start sandbox to send requests"
    }

    private fun sendSelected() {
        val currentProfile = profile ?: return
        val currentParticipant = participant ?: return
        val endpoint = EndpointBuilder.participantEndpoints(currentProfile)
            .firstOrNull { it.nodeId == currentParticipant.id && it.kind == "json" }
            ?: return showResponse("No JSON API endpoint configured for ${currentParticipant.name}.", error = true)
        if (session.status != SandboxSessionStatus.RUNNING) {
            showResponse("Start sandbox to send requests.", error = true)
            return
        }
        val preset = selectedPreset ?: return
        val method = methodField.text.trim().ifBlank { preset.method }
        val path = pathField.text.trim()
        val mutating = SandboxEndpointCatalog.isMutating(method, path)
        val confirmationKey = "${currentProfile.id}:${currentParticipant.id}:${session.status}:$method:$path"
        if (mutating && confirmationKey !in writeConfirmations) {
            if (!confirmWrite(preset.copy(method = method, path = path, risk = SandboxEndpointRisk.WRITE))) return
            writeConfirmations += confirmationKey
        }

        responseMeta.text = "Sending $method $path..."
        responseMeta.foreground = TopologyGraphTheme.warning
        responseArea.text = ""
        sendButton.isEnabled = false
        val token = tokenField.text.trim().takeIf { it.isNotBlank() }
        val body = bodyArea.text.takeIf { method.uppercase() !in setOf("GET", "DELETE") }
        backgroundExecutor {
            val result = runCatching { requestSender(endpoint, method, path, token, body) }
            runOnEdt {
                updateEnabledState()
                result.fold(
                    onSuccess = { renderResponse(it) },
                    onFailure = { showResponse("Request failed: ${it.message ?: it::class.java.simpleName}", error = true) }
                )
            }
        }
    }

    private fun renderResponse(response: SandboxHttpResponse) {
        responseMeta.text = "HTTP ${response.status} • ${response.durationMillis} ms"
        responseMeta.foreground = if (response.status in 200..299) TopologyGraphTheme.syncBorder else Color(0xFF5C7A)
        responseArea.text = buildString {
            appendLine("Status: HTTP ${response.status}")
            appendLine("Duration: ${response.durationMillis} ms")
            appendLine()
            appendLine("Headers")
            appendLine(response.headers.entries.joinToString("\n") { (key, values) -> "$key: ${values.joinToString(", ")}" }.ifBlank { "-" })
            appendLine()
            appendLine("Body")
            appendLine(SandboxEndpointCatalog.prettyBody(response.body))
        }
        responseArea.caretPosition = 0
        if (pathField.text.trim() == "/v2/state/ledger-end" && response.status in 200..299) {
            lastLedgerEnd = runCatching {
                JsonParser.parseString(response.body).asJsonObject.get("offset").asLong
            }.getOrDefault(lastLedgerEnd)
        }
    }

    private fun showResponse(message: String, error: Boolean) {
        responseMeta.text = if (error) "Request blocked" else "Ready"
        responseMeta.foreground = if (error) Color(0xFF5C7A) else TopologyGraphTheme.detail
        responseArea.text = message
        responseArea.caretPosition = 0
    }

    private fun fetchOpenApiIfAvailable() {
        val currentProfile = profile ?: return
        val currentParticipant = participant ?: return
        if (session.status != SandboxSessionStatus.RUNNING) {
            metadataLabel.text = "Endpoint metadata: built-in"
            return
        }
        val endpoint = EndpointBuilder.participantEndpoints(currentProfile)
            .firstOrNull { it.nodeId == currentParticipant.id && it.kind == "json" }
            ?: return
        if (openApiKey == endpoint.url || openApiInFlightKey == endpoint.url) return
        openApiInFlightKey = endpoint.url
        metadataLabel.text = "Endpoint metadata: loading /docs/openapi"
        backgroundExecutor {
            val response = runCatching { requestSender(endpoint, "GET", "/docs/openapi", null, null) }
            runOnEdt {
                openApiInFlightKey = null
                response.onSuccess {
                    if (it.status in 200..299) {
                        replacePresets(SandboxEndpointCatalog.enrichFromOpenApi(it.body))
                        openApiKey = endpoint.url
                        metadataLabel.text = "Endpoint metadata: enriched from /docs/openapi"
                    } else {
                        metadataLabel.text = "Endpoint metadata: built-in (/docs/openapi HTTP ${it.status})"
                    }
                }.onFailure {
                    metadataLabel.text = "Endpoint metadata: built-in (${it.message ?: "OpenAPI unavailable"})"
                }
            }
        }
    }

    private fun replacePresets(presets: List<SandboxEndpointPreset>) {
        val selectedId = selectedPreset?.id
        presetModel.clear()
        presets.forEach(presetModel::addElement)
        val index = (0 until presetModel.size()).firstOrNull { presetModel.getElementAt(it).id == selectedId } ?: 0
        presetList.selectedIndex = index
    }

    private fun consoleCard(layout: BorderLayout): JPanel =
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

    private fun consoleButton(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): JButton =
        EndpointButton(text, icon).apply {
            toolTipText = text
            addActionListener { action() }
        }

    private fun themedScroll(component: JComponent): JBScrollPane =
        JBScrollPane(component).apply {
            border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
            viewport.background = TopologyGraphTheme.panel
            background = TopologyGraphTheme.panel
            horizontalScrollBar.styleEndpointScrollBar()
            verticalScrollBar.styleEndpointScrollBar()
        }

    private fun consoleArea(rows: Int): JBTextArea =
        JBTextArea(rows, 32).apply {
            foreground = TopologyGraphTheme.text
            background = TopologyGraphTheme.canvas
            caretColor = TopologyGraphTheme.hover
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = false
            border = JBUI.Borders.empty(8)
        }

    private fun JBTextField.styledTextField(): JBTextField =
        apply {
            foreground = TopologyGraphTheme.text
            background = TopologyGraphTheme.canvas
            caretColor = TopologyGraphTheme.hover
            border = BorderFactory.createCompoundBorder(
                EndpointRoundBorder(TopologyGraphTheme.panelBorder, 10),
                JBUI.Borders.empty(5, 9)
            )
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    updateHeadersPreview()
                }
            })
        }

    private fun JPanel.addRow(label: String, component: JComponent, y: Int, weighty: Double = 0.0) {
        add(JBLabel(label).apply {
            foreground = TopologyGraphTheme.detail
            font = font.deriveFont(Font.PLAIN, 12f)
        }, GridBagConstraints().apply {
            gridx = 0
            gridy = y
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(3, 0, 5, 8)
        })
        add(component, GridBagConstraints().apply {
            gridx = 1
            gridy = y
            fill = GridBagConstraints.BOTH
            weightx = 1.0
            this.weighty = weighty
            insets = Insets(3, 0, 5, 0)
        })
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }
}

private class EndpointPresetRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component =
        EndpointPresetCell(value as? SandboxEndpointPreset, isSelected).apply {
            toolTipText = (value as? SandboxEndpointPreset)?.description
        }
}

private class EndpointPresetCell(
    private val preset: SandboxEndpointPreset?,
    private val selected: Boolean
) : JComponent() {
    init {
        preferredSize = Dimension(230, 58)
        minimumSize = Dimension(180, 58)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (selected) endpointAlpha(TopologyGraphTheme.selected, 90) else TopologyGraphTheme.panel
            g2.fillRect(0, 0, width, height)
            g2.color = endpointRiskColor(preset?.risk ?: SandboxEndpointRisk.READ)
            g2.fillRoundRect(0, 0, 3, height, 3, 3)
            g2.color = TopologyGraphTheme.panelBorder
            g2.drawLine(0, height - 1, width, height - 1)

            val method = preset?.method.orEmpty()
            val methodColor = endpointMethodColor(method)
            g2.font = Font(Font.MONOSPACED, Font.BOLD, 10)
            val methodWidth = g2.fontMetrics.stringWidth(method).coerceAtLeast(28) + 12
            val pillX = 10
            val pillY = 9
            g2.color = endpointAlpha(methodColor, 32)
            g2.fillRoundRect(pillX, pillY, methodWidth, 20, 10, 10)
            g2.color = endpointAlpha(methodColor, 185)
            g2.drawRoundRect(pillX, pillY, methodWidth, 20, 10, 10)
            g2.color = methodColor
            g2.drawString(method, pillX + 6, pillY + 14)

            g2.font = font.deriveFont(if (selected) Font.BOLD else Font.PLAIN, 12f)
            g2.color = TopologyGraphTheme.text
            val nameX = pillX + methodWidth + 8
            val group = preset?.group.orEmpty()
            val groupWidth = g2.fontMetrics.stringWidth(group)
            val nameMax = (width - nameX - groupWidth - 18).coerceAtLeast(20)
            g2.drawString(ellipsize(preset?.name.orEmpty(), g2.fontMetrics, nameMax), nameX, 24)

            g2.font = font.deriveFont(Font.PLAIN, 10f)
            g2.color = TopologyGraphTheme.detail
            g2.drawString(group, (width - groupWidth - 10).coerceAtLeast(nameX), 24)

            g2.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            g2.color = TopologyGraphTheme.detail
            g2.drawString(ellipsize(preset?.path.orEmpty(), g2.fontMetrics, width - 20), 10, 45)
        } finally {
            g2.dispose()
        }
    }

    private fun ellipsize(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        var candidate = text
        while (candidate.length > 4 && metrics.stringWidth(candidate + "...") > maxWidth) {
            candidate = candidate.dropLast(1)
        }
        return candidate + "..."
    }
}

private class EndpointButton(text: String, icon: javax.swing.Icon?) : JButton(text, icon) {
    init {
        foreground = TopologyGraphTheme.text
        background = TopologyGraphTheme.panel
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(5, 11)
        margin = Insets(0, 0, 0, 0)
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width.coerceAtLeast(34), 34)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when {
                !isEnabled -> endpointAlpha(TopologyGraphTheme.panelBorder, 45)
                model.isPressed -> endpointAlpha(TopologyGraphTheme.selected, 70)
                model.isRollover -> endpointAlpha(TopologyGraphTheme.hover, 34)
                else -> TopologyGraphTheme.panel
            }
            g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g2.color = if (model.isRollover && isEnabled) TopologyGraphTheme.hover else TopologyGraphTheme.panelBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class EndpointRoundBorder(
    private val color: Color,
    private val radius: Int
) : AbstractBorder() {
    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)

    override fun getBorderInsets(c: Component, insets: Insets): Insets {
        insets.set(1, 1, 1, 1)
        return insets
    }

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        } finally {
            g2.dispose()
        }
    }
}

private class EndpointSplitPaneUI : javax.swing.plaf.basic.BasicSplitPaneUI() {
    override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider =
        object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
            init {
                border = BorderFactory.createEmptyBorder()
                background = TopologyGraphTheme.canvas
            }

            override fun getPreferredSize(): Dimension = Dimension(8, 8)

            override fun paint(g: Graphics) {
                g.color = TopologyGraphTheme.canvas
                g.fillRect(0, 0, width, height)
                g.color = endpointAlpha(TopologyGraphTheme.participantBorder, 100)
                if (height >= width) {
                    g.fillRoundRect(width / 2 - 1, 8, 2, height - 16, 4, 4)
                } else {
                    g.fillRoundRect(8, height / 2 - 1, width - 16, 2, 4, 4)
                }
            }
        }
}

private fun JScrollBar.styleEndpointScrollBar() {
    unitIncrement = 18
    preferredSize = Dimension(10, 10)
    setUI(object : BasicScrollBarUI() {
        override fun configureScrollBarColors() {
            thumbColor = endpointAlpha(TopologyGraphTheme.participantBorder, 135)
            trackColor = TopologyGraphTheme.canvas
        }

        override fun createDecreaseButton(orientation: Int): JButton = zeroButton()
        override fun createIncreaseButton(orientation: Int): JButton = zeroButton()

        private fun zeroButton(): JButton =
            JButton().apply {
                preferredSize = Dimension(0, 0)
                minimumSize = Dimension(0, 0)
                maximumSize = Dimension(0, 0)
            }
    })
}

private fun endpointAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
