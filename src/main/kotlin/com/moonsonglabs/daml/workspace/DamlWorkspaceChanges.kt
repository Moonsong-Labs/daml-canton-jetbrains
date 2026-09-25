package com.moonsonglabs.daml.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.moonsonglabs.daml.lsp.DamlLspServerSupportProvider
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Coalesce build/config batches. The public LSP API restarts one provider in this project. */
@Service(Service.Level.PROJECT)
class DamlWorkspaceChanges(private val project: Project) : Disposable {
    private var pending: ScheduledFuture<*>? = null
    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val root = project.basePath ?: return
                if (events.none { it.path.startsWith(root + "/") && relevant(it) }) return
                scheduleRestart()
            }
        })
    }
    @Synchronized private fun scheduleRestart() {
        pending?.cancel(false)
        pending = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    val manager = LspServerManager.getInstance(project)
                    if (manager.getServersForProvider(DamlLspServerSupportProvider::class.java).isNotEmpty()) manager.stopAndRestartIfNeeded(DamlLspServerSupportProvider::class.java)
                }
            }
        }, 1, TimeUnit.SECONDS)
    }
    override fun dispose() { pending?.cancel(false) }
    private fun relevant(event: VFileEvent): Boolean = event.path.substringAfterLast('/') in CONFIG_NAMES ||
        event.path.endsWith(".dar") || (event.file?.isDirectory == true && event.file?.findChild("daml.yaml") != null)
    companion object { private val CONFIG_NAMES = setOf("daml.yaml", "multi-package.yaml") }
}
