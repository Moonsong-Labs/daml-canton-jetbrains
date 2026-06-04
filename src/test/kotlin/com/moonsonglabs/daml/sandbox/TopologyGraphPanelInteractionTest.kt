package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

class TopologyGraphPanelInteractionTest {
    @Test
    fun `dragging a node reports a persisted position`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var moved: Triple<TopologyGraphPanel.Selection, Int, Int>? = null
        var selected: TopologyGraphPanel.Selection? = null
        panel.setPositionListener { selection, x, y -> moved = Triple(selection, x, y) }
        panel.setSelectionListener { selected = it }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_DRAGGED, 220, 286)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, 220, 286)

        val result = moved
        assertTrue(result?.first is TopologyGraphPanel.Selection.Participant)
        assertEquals(190, result?.second)
        assertEquals(266, result?.third)
        assertEquals("Dragging should not open/select via the click listener", null, selected)
        assertFalse(panel.isPropertiesOverlayVisibleForTest())
    }

    @Test
    fun `clicking a participant sync wire toggles the connection`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var update: Triple<String, String, Boolean>? = null
        panel.setConnectionListener { participantId, synchronizerId, connected ->
            update = Triple(participantId, synchronizerId, connected)
        }

        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 395, 250)

        assertEquals(profile.participants.first().id, update?.first)
        assertEquals(profile.synchronizers.first().id, update?.second)
        assertEquals(false, update?.third)
    }

    @Test
    fun `clicking a dormant participant sync wire reconnects it`() {
        val profile = SandboxDefaults.newProfile(null)
        profile.bindings.first().connected = false
        val panel = renderedPanel(profile)
        var update: Triple<String, String, Boolean>? = null
        panel.setConnectionListener { participantId, synchronizerId, connected ->
            update = Triple(participantId, synchronizerId, connected)
        }

        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 395, 250)

        assertEquals(profile.participants.first().id, update?.first)
        assertEquals(profile.synchronizers.first().id, update?.second)
        assertEquals(true, update?.third)
    }

    @Test
    fun `dragging from a participant port to a sync domain connects them`() {
        val profile = SandboxDefaults.newProfile(null)
        profile.bindings.first().connected = false
        val panel = renderedPanel(profile)
        var update: Triple<String, String, Boolean>? = null
        panel.setConnectionListener { participantId, synchronizerId, connected ->
            update = Triple(participantId, synchronizerId, connected)
        }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 350, 257)
        panel.dispatchMouse(MouseEvent.MOUSE_DRAGGED, 520, 253)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, 590, 250)

        assertEquals(profile.participants.first().id, update?.first)
        assertEquals(profile.synchronizers.first().id, update?.second)
        assertEquals(true, update?.third)
    }

    @Test
    fun `properties overlay can be closed`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        panel.openParticipantOverlay(profile)
        assertTrue(panel.isPropertiesOverlayVisibleForTest())

        val close = panel.propertiesOverlayBoundsForTest()!!.let { it.x + it.width - 18 to it.y + 16 }
        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, close.first, close.second)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, close.first, close.second)
        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, close.first, close.second)

        assertFalse(panel.isPropertiesOverlayVisibleForTest())
    }

    @Test
    fun `hovering a participant does not open details`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var selected: TopologyGraphPanel.Selection? = null
        panel.setSelectionListener { selected = it }

        panel.dispatchMouse(MouseEvent.MOUSE_MOVED, 120, 236)

        assertEquals("Hover must not invoke the detail selection listener", null, selected)
        assertFalse(panel.isPropertiesOverlayVisibleForTest())
        assertEquals("Graph details should not be registered as Swing hover popups", null, panel.toolTipText)
    }

    @Test
    fun `refreshing details after closing overlay does not reopen it`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        panel.openParticipantOverlay(profile)
        val close = panel.propertiesOverlayBoundsForTest()!!.let { it.x + it.width - 18 to it.y + 16 }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, close.first, close.second)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, close.first, close.second)
        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, close.first, close.second)
        panel.setSelectionDetails("${TopologyNodeIcons.PARTICIPANT} Participant - issuer\n\nHealth: live=true ready=true")

        assertFalse(panel.isPropertiesOverlayVisibleForTest())
    }

    @Test
    fun `properties overlay can move without moving selected node`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        panel.openParticipantOverlay(profile)
        val before = panel.propertiesOverlayBoundsForTest()!!
        var moved: Triple<TopologyGraphPanel.Selection, Int, Int>? = null
        panel.setPositionListener { selection, x, y -> moved = Triple(selection, x, y) }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, before.x + 20, before.y + 20)
        panel.dispatchMouse(MouseEvent.MOUSE_DRAGGED, before.x + 100, before.y + 60)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, before.x + 100, before.y + 60)
        repaint(panel)

        val after = panel.propertiesOverlayBoundsForTest()!!
        assertEquals(before.x + 80, after.x)
        assertEquals((before.y + 40).coerceAtMost(panel.height - after.height - 12), after.y)
        assertEquals(null, moved)
    }

    @Test
    fun `selecting another node replaces the current properties overlay`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        panel.openParticipantOverlay(profile)
        val moved = panel.propertiesOverlayBoundsForTest()!!
        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, moved.x + 20, moved.y + 20)
        panel.dispatchMouse(MouseEvent.MOUSE_DRAGGED, moved.x + 100, moved.y + 60)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, moved.x + 100, moved.y + 60)
        repaint(panel)
        val movedBounds = panel.propertiesOverlayBoundsForTest()!!

        panel.select(TopologyGraphPanel.Selection.Synchronizer(profile.synchronizers.first().id))
        panel.setSelectionDetails("${TopologyNodeIcons.SYNCHRONIZER} Sync Domain - global\n\nSequencer: globalSequencer")
        repaint(panel)

        val replacedBounds = panel.propertiesOverlayBoundsForTest()!!
        assertTrue(panel.isPropertiesOverlayVisibleForTest())
        assertFalse("New overlay should not reuse manually moved card position", movedBounds.location == replacedBounds.location)
    }

    @Test
    fun `single click opens the details selection after double click window`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var selected: TopologyGraphPanel.Selection? = null
        panel.setSelectionListener { selected = it }

        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 120, 236)
        assertEquals(null, selected)

        panel.flushPendingSingleClickForTest()

        assertEquals(TopologyGraphPanel.Selection.Participant(profile.participants.first().id), selected)
    }

    @Test
    fun `right clicking a participant reports a context action without opening details`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var contextSelection: TopologyGraphPanel.Selection? = null
        var detailSelectionOpened = false
        panel.setContextMenuListener { selection, _ -> contextSelection = selection }
        panel.setSelectionListener { detailSelectionOpened = true }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 120, 236, button = MouseEvent.BUTTON3)

        assertEquals(TopologyGraphPanel.Selection.Participant(profile.participants.first().id), contextSelection)
        assertFalse("Context click should not open the detail overlay path", detailSelectionOpened)
        assertFalse(panel.isPropertiesOverlayVisibleForTest())
    }

    @Test
    fun `right clicking a participant does not schedule a later single click detail`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var detailSelectionOpened = false
        panel.setContextMenuListener { _, _ -> }
        panel.setSelectionListener { detailSelectionOpened = true }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 120, 236, button = MouseEvent.BUTTON3)
        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 120, 236, button = MouseEvent.BUTTON3)
        panel.flushPendingSingleClickForTest()

        assertFalse(detailSelectionOpened)
    }

    @Test
    fun `double click opens edit activation without opening details`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var detailsOpened = false
        var activated: TopologyGraphPanel.Selection? = null
        panel.setSelectionListener { detailsOpened = true }
        panel.setActivationListener { activated = it }

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 120, 236, clickCount = 1)
        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 120, 236, clickCount = 2)

        assertFalse("Double click should not open the detail selection listener", detailsOpened)
        assertEquals(TopologyGraphPanel.Selection.Participant(profile.participants.first().id), activated)
        assertFalse(panel.isPropertiesOverlayVisibleForTest())
    }

    @Test
    fun `double click closes a details overlay that already opened before second click`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var activated: TopologyGraphPanel.Selection? = null
        panel.setSelectionListener { selection ->
            panel.select(selection)
            panel.setSelectionDetails("${TopologyNodeIcons.PARTICIPANT} Participant - issuer\n\nLedger API: grpc://127.0.0.1:5011")
        }
        panel.setActivationListener { activated = it }

        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 120, 236, clickCount = 1)
        panel.flushPendingSingleClickForTest()
        repaint(panel)
        assertTrue(panel.isPropertiesOverlayVisibleForTest())

        panel.dispatchMouse(MouseEvent.MOUSE_PRESSED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_RELEASED, 120, 236)
        panel.dispatchMouse(MouseEvent.MOUSE_CLICKED, 120, 236, clickCount = 2)

        assertEquals(TopologyGraphPanel.Selection.Participant(profile.participants.first().id), activated)
        assertFalse("Double click should close any previously opened detail overlay", panel.isPropertiesOverlayVisibleForTest())
    }

    @Test
    fun `sync details overlay avoids covering running wires`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        val sync = profile.synchronizers.first()

        panel.setRuntimeState(SandboxSessionStatus.RUNNING, emptyList(), 1)
        panel.select(TopologyGraphPanel.Selection.Synchronizer(sync.id))
        panel.setSelectionDetails(
            """
            |${TopologyNodeIcons.SYNCHRONIZER} Sync Domain - ${sync.name}
            |
            |Sequencer: ${sync.sequencer.name}
            |Mediator: ${sync.mediator.name}
            |
            |Connected participants:
            |issuer
            |""".trimMargin()
        )
        repaint(panel)

        assertTrue(panel.isPropertiesOverlayVisibleForTest())
        assertEquals("The auto-placed details card should not cover connected wires", 0, panel.propertiesOverlayWireIntersectionsForTest())
    }

    @Test
    fun `participant node tooltip names assigned dar files instead of only showing a count`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            darAssignments.add(DarAssignment("/workspace/.daml/dist/sample-settlement.dar", mutableListOf(participants.first().id)))
        }
        val panel = renderedPanel(profile)

        val tooltip = panel.getToolTipText(MouseEvent(panel, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 120, 236, 0, false))

        assertTrue(tooltip.orEmpty().contains("DAR sample-settlement.dar"))
        assertFalse(tooltip.orEmpty().contains("1 DAR"))
    }

    @Test
    fun `participant node tooltip summarizes multiple assigned dar file names`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            darAssignments.add(DarAssignment("/workspace/.daml/dist/sample-settlement.dar", mutableListOf(participants.first().id)))
            darAssignments.add(DarAssignment("/workspace/.daml/dist/sample-interface.dar", mutableListOf(participants.first().id)))
            darAssignments.add(DarAssignment("/workspace/.daml/dist/sample-impl.dar", mutableListOf(participants.first().id)))
        }
        val panel = renderedPanel(profile)

        val tooltip = panel.getToolTipText(MouseEvent(panel, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 120, 236, 0, false))

        assertTrue(tooltip.orEmpty().contains("DARs sample-impl.dar, sample-interface.dar +1 more"))
    }

    @Test
    fun `runtime flow only animates for running online topology`() {
        val profile = SandboxDefaults.newProfile(null)
        val participant = profile.participants.first()
        val panel = renderedPanel(profile)

        panel.setRuntimeState(SandboxSessionStatus.STOPPED, emptyList(), 1)
        assertFalse(panel.isRuntimeFlowEnabledForTest())

        panel.setRuntimeState(
            SandboxSessionStatus.RUNNING,
            listOf(HealthSnapshot(Endpoint(participant.id, participant.name, "json", "http://127.0.0.1:${participant.jsonPort}", participant.jsonPort), true, true, "ok")),
            2
        )
        assertTrue(panel.isRuntimeFlowEnabledForTest())

        panel.setRuntimeState(
            SandboxSessionStatus.RUNNING,
            listOf(HealthSnapshot(Endpoint(participant.id, participant.name, "json", "http://127.0.0.1:${participant.jsonPort}", participant.jsonPort), false, false, "down")),
            3
        )
        assertFalse(panel.isRuntimeFlowEnabledForTest())
    }

    @Test
    fun `runtime flow gets a temporary boost when activity changes`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)

        panel.setRuntimeState(SandboxSessionStatus.RUNNING, emptyList(), 1)
        val idleBoost = panel.flowBoostForTest()
        panel.setRuntimeState(SandboxSessionStatus.RUNNING, emptyList(), 2)

        assertTrue(panel.isRuntimeFlowEnabledForTest())
        assertTrue("Activity should temporarily speed up the wire flow", panel.flowBoostForTest() > idleBoost)
    }

    @Test
    fun `running runtime flow paints without an invalid dash phase`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)

        panel.setRuntimeState(SandboxSessionStatus.RUNNING, emptyList(), 1)
        repaint(panel)
        panel.setRuntimeState(SandboxSessionStatus.RUNNING, emptyList(), 2)
        repaint(panel)

        assertTrue(panel.isRuntimeFlowEnabledForTest())
    }

    @Test
    fun `dar drop accepts participant nodes only`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)
        var dropped: Pair<String, String>? = null
        var rejected: String? = null
        panel.setDarDropListener { path, participantId -> dropped = path to participantId }
        panel.setDarDropRejectedListener { rejected = it }

        assertTrue(panel.dropDarForTest("/tmp/app.dar", 120, 236))
        assertEquals("/tmp/app.dar", dropped?.first)
        assertEquals(profile.participants.first().id, dropped?.second)

        dropped = null
        assertFalse(panel.dropDarForTest("/tmp/app.dar", 590, 250))
        assertEquals(null, dropped)
        assertEquals("DARs are uploaded to participant nodes", rejected)
    }

    @Test
    fun `participant drop target lookup ignores sync domains and canvas`() {
        val profile = SandboxDefaults.newProfile(null)
        val panel = renderedPanel(profile)

        assertEquals(profile.participants.first().id, panel.participantDropTargetForTest(120, 236))
        assertEquals(null, panel.participantDropTargetForTest(590, 250))
        assertEquals(null, panel.participantDropTargetForTest(20, 20))
    }

    private fun renderedPanel(profile: SandboxProfile): TopologyGraphPanel {
        val panel = TopologyGraphPanel()
        panel.setProfile(profile)
        panel.setSize(panel.preferredSize)
        repaint(panel)
        return panel
    }

    private fun TopologyGraphPanel.openParticipantOverlay(profile: SandboxProfile) {
        select(TopologyGraphPanel.Selection.Participant(profile.participants.first().id))
        setSelectionDetails(
            """
            |${TopologyNodeIcons.PARTICIPANT} Participant - issuer
            |
            |Ledger API: grpc://127.0.0.1:5011
            |Admin API: grpc://127.0.0.1:5012
            |JSON API: http://127.0.0.1:7575
            |""".trimMargin()
        )
        repaint(this)
    }

    private fun repaint(panel: TopologyGraphPanel) {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.paint(graphics)
        graphics.dispose()
    }

    private fun TopologyGraphPanel.dispatchMouse(
        id: Int,
        x: Int,
        y: Int,
        clickCount: Int = 1,
        button: Int = MouseEvent.NOBUTTON
    ) {
        dispatchEvent(MouseEvent(this, id, System.currentTimeMillis(), 0, x, y, clickCount, false, button))
    }
}
