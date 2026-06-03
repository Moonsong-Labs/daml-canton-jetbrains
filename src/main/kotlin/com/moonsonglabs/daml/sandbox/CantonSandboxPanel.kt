package com.moonsonglabs.daml.sandbox

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
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
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JOptionPane
import javax.swing.JTable
import javax.swing.JMenuItem
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.plaf.basic.BasicTabbedPaneUI
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.JTableHeader
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import java.nio.file.Path

class CantonSandboxPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val profiles = SandboxProfileService.getInstance(project)
    private val sessions = SandboxSessionService.getInstance(project)
    private val explorerNavigation = SandboxExplorerNavigationService.getInstance(project)
    private val graph = TopologyGraphPanel()

    private val profileComboModel = DefaultComboBoxModel<SandboxProfile>()
    private val profileCombo = ProfileComboBox(profileComboModel) { deleteProfile(it) }
    private val networkStatusBadge = JLabel()
    private val nameField = JBTextField()
    private val portBaseField = JBTextField()

    private val participantModel = tableModel("Participant", "Ledger", "Admin", "JSON")
    private val syncModel = tableModel("Sync Domain", "Sequencer", "Seq Public", "Seq Admin", "Mediator", "Med Admin")
    private val connectionModel = tableModel("Participant", "Sync Domain", "Connected")
    private val partyModel = tableModel("Participant", "Sync Domain", "Party Hint")

    private val participantTable = table(participantModel)
    private val syncTable = table(syncModel)
    private val connectionTable = table(connectionModel)
    private val partyTable = table(partyModel)
    private val participantEndpointConsole = ParticipantEndpointConsole(project, sessions)
    private val syncDomainEndpointConsole = SyncDomainEndpointConsole(project, sessions)
    private val partyField = JBTextField("Alice")
    private val partyParticipantCombo = JComboBox<String>()
    private val partySyncCombo = JComboBox<String>()
    private val componentPalette = TopologyComponentPalettePanel()

    private val logArea = JTextPane().apply {
        isEditable = false
        background = TopologyGraphTheme.canvas
        foreground = TopologyGraphTheme.detail
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(10)
    }

    private var currentProfile: SandboxProfile = profiles.selectedProfile()
    private var latestSession: SandboxSessionState = sessions.snapshot()
    private var currentTopologySelection: TopologyGraphPanel.Selection? = null
    private var sessionListener: Disposable? = null
    private var profileListener: Disposable? = null
    private var loadingProfile = false
    private var suppressTableNavigation = false

    init {
        background = TopologyGraphTheme.canvas
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        styleNetworkControls()
        add(toolbar(), BorderLayout.NORTH)
        add(mainContent(), BorderLayout.CENTER)
        wireTableNavigation()
        refreshProfiles()
        loadProfile(profiles.selectedProfile())
        profileListener = profiles.addListener { profile ->
            if (SwingUtilities.isEventDispatchThread()) {
                loadProfile(profile)
            } else {
                SwingUtilities.invokeLater { loadProfile(profile) }
            }
        }
        sessionListener = sessions.addListener { state ->
            latestSession = state
            SwingUtilities.invokeLater { renderSession(state) }
        }
    }

    private fun toolbar(): JComponent {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            background = TopologyGraphTheme.canvas
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        profileCombo.addActionListener {
            if (!loadingProfile && !profileCombo.isDeletingProfileFromPopup) (profileCombo.selectedItem as? SandboxProfile)?.let { profile ->
                profiles.selectProfile(profile.id)
            }
        }
        nameField.columns = 24
        portBaseField.columns = 6
        compactToolbarControl(profileCombo)
        compactToolbarControl(nameField)
        compactToolbarControl(portBaseField)
        listOf(nameField, portBaseField).forEach {
            it.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    saveProfileFields()
                }
            })
        }

        panel.add(row(
            networkStatusBadge,
            networkLabel("Profile"),
            profileCombo,
            button("New", AllIcons.General.Add) { loadProfile(profiles.createProfile()) },
            networkLabel("Name"),
            nameField,
            networkLabel("Port base"),
            portBaseField
        ), BorderLayout.CENTER)
        panel.add(row(
            button("Start", AllIcons.Actions.Execute) { saveProfileFields(); sessions.startLocal(currentProfile) },
            button("Stop", AllIcons.Actions.Suspend) { sessions.stop() },
            popupButton("More") { popup ->
                popup.add(menuItem("Validate Profile", AllIcons.Actions.Checked) { validateProfile() })
                popup.add(menuItem("Generate Files", AllIcons.FileTypes.Config) { doGenerate() })
                popup.add(menuItem("Refresh Health", AllIcons.Actions.Refresh) { sessions.refreshHealth(currentProfile) })
                popup.add(menuItem("Rebase Ports", AllIcons.Actions.Refresh) { rebasePorts() })
                popup.addSeparator()
                popup.add(menuItem("Clean Runtime Data", AllIcons.Actions.GC) { sessions.clean(currentProfile) })
                popup.add(menuItem("Delete Profile", AllIcons.General.Remove) { deleteProfile(currentProfile) })
            }
        ), BorderLayout.EAST)
        return panel
    }

    private fun topologyTab(): JComponent {
        graph.setSelectionListener { selection -> selectTopology(selection) }
        graph.setActivationListener { selection ->
            currentTopologySelection = selection
            componentPalette.select(selection)
            editSelectedTopologyNode()
        }
        graph.setPositionListener { selection, x, y -> updateTopologyPosition(selection, x, y) }
        graph.setConnectionListener { participantId, synchronizerId, connected ->
            setGraphConnection(participantId, synchronizerId, connected)
        }
        graph.setContextMenuListener { selection, point -> showTopologyContextMenu(selection, point.x, point.y) }
        graph.setDarDropListener { darPath, participantId -> assignDarToParticipant(darPath, participantId) }
        graph.setDarDropRejectedListener { message ->
            Messages.showInfoMessage(project, message, "DAR Assignment")
        }
        graph.setOverlayActionListener { selection, actionId ->
            if (selection is TopologyGraphPanel.Selection.Participant) {
                when (actionId) {
                    "manageDars" -> manageDarAssignments(selection.id)
                    "clearDars" -> clearDarsFromParticipant(selection.id)
                }
            }
        }
        componentPalette.setSelectionListener { selection -> selectTopology(selection) }
        val sidebar = TopologyComponentSidebarPanel(
            componentPalette,
            TopologyComponentSidebarPanel.Actions(
                addParticipant = { addParticipantFromGraph() },
                addSynchronizer = { addSynchronizerFromGraph() },
                arrange = { autoArrangeTopology() },
                selectionMenu = { showSelectionMenu(it) }
            )
        )
        val workspace = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, JBScrollPane(graph).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = TopologyGraphTheme.canvas
        }).apply {
            ui = TopologySplitPaneUI()
            resizeWeight = 0.0
            dividerLocation = 292
            dividerSize = 8
            isContinuousLayout = true
            border = BorderFactory.createEmptyBorder()
            background = TopologyGraphTheme.canvas
        }
        return JPanel(BorderLayout()).apply {
            background = TopologyGraphTheme.canvas
            add(workspace, BorderLayout.CENTER)
        }
    }

    private fun mainContent(): JComponent {
        val tabs = styledTabbedPane()
        tabs.addTab("Topology", topologyTab())
        tabs.addTab("Nodes", nodesTab())
        tabs.addTab("Parties", partiesTab())
        tabs.addTab("Logs", logsTab())
        return JPanel(BorderLayout()).apply {
            background = TopologyGraphTheme.canvas
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun nodesTab(): JComponent {
        val tabs = styledTabbedPane()
        tabs.addTab("Participants", participantsNodeTab())
        tabs.addTab("Sync Domains", syncDomainsNodeTab())
        tabs.addTab("Connections", networkCard(themedScrollPane(connectionTable)))
        return networkSurface(tabs)
    }

    private fun participantsNodeTab(): JComponent =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, networkCard(themedScrollPane(participantTable)).apply {
            preferredSize = Dimension(380, 100)
            minimumSize = Dimension(280, 80)
        }, participantEndpointConsole).apply {
            ui = TopologySplitPaneUI()
            resizeWeight = 0.0
            dividerLocation = 380
            dividerSize = 8
            isContinuousLayout = true
            border = BorderFactory.createEmptyBorder()
            background = TopologyGraphTheme.canvas
        }

    private fun syncDomainsNodeTab(): JComponent =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, networkCard(themedScrollPane(syncTable)).apply {
            preferredSize = Dimension(440, 100)
            minimumSize = Dimension(320, 80)
        }, syncDomainEndpointConsole).apply {
            ui = TopologySplitPaneUI()
            resizeWeight = 0.0
            dividerLocation = 440
            dividerSize = 8
            isContinuousLayout = true
            border = BorderFactory.createEmptyBorder()
            background = TopologyGraphTheme.canvas
        }

    private fun partiesTab(): JComponent {
        val editor = row(
            networkLabel("Party"),
            partyField,
            networkLabel("Participant"),
            partyParticipantCombo,
            networkLabel("Sync"),
            partySyncCombo,
            button("Allocate in Profile", AllIcons.General.Add) {
                val participant = currentProfile.participants.firstOrNull { it.name == partyParticipantCombo.selectedItem }
                val sync = currentProfile.synchronizers.firstOrNull { it.name == partySyncCombo.selectedItem }
                if (participant != null && sync != null && partyField.text.isNotBlank()) {
                    currentProfile.partyAllocations.add(PartyAllocation(partyField.text.trim(), participant.id, sync.id))
                    persistAndRefresh()
                }
            },
            button("Remove Selected", AllIcons.General.Remove) { removeSelectedPartyAllocation() }
        )
        return networkPanel(BorderLayout(8, 8)).apply {
            add(networkCard(themedScrollPane(partyTable)), BorderLayout.CENTER)
            add(networkCard(editor), BorderLayout.SOUTH)
        }
    }

    private fun logsTab(): JComponent =
        networkPanel(BorderLayout(8, 8)).apply {
            add(networkCard(row(
                networkLabel("Runtime log"),
                button("Clear", AllIcons.Actions.GC) { sessions.clearLog() }
            )), BorderLayout.NORTH)
            add(networkCard(themedScrollPane(logArea)), BorderLayout.CENTER)
        }

    private fun loadProfile(profile: SandboxProfile) {
        loadingProfile = true
        try {
            currentProfile = profile
            currentTopologySelection = null
            graph.select(null)
            nameField.text = profile.name
            portBaseField.text = profile.portBase.toString()
            graph.setProfile(profile)
            componentPalette.setProfile(profile)
            refreshProfiles()
            profileCombo.selectedIndex = profiles.profiles().indexOfFirst { it.id == profile.id }.coerceAtLeast(0)
            renderProfileTables()
            renderInspector(currentTopologySelection)
            renderSession(latestSession)
        } finally {
            loadingProfile = false
        }
    }

    private fun refreshProfiles() {
        val selectedId = currentProfile.id
        val wasLoading = loadingProfile
        loadingProfile = true
        try {
            profileComboModel.removeAllElements()
            profiles.profiles().forEach(profileComboModel::addElement)
            profileCombo.selectedIndex = profiles.profiles().indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        } finally {
            loadingProfile = wasLoading
        }
    }

    private fun deleteProfile(profile: SandboxProfile) {
        val allProfiles = profiles.profiles()
        if (allProfiles.size <= 1) {
            Messages.showInfoMessage(project, "Keep at least one sandbox profile.", "Managed Canton Sandboxes")
            refreshProfiles()
            return
        }

        val preferredProfileId = currentProfile.id.takeIf { it != profile.id }
        profiles.deleteProfile(profile.id)
        val nextProfile = preferredProfileId
            ?.let { id -> profiles.profiles().firstOrNull { it.id == id } }
            ?: profiles.selectedProfile()
        profiles.selectProfile(nextProfile.id)
        loadProfile(nextProfile)
    }

    private fun saveProfileFields() {
        currentProfile.name = nameField.text.trim().ifBlank { "Managed Canton Sandbox" }
        currentProfile.portBase = portBaseField.text.toIntOrNull() ?: currentProfile.portBase
        profiles.upsert(currentProfile)
        graph.setProfile(currentProfile)
        componentPalette.setProfile(currentProfile)
    }

    private fun persistAndRefresh() {
        saveProfileFields()
        profiles.upsert(currentProfile)
        loadProfile(currentProfile)
    }

    private fun doGenerate() {
        saveProfileFields()
        val profile = currentProfile
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { sessions.generate(profile) }
                .onSuccess { generated ->
                    SwingUtilities.invokeLater {
                        Messages.showInfoMessage(project, "Generated files under ${generated.root}", "Managed Canton Sandboxes")
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(project, error.message ?: "Generation failed", "Managed Canton Sandboxes")
                    }
                }
        }
    }

    private fun validateProfile() {
        saveProfileFields()
        val profile = currentProfile
        val generated = latestSession.generated.takeIf { latestSession.profileId == profile.id }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SandboxRuntimeValidator.getInstance(project).validate(profile, generated)
            SwingUtilities.invokeLater {
                Messages.showInfoMessage(project, buildString {
                    appendLine(result.message)
                    result.checks.forEach { check ->
                        appendLine("${if (check.ok) "OK" else "FAIL"} ${check.name}: ${check.detail}")
                    }
                }, "Sandbox Validation")
            }
        }
    }

    private fun addParticipant() {
        val next = currentProfile.participants.size + 1
        val participant = SandboxDefaults.participant(next, currentProfile.portBase)
        currentProfile.participants.add(participant)
        currentProfile.synchronizers.forEach { currentProfile.bindings.add(ParticipantSyncBinding(participant.id, it.id, true)) }
        persistAndRefresh()
    }

    private fun addParticipantFromGraph() {
        addParticipant()
        currentProfile.participants.lastOrNull()?.let {
            selectTopology(TopologyGraphPanel.Selection.Participant(it.id))
        }
    }

    private fun addSynchronizerFromGraph() {
        addSynchronizer()
        currentProfile.synchronizers.lastOrNull()?.let {
            selectTopology(TopologyGraphPanel.Selection.Synchronizer(it.id))
        }
    }

    private fun showSelectionMenu(source: JButton) {
        JPopupMenu().apply {
            add(menuItem("Edit Selected", AllIcons.Actions.Edit) { editSelectedTopologyNode() })
            add(menuItem("Edit Connection", AllIcons.Actions.ToggleVisibility) { editGraphConnection() })
            add(menuItem("Remove Selected", AllIcons.General.Remove) { removeSelectedTopologyNode() })
        }.show(source, 0, source.height)
    }

    private fun editSelectedTopologyNode() {
        when (val selection = currentTopologySelection) {
            is TopologyGraphPanel.Selection.Participant -> {
                selectParticipantRow(selection.id)
                editSelectedParticipant()
            }
            is TopologyGraphPanel.Selection.Synchronizer -> {
                selectSynchronizerRow(selection.id)
                editSelectedSynchronizer()
            }
            null -> Messages.showInfoMessage(project, "Select a topology node first.", "Managed Canton Sandboxes")
        }
    }

    private fun removeSelectedTopologyNode() {
        when (val selection = currentTopologySelection) {
            is TopologyGraphPanel.Selection.Participant -> removeParticipantById(selection.id)
            is TopologyGraphPanel.Selection.Synchronizer -> removeSynchronizerById(selection.id)
            null -> Messages.showInfoMessage(project, "Select a topology node first.", "Managed Canton Sandboxes")
        }
    }

    private fun showTopologyContextMenu(selection: TopologyGraphPanel.Selection, x: Int, y: Int) {
        currentTopologySelection = selection
        componentPalette.select(selection)
        renderInspector(selection)
        if (selection !is TopologyGraphPanel.Selection.Participant) return
        JPopupMenu().apply {
            add(menuItem("Assign DARs...", AllIcons.Actions.Edit) {
                manageDarAssignments(selection.id)
            })
            add(menuItem("Clear DARs from Participant", AllIcons.Actions.GC) {
                clearDarsFromParticipant(selection.id)
            })
            add(menuItem("Copy DARs from...", AllIcons.Actions.Copy) {
                copyDarsFromParticipant(selection.id)
            })
            addSeparator()
            add(menuItem("See in Explorer", AllIcons.Actions.ToggleVisibility) {
                showParticipantInExplorer(selection.id)
            })
        }.show(graph, x, y)
    }

    private fun manageDarAssignments(participantId: String? = null) {
        val dialog = DarAssignmentDialog(project, currentProfile)
        if (!dialog.showAndGet()) return
        currentProfile.darAssignments = dialog.resultAssignments()
        persistDarAssignmentChange(participantId)
    }

    private fun assignDarToParticipant(rawDarPath: String, participantId: String) {
        if (currentProfile.participant(participantId) == null) {
            return
        }
        val path = Path.of(rawDarPath).toAbsolutePath().normalize()
        if (!path.fileName.toString().endsWith(".dar", ignoreCase = true)) {
            return
        }
        val profilePath = SandboxPaths.relativeProfilePath(path.toString(), currentProfile, projectRoot())
        val assignment = currentProfile.darAssignments.firstOrNull { assignment ->
            runCatching {
                SandboxPaths.resolveProfilePath(assignment.darPath, currentProfile, projectRoot()).toAbsolutePath().normalize() == path
            }.getOrDefault(false)
        } ?: DarAssignment(profilePath, mutableListOf()).also { currentProfile.darAssignments.add(it) }
        if (participantId !in assignment.participantIds) {
            assignment.participantIds.add(participantId)
        }
        persistDarAssignmentChange(participantId)
    }

    private fun clearDarsFromParticipant(participantId: String) {
        currentProfile.darAssignments.forEach { it.participantIds.removeIf { id -> id == participantId } }
        persistDarAssignmentChange(participantId)
    }

    private fun copyDarsFromParticipant(targetParticipantId: String) {
        val candidates = currentProfile.participants.filter { it.id != targetParticipantId }
        if (candidates.isEmpty()) return
        val names = candidates.map { it.name }.toTypedArray()
        val selected = Messages.showEditableChooseDialog(
            "Copy DAR assignments from which participant?",
            "Copy DAR Assignments",
            null,
            names,
            names.firstOrNull(),
            null
        ) ?: return
        val source = candidates.firstOrNull { it.name == selected } ?: return
        currentProfile.darAssignments.forEach { assignment ->
            assignment.participantIds.removeIf { it == targetParticipantId }
            if (source.id in assignment.participantIds) assignment.participantIds.add(targetParticipantId)
        }
        persistDarAssignmentChange(targetParticipantId)
    }

    private fun persistDarAssignmentChange(participantId: String?) {
        normalizeDarAssignments(currentProfile)
        profiles.upsert(currentProfile)
        val generated = regenerateManagedSandboxFiles()
        renderProfileTables()
        participantId?.let { selectTopology(TopologyGraphPanel.Selection.Participant(it)) }
        if (generated == null) {
            Messages.showErrorDialog(project, "DAR assignments were saved, but generated files could not be updated.", "Managed Canton Sandboxes")
        }
    }

    private fun regenerateManagedSandboxFiles(): SandboxGeneratedFiles? =
        runCatching {
            val runtimeProfile = SandboxProjectPaths.runtimeProfile(
                currentProfile,
                com.moonsonglabs.daml.workspace.DamlWorkspaceService.getInstance(project)
            )
            SandboxGenerator(projectRoot()).updateGeneratedFiles(runtimeProfile)
        }.getOrNull()

    private fun projectRoot(): Path? =
        com.moonsonglabs.daml.workspace.DamlWorkspaceService.getInstance(project).projectRoot()

    private fun showParticipantInExplorer(participantId: String) {
        if (currentProfile.participant(participantId) == null) return
        saveProfileFields()
        explorerNavigation.showParticipant(
            currentProfile,
            participantId,
            refresh = latestSession.status == SandboxSessionStatus.RUNNING
        )
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SANDBOX_TOOL_WINDOW_ID) ?: return
        val explorerContent = toolWindow.contentManager.contents
            .firstOrNull { it.displayName == SANDBOX_EXPLORER_CONTENT_NAME }
        toolWindow.activate(Runnable {
            if (explorerContent != null) toolWindow.contentManager.setSelectedContent(explorerContent)
        })
    }

    private fun editGraphConnection() {
        val participants = currentProfile.participants
        val synchronizers = currentProfile.synchronizers
        if (participants.isEmpty() || synchronizers.isEmpty()) return

        val participantCombo = JComboBox(participants.map { it.name }.toTypedArray())
        val syncCombo = JComboBox(synchronizers.map { it.name }.toTypedArray())
        val selectedParticipant = (currentTopologySelection as? TopologyGraphPanel.Selection.Participant)?.id
        selectedParticipant?.let { id ->
            participants.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { participantCombo.selectedIndex = it }
        }
        selectedSynchronizerId(currentTopologySelection)?.let { id ->
            synchronizers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { syncCombo.selectedIndex = it }
        }
        val connected = JCheckBox("Connected", true)
        fun refreshConnectionState() {
            val participant = participants.getOrNull(participantCombo.selectedIndex) ?: return
            val sync = synchronizers.getOrNull(syncCombo.selectedIndex) ?: return
            connected.isSelected = currentProfile.bindings
                .firstOrNull { it.participantId == participant.id && it.synchronizerId == sync.id }
                ?.connected ?: false
        }
        participantCombo.addActionListener { refreshConnectionState() }
        syncCombo.addActionListener { refreshConnectionState() }
        refreshConnectionState()

        val form = JPanel(GridBagLayout()).apply {
            addLabeled("Participant", participantCombo, 0)
            addLabeled("Sync domain", syncCombo, 1)
            addLabeled("State", connected, 2)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Edit Connection", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val participant = participants.getOrNull(participantCombo.selectedIndex) ?: return
        val sync = synchronizers.getOrNull(syncCombo.selectedIndex) ?: return
        val binding = currentProfile.bindings.firstOrNull { it.participantId == participant.id && it.synchronizerId == sync.id }
        if (binding == null) {
            currentProfile.bindings.add(ParticipantSyncBinding(participant.id, sync.id, connected.isSelected))
        } else {
            binding.connected = connected.isSelected
        }
        persistAndRefresh()
        selectTopology(TopologyGraphPanel.Selection.Participant(participant.id))
    }

    private fun updateTopologyPosition(selection: TopologyGraphPanel.Selection, x: Int, y: Int) {
        val nodeId = topologyNodeId(selection)
        currentProfile.topologyPositions.removeIf { it.nodeId == nodeId }
        currentProfile.topologyPositions.add(TopologyNodePosition(nodeId, x.coerceAtLeast(0), y.coerceAtLeast(0)))
        profiles.upsert(currentProfile)
        graph.setProfile(currentProfile)
        currentTopologySelection = selection
        graph.select(selection)
        graph.setSelectionDetails(null)
    }

    private fun autoArrangeTopology() {
        val selection = currentTopologySelection
        currentProfile.topologyPositions.clear()
        profiles.upsert(currentProfile)
        graph.setProfile(currentProfile)
        renderProfileTables()
        selectTopology(selection)
    }

    private fun setGraphConnection(participantId: String, synchronizerId: String, connected: Boolean) {
        val binding = currentProfile.bindings.firstOrNull {
            it.participantId == participantId && it.synchronizerId == synchronizerId
        }
        if (binding == null) {
            currentProfile.bindings.add(ParticipantSyncBinding(participantId, synchronizerId, connected))
        } else {
            binding.connected = connected
        }
        profiles.upsert(currentProfile)
        renderProfileTables()
        selectTopology(TopologyGraphPanel.Selection.Participant(participantId))
    }

    private fun editSelectedParticipant() {
        val row = participantTable.selectedRow
        val participant = currentProfile.participants.getOrNull(row) ?: return
        val name = JBTextField(participant.name)
        val ledgerPort = JBTextField(participant.ledgerPort.toString())
        val adminPort = JBTextField(participant.adminPort.toString())
        val jsonPort = JBTextField(participant.jsonPort.toString())
        val form = JPanel(GridBagLayout()).apply {
            addLabeled("Name", name, 0)
            addLabeled("Ledger port", ledgerPort, 1)
            addLabeled("Admin port", adminPort, 2)
            addLabeled("JSON port", jsonPort, 3)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Edit Participant", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val newName = name.text.trim()
        val ports = listOf(ledgerPort.text.toIntOrNull(), adminPort.text.toIntOrNull(), jsonPort.text.toIntOrNull())
        if (!isCantonIdentifier(newName) || ports.any { it == null || it !in 1..65535 }) {
            Messages.showErrorDialog(project, "Use a Canton identifier for the name and valid TCP ports.", "Invalid Participant")
            return
        }
        if (currentProfile.participants.any { it.id != participant.id && it.name == newName }) {
            Messages.showErrorDialog(project, "Another participant already uses '$newName'.", "Duplicate Participant")
            return
        }
        participant.name = newName
        participant.ledgerPort = ports[0]!!
        participant.adminPort = ports[1]!!
        participant.jsonPort = ports[2]!!
        persistAndRefresh()
    }

    private fun addSynchronizer() {
        val next = currentProfile.synchronizers.size + 1
        val sync = SandboxDefaults.synchronizer(next, currentProfile.portBase)
        currentProfile.synchronizers.add(sync)
        currentProfile.participants.forEach { currentProfile.bindings.add(ParticipantSyncBinding(it.id, sync.id, true)) }
        persistAndRefresh()
    }

    private fun editSelectedSynchronizer() {
        val row = syncTable.selectedRow
        val sync = currentProfile.synchronizers.getOrNull(row) ?: return
        val name = JBTextField(sync.name)
        val sequencerName = JBTextField(sync.sequencer.name)
        val sequencerPublic = JBTextField(sync.sequencer.publicPort.toString())
        val sequencerAdmin = JBTextField(sync.sequencer.adminPort.toString())
        val mediatorName = JBTextField(sync.mediator.name)
        val mediatorAdmin = JBTextField(sync.mediator.adminPort.toString())
        val form = JPanel(GridBagLayout()).apply {
            addLabeled("Sync domain", name, 0)
            addLabeled("Sequencer", sequencerName, 1)
            addLabeled("Sequencer public", sequencerPublic, 2)
            addLabeled("Sequencer admin", sequencerAdmin, 3)
            addLabeled("Mediator", mediatorName, 4)
            addLabeled("Mediator admin", mediatorAdmin, 5)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Edit Sync Domain", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val newName = name.text.trim()
        val newSequencer = sequencerName.text.trim()
        val newMediator = mediatorName.text.trim()
        val ports = listOf(sequencerPublic.text.toIntOrNull(), sequencerAdmin.text.toIntOrNull(), mediatorAdmin.text.toIntOrNull())
        if (listOf(newName, newSequencer, newMediator).any { !isCantonIdentifier(it) } || ports.any { it == null || it !in 1..65535 }) {
            Messages.showErrorDialog(project, "Use Canton identifiers for names and valid TCP ports.", "Invalid Sync Domain")
            return
        }
        if (currentProfile.synchronizers.any { it.id != sync.id && it.name == newName }) {
            Messages.showErrorDialog(project, "Another sync domain already uses '$newName'.", "Duplicate Sync Domain")
            return
        }
        val nodeNames = currentProfile.synchronizers
            .filter { it.id != sync.id }
            .flatMap { listOf(it.sequencer.name, it.mediator.name) }
        if (newSequencer in nodeNames || newMediator in nodeNames || newSequencer == newMediator) {
            Messages.showErrorDialog(project, "Sequencer and mediator names must be unique.", "Duplicate Node")
            return
        }
        sync.name = newName
        sync.sequencer.name = newSequencer
        sync.sequencer.publicPort = ports[0]!!
        sync.sequencer.adminPort = ports[1]!!
        sync.mediator.name = newMediator
        sync.mediator.adminPort = ports[2]!!
        persistAndRefresh()
    }

    private fun removeParticipantById(id: String) {
        if (currentProfile.participants.size <= 1) {
            Messages.showErrorDialog(project, "A sandbox needs at least one participant.", "Cannot Remove Participant")
            return
        }
        currentProfile.participants.removeIf { it.id == id }
        currentProfile.bindings.removeIf { it.participantId == id }
        currentProfile.darAssignments.forEach { it.participantIds.removeIf { pid -> pid == id } }
        currentProfile.partyAllocations.removeIf { it.participantId == id }
        persistAndRefresh()
    }

    private fun removeSynchronizerById(id: String) {
        if (SandboxDefaults.isSharedSynchronizer(id, currentProfile.synchronizer(id)?.name ?: id)) {
            Messages.showErrorDialog(
                project,
                "The global sync domain is the default shared route for cross-participant workflows. You can disconnect participants from it, but it cannot be removed.",
                "Cannot Remove Global Sync Domain"
            )
            return
        }
        if (currentProfile.synchronizers.size <= 1) {
            Messages.showErrorDialog(project, "A sandbox needs at least one sync domain.", "Cannot Remove Sync Domain")
            return
        }
        currentProfile.synchronizers.removeIf { it.id == id }
        currentProfile.bindings.removeIf { it.synchronizerId == id }
        currentProfile.partyAllocations.removeIf { it.synchronizerId == id }
        persistAndRefresh()
    }

    private fun selectParticipantRow(id: String) {
        val index = currentProfile.participants.indexOfFirst { it.id == id }
        if (index >= 0) {
            withoutTableNavigation {
                participantTable.selectionModel.setSelectionInterval(index, index)
            }
            updateParticipantEndpointConsole()
        }
    }

    private fun selectSynchronizerRow(id: String) {
        val index = currentProfile.synchronizers.indexOfFirst { it.id == id }
        if (index >= 0) {
            withoutTableNavigation {
                syncTable.selectionModel.setSelectionInterval(index, index)
            }
        }
    }

    private fun withoutTableNavigation(action: () -> Unit) {
        val previous = suppressTableNavigation
        suppressTableNavigation = true
        try {
            action()
        } finally {
            suppressTableNavigation = previous
        }
    }

    private fun selectedSynchronizerId(selection: TopologyGraphPanel.Selection?): String? =
        when (selection) {
            is TopologyGraphPanel.Selection.Synchronizer -> selection.id
            else -> null
        }

    private fun topologyNodeId(selection: TopologyGraphPanel.Selection): String =
        when (selection) {
            is TopologyGraphPanel.Selection.Participant -> selection.id
            is TopologyGraphPanel.Selection.Synchronizer -> selection.id
        }

    private fun rebasePorts() {
        val base = portBaseField.text.toIntOrNull() ?: return
        currentProfile.portBase = base
        currentProfile.participants = currentProfile.participants.mapIndexed { index, old ->
            SandboxDefaults.participant(index + 1, base).also {
                it.id = old.id
                it.name = old.name
            }
        }.toMutableList()
        currentProfile.synchronizers = currentProfile.synchronizers.mapIndexed { index, old ->
            SandboxDefaults.synchronizer(index + 1, base).also {
                it.id = old.id
                it.name = old.name
            }
        }.toMutableList()
        persistAndRefresh()
    }

    private fun removeSelectedPartyAllocation() {
        val row = partyTable.selectedRow
        if (row < 0) return
        currentProfile.partyAllocations.getOrNull(row)?.let { currentProfile.partyAllocations.remove(it) }
        persistAndRefresh()
    }

    private fun renderProfileTables() {
        reset(participantModel)
        currentProfile.participants.forEach { participantModel.addRow(rowData(it.name, it.ledgerPort, it.adminPort, it.jsonPort)) }
        if (participantTable.rowCount > 0 && participantTable.selectedRow < 0) {
            withoutTableNavigation {
                participantTable.selectionModel.setSelectionInterval(0, 0)
            }
        }
        reset(syncModel)
        currentProfile.synchronizers.forEach {
            syncModel.addRow(rowData(it.name, it.sequencer.name, it.sequencer.publicPort, it.sequencer.adminPort, it.mediator.name, it.mediator.adminPort))
        }
        if (syncTable.rowCount > 0 && syncTable.selectedRow < 0) {
            withoutTableNavigation {
                syncTable.selectionModel.setSelectionInterval(0, 0)
            }
        }
        reset(connectionModel)
        currentProfile.bindings.forEach {
            connectionModel.addRow(rowData(currentProfile.participant(it.participantId)?.name.orEmpty(), currentProfile.synchronizer(it.synchronizerId)?.name.orEmpty(), it.connected))
        }
        reset(partyModel)
        currentProfile.partyAllocations.forEach {
            partyModel.addRow(rowData(currentProfile.participant(it.participantId)?.name.orEmpty(), currentProfile.synchronizer(it.synchronizerId)?.name.orEmpty(), it.partyHint))
        }
        graph.setProfile(currentProfile)
        refreshPartyCombos()
        updateParticipantEndpointConsole()
        updateSyncDomainEndpointConsole()
    }

    private fun selectedParticipantId(): String? =
        currentProfile.participants.getOrNull(participantTable.selectedRow)?.id
            ?: currentProfile.participants.firstOrNull()?.id

    private fun selectedSyncId(): String? =
        currentProfile.synchronizers.getOrNull(syncTable.selectedRow)?.id
            ?: currentProfile.synchronizers.firstOrNull()?.id

    private fun updateParticipantEndpointConsole() {
        participantEndpointConsole.setContext(currentProfile, latestSession, selectedParticipantId())
    }

    private fun updateSyncDomainEndpointConsole() {
        syncDomainEndpointConsole.setContext(currentProfile, latestSession, selectedSyncId())
    }

    private fun refreshPartyCombos() {
        val participantSelection = partyParticipantCombo.selectedItem as? String
        val syncSelection = partySyncCombo.selectedItem as? String
        val participants = currentProfile.participants.map { it.name }
        val synchronizers = currentProfile.synchronizers.map { it.name }
        partyParticipantCombo.model = DefaultComboBoxModel(participants.toTypedArray())
        partySyncCombo.model = DefaultComboBoxModel(synchronizers.toTypedArray())
        participantSelection?.takeIf { it in participants }?.let { partyParticipantCombo.selectedItem = it }
        syncSelection?.takeIf { it in synchronizers }?.let { partySyncCombo.selectedItem = it }
    }

    private fun renderSession(state: SandboxSessionState) {
        renderLog(state.log)
        logArea.caretPosition = logArea.document.length
        val belongsToCurrentProfile = state.profileId.isBlank() || state.profileId == currentProfile.id
        val effectiveState = if (belongsToCurrentProfile) {
            state
        } else {
            state.copy(status = SandboxSessionStatus.STOPPED, health = emptyList(), message = "No running session for this profile")
        }
        renderNetworkStatus(effectiveState)
        graph.setRuntimeState(
            effectiveState.status,
            effectiveState.health,
            if (belongsToCurrentProfile) state.log.hashCode() else 0
        )
        updateParticipantEndpointConsole()
        updateSyncDomainEndpointConsole()
        renderInspector(currentTopologySelection)
    }

    private fun renderNetworkStatus(state: SandboxSessionState) {
        val color = networkStatusColor(state.status)
        networkStatusBadge.text = "Status: ${state.status.presentableName}"
        networkStatusBadge.foreground = color
        networkStatusBadge.background = TopologyGraphTheme.panel
        networkStatusBadge.font = networkStatusBadge.font.deriveFont(Font.BOLD, 12f)
        networkStatusBadge.isOpaque = true
        networkStatusBadge.border = BorderFactory.createCompoundBorder(
            NetworkRoundBorder(color, 12),
            JBUI.Borders.empty(5, 12)
        )
        networkStatusBadge.toolTipText = state.message.ifBlank { state.status.presentableName }
    }

    private fun renderInspector(selection: TopologyGraphPanel.Selection?) {
        val text = when (selection) {
            is TopologyGraphPanel.Selection.Participant -> currentProfile.participant(selection.id)?.let { participant ->
                participantInspectorText(currentProfile, participant, healthLine(participant.id))
            }
            is TopologyGraphPanel.Selection.Synchronizer -> currentProfile.synchronizer(selection.id)?.let { sync ->
                """
                |${TopologyNodeIcons.SYNCHRONIZER} Sync Domain - ${sync.name}
                |
                |Sequencer: ${sync.sequencer.name}
                |Mediator: ${sync.mediator.name}
                |
                |Connected participants:
                |${currentProfile.bindings.filter { it.synchronizerId == sync.id && it.connected }.mapNotNull { currentProfile.participant(it.participantId)?.name }.joinToString("\n").ifBlank { "None" }}
                |""".trimMargin()
            }
            null -> null
        }
        val actions = if (selection is TopologyGraphPanel.Selection.Participant && text != null) {
            listOf(
                TopologyGraphPanel.OverlayAction("manageDars", "Add / Manage DARs"),
                TopologyGraphPanel.OverlayAction("clearDars", "Clear DARs")
            )
        } else {
            emptyList()
        }
        graph.setSelectionDetails(text, actions)
    }

    private fun selectTopology(selection: TopologyGraphPanel.Selection?) {
        currentTopologySelection = selection
        graph.select(selection)
        componentPalette.select(selection)
        renderInspector(selection)
    }

    private fun healthLine(participantId: String): String {
        if (latestSession.profileId.isNotBlank() && latestSession.profileId != currentProfile.id) return "not checked"
        val snapshot = latestSession.health.firstOrNull { it.endpoint.nodeId == participantId && it.endpoint.kind == "json" }
            ?: return "not checked"
        return "live=${snapshot.live.statusText()} ready=${snapshot.ready.statusText()}"
    }

    private fun Boolean.statusText(): String = if (this) "ok" else "down"

    private fun wireTableNavigation() {
        participantTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateParticipantEndpointConsole()
                if (!suppressTableNavigation) {
                    currentProfile.participants.getOrNull(participantTable.selectedRow)?.let { participant ->
                        selectTopology(TopologyGraphPanel.Selection.Participant(participant.id))
                    }
                }
            }
        }
        syncTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateSyncDomainEndpointConsole()
                if (!suppressTableNavigation) {
                    currentProfile.synchronizers.getOrNull(syncTable.selectedRow)?.let { sync ->
                        selectTopology(TopologyGraphPanel.Selection.Synchronizer(sync.id))
                    }
                }
            }
        }
        participantTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) editSelectedParticipant()
            }
        })
        syncTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) editSelectedSynchronizer()
            }
        })
    }

    private fun styleNetworkControls() {
        listOf(nameField, portBaseField, partyField).forEach(::styleTextField)
        listOf(partyParticipantCombo, partySyncCombo).forEach(::styleComboBox)
    }

    private fun styledTabbedPane(): JTabbedPane =
        JTabbedPane().apply {
            ui = NetworkTabbedPaneUI()
            background = TopologyGraphTheme.canvas
            foreground = TopologyGraphTheme.text
            border = BorderFactory.createEmptyBorder()
            isOpaque = false
        }

    private fun networkSurface(content: JComponent): JComponent =
        networkPanel(BorderLayout()).apply {
            add(content, BorderLayout.CENTER)
        }

    private fun networkPanel(layout: LayoutManager): JPanel =
        JPanel(layout).apply {
            background = TopologyGraphTheme.canvas
            foreground = TopologyGraphTheme.text
        }

    private fun networkCard(content: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            background = TopologyGraphTheme.panel
            foreground = TopologyGraphTheme.text
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder),
                JBUI.Borders.empty(10)
            )
            add(content, BorderLayout.CENTER)
        }

    private fun networkLabel(text: String): JLabel =
        JLabel(text).apply {
            foreground = TopologyGraphTheme.detail
            border = JBUI.Borders.emptyRight(2)
            font = font.deriveFont(Font.PLAIN, 12f)
        }

    private fun themedScrollPane(component: JComponent): JBScrollPane =
        JBScrollPane(component).apply {
            border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
            background = TopologyGraphTheme.panel
            viewport.background = TopologyGraphTheme.panel
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

    private fun styleTextField(field: JBTextField) {
        field.foreground = TopologyGraphTheme.text
        field.background = TopologyGraphTheme.panel
        field.caretColor = TopologyGraphTheme.hover
        field.isOpaque = false
        field.border = BorderFactory.createCompoundBorder(
            NetworkRoundBorder(TopologyGraphTheme.panelBorder, 12),
            JBUI.Borders.empty(4, 9)
        )
    }

    private fun compactToolbarControl(component: JComponent) {
        val width = component.preferredSize.width.coerceAtLeast(34)
        val size = Dimension(width, 34)
        component.preferredSize = size
        component.minimumSize = Dimension(32, 34)
        component.maximumSize = Dimension(Int.MAX_VALUE, 34)
    }

    private fun styleComboBox(combo: JComboBox<String>) {
        combo.foreground = TopologyGraphTheme.text
        combo.background = TopologyGraphTheme.panel
        combo.border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
        combo.renderer = NetworkListCellRenderer()
    }

    private fun renderLog(log: String) {
        val document = logArea.styledDocument
        document.remove(0, document.length)
        val lines = if (log.isEmpty()) listOf("") else log.split('\n')
        for ((index, line) in lines.withIndex()) {
            val attributes = SimpleAttributeSet().apply {
                StyleConstants.setForeground(this, networkLogLineColor(line))
                StyleConstants.setFontFamily(this, Font.MONOSPACED)
                StyleConstants.setFontSize(this, 12)
            }
            document.insertString(document.length, line + if (index < lines.lastIndex) "\n" else "", attributes)
        }
    }

    private fun table(model: DefaultTableModel): JBTable =
        JBTable(model).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            rowHeight = 30
            intercellSpacing = Dimension(0, 0)
            gridColor = networkAlpha(TopologyGraphTheme.panelBorder, 150)
            showHorizontalLines = true
            showVerticalLines = true
            background = TopologyGraphTheme.panel
            foreground = TopologyGraphTheme.text
            selectionBackground = networkAlpha(TopologyGraphTheme.selected, 90)
            selectionForeground = TopologyGraphTheme.text
            setDefaultRenderer(Object::class.java, NetworkTableCellRenderer())
            styleTableHeader(tableHeader)
        }

    private fun tableModel(vararg columns: String): DefaultTableModel =
        object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

    private fun reset(model: DefaultTableModel) {
        model.rowCount = 0
    }

    private fun JPanel.addLabeled(label: String, component: JComponent, y: Int) {
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = y
            anchor = GridBagConstraints.WEST
            insets = java.awt.Insets(2, 2, 2, 6)
        }
        val fieldConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = y
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = java.awt.Insets(2, 2, 2, 2)
        }
        add(JLabel(label), labelConstraints)
        add(component, fieldConstraints)
    }

    private fun styleTableHeader(header: JTableHeader) {
        header.background = TopologyGraphTheme.canvas
        header.foreground = TopologyGraphTheme.warning
        header.font = header.font.deriveFont(Font.BOLD, 12f)
        header.border = BorderFactory.createMatteBorder(0, 0, 1, 0, TopologyGraphTheme.panelBorder)
        header.defaultRenderer = NetworkTableHeaderRenderer(header.defaultRenderer)
    }

    private fun row(vararg components: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            background = TopologyGraphTheme.canvas
            foreground = TopologyGraphTheme.text
            components.forEach(::add)
        }

    private fun button(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): JButton =
        NetworkButton(text, icon).apply {
            toolTipText = text
            addActionListener { action() }
        }

    private fun popupButton(text: String, builder: (JPopupMenu) -> Unit): JButton =
        NetworkButton(text, null).apply {
            toolTipText = text
            addActionListener {
                val popup = JPopupMenu().apply {
                    background = TopologyGraphTheme.panel
                    border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
                }
                builder(popup)
                popup.show(this, 0, height)
            }
        }

    private fun menuItem(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): JMenuItem =
        JMenuItem(text, icon).apply {
            isOpaque = true
            background = TopologyGraphTheme.panel
            foreground = TopologyGraphTheme.text
            border = JBUI.Borders.empty(5, 10)
            addActionListener { action() }
        }

    private fun rowData(vararg values: Any?): Array<Any?> = arrayOf(*values)

    private fun isCantonIdentifier(value: String): Boolean =
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))

    override fun dispose() {
        sessionListener?.dispose()
        sessionListener = null
        profileListener?.dispose()
        profileListener = null
    }
}

