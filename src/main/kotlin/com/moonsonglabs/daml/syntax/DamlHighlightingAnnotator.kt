package com.moonsonglabs.daml.syntax

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.navigation.DamlModuleResolver

class DamlHighlightingAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return

        val file = element.containingFile ?: return
        val roles = com.intellij.psi.util.CachedValuesManager.getCachedValue(file, ROLES) {
            com.intellij.psi.util.CachedValueProvider.Result.create(
                classify(file), PsiModificationTracker.MODIFICATION_COUNT,
                DumbService.getInstance(file.project).modificationTracker)
        }
        val role = roles[element.textRange.startOffset] ?: return

        val key = role.textAttributesKey()
        val annotation = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(key)
        annotation.create()
    }

    private fun DamlHighlightingClassifier.Role.textAttributesKey(): TextAttributesKey = when (this) {
        DamlHighlightingClassifier.Role.LOCAL_NAME -> DamlSyntaxHighlighter.IDENTIFIER
        DamlHighlightingClassifier.Role.MODULE_NAME -> DamlSyntaxHighlighter.MODULE_NAME
        DamlHighlightingClassifier.Role.DECLARATION_NAME -> DamlSyntaxHighlighter.DECLARATION_NAME
        DamlHighlightingClassifier.Role.FUNCTION_CALL -> DamlSyntaxHighlighter.FUNCTION_CALL
        DamlHighlightingClassifier.Role.CHOICE_NAME -> DamlSyntaxHighlighter.CHOICE_NAME
        DamlHighlightingClassifier.Role.FIELD_NAME -> DamlSyntaxHighlighter.FIELD_NAME
        DamlHighlightingClassifier.Role.TYPE_REFERENCE -> DamlSyntaxHighlighter.TYPE_NAME
        DamlHighlightingClassifier.Role.PRELUDE_TYPE_REFERENCE -> DamlSyntaxHighlighter.PRELUDE_TYPE
        DamlHighlightingClassifier.Role.TYPE_PARAMETER -> DamlSyntaxHighlighter.TYPE_PARAMETER
        DamlHighlightingClassifier.Role.IMPORT_SYMBOL -> DamlSyntaxHighlighter.IMPORT_SYMBOL
        DamlHighlightingClassifier.Role.SCRIPT_DECLARATION -> DamlSyntaxHighlighter.SCRIPT_DECLARATION
        DamlHighlightingClassifier.Role.ABSTRACT_METHOD -> DamlSyntaxHighlighter.ABSTRACT_METHOD
        DamlHighlightingClassifier.Role.BUILTIN -> DamlSyntaxHighlighter.BUILTIN
        DamlHighlightingClassifier.Role.PARTY_NAME -> DamlSyntaxHighlighter.PARTY_NAME
        DamlHighlightingClassifier.Role.THIS_REFERENCE -> DamlSyntaxHighlighter.THIS_REFERENCE
        DamlHighlightingClassifier.Role.PREDEFINED_VALUE -> DamlSyntaxHighlighter.PREDEFINED_VALUE
    }

    companion object {
        private fun classify(file: PsiFile): Map<Int, DamlHighlightingClassifier.Role> {
            val model = DamlSourceModel.get(file)
            if (DumbService.isDumb(file.project)) return DamlHighlightingClassifier.classify(model)
            val resolver = DamlModuleResolver.getInstance(file.project)
            val importedFunctions = mutableMapOf<Pair<String?, String>, Boolean>()
            return DamlHighlightingClassifier.classify(model) { token ->
                val reference = DamlModuleResolver.referenceAt(file, token.start)
                    ?: return@classify false
                importedFunctions.getOrPut(reference.qualifier to reference.name) {
                    val target = resolver.resolveAll(reference, file).singleOrNull() as? DamlNamedElement
                    val symbol = target?.symbol
                    symbol != null && DamlHighlightingClassifier.isFunction(symbol, DamlSourceModel.get(target.containingFile))
                }
            }
        }

        private val ROLES = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<Map<Int, DamlHighlightingClassifier.Role>>>("daml.highlight.roles")
    }
}
