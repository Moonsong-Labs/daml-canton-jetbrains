package com.moonsonglabs.daml.sandbox

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class DarAssignmentDialog(
    private val project: Project,
    private val profile: SandboxProfile,
    discoveredDars: List<DiscoveredDar> = emptyList()
) : DialogWrapper(project) {
    private val projectRoot = DamlWorkspaceService.getInstance(project).projectRoot()
    private val darPaths = linkedSetOf<Path>()
    private val model = object : DefaultTableModel() {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) String::class.java else Boolean::class.javaObjectType

        override fun isCellEditable(row: Int, column: Int): Boolean = column > 0
    }
    private val table = JBTable(model)
    private val status = JLabel()
    private val sourceRoots = linkedSetOf<Path>()
    private val sourceModel = DefaultListModel<String>()
    private val sourceList = JList(sourceModel)

    init {
        title = "Manage DAR Assignments"
        setOKButtonText("Save")
        discoveredDars.mapTo(darPaths) { it.path.toAbsolutePath().normalize() }
        profile.darAssignments.mapNotNullTo(darPaths) {
            runCatching { SandboxPaths.resolveProfilePath(it.darPath, profile, projectRoot).toAbsolutePath().normalize() }.getOrNull()
        }
        discoveredDars.mapNotNullTo(sourceRoots) { it.path.toAbsolutePath().normalize().parent }
        darPaths.mapNotNullTo(sourceRoots) { it.parent?.toAbsolutePath()?.normalize() }
        sourceRoots.toList().forEach { root ->
            discoverSourceDars(root).forEach { path -> darPaths.add(path) }
        }
        initSourceList()
        initTable()
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(8, 8)).apply {
            background = TopologyGraphTheme.canvas
            preferredSize = Dimension(780, 420)
            border = JBUI.Borders.empty(8)
            add(sourcePanel(), BorderLayout.NORTH)
            add(JBScrollPane(table).apply {
                border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
                viewport.background = TopologyGraphTheme.panel
            }, BorderLayout.CENTER)
            add(status.apply {
                foreground = TopologyGraphTheme.detail
                font = font.deriveFont(11f)
                text = validationSummary()
            }, BorderLayout.SOUTH)
        }

    fun resultAssignments(): MutableList<DarAssignment> {
        if (!isOK) return profile.darAssignments
        val assignments = mutableListOf<DarAssignment>()
        for (row in 0 until model.rowCount) {
            val path = darPaths.elementAt(row)
            val participantIds = profile.participants.mapIndexedNotNull { index, participant ->
                if (model.getValueAt(row, index + 1) == true) participant.id else null
            }.toMutableList()
            if (participantIds.isNotEmpty()) {
                assignments += DarAssignment(SandboxPaths.relativeProfilePath(path.toString(), profile, projectRoot), participantIds)
            }
        }
        return assignments
    }

    private fun sourcePanel(): JComponent =
        JPanel(GridBagLayout()).apply {
            background = TopologyGraphTheme.canvas
            border = JBUI.Borders.emptyBottom(8)
            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0, 0, 6, 6)
            }
            add(themedButton("Browse Source Folder", AllIcons.General.OpenDisk) { addSourceFolder() }, constraints)
            constraints.gridx = 1
            constraints.weightx = 1.0
            constraints.fill = GridBagConstraints.HORIZONTAL
            add(JLabel("Source folders").apply {
                foreground = TopologyGraphTheme.detail
                font = font.deriveFont(11f)
            }, constraints)
            constraints.gridx = 0
            constraints.gridy = 1
            constraints.gridwidth = 2
            constraints.weightx = 1.0
            constraints.fill = GridBagConstraints.HORIZONTAL
            add(JBScrollPane(sourceList).apply {
                preferredSize = Dimension(720, 64)
                border = BorderFactory.createLineBorder(TopologyGraphTheme.panelBorder)
                viewport.background = TopologyGraphTheme.panel
            }, constraints)
        }

    private fun initTable() {
        model.addColumn("DAR")
        profile.participants.forEach { model.addColumn(it.name) }
        darPaths.forEach { addPathRow(it) }
        table.rowHeight = 30
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.background = TopologyGraphTheme.panel
        table.foreground = TopologyGraphTheme.text
        table.gridColor = TopologyGraphTheme.panelBorder
        table.selectionBackground = networkAlpha(TopologyGraphTheme.selected, 80)
        table.selectionForeground = TopologyGraphTheme.text
        table.tableHeader.background = TopologyGraphTheme.canvas
        table.tableHeader.foreground = TopologyGraphTheme.warning
        table.tableHeader.font = table.tableHeader.font.deriveFont(Font.BOLD, 12f)
        table.columnModel.getColumn(0).preferredWidth = 330
        for (index in 1 until table.columnModel.columnCount) {
            table.columnModel.getColumn(index).preferredWidth = 110
        }
        table.setDefaultRenderer(String::class.java, DarPathRenderer())
    }

    private fun initSourceList() {
        sourceList.background = TopologyGraphTheme.panel
        sourceList.foreground = TopologyGraphTheme.detail
        sourceList.selectionBackground = networkAlpha(TopologyGraphTheme.selected, 80)
        sourceList.selectionForeground = TopologyGraphTheme.text
        sourceList.visibleRowCount = 2
        updateSourceList()
    }

    private fun addPathRow(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        val row = mutableListOf<Any>(absolute.fileName?.toString() ?: absolute.toString())
        profile.participants.forEach { participant ->
            row += profile.darAssignments.any { assignment ->
                participant.id in assignment.participantIds &&
                    runCatching {
                        SandboxPaths.resolveProfilePath(assignment.darPath, profile, projectRoot).toAbsolutePath().normalize() == absolute
                    }.getOrDefault(false)
            }
        }
        model.addRow(row.toTypedArray())
    }

    private fun addSourceFolder() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select DAR Source Folder")
        val selected = FileChooser.chooseFiles(descriptor, project, null).firstOrNull() ?: return
        val root = Path.of(selected.path).toAbsolutePath().normalize()
        if (!sourceRoots.add(root)) return
        updateSourceList()
        discoverSourceDars(root).forEach { path ->
            if (darPaths.add(path)) addPathRow(path)
        }
        status.text = validationSummary()
    }

    private fun discoverSourceDars(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val discovered = mutableListOf<Path>()
        Files.walk(root, 10).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".dar", ignoreCase = true) }
                .map { it.toAbsolutePath().normalize() }
                .forEach(discovered::add)
        }
        return discovered.sorted()
    }

    private fun updateSourceList() {
        sourceModel.clear()
        if (sourceRoots.isEmpty()) {
            sourceModel.addElement("No source folders selected")
        } else {
            sourceRoots.forEach { sourceModel.addElement(it.toString()) }
        }
    }

    private fun validationSummary(): String {
        val missing = darPaths.count { !Files.isRegularFile(it) || !it.fileName.toString().endsWith(".dar", ignoreCase = true) }
        val assignedRows = (0 until model.rowCount).count { row ->
            (1 until model.columnCount).any { column -> model.getValueAt(row, column) == true }
        }
        val partyParticipantsWithoutDar = profile.participants.count { participant ->
            profile.partyAllocations.any { it.participantId == participant.id } &&
                (0 until model.rowCount).none { row ->
                    val index = profile.participants.indexOfFirst { it.id == participant.id }
                    index >= 0 && model.getValueAt(row, index + 1) == true
                }
        }
        return buildString {
            append("$assignedRows DAR row(s) assigned")
            if (missing > 0) append(" | $missing missing/invalid path(s)")
            if (partyParticipantsWithoutDar > 0) append(" | $partyParticipantsWithoutDar participant(s) have parties but no DAR")
        }
    }

    private fun themedButton(text: String, icon: javax.swing.Icon?, action: () -> Unit): JButton =
        NetworkButton(text, icon).apply {
            addActionListener { action() }
        }

    private class DarPathRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ) = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
            background = if (isSelected) networkAlpha(TopologyGraphTheme.selected, 80) else TopologyGraphTheme.panel
            foreground = TopologyGraphTheme.text
            font = font.deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.empty(0, 8)
        }
    }
}
