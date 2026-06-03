package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerExplorerRowsTest {
    @Test
    fun `maps active contracts and events into explorer activity rows`() {
        val rows = LedgerExplorerRows.from(snapshot())

        assertEquals(listOf("Archived", "Active", "Created", "Active"), rows.map { it.kind })
        val active = rows.first { it.kind == "Active" && it.contractId == "00active" }
        assertEquals("PublicSettlement", active.templateName)
        assertEquals("global", active.syncName)
        assertEquals(listOf("BridgePublic::party", "Operator::party"), active.parties)
        assertEquals("private-settlement", active.packageName)
        assertEquals(mapOf("amount" to "42.0"), active.argumentFields)
        val activeFromHistory = rows.first { it.kind == "Active" && it.contractId == "00created" }
        assertEquals("PrivateOffer", activeFromHistory.templateName)
        assertEquals("privateSync", activeFromHistory.syncName)
    }

    @Test
    fun `search matches template contract party synchronizer package and arguments`() {
        val row = LedgerExplorerRows.from(snapshot()).first { it.kind == "Active" && it.contractId == "00active" }

        listOf("publicsettlement", "00active", "bridgepublic", "global", "private-settlement", "amount", "42.0")
            .forEach { query -> assertTrue("Expected query $query to match", LedgerExplorerRows.matches(row, query)) }
        assertFalse(LedgerExplorerRows.matches(row, "not-present"))
    }

    @Test
    fun `filters combine status synchronizer party and search state`() {
        val rows = LedgerExplorerRows.from(snapshot())

        val visible = LedgerExplorerRows.filter(
            rows,
            ExplorerFilterState(
                query = "privateoffer",
                syncDomains = setOf("privateSync"),
                parties = setOf("IssuerPrivate"),
                kinds = setOf("Archived")
            )
        )

        assertEquals(1, visible.size)
        assertEquals("Archived", visible.single().kind)
        assertEquals("PrivateOffer", visible.single().templateName)
    }

    @Test
    fun `filter returns deterministic empty result for impossible state`() {
        val visible = LedgerExplorerRows.filter(
            LedgerExplorerRows.from(snapshot()),
            ExplorerFilterState(syncDomains = setOf("missing"), parties = setOf("Operator"), kinds = setOf("Active"))
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `empty sync and party selections mean no filter rather than no rows`() {
        val visible = LedgerExplorerRows.filter(
            LedgerExplorerRows.from(snapshot()),
            ExplorerFilterState(syncDomains = emptySet(), parties = emptySet(), kinds = setOf("Active", "Created", "Archived"))
        )

        assertEquals(4, visible.size)
    }

    private fun snapshot(): LedgerExplorerSnapshot =
        LedgerExplorerSnapshot(
            participantName = "bridge",
            endpointUrl = "http://127.0.0.1:8577",
            ledgerEnd = 64,
            parties = listOf("BridgePublic::party", "Operator::party", "IssuerPrivate::party"),
            activeContracts = listOf(
                LedgerContractRow(
                    templateId = "pkg:PrivateSettlement:PublicSettlement",
                    templateName = "PublicSettlement",
                    contractId = "00active",
                    offset = "55",
                    synchronizerId = "global::abc",
                    packageName = "private-settlement",
                    createdAt = "2026-05-25T12:35:56Z",
                    signatories = listOf("Operator::party"),
                    observers = listOf("BridgePublic::party"),
                    witnessParties = listOf("BridgePublic::party"),
                    createArgument = mapOf("amount" to "42.0"),
                    rawJson = """{"contractId":"00active"}"""
                )
            ),
            archivedContracts = listOf(
                LedgerEventRow(
                    kind = "Archived",
                    templateId = "pkg:PrivateSettlement:PrivateOffer",
                    templateName = "PrivateOffer",
                    contractId = "00archived",
                    offset = "63",
                    synchronizerId = "privateSync::def",
                    packageName = "private-settlement",
                    witnessParties = listOf("IssuerPrivate::party"),
                    rawJson = """{"contractId":"00archived"}"""
                )
            ),
            events = listOf(
                LedgerEventRow(
                    kind = "Created",
                    templateId = "pkg:PrivateSettlement:PrivateOffer",
                    templateName = "PrivateOffer",
                    contractId = "00created",
                    offset = "62",
                    synchronizerId = "privateSync::def",
                    packageName = "private-settlement",
                    witnessParties = listOf("IssuerPrivate::party"),
                    rawJson = """{"contractId":"00created"}"""
                ),
                LedgerEventRow(
                    kind = "Archived",
                    templateId = "pkg:PrivateSettlement:PrivateOffer",
                    templateName = "PrivateOffer",
                    contractId = "00archived",
                    offset = "63",
                    synchronizerId = "privateSync::def",
                    packageName = "private-settlement",
                    witnessParties = listOf("IssuerPrivate::party"),
                    rawJson = """{"contractId":"00archived"}"""
                )
            ),
            rawActiveResponse = "[]",
            rawUpdatesResponse = "[]",
            warnings = emptyList()
        )
}
