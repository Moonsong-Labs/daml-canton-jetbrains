package com.moonsonglabs.daml.sandbox

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.QuadCurve2D
import java.awt.geom.RoundRectangle2D
import java.awt.Toolkit
import javax.swing.JPanel
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.TransferHandler
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object TopologyGraphTheme {
    fun fontSize(points: Float): Float = UIUtil.getLabelFont().size2D * points / 12f
    val canvas get() = UIUtil.getPanelBackground()
    val panel get() = UIUtil.getTableBackground()
    val panelBorder get() = JBColor.border()
    val grid get() = JBColor(Color(0xE8EAED), Color(0x30343B))
    val edge get() = JBColor(Color(0x247D9D), Color(0x68C8DF))
    val edgeMuted get() = JBColor.GRAY
    val text get() = UIUtil.getLabelForeground()
    val detail get() = JBColor(Color(0x596273), Color(0xADB4C0))
    val selected get() = JBColor(Color(0x3574F0), Color(0x75A5FF))
    val hover get() = selected
    val warning get() = JBColor(Color(0x916300), Color(0xE3B65D))
    val participantFill get() = JBColor(Color(0xEAF4F9), Color(0x203640))
    val participantBorder get() = JBColor(Color(0x087F9E), Color(0x56CEDC))
    val syncFill get() = JBColor(Color(0xF2EFF8), Color(0x302A3E))
    val syncBorder get() = JBColor(Color(0x8054B3), Color(0xBD9AE8))
    val globalSyncFill get() = JBColor(Color(0xF6F0FA), Color(0x393044))
    val globalSyncBorder get() = JBColor(Color(0x8051AF), Color(0xC39CE9))
    fun fill(selection: TopologyGraphPanel.Selection): Color =
        when (selection) {
            is TopologyGraphPanel.Selection.Participant -> participantFill
            is TopologyGraphPanel.Selection.Synchronizer ->
                if (SandboxDefaults.isSharedSynchronizer(selection.id)) globalSyncFill else syncFill
        }

    fun border(selection: TopologyGraphPanel.Selection): Color =
        when (selection) {
            is TopologyGraphPanel.Selection.Participant -> participantBorder
            is TopologyGraphPanel.Selection.Synchronizer ->
                if (SandboxDefaults.isSharedSynchronizer(selection.id)) globalSyncBorder else syncBorder
        }

    fun cornerRadius(selection: TopologyGraphPanel.Selection): Float =
        when (selection) {
            is TopologyGraphPanel.Selection.Synchronizer ->
                if (SandboxDefaults.isSharedSynchronizer(selection.id)) 24f else 8f
            else -> 8f
    }
}

private fun darNodeSummary(names: List<String>): String =
    when (names.size) {
        0 -> "No DARs"
        1 -> "DAR ${names.first()}"
        2 -> "DARs ${names.joinToString(", ")}"
        else -> "DARs ${names.take(2).joinToString(", ")} +${names.size - 2} more"
    }

private fun darPaletteSummary(names: List<String>): String =
    when (names.size) {
        0 -> "no DAR"
        1 -> names.first()
        else -> "${names.first()} +${names.size - 1}"
    }

