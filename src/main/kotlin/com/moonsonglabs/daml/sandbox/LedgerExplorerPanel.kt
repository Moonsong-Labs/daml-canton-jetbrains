package com.moonsonglabs.daml.sandbox

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import javax.swing.JSplitPane
import javax.swing.JCheckBox
import javax.swing.JToggleButton
import javax.swing.ButtonGroup
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
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
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.AbstractBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import kotlin.math.max
import kotlin.math.min

internal object ExplorerTheme {
    val shell get() = UIUtil.getPanelBackground()
    val appBar get() = shell
    val card get() = shell
    val cardAlt get() = UIUtil.getTableBackground()
    val cardSoft get() = UIUtil.getTableSelectionBackground(true)
    val tableRow get() = UIUtil.getTableBackground()
    val tableRowAlt get() = tableRow
    val border get() = JBColor.border()
    val borderSoft get() = border
    val glowBlue get() = activity
    val text get() = UIUtil.getLabelForeground()
    val mutedText get() = UIUtil.getContextHelpForeground()
    val faintText get() = mutedText
    val participant = JBColor(Color(0x007F99), Color(0x50CEE3))
    val privateSync = JBColor(Color(0x7450AC), Color(0xBB9BE8))
    val globalSync = JBColor(Color(0x356DB1), Color(0x8BADDC))
    val active = JBColor(Color(0x238344), Color(0x78BD8E))
    val created get() = participant
    val archived get() = mutedText
    val activity = JBColor(Color(0x3574D4), Color(0x6C9CEB))
    val warning = JBColor(Color(0x916300), Color(0xE9BD64))
    val error = JBColor(Color(0xBB3547), Color(0xEF8290))
}

internal data class ExplorerActivityRow(
    val kind: String,
    val templateId: String,
    val templateName: String,
    val contractId: String,
    val offset: Long?,
    val offsetText: String,
    val synchronizerId: String,
    val syncName: String,
    val packageName: String,
    val parties: List<String>,
    val argumentFields: Map<String, String>,
    val rawJson: String,
    val sourceSynchronizerId: String = "",
    val targetSynchronizerId: String = ""
)

internal data class ExplorerFilterState(
    val query: String = "",
    val syncDomains: Set<String> = emptySet(),
    val parties: Set<String> = emptySet(),
    val kinds: Set<String> = setOf(LedgerActivityKind.ACTIVE, LedgerActivityKind.CREATED, LedgerActivityKind.ARCHIVED)
)

internal object LedgerExplorerRows {
    fun from(snapshot: LedgerExplorerSnapshot): List<ExplorerActivityRow> {
        val active = snapshot.activeContracts.map {
            ExplorerActivityRow(
                kind = LedgerActivityKind.ACTIVE,
                templateId = it.templateId,
                templateName = it.templateName,
                contractId = it.contractId,
                offset = it.offset.toLongOrNull(),
                offsetText = it.offset,
                synchronizerId = it.synchronizerId,
                syncName = shortSynchronizer(it.synchronizerId),
                packageName = it.packageName,
                parties = (it.signatories + it.observers + it.witnessParties).distinct().sorted(),
                argumentFields = it.createArgument,
                rawJson = it.rawJson
            )
        }
        val eventRows = (snapshot.events + snapshot.inFlightContracts).map {
            ExplorerActivityRow(
                kind = it.kind,
                templateId = it.templateId,
                templateName = it.templateName,
                contractId = it.contractId,
                offset = it.offset.toLongOrNull(),
                offsetText = it.offset,
                synchronizerId = it.synchronizerId,
                syncName = shortSynchronizer(it.synchronizerId),
                packageName = it.packageName,
                parties = it.witnessParties.distinct().sorted(),
                argumentFields = it.createArgument,
                rawJson = it.rawJson,
                sourceSynchronizerId = it.sourceSynchronizerId,
                targetSynchronizerId = it.targetSynchronizerId
            )
        }
        return (active + eventRows)
            .sortedWith(compareByDescending<ExplorerActivityRow> { it.offset ?: Long.MIN_VALUE }.thenBy { it.kind })
    }

    fun filter(rows: List<ExplorerActivityRow>, state: ExplorerFilterState): List<ExplorerActivityRow> =
        rows.filter { row ->
            row.kind in state.kinds &&
                (state.syncDomains.isEmpty() || row.syncName in state.syncDomains) &&
                (state.parties.isEmpty() || row.parties.any { it in state.parties || shortParty(it) in state.parties }) &&
                matches(row, state.query)
        }

    fun matches(row: ExplorerActivityRow, query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return true
        val haystack = buildList {
            add(row.kind)
            add(row.templateId)
            add(row.templateName)
            add(row.contractId)
            add(row.synchronizerId)
            add(row.syncName)
            add(row.packageName)
            addAll(row.parties)
            row.argumentFields.forEach { (key, value) ->
                add(key)
                add(value)
            }
        }
        return haystack.any { normalized in it.lowercase() }
    }

    fun shortId(value: String): String =
        when {
            value.length <= 16 -> value
            else -> "${value.take(6)}...${value.takeLast(4)}"
        }

    fun shortSynchronizer(value: String): String =
        value.substringBefore("::").ifBlank { value.ifBlank { "unknown" } }

    fun shortParty(value: String): String =
        value.substringBefore("::").ifBlank { value }

    fun partySummary(parties: List<String>, maxItems: Int = 2): String {
        if (parties.isEmpty()) return "-"
        val short = parties.map(::shortParty)
        val visible = short.take(maxItems)
        return if (short.size > maxItems) "${visible.joinToString(", ")} +${short.size - maxItems}" else visible.joinToString(", ")
    }
}

