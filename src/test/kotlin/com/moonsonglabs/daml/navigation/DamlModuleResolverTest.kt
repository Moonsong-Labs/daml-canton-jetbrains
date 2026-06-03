package com.moonsonglabs.daml.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.DamlFileType

class DamlModuleResolverTest : BasePlatformTestCase() {
    fun testSourceSymbolResolutionIgnoresDamlPackageDatabaseCopies() {
        val source = myFixture.addFileToProject(
            "vault-interface/daml/Vault/Component/KYCPolicy.daml",
            """
module Vault.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()
""".trimIndent()
        )
        myFixture.addFileToProject(
            "vault-impl/.daml/package-database/2.2/vault-interface/Vault/Component/KYCPolicy.daml",
            """
module Vault.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()
""".trimIndent()
        )
        val user = myFixture.addFileToProject(
            "vault-impl/daml/Vault/Impl/SimpleKYCPolicy.daml",
            """
module Vault.Impl.SimpleKYCPolicy where

import Vault.Component.KYCPolicy (IKYCPolicy)

template SimpleKYCPolicy
  with
    operator : Party
  where
    signatory operator
    interface instance IKYCPolicy for SimpleKYCPolicy where
      view = ()
""".trimIndent()
        )

        val symbol = DamlModuleNames.symbolAt(user.text, user.text.lastIndexOf("IKYCPolicy"))!!
        val target = DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, user.virtualFile)!!

