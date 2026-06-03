package com.moonsonglabs.daml.sandbox

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

class LedgerExplorerPanelTest : BasePlatformTestCase() {
    fun `test explorer panel constructs in headless mode`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))
            panel.setSession(SandboxSessionState(status = SandboxSessionStatus.STOPPED, message = "Stopped"))

            assertTrue(panel.componentCount > 0)
        } finally {
            panel.dispose()
        }
    }

    fun `test filters update table and selection populates inspector`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            val profile = SandboxDefaults.newProfile(null)
            panel.setProfile(profile)
            panel.privateField<DefaultListModel<String>>("partyModel").addElement("Operator")
            panel.privateField<JBList<String>>("partyList").selectedIndices = intArrayOf(1)
            panel.privateSet("allRows", listOf(activityRow()))

            panel.applyFilters()

            val model = panel.privateField<DefaultTableModel>("activityModel")
            assertEquals(1, model.rowCount)
            panel.privateField<JBTable>("activityTable").selectionModel.setSelectionInterval(0, 0)
            assertTrue(panel.privateField<JBTextArea>("detailsArea").text.contains("PublicSettlement"))
        } finally {
            panel.dispose()
        }
    }

    fun `test explorer follows selected sandbox profile service`() {
        val service = SandboxProfileService.getInstance(project)
        service.loadState(SandboxProfileService.State())
        val panel = LedgerExplorerPanel(project)

        try {
            val profile = service.createProfile().apply {
                name = "Explorer Follow Profile"
            }
            service.upsert(profile)
            flushEdt()

            val selected = panel.privateField<ProfileComboBox>("profileCombo").selectedItem as SandboxProfile
            assertEquals(profile.id, selected.id)
        } finally {
            panel.dispose()
            service.loadState(SandboxProfileService.State())
        }
    }

    fun `test stopped empty state is deterministic`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))
            panel.setSession(SandboxSessionState(status = SandboxSessionStatus.STOPPED, message = "Stopped"))
            panel.applyFilters()

            assertTrue(panel.privateField<JBTextArea>("detailsArea").text.contains("Sandbox not running"))
        } finally {
            panel.dispose()
        }
    }

    fun `test filter sidebar is collapsed by default`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))

            assertFalse(panel.privateField<Boolean>("sidebarExpanded"))
        } finally {
            panel.dispose()
        }
    }

    fun `test expanded sidebar omits duplicated category headers`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            val profile = SandboxDefaults.newProfile(null)
            panel.setProfile(profile)
            panel.privateSet("sidebarExpanded", true)
            panel.privateMethod("updateSidebar").invoke(panel)

            val sidebar = panel.privateField<JPanel>("sidebarSlot")
            assertTrue(sidebar.containsLabel("Filters"))
            assertFalse(sidebar.containsLabel("Participants"))
            assertFalse(sidebar.containsLabel("Sync Domains"))
            assertFalse(sidebar.containsLabel("Parties"))
        } finally {
            panel.dispose()
        }
    }

    fun `test switch filters update visible activity rows`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))
            panel.privateSet("selectedSegment", "Raw")
            panel.privateField<DefaultListModel<String>>("partyModel").addElement("Operator")
            panel.privateField<JBList<String>>("partyList").selectedIndices = intArrayOf(1)
            panel.privateSet("allRows", listOf(activityRow(), activityRow(kind = "Archived", contractId = "00archived")))

            panel.applyFilters()
            assertEquals(2, panel.privateField<DefaultTableModel>("activityModel").rowCount)

            panel.privateField<Any>("archivedSwitch").setPrivateSwitchSelected(false)
            assertEquals(1, panel.privateField<DefaultTableModel>("activityModel").rowCount)
        } finally {
            panel.dispose()
        }
    }

    fun `test explorer navigation selects participant and full activity segment`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            val profile = SandboxDefaults.newProfile(null)
            val second = SandboxDefaults.participant(2, profile.portBase)
            profile.participants.add(second)
            panel.setProfile(profile)

            SandboxExplorerNavigationService.getInstance(project).showParticipant(profile, second.id, refresh = false)
            flushEdt()

            assertEquals(second.name, panel.privateField<JBList<String>>("participantList").selectedValue)
            assertEquals("History", panel.privateField<String>("selectedSegment"))
            assertTrue(panel.privateField<JBTextArea>("detailsArea").text.contains("Sandbox not running"))
        } finally {
            panel.dispose()
        }
    }

    fun `test history segment includes created and archived rows but not active snapshot rows`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))
            panel.privateSet("selectedSegment", "History")
            panel.privateField<DefaultListModel<String>>("partyModel").addElement("Operator")
            panel.privateField<JBList<String>>("partyList").selectedIndices = intArrayOf(1)
            panel.privateSet(
                "allRows",
                listOf(
                    activityRow(),
                    activityRow(kind = "Created", contractId = "00created"),
                    activityRow(kind = "Archived", contractId = "00archived")
                )
            )

            panel.applyFilters()

            assertEquals(2, panel.privateField<DefaultTableModel>("activityModel").rowCount)
        } finally {
            panel.dispose()
        }
    }

    fun `test active segment shows active contracts even when sidebar active toggle is off`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))
            panel.privateSet("selectedSegment", "Active")
            panel.privateField<DefaultListModel<String>>("partyModel").addElement("Operator")
            panel.privateField<JBList<String>>("partyList").selectedIndices = intArrayOf(1)
            panel.privateSet("allRows", listOf(activityRow()))
            panel.privateField<Any>("activeSwitch").setPrivateSwitchSelected(false)

            panel.applyFilters()

            assertEquals(1, panel.privateField<DefaultTableModel>("activityModel").rowCount)
        } finally {
            panel.dispose()
        }
    }

    fun `test timeline hover describes event and click selects table row`() {
        val panel = LedgerExplorerPanel(project, SandboxSessionService.getInstance(project)) {}

        try {
            panel.setProfile(SandboxDefaults.newProfile(null))
            panel.privateSet("selectedSegment", "History")
            panel.privateField<JBList<String>>("partyList").clearSelection()
            val created = activityRow(kind = "Created", contractId = "00created", offset = 6)
            val archived = activityRow(kind = "Archived", contractId = "00archived", offset = 7)
            panel.privateSet("allRows", listOf(created, archived))

            panel.applyFilters()
            val timeline = panel.privateField<NetworkActivityTimelinePanel>("timeline")
            timeline.setSize(1000, 126)
            val point = timeline.markerCenterForTest(created)!!

            assertTrue(timeline.hoverDescriptionForTest(point.x, point.y)!!.contains("Created PublicSettlement"))
            timeline.dispatchEvent(MouseEvent(timeline, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, point.x, point.y, 1, false))

            assertTrue(panel.privateField<JBTextArea>("detailsArea").text.contains("00created"))
        } finally {
            panel.dispose()
        }
    }

    private fun activityRow(kind: String = "Active", contractId: String = "00active", offset: Long = 5): ExplorerActivityRow =
        ExplorerActivityRow(
            kind = kind,
            templateId = "pkg:PrivateSettlement:PublicSettlement",
            templateName = "PublicSettlement",
            contractId = contractId,
            offset = offset,
            offsetText = offset.toString(),
            synchronizerId = "global::abc",
            syncName = "global",
            packageName = "private-settlement",
            parties = listOf("Operator::party"),
            argumentFields = mapOf("amount" to "42.0"),
            rawJson = """{"contractId":"00active"}"""
        )

    private inline fun <reified T> Any.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }

    private fun Any.privateSet(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private fun Any.setPrivateSwitchSelected(value: Boolean) {
        javaClass.getMethod("setSelected", java.lang.Boolean.TYPE).invoke(this, value)
    }

    private fun Any.privateMethod(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        val method = javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method
    }

    private fun flushEdt() {
        if (!SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeAndWait {}
    }

    private fun Container.containsLabel(text: String): Boolean =
        components.any { component ->
            (component is JLabel && component.text == text) ||
                (component is Container && component.containsLabel(text))
        }
}
