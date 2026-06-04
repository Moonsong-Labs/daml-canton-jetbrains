package com.moonsonglabs.daml.sandbox

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class SandboxExplorerNavigationService {
    data class Request(
        val profileId: String,
        val participantId: String,
        val refresh: Boolean = true
    )

    private val listeners = CopyOnWriteArrayList<(Request) -> Unit>()

    fun addListener(listener: (Request) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    fun showParticipant(profile: SandboxProfile, participantId: String, refresh: Boolean = true) {
        val request = Request(profile.id, participantId, refresh)
        listeners.forEach { it(request) }
    }

    companion object {
        fun getInstance(project: Project): SandboxExplorerNavigationService = project.service()
    }
}
