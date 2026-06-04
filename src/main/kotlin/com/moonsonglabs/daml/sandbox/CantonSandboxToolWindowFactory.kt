package com.moonsonglabs.daml.sandbox

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel

internal const val SANDBOX_TOOL_WINDOW_ID = "Managed Canton Sandboxes"
internal const val SANDBOX_NETWORK_CONTENT_NAME = "Network"
internal const val SANDBOX_EXPLORER_CONTENT_NAME = "Explorer"

internal data class SandboxToolWindowContent(
    val name: String,
    val component: JPanel,
    val disposable: Disposable?
)

class CantonSandboxToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        createContents(project).forEach { item ->
            val content = ContentFactory.getInstance().createContent(item.component, item.name, false)
            item.disposable?.let { Disposer.register(content, it) }
            toolWindow.contentManager.addContent(content)
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    internal fun createContents(project: Project): List<SandboxToolWindowContent> =
        listOf(
            runCatching {
                val panel = CantonSandboxPanel(project)
                SandboxToolWindowContent(SANDBOX_NETWORK_CONTENT_NAME, panel, panel)
            }.getOrElse { error ->
                thisLogger().error("Failed to create Managed Canton Sandboxes network view", error)
                SandboxToolWindowContent(SANDBOX_NETWORK_CONTENT_NAME, fallbackPanel(error), null)
            },
            runCatching {
                val panel = LedgerExplorerPanel(project)
                SandboxToolWindowContent(SANDBOX_EXPLORER_CONTENT_NAME, panel, panel)
            }.getOrElse { error ->
                thisLogger().error("Failed to create Managed Canton Sandboxes explorer", error)
                SandboxToolWindowContent(SANDBOX_EXPLORER_CONTENT_NAME, fallbackPanel(error), null)
            }
        )

    private fun fallbackPanel(error: Throwable): JPanel =
        JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JBLabel("Managed Canton Sandboxes failed to load", AllIcons.General.Error, JBLabel.LEFT), BorderLayout.NORTH)
            add(JBTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                text = buildString {
                    appendLine(error::class.java.name)
                    appendLine(error.message.orEmpty())
                    appendLine()
                    appendLine("Open Help > Show Log in Finder and search for 'Managed Canton Sandboxes' for the full stack trace.")
                }
            }, BorderLayout.CENTER)
        }
}
