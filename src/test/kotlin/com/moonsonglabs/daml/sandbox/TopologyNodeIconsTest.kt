package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyNodeIconsTest {
    @Test
    fun `each visible topology node category has a distinct icon`() {
        val icons = listOf(
            TopologyNodeIcons.PARTICIPANT,
            TopologyNodeIcons.SYNCHRONIZER
        )

        assertEquals(2, icons.distinct().size)
        assertTrue(icons.all { it.isNotBlank() })
    }

    @Test
    fun `global synchronizer uses distinct rounded styling`() {
        val global = TopologyGraphPanel.Selection.Synchronizer(SandboxDefaults.SHARED_SYNCHRONIZER_ID)
        val regular = TopologyGraphPanel.Selection.Synchronizer("sync2")

        assertTrue(TopologyGraphTheme.border(global) != TopologyGraphTheme.border(regular))
        assertTrue(TopologyGraphTheme.fill(global) != TopologyGraphTheme.fill(regular))
        assertTrue(TopologyGraphTheme.cornerRadius(global) > TopologyGraphTheme.cornerRadius(regular))
    }
}