internal fun participantInspectorText(
    profile: SandboxProfile,
    participant: ParticipantNode,
    health: String
): String {
    val uploadedDars = profile.assignedDarFileNames(participant.id)
    val darLines = when {
        uploadedDars.isNotEmpty() -> uploadedDars.joinToString("\n")
        profile.hasUploadedDarAssignments() -> "! No DARs assigned to this participant."
        else -> "! No DARs assigned to any participant."
    }
    val parties = profile.partyAllocations
        .filter { it.participantId == participant.id }
        .joinToString("\n") { it.partyHint }
    return """
        |${TopologyNodeIcons.PARTICIPANT} Participant - ${participant.name}
        |
        |Uploaded DARs:
        |$darLines
        |
        |Ledger API: grpc://127.0.0.1:${participant.ledgerPort}
        |Admin API: grpc://127.0.0.1:${participant.adminPort}
        |JSON API: http://127.0.0.1:${participant.jsonPort}
        |Health: $health
        |
        |Connected sync domains:
        |${profile.connectedSynchronizers(participant.id).joinToString("\n") { it.name }.ifBlank { "None" }}
        |
        |Parties:
        |${parties.ifBlank { "None allocated" }}
        |""".trimMargin()
}

internal fun networkParticipantColor(): Color = TopologyGraphTheme.participantBorder

