package com.moonsonglabs.daml.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DamlWorkspaceServiceTest {
    @Test
    fun `project and ancestor names do not exclude ordinary sources`() {
        for (root in listOf(Path.of("/tmp/build/project"), Path.of("/tmp/out"))) {
            assertFalse(DamlWorkspaceService.isIgnoredPath(root, root))
            assertFalse(DamlWorkspaceService.isIgnoredPath(root, root.resolve("daml/Main.daml")))
        }
    }

    @Test
    fun `generated and dependency descendants remain excluded`() {
        val root = Path.of("/tmp/build/project")
        for (directory in listOf("build", "out", ".daml", "node_modules", ".gradle")) {
            assertTrue(DamlWorkspaceService.isIgnoredPath(root, root.resolve("$directory/Main.daml")))
        }
        assertTrue(DamlWorkspaceService.isIgnoredPath(root, root.resolve("daml/../build/Main.daml")))
        assertFalse(DamlWorkspaceService.isIgnoredPath(root, root.resolve("build/../daml/Main.daml")))
    }
}
