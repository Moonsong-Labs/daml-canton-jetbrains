package com.moonsonglabs.daml.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamlLocalBindingsTest {
    @Test
    fun `resolves choice controller argument to with-field`() {
        val text = """
module Vault.IYieldSource where

interface IYieldSource where
  viewtype ()

  choice Allocate : ContractId IYieldSource
    with
      vault : Party
      funds : ContractId Holding
    controller vault
    do allocateImpl this vault funds
""".trimIndent()
        val usage = DamlModuleNames.symbolAt(text, text.indexOf("vault", text.indexOf("controller")))!!
        val binding = DamlLocalBindings.resolve(text, usage)

        assertEquals(text.indexOf("vault : Party"), binding?.startOffset)
    }

    @Test
    fun `prefers nearest choice field and does not leak across sibling choices`() {
        val text = """
module M where

interface I where
  viewtype ()

  choice Allocate : ()
    with
      vault : Party
    controller vault
    do pure ()

  choice RequestWithdraw : ()
    with
      vault : Party
    controller vault
    do pure ()
""".trimIndent()
        val secondUsage = DamlModuleNames.symbolAt(text, text.lastIndexOf("vault"))!!
        val binding = DamlLocalBindings.resolve(text, secondUsage)

        assertEquals(text.indexOf("vault : Party", text.indexOf("RequestWithdraw")), binding?.startOffset)
    }

    @Test
    fun `does not resolve declaration token to itself`() {
        val text = """
module M where

template T
  with
    operator : Party
  where
    signatory operator
""".trimIndent()
        val declaration = DamlModuleNames.symbolAt(text, text.indexOf("operator : Party"))!!

        assertNull(DamlLocalBindings.resolve(text, declaration))
    }
}
