package com.moonsonglabs.daml.sandbox

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTabbedPane

class CantonSandboxPanelTest : BasePlatformTestCase() {
    fun `test panel constructs with default profile`() {
        val panel = CantonSandboxPanel(project)

        try {
            assertTrue(panel.componentCount > 0)
        } finally {
            panel.dispose()
        }
    }

    fun `test designer no longer embeds explorer as nested tab`() {
        val panel = CantonSandboxPanel(project)

        try {
            assertFalse(panel.containsTab("Explorer"))
            assertTrue(panel.containsTab("Topology"))
            assertTrue(panel.containsTab("Nodes"))
            assertFalse(panel.containsTab("DARs"))
            assertTrue(panel.containsTab("Parties"))
            assertTrue(panel.containsTab("Logs"))
        } finally {
            panel.dispose()
        }
    }

    fun `test topology tab exposes collapsible component sidebar without dar picker`() {
        val panel = CantonSandboxPanel(project)

        try {
            assertTrue(panel.containsLabel("Topology"))
            assertNotNull(panel.findButton("Add PN"))
            assertNotNull(panel.findButton("Add SD"))
            assertNotNull(panel.findButton("Arrange"))
            assertNotNull(panel.findButton("Selection"))
            assertFalse(panel.containsLabel("DARs"))
            assertNull(panel.findButton("Browse"))
            assertNull(panel.findButton("Add DAR"))
            assertNull(panel.findButton("Manage"))
        } finally {
            panel.dispose()
        }
    }

    fun `test tool window factory creates designer and explorer contents`() {
        val contents = CantonSandboxToolWindowFactory().createContents(project)

        try {
            assertEquals(listOf("Network", "Explorer"), contents.map { it.name })
            assertTrue(contents[0].component is CantonSandboxPanel)
            assertTrue(contents[1].component is LedgerExplorerPanel)
        } finally {
            contents.mapNotNull { it.disposable }.forEach { it.dispose() }
        }
    }

    fun `test selecting a row for graph edit does not reopen topology details`() {
        val panel = CantonSandboxPanel(project)

        try {
            val profile = panel.privateField<SandboxProfile>("currentProfile")
            val selection = panel.privateField<TopologyGraphPanel.Selection?>("currentTopologySelection")
            assertNull(selection)

            panel.privateMethod("selectParticipantRow", String::class.java)
                .invoke(panel, profile.participants.first().id)

            assertNull(panel.privateField<TopologyGraphPanel.Selection?>("currentTopologySelection"))
        } finally {
            panel.dispose()
        }
    }

    fun `test network tables use topology themed surfaces`() {
        val panel = CantonSandboxPanel(project)

        try {
            val participantTable = panel.privateField<JBTable>("participantTable")
            val syncTable = panel.privateField<JBTable>("syncTable")

            assertEquals(TopologyGraphTheme.panel, participantTable.background)
            assertEquals(TopologyGraphTheme.panel, syncTable.background)
            assertEquals(30, participantTable.rowHeight)
            assertEquals(TopologyGraphTheme.warning, participantTable.tableHeader.foreground)
        } finally {
            panel.dispose()
        }
    }

    fun `test network toolbar controls stay compact`() {
        val panel = CantonSandboxPanel(project)

        try {
            val profileCombo = panel.privateField<ProfileComboBox>("profileCombo")
            val nameField = panel.privateField<JBTextField>("nameField")
            val portBaseField = panel.privateField<JBTextField>("portBaseField")

            assertEquals(34, profileCombo.preferredSize.height)
            assertEquals(34, nameField.preferredSize.height)
            assertEquals(34, portBaseField.preferredSize.height)
        } finally {
            panel.dispose()
        }
    }

    fun `test network toolbar shows session status`() {
        val panel = CantonSandboxPanel(project)

        try {
            val statusBadge = panel.privateField<JLabel>("networkStatusBadge")

            assertEquals("Status: Stopped", statusBadge.text)
            assertEquals(networkStatusColor(SandboxSessionStatus.STOPPED), statusBadge.foreground)

            panel.privateMethod("renderSession", SandboxSessionState::class.java)
                .invoke(
                    panel,
                    SandboxSessionState(
                        profileId = panel.privateField<SandboxProfile>("currentProfile").id,
                        status = SandboxSessionStatus.RUNNING,
                        message = "Sandbox ready"
                    )
                )

            assertEquals("Status: Running", statusBadge.text)
            assertEquals(networkStatusColor(SandboxSessionStatus.RUNNING), statusBadge.foreground)
            assertEquals("Sandbox ready", statusBadge.toolTipText)
        } finally {
            panel.dispose()
        }
    }

    fun `test logs tab clear button clears session log`() {
        val service = SandboxSessionService.getInstance(project)
        service.privateSetField("state", SandboxSessionState(log = "line one\nline two"))
        val panel = CantonSandboxPanel(project)

        try {
            val clearButton = panel.findButton("Clear")
            assertNotNull(clearButton)

            clearButton!!.doClick()

            assertEquals("", service.snapshot().log)
        } finally {
            panel.dispose()
        }
    }