class LedgerExplorerPanel(
    private val project: Project,
    private val sessions: SandboxSessionService = SandboxSessionService.getInstance(project),
    private val profiles: SandboxProfileService = SandboxProfileService.getInstance(project),
    private val navigation: SandboxExplorerNavigationService = SandboxExplorerNavigationService.getInstance(project),
    private val backgroundExecutor: (() -> Unit) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    private val snapshotLoader: (SandboxProfile, String, Set<String>?) -> LedgerExplorerSnapshot = { profile, participant, parties ->
        sessions.fetchLedgerSnapshot(profile, participant, "", parties)
    }
) : JPanel(BorderLayout(10, 10)), Disposable {
    private val statusPill = ExplorerPill("Stopped", ExplorerTheme.archived, filled = false)
    private val profileComboModel = DefaultComboBoxModel<SandboxProfile>()
    private val profileCombo = ProfileComboBox(profileComboModel)
    private val participantSelector = ExplorerStringSelector(ExplorerTheme.participant)
    private val offsetPill = ExplorerPill("Offset -", ExplorerTheme.border, filled = false)
    private val searchField = JBTextField()
    private val participantModel = DefaultListModel<String>()
    private val participantList = JBList(participantModel)
    private val syncModel = DefaultListModel<String>()
    private val syncList = JBList(syncModel)
    private val partyModel = DefaultListModel<String>()
    private val partyList = JBList(partyModel)
    private val archivedSwitch = ExplorerSwitch(LedgerActivityKind.ARCHIVED, "▣", true)
    private val eventsSwitch = ExplorerSwitch(LedgerActivityKind.CREATED, "ϟ", true)
    private val messageLabel = JBLabel("Sandbox not running")
    private val segmentTabs = ExplorerSegmentTabs(listOf(LedgerActivityKind.ACTIVE, HISTORY_SEGMENT))
    private val activityModel = tableModel("Type", "Template", "Contract", "Sync Domain", "Parties", "Offset")
    private val activityTable = ActivityTable(activityModel)
    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        foreground = ExplorerTheme.text
        background = ExplorerTheme.card
        border = JBUI.Borders.empty(2)
    }
    private val rawArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = ExplorerTheme.mutedText
        background = ExplorerTheme.card
        border = JBUI.Borders.empty()
    }
    private val selectedStatusPill = ExplorerPill("No selection", ExplorerTheme.border, filled = false)
    private val timeline = NetworkActivityTimelinePanel()
    private val sidebarSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
    }

    private val viewPreferences = PropertiesComponent.getInstance(project)
    private val detailsSlot = JPanel(BorderLayout()).apply { isVisible = false }
    private val detailSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
    private val loadMore = JButton("Load more history").apply { addActionListener { refresh() }; isVisible = false }
    private val historyCoverage = JBLabel()
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh).apply { addActionListener { refresh() } }
    private val diagnosticToggle = JCheckBox("Diagnostics")
    private val timelineToggle = JCheckBox("Timeline")
    private var queryParties: Set<String>? = null
    private var loading = false
    private var profile: SandboxProfile? = null
    private var session: SandboxSessionState = SandboxSessionState()
    private var currentSnapshot: LedgerExplorerSnapshot? = null
    private var currentSnapshotProfileId: String? = null
    private var allRows: List<ExplorerActivityRow> = emptyList()
    private var visibleRows: List<ExplorerActivityRow> = emptyList()
    private var selectedRow: ExplorerActivityRow? = null
    private var selectedSegment = LedgerActivityKind.ACTIVE
    private var updatingProfile = false
    private var updatingFilters = false
    private var sidebarExpanded = false
    private var refreshSequence = 0L
    private var profileListener: Disposable? = null
    private var sessionListener: Disposable? = null
    private var navigationListener: Disposable? = null
    private var pendingNavigation: SandboxExplorerNavigationService.Request? = null

    init {
        name = "Managed Canton Sandboxes - Explorer"
        background = ExplorerTheme.shell
        border = JBUI.Borders.empty(10)
        configureProfileCombo()
        configureParticipantSelector()
        configureLists()
        configureTable()
        configureTimeline()
        configureActions()
        add(toolbar(), BorderLayout.NORTH)
        add(explorerSurface(), BorderLayout.CENTER)
        profileListener = profiles.addListener { next -> runOnEdt { setProfile(next); setSession(sessions.snapshot()) } }
        sessionListener = sessions.addListener { next -> runOnEdt { setSession(next) } }
        navigationListener = navigation.addListener { request -> runOnEdt { handleNavigation(request) } }
    }

    fun setProfile(draft: SandboxProfile) {
        val next = session.launchedProfile?.takeIf { session.ownsProcess && it.id == draft.id } ?: draft
        val changed = profile?.id != next.id
        if (!changed && profile?.runtimeDefinition() == next.runtimeDefinition()) {
            profile = next
            profileCombo.repaint()
            updateRefreshControls()
            return
        }
        refreshSequence++; loading = false
        if (changed) queryParties = null
        profile = next
        updatingProfile = true
        try {
            refreshProfileCombo(next)
        } finally {
            updatingProfile = false
        }
        val previousParticipant = participantSelector.selectedValue ?: participantList.selectedValue
        updatingFilters = true
        try {
            replace(participantModel, next.participants.map { it.name })
            replace(syncModel, next.synchronizers.map { it.name })
            replace(partyModel, next.partyAllocations.map { it.partyHint }.distinct().sorted())
            participantSelector.setValues(next.participants.map { it.name }, previousParticipant)
            selectValueOrFirst(participantList, participantSelector.selectedValue ?: previousParticipant)
            selectAll(syncList, fire = false)
            selectAll(partyList, fire = false)
        } finally {
            updatingFilters = false
        }
        if (!sidebarExpanded) updateSidebar()
        if (currentSnapshotProfileId != null && currentSnapshotProfileId != next.id ||
            currentSnapshot?.participantName !in next.participants.map { it.name }.toSet()
        ) {
            currentSnapshot = null
            currentSnapshotProfileId = null
            allRows = emptyList()
            selectedRow = null
            offsetPill.setStatus("Offset -", ExplorerTheme.border, false)
        }
        updateRefreshControls()
        applyFilters()
        pendingNavigation?.takeIf { it.profileId == next.id }?.let { request ->
            pendingNavigation = null
            selectParticipantForExplorer(request.participantId, request.refresh)
        }
    }

    fun setSession(next: SandboxSessionState) {
        if (session.sessionId != next.sessionId) {
            loading = false; queryParties = null
            refreshSequence++; currentSnapshot = null; allRows = emptyList(); selectedRow = null
            applyFilters()
        }
        val current = profile
        val belongs = current == null || next.profileId.isBlank() || next.profileId == current.id
        session = next
        current?.let { setProfile(profiles.selectedProfile()) }
        updateRefreshControls()
        val visibleStatus = if (belongs) next.status else SandboxSessionStatus.STOPPED
        val color = when (visibleStatus) {
            SandboxSessionStatus.RUNNING -> ExplorerTheme.active
            SandboxSessionStatus.STARTING, SandboxSessionStatus.GENERATING, SandboxSessionStatus.STOPPING -> ExplorerTheme.warning
            SandboxSessionStatus.FAILED -> ExplorerTheme.error
            SandboxSessionStatus.STOPPED -> ExplorerTheme.archived
        }
        statusPill.setStatus(visibleStatus.presentableName, color, visibleStatus == SandboxSessionStatus.RUNNING)
        if (currentSnapshot == null) {
            messageLabel.text = session.message.ifBlank { if (session.status == SandboxSessionStatus.RUNNING) "Ready to refresh ledger data" else "Sandbox not running" }
        }
    }

    internal fun refresh() {
        val current = profile ?: return
        if (!session.canQuery(current) || loading) { messageLabel.text = "Start this profile before refreshing."; return }
        val participantName = participantList.selectedValue ?: current.participants.firstOrNull()?.name ?: return
        loading = true
        updateRefreshControls()
        val profileId = current.id
        val requestId = ++refreshSequence
        messageLabel.text = "Refreshing $participantName ledger data..."
        val parties = queryParties?.toSet()
        backgroundExecutor {
            val result = runCatching { snapshotLoader(current, participantName, parties) }
            SwingUtilities.invokeLater {
                if (requestId != refreshSequence || profile?.id != profileId || participantList.selectedValue != participantName) {
                    return@invokeLater
                }
                loading = false
                updateRefreshControls()
                result.fold(::renderSnapshot) { renderError(participantName, it) }
            }
        }
    }

    internal fun applyFilters() {
        if (updatingFilters) return
        listOf(archivedSwitch, eventsSwitch).forEach { it.isVisible = selectedSegment == HISTORY_SEGMENT }
        val state = ExplorerFilterState(
            query = searchField.text,
            syncDomains = syncList.selectedValuesList.toSet(),
            parties = partyList.selectedValuesList.toSet(),
            kinds = selectedKinds()
        )
        val previousSelection = selectedRow
        visibleRows = LedgerExplorerRows.filter(allRows, state)
        reset(activityModel)
        visibleRows.forEach { row ->
            activityModel.addRow(arrayOf(
                activityLabel(row),
                row.templateName,
                LedgerExplorerRows.shortId(row.contractId),
                row.syncName,
                LedgerExplorerRows.partySummary(row.parties),
                row.offsetText.ifBlank { "-" }
            ))
        }
        timeline.setRows(visibleRows, selectedRow)
        if (visibleRows.isEmpty()) {
            selectedRow = null
            val participant = participantList.selectedValue ?: currentSnapshot?.participantName ?: "-"
            val offset = currentSnapshot?.ledgerEnd?.toString() ?: "-"
            clearInspector(
                when {
                    currentSnapshot == null && session.status != SandboxSessionStatus.RUNNING ->
                        "Sandbox not running. Start this profile in Network, then refresh."
                    currentSnapshot == null -> "Refresh ledger data for $participant."
                    allRows.any { it.kind in state.kinds } -> "No results match your filters. Clear search or adjust filters."
                    selectedSegment == LedgerActivityKind.ACTIVE -> "No active contracts for $participant at offset $offset."
                    selectedSegment == HISTORY_SEGMENT -> "No ledger history for $participant at offset $offset."
                    else -> "No visible contracts for $participant at offset $offset."
                }
            )
        } else {
            val selectedIndex = visibleRows.indexOfFirst { it.contractId == previousSelection?.contractId && it.kind == previousSelection?.kind }
                .takeIf { it >= 0 }
            if (selectedIndex != null) {
                activityTable.selectionModel.setSelectionInterval(selectedIndex, selectedIndex)
                showInspector(visibleRows[selectedIndex])
            } else { activityTable.clearSelection(); detailsSlot.isVisible = false }
        }
        activityTable.repaint()
    }

    private fun selectedKinds(): Set<String> = when (selectedSegment) {
        LedgerActivityKind.ACTIVE -> setOf(LedgerActivityKind.ACTIVE)
        else -> buildSet {
            if (eventsSwitch.selected) add(LedgerActivityKind.CREATED)
            if (archivedSwitch.selected) add(LedgerActivityKind.ARCHIVED)
            addAll(listOf(LedgerActivityKind.ASSIGNED, LedgerActivityKind.UNASSIGNED,
                LedgerActivityKind.IN_FLIGHT_ASSIGNMENT, LedgerActivityKind.IN_FLIGHT_UNASSIGNMENT))
        }
    }

    private fun handleNavigation(request: SandboxExplorerNavigationService.Request) {
        val current = profile
        if (current == null || current.id != request.profileId) {
            pendingNavigation = request
            profiles.selectProfile(request.profileId)
            return
        }
        selectParticipantForExplorer(request.participantId, request.refresh)
    }

    private fun selectParticipantForExplorer(participantId: String, refreshAfterSelect: Boolean) {
        val current = profile ?: return
        val participant = current.participant(participantId) ?: return
        updatingFilters = true
        try {
            searchField.text = ""
            archivedSwitch.selected = true
            eventsSwitch.selected = true
            selectValueOrFirst(participantList, participant.name)
            selectAll(syncList, fire = false)
            selectAll(partyList, fire = false)
            segmentTabs.select(HISTORY_SEGMENT, fire = false)
            selectedSegment = HISTORY_SEGMENT
            participantSelector.selectValue(participant.name, notify = false)
        } finally {
            updatingFilters = false
        }
        messageLabel.text = "Showing ${participant.name} in Explorer"
        handleParticipantSelectionChanged()
        applyFilters()
        if (refreshAfterSelect && session.status == SandboxSessionStatus.RUNNING) refresh()
    }

    private fun configureProfileCombo() {
        profileCombo.background = ExplorerTheme.card
        profileCombo.foreground = ExplorerTheme.text
        profileCombo.addActionListener {
            if (!updatingProfile) {
                (profileCombo.selectedItem as? SandboxProfile)?.let { profiles.selectProfile(it.id) }
            }
        }
    }

    private fun configureParticipantSelector() {
        participantSelector.onSelectionChanged = { selected ->
            if (!updatingFilters) {
                updatingFilters = true
                try {
                    selectValueOrFirst(participantList, selected)
                } finally {
                    updatingFilters = false
                }
                handleParticipantSelectionChanged()
            }
        }
    }

    private fun configureLists() {
        participantList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        participantList.cellRenderer = SidebarCellRenderer("participant")
        syncList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        syncList.cellRenderer = SidebarCellRenderer("sync")
        partyList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        partyList.cellRenderer = SidebarCellRenderer("party")
        listOf(participantList, syncList, partyList).forEach {
            it.background = ExplorerTheme.card
            it.foreground = ExplorerTheme.text
            it.fixedCellHeight = 42
            it.border = JBUI.Borders.empty()
        }
        participantList.addListSelectionListener {
            if (!it.valueIsAdjusting && !updatingFilters) handleParticipantSelectionChanged()
        }
        listOf(syncList, partyList).forEach { list ->
            list.addListSelectionListener {
                if (!it.valueIsAdjusting && !updatingFilters) applyFilters()
            }
        }
        searchField.document.addDocumentListener(SimpleDocumentListener { applyFilters() })
    }

    private fun handleParticipantSelectionChanged() {
        val selected = participantList.selectedValue ?: participantSelector.selectedValue
        if (selected != null && participantSelector.selectedValue != selected) {
            participantSelector.selectValue(selected, notify = false)
        }
        invalidateRefresh()
        queryParties = null
        if (currentSnapshot?.participantName != selected) {
            currentSnapshot = null
            currentSnapshotProfileId = null
            allRows = emptyList()
            selectedRow = null
            offsetPill.setStatus("Offset -", ExplorerTheme.border, false)
            applyFilters()
        }
    }

    private fun invalidateRefresh() {
        refreshSequence++
        loading = false
        updateRefreshControls()
    }

    private fun updateRefreshControls() {
        val canRefresh = profile?.let(session::canQuery) == true && !loading
        refreshButton.isEnabled = canRefresh
        loadMore.isEnabled = canRefresh
    }

    private fun configureTable() {
        activityTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        activityTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        activityTable.rowHeight = JBUI.scale(30)
        activityTable.showHorizontalLines = true
        activityTable.showVerticalLines = false
        activityTable.gridColor = ExplorerTheme.borderSoft
        activityTable.intercellSpacing = Dimension(0, 0)
        activityTable.background = ExplorerTheme.tableRow
        activityTable.foreground = ExplorerTheme.text
        activityTable.fillsViewportHeight = true
        activityTable.tableHeader.background = ExplorerTheme.card
        activityTable.tableHeader.foreground = ExplorerTheme.text
        activityTable.tableHeader.font = activityTable.tableHeader.font.deriveFont(Font.BOLD, 12f)
        activityTable.tableHeader.preferredSize = Dimension(0, 30)
        activityTable.tableHeader.border = BorderFactory.createMatteBorder(0, 0, 1, 0, ExplorerTheme.borderSoft)
        activityTable.selectionBackground = ExplorerTheme.cardSoft
        activityTable.selectionForeground = ExplorerTheme.text
        activityTable.columnModel.getColumn(0).preferredWidth = 158
        activityTable.columnModel.getColumn(1).preferredWidth = 142
        activityTable.columnModel.getColumn(2).preferredWidth = 150
        activityTable.columnModel.getColumn(3).preferredWidth = 130
        activityTable.columnModel.getColumn(4).preferredWidth = 128
        activityTable.columnModel.getColumn(5).preferredWidth = 72
        activityTable.setDefaultRenderer(Any::class.java, DefaultTableCellRenderer())
        activityTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                visibleRows.getOrNull(activityTable.selectedRow)?.let(::showInspector)
            }
        }
        activityTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) copySelectedContract()
            }
        })
    }

    private fun configureTimeline() {
        timeline.onRowSelected = { row ->
            selectActivityRow(row, source = "timeline")
        }
    }

    private fun configureActions() {
        segmentTabs.onSelectionChanged = {
            selectedSegment = it
            historyCoverage.isVisible = it == HISTORY_SEGMENT
            loadMore.isVisible = it == HISTORY_SEGMENT && currentSnapshot?.historyComplete == false
            applyFilters()
        }
        listOf(archivedSwitch, eventsSwitch).forEach { it.onChanged = { applyFilters() } }
        searchField.emptyText.text = "contract, party, template..."
        searchField.toolTipText = "Search contract, party, synchronizer, package, template, or argument"
    }

    private fun toolbar(): JComponent = JPanel(BorderLayout(6, 6)).apply {
        add(JPanel(WrappingToolbarLayout()).apply {
            add(profileCombo.apply { preferredSize = JBUI.size(220, preferredSize.height) })
            add(participantSelector.apply { preferredSize = JBUI.size(150, preferredSize.height) })
            add(refreshButton)
            add(JButton("Parties…").apply { addActionListener { chooseQueryParties() } })
            add(searchField.apply { columns = 22 })
            add(statusPill)
        }, BorderLayout.NORTH)
        add(messageLabel, BorderLayout.SOUTH)
    }

    private fun chooseQueryParties() {
        val snapshot = currentSnapshot ?: run { messageLabel.text = "Refresh to discover available parties."; return }
        val choices = JBList<String>(*snapshot.knownParties.toTypedArray()).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            selectedIndices = snapshot.knownParties.indices.filter { snapshot.knownParties[it] in snapshot.parties }.toIntArray()
        }
        val content = JPanel(BorderLayout(6, 6)).apply {
            add(JBLabel("Local parties are selected by default. Full party IDs are required."), BorderLayout.NORTH)
            add(JBScrollPane(choices).apply { preferredSize = JBUI.size(520, 220) }, BorderLayout.CENTER)
        }
        if (javax.swing.JOptionPane.showConfirmDialog(this, content, "Query parties", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
            queryParties = choices.selectedValuesList.toSet(); refresh()
        }
    }

    private fun explorerSurface(): JComponent = JPanel(BorderLayout(6, 6)).apply {
        detailsSlot.add(detailsCard(), BorderLayout.CENTER)
        detailSplit.apply {
            border = JBUI.Borders.empty(); resizeWeight = 0.65
            leftComponent = activityCard(); rightComponent = detailsSlot
            dividerLocation = viewPreferences.getInt(DETAIL_DIVIDER_KEY, 680)
            addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
                if (detailsSlot.isVisible) viewPreferences.setValue(DETAIL_DIVIDER_KEY, dividerLocation, 680)
            }
        }
        add(JPanel(BorderLayout(6, 0)).apply {
            updateSidebar(); add(sidebarSlot, BorderLayout.WEST); add(detailSplit, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        val timelineView = timelineScroll().apply { isVisible = viewPreferences.getBoolean(TIMELINE_KEY, false) }
        timelineToggle.isSelected = timelineView.isVisible
        timelineToggle.addActionListener {
            timelineView.isVisible = timelineToggle.isSelected
            viewPreferences.setValue(TIMELINE_KEY, timelineToggle.isSelected)
        }
        add(timelineView, BorderLayout.SOUTH)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val orientation = if (width < JBUI.scale(900)) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
                if (detailSplit.orientation != orientation) { detailSplit.orientation = orientation; detailSplit.setDividerLocation(0.6) }
            }
        })
    }

    private fun timelineScroll(): JComponent =
        JBScrollPane(timeline).apply {
            preferredSize = Dimension(100, 138)
            minimumSize = Dimension(100, 118)
            border = BorderFactory.createEmptyBorder()
            viewport.background = ExplorerTheme.shell
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        }

    private fun updateSidebar() {
        sidebarSlot.removeAll()
        sidebarSlot.add(if (sidebarExpanded) expandedSidebar() else collapsedSidebar(), BorderLayout.CENTER)
        sidebarSlot.revalidate()
        sidebarSlot.repaint()
    }

    private fun expandedSidebar(): JComponent =
        ExplorerCard(BorderLayout(0, 10), padded = 10).apply {
            preferredSize = Dimension(260, 100)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel("Filters").styledTitle(), BorderLayout.WEST)
                add(sidebarToggleButton(expanded = true), BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(GridBagLayout()).apply {
                isOpaque = false
                var y = 0
                add(switches(), gbc(y++, 0.0))
                add(clearFiltersButton(), gbc(y++, 0.0))
                add(JPanel().apply { isOpaque = false }, gbc(y, 1.0))
            }, BorderLayout.CENTER)
        }

    private fun collapsedSidebar(): JComponent =
        ExplorerCard(GridBagLayout(), padded = 6).apply {
            preferredSize = Dimension(44, 100)
            add(sidebarToggleButton(expanded = false), collapsedGbc(0, 0.0))
            add(JPanel().apply { isOpaque = false }, collapsedGbc(1, 1.0))
        }

    private fun sidebarToggleButton(expanded: Boolean): JButton =
        JButton(if (expanded) "<" else ">").apply {
            toolTipText = if (expanded) "Collapse filters" else "Expand filters"
            foreground = ExplorerTheme.text
            background = ExplorerTheme.card
            isOpaque = true
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, 14f)
            preferredSize = Dimension(32, 30)
            border = BorderFactory.createLineBorder(ExplorerTheme.border)
            addActionListener {
                sidebarExpanded = !sidebarExpanded
                updateSidebar()
            }
        }

    private fun activityCard(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        add(JPanel(BorderLayout(6, 6)).apply {
            add(segmentTabs, BorderLayout.NORTH)
            add(historyCoverage, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(loadMore); add(timelineToggle); add(diagnosticToggle)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(activityTable).apply { setColumnHeaderView(activityTable.tableHeader); horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED }, BorderLayout.CENTER)
    }

    private fun detailsCard(): JComponent = JPanel(BorderLayout(6, 6)).apply {
        minimumSize = JBUI.size(100, 100)
        add(JPanel(BorderLayout()).apply {
            add(selectedStatusPill, BorderLayout.CENTER)
            add(JButton("Close").apply { addActionListener { activityTable.clearSelection(); detailsSlot.isVisible = false } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(detailsArea), BorderLayout.CENTER)
        val diagnostics = JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(300, 200); add(JBScrollPane(rawArea), BorderLayout.CENTER)
            add(JButton("Copy full JSON").apply { addActionListener { copySelectedRaw() } }, BorderLayout.SOUTH)
            isVisible = false
        }
        diagnosticToggle.addActionListener {
            diagnostics.isVisible = diagnosticToggle.isSelected
            if (diagnosticToggle.isSelected) { if (selectedRow == null) showSnapshotRaw(); showDetails() }
        }
        add(diagnostics, BorderLayout.SOUTH)
    }

    private fun switches(): JComponent =
        JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            listOf(archivedSwitch, eventsSwitch).forEachIndexed { index, component ->
                add(component, GridBagConstraints().apply {
                    gridx = 0
                    gridy = index
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    insets = Insets(3, 0, 3, 0)
                })
            }
        }

    private fun clearFiltersButton(): JComponent =
        iconButton("Clear filters", AllIcons.Actions.GC) {
            searchField.text = ""
            archivedSwitch.selected = true
            eventsSwitch.selected = true
            selectAll(syncList)
            selectAll(partyList)
            segmentTabs.select(LedgerActivityKind.ACTIVE)
            applyFilters()
        }.apply {
            horizontalAlignment = SwingConstants.LEFT
            preferredSize = Dimension(232, 34)
        }

    private fun showDetails() {
        val opening = !detailsSlot.isVisible
        detailsSlot.isVisible = true
        if (opening) {
            val extent = if (detailSplit.orientation == JSplitPane.HORIZONTAL_SPLIT) detailSplit.width else detailSplit.height
            if (detailSplit.dividerLocation > extent - JBUI.scale(180)) detailSplit.dividerLocation = (extent * 0.56).toInt()
        }
        detailSplit.revalidate()
    }

    private fun renderSnapshot(snapshot: LedgerExplorerSnapshot) {
        currentSnapshot = snapshot
        loadMore.isVisible = !snapshot.historyComplete
        historyCoverage.isVisible = selectedSegment == HISTORY_SEGMENT
        loadMore.isVisible = selectedSegment == HISTORY_SEGMENT && !snapshot.historyComplete
        historyCoverage.text = "${snapshot.participantName}: history through ${snapshot.historyThrough} of ${snapshot.ledgerEnd}" + if (snapshot.historyComplete) " · Complete" else " · Partial"
        currentSnapshotProfileId = profile?.id
        if (participantList.selectedValue != snapshot.participantName || participantSelector.selectedValue != snapshot.participantName) {
            updatingFilters = true
            try {
                selectValueOrFirst(participantList, snapshot.participantName)
                participantSelector.selectValue(snapshot.participantName, notify = false)
            } finally {
                updatingFilters = false
            }
        }
        allRows = LedgerExplorerRows.from(snapshot)
        offsetPill.setStatus("Offset ${snapshot.ledgerEnd}", ExplorerTheme.warning, false)
        messageLabel.text = buildString {
            append("${snapshot.participantName}: snapshot at offset ${snapshot.ledgerEnd} · ${snapshot.activeContracts.size} active contract(s)")
            if (snapshot.warnings.isNotEmpty()) append(" - ${snapshot.warnings.joinToString(" ")}")
        }
        rawArea.text = snapshotRawText(snapshot)
        updateFilterOptionsFrom(snapshot)
        applyFilters()
    }

    private fun renderError(participantName: String, error: Throwable) {
        messageLabel.text = "$participantName: ${error.message ?: "ledger refresh failed"}. Retry Refresh." +
            if (currentSnapshot != null) " Showing previous snapshot at ${currentSnapshot?.ledgerEnd}." else ""
        if (currentSnapshot == null) clearInspector(messageLabel.text)
    }

    private fun updateFilterOptionsFrom(snapshot: LedgerExplorerSnapshot) {
        val syncSelection = syncList.selectedValuesList.toSet()
        val partySelection = partyList.selectedValuesList.toSet()
        val syncs = (profile?.synchronizers?.map { it.name }.orEmpty() + allRows.map { it.syncName })
            .distinct()
            .sortedWith { a, b ->
                when {
                    a == SandboxDefaults.SHARED_SYNCHRONIZER_NAME -> -1
                    b == SandboxDefaults.SHARED_SYNCHRONIZER_NAME -> 1
                    else -> a.compareTo(b)
                }
            }
        val parties = (snapshot.parties.map(LedgerExplorerRows::shortParty) + allRows.flatMap { it.parties.map(LedgerExplorerRows::shortParty) })
            .distinct()
            .sorted()
        updatingFilters = true
        try {
            replace(syncModel, syncs)
            replace(partyModel, parties)
            selectValuesOrAll(syncList, syncSelection)
            selectValuesOrAll(partyList, partySelection)
        } finally {
            updatingFilters = false
        }
        if (!sidebarExpanded) updateSidebar()
    }

    private fun showInspector(row: ExplorerActivityRow) {
        selectedRow = row
        showDetails()
        timeline.setRows(visibleRows, row)
        val route = if (row.syncName == SandboxDefaults.SHARED_SYNCHRONIZER_NAME) "global route" else "private route"
        val partyText = row.parties.joinToString("\n") { "  $it" }.ifBlank { "  -" }
        val args = row.argumentFields.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }.ifBlank { "  -" }
        selectedStatusPill.setStatus(row.kind, kindColor(row.kind), row.kind == LedgerActivityKind.ACTIVE || row.kind == LedgerActivityKind.CREATED)
        detailsArea.text = buildString {
            appendLine("${row.templateName}")
            appendLine()
            appendLine("Template        ${row.templateName}")
            appendLine("Contract ID     ${row.contractId}")
            appendLine("Synchronizer    ${row.syncName} ($route)")
            appendLine("Package         ${row.packageName.ifBlank { "-" }}")
            appendLine("Participant offset  ${row.offsetText.ifBlank { "-" }}")
            if (row.sourceSynchronizerId.isNotBlank()) appendLine("Source synchronizer  ${row.sourceSynchronizerId}")
            if (row.targetSynchronizerId.isNotBlank()) appendLine("Target synchronizer  ${row.targetSynchronizerId}")
            appendLine()
            appendLine("Parties")
            appendLine(partyText)
            appendLine()
            appendLine("Arguments")
            appendLine(args)
        }
        detailsArea.caretPosition = 0
        rawArea.text = row.rawJson.ifBlank { "(no raw JSON for this row)" }
        activityTable.repaint()
    }

    private fun selectActivityRow(row: ExplorerActivityRow, source: String) {
        val index = visibleRows.indexOf(row)
        if (index < 0) return
        activityTable.selectionModel.setSelectionInterval(index, index)
        activityTable.scrollRectToVisible(activityTable.getCellRect(index, 0, true))
        showInspector(row)
        messageLabel.text = "Selected ${row.kind.lowercase()} ${row.templateName} at offset ${row.offsetText.ifBlank { "-" }} from $source"
    }

    private fun clearInspector(message: String) {
        selectedRow = null
        detailsSlot.isVisible = false
        messageLabel.text = message
        selectedStatusPill.setStatus("No selection", ExplorerTheme.border, false)
        detailsArea.text = message
        detailsArea.caretPosition = 0
        rawArea.text = currentSnapshot?.let(::snapshotRawText) ?: ""
    }

    private fun showSnapshotRaw() {
        val snapshot = currentSnapshot
        selectedStatusPill.setStatus("Raw response", ExplorerTheme.activity, false)
        detailsArea.text = snapshot?.let {
            "Raw JSON for ${it.participantName} at ledger offset ${it.ledgerEnd}."
        } ?: "No raw ledger response available."
        detailsArea.caretPosition = 0
        rawArea.text = snapshot?.let(::snapshotRawText) ?: ""
    }

    private fun snapshotRawText(snapshot: LedgerExplorerSnapshot): String = buildString {
        appendLine("Active contracts")
        appendLine(snapshot.rawActiveResponse)
        appendLine()
        appendLine("Updates (last fetched history page)")
        appendLine(snapshot.rawUpdatesResponse)
    }

    private fun copySelectedContract() {
        val row = selectedRow ?: visibleRows.getOrNull(activityTable.selectedRow) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(row.contractId))
        messageLabel.text = "Copied contract id ${LedgerExplorerRows.shortId(row.contractId)}"
    }

    private fun copySelectedRaw() {
        val raw = rawArea.text.takeIf { it.isNotBlank() } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(raw))
        messageLabel.text = "Copied raw JSON"
    }

    private fun refreshProfileCombo(selected: SandboxProfile) {
        profileComboModel.removeAllElements()
        profiles.profiles().forEach(profileComboModel::addElement)
        val index = profiles.profiles().indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        if (profileComboModel.size > 0) profileCombo.selectedIndex = index
    }

    private fun activityLabel(row: ExplorerActivityRow): String =
        row.kind

    private fun kindColor(kind: String): Color =
        when (kind) {
            LedgerActivityKind.ACTIVE -> ExplorerTheme.created
            LedgerActivityKind.ARCHIVED -> ExplorerTheme.archived
            else -> ExplorerTheme.active
        }

    private fun iconButton(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            addActionListener { action() }
        }

    private fun selectAll(list: JBList<String>, fire: Boolean = true) {
        if (list.model.size > 0) list.setSelectionInterval(0, list.model.size - 1)
        if (fire) applyFilters()
    }

    private fun selectValueOrFirst(list: JBList<String>, value: String?) {
        val index = (0 until list.model.size).firstOrNull { list.model.getElementAt(it) == value } ?: 0
        if (list.model.size > 0) list.selectedIndex = index
    }

    private fun selectValuesOrAll(list: JBList<String>, previous: Set<String>) {
        val indexes = (0 until list.model.size).filter { list.model.getElementAt(it) in previous }
        when {
            indexes.isNotEmpty() -> list.selectedIndices = indexes.toIntArray()
            list.model.size > 0 -> list.setSelectionInterval(0, list.model.size - 1)
        }
    }

    private fun replace(model: DefaultListModel<String>, values: List<String>) {
        model.clear()
        values.forEach(model::addElement)
    }

    private fun tableModel(vararg columns: String): DefaultTableModel =
        object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

    private fun reset(model: DefaultTableModel) {
        model.rowCount = 0
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    private fun gbc(y: Int, weighty: Double): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = 0
            gridy = y
            fill = GridBagConstraints.BOTH
            weightx = 1.0
            this.weighty = weighty
            insets = Insets(0, 0, 10, 0)
        }

    private fun collapsedGbc(y: Int, weighty: Double): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = 0
            gridy = y
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            this.weighty = weighty
            insets = Insets(3, 0, 3, 0)
        }

    private companion object {
        const val DETAIL_DIVIDER_KEY = "daml.explorer.detail.divider"
        const val TIMELINE_KEY = "daml.explorer.timeline"
        const val HISTORY_SEGMENT = "History"
    }

    override fun dispose() {
        refreshSequence++
        profileListener?.dispose()
        sessionListener?.dispose()
        navigationListener?.dispose()
        profileListener = null
        sessionListener = null
        navigationListener = null
    }
}

