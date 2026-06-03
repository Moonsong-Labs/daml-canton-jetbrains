package com.moonsonglabs.daml.syntax

import com.moonsonglabs.daml.syntax.DamlHighlightingClassifier.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class DamlHighlightingClassifierTest {
    private val sample = javaClass.classLoader
        .getResourceAsStream("highlighting/HighlightingSample.daml")!!
        .bufferedReader()
        .use { it.readText() }

    @Test
    fun `classifies module imports and explicit import symbols`() {
        assertRole("Vault", sample.indexOf("Vault.Deposit.Test"), Role.MODULE_NAME)
        assertRole("Daml", sample.indexOf("Daml.Script"), Role.MODULE_NAME)
        assertRole("IProcessor", sample.indexOf("IProcessor)"), Role.IMPORT_SYMBOL)
    }

    @Test
    fun `classifies declarations choices fields and type parameters`() {
        assertRole("Receipt", sample.indexOf("Receipt a"), Role.DECLARATION_NAME)
        assertRole("a", sample.indexOf("Receipt a") + "Receipt ".length, Role.TYPE_PARAMETER)
        assertRole("IProcessor", sample.indexOf("IProcessor where"), Role.DECLARATION_NAME)
        assertRole("Deposit", sample.indexOf("Deposit\n  with"), Role.DECLARATION_NAME)
        assertRole("RunDeposit", sample.indexOf("RunDeposit"), Role.CHOICE_NAME)
        assertRole("operator", sample.indexOf("operator : Party"), Role.PARTY_NAME)
        assertRole("Party", sample.indexOf("operator : Party") + "operator : ".length, Role.PRELUDE_TYPE_REFERENCE)
        assertRole("a", sample.indexOf("payload : a") + "payload : ".length, Role.TYPE_PARAMETER)
    }

    @Test
    fun `classifies scripts builtins and predefined values`() {
        assertRole("setup", sample.indexOf("setup : Script"), Role.SCRIPT_DECLARATION)
        assertRole("allocateParty", sample.indexOf("allocateParty"), Role.BUILTIN)
        assertRole("submit", sample.indexOf("submit alice"), Role.BUILTIN)
        assertRole("alice", sample.indexOf("alice <- allocateParty"), Role.PARTY_NAME)
        assertRole("alice", sample.indexOf("submit alice") + "submit ".length, Role.PARTY_NAME)

        val text = """
template T
  with owner : Party
  where
    signatory owner
    choice C : ()
      controller this.owner
      do pure self
""".trimIndent()
        assertEquals(Role.THIS_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("this"), "this"))
        assertEquals(Role.PREDEFINED_VALUE, DamlHighlightingClassifier.roleAt(text, text.indexOf("self"), "self"))
    }

    @Test
    fun `classifies abstract interface methods this references and party arguments`() {
        val text = """
module Vault.Component.KYCPolicy where

data VKYCPolicy = VKYCPolicy with
    operator : Party

interface IKYCPolicy where
  viewtype VKYCPolicy

  checkEligibleImpl : Party -> Update ()

  nonconsuming choice CheckEligible : ()
    with
      depositor : Party
    controller (view this).operator
    do checkEligibleImpl this depositor
""".trimIndent()

        assertEquals(Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("operator : Party"), "operator"))
        assertEquals(Role.ABSTRACT_METHOD, DamlHighlightingClassifier.roleAt(text, text.indexOf("checkEligibleImpl :"), "checkEligibleImpl"))
        assertEquals(Role.ABSTRACT_METHOD, DamlHighlightingClassifier.roleAt(text, text.indexOf("checkEligibleImpl this"), "checkEligibleImpl"))
        assertEquals(Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("depositor : Party"), "depositor"))
        assertEquals(Role.THIS_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("this).operator"), "this"))
        assertEquals(Role.THIS_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("this depositor"), "this"))
        assertEquals(Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("depositor", text.indexOf("this depositor")), "depositor"))
    }

    @Test
    fun `keeps interface abstract method and party argument highlighting scoped`() {
        val text = """
interface IPolicy where
  viewtype VPolicy
  approveImpl : Party -> Update ()

  nonconsuming choice Approve : ()
    with
      depositor : Party
    controller this
    do approveImpl this depositor

template NotInterface
  with
    owner : Party
  where
    signatory owner

    helper = approveImpl
    depositor = "ordinary value"
""".trimIndent()

        val choiceUse = text.indexOf("approveImpl this")
        val partyUse = text.indexOf("depositor", text.indexOf("this depositor"))
        val templateUse = text.indexOf("approveImpl", text.indexOf("helper"))
        val shadowedValue = text.indexOf("depositor =", text.indexOf("template NotInterface"))

        assertEquals(Role.ABSTRACT_METHOD, DamlHighlightingClassifier.roleAt(text, choiceUse, "approveImpl"))
        assertEquals(Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(text, partyUse, "depositor"))
        assertEquals(null, DamlHighlightingClassifier.roleAt(text, templateUse, "approveImpl"))
        assertEquals(Role.FIELD_NAME, DamlHighlightingClassifier.roleAt(text, shadowedValue, "depositor"))
    }

    @Test
    fun `classifies interface instance method implementations as abstract methods`() {
        val text = """
template SimpleFeeModel
  with
    operator : Party
    feeRate : Decimal
  where
    signatory operator

    interface instance IFeeModel for SimpleFeeModel where
      view = VFeeModel with operator

      computeFeeImpl grossAmount = do
        debug ("[Fee] gross=" <> show grossAmount)
        pure (grossAmount * feeRate)
""".trimIndent()

        assertEquals(Role.DECLARATION_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("IFeeModel"), "IFeeModel"))
        assertEquals(Role.DECLARATION_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("SimpleFeeModel", text.indexOf("for")), "SimpleFeeModel"))
        assertEquals(Role.FIELD_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("view ="), "view"))
        assertEquals(Role.ABSTRACT_METHOD, DamlHighlightingClassifier.roleAt(text, text.indexOf("computeFeeImpl"), "computeFeeImpl"))
    }

    @Test
    fun `classifies punned record fields and qualified module aliases`() {
        val text = """
depositCfgCid <- submit operator ${'$'} createCmd DC.Config with
  operator
  public
  validatorCid = toInterfaceContractId @IValidator vRaw
""".trimIndent()

        assertEquals(Role.MODULE_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("DC.Config"), "DC"))
        assertEquals(Role.TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("DC.Config") + "DC.".length, "Config"))
        assertEquals(Role.FIELD_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("operator\n"), "operator"))
        assertEquals(Role.FIELD_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("public"), "public"))
        assertEquals(Role.FIELD_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("validatorCid"), "validatorCid"))
        assertEquals(Role.TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("IValidator"), "IValidator"))
        assertEquals(Role.BUILTIN, DamlHighlightingClassifier.roleAt(text, text.indexOf("toInterfaceContractId"), "toInterfaceContractId"))
    }

    @Test
    fun `does not classify standalone do expressions as punned record fields`() {
        val text = """
test = script do
  value <- pure True
  value
""".trimIndent()

        assertEquals(null, DamlHighlightingClassifier.roleAt(text, text.lastIndexOf("value"), "value"))
    }

    @Test
    fun `classifies custom and native type references in signatures`() {
        val text = """
template VaultConfig
  with
    operator : Party
    public : Party
    vaultId : Text
    depositConfigCid : ContractId DC.Config
    processor : IProcessor
  where
    signatory operator
""".trimIndent()

        assertEquals(Role.DECLARATION_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("VaultConfig"), "VaultConfig"))
        assertEquals(Role.PRELUDE_TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("Party"), "Party"))
        assertEquals(Role.PRELUDE_TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("Text"), "Text"))
        assertEquals(Role.PRELUDE_TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("ContractId"), "ContractId"))
        assertEquals(Role.MODULE_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("DC.Config"), "DC"))
        assertEquals(Role.TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("DC.Config") + "DC.".length, "Config"))
        assertEquals(Role.TYPE_REFERENCE, DamlHighlightingClassifier.roleAt(text, text.indexOf("IProcessor"), "IProcessor"))
    }

    @Test
    fun `classifies party names bound by allocateParty without coloring every local`() {
        val text = """
testDeposit = script do
  depositor <- allocateParty "depositor"
  localId <- pure "id"
  _ <- submit depositor do
    pure ()
""".trimIndent()

        assertEquals(Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("depositor <-"), "depositor"))
        assertEquals(Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(text, text.indexOf("submit depositor") + "submit ".length, "depositor"))
        assertEquals(null, DamlHighlightingClassifier.roleAt(text, text.indexOf("localId"), "localId"))
    }

    private fun assertRole(tokenText: String, start: Int, role: Role) {
        assertEquals(role, DamlHighlightingClassifier.roleAt(sample, start, tokenText))
    }
}
