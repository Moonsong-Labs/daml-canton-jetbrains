package com.moonsonglabs.daml.sandbox

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxLedgerExplorerTest {
    private val explorer = SandboxLedgerExplorer()

    @Test
    fun `active contracts request uses ledger end and event format`() {
        val body = explorer.activeContractsRequestBody(51, listOf("Alice::party", "Bob::party"))
        val json = JsonParser.parseString(body).asJsonObject

        assertEquals(51, json.get("activeAtOffset").asInt)
        val eventFormat = json.getAsJsonObject("eventFormat")
        assertTrue(eventFormat.get("verbose").asBoolean)
        assertTrue(eventFormat.getAsJsonObject("filtersByParty").has("Alice::party"))
        assertTrue(eventFormat.getAsJsonObject("filtersByParty").has("Bob::party"))
    }

    @Test
    fun `updates request asks for ACS delta transaction events`() {
        val body = explorer.updatesRequestBody(51, listOf("Alice::party"))
        val includeTransactions = JsonParser.parseString(body)
            .asJsonObject
            .getAsJsonObject("updateFormat")
            .getAsJsonObject("includeTransactions")

        assertEquals(0, JsonParser.parseString(body).asJsonObject.get("beginExclusive").asInt)
        assertEquals(51, JsonParser.parseString(body).asJsonObject.get("endInclusive").asInt)
        assertEquals("TRANSACTION_SHAPE_ACS_DELTA", includeTransactions.get("transactionShape").asString)
        assertTrue(includeTransactions.getAsJsonObject("eventFormat").getAsJsonObject("filtersByParty").has("Alice::party"))
    }

    @Test
    fun `parses active contracts and archived update events`() {
        val active = explorer.parseActiveContracts(
            """
            [
              {
                "workflowId": "",
                "contractEntry": {
                  "JsActiveContract": {
                    "synchronizerId": "privateSync::abc",
                    "createdEvent": {
                      "offset": 44,
                      "contractId": "00active",
                      "templateId": "pkg:PrivateSettlement:PublicSettlement",
                      "packageName": "private-settlement-bridge",
                      "createdAt": "2026-05-25T12:35:56Z",
                      "signatories": ["BridgePublic::party"],
                      "observers": [],
                      "witnessParties": ["BridgePublic::party"]
                    }
                  }
                }
              }
            ]
            """.trimIndent()
        )
        val events = explorer.parseUpdateEvents(
            """
            [
              {
                "update": {
                  "Transaction": {
                    "value": {
                      "offset": 47,
                      "synchronizerId": "privateSync::abc",
                      "events": [
                        {
                          "ArchivedEvent": {
                            "offset": 47,
                            "contractId": "00active",
                            "templateId": "pkg:PrivateSettlement:PrivateOffer",
                            "packageName": "private-settlement-bridge",
                            "witnessParties": ["IssuerPrivate::party"]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, active.size)
        assertEquals("PublicSettlement", active.single().templateName)
        assertEquals("privateSync::abc", active.single().synchronizerId)
        assertEquals(1, events.size)
        assertEquals("Archived", events.single().kind)
        assertEquals("PrivateOffer", events.single().templateName)
    }

    @Test
    fun `parses active contracts when created event is wrapped`() {
        val active = explorer.parseActiveContracts(
            """
            [
              {
                "contractEntry": {
                  "JsActiveContract": {
                    "synchronizerId": "global::abc",
                    "createdEvent": {
                      "CreatedEvent": {
                        "offset": 55,
                        "contractId": "00wrapped",
                        "templateId": "pkg:PrivateSettlement:PrivateOffer",
                        "packageName": "private-settlement-bridge",
                        "witnessParties": ["IssuerPrivate::party"]
                      }
                    }
                  }
                }
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, active.size)
        assertEquals("00wrapped", active.single().contractId)
        assertEquals("PrivateOffer", active.single().templateName)
    }
}
