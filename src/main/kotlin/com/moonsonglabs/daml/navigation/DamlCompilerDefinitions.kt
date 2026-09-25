package com.moonsonglabs.daml.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lsp.DamlLspServerSupportProvider
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position

/** Explicit usages/refactoring fallback only. Never called by painting or ordinary reference resolution. */
@Service(Service.Level.PROJECT)
class DamlCompilerDefinitions(private val project: Project) {
    fun resolve(element: PsiElement): List<PsiElement>? {
        ProgressManager.checkCanceled()
        val file = element.containingFile
        val virtual = file.virtualFile ?: return null
        val server = LspServerManager.getInstance(project).getServersForProvider(DamlLspServerSupportProvider::class.java)
            .firstOrNull { it.descriptor.isSupportedFile(virtual) && it.initializeResult != null } ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val line = document.getLineNumber(element.textOffset)
        val position = Position(line, element.textOffset - document.getLineStartOffset(line))
        val result = try {
            server.sendRequestSync(5_000) { it.textDocumentService.definition(DefinitionParams(server.getDocumentIdentifier(virtual), position)) }
        } catch (canceled: com.intellij.openapi.progress.ProcessCanceledException) { throw canceled
        } catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException) throw failure
            return null
        }
        ProgressManager.checkCanceled()
        if (result == null) return null
        val locations = if (result.isLeft) result.left.map { it.uri to it.range.start } else result.right.map { it.targetUri to it.targetSelectionRange.start }
        return locations.mapNotNull { (uri, start) ->
            val targetFile = server.descriptor.findFileByUri(uri) ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(targetFile) ?: return@mapNotNull null
            val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@mapNotNull null
            if (start.line >= doc.lineCount) return@mapNotNull null
            val offset = doc.getLineStartOffset(start.line) + start.character
            DamlNamedElement.at(psi, offset) ?: psi.findElementAt(offset)
        }
    }
}
