package com.moonsonglabs.daml.syntax

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.DamlTokenTypes
import java.awt.Font

class DamlHighlightingAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return

        val type = element.node?.elementType ?: return
        if (type != DamlTokenTypes.IDENTIFIER &&
            type != DamlTokenTypes.TYPE_NAME &&
            type != DamlTokenTypes.PRELUDE_TYPE
        ) return

        val fileText = element.containingFile?.text ?: return
        val role = DamlHighlightingClassifier.roleAt(fileText, element.textRange.startOffset, element.text)
            ?: return

        val key = role.textAttributesKey()
        val annotation = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(key)
        role.styledAttributes(key)?.let(annotation::enforcedTextAttributes)
        annotation.create()
    }

    private fun DamlHighlightingClassifier.Role.textAttributesKey(): TextAttributesKey = when (this) {
        DamlHighlightingClassifier.Role.MODULE_NAME -> DamlSyntaxHighlighter.MODULE_NAME
        DamlHighlightingClassifier.Role.DECLARATION_NAME -> DamlSyntaxHighlighter.DECLARATION_NAME
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

    private fun DamlHighlightingClassifier.Role.styledAttributes(key: TextAttributesKey): TextAttributes? = when (this) {
        DamlHighlightingClassifier.Role.ABSTRACT_METHOD -> key.withFontStyle(Font.ITALIC)
        DamlHighlightingClassifier.Role.THIS_REFERENCE -> key.withFontStyle(Font.BOLD)
        else -> null
    }

    private fun TextAttributesKey.withFontStyle(style: Int): TextAttributes {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val attributes = scheme.getAttributes(this)?.clone() ?: TextAttributes()
        attributes.fontType = attributes.fontType or style
        return attributes
    }
}
