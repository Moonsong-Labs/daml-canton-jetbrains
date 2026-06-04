package com.moonsonglabs.daml

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class DamlTokenType(debugName: String) : IElementType(debugName, DamlLanguage)

object DamlTokenTypes {
    @JvmField val WHITE_SPACE = DamlTokenType("WHITE_SPACE")
    @JvmField val LINE_COMMENT = DamlTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = DamlTokenType("BLOCK_COMMENT")
    @JvmField val DOC_COMMENT = DamlTokenType("DOC_COMMENT")
    @JvmField val PRAGMA = DamlTokenType("PRAGMA")

    @JvmField val KEYWORD = DamlTokenType("KEYWORD")
    @JvmField val MODULE_KEYWORD = DamlTokenType("MODULE_KEYWORD")
    @JvmField val IMPORT_KEYWORD = DamlTokenType("IMPORT_KEYWORD")
    @JvmField val DECLARATION_KEYWORD = DamlTokenType("DECLARATION_KEYWORD")
    @JvmField val DAML_KEYWORD = DamlTokenType("DAML_KEYWORD")
    @JvmField val CHOICE_MODIFIER_KEYWORD = DamlTokenType("CHOICE_MODIFIER_KEYWORD")
    @JvmField val CONTROL_KEYWORD = DamlTokenType("CONTROL_KEYWORD")

    @JvmField val TYPE_NAME = DamlTokenType("TYPE_NAME")
    @JvmField val PRELUDE_TYPE = DamlTokenType("PRELUDE_TYPE")
    @JvmField val IDENTIFIER = DamlTokenType("IDENTIFIER")
    @JvmField val BUILTIN_IDENTIFIER = DamlTokenType("BUILTIN_IDENTIFIER")
    @JvmField val PREDEFINED_IDENTIFIER = DamlTokenType("PREDEFINED_IDENTIFIER")

    @JvmField val STRING_LITERAL = DamlTokenType("STRING_LITERAL")
    @JvmField val CHAR_LITERAL = DamlTokenType("CHAR_LITERAL")
    @JvmField val NUMBER = DamlTokenType("NUMBER")
    @JvmField val BOOLEAN_LITERAL = DamlTokenType("BOOLEAN_LITERAL")
    @JvmField val UNIT_LITERAL = DamlTokenType("UNIT_LITERAL")
    @JvmField val EMPTY_LIST_LITERAL = DamlTokenType("EMPTY_LIST_LITERAL")

    @JvmField val OPERATOR = DamlTokenType("OPERATOR")
    @JvmField val DOT = DamlTokenType("DOT")
    @JvmField val COLON = DamlTokenType("COLON")
    @JvmField val DOUBLE_COLON = DamlTokenType("DOUBLE_COLON")
    @JvmField val ARROW = DamlTokenType("ARROW")
    @JvmField val BIG_ARROW = DamlTokenType("BIG_ARROW")
    @JvmField val BIND_ARROW = DamlTokenType("BIND_ARROW")
    @JvmField val EQUALS = DamlTokenType("EQUALS")
    @JvmField val EQUALITY_OPERATOR = DamlTokenType("EQUALITY_OPERATOR")
    @JvmField val LPAREN = DamlTokenType("LPAREN")
    @JvmField val RPAREN = DamlTokenType("RPAREN")
    @JvmField val LBRACE = DamlTokenType("LBRACE")
    @JvmField val RBRACE = DamlTokenType("RBRACE")
    @JvmField val LBRACKET = DamlTokenType("LBRACKET")
    @JvmField val RBRACKET = DamlTokenType("RBRACKET")
    @JvmField val COMMA = DamlTokenType("COMMA")
    @JvmField val SEMICOLON = DamlTokenType("SEMICOLON")
    @JvmField val BACKTICK = DamlTokenType("BACKTICK")

    @JvmField val BAD_CHARACTER = DamlTokenType("BAD_CHARACTER")

    @JvmField val MODULE_DECL = DamlTokenType("MODULE_DECL")
    @JvmField val IMPORT_DECL = DamlTokenType("IMPORT_DECL")
    @JvmField val TEMPLATE_DECL = DamlTokenType("TEMPLATE_DECL")
    @JvmField val CHOICE_DECL = DamlTokenType("CHOICE_DECL")
    @JvmField val INTERFACE_DECL = DamlTokenType("INTERFACE_DECL")
    @JvmField val DATA_DECL = DamlTokenType("DATA_DECL")
    @JvmField val TYPE_DECL = DamlTokenType("TYPE_DECL")

    @JvmField val FILE = IFileElementType("DAML_FILE", DamlLanguage)

    @JvmField
    val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)

    @JvmField
    val STRINGS = TokenSet.create(STRING_LITERAL, CHAR_LITERAL)

    @JvmField
    val WHITESPACES = TokenSet.create(WHITE_SPACE)
}

/**
 * The contextual DAML keywords (Haskell shares the rest).
 *
 * Why: kept in the native lexer so first-paint highlighting does not depend on external grammars.
 */
object DamlKeywords {
    val moduleKeywords = setOf("module", "where")

    val importKeywords = setOf("import", "qualified", "as", "hiding")

    val declarationKeywords = setOf(
        "data", "newtype", "type", "class", "instance", "deriving",
        "default", "infix", "infixl", "infixr", "forall",
        "template", "interface", "exception"
    )