private fun JLabel.styledTitle(): JLabel =
    apply {
        foreground = ExplorerTheme.text
        font = font.deriveFont(Font.BOLD, 16f)
    }

internal open class ExplorerCard(layout: java.awt.LayoutManager, padded: Int = 0) : JPanel(layout) {
    init {
        background = ExplorerTheme.card
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(ExplorerTheme.borderSoft, 8),
            JBUI.Borders.empty(padded)
        )
    }
}

private class ExplorerPill(text: String, color: Color, filled: Boolean) : JBLabel(text) {
    init { foreground = color; border = JBUI.Borders.empty(2, 6) }
    fun setStatus(text: String, color: Color, filled: Boolean) { this.text = text; foreground = color }
}

private class ExplorerButton(text: String, icon: javax.swing.Icon?) : JButton(text, icon) {
    init {
        foreground = ExplorerTheme.text
        background = ExplorerTheme.card
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(6, 12)
        margin = Insets(0, 0, 0, 0)
        horizontalAlignment = SwingConstants.CENTER
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when {
                model.isPressed -> ExplorerTheme.cardSoft
                model.isRollover -> Color(ExplorerTheme.glowBlue.red, ExplorerTheme.glowBlue.green, ExplorerTheme.glowBlue.blue, 30)
                else -> ExplorerTheme.card
            }
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
            g2.color = if (model.isRollover) ExplorerTheme.glowBlue else ExplorerTheme.border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class ExplorerStringSelector(accent: Color) : JComboBox<String>() {
    val selectedValue: String? get() = selectedItem as? String
    var onSelectionChanged: (String) -> Unit = {}
    private var suppressNotification = false
    init {
        foreground = accent
        getAccessibleContext().accessibleName = "Participant"
        addActionListener { if (!suppressNotification) selectedValue?.let(onSelectionChanged) }
    }
    fun setValues(next: List<String>, preferred: String?) {
        suppressNotification = true
        try { model = DefaultComboBoxModel(next.toTypedArray()); selectedItem = preferred?.takeIf { it in next } ?: next.firstOrNull() }
        finally { suppressNotification = false }
    }
    fun selectValue(value: String, notify: Boolean = true) {
        suppressNotification = !notify
        try { selectedItem = value } finally { suppressNotification = false }
    }
}

private class ExplorerSwitch(
    private val label: String,
    private val icon: String,
    selected: Boolean
) : JComponent() {
    var selected: Boolean = selected
        set(value) {
            field = value
            repaint()
            onChanged()
        }
    var onChanged: () -> Unit = {}

    init {
        preferredSize = Dimension(232, 38)
        minimumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                this@ExplorerSwitch.selected = !this@ExplorerSwitch.selected
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ExplorerTheme.card
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
            g2.color = ExplorerTheme.borderSoft
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
            g2.color = ExplorerTheme.mutedText
            g2.font = font.deriveFont(Font.PLAIN, 13f)
            g2.drawString(icon, 14, 24)
            g2.color = ExplorerTheme.text
            g2.drawString(label, 38, 24)
            val switchW = 42
            val switchH = 22
            val x = width - switchW - 12
            val y = (height - switchH) / 2
            g2.color = if (selected) Color(0x245E43) else Color(0x263241)
            g2.fillRoundRect(x, y, switchW, switchH, switchH, switchH)
            g2.color = if (selected) ExplorerTheme.active else ExplorerTheme.mutedText
            val knobX = if (selected) x + switchW - 19 else x + 3
            g2.fillOval(knobX, y + 3, 16, 16)
        } finally {
            g2.dispose()
        }
    }
}

private class ExplorerSegmentTabs(private val values: List<String>) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
    var selected: String = values.first()
        private set
    var onSelectionChanged: (String) -> Unit = {}
    private val buttons = values.associateWith { JToggleButton(if (it == LedgerActivityKind.ACTIVE) "Active Contracts" else it) }
    init {
        val group = ButtonGroup()
        buttons.forEach { (value, button) -> group.add(button); add(button); button.addActionListener { select(value) } }
        select(values.first(), false)
    }
    fun select(value: String, fire: Boolean = true) {
        if (value !in values) return
        selected = value; buttons.forEach { (key, button) -> button.isSelected = key == value }
        if (fire) onSelectionChanged(value)
    }
}

