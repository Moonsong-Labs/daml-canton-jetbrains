package com.moonsonglabs.daml.navigation

import com.intellij.find.FindManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usages.UsageTargetUtil
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlTokenTypes

class DamlChoiceReferenceContributorTest : BasePlatformTestCase() {
    fun testFindUsagesIncludesChoiceExerciseCalls() {
        val file = myFixture.configureByText(DamlFileType, privateSettlementSnippet)
        val declarationOffset = privateSettlementSnippet.indexOf("Accept :")
        val declaration = file.findElementAt(declarationOffset)!!

        val usages = ReferencesSearch.search(declaration)
            .findAll()
            .map { it.element.textRange.startOffset }
            .sorted()

        assertTrue(usages.contains(privateSettlementSnippet.indexOf("Accept", privateSettlementSnippet.indexOf("exerciseCmd offerCid"))))
        assertFalse(usages.contains(privateSettlementSnippet.indexOf("PrivateOffer", privateSettlementSnippet.indexOf("createCmd"))))
    }

    fun testChoiceUsageReferenceResolvesToDeclaration() {
        val file = myFixture.configureByText(DamlFileType, privateSettlementSnippet)
        val usage = file.findElementAt(privateSettlementSnippet.indexOf("ApproveForPublicSettlement", privateSettlementSnippet.indexOf("acceptedCid")))!!
        val declaration = file.findElementAt(privateSettlementSnippet.indexOf("ApproveForPublicSettlement :"))!!

        assertEquals(DamlTokenTypes.TYPE_NAME, usage.node.elementType)
        assertEquals("ApproveForPublicSettlement", usage.text)
        assertEquals("ApproveForPublicSettlement", DamlChoiceNames.useAt(file.text, usage.textRange.startOffset)?.name)
        assertEquals(declaration, DamlChoiceResolver.getInstance(project).resolveChoice("ApproveForPublicSettlement", file.virtualFile))
        val references = ReferencesSearch.search(declaration).findAll()
        assertTrue(references.any { it.element == usage && it.resolve() == declaration })
    }

    fun testFindUsagesIncludesCrossFileInterfaceChoiceExercise() {
        val kycPolicy = myFixture.addFileToProject(
            "sample-interface/daml/Sample/Component/KYCPolicy.daml",
            """
module Sample.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()

  nonconsuming choice CheckEligible : ()
    with
      depositor : Party
    controller depositor
    do pure ()
""".trimIndent()
        )
        val sample = myFixture.addFileToProject(
            "sample-interface/daml/Sample/Sample.daml",
            """
module Sample.Sample where

import Sample.Component.KYCPolicy

template SampleConfig
  with
    operator : Party
    kycPolicyCid : ContractId IKYCPolicy
  where
    signatory operator

template Sample
  with
    operator : Party
    configCid : ContractId SampleConfig
  where
    signatory operator

    choice RouteDeposit : ()
      with
        depositor : Party
      controller operator
      do
        cfg <- fetch configCid
        exercise cfg.kycPolicyCid CheckEligible with depositor
""".trimIndent()
        )

        val declaration = kycPolicy.findElementAt(kycPolicy.text.indexOf("CheckEligible :"))!!
        val usageOffset = sample.text.indexOf("CheckEligible", sample.text.indexOf("exercise cfg.kycPolicyCid"))

        val references = ReferencesSearch.search(declaration).findAll()

        assertTrue(references.any { it.element.containingFile == sample && it.element.textRange.startOffset == usageOffset })
    }

    fun testFindUsagesFromInterfaceChoiceHeadingFindsExerciseCalls() {
        val kycPolicy = myFixture.addFileToProject(
            "sample-interface/daml/Sample/Component/KYCPolicy.daml",
            """
module Sample.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()

  nonconsuming choice CheckEligible : ()
    with
      depositor : Party
    controller depositor
    do pure ()
""".trimIndent()
        )
        val sample = myFixture.addFileToProject(
            "sample-interface/daml/Sample/Sample.daml",
            """
module Sample.Sample where

import Sample.Component.KYCPolicy

template SampleConfig
  with
    operator : Party
    kycPolicyCid : ContractId IKYCPolicy
  where
    signatory operator

template Sample
  with
    operator : Party
    configCid : ContractId SampleConfig
  where
    signatory operator

    choice RouteDeposit : ()
      with
        depositor : Party
      controller operator
      do
        cfg <- fetch configCid
        exercise cfg.kycPolicyCid CheckEligible with depositor
""".trimIndent()
        )

        val headingKeyword = kycPolicy.findElementAt(kycPolicy.text.indexOf("nonconsuming"))!!
        val declarationName = kycPolicy.findElementAt(kycPolicy.text.indexOf("CheckEligible :"))!!
        val usageOffset = sample.text.indexOf("CheckEligible", sample.text.indexOf("exercise cfg.kycPolicyCid"))

        assertEquals("CheckEligible", DamlChoiceUsageTargets.fromElement(headingKeyword)?.name)
        assertTrue(DamlFindUsagesProvider().canFindUsagesFor(headingKeyword))
        assertTrue(FindManager.getInstance(project).canFindUsages(headingKeyword))

        val references = ReferencesSearch.search(headingKeyword).findAll()
        assertTrue(references.any { it.element.containingFile == sample && it.element.textRange.startOffset == usageOffset })
        assertTrue(references.any { it.resolve() == declarationName })
    }