    val haskellKeywords = setOf(
        "module", "where", "import", "qualified", "as", "hiding",
        "data", "newtype", "type", "class", "instance", "deriving",
        "let", "in", "do", "if", "then", "else", "case", "of",
        "default", "infix", "infixl", "infixr",
        "forall",
        "try", "catch"
    )

    val damlKeywords = setOf(
        "template", "with", "choice", "controller", "can",
        "signatory", "observer", "agreement", "ensure",
        "key", "maintainer", "message", "magreement",
        "nonconsuming", "preconsuming", "postconsuming",
        "interface", "viewtype", "requires", "implements", "coimplements",
        "exception", "for"
    )

    val contractClauseKeywords = setOf(
        "with", "choice", "controller", "can",
        "signatory", "observer", "agreement", "ensure",
        "key", "maintainer", "message", "magreement",
        "viewtype", "requires", "implements", "coimplements", "for"
    )

    val choiceModifierKeywords = setOf("nonconsuming", "preconsuming", "postconsuming")

    val controlKeywords = setOf("do", "if", "then", "else", "case", "of", "try", "catch")

    val booleanLiterals = setOf("True", "False")

    val preludeTypes = setOf(
        "Any", "AnyChoice", "AnyContractKey", "AnyTemplate",
        "AnyContractId", "Archive", "Bool", "Choice", "Commands", "ContractId",
        "CryptoErrorType", "Date", "DayOfWeek", "Decimal", "Disclosure", "Either",
        "Exercised", "GenMap", "Int", "List", "Map", "Month", "Numeric", "Optional",
        "PackageId", "ParticipantName", "Party", "PartyDetails", "PartyIdHint",
        "PrivateKeyHex", "RelTime", "Script", "Secp256k1KeyPair", "SubmitError",
        "SubmitOptions", "Template", "TemplateKey", "TemplateTypeRep", "Text", "TextMap",
        "Time", "TransactionTree", "TreeEvent", "TreeIndex", "Update", "UpgradeErrorType",
        "User", "UserAlreadyExists", "UserId", "UserNotFound", "UserRight"
    )

    val predefinedConstructors = setOf(
        "Some", "None", "Left", "Right", "LT", "EQ", "GT",
        "CanActAs", "CanReadAs", "CanReadAsAnyParty", "ParticipantAdmin",
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    val builtins = setOf(
        "script",
        "create", "createAndExercise", "exercise", "exerciseByKey", "fetch", "fetchByKey",
        "lookupByKey", "visibleByKey", "archive", "abort", "assert", "assertMsg",
        "getTime", "return", "pure", "debug", "debugRaw",
        "date", "datetime", "time", "subTime", "wholeDays",
        "optional", "fromOptional", "fromSome", "fromSomeNote", "isNone", "isSome",
        "createCmd", "createExactCmd", "exerciseCmd", "exerciseExactCmd",
        "exerciseInterface", "exerciseInterfaceCmd",
        "exerciseByKeyInterface", "exerciseByKeyInterfaceCmd",
        "exerciseByKeyCmd", "exerciseByKeyExactCmd", "createAndExerciseCmd",
        "createAndExerciseExactCmd", "createAndExerciseWithCidCmd",
        "createAndExerciseWithCidExactCmd", "archiveCmd",
        "submit", "submitWithOptions", "submitMustFail", "submitMustFailWithOptions",
        "submitMulti", "submitMultiMustFail", "submitTree", "submitTreeMulti",
        "submitResultAndTree", "submitWithDisclosures", "submitWithDisclosuresMustFail",
        "submitWithError", "trySubmit", "trySubmitMulti", "trySubmitResultAndTree",
        "trySubmitTree",
        "query", "queryContractId", "queryContractKey", "queryDisclosure", "queryFilter",
        "queryInterface", "queryInterfaceContractId",
        "allocateParty", "allocatePartyByHint", "allocatePartyOn", "allocatePartyWithHint",
        "allocatePartyByHintOn", "allocatePartyWithHintOn", "actAs", "readAs",
        "disclose", "discloseMany", "prefetchKeys",
        "concurrently", "partyFromText", "validateUserId", "createUser",
        "createUserOn", "deleteUser", "deleteUserOn", "getUser", "getUserOn",
        "grantUserRights", "grantUserRightsOn", "listAllUsers", "listAllUsersOn",
        "listKnownParties", "listKnownPartiesOn", "listUserRights", "listUserRightsOn",
        "revokeUserRights", "revokeUserRightsOn", "submitUser", "submitUserOn",
        "tryFailureStatus", "tryToEither", "userIdToText",
        "passTime", "setTime", "sleep",
        "created", "createdN", "exercised", "exercisedN", "fromAnyContractId", "fromTree",
        "packagePreference",
        "toAnyChoice", "fromAnyChoice", "toAnyContractKey", "fromAnyContractKey",
        "toAnyTemplate", "fromAnyTemplate", "toInterface", "fromInterface",
        "toInterfaceContractId", "fromInterfaceContractId", "coerceInterfaceContractId",
        "fetchFromInterface", "interfaceTypeRep", "view"
    )

    val all = haskellKeywords + damlKeywords + booleanLiterals
}
