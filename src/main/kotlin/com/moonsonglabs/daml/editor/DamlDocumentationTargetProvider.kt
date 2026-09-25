package com.moonsonglabs.daml.editor

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.moonsonglabs.daml.lang.DamlNamedElement

/** Native hover is available without a server; compiler targets remain available for inferred types. */
class DamlDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val target = DamlDocumentationProvider.targetAt(file, offset) ?: return emptyList()
        if (!DamlDocumentation.hasDetails(target)) return emptyList()
        return listOf(DamlDocumentationTarget(target))
    }
}

internal class DamlDocumentationTarget(private val element: DamlNamedElement) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let(::DamlDocumentationTarget) }
    }

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(element.name.orEmpty())
        .icon(element.getIcon(0)).locationText(element.containingFile.name).presentation()

    override val navigatable get() = element
    override fun computeDocumentationHint() = DamlDocumentation.render(element)
    override fun computeDocumentation(): DocumentationResult? = DamlDocumentation.render(element)?.let(DocumentationResult::documentation)
}
