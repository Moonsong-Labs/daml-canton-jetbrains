package com.moonsonglabs.daml.navigation

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel

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

        val model = DamlSourceModel.get(file)
        targetNearRange(file, model, element.textRange)?.let { return it }

        targetOnLine(file, model, element.textRange.startOffset)?.let { return it }

        return null
    }

    fun fromFileOffset(file: PsiFile, offset: Int): DamlChoiceUsageTarget? {
        if (file.fileType !== DamlFileType) return null
        val model = DamlSourceModel.get(file)
        targetNearOffset(file, model, offset)?.let { return it }
        return targetOnLine(file, model, offset)
    }

    private fun targetNearRange(file: PsiFile, model: DamlSourceModel, range: TextRange?): DamlChoiceUsageTarget? {
        if (range == null) return null
        val offsets = linkedSetOf(
            range.startOffset,
            range.startOffset - 1,
            range.startOffset + 1,
            range.endOffset - 1,
            range.endOffset,
        )
        return offsets.firstNotNullOfOrNull { targetNearOffset(file, model, it) }
    }

    private fun targetNearOffset(file: PsiFile, model: DamlSourceModel, offset: Int): DamlChoiceUsageTarget? {
        val text = model.text
        if (text.isEmpty()) return null
        val clamped = offset.coerceIn(0, text.lastIndex)
        val offsets = linkedSetOf(clamped, clamped - 1, clamped + 1)
        return offsets.firstNotNullOfOrNull { candidate ->
            model.tokenAt(candidate)?.let { model.declaration(it.start) }
                ?.takeIf { it.kind == DamlSourceModel.Kind.CHOICE }?.toTarget(file)
        }
    }

    private fun targetOnLine(file: PsiFile, model: DamlSourceModel, offset: Int): DamlChoiceUsageTarget? {
        val text = model.text
        if (text.isEmpty()) return null
        val clamped = offset.coerceIn(0, text.lastIndex)
        val lineStart = text.lastIndexOf('\n', clamped).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', clamped).let { if (it == -1) text.length else it }
        return model.symbols
            .firstOrNull {
                it.kind == DamlSourceModel.Kind.CHOICE && it.start in lineStart until lineEnd &&
                    clamped in lineStart..(it.start + it.name.length)
            }
            ?.toTarget(file)
    }

    private fun DamlSourceModel.Symbol.toTarget(file: PsiFile): DamlChoiceUsageTarget? {
        val virtualFile = file.virtualFile ?: return null
        val element = DamlNamedElement.at(file, start) ?: return null
        return DamlChoiceUsageTarget(name, virtualFile, start, element)
    }
}