internal fun networkSyncColor(name: String): Color =
    if (name == SandboxDefaults.SHARED_SYNCHRONIZER_NAME) TopologyGraphTheme.globalSyncBorder else TopologyGraphTheme.syncBorder

internal fun networkConnectionColor(connected: Boolean): Color =
    if (connected) TopologyGraphTheme.syncBorder else Color(0xFF5C7A)

internal fun networkStatusColor(status: SandboxSessionStatus): Color =
    when (status) {
        SandboxSessionStatus.RUNNING -> TopologyGraphTheme.syncBorder
        SandboxSessionStatus.STARTING,
        SandboxSessionStatus.GENERATING,
        SandboxSessionStatus.STOPPING -> TopologyGraphTheme.warning
        SandboxSessionStatus.FAILED -> Color(0xFF5C7A)
        SandboxSessionStatus.STOPPED -> Color(0xFF6D86)
    }

internal fun networkLogLineColor(line: String): Color {
    val lower = line.lowercase()
    return when {
        "error" in lower || "exception" in lower || "failed" in lower -> Color(0xFF5C7A)
        "warn" in lower || "warning" in lower -> TopologyGraphTheme.warning
        "ready" in lower || "running" in lower || "serving" in lower || "started" in lower -> TopologyGraphTheme.syncBorder
        else -> TopologyGraphTheme.detail
    }
}