class TopologyGraphPanel : JPanel(), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 24
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 120
    fun refreshViewportSize() { preferredSize = graphSize(); revalidate() }
    private fun narrowCanvas() = ((parent as? javax.swing.JViewport)?.extentSize?.width ?: width) in 1..699

    sealed class Selection {
        data class Participant(val id: String) : Selection()
        data class Synchronizer(val id: String) : Selection()
    }

    private data class DrawNode(
        val selection: Selection,
        val label: String,
        val detail: String,
        val secondaryDetail: String? = null,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val fill: Color,
        val border: Color,
        val cornerRadius: Float = TopologyGraphTheme.cornerRadius(selection),
        val warning: String? = null
    ) {
        fun contains(px: Int, py: Int): Boolean = px in x..(x + w) && py in y..(y + h)
        fun centerX(): Int = x + w / 2
        fun centerY(): Int = y + h / 2
        fun leftPort(): Pair<Int, Int> = x to centerY()
        fun rightPort(): Pair<Int, Int> = x + w to centerY()
    }

    private data class DrawWire(
        val participantId: String,
        val synchronizerId: String,
        val connected: Boolean,
        val from: Pair<Int, Int>,
        val to: Pair<Int, Int>,
        val control: Pair<Int, Int>
    ) {
        fun hit(px: Int, py: Int): Boolean {
            var previous = from
            for (step in 1..28) {
                val t = step / 28.0
                val point = quadraticPoint(t)
                if (nearSegment(px, py, previous.first, previous.second, point.first, point.second)) return true
                previous = point
            }
            return false
        }

        private fun nearSegment(px: Int, py: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
            val minX = min(x1, x2) - 9
            val maxX = max(x1, x2) + 9
            val minY = min(y1, y2) - 9
            val maxY = max(y1, y2) + 9
            if (px !in minX..maxX || py !in minY..maxY) return false
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0.0) {
                val xDistance = px - x1
                val yDistance = py - y1
                return xDistance * xDistance + yDistance * yDistance <= 81
            }
            val t = (((px - x1) * dx + (py - y1) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val nearestX = x1 + t * dx
            val nearestY = y1 + t * dy
            val xDistance = px - nearestX
            val yDistance = py - nearestY
            return xDistance * xDistance + yDistance * yDistance <= 81
        }

        private fun quadraticPoint(t: Double): Pair<Int, Int> {
            val point = pointAt(t)
            return point.first.roundToInt() to point.second.roundToInt()
        }

        fun pointAt(t: Double): Pair<Double, Double> {
            val inverse = 1.0 - t
            val x = inverse * inverse * from.first + 2 * inverse * t * control.first + t * t * to.first
            val y = inverse * inverse * from.second + 2 * inverse * t * control.second + t * t * to.second
            return x to y
        }
    }

    private data class NodeDrag(
        val selection: Selection,
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val height: Int
    )

    private data class ConnectionDrag(
        val from: Selection,
        val start: Pair<Int, Int>,
        var current: Pair<Int, Int>
    )

    private data class PropertiesDrag(val offsetX: Int, val offsetY: Int)

    private var profile: SandboxProfile = SandboxDefaults.newProfile(null)
    private var selected: Selection? = null
    private var hover: Selection? = null
    private var nodes: List<DrawNode> = emptyList()
    private var wires: List<DrawWire> = emptyList()
    private var nodeDrag: NodeDrag? = null
    private var connectionDrag: ConnectionDrag? = null
    private var propertiesDrag: PropertiesDrag? = null
    private var propertiesOwner: Selection? = null
    private var propertiesVisible = false
    private var propertiesPosition: Pair<Int, Int>? = null
    private var propertiesBounds: Rectangle? = null
    private var propertiesCloseBounds: Rectangle? = null
    private var dragMoved = false
    private var suppressNextClick = false
    private var runtimeStatus: SandboxSessionStatus = SandboxSessionStatus.STOPPED
    private var healthChecked = false
    private var onlineParticipants: Set<String> = emptySet()
    private var activityToken: Int? = null
    private var lastActivityMillis = 0L
    private var flowPhase = 0.0
    private var lastAnimationNanos = 0L
    private var pendingSingleClickTimer: Timer? = null
    private var pendingSingleClickSelection: Selection? = null
    private val dragOverrides = mutableMapOf<String, Pair<Int, Int>>()
    private var listener: ((Selection?) -> Unit)? = null
    private var activationListener: ((Selection?) -> Unit)? = null
    private var positionListener: ((Selection, Int, Int) -> Unit)? = null
    private var connectionListener: ((String, String, Boolean) -> Unit)? = null
    private var contextMenuListener: ((Selection, Point) -> Unit)? = null
    private var darDropListener: ((String, String) -> Unit)? = null
    private var darDropRejectedListener: ((String) -> Unit)? = null
    private var selectionDetails: List<String> = emptyList()
    private var overlayActions: List<OverlayAction> = emptyList()
    private var overlayActionListener: ((Selection, String) -> Unit)? = null
    private var overlayActionBounds: Map<String, Rectangle> = emptyMap()
    private var darDropParticipantId: String? = null
    private var darDropPoint: Point? = null
    private var darDropHint: String? = null
    private val singleClickDelayMillis = runCatching {
        val multiClick = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500
        (multiClick / 2).coerceIn(140, 220)
    }.getOrDefault(180)
    private val flowTimer = Timer(16) {
        advanceFlowAnimation()
    }.apply {
        isRepeats = true
    }

    init {
        preferredSize = graphSize()
        minimumSize = Dimension(360, 260)
        background = TopologyGraphTheme.canvas
        isOpaque = true
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                cancelPendingSingleClick()
                if (showContextMenuIfRequested(e)) return
                if (propertiesCloseBounds?.contains(e.x, e.y) == true) {
                    closePropertiesOverlay()
                    suppressNextClick = true
                    repaint()
                    return
                }
                overlayActionBounds.entries.firstOrNull { it.value.contains(e.x, e.y) }?.let { action ->
                    propertiesOwner?.let { owner -> overlayActionListener?.invoke(owner, action.key) }
                    suppressNextClick = true
                    repaint()
                    return
                }
                propertiesBounds?.takeIf { it.contains(e.x, e.y) }?.let {
                    propertiesDrag = PropertiesDrag(e.x - it.x, e.y - it.y)
                    suppressNextClick = true
                    dragMoved = false
                    return
                }
                val node = nodes.firstOrNull { it.contains(e.x, e.y) }
                dragMoved = false
                if (node != null) {
                    selected = node.selection
                    val port = connectionPort(node, e.x, e.y)
                    if (port != null) {
                        connectionDrag = ConnectionDrag(node.selection, port, e.x to e.y)
                    } else {
                        closePropertiesOverlay()
                        nodeDrag = NodeDrag(node.selection, e.x - node.x, e.y - node.y, node.w, node.h)
                    }
                    repaint()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (suppressNextClick) {
                    suppressNextClick = false
                    return
                }
                if (isContextMenuTrigger(e)) {
                    cancelPendingSingleClick()
                    return
                }
                if (dragMoved) return
                val node = nodes.firstOrNull { it.contains(e.x, e.y) }
                if (node != null) {
                    selected = node.selection
                    if (e.clickCount >= 2) {
                        cancelPendingSingleClick()
                        closePropertiesOverlay()
                        suppressNextClick = true
                        activationListener?.invoke(selected)
                    } else {
                        scheduleSingleClickSelection(node.selection)
                    }
                    repaint()
                    return
                }
                cancelPendingSingleClick()
                wires.firstOrNull { it.hit(e.x, e.y) }?.let { wire ->
                    connectionListener?.invoke(wire.participantId, wire.synchronizerId, !wire.connected)
                    return
                }
                selected = null
                closePropertiesOverlay()
                listener?.invoke(null)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (showContextMenuIfRequested(e)) return
                connectionDrag?.let { drag ->
                    val target = nodes.firstOrNull { it.contains(e.x, e.y) }?.selection
                    connectionFromDrag(drag.from, target)?.let { (participantId, synchronizerId) ->
                        connectionListener?.invoke(participantId, synchronizerId, true)
                    }
                }
                nodeDrag?.let { drag ->
                    val position = dragOverrides[drag.selection.nodeId()]
                    if (position != null && dragMoved) {
                        positionListener?.invoke(drag.selection, position.first, position.second)
                    }
                }
                connectionDrag = null
                nodeDrag = null
                propertiesDrag = null
                repaint()
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val next = nodes.firstOrNull { it.contains(e.x, e.y) }?.selection
                if (next != hover) {
                    hover = next
                }
                val node = nodes.firstOrNull { it.contains(e.x, e.y) }
                cursor = Cursor.getPredefinedCursor(
                    when {
                        propertiesCloseBounds?.contains(e.x, e.y) == true -> Cursor.HAND_CURSOR
                        propertiesBounds?.contains(e.x, e.y) == true -> Cursor.MOVE_CURSOR
                        node != null && connectionPort(node, e.x, e.y) != null -> Cursor.CROSSHAIR_CURSOR
                        node != null -> Cursor.MOVE_CURSOR
                        wires.any { it.hit(e.x, e.y) } -> Cursor.HAND_CURSOR
                        else -> Cursor.DEFAULT_CURSOR
                    }
                )
                repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                propertiesDrag?.let { drag ->
                    propertiesPosition = (e.x - drag.offsetX).coerceAtLeast(12) to (e.y - drag.offsetY).coerceAtLeast(12)
                    dragMoved = true
                    repaint()
                    return
                }
                connectionDrag?.let {
                    it.current = e.x to e.y
                    dragMoved = true
                    repaint()
                    return
                }
                nodeDrag?.let { drag ->
                    val x = (e.x - drag.offsetX).coerceAtLeast(12)
                    val y = (e.y - drag.offsetY).coerceAtLeast(12)
                    dragOverrides[drag.selection.nodeId()] = x to y
                    expandCanvasFor(x, y, drag.width, drag.height)
                    dragMoved = true
                    repaint()
                }
            }
        })
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                if (support.isDrop) {
                    val point = support.dropLocation.dropPoint
                    updateDarDropHover(point)
                }
                return true
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                val darPath = support.transferable.getTransferData(DataFlavor.stringFlavor)?.toString().orEmpty()
                val point = if (support.isDrop) support.dropLocation.dropPoint else darDropPoint
                val participantId = point?.let { participantDropTargetAt(it.x, it.y) }
                clearDarDropHover()
                return if (participantId != null && darPath.isNotBlank()) {
                    darDropListener?.invoke(darPath, participantId)
                    true
                } else {
                    darDropRejectedListener?.invoke("DARs are uploaded to participant nodes")
                    false
                }
            }

            override fun exportDone(source: JComponent?, data: java.awt.datatransfer.Transferable?, action: Int) {
                clearDarDropHover()
            }
        }
    }

    fun setProfile(profile: SandboxProfile) {
        this.profile = profile
        dragOverrides.clear()
        preferredSize = graphSize()
        revalidate()
        updateFlowTimer()
        repaint()
    }

    fun setRuntimeState(status: SandboxSessionStatus, health: List<HealthSnapshot>, activityToken: Int) {
        val wasAnimating = isRuntimeFlowEnabled()
        val nowMillis = System.currentTimeMillis()
        val freshHealth = health.filter { nowMillis - it.timestampMillis <= 30_000 }
        runtimeStatus = status
        healthChecked = freshHealth.isNotEmpty()
        onlineParticipants = freshHealth
            .filter { it.endpoint.kind == "json" && (it.live || it.ready) }
            .map { it.endpoint.nodeId }
            .toSet()
        val previousToken = this.activityToken
        this.activityToken = activityToken
        if (previousToken != null && previousToken != activityToken && isRuntimeFlowEnabled()) {
            pulseFlow()
        }
        if (wasAnimating != isRuntimeFlowEnabled()) {
            lastAnimationNanos = 0L
        }
        updateFlowTimer()
        repaint()
    }

    fun pulseFlow() {
        if (!isRuntimeFlowEnabled()) return
        lastActivityMillis = System.currentTimeMillis()
        updateFlowTimer()
        repaint()
    }

    internal fun isRuntimeFlowEnabledForTest(): Boolean = isRuntimeFlowEnabled()

    internal fun flowBoostForTest(): Double = activityBoost(System.currentTimeMillis())

    fun select(selection: Selection?) {
        val previous = selected
        selected = selection
        when {
            selection == null -> closePropertiesOverlay()
            selection != previous || propertiesOwner != selection || !propertiesVisible -> {
                propertiesOwner = selection
                propertiesVisible = true
                propertiesPosition = null
            }
        }
        repaint()
    }

    fun setSelectionDetails(details: String?, actions: List<OverlayAction> = emptyList()) {
        selectionDetails = details
            ?.lines()
            ?.map { it.trimEnd() }
            ?.dropWhile { it.isBlank() }
            ?.dropLastWhile { it.isBlank() }
            .orEmpty()
        overlayActions = actions
        if (selectionDetails.isEmpty()) {
            closePropertiesOverlay()
        }
        repaint()
    }

    data class OverlayAction(val id: String, val label: String)

    internal fun isPropertiesOverlayVisibleForTest(): Boolean = propertiesVisible
    internal fun propertiesOverlayBoundsForTest(): Rectangle? = propertiesBounds
    internal fun propertiesOverlayWireIntersectionsForTest(): Int =
        propertiesBounds?.let { wireSampleCountInside(it) } ?: 0

    internal fun flushPendingSingleClickForTest() {
        val selection = pendingSingleClickSelection ?: return
        cancelPendingSingleClick()
        selected = selection
        listener?.invoke(selection)
        repaint()
    }

    fun setSelectionListener(listener: (Selection?) -> Unit) {
        this.listener = listener
    }

    fun setActivationListener(listener: (Selection?) -> Unit) {
        this.activationListener = listener
    }

    fun setPositionListener(listener: (Selection, Int, Int) -> Unit) {
        this.positionListener = listener
    }

    fun setConnectionListener(listener: (String, String, Boolean) -> Unit) {
        this.connectionListener = listener
    }

    fun setContextMenuListener(listener: (Selection, Point) -> Unit) {
        this.contextMenuListener = listener
    }

    fun setDarDropListener(listener: (String, String) -> Unit) {
        this.darDropListener = listener
    }

    fun setDarDropRejectedListener(listener: (String) -> Unit) {
        this.darDropRejectedListener = listener
    }

    fun setOverlayActionListener(listener: (Selection, String) -> Unit) {
        this.overlayActionListener = listener
    }

    internal fun participantDropTargetForTest(x: Int, y: Int): String? =
        participantDropTargetAt(x, y)

    internal fun dropDarForTest(path: String, x: Int, y: Int): Boolean {
        val participantId = participantDropTargetAt(x, y)
        return if (participantId != null) {
            darDropListener?.invoke(path, participantId)
            true
        } else {
            darDropRejectedListener?.invoke("DARs are uploaded to participant nodes")
            false
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        if (propertiesCloseBounds?.contains(event.x, event.y) == true) return "Close details"
        if (propertiesBounds?.contains(event.x, event.y) == true) return "Drag details"
        val node = nodes.firstOrNull { it.contains(event.x, event.y) }
        if (node != null && connectionPort(node, event.x, event.y) != null) return "Drag to a compatible node to connect"
        wires.firstOrNull { it.hit(event.x, event.y) }?.let {
            return if (it.connected) "Click wire to disconnect" else "Click wire to connect"
        }
        return node?.let {
            val details = listOfNotNull(it.detail, it.secondaryDetail).joinToString(" - ")
            if (it.warning.isNullOrBlank()) "${it.label} - $details" else "${it.label} - $details - ${it.warning}"
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            drawCanvas(g2)
            nodes = layoutNodes()
            drawEdges(g2, nodes)
            nodes.forEach { drawNode(g2, it) }
            drawConnectionDrag(g2)
            drawDarDropHint(g2)
            drawSelectedProperties(g2, nodes)
        } finally {
            g2.dispose()
        }
    }

    override fun addNotify() {
        super.addNotify()
        updateFlowTimer()
    }

    override fun removeNotify() {
        cancelPendingSingleClick()
        flowTimer.stop()
        super.removeNotify()
    }

    private fun layoutNodes(): List<DrawNode> {
        val result = mutableListOf<DrawNode>()
        val participantCount = max(1, profile.participants.size)
        val syncCount = max(1, profile.synchronizers.size)
        val canvasWidth = width.coerceAtLeast(320)
        val canvasHeight = max(height, graphSize().height)
        val participantX = if (narrowCanvas()) 20 else 90
        val syncX = if (narrowCanvas()) 20 else min(max(460, canvasWidth / 2 + 90), canvasWidth - 340)
        val participantSpacing = canvasHeight / (participantCount + 1)
        val syncSpacing = canvasHeight / (syncCount + 1)

        profile.participants.forEachIndexed { index, participant ->
            val selection = Selection.Participant(participant.id)
            val defaultY = if (narrowCanvas()) 24 + index * 118 else participantSpacing * (index + 1) - 34
            val position = positionFor(selection, participantX, defaultY)
            val darNames = profile.assignedDarFileNames(participant.id)
            result += DrawNode(
                selection,
                "${TopologyNodeIcons.PARTICIPANT} - Participant - ${participant.name}",
                "${profile.bindings.count { it.participantId == participant.id && it.connected }} configured synchronizer(s)",
                darNodeSummary(darNames),
                position.first,
                position.second,
                260,
                82,
                TopologyGraphTheme.participantFill,
                TopologyGraphTheme.participantBorder,
                warning = if (darNames.isEmpty()) {
                    if (profile.hasUploadedDarAssignments()) "No DAR assigned to this participant" else "No DARs assigned anywhere"
                } else {
                    null
                }
            )
        }
        profile.synchronizers.forEachIndexed { index, synchronizer ->
            val y = if (narrowCanvas()) 58 + (participantCount + index) * 118 else syncSpacing * (index + 1)
            val syncSelection = Selection.Synchronizer(synchronizer.id)
            val syncPosition = positionFor(syncSelection, syncX, y - 34)
            result += DrawNode(
                syncSelection,
                "${TopologyNodeIcons.SYNCHRONIZER} - Synchronizer - ${synchronizer.name}",
                if (SandboxDefaults.isSharedSynchronizer(synchronizer.id, synchronizer.name)) "shared route" else "sync domain",
                null,
                syncPosition.first,
                syncPosition.second,
                270,
                68,
                TopologyGraphTheme.fill(syncSelection),
                TopologyGraphTheme.border(syncSelection)
            )
        }
        return result
    }

    private fun drawCanvas(g2: Graphics2D) {
        g2.color = TopologyGraphTheme.canvas
        g2.fillRect(0, 0, width, height)
        g2.stroke = BasicStroke(1f)
        g2.color = withAlpha(TopologyGraphTheme.grid, 80)
        val step = 40
        var x = 0
        while (x < width) {
            g2.drawLine(x, 0, x, height)
            x += step
        }
        var y = 0
        while (y < height) {
            g2.drawLine(0, y, width, y)
            y += step
        }
    }

    private fun drawEdges(g2: Graphics2D, nodes: List<DrawNode>) {
        val bySelection = nodes.associateBy { it.selection }
        val nextWires = mutableListOf<DrawWire>()
        profile.bindings.forEach { binding ->
            val p = bySelection[Selection.Participant(binding.participantId)]
            val s = bySelection[Selection.Synchronizer(binding.synchronizerId)]
            if (p != null && s != null) {
                val from = p.rightPort()
                val to = s.leftPort()
                val control = wireControl(from, to)
                val targetColor = if (SandboxDefaults.isSharedSynchronizer(binding.synchronizerId)) {
                    TopologyGraphTheme.globalSyncBorder
                } else {
                    TopologyGraphTheme.edge
                }
                val wire = DrawWire(binding.participantId, binding.synchronizerId, binding.connected, from, to, control)
                if (binding.connected) {
                    g2.stroke = BasicStroke(1.5f)
                    g2.color = targetColor
                    drawWire(g2, wire.from, wire.to, wire.control)
                } else {
                    drawDormantWire(g2, wire)
                }
                nextWires += wire
            }
        }
        wires = nextWires
    }

    private fun drawConnectionDrag(g2: Graphics2D) {
        val drag = connectionDrag ?: return
        g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.color = withAlpha(TopologyGraphTheme.hover, 210)
        drawWire(g2, drag.start, drag.current, wireControl(drag.start, drag.current))
    }

    private fun drawNode(g2: Graphics2D, node: DrawNode) {
        val shape = RoundRectangle2D.Float(
            node.x.toFloat(),
            node.y.toFloat(),
            node.w.toFloat(),
            node.h.toFloat(),
            node.cornerRadius,
            node.cornerRadius
        )
        g2.color = node.fill
        g2.fill(shape)
        val header = RoundRectangle2D.Float(
            (node.x + 1).toFloat(),
            (node.y + 1).toFloat(),
            (node.w - 2).toFloat(),
            24f,
            node.cornerRadius,
            node.cornerRadius
        )
        g2.color = withAlpha(node.border, 34)
        g2.fill(header)
        val isSelected = selected == node.selection
        val isHover = hover == node.selection
        val isDarDropTarget = (node.selection as? Selection.Participant)?.id == darDropParticipantId
        val border = when {
            isDarDropTarget -> TopologyGraphTheme.hover
            isSelected -> TopologyGraphTheme.selected
            isHover -> TopologyGraphTheme.hover
            else -> node.border
        }
        g2.stroke = BasicStroke(if (isSelected || isDarDropTarget) 2.8f else if (isHover) 2.0f else 1.4f)
        g2.color = border
        g2.draw(shape)
        drawNodePorts(g2, node, node.border)
        g2.color = TopologyGraphTheme.text
        g2.font = font.deriveFont(Font.BOLD, TopologyGraphTheme.fontSize(13f))
        g2.drawString(elide(node.label, node.w - if (node.warning == null) 24 else 44, g2), node.x + 12, node.y + 26)
        node.warning?.let {
            drawWarningBadge(g2, node.x + node.w - 23, node.y + 7)
        }
        g2.font = font.deriveFont(TopologyGraphTheme.fontSize(11f))
        g2.color = if (node.warning == null) TopologyGraphTheme.detail else TopologyGraphTheme.warning
        g2.drawString(elide(node.detail, node.w - 24, g2), node.x + 12, node.y + 50)
        node.secondaryDetail?.let {
            g2.font = font.deriveFont(TopologyGraphTheme.fontSize(10.7f))
            g2.color = if (node.warning == null) TopologyGraphTheme.detail else TopologyGraphTheme.warning
            g2.drawString(elide(it, node.w - 24, g2), node.x + 12, node.y + 68)
        }
    }

    private fun drawDarDropHint(g2: Graphics2D) {
        val point = darDropPoint ?: return
        val text = darDropHint ?: return
        g2.font = font.deriveFont(Font.BOLD, TopologyGraphTheme.fontSize(11f))
        val width = g2.fontMetrics.stringWidth(text) + 20
        val x = point.x.coerceIn(8, (this.width - width - 8).coerceAtLeast(8))
        val y = (point.y + 18).coerceIn(28, (this.height - 34).coerceAtLeast(28))
        val valid = darDropParticipantId != null
        val color = if (valid) TopologyGraphTheme.hover else TopologyGraphTheme.warning
        val shape = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), 26f, 10f, 10f)
        g2.color = withAlpha(TopologyGraphTheme.panel, 238)
        g2.fill(shape)
        g2.stroke = BasicStroke(1.4f)
        g2.color = color
        g2.draw(shape)
        g2.color = if (valid) TopologyGraphTheme.text else TopologyGraphTheme.warning
        g2.drawString(text, x + 10, y + 17)
    }

    private fun drawWarningBadge(g2: Graphics2D, x: Int, y: Int) {
        g2.color = withAlpha(TopologyGraphTheme.warning, 48)
        g2.fillOval(x, y, 15, 15)
        g2.stroke = BasicStroke(1.2f)
        g2.color = TopologyGraphTheme.warning
        g2.drawOval(x, y, 15, 15)
        g2.font = font.deriveFont(Font.BOLD, TopologyGraphTheme.fontSize(10f))
        g2.drawString("!", x + 6, y + 11)
    }

    private fun drawNodePorts(g2: Graphics2D, node: DrawNode, border: Color) {
        when (node.selection) {
            is Selection.Participant -> drawPort(g2, node.rightPort(), border)
            is Selection.Synchronizer -> drawPort(g2, node.leftPort(), border)
        }
    }

    private fun drawSelectedProperties(g2: Graphics2D, nodes: List<DrawNode>) {
        if (!propertiesVisible || selectionDetails.isEmpty()) {
            propertiesBounds = null
            propertiesCloseBounds = null
            return
        }
        val selectedNode = nodes.firstOrNull { it.selection == propertiesOwner } ?: return

        val cardWidth = 330
        val horizontalGap = 18
        val lineHeight = 17
        val maxBodyLines = 15
        val visibleLines = selectionDetails.take(maxBodyLines)
        val clipped = selectionDetails.size > maxBodyLines
        val actionsHeight = if (overlayActions.isEmpty()) 0 else 38
        val cardHeight = (52 + visibleLines.size * lineHeight + if (clipped) lineHeight else 0 + actionsHeight).coerceAtMost(380)
        val position = propertiesPosition ?: defaultPropertiesPosition(selectedNode, cardWidth, cardHeight, horizontalGap)
        val x = position.first.coerceIn(12, (width - cardWidth - 12).coerceAtLeast(12))
        val y = position.second.coerceIn(12, (height - cardHeight - 12).coerceAtLeast(12))
        val border = selectedNode.border
        val shape = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 10f, 10f)
        propertiesBounds = Rectangle(x, y, cardWidth, cardHeight)
        propertiesCloseBounds = Rectangle(x + cardWidth - 28, y + 7, 18, 18)

        g2.stroke = BasicStroke(10f)
        g2.color = withAlpha(border, 32)
        g2.draw(shape)
        g2.color = withAlpha(selectedNode.fill, 244)
        g2.fill(shape)
        g2.color = withAlpha(border, 42)
        g2.fillRect(x + 2, y + 2, cardWidth - 4, 26)
        g2.stroke = BasicStroke(1.8f)
        g2.color = border
        g2.draw(shape)

        val title = selectionDetails.firstOrNull().orEmpty()
        g2.font = font.deriveFont(Font.BOLD, TopologyGraphTheme.fontSize(13f))
        g2.color = TopologyGraphTheme.text
        g2.drawString(elide(title, cardWidth - 52, g2), x + 13, y + 21)
        drawCloseButton(g2, propertiesCloseBounds!!, border)

        g2.font = font.deriveFont(TopologyGraphTheme.fontSize(11.5f))
        var textY = y + 48
        selectionDetails.drop(1).take(maxBodyLines - 1).forEach { line ->
            if (line.isBlank()) {
                textY += 7
            } else {
                g2.color = if (line.endsWith(":")) TopologyGraphTheme.text else TopologyGraphTheme.detail
                g2.drawString(elide(line.trim(), cardWidth - 26, g2), x + 13, textY)
                textY += lineHeight
            }
        }
        if (clipped) {
            g2.color = withAlpha(border, 210)
            g2.drawString("More details in the selection inspector", x + 13, textY)
            textY += lineHeight
        }
        overlayActionBounds = drawOverlayActions(g2, x + 13, (y + cardHeight - 32).coerceAtLeast(textY + 4), cardWidth - 26, border)
    }

    private fun drawOverlayActions(g2: Graphics2D, x: Int, y: Int, maxWidth: Int, border: Color): Map<String, Rectangle> {
        if (overlayActions.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Rectangle>()
        var cursorX = x
        g2.font = font.deriveFont(Font.BOLD, TopologyGraphTheme.fontSize(10.5f))
        overlayActions.forEach { action ->
            val w = (g2.fontMetrics.stringWidth(action.label) + 20).coerceAtMost(maxWidth)
            val bounds = Rectangle(cursorX, y, w, 24)
            val shape = RoundRectangle2D.Float(bounds.x.toFloat(), bounds.y.toFloat(), bounds.width.toFloat(), bounds.height.toFloat(), 12f, 12f)
            g2.color = withAlpha(border, 32)
            g2.fill(shape)
            g2.stroke = BasicStroke(1.2f)
            g2.color = border
            g2.draw(shape)
            g2.color = TopologyGraphTheme.text
            g2.drawString(elide(action.label, w - 14, g2), bounds.x + 10, bounds.y + 16)
            result[action.id] = bounds
            cursorX += w + 7
        }
        return result
    }

    private fun drawCloseButton(g2: Graphics2D, bounds: Rectangle, color: Color) {
        g2.stroke = BasicStroke(1.3f)
        g2.color = withAlpha(color, 80)
        g2.drawOval(bounds.x, bounds.y, bounds.width, bounds.height)
        g2.color = TopologyGraphTheme.text
        val pad = 5
        g2.drawLine(bounds.x + pad, bounds.y + pad, bounds.x + bounds.width - pad, bounds.y + bounds.height - pad)
        g2.drawLine(bounds.x + bounds.width - pad, bounds.y + pad, bounds.x + pad, bounds.y + bounds.height - pad)
    }

    private fun graphSize(): Dimension {
        val height = max(
            500,
            max(
                profile.participants.size.coerceAtLeast(1) * 118 + 120,
                profile.synchronizers.size.coerceAtLeast(1) * 118 + 120
            )
        )
        val positioned = profile.topologyPositions.map { it.x to it.y } + dragOverrides.values
        val maxX = positioned.maxOfOrNull { it.first } ?: 0
        val maxY = positioned.maxOfOrNull { it.second } ?: 0
        return Dimension(max(980, maxX + 520), if (narrowCanvas()) max(500, (profile.participants.size + profile.synchronizers.size) * 118 + 100) else max(height, maxY + 260))
    }

    private fun expandCanvasFor(x: Int, y: Int, nodeWidth: Int, nodeHeight: Int) {
        val next = Dimension(
            max(preferredSize.width, x + nodeWidth + 360),
            max(preferredSize.height, y + nodeHeight + 220)
        )
        if (next != preferredSize) {
            preferredSize = next
            revalidate()
        }
    }

    private fun drawWire(g2: Graphics2D, from: Pair<Int, Int>, to: Pair<Int, Int>, control: Pair<Int, Int>) {
        val wireColor = g2.color
        g2.draw(
            QuadCurve2D.Float(
                from.first.toFloat(),
                from.second.toFloat(),
                control.first.toFloat(),
                control.second.toFloat(),
                to.first.toFloat(),
                to.second.toFloat()
            )
        )
        drawPort(g2, from, wireColor)
        drawPort(g2, to, wireColor)
        g2.color = wireColor
    }

    private fun drawRuntimeWire(g2: Graphics2D, wire: DrawWire, color: Color, intensity: Double) {
        val path = QuadCurve2D.Float(
            wire.from.first.toFloat(),
            wire.from.second.toFloat(),
            wire.control.first.toFloat(),
            wire.control.second.toFloat(),
            wire.to.first.toFloat(),
            wire.to.second.toFloat()
        )

        g2.stroke = BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.color = withAlpha(color, if (intensity > 0.0) (30 + intensity * 42).roundToInt() else 22)
        g2.draw(path)

        g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.color = withAlpha(color, if (intensity > 0.0) 210 else 150)
        g2.draw(path)

        if (intensity > 0.0) {
            val dash = floatArrayOf(10f, (25f - (8f * intensity).toFloat()).coerceAtLeast(14f))
            g2.stroke = BasicStroke(
                (1.35f + intensity.toFloat() * 1.15f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                0f,
                dash,
                (flowPhase * 96f).toFloat()
            )
            g2.color = withAlpha(Color.WHITE, (38 + intensity * 70).roundToInt())
            g2.draw(path)
            drawFlowPackets(g2, wire, color, intensity)
        }

        drawPort(g2, wire.from, color)
        drawPort(g2, wire.to, color)
    }

    private fun drawDormantWire(g2: Graphics2D, wire: DrawWire) {
        val path = QuadCurve2D.Float(
            wire.from.first.toFloat(),
            wire.from.second.toFloat(),
            wire.control.first.toFloat(),
            wire.control.second.toFloat(),
            wire.to.first.toFloat(),
            wire.to.second.toFloat()
        )
        g2.stroke = BasicStroke(
            1.4f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
            0f,
            floatArrayOf(7f, 9f),
            0f
        )
        g2.color = withAlpha(TopologyGraphTheme.edgeMuted, 135)
        g2.draw(path)
        drawPort(g2, wire.from, TopologyGraphTheme.edgeMuted)
        drawPort(g2, wire.to, TopologyGraphTheme.edgeMuted)
    }

    private fun drawFlowPackets(g2: Graphics2D, wire: DrawWire, color: Color, intensity: Double) {
        val packetCount = if (intensity > 0.7) 4 else 3
        val radius = (2.4 + 2.8 * intensity).toFloat()
        repeat(packetCount) { index ->
            val t = (flowPhase + index.toDouble() / packetCount) % 1.0
            val point = wire.pointAt(t)
            val x = point.first.toFloat()
            val y = point.second.toFloat()
            g2.color = withAlpha(color, (90 + 120 * intensity).roundToInt())
            g2.fillOval(
                (x - radius).roundToInt(),
                (y - radius).roundToInt(),
                (radius * 2).roundToInt(),
                (radius * 2).roundToInt()
            )
            g2.color = withAlpha(Color.WHITE, (50 + 85 * intensity).roundToInt())
            val core = (radius * 0.42f).coerceAtLeast(1.1f)
            g2.fillOval(
                (x - core).roundToInt(),
                (y - core).roundToInt(),
                (core * 2).roundToInt(),
                (core * 2).roundToInt()
            )
        }
    }

    private fun flowIntensityFor(wire: DrawWire): Double {
        if (!isRuntimeFlowEnabled()) return 0.0
        if (healthChecked && wire.participantId !in onlineParticipants) return 0.0
        return 0.28 + 0.72 * activityBoost(System.currentTimeMillis())
    }

    private fun activityBoost(nowMillis: Long): Double {
        val age = nowMillis - lastActivityMillis
        if (age !in 0..4_500) return 0.0
        return 1.0 - age / 4_500.0
    }

    // A configured edge is not evidence of transaction traffic.
    private fun isRuntimeFlowEnabled(): Boolean = false

    private fun advanceFlowAnimation() {
        if (!isRuntimeFlowEnabled()) {
            updateFlowTimer()
            return
        }
        val now = System.nanoTime()
        val elapsedSeconds = if (lastAnimationNanos == 0L) {
            0.0
        } else {
            ((now - lastAnimationNanos) / 1_000_000_000.0).coerceIn(0.0, 0.08)
        }
        lastAnimationNanos = now
        val speed = 0.085 + 0.45 * activityBoost(System.currentTimeMillis())
        flowPhase = (flowPhase + elapsedSeconds * speed) % 1.0
        repaint()
        updateFlowTimer()
    }

    private fun updateFlowTimer() {
        val shouldRun = isShowing && isRuntimeFlowEnabled()
        when {
            shouldRun && !flowTimer.isRunning -> {
                lastAnimationNanos = 0L
                flowTimer.start()
            }
            !shouldRun && flowTimer.isRunning -> flowTimer.stop()
        }
    }

    private fun scheduleSingleClickSelection(selection: Selection) {
        cancelPendingSingleClick()
        pendingSingleClickSelection = selection
        pendingSingleClickTimer = Timer(singleClickDelayMillis) {
            val pending = pendingSingleClickSelection ?: return@Timer
            cancelPendingSingleClick()
            selected = pending
            listener?.invoke(pending)
            repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelPendingSingleClick() {
        pendingSingleClickTimer?.stop()
        pendingSingleClickTimer = null
        pendingSingleClickSelection = null
    }

    private fun showContextMenuIfRequested(e: MouseEvent): Boolean {
        if (!isContextMenuTrigger(e)) return false
        val node = nodes.firstOrNull { it.contains(e.x, e.y) } ?: return false
        selected = node.selection
        closePropertiesOverlay()
        contextMenuListener?.invoke(node.selection, e.point)
        repaint()
        return true
    }

    private fun isContextMenuTrigger(e: MouseEvent): Boolean =
        e.isPopupTrigger || e.button == MouseEvent.BUTTON3

    private fun defaultPropertiesPosition(
        selectedNode: DrawNode,
        cardWidth: Int,
        cardHeight: Int,
        gap: Int
    ): Pair<Int, Int> {
        val candidates = listOf(
            selectedNode.x + selectedNode.w + gap to selectedNode.y - 12,
            selectedNode.centerX() - cardWidth / 2 to selectedNode.y + selectedNode.h + gap,
            selectedNode.centerX() - cardWidth / 2 to selectedNode.y - cardHeight - gap,
            selectedNode.x - cardWidth - gap to selectedNode.y - 12
        )
        return candidates
            .mapIndexed { index, raw ->
                val clamped = raw.first.coerceIn(12, (width - cardWidth - 12).coerceAtLeast(12)) to
                    raw.second.coerceIn(12, (height - cardHeight - 12).coerceAtLeast(12))
                val rect = Rectangle(clamped.first, clamped.second, cardWidth, cardHeight)
                val rawFits = raw.first == clamped.first && raw.second == clamped.second
                val selectedOverlap = overlapArea(rect, Rectangle(selectedNode.x, selectedNode.y, selectedNode.w, selectedNode.h))
                val wireOverlap = wireSampleCountInside(rect)
                val score = (if (rawFits) 0 else 10_000) + wireOverlap * 250 + selectedOverlap + index * 10
                score to clamped
            }
            .minByOrNull { it.first }
            ?.second
            ?: (selectedNode.x + selectedNode.w + gap to selectedNode.y - 12)
    }

    private fun wireSampleCountInside(rectangle: Rectangle): Int =
        wires.sumOf { wire ->
            (0..36).count { step ->
                val point = wire.pointAt(step / 36.0)
                rectangle.contains(point.first.roundToInt(), point.second.roundToInt())
            }
        }

    private fun overlapArea(first: Rectangle, second: Rectangle): Int {
        val x1 = max(first.x, second.x)
        val y1 = max(first.y, second.y)
        val x2 = min(first.x + first.width, second.x + second.width)
        val y2 = min(first.y + first.height, second.y + second.height)
        return if (x2 <= x1 || y2 <= y1) 0 else (x2 - x1) * (y2 - y1)
    }

    private fun wireControl(from: Pair<Int, Int>, to: Pair<Int, Int>): Pair<Int, Int> {
        val horizontal = (to.first - from.first).coerceAtLeast(80)
        val vertical = to.second - from.second
        val direction = when {
            vertical > 0 -> 1
            vertical < 0 -> -1
            else -> 0
        }
        val bend = (kotlin.math.abs(vertical) / 3 + 22).coerceAtMost(96) * direction
        return from.first + horizontal / 2 to from.second + bend
    }

    private fun drawPort(g2: Graphics2D, point: Pair<Int, Int>, color: Color) {
        g2.color = color
        g2.fillOval(point.first - 4, point.second - 4, 8, 8)
        g2.color = TopologyGraphTheme.canvas
        g2.fillOval(point.first - 2, point.second - 2, 4, 4)
    }

    private fun elide(value: String, maxWidth: Int, g2: Graphics2D): String {
        if (g2.fontMetrics.stringWidth(value) <= maxWidth) return value
        val ellipsis = "..."
        var candidate = value
        while (candidate.isNotEmpty() && g2.fontMetrics.stringWidth(candidate + ellipsis) > maxWidth) {
            candidate = candidate.dropLast(1)
        }
        return if (candidate.isEmpty()) ellipsis else candidate + ellipsis
    }

    private fun withAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun closePropertiesOverlay() {
        propertiesVisible = false
        propertiesOwner = null
        propertiesPosition = null
        propertiesBounds = null
        propertiesCloseBounds = null
        overlayActionBounds = emptyMap()
        propertiesDrag = null
    }

    private fun updateDarDropHover(point: Point) {
        darDropPoint = point
        val participantId = participantDropTargetAt(point.x, point.y)
        darDropParticipantId = participantId
        darDropHint = if (participantId != null) {
            val name = profile.participant(participantId)?.name ?: participantId
            "Upload DAR to $name"
        } else {
            "DARs are uploaded to participant nodes"
        }
        repaint()
    }

    private fun clearDarDropHover() {
        darDropParticipantId = null
        darDropPoint = null
        darDropHint = null
        repaint()
    }

    private fun participantDropTargetAt(x: Int, y: Int): String? {
        val source = if (nodes.isEmpty()) layoutNodes() else nodes
        return source.firstOrNull {
            it.selection is Selection.Participant && it.contains(x, y)
        }?.selection?.let { (it as Selection.Participant).id }
    }

    private fun positionFor(selection: Selection, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        if (narrowCanvas()) return defaultX to defaultY
        dragOverrides[selection.nodeId()]?.let { return it }
        val persisted = profile.topologyPositions.firstOrNull { it.nodeId == selection.nodeId() }
        return if (persisted == null) defaultX to defaultY else persisted.x to persisted.y
    }

    private fun connectionPort(node: DrawNode, px: Int, py: Int): Pair<Int, Int>? {
        val candidate = when (node.selection) {
            is Selection.Participant -> node.rightPort()
            is Selection.Synchronizer -> node.leftPort()
        }
        val dx = px - candidate.first
        val dy = py - candidate.second
        return candidate.takeIf { dx * dx + dy * dy <= 144 }
    }

    private fun connectionFromDrag(from: Selection, target: Selection?): Pair<String, String>? =
        when {
            from is Selection.Participant && target is Selection.Synchronizer -> from.id to target.id
            from is Selection.Synchronizer && target is Selection.Participant -> target.id to from.id
            else -> null
        }

    private fun Selection.nodeId(): String =
        when (this) {
            is Selection.Participant -> id
            is Selection.Synchronizer -> id
        }
}

/** Native node list provides keyboard selection, accessibility, and IDE scaling. */
class TopologyComponentPalettePanel : JPanel(java.awt.BorderLayout()) {
    private data class Entry(val selection: TopologyGraphPanel.Selection, val label: String, val detail: String) {
        override fun toString() = label
    }
    private val model = javax.swing.DefaultListModel<Entry>()
    private val list = com.intellij.ui.components.JBList(model)
    private var listener: ((TopologyGraphPanel.Selection?) -> Unit)? = null
    private var updating = false

    init {
        minimumSize = Dimension(170, 120)
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        list.getAccessibleContext().accessibleName = "Topology nodes"
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(owner: javax.swing.JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(owner, value, index, selected, focus)
                border = com.intellij.util.ui.JBUI.Borders.empty(8, 6)
                val entry = value as? Entry
                if (!selected && entry != null) foreground = TopologyGraphTheme.border(entry.selection)
                toolTipText = entry?.detail
                return this
            }
        }
        list.addListSelectionListener { if (!updating && !it.valueIsAdjusting) listener?.invoke(list.selectedValue?.selection) }
        add(list, java.awt.BorderLayout.CENTER)
        setProfile(SandboxDefaults.newProfile(null))
    }

    fun setProfile(profile: SandboxProfile) {
        val entries = profile.participants.map { Entry(TopologyGraphPanel.Selection.Participant(it.id),
            "Participant · ${it.name}", darPaletteSummary(profile.assignedDarFileNames(it.id))) } +
            profile.synchronizers.map { Entry(TopologyGraphPanel.Selection.Synchronizer(it.id), "Synchronizer · ${it.name}",
                "${profile.bindings.count { binding -> binding.synchronizerId == it.id && binding.connected }} configured participants") }
        if ((0 until model.size()).map(model::getElementAt) == entries) return
        val selected = list.selectedValue?.selection
        updating = true
        try { model.clear(); entries.forEach(model::addElement); select(selected) } finally { updating = false }
        revalidate(); repaint()
    }

    fun select(selection: TopologyGraphPanel.Selection?) {
        val wasUpdating = updating; updating = true
        try { list.selectedIndex = (0 until model.size()).firstOrNull { model.getElementAt(it).selection == selection } ?: -1 }
        finally { updating = wasUpdating }
    }

    fun setSelectionListener(listener: (TopologyGraphPanel.Selection?) -> Unit) { this.listener = listener }
}
