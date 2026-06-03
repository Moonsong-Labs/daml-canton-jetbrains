package com.moonsonglabs.daml.sandbox

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import kotlin.math.min

internal class ProfileComboBox(
    private val model: DefaultComboBoxModel<SandboxProfile>,
    private val onDeleteProfile: (SandboxProfile) -> Unit
) : JPanel(BorderLayout(8, 0)) {
    private val listeners = mutableListOf<ActionListener>()
    private val nameLabel = JBLabel()
    private val arrowLabel = JBLabel("v", SwingConstants.CENTER)
    private var popup: JBPopup? = null
    private var selectedIndexValue = -1
    private var hover = false

    val isDeletingProfileFromPopup: Boolean = false

    var selectedIndex: Int
        get() = selectedIndexValue
        set(value) {
            selectIndex(value, notify = true)
        }

    val selectedItem: Any?
        get() = selectedProfile()

    init {
        isOpaque = false
        background = ProfileSelectorTheme.background
        foreground = ProfileSelectorTheme.text
        border = JBUI.Borders.empty(5, 10)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        nameLabel.foreground = ProfileSelectorTheme.text
        nameLabel.font = nameLabel.font.deriveFont(Font.PLAIN, 12f)
        arrowLabel.foreground = ProfileSelectorTheme.muted
        arrowLabel.font = arrowLabel.font.deriveFont(Font.BOLD, 12f)
        add(nameLabel, BorderLayout.CENTER)
        add(arrowLabel, BorderLayout.EAST)
        val openPopupListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                showProfilePopup()
            }
        }
        addMouseListener(openPopupListener)
        nameLabel.addMouseListener(openPopupListener)
        arrowLabel.addMouseListener(openPopupListener)
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = reconcileSelection()
            override fun intervalRemoved(e: ListDataEvent) = reconcileSelection()
            override fun contentsChanged(e: ListDataEvent) = reconcileSelection()
        })
        reconcileSelection()
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width.coerceAtLeast(260), 34)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (hover) ProfileSelectorTheme.hover else ProfileSelectorTheme.background
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = if (hover) ProfileSelectorTheme.focus else ProfileSelectorTheme.border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    fun addActionListener(listener: ActionListener) {
        listeners += listener
    }

    private fun showProfilePopup() {
        popup?.cancel()
        val rows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = ProfileSelectorTheme.popup
            border = JBUI.Borders.empty(4)
        }
        if (model.size == 0) {
            rows.add(emptyRow())
        } else {
            for (index in 0 until model.size) {
                rows.add(profileRow(model.getElementAt(index), index))
                if (index < model.size - 1) rows.add(Box.createVerticalStrut(2))
            }
        }
        val width = maxOf(width, preferredSize.width, 300)
        val height = min(320, 12 + (model.size.coerceAtLeast(1) * 40))
        val scroll = JBScrollPane(rows).apply {
            preferredSize = Dimension(width, height)
            border = BorderFactory.createLineBorder(ProfileSelectorTheme.border)
            viewport.background = ProfileSelectorTheme.popup
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, rows)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()
            .also { it.showUnderneathOf(this) }
    }

    private fun profileRow(profile: SandboxProfile, index: Int): JComponent {
        val selected = index == selectedIndexValue
        val row = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = true
            background = if (selected) ProfileSelectorTheme.selected else ProfileSelectorTheme.popup
            border = JBUI.Borders.empty(5, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            toolTipText = profile.id
        }
        val label = JBLabel(profile.name).apply {
            foreground = ProfileSelectorTheme.text
            font = font.deriveFont(if (selected) Font.BOLD else Font.PLAIN)
        }
        val deleteButton = JButton(AllIcons.Actions.GC).apply {
            toolTipText = "Delete profile"
            isEnabled = this@ProfileComboBox.model.size > 1
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(30, 26)
            addActionListener {
                popup?.cancel()
                onDeleteProfile(profile)
            }
        }
        row.add(label, BorderLayout.CENTER)
        row.add(deleteButton, BorderLayout.EAST)
        val rowListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                row.background = if (selected) ProfileSelectorTheme.selected else ProfileSelectorTheme.hover
            }

            override fun mouseExited(e: MouseEvent) {
                row.background = if (selected) ProfileSelectorTheme.selected else ProfileSelectorTheme.popup
            }

            override fun mouseClicked(e: MouseEvent) {
                popup?.cancel()
                selectIndex(index, notify = true)
            }
        }
        row.addMouseListener(rowListener)
        label.addMouseListener(rowListener)
        return row
    }

    private fun emptyRow(): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = true
            background = ProfileSelectorTheme.popup
            border = JBUI.Borders.empty(8, 10)
            add(JBLabel("No profiles").apply {
                foreground = ProfileSelectorTheme.muted
            })
        }

    private fun selectIndex(index: Int, notify: Boolean) {
        val nextIndex = index.takeIf { it in 0 until model.size } ?: -1
        if (nextIndex == selectedIndexValue) {
            renderSelected()
            return
        }
        selectedIndexValue = nextIndex
        model.selectedItem = selectedProfile()
        renderSelected()
        if (notify) {
            val event = ActionEvent(this, ActionEvent.ACTION_PERFORMED, "profileSelected")
            listeners.toList().forEach { it.actionPerformed(event) }
        }
    }

    private fun reconcileSelection() {
        if (selectedIndexValue !in 0 until model.size) {
            selectedIndexValue = if (model.size > 0) 0 else -1
        }
        renderSelected()
    }

    private fun selectedProfile(): SandboxProfile? =
        selectedIndexValue.takeIf { it in 0 until model.size }?.let(model::getElementAt)

    private fun renderSelected() {
        nameLabel.text = selectedProfile()?.name ?: "No profiles"
        nameLabel.foreground = if (selectedProfile() == null) ProfileSelectorTheme.muted else ProfileSelectorTheme.text
        revalidate()
        repaint()
    }

    private object ProfileSelectorTheme {
        val background = Color(0x171A20)
        val popup = Color(0x202329)
        val hover = Color(0x273140)
        val selected = Color(0x314D81)
        val border = Color(0x46505D)
        val focus = Color(0x2DE2E6)
        val text = Color(0xDDE3EA)
        val muted = Color(0x9EA7B4)
    }
}