internal fun networkAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

private class NetworkTableHeaderRenderer(
    private val delegate: javax.swing.table.TableCellRenderer
) : javax.swing.table.TableCellRenderer {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component =
        delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
            background = TopologyGraphTheme.canvas
            foreground = TopologyGraphTheme.warning
            font = font.deriveFont(Font.BOLD, 12f)
            if (this is JComponent) {
                isOpaque = true
                border = JBUI.Borders.empty(0, 10)
            }
            if (this is JLabel) {
                horizontalAlignment = SwingConstants.LEFT
            }
        }
}

private class NetworkTableCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val modelColumn = table.convertColumnIndexToModel(column)
        val columnName = table.model.getColumnName(modelColumn)
        val textValue = value?.toString().orEmpty()

        isOpaque = true
        border = JBUI.Borders.empty(0, 10)
        horizontalAlignment = SwingConstants.LEFT
        font = table.font.deriveFont(Font.PLAIN)
        background = if (isSelected) networkAlpha(TopologyGraphTheme.selected, 105) else TopologyGraphTheme.panel
        foreground = TopologyGraphTheme.text

        when (columnName) {
            "Participant" -> {
                foreground = networkParticipantColor()
                font = font.deriveFont(Font.BOLD)
            }
            "Sync Domain" -> {
                foreground = networkSyncColor(textValue)
                font = font.deriveFont(Font.BOLD)
            }
            "Connected" -> {
                val connected = value == true || textValue.equals("true", ignoreCase = true) || textValue.equals("connected", ignoreCase = true)
                text = if (connected) "connected" else "disconnected"
                foreground = networkConnectionColor(connected)
                background = if (isSelected) networkAlpha(TopologyGraphTheme.selected, 105) else networkAlpha(networkConnectionColor(connected), 34)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(4, 8, 4, 8, TopologyGraphTheme.panel),
                    BorderFactory.createLineBorder(networkAlpha(networkConnectionColor(connected), 165))
                )
                horizontalAlignment = SwingConstants.CENTER
            }
            "Ledger", "Admin", "JSON", "Seq Public", "Seq Admin", "Med Admin" -> {
                foreground = TopologyGraphTheme.detail
                horizontalAlignment = SwingConstants.RIGHT
            }
            "Party Hint" -> foreground = TopologyGraphTheme.warning
            "Sequencer", "Mediator" -> foreground = TopologyGraphTheme.text
        }
        return this
    }
}

