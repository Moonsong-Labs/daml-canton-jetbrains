package com.moonsonglabs.daml

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Lightweight ParserDefinition for DAML.
 *
 * Real semantic analysis lives in the DAML LSP. This parser deliberately avoids a full grammar,
 * but it still creates declaration-level nodes so editor features have a useful local shape while
 * the language server is starting or unavailable.
 */
class DamlParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = DamlLexer()

    override fun createParser(project: Project?): PsiParser = DamlStructuralParser()

    override fun getFileNodeType(): IFileElementType = DamlTokenTypes.FILE
    override fun getCommentTokens(): TokenSet = DamlTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = DamlTokenTypes.STRINGS
    override fun getWhitespaceTokens(): TokenSet = DamlTokenTypes.WHITESPACES
    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType == DamlTokenTypes.SYMBOL_NAME) com.moonsonglabs.daml.lang.DamlNamedElement(node)
        else ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = DamlPsiFile(viewProvider)
}

private class DamlStructuralParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val model = com.moonsonglabs.daml.lang.DamlSourceModel.parse(builder.originalText.toString())
        val rootMarker = builder.mark()
        // Layout is derived from source offsets: PsiBuilder has already skipped whitespace.
        val boundaries = model.lines.filter { it.tokens.isNotEmpty() }.associateBy { it.tokens.first().start }
        while (!builder.eof()) {
            val line = boundaries[builder.currentOffset]
            val marker = builder.mark()
            val type = when (builder.tokenText) {
                "module" -> DamlTokenTypes.MODULE_DECL
                "import" -> DamlTokenTypes.IMPORT_DECL
                "template" -> DamlTokenTypes.TEMPLATE_DECL
                "interface" -> DamlTokenTypes.INTERFACE_DECL
                "data", "newtype" -> DamlTokenTypes.DATA_DECL
                "type" -> DamlTokenTypes.TYPE_DECL
                "choice", "nonconsuming", "preconsuming", "postconsuming" -> DamlTokenTypes.CHOICE_DECL
                else -> DamlTokenTypes.DECLARATION
            }
            val end = line?.end ?: (builder.currentOffset + (builder.tokenText?.length ?: 1))
            do {
                val named = model.declaration(builder.currentOffset)
                if (named != null && named.start == builder.currentOffset) {
                    val name = builder.mark()
                    builder.advanceLexer()
                    name.done(DamlTokenTypes.SYMBOL_NAME)
                } else builder.advanceLexer()
            } while (!builder.eof() && builder.currentOffset < end)
            marker.done(type)
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }
}
