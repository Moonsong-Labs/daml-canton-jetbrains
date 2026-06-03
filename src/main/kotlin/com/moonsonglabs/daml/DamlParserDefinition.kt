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
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = DamlPsiFile(viewProvider)
}

private class DamlStructuralParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        var atLineStart = true

        while (!builder.eof()) {
            val tokenType = builder.tokenType
            val tokenText = builder.tokenText.orEmpty()

            if (tokenType == DamlTokenTypes.WHITE_SPACE) {
                if (tokenText.contains('\n')) atLineStart = true
                builder.advanceLexer()
                continue
            }

            val declarationType = if (atLineStart) declarationType(tokenText) else null
            if (declarationType != null) {
                parseDeclarationHeading(builder, declarationType)
                atLineStart = true
                continue
            }

            builder.advanceLexer()
            atLineStart = tokenText.contains('\n')
        }

        rootMarker.done(root)
        return builder.treeBuilt
    }

    private fun declarationType(tokenText: String): IElementType? = when (tokenText) {
        "module" -> DamlTokenTypes.MODULE_DECL
        "import" -> DamlTokenTypes.IMPORT_DECL
        "template" -> DamlTokenTypes.TEMPLATE_DECL
        "choice" -> DamlTokenTypes.CHOICE_DECL
        "interface" -> DamlTokenTypes.INTERFACE_DECL
        "data", "newtype" -> DamlTokenTypes.DATA_DECL
        "type" -> DamlTokenTypes.TYPE_DECL
        else -> null
    }

    private fun parseDeclarationHeading(builder: PsiBuilder, elementType: IElementType) {
        val marker = builder.mark()
        while (!builder.eof()) {
            val tokenText = builder.tokenText.orEmpty()
            builder.advanceLexer()
            if (tokenText.contains('\n')) break
        }
        marker.done(elementType)
    }
}
