package com.moonsonglabs.daml.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamlModuleNamesTest {
    @Test
    fun `parses module declaration`() {
        val text = "module Vault.Impl.SimpleProcessor where\n"
        assertEquals(
            DamlModuleNames.ModuleDeclaration("Vault.Impl.SimpleProcessor", text.indexOf("Vault")),
            DamlModuleNames.declaredModule(text)
        )
    }

    @Test
    fun `parses qualified import at component offset`() {
        val text = "import qualified Vault.Impl.SimpleProcessor as Processor\n"
        val ref = DamlModuleNames.importAt(text, text.indexOf("SimpleProcessor"))!!
        assertEquals("Vault.Impl.SimpleProcessor", ref.moduleName)
        assertEquals(text.indexOf("Vault"), ref.startOffset)
    }

    @Test
    fun `ignores non import lines`() {
        assertNull(DamlModuleNames.importAt("processor = 1\n", 2))
    }

    @Test
    fun `parses explicit import symbol at symbol offset`() {
        val text = "import Vault.Deposit.Processor (IProcessor)\n"
        val ref = DamlModuleNames.importAt(text, text.indexOf("IProcessor"))!!
        assertEquals("Vault.Deposit.Processor", ref.moduleName)
        assertEquals("IProcessor", ref.symbolName)
        assertEquals(text.indexOf("IProcessor"), ref.symbolStartOffset)
    }

    @Test
    fun `parses import declarations with aliases and explicit symbols`() {
        val text = """
import qualified Vault.Vault as V
import Vault.Component.KYCPolicy (IKYCPolicy, VKYCPolicy(..))
""".trimIndent()

        val imports = DamlModuleNames.imports(text)

        assertEquals("Vault.Vault", imports[0].moduleName)
        assertEquals(true, imports[0].qualified)
        assertEquals("V", imports[0].alias)
        assertEquals(setOf("IKYCPolicy", "VKYCPolicy"), imports[1].symbols)
    }

    @Test
    fun `parses qualified source symbol references`() {
        val text = "submit operator ${'$'} exerciseCmd vault0 V.RouteDeposit with depositor"
        val routeDeposit = DamlModuleNames.symbolAt(text, text.indexOf("RouteDeposit"))!!

        assertEquals("RouteDeposit", routeDeposit.name)
        assertEquals("V", routeDeposit.qualifier)
        assertEquals(text.indexOf("RouteDeposit"), routeDeposit.startOffset)
        assertNull(DamlModuleNames.symbolAt(text, text.indexOf("V.RouteDeposit")))
    }

    @Test
    fun `parses type application symbol from at marker or type offset`() {
        val text = "mandateCid = toInterfaceContractId @IMandate manRaw"

        val atOffset = text.indexOf("@IMandate")
        val typeOffset = text.indexOf("IMandate")

        assertEquals("IMandate", DamlModuleNames.symbolAtOrNear(text, atOffset)!!.name)
        assertEquals("IMandate", DamlModuleNames.symbolAtOrNear(text, typeOffset)!!.name)
    }

    @Test
    fun `ignores symbols inside comments and strings`() {
        val text = """
module User where

debugText = "IKYCPolicy"
-- IKYCPolicy
{- exerciseCmd cid RouteDeposit -}
""".trimIndent()

        assertNull(DamlModuleNames.symbolAtOrNear(text, text.indexOf("IKYCPolicy")))
        assertNull(DamlModuleNames.symbolAtOrNear(text, text.lastIndexOf("IKYCPolicy")))
        assertNull(DamlModuleNames.symbolAtOrNear(text, text.indexOf("RouteDeposit")))
    }

    @Test
    fun `finds interface declaration`() {
        val text = """
module Vault.Deposit.Processor where

interface IProcessor where
  viewtype V
""".trimIndent()
        val declaration = DamlModuleNames.declarationNamed(text, "IProcessor")!!
        assertEquals(text.indexOf("IProcessor"), declaration.startOffset)
    }
}