private class ActivityTable(model: DefaultTableModel) : JBTable(model) {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (rowCount == 0) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = ExplorerTheme.faintText
                g2.font = font.deriveFont(Font.BOLD, 14f)
                val text = "No contract activity visible"
                val metrics = g2.fontMetrics
                g2.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
            } finally {
                g2.dispose()
            }
        }
    }
}

private class SidebarCellRenderer(private val kind: String) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        val text = value?.toString().orEmpty()
        val color = when {
            kind == "participant" -> ExplorerTheme.participant
            kind == "party" -> ExplorerTheme.warning
            text == SandboxDefaults.SHARED_SYNCHRONIZER_NAME -> ExplorerTheme.globalSync
            else -> ExplorerTheme.privateSync
        }
        label.text = when (kind) {
            "participant" -> "${TopologyNodeIcons.PARTICIPANT}   $text                                      ›"
            "sync" -> "${TopologyNodeIcons.SYNCHRONIZER}   $text"
            else -> LedgerExplorerRows.shortParty(text)
        }
        label.foreground = if (isSelected) Color.WHITE else color
        label.background = if (isSelected) Color(color.red, color.green, color.blue, 92) else ExplorerTheme.card
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ExplorerTheme.borderSoft),
            JBUI.Borders.empty(0, 10)
        )
        return label
    }
}

