package com.moonsonglabs.daml.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

internal fun pathField(project: Project, title: String, folder: Boolean = false) = TextFieldWithBrowseButton().apply {
    val descriptor = if (folder) FileChooserDescriptorFactory.createSingleFolderDescriptor()
        else FileChooserDescriptorFactory.createSingleFileDescriptor()
    descriptor.title = title
    addBrowseFolderListener(project, descriptor)
}

internal fun formRow(label: String, field: JComponent): JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
    border = JBUI.Borders.empty(4, 0)
    add(JBLabel(label).apply { preferredSize = JBUI.size(130, 28) }, BorderLayout.WEST)
    add(field, BorderLayout.CENTER)
}

internal fun JTextComponent.onChange(action: () -> Unit) {
    document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    })
}
