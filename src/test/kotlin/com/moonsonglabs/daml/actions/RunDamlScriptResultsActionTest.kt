package com.moonsonglabs.daml.actions

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class RunDamlScriptResultsActionTest : BasePlatformTestCase() {
    fun `test cancelling a script selector does not report a missing script`() {
        val notifications = captureNotifications()
        var selectorShown = false
        TestDialogManager.setTestDialog(TestDialog {
            selectorShown = true
            -1
        }, testRootDisposable)
        runAction("module Main where\nfirst : Script ()\nfirst = script do\n  pure ()\nsecond : Script ()\nsecond = script do\n  pure ()\n")
        assertTrue(selectorShown)
        assertEmpty(notifications)
    }

    fun `test files without scripts still report the discovery error`() {
        val notifications = captureNotifications()
        runAction("module Main where\nvalue = 1\n")
        assertEquals(1, notifications.size)
        assertTrue(notifications.single().content.contains("No DAML script declaration found"))
    }

    private fun captureNotifications(): MutableList<Notification> {
        val result = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "DAML") result += notification
            }
        })
        return result
    }

    private fun runAction(source: String) {
        val file = myFixture.configureByText("Main.daml", source)
        myFixture.editor.caretModel.moveToOffset(0)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .build()
        RunDamlScriptResultsAction().actionPerformed(AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, null, context))
        UIUtil.dispatchAllInvocationEvents()
    }
}
