package com.moonsonglabs.daml.sandbox

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel

class DarAssignmentDialogTest : BasePlatformTestCase() {
    fun `test source folders are derived from profile dar assignments`() {
        val root = Path.of(project.basePath!!).toAbsolutePath().normalize()
        val dist = root.resolve(".daml/dist")
        Files.createDirectories(dist)
        Files.writeString(dist.resolve("vault-interface-0.1.0.dar"), "fake")
        Files.writeString(dist.resolve("vault-impl-0.1.0.dar"), "fake")

        val profile = SandboxDefaults.newProfile(root).apply {
            workspacePath = "."
            darAssignments.add(DarAssignment(".daml/dist/vault-interface-0.1.0.dar", mutableListOf(participants.first().id)))
        }
        val dialog = DarAssignmentDialog(project, profile)

        try {
            val sourceModel = dialog.privateField<DefaultListModel<String>>("sourceModel")
            val sources = (0 until sourceModel.size()).map(sourceModel::getElementAt)
            assertEquals(listOf(dist.toString()), sources)
            assertFalse("Source list should not show an empty-state row when profile DARs imply a source folder.", "No source folders selected" in sources)

            val table = dialog.privateField<JBTable>("table")
            val darNames = (0 until table.rowCount).map { row -> table.getValueAt(row, 0).toString() }.toSet()
            assertTrue("Assigned DAR should be shown.", "vault-interface-0.1.0.dar" in darNames)
            assertTrue("Sibling DARs in the same derived source folder should be shown.", "vault-impl-0.1.0.dar" in darNames)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    private inline fun <reified T> Any.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }
}
