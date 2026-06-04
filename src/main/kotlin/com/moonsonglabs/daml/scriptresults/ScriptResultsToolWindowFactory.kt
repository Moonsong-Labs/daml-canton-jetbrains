package com.moonsonglabs.daml.scriptresults

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ScriptResultsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ScriptResultsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // The panel owns native JCEF resources; chain its disposer to the content so it is
        // released when the content is removed (e.g. project close).
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
