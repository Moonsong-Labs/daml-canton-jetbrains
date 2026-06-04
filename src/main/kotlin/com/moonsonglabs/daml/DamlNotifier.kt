package com.moonsonglabs.daml

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object DamlNotifier {
    private const val GROUP_ID = "DAML"

    fun info(project: Project?, message: String, title: String = "DAML") =
        notify(project, title, message, NotificationType.INFORMATION)

    fun warn(project: Project?, message: String, title: String = "DAML") =
        notify(project, title, message, NotificationType.WARNING)

    fun error(project: Project?, message: String, title: String = "DAML") =
        notify(project, title, message, NotificationType.ERROR)

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
