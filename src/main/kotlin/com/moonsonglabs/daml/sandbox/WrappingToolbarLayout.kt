package com.moonsonglabs.daml.sandbox

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import kotlin.math.max

/** Flow layout whose preferred height includes wrapped rows in narrow IDE tool windows. */
internal class WrappingToolbarLayout : FlowLayout(LEADING, 6, 4) {
    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val available = (target.width.takeIf { it > 0 } ?: target.parent?.width?.takeIf { it > 0 } ?: 1000) - insets.left - insets.right - hgap * 2
        var rowWidth = 0; var rowHeight = 0; var width = 0; var height = vgap * 2
        for (component in target.components.filter { it.isVisible }) {
            val size = component.preferredSize
            if (rowWidth > 0 && rowWidth + hgap + size.width > available) {
                width = max(width, rowWidth); height += rowHeight + vgap; rowWidth = 0; rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += size.width; rowHeight = max(rowHeight, size.height)
        }
        Dimension(max(width, rowWidth) + insets.left + insets.right + hgap * 2, height + rowHeight + insets.top + insets.bottom)
    }
    override fun minimumLayoutSize(target: Container): Dimension = preferredLayoutSize(target).apply { width = 100 }
}