        assertEquals(source.virtualFile.path, target.containingFile.virtualFile.path)
        assertEquals(source.text.indexOf("IKYCPolicy"), target.textRange.startOffset)
    }

    fun testCtrlClickInterfaceInstancePrefersSourceOverDamlPackageDatabase() {
        val source = myFixture.addFileToProject(
            "vault-interface/daml/Vault/YieldSource.daml",
            """
module Vault.YieldSource where

data VYieldSource = VYieldSource with
    operator : Party

interface IYieldSource where
  viewtype VYieldSource
""".trimIndent()
        )
        myFixture.addFileToProject(
            "vault-impl/.daml/package-database/2.2/vault-interface-0.1.0-hash/Vault/YieldSource.daml",
            source.text
        )
        val user = myFixture.addFileToProject(
            "vault-impl/daml/Vault/Impl/Strategy.daml",
            """
module Vault.Impl.Strategy where

import Vault.YieldSource

template Strategy
  with
    operator : Party
  where
    signatory operator

    interface instance IYieldSource for Strategy where
      view = VYieldSource with operator
""".trimIndent()
        )
        myFixture.openFileInEditor(user.virtualFile)

        val offset = user.text.indexOf("IYieldSource", user.text.indexOf("interface instance"))
        val element = user.findElementAt(offset)!!
        val expected = source.findElementAt(source.text.indexOf("IYieldSource"))!!
        val symbol = DamlModuleNames.symbolAt(user.text, offset)!!
        val resolved = DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, user.virtualFile)

        assertEquals(expected, resolved)
        val targets = DamlGotoDeclarationHandler().getGotoDeclarationTargets(element, offset, myFixture.editor).orEmpty()
        assertTrue(targets.any { it == expected })
        val actionTargets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset)
        assertTrue(actionTargets.any { it == expected })
    }

    fun testCtrlClickTypeApplicationPrefersExplicitImportedSourceSymbol() {
        val source = myFixture.addFileToProject(
            "vault-interface/daml/Vault/Strategy/Mandate.daml",
            """
module Vault.Strategy.Mandate where

data VMandate = VMandate with
    operator : Party

interface IMandate where
  viewtype VMandate
""".trimIndent()
        )
        myFixture.addFileToProject(
            "vault-test/.daml/package-database/2.2/vault-interface-0.1.0-hash/Vault/Strategy/Mandate.daml",
            source.text
        )
        val user = myFixture.addFileToProject(
            "vault-test/daml/Tests/VaultTest.daml",
            """
module Tests.VaultTest where

import Vault.Strategy.Mandate (IMandate)

test manRaw =
  mandateCid = toInterfaceContractId @IMandate manRaw
""".trimIndent()
        )
        myFixture.openFileInEditor(user.virtualFile)

        val symbolOffset = user.text.indexOf("IMandate", user.text.indexOf("@IMandate"))
        val markerOffset = user.text.indexOf("@IMandate")
        val expected = source.findElementAt(source.text.indexOf("IMandate"))!!

        assertEquals(expected, DamlModuleResolver.getInstance(project)
            .resolveSymbolReference(DamlModuleNames.symbolAt(user.text, symbolOffset)!!, user.virtualFile))
        assertEquals(expected, DamlGotoDeclarationHandler()
            .getGotoDeclarationTargets(user.findElementAt(symbolOffset), symbolOffset, myFixture.editor).orEmpty().single())
        assertEquals(expected, DamlGotoDeclarationHandler()
            .getGotoDeclarationTargets(user.findElementAt(markerOffset), markerOffset, myFixture.editor).orEmpty().single())
        assertEquals(expected, DamlDirectNavigationProvider().getNavigationElement(user.findElementAt(symbolOffset)!!))
        assertEquals(expected, DamlDirectNavigationProvider().getNavigationElement(user.findElementAt(markerOffset)!!))
    }

    fun testQualifiedSourceSymbolResolutionIgnoresDamlPackageDatabaseCopies() {
        val source = myFixture.addFileToProject(
            "vault-interface/daml/Vault/Vault.daml",
            """
module Vault.Vault where

template Vault
  with
    operator : Party
  where
    signatory operator

    choice RouteDeposit : ()
      controller operator
      do pure ()
""".trimIndent()
        )
        myFixture.addFileToProject(
            "vault-test/.daml/package-database/2.2/vault-interface/Vault/Vault.daml",
            source.text
        )
        val test = myFixture.addFileToProject(
            "vault-test/daml/Tests/VaultTest.daml",
            """
module Tests.VaultTest where

import qualified Vault.Vault as V

testRoute = script do
  submit operator ${'$'} exerciseCmd vault0 V.RouteDeposit
""".trimIndent()
        )

        val symbol = DamlModuleNames.symbolAt(test.text, test.text.indexOf("RouteDeposit"))!!
        val target = DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, test.virtualFile)!!

        assertEquals(source.virtualFile.path, target.containingFile.virtualFile.path)
        assertEquals(source.text.indexOf("RouteDeposit"), target.textRange.startOffset)
    }

    fun testGeneratedDamlModuleIsNotResolvedWhenSourceIsMissing() {
        myFixture.addFileToProject(
            "vault-test/.daml/package-database/2.2/vault-interface/Vault/Component/KYCPolicy.daml",
            """
module Vault.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()
""".trimIndent()
        )
        val user = myFixture.configureByText(
            DamlFileType,
            """
module User where

import Vault.Component.KYCPolicy (IKYCPolicy)

value : Optional IKYCPolicy
value = None
""".trimIndent()
        )

        val symbol = DamlModuleNames.symbolAt(user.text, user.text.lastIndexOf("IKYCPolicy"))!!

        assertNull(DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, user.virtualFile))
    }

    fun testOpenImportDoesNotResolveUnknownIdentifierToImportedModule() {
        myFixture.addFileToProject(
            "src/Vault/YieldSource.daml",
            """
module Vault.YieldSource where

interface IYieldSource where
  viewtype ()
""".trimIndent()
        )
        val user = myFixture.addFileToProject(
            "src/Vault/Impl/Strategy.daml",
            """
module Vault.Impl.Strategy where

import Vault.YieldSource

template Strategy
  with
    operator : Party
  where
    signatory operator

    test = unknownValue
""".trimIndent()
        )
        myFixture.openFileInEditor(user.virtualFile)

        val offset = user.text.indexOf("unknownValue")
        val symbol = DamlModuleNames.symbolAt(user.text, offset)!!

        assertNull(DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, user.virtualFile))
        assertNull(DamlGotoDeclarationHandler().getGotoDeclarationTargets(user.findElementAt(offset), offset, myFixture.editor))
    }

    fun testUnimportedWorkspaceSymbolDoesNotHijackUnqualifiedReference() {
        myFixture.addFileToProject(
            "vault-interface/daml/Vault/YieldSource.daml",
            """
module Vault.YieldSource where

interface IYieldSource where
  viewtype ()
""".trimIndent()
        )
        val user = myFixture.addFileToProject(
            "vault-impl/daml/Vault/Impl/Strategy.daml",
            """
module Vault.Impl.Strategy where

template Strategy
  with
    operator : Party
  where
    signatory operator

    value = IYieldSource
""".trimIndent()
        )

        val symbol = DamlModuleNames.symbolAt(user.text, user.text.indexOf("IYieldSource", user.text.indexOf("value")))!!

        assertNull(DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, user.virtualFile))
    }

    fun testCtrlClickChoiceControllerArgumentResolvesToChoiceField() {
        val file = myFixture.configureByText(
            DamlFileType,
            """
module Vault.IYieldSource where

import Vault.Holding (Holding)

interface IYieldSource where
  viewtype ()

  choice Allocate : ContractId IYieldSource
    with
      vault : Party
      funds : ContractId Holding
    controller vault
    do pure this
""".trimIndent()
        )
        myFixture.openFileInEditor(file.virtualFile)

        val usageOffset = file.text.indexOf("vault", file.text.indexOf("controller"))
        val declarationOffset = file.text.indexOf("vault : Party")
        val usage = file.findElementAt(usageOffset)!!
        val expected = file.findElementAt(declarationOffset)!!
        val symbol = DamlModuleNames.symbolAt(file.text, usageOffset)!!

        assertEquals(expected, DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, file.virtualFile))
        assertEquals(expected, DamlDirectNavigationProvider().getNavigationElement(usage))
        assertEquals(expected, DamlGotoDeclarationHandler().getGotoDeclarationTargets(usage, usageOffset, myFixture.editor).orEmpty().single())
        val actionTargets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, usageOffset)
        assertTrue(actionTargets.any { it == expected })
    }

    fun testTemplateFieldDoesNotLeakOutsideOwnerBlock() {
        val file = myFixture.configureByText(
            DamlFileType,
            """
module User where

template Vault
  with
    owner : Party
  where
    signatory owner

helper = owner
""".trimIndent()
        )

        val offset = file.text.indexOf("owner", file.text.indexOf("helper"))
        val symbol = DamlModuleNames.symbolAt(file.text, offset)!!

        assertNull(DamlModuleResolver.getInstance(project).resolveSymbolReference(symbol, file.virtualFile))
    }

    fun testGotoDeclarationIgnoresStringsAndComments() {
        myFixture.addFileToProject(
            "vault-interface/daml/Vault/Component/KYCPolicy.daml",
            """
module Vault.Component.KYCPolicy where

interface IKYCPolicy where
  viewtype ()
""".trimIndent()
        )
        val user = myFixture.configureByText(
            DamlFileType,
            """
module User where

import Vault.Component.KYCPolicy (IKYCPolicy)

debugText = "IKYCPolicy"
-- IKYCPolicy
""".trimIndent()
        )
        myFixture.openFileInEditor(user.virtualFile)
        val stringOffset = user.text.indexOf("IKYCPolicy", user.text.indexOf("\""))
        val commentOffset = user.text.lastIndexOf("IKYCPolicy")

        assertNull(DamlGotoDeclarationHandler().getGotoDeclarationTargets(user.findElementAt(stringOffset), stringOffset, myFixture.editor))
        assertNull(DamlGotoDeclarationHandler().getGotoDeclarationTargets(user.findElementAt(commentOffset), commentOffset, myFixture.editor))
    }
}
