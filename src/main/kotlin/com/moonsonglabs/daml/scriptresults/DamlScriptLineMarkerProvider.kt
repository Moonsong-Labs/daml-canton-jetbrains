package com.moonsonglabs.daml.scriptresults

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlTokenTypes

class DamlScriptLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.containingFile?.fileType !== DamlFileType) return null
        if (element.node?.elementType != DamlTokenTypes.IDENTIFIER) return null

        val script = DamlScriptResource.findScripts(element.containingFile.text)
            .firstOrNull { it.name == element.text && it.startOffset == element.textRange.startOffset }
            ?: return null
        val virtualFile = element.containingFile.virtualFile ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Execute,
            { "Show DAML Script Results" },
            GutterIconNavigationHandler<PsiElement> { _, psi ->
                VirtualResourceManager.getInstance(psi.project).showResource(
                    DamlScriptResource.title(script.name),
                    DamlScriptResource.uri(virtualFile.path, script.name)
                )
            },
            GutterIconRenderer.Alignment.CENTER
        ) { "Show DAML Script Results" }
    }
}
