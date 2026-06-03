package com.moonsonglabs.daml.navigation

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.moonsonglabs.daml.DamlFileType

internal data class DamlChoiceUsageTarget(
    val name: String,
    val file: VirtualFile,
    val offset: Int,
    val element: PsiElement
)

internal object DamlChoiceUsageTargets {
    fun fromElement(element: PsiElement): DamlChoiceUsageTarget? {
        val file = element.containingFile ?: return null
        if (file.fileType !== DamlFileType) return null

        targetNearRange(file, element.textRange)?.let { return it }

        var current: PsiElement? = element
        while (current != null && current != file) {
            targetOnLine(file, current.textRange.startOffset)?.let { return it }
            current = current.parent
        }

        return null
    }

    fun fromFileOffset(file: PsiFile, offset: Int): DamlChoiceUsageTarget? {
        if (file.fileType !== DamlFileType) return null
        targetNearOffset(file, offset)?.let { return it }
        return targetOnLine(file, offset)
    }

    private fun targetNearRange(file: PsiFile, range: TextRange?): DamlChoiceUsageTarget? {
        if (range == null) return null
        val offsets = linkedSetOf(
            range.startOffset,
            range.startOffset - 1,
            range.startOffset + 1,
            range.endOffset - 1,
            range.endOffset,
        )
        return offsets.firstNotNullOfOrNull { targetNearOffset(file, it) }
    }

    private fun targetNearOffset(file: PsiFile, offset: Int): DamlChoiceUsageTarget? {
        val text = file.text
        if (text.isEmpty()) return null
        val clamped = offset.coerceIn(0, text.lastIndex)
        val offsets = linkedSetOf(clamped, clamped - 1, clamped + 1)
        return offsets.firstNotNullOfOrNull { candidate ->
            if (candidate !in text.indices) null else DamlChoiceNames.declarationAt(text, candidate)?.toTarget(file)
        }
    }

    private fun targetOnLine(file: PsiFile, offset: Int): DamlChoiceUsageTarget? {
        val text = file.text
        if (text.isEmpty()) return null
        val clamped = offset.coerceIn(0, text.lastIndex)
        val lineStart = text.lastIndexOf('\n', clamped).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', clamped).let { if (it == -1) text.length else it }
        return DamlChoiceNames.declarations(text)
            .firstOrNull {
                it.startOffset in lineStart until lineEnd &&
                    clamped in lineStart..(it.startOffset + it.name.length)
            }
            ?.toTarget(file)
    }

    private fun DamlChoiceNames.ChoiceDeclaration.toTarget(file: PsiFile): DamlChoiceUsageTarget? {
        val virtualFile = file.virtualFile ?: return null
        val element = file.findElementAt(startOffset) ?: return null
        return DamlChoiceUsageTarget(name, virtualFile, startOffset, element)
    }
}