internal class NetworkListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        isOpaque = true
        border = JBUI.Borders.empty(4, 10)
        background = if (isSelected) networkAlpha(TopologyGraphTheme.selected, 90) else TopologyGraphTheme.panel
        foreground = if (isSelected) TopologyGraphTheme.text else TopologyGraphTheme.participantBorder
        font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
        return this
    }
}

internal class NetworkButton(text: String, icon: javax.swing.Icon?) : JButton(text, icon) {
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
        horizontalAlignment = SwingConstants.CENTER
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
                model.isPressed -> networkAlpha(TopologyGraphTheme.selected, 70)
                model.isRollover -> networkAlpha(TopologyGraphTheme.hover, 34)
                else -> TopologyGraphTheme.panel
            }
            g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g2.color = if (model.isRollover) TopologyGraphTheme.hover else TopologyGraphTheme.panelBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

internal class NetworkRoundBorder(
    private val color: Color,
    private val radius: Int
) : javax.swing.border.AbstractBorder() {
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

private class TopologyComponentSidebarPanel(
    private val componentPalette: TopologyComponentPalettePanel,
    private val actions: Actions
) : JPanel(BorderLayout()) {
    data class Actions(
        val addParticipant: () -> Unit,
        val addSynchronizer: () -> Unit,
        val arrange: () -> Unit,
        val selectionMenu: (JButton) -> Unit
    )

    private var collapsed = false

    init {
        background = TopologyGraphTheme.panel
        border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
        preferredSize = Dimension(292, 420)
        minimumSize = Dimension(38, 160)
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        add(if (collapsed) collapsedView() else expandedView(), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun collapsedView(): JComponent =
        JPanel(BorderLayout()).apply {
            background = TopologyGraphTheme.panel
            add(sidebarButton("›", null) {
                collapsed = false
                preferredSize = Dimension(292, preferredSize.height)
                rebuild()
            }.apply {
                toolTipText = "Expand topology sidebar"
                preferredSize = Dimension(34, 32)
            }, BorderLayout.NORTH)
        }

    private fun expandedView(): JComponent =
        JPanel(BorderLayout(0, 8)).apply {
            background = TopologyGraphTheme.panel
            border = JBUI.Borders.empty(8)
            add(header(), BorderLayout.NORTH)
            add(JBScrollPane(componentPalette).apply {
                border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
                viewport.background = TopologyGraphTheme.panel
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
            add(actionsPanel(), BorderLayout.SOUTH)
        }

    private fun header(): JComponent =
        JPanel(BorderLayout()).apply {
            background = TopologyGraphTheme.panel
            add(JLabel("Topology").apply {
                foreground = TopologyGraphTheme.text
                font = font.deriveFont(Font.BOLD, 15f)
            }, BorderLayout.WEST)
            add(sidebarButton("‹", null) {
                collapsed = true
                preferredSize = Dimension(38, preferredSize.height)
                rebuild()
            }.apply {
                toolTipText = "Collapse topology sidebar"
                preferredSize = Dimension(34, 30)
            }, BorderLayout.EAST)
        }

    private fun actionsPanel(): JComponent =
        JPanel(GridBagLayout()).apply {
            background = TopologyGraphTheme.panel
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, TopologyGraphTheme.panelBorder)
            val buttons = listOf(
                sidebarButton("Add PN", AllIcons.General.Add, actions.addParticipant),
                sidebarButton("Add SD", AllIcons.General.Add, actions.addSynchronizer),
                sidebarButton("Arrange", AllIcons.Actions.Refresh, actions.arrange),
                sidebarButtonWithSource("Selection", AllIcons.Actions.Edit) { source -> actions.selectionMenu(source) }
            )
            buttons.forEachIndexed { index, button ->
                val c = GridBagConstraints().apply {
                    gridx = index % 2
                    gridy = index / 2
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    insets = Insets(8.takeIf { index < 2 } ?: 4, if (index % 2 == 0) 0 else 5, 0, if (index % 2 == 0) 5 else 0)
                }
                add(button, c)
            }
        }

    private fun sidebarButton(text: String, icon: javax.swing.Icon?, action: () -> Unit): JButton =
        NetworkButton(text, icon).apply {
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.BOLD, 10.5f)
            preferredSize = Dimension(118, 30)
            addActionListener { action() }
        }

    private fun sidebarButtonWithSource(text: String, icon: javax.swing.Icon?, action: (JButton) -> Unit): JButton =
        NetworkButton(text, icon).apply {
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.BOLD, 10.5f)
            preferredSize = Dimension(118, 30)
            addActionListener { action(this) }
        }
}

private class TopologySplitPaneUI : BasicSplitPaneUI() {
    override fun createDefaultDivider(): BasicSplitPaneDivider =
        object : BasicSplitPaneDivider(this) {
            init {
                border = BorderFactory.createEmptyBorder()
                background = TopologyGraphTheme.canvas
            }

            override fun getPreferredSize(): Dimension = Dimension(8, 8)

            override fun paint(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = TopologyGraphTheme.canvas
                    g2.fillRect(0, 0, width, height)

                    if (height >= width) {
                        val center = width / 2
                        g2.color = dividerAlpha(TopologyGraphTheme.panelBorder, 170)
                        g2.fillRoundRect(center - 3, 0, 6, height, 6, 6)
                        g2.color = dividerAlpha(TopologyGraphTheme.participantBorder, 105)
                        g2.fillRoundRect(center - 1, 8, 2, height - 16, 4, 4)
                        g2.color = dividerAlpha(TopologyGraphTheme.hover, 55)
                        g2.drawLine(center + 2, 12, center + 2, height - 12)
                    } else {
                        val center = height / 2
                        g2.color = dividerAlpha(TopologyGraphTheme.panelBorder, 170)
                        g2.fillRoundRect(0, center - 3, width, 6, 6, 6)
                        g2.color = dividerAlpha(TopologyGraphTheme.participantBorder, 105)
                        g2.fillRoundRect(8, center - 1, width - 16, 2, 4, 4)
                        g2.color = dividerAlpha(TopologyGraphTheme.hover, 55)
                        g2.drawLine(12, center + 2, width - 12, center + 2)
                    }
                } finally {
                    g2.dispose()
                }
            }
        }
}

private fun dividerAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

private class NetworkTabbedPaneUI : BasicTabbedPaneUI() {
    override fun installDefaults() {
        super.installDefaults()
        tabInsets = Insets(4, 14, 4, 14)
        selectedTabPadInsets = Insets(0, 0, 0, 0)
        contentBorderInsets = Insets(0, 0, 0, 0)
    }

    override fun paintContentBorder(g: Graphics, tabPlacement: Int, selectedIndex: Int) {
        g.color = TopologyGraphTheme.panelBorder
        g.drawLine(0, tabPane.height - 1, tabPane.width, tabPane.height - 1)
    }

    override fun paintTabBackground(
        g: Graphics,
        tabPlacement: Int,
        tabIndex: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        isSelected: Boolean
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (isSelected) networkAlpha(TopologyGraphTheme.selected, 72) else TopologyGraphTheme.canvas
            g2.fillRoundRect(x + 2, y + 2, w - 4, h - 4, 10, 10)
        } finally {
            g2.dispose()
        }
    }

    override fun paintTabBorder(
        g: Graphics,
        tabPlacement: Int,
        tabIndex: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        isSelected: Boolean
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (isSelected) TopologyGraphTheme.selected else networkAlpha(TopologyGraphTheme.panelBorder, 120)
            g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 10, 10)
        } finally {
            g2.dispose()
        }
    }

    override fun paintFocusIndicator(
        g: Graphics,
        tabPlacement: Int,
        rects: Array<java.awt.Rectangle>,
        tabIndex: Int,
        iconRect: java.awt.Rectangle,
        textRect: java.awt.Rectangle,
        isSelected: Boolean
    ) = Unit

    override fun paintText(
        g: Graphics,
        tabPlacement: Int,
        font: Font,
        metrics: java.awt.FontMetrics,
        tabIndex: Int,
        title: String,
        textRect: java.awt.Rectangle,
        isSelected: Boolean
    ) {
        g.font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
        g.color = if (isSelected) TopologyGraphTheme.text else TopologyGraphTheme.detail
        g.drawString(title, textRect.x, textRect.y + metrics.ascent)
    }
}