internal class NetworkActivityTimelinePanel : ExplorerCard(BorderLayout(), padded = 10), Scrollable {
    private data class TimelineMarker(
        val row: ExplorerActivityRow,
        val x: Int,
        val y: Int,
        val radius: Int
    ) {
        val bounds: Rectangle = Rectangle(x - 14, y - 18, 88, 36)
    }

    private var rows: List<ExplorerActivityRow> = emptyList()
    private var selected: ExplorerActivityRow? = null
    private var hovered: ExplorerActivityRow? = null
    var onRowSelected: ((ExplorerActivityRow) -> Unit)? = null

    init {
        preferredSize = Dimension(1000, 126)
        minimumSize = Dimension(100, 100)
        toolTipText = ""
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val next = markerAt(e.point)?.row
                if (next != hovered) {
                    hovered = next
                    cursor = Cursor.getPredefinedCursor(if (next == null) Cursor.DEFAULT_CURSOR else Cursor.HAND_CURSOR)
                    repaint()
                }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hovered != null) {
                    hovered = null
                    cursor = Cursor.getDefaultCursor()
                    repaint()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                markerAt(e.point)?.row?.let { row ->
                    onRowSelected?.invoke(row)
                }
            }
        })
    }

    fun setRows(next: List<ExplorerActivityRow>, selectedRow: ExplorerActivityRow?) {
        rows = next
        selected = selectedRow
        if (hovered !in rows) hovered = null
        revalidate()
        repaint()
    }

    override fun getToolTipText(event: MouseEvent): String? =
        markerAt(event.point)?.row?.let(::timelineDescription)

    internal fun markerCenterForTest(row: ExplorerActivityRow): Point? =
        markers().firstOrNull { it.row == row }?.let { Point(it.x, it.y) }

    internal fun hoverDescriptionForTest(x: Int, y: Int): String? =
        markerAt(Point(x, y))?.row?.let(::timelineDescription)

    override fun getPreferredSize(): Dimension {
        val viewportWidth = (parent as? javax.swing.JViewport)?.extentSize?.width ?: 1000
        val eventCount = rows.mapNotNull { it.offset }.distinct().size.coerceAtLeast(rows.size).coerceAtLeast(1)
        val contentWidth = TIMELINE_LEFT + TIMELINE_RIGHT_PADDING + ((eventCount - 1) * EVENT_SPACING) + ACTIVITY_DOT_SPACE
        return Dimension(max(viewportWidth, contentWidth), 126)
    }

    override fun getScrollableTracksViewportWidth(): Boolean =
        preferredSize.width <= ((parent as? javax.swing.JViewport)?.extentSize?.width ?: 0)

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = 48
    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.width * 0.85).toInt().coerceAtLeast(120)

    override fun getScrollableTracksViewportHeight(): Boolean = true

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = font.deriveFont(Font.BOLD, 13f)
            g2.color = ExplorerTheme.text
            g2.drawString("Network Activity Timeline", 10, 24)
            drawLegend(g2)
            drawControls(g2)
            val left = TIMELINE_LEFT
            val right = max(left + 1, width - TIMELINE_RIGHT_PADDING)
            val y = 78
            g2.color = ExplorerTheme.border
            g2.stroke = BasicStroke(1.3f)
            g2.drawLine(left, y, right, y)
            if (rows.isEmpty()) {
                g2.color = ExplorerTheme.mutedText
                g2.font = font.deriveFont(Font.PLAIN, 13f)
                g2.drawString("No ledger activity visible", left, y + 20)
                return
            }
            markers().forEach { marker ->
                val row = marker.row
                val color = when {
                    row.syncName == SandboxDefaults.SHARED_SYNCHRONIZER_NAME -> ExplorerTheme.globalSync
                    row.kind == LedgerActivityKind.ARCHIVED -> ExplorerTheme.archived
                    else -> ExplorerTheme.privateSync
                }
                g2.color = color
                drawDiamond(g2, marker.x, marker.y, marker.radius)
                g2.font = font.deriveFont(Font.PLAIN, 11f)
                row.offset?.let { g2.drawString(it.toString(), marker.x - 8, marker.y - 18) }
                drawActivityDots(g2, marker.x + 18, marker.y)
            }
            markers().firstOrNull { it.row == hovered }?.let { drawHoverCard(g2, it) }
        } finally {
            g2.dispose()
        }
    }

    private fun markers(): List<TimelineMarker> {
        if (rows.isEmpty()) return emptyList()
        val left = TIMELINE_LEFT
        val right = max(left + 1, width - TIMELINE_RIGHT_PADDING)
        val y = 78
        val offsets = rows.mapNotNull { it.offset }
        val minOffset = offsets.minOrNull() ?: 0L
        val maxOffset = offsets.maxOrNull() ?: minOffset
        val positions = timelinePositions(rows, minOffset, maxOffset, left, right)
        return rows
            .sortedWith(compareByDescending<ExplorerActivityRow> { it.offset ?: 0 }.thenBy { it.contractId })
            .map { row ->
                val x = positions[row] ?: timelineX(row.offset ?: minOffset, minOffset, maxOffset, left, right)
                TimelineMarker(row, x, y, if (row == selected || row == hovered) 8 else 6)
            }
    }

    private fun markerAt(point: Point): TimelineMarker? =
        markers().firstOrNull { it.bounds.contains(point) }

    private fun timelineDescription(row: ExplorerActivityRow): String =
        "${row.kind} ${row.templateName} on ${row.syncName} at offset ${row.offsetText.ifBlank { "-" }} - ${LedgerExplorerRows.partySummary(row.parties, maxItems = 3)}"

    private fun drawHoverCard(g2: Graphics2D, marker: TimelineMarker) {
        val row = marker.row
        val title = "${row.kind} ${row.templateName}"
        val detail = "${row.syncName} · offset ${row.offsetText.ifBlank { "-" }} · ${LedgerExplorerRows.partySummary(row.parties, maxItems = 2)}"
        g2.font = font.deriveFont(Font.BOLD, 11.5f)
        val titleWidth = g2.fontMetrics.stringWidth(title)
        g2.font = font.deriveFont(Font.PLAIN, 10.5f)
        val detailWidth = g2.fontMetrics.stringWidth(detail)
        val bubbleWidth = max(titleWidth, detailWidth) + 22
        val bubbleHeight = 48
        val x = (marker.x - bubbleWidth / 2).coerceIn(10, (width - bubbleWidth - 10).coerceAtLeast(10))
        val y = (marker.y - 70).coerceAtLeast(36)
        g2.color = Color(ExplorerTheme.cardSoft.red, ExplorerTheme.cardSoft.green, ExplorerTheme.cardSoft.blue, 238)
        g2.fillRoundRect(x, y, bubbleWidth, bubbleHeight, 9, 9)
        g2.color = when (row.kind) {
            LedgerActivityKind.ARCHIVED -> ExplorerTheme.archived
            LedgerActivityKind.ACTIVE -> ExplorerTheme.created
            else -> ExplorerTheme.active
        }
        g2.stroke = BasicStroke(1.4f)
        g2.drawRoundRect(x, y, bubbleWidth, bubbleHeight, 9, 9)
        g2.font = font.deriveFont(Font.BOLD, 11.5f)
        g2.color = ExplorerTheme.text
        g2.drawString(title, x + 11, y + 19)
        g2.font = font.deriveFont(Font.PLAIN, 10.5f)
        g2.color = ExplorerTheme.mutedText
        g2.drawString(detail, x + 11, y + 36)
    }

    private fun timelinePositions(
        activityRows: List<ExplorerActivityRow>,
        minOffset: Long,
        maxOffset: Long,
        left: Int,
        right: Int
    ): Map<ExplorerActivityRow, Int> {
        if (activityRows.isEmpty()) return emptyMap()
        val ordered = activityRows.sortedWith(compareBy<ExplorerActivityRow> { it.offset ?: minOffset }.thenBy { it.contractId })
        if (ordered.size == 1) return mapOf(ordered.first() to (left + right) / 2)
        val available = (right - left).coerceAtLeast((ordered.size - 1) * MIN_EVENT_GAP)
        val linear = ordered.associateWith { row -> timelineX(row.offset ?: minOffset, minOffset, maxOffset, left, left + available) }
        val tooDense = linear.values.sorted().zipWithNext().any { (a, b) -> b - a < MIN_EVENT_GAP }
        if (!tooDense) return linear
        val span = (right - left).coerceAtLeast((ordered.size - 1) * MIN_EVENT_GAP)
        return ordered.mapIndexed { index, row ->
            row to (left + ((span * index).toDouble() / (ordered.size - 1).toDouble()).toInt())
        }.toMap()
    }

    private fun drawLegend(g2: Graphics2D) {
        var x = 210
        g2.font = font.deriveFont(Font.PLAIN, 12f)
        listOf(
            ExplorerTheme.privateSync to "privateSync",
            ExplorerTheme.globalSync to "global",
            ExplorerTheme.activity to "activity"
        ).forEach { (color, label) ->
            g2.color = color
            if (label == "activity") g2.fillOval(x, 15, 9, 9) else drawDiamond(g2, x + 5, 20, 6)
            g2.drawString(label, x + 18, 24)
            x += g2.fontMetrics.stringWidth(label) + 48
        }
    }

    private fun drawControls(g2: Graphics2D) {
        val liveX = width - 218
        g2.color = ExplorerTheme.card
        g2.fillRoundRect(liveX, 10, 68, 28, 7, 7)
        g2.color = ExplorerTheme.border
        g2.drawRoundRect(liveX, 10, 68, 28, 7, 7)
        g2.color = ExplorerTheme.active
        g2.fillOval(liveX + 12, 20, 8, 8)
        g2.color = ExplorerTheme.text
        g2.font = font.deriveFont(Font.PLAIN, 12f)
        g2.drawString("Live", liveX + 28, 28)
        g2.color = ExplorerTheme.card
        g2.fillRoundRect(width - 138, 10, 42, 28, 7, 7)
        g2.color = ExplorerTheme.border
        g2.drawRoundRect(width - 138, 10, 42, 28, 7, 7)
        g2.color = ExplorerTheme.text
        g2.drawString("Ⅱ", width - 122, 29)
        g2.color = ExplorerTheme.card
        g2.fillRoundRect(width - 86, 10, 72, 28, 7, 7)
        g2.color = ExplorerTheme.border
        g2.drawRoundRect(width - 86, 10, 72, 28, 7, 7)
        g2.color = ExplorerTheme.text
        g2.drawString("20s ˅", width - 66, 28)
    }

    private fun drawActivityDots(g2: Graphics2D, x: Int, y: Int) {
        g2.color = ExplorerTheme.activity
        (0..3).forEach { index ->
            val dotX = x + index * 15
            g2.fillOval(dotX, y - 3, 6, 6)
            g2.color = Color(ExplorerTheme.activity.red, ExplorerTheme.activity.green, ExplorerTheme.activity.blue, 58)
            g2.fillOval(dotX - 6, y - 9, 18, 18)
            g2.color = ExplorerTheme.activity
        }
    }

    private fun drawDiamond(g2: Graphics2D, x: Int, y: Int, radius: Int) {
        g2.stroke = BasicStroke(2f)
        g2.drawPolygon(
            intArrayOf(x, x + radius, x, x - radius),
            intArrayOf(y - radius, y, y + radius, y),
            4
        )
    }

    private fun timelineX(value: Long, minOffset: Long, maxOffset: Long, left: Int, right: Int): Int {
        if (maxOffset <= minOffset) return (left + right) / 2
        val fraction = (value - minOffset).toDouble() / (maxOffset - minOffset).toDouble()
        return min(right, max(left, left + ((right - left) * fraction).toInt()))
    }

    private companion object {
        private const val TIMELINE_LEFT = 60
        private const val TIMELINE_RIGHT_PADDING = 58
        private const val EVENT_SPACING = 118
        private const val MIN_EVENT_GAP = 84
        private const val ACTIVITY_DOT_SPACE = 90
    }
}

private open class RoundedLineBorder(private val color: Color, private val radius: Int) : AbstractBorder() {
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

    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
    override fun getBorderInsets(c: Component, insets: Insets): Insets {
        insets.set(1, 1, 1, 1)
        return insets
    }
}

private fun interface SimpleDocumentListener : javax.swing.event.DocumentListener {
    fun update()

    override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
    override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
    override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()
}