    fun `test assigning dar to participant updates profile while running`() {
        val panel = CantonSandboxPanel(project)

        try {
            val profile = panel.privateField<SandboxProfile>("currentProfile")
            panel.privateSetField(
                "latestSession",
                SandboxSessionState(profileId = profile.id, status = SandboxSessionStatus.RUNNING)
            )
            panel.privateMethod("assignDarToParticipant", String::class.java, String::class.java)
                .invoke(panel, "/tmp/sample-settlement.dar", profile.participants.first().id)

            val updated = panel.privateField<SandboxProfile>("currentProfile")
            assertTrue(updated.darAssignments.any { profile.participants.first().id in it.participantIds })
        } finally {
            panel.dispose()
        }
    }

    fun `test clearing participant dars removes empty assignments`() {
        val panel = CantonSandboxPanel(project)

        try {
            val profile = panel.privateField<SandboxProfile>("currentProfile")
            profile.darAssignments.add(DarAssignment("/tmp/sample-settlement.dar", mutableListOf(profile.participants.first().id)))

            panel.privateMethod("clearDarsFromParticipant", String::class.java)
                .invoke(panel, profile.participants.first().id)

            assertTrue(panel.privateField<SandboxProfile>("currentProfile").darAssignments.isEmpty())
        } finally {
            panel.dispose()
        }
    }

    fun `test panel reloads when profile service publishes updated profile`() {
        val root = java.nio.file.Path.of(project.basePath!!)
        val service = SandboxProfileService.getInstance(project)
        val baseProfile = SandboxDefaults.newProfile(root).apply {
            id = "panel-refresh-test"
            name = "Panel Refresh Test"
            generatedPath = root.resolve(".canton-sandboxes/panel-refresh-test").toString()
        }
        service.loadState(SandboxProfileService.State(mutableListOf(baseProfile), baseProfile.id))
        val panel = CantonSandboxPanel(project)

        try {
            val current = panel.privateField<SandboxProfile>("currentProfile")
            val updated = current.copy(
                participants = (current.participants + ParticipantNode("auditor", "auditor", 5032, 5031, 7578)).toMutableList()
            )

            service.upsert(updated)

            val participantTable = panel.privateField<JBTable>("participantTable")
            assertEquals(2, panel.privateField<SandboxProfile>("currentProfile").participants.size)
            assertEquals("auditor", participantTable.getValueAt(1, 0))
        } finally {
            panel.dispose()
        }
    }

    fun `test network renderers map participants syncs and connections`() {
        val panel = CantonSandboxPanel(project)

        try {
            val participantTable = panel.privateField<JBTable>("participantTable")
            val syncTable = panel.privateField<JBTable>("syncTable")
            val connectionTable = panel.privateField<JBTable>("connectionTable")

            val participant = participantTable.renderedLabel(0, 0)
            assertEquals(networkParticipantColor(), participant.foreground)

            val sync = syncTable.renderedLabel(0, 0)
            assertEquals(networkSyncColor(SandboxDefaults.SHARED_SYNCHRONIZER_NAME), sync.foreground)

            val connected = connectionTable.renderedLabel(0, 2)
            assertEquals("connected", connected.text)
            assertEquals(networkConnectionColor(true), connected.foreground)
        } finally {
            panel.dispose()
        }
    }

    fun `test network log severity colors are deterministic`() {
        assertEquals(TopologyGraphTheme.syncBorder, networkLogLineColor("participant started and SERVING"))
        assertEquals(TopologyGraphTheme.warning, networkLogLineColor("WARN port already in use"))
        assertEquals(java.awt.Color(0xFF5C7A), networkLogLineColor("ERROR failed to start"))
        assertEquals(TopologyGraphTheme.detail, networkLogLineColor("plain canton output"))
    }

    private inline fun <reified T> Any.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }

    private fun Any.privateSetField(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private fun Any.privateMethod(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        val method = javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method
    }

    private fun Container.containsTab(title: String): Boolean {
        if (this is JTabbedPane) {
            for (index in 0 until tabCount) {
                if (getTitleAt(index) == title) return true
            }
        }
        return components.filterIsInstance<Container>().any { it.containsTab(title) }
    }

    private fun Container.containsLabel(text: String): Boolean {
        components.filterIsInstance<JLabel>().firstOrNull { it.text == text }?.let { return true }
        return components.filterIsInstance<Container>().any { it.containsLabel(text) }
    }

    private fun Container.findButton(text: String): JButton? {
        components.filterIsInstance<JButton>().firstOrNull { it.text == text }?.let { return it }
        return components.filterIsInstance<Container>().firstNotNullOfOrNull { it.findButton(text) }
    }

    private fun JBTable.renderedLabel(row: Int, column: Int): JLabel {
        val renderer = getCellRenderer(row, column)
        return renderer.getTableCellRendererComponent(this, getValueAt(row, column), false, false, row, column) as JLabel
    }
}
