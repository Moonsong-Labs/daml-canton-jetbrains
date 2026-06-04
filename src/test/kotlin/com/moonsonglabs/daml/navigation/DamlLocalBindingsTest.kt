package com.moonsonglabs.daml.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamlLocalBindingsTest {
    @Test
    fun `resolves choice controller argument to with-field`() {
        val text = """
module Sample.IYieldSource where

interface IYieldSource where
  viewtype ()

  choice Allocate : ContractId IYieldSource
    with
      sample : Party
      funds : ContractId Holding
    controller sample
    do allocateImpl this sample funds
""".trimIndent()
        val usage = DamlModuleNames.symbolAt(text, text.indexOf("sample", text.indexOf("controller")))!!
        val binding = DamlLocalBindings.resolve(text, usage)

        assertEquals(text.indexOf("sample : Party"), binding?.startOffset)
    }

    @Test
    fun `prefers nearest choice field and does not leak across sibling choices`() {
        val text = """
module M where

interface I where
  viewtype ()

  choice Allocate : ()
    with
      sample : Party
    controller sample
    do pure ()

  choice RequestWithdraw : ()
    with
      sample : Party
    controller sample
    do pure ()
""".trimIndent()
        val secondUsage = DamlModuleNames.symbolAt(text, text.lastIndexOf("sample"))!!
        val binding = DamlLocalBindings.resolve(text, secondUsage)

        assertEquals(text.indexOf("sample : Party", text.indexOf("RequestWithdraw")), binding?.startOffset)
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