    fun testEditorUsageTargetProviderAcceptsChoiceNameAndCaretBoundary() {
        val file = myFixture.configureByText(
            DamlFileType,
            """
module Sample.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()

  nonconsuming choice CheckEligible : ()
    with
      depositor : Party
    controller depositor
    do pure ()
""".trimIndent()
        )
        val choiceOffset = file.text.indexOf("CheckEligible")
        val choiceName = file.findElementAt(choiceOffset)!!

        myFixture.editor.caretModel.moveToOffset(choiceOffset + "CheckEligible".length)

        val targets = UsageTargetUtil.findUsageTargets(myFixture.editor, file, choiceName)
        assertNotNull(targets)
        assertTrue(targets!!.isNotEmpty())
        assertEquals("CheckEligible", DamlChoiceUsageTargets.fromFileOffset(file, myFixture.editor.caretModel.offset)?.name)
    }

    fun testFindUsagesIncludesQualifiedChoiceExerciseInScript() {
        val sample = myFixture.addFileToProject(
            "sample-interface/daml/Sample/Sample.daml",
            """
module Sample.Sample where

template Sample
  with
    operator : Party
  where
    signatory operator

    choice RouteDeposit : ()
      with
        depositor : Party
      controller operator
      do pure ()
""".trimIndent()
        )
        val test = myFixture.addFileToProject(
            "sample-test/daml/Tests/SampleTest.daml",
            """
module Tests.SampleTest where

import Daml.Script
import qualified Sample.Sample as V

testRoute : Script ()
testRoute = script do
  operator <- allocateParty "operator"
  sample0 <- submit operator $ createCmd V.Sample with operator
  submit operator $ exerciseCmd sample0 V.RouteDeposit with depositor = operator
""".trimIndent()
        )

        val declaration = sample.findElementAt(sample.text.indexOf("RouteDeposit :"))!!
        val usageOffset = test.text.indexOf("RouteDeposit", test.text.indexOf("exerciseCmd"))

        val references = ReferencesSearch.search(declaration).findAll()

        assertTrue(references.any { it.element.containingFile == test && it.element.textRange.startOffset == usageOffset })
    }

    fun testQualifiedChoiceExerciseResolvesThroughImportAlias() {
        val first = myFixture.addFileToProject(
            "src/Sample/First.daml",
            """
module Sample.First where

template Sample
  with operator : Party
  where
    signatory operator
    choice RouteDeposit : ()
      controller operator
      do pure ()
""".trimIndent()
        )
        val second = myFixture.addFileToProject(
            "src/Sample/Second.daml",
            first.text.replace("module Sample.First", "module Sample.Second")
        )
        val test = myFixture.addFileToProject(
            "src/Tests/SampleTest.daml",
            """
module Tests.SampleTest where

import qualified Sample.Second as V

testRoute = script do
  submit operator ${'$'} exerciseCmd sample0 V.RouteDeposit
""".trimIndent()
        )
        val firstDeclaration = first.findElementAt(first.text.indexOf("RouteDeposit :"))!!
        val secondDeclaration = second.findElementAt(second.text.indexOf("RouteDeposit :"))!!
        val usageOffset = test.text.indexOf("RouteDeposit", test.text.indexOf("exerciseCmd"))
        val use = DamlChoiceNames.useAt(test.text, usageOffset)!!

        assertEquals(secondDeclaration, DamlChoiceResolver.getInstance(project).resolveChoiceUse(use, test.virtualFile))
        assertFalse(ReferencesSearch.search(firstDeclaration).findAll().any { it.element.containingFile == test })
        assertTrue(ReferencesSearch.search(secondDeclaration).findAll().any { it.element.containingFile == test })
    }

    fun testFindUsagesTargetDoesNotTreatReturnTypeAsChoiceName() {
        val file = myFixture.configureByText(
            DamlFileType,
            """
module User where

template T
  with operator : Party
  where
    signatory operator
    choice C : ContractId T
      controller operator
      do pure this
""".trimIndent()
        )
        val returnType = file.findElementAt(file.text.indexOf("ContractId"))!!

        assertNull(DamlChoiceUsageTargets.fromElement(returnType))
    }

    fun testFindUsagesProviderAcceptsChoiceDeclarations() {
        val file = myFixture.configureByText(DamlFileType, privateSettlementSnippet)
        val declaration = file.findElementAt(privateSettlementSnippet.indexOf("Accept :"))!!

        val provider = DamlFindUsagesProvider()

        assertTrue(provider.canFindUsagesFor(declaration))
        assertEquals("DAML choice", provider.getType(declaration))
        assertEquals("Accept", provider.getDescriptiveName(declaration))
    }

    private val privateSettlementSnippet = """
module PrivateSettlement where

template PrivateOffer
  with
    issuer : Party
    investor : Party
  where
    signatory issuer
    observer investor

    choice Accept : ContractId PrivateAccepted
      controller investor
      do pure ()

template PrivateAccepted
  with
    bridge : Party
  where
    signatory bridge

    choice ApproveForPublicSettlement : ()
      controller bridge
      do pure ()

runDemo = script do
  acceptedCid <- submit investor do
    exerciseCmd offerCid Accept

  instruction <- submit bridge do
    exerciseCmd acceptedCid ApproveForPublicSettlement

  _ <- submit issuer do
    createCmd PrivateOffer with issuer; investor
""".trimIndent()
}
