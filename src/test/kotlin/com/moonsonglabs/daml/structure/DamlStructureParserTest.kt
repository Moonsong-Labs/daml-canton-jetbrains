package com.moonsonglabs.daml.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamlStructureParserTest {
    @Test
    fun `classifies module imports declarations choices and values`() {
        val structure = DamlStructureParser.parse(
            """
module Vault.DepositRequest where

import Daml.Script
import qualified Vault.Common as C

data DepositRequestState = Pending | Accepted
newtype DepositLock = DepositLock ContractId

template DepositRequest
  with
    operator : Party
  where
    signatory operator

    nonconsuming choice AcceptDeposit : ()
      controller operator
      do pure ()

    choice RejectDeposit : ()
      controller operator
      do pure ()

topLevelHelper : Party -> Update ()
topLevelHelper party = pure ()
""".trimIndent()
        )

        assertEquals("Vault.DepositRequest", structure.module?.name)
        assertEquals(DamlStructureKind.MODULE, structure.module?.kind)
        assertEquals(listOf("Daml.Script", "Vault.Common as C"), structure.imports.map { it.name })
        assertEquals(listOf(DamlStructureKind.IMPORT, DamlStructureKind.IMPORT), structure.imports.map { it.kind })
        assertEquals(
            listOf(
                "DepositRequestState" to DamlStructureKind.DATA,
                "DepositLock" to DamlStructureKind.NEWTYPE,
                "DepositRequest" to DamlStructureKind.TEMPLATE,
                "AcceptDeposit" to DamlStructureKind.CHOICE,
                "RejectDeposit" to DamlStructureKind.CHOICE,
                "topLevelHelper" to DamlStructureKind.FUNCTION
            ),
            structure.declarations.map { it.name to it.kind }
        )
    }

    @Test
    fun `ignores declarations inside comments and strings`() {
        val structure = DamlStructureParser.parse(
            """
module User where

-- template FakeComment
{- data FakeBlock = FakeBlock -}
textValue = "choice FakeString : ()"

interface IPolicy where
  viewtype VPolicy
""".trimIndent()
        )

        assertEquals(
            listOf(
                "textValue" to DamlStructureKind.VALUE,
                "IPolicy" to DamlStructureKind.INTERFACE
            ),
            structure.declarations.map { it.name to it.kind }
        )
        assertNull(structure.importGroup())
    }
}
