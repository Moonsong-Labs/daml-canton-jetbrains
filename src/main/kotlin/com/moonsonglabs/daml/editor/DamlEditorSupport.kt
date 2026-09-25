package com.moonsonglabs.daml.editor

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.moonsonglabs.daml.DamlLanguage
import com.moonsonglabs.daml.DamlTokenTypes
import com.moonsonglabs.daml.lang.DamlSourceModel

class DamlBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(BracePair(DamlTokenTypes.LPAREN, DamlTokenTypes.RPAREN, false),
        BracePair(DamlTokenTypes.LBRACKET, DamlTokenTypes.RBRACKET, false), BracePair(DamlTokenTypes.LBRACE, DamlTokenTypes.RBRACE, true))
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}

class DamlFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PsiFile ?: return emptyArray()
        val model = DamlSourceModel.get(file)
        return buildList {
            for (symbol in model.symbols) {
                if (symbol.kind in DamlSourceModel.PRIVATE_KINDS) continue
                val firstLine = document.getLineNumber(symbol.header)
                val start = document.getLineEndOffset(firstLine)
                val end = symbol.end.coerceAtMost(document.textLength)
                if (end > start + 1 && document.getLineNumber((end - 1).coerceAtLeast(0)) > firstLine) add(FoldingDescriptor(root.node, TextRange(start, end)))
            }
            model.tokens.filter { !it.code && it.text.startsWith("{-") && '\n' in it.text }.forEach {
                add(FoldingDescriptor(root.node, TextRange(it.start, it.end)))
            }
            if (model.imports.size > 1) add(FoldingDescriptor(root.node, TextRange(model.imports.first().start, model.imports.last().end)))
        }.distinctBy { it.range }.toTypedArray()
    }
    override fun getPlaceholderText(node: ASTNode) = "…"
    override fun isCollapsedByDefault(node: ASTNode) = false
}

class DamlSelectionHandler : ExtendWordSelectionHandlerBase() {
    override fun canSelect(e: PsiElement) = e.language == DamlLanguage
    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> {
        val model = DamlSourceModel.get(e.containingFile)
        return buildList {
            add(e.textRange)
            model.symbols.filter { cursorOffset in it.header until it.end }.forEach { add(TextRange(it.header, it.end)) }
        }.distinct().sortedBy { it.length }
    }
}
