package com.moonsonglabs.daml.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamlChoiceNamesTest {
    @Test
    fun `finds choice declarations`() {
        val declarations = DamlChoiceNames.declarations(privateSettlementSnippet)

        assertEquals(
            listOf("Accept", "ApproveForPublicSettlement"),
            declarations.map { it.name }
        )
        assertEquals(privateSettlementSnippet.indexOf("Accept"), declarations[0].startOffset)
    }

    @Test
    fun `detects choices in exercise calls`() {
        val accept = DamlChoiceNames.useAt(privateSettlementSnippet, privateSettlementSnippet.indexOf("Accept", privateSettlementSnippet.indexOf("exerciseCmd")))
        val approve = DamlChoiceNames.useAt(privateSettlementSnippet, privateSettlementSnippet.indexOf("ApproveForPublicSettlement", privateSettlementSnippet.indexOf("acceptedCid")))

        assertEquals("Accept", accept?.name)
        assertEquals("ApproveForPublicSettlement", approve?.name)
    }

    @Test
    fun `detects qualified choices in exercise calls`() {
        val text = "vault1 <- submit operator $ exerciseCmd vault0 V.RouteDeposit with depositor"
        val qualifier = DamlChoiceNames.useAt(text, text.indexOf("V.RouteDeposit"))
        val routeDeposit = DamlChoiceNames.useAt(text, text.indexOf("RouteDeposit"))

        assertNull(qualifier)
        assertEquals("RouteDeposit", routeDeposit?.name)
    }

    @Test
    fun `does not treat command template arguments as choice usages`() {
        val createTemplate = DamlChoiceNames.useAt(privateSettlementSnippet, privateSettlementSnippet.indexOf("PrivateOffer", privateSettlementSnippet.indexOf("createCmd")))
        val createAndExerciseTemplate = DamlChoiceNames.useAt("createAndExerciseCmd PrivateOffer Accept", "createAndExerciseCmd ".length)
        val createAndExerciseChoice = DamlChoiceNames.useAt("createAndExerciseCmd PrivateOffer Accept", "createAndExerciseCmd PrivateOffer ".length)

        assertNull(createTemplate)
        assertNull(createAndExerciseTemplate)
        assertEquals("Accept", createAndExerciseChoice?.name)
    }

    @Test
    fun `detects multiline exercise calls but ignores comments and strings`() {
        val text = """
test = script do
  _ <- submit investor do
    exerciseCmd
      offerCid
      Accept

  debug "-- exerciseCmd offerCid Accept"
  -- exerciseCmd offerCid Accept
""".trimIndent()

        val multiline = DamlChoiceNames.useAt(text, text.indexOf("Accept"))
        val stringMention = DamlChoiceNames.useAt(text, text.indexOf("Accept", text.indexOf("debug")))
        val commentMention = DamlChoiceNames.useAt(text, text.lastIndexOf("Accept"))

        assertEquals("Accept", multiline?.name)
        assertNull(stringMention)
        assertNull(commentMention)
    }

    @Test
    fun `ignores exercise-looking choices inside block comments`() {
        val text = """
test = script do
  {- exerciseCmd offerCid Accept -}
  pure ()
""".trimIndent()

        assertNull(DamlChoiceNames.useAt(text, text.indexOf("Accept")))
        assertEquals(emptyList<DamlChoiceNames.ChoiceUse>(), DamlChoiceNames.uses(text))
    }

    private val privateSettlementSnippet = """
module PrivateSettlement where

template PrivateOffer
  with
    investor : Party
  where
    signatory investor
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

  _ <- submit investor do
    createCmd PrivateOffer with investor
""".trimIndent()
}
