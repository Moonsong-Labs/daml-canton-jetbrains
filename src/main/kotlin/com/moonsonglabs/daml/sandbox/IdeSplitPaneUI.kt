package com.moonsonglabs.daml.sandbox

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Graphics
import javax.swing.JSplitPane
import javax.swing.BorderFactory
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

/** Swing's native split behavior with a flat divider that follows the IDE theme. */
internal class IdeSplitPaneUI : BasicSplitPaneUI() {
    override fun createDefaultDivider(): BasicSplitPaneDivider = object : BasicSplitPaneDivider(this) {
        init { border = BorderFactory.createEmptyBorder() }
        override fun paint(g: Graphics) {
            g.color = UIUtil.getPanelBackground(); g.fillRect(0, 0, width, height)
            g.color = JBColor.border()
            if (orientation == JSplitPane.HORIZONTAL_SPLIT) g.drawLine(width / 2, 0, width / 2, height)
            else g.drawLine(0, height / 2, width, height / 2)
        }
    }
}
