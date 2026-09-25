package com.moonsonglabs.daml.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

data class LedgerContractRow(
    val templateId: String,
    val templateName: String,
    val contractId: String,
    val offset: String,
    val synchronizerId: String,
    val packageName: String,
    val createdAt: String,
    val signatories: List<String>,
    val observers: List<String>,
    val witnessParties: List<String>,
    val createArgument: Map<String, String> = emptyMap(),
    val rawJson: String
)

data class LedgerEventRow(
    val kind: String,
    val templateId: String,
    val templateName: String,
    val contractId: String,
    val offset: String,
    val synchronizerId: String,
    val packageName: String = "",
    val witnessParties: List<String>,
    val createArgument: Map<String, String> = emptyMap(),
    val rawJson: String,
    val sourceSynchronizerId: String = "",
    val targetSynchronizerId: String = "",
    val reassignmentId: String = ""
)

data class LedgerExplorerSnapshot(
    val participantName: String,
    val endpointUrl: String,
    val ledgerEnd: Long,
    val parties: List<String>,
    val activeContracts: List<LedgerContractRow>,
    val archivedContracts: List<LedgerEventRow>,
    val events: List<LedgerEventRow>,
    val rawActiveResponse: String,
    val rawUpdatesResponse: String,
    val warnings: List<String>,
    val historyThrough: Long = ledgerEnd,
    val historyComplete: Boolean = true,
    val inFlightContracts: List<LedgerEventRow> = emptyList(),
    val knownParties: List<String> = parties
)

class SandboxLedgerExplorer(
    private val client: SandboxTransport = JsonApiClient(),
    private val projectRoot: Path? = null
) {
    private data class History(var through: Long = 0, var events: List<LedgerEventRow> = emptyList())
    private val histories = mutableMapOf<String, History>()

    @Synchronized
    fun fetch(profile: SandboxProfile, participant: ParticipantNode, token: String?, sessionId: String = "", selectedParties: Set<String>? = null): LedgerExplorerSnapshot {
        val endpoint = EndpointBuilder.participantEndpoints(profile).first { it.nodeId == participant.id && it.kind == "json" }
        val known = SandboxParties.fetch(client, endpoint.url, token)
        val parties = known.filter { if (selectedParties == null) it.local else it.id in selectedParties }.map { it.id }
        val warnings = mutableListOf<String>()
        if (parties.isEmpty()) {
            return LedgerExplorerSnapshot(participant.name, endpoint.url, 0, parties, emptyList(), emptyList(), emptyList(), "[]", "[]",
                listOf("No selected local parties. Allocate a party or select a known party."), knownParties = known.map { it.id })
        }
        val ledgerEnd = parseOffset(requireOk(client.request("GET", endpoint.url, "/v2/state/ledger-end", token, null), "ledger-end").body)
        val activeBody = activeContractsRequestBody(ledgerEnd, parties)
        val httpActive = client.request("POST", endpoint.url, "/v2/state/active-contracts", token, activeBody)
        val activeResponse = requireOk(if (httpActive.status == 413 && client is SandboxStreamingTransport)
            client.activeContracts(endpoint.url, token, activeBody) else httpActive, "active-contracts")
        val key = "$sessionId:${profile.id}:${participant.id}:${endpoint.url}:${parties.sorted()}"
        if (histories.size > 32) histories.clear()
        val history = histories.getOrPut(key) { History() }
        if (ledgerEnd < history.through) { history.through = 0; history.events = emptyList() }
        var rawUpdates = "[]"
        var complete = false
        runCatching {
            if (ledgerEnd == history.through) { complete = true; return@runCatching }
            val response = requireOk(client.request("POST", endpoint.url, "/v2/updates?limit=200", token,
                updatesRequestBody(ledgerEnd, parties, history.through)), "updates")
            rawUpdates = response.body
            val updates = JsonParser.parseString(rawUpdates).asJsonArray
            val maxOffset = updates.mapNotNull { entry ->
                entry.asJsonObject.obj("update")?.entrySet()?.firstOrNull()?.value?.asJsonObject?.obj("value")?.get("offset")?.asLong
            }.maxOrNull() ?: history.through
            check(updates.isEmpty || maxOffset > history.through) { "History page made no progress; refusing to skip updates." }
            complete = updates.size() < 200 || maxOffset >= ledgerEnd
            history.events = (history.events + parseUpdateEvents(rawUpdates)).distinctBy {
                listOf(it.kind, it.contractId, it.offset, it.reassignmentId, it.rawJson)
            }
            history.through = if (complete) ledgerEnd else maxOffset
        }.onFailure { warnings += "Current contracts loaded; history unavailable: ${it.message}" }
        if (!complete) warnings += "History incomplete: loaded through participant offset ${history.through} of $ledgerEnd. Load more to continue."
        return LedgerExplorerSnapshot(participant.name, endpoint.url, ledgerEnd, parties, parseActiveContracts(activeResponse.body),
            history.events.filter { it.kind == "Archived" }, history.events, prettyJson(activeResponse.body), prettyJson(rawUpdates), warnings,
            history.through, complete, parseInFlightContracts(activeResponse.body), known.map { it.id })
    }

    private fun requireOk(response: SandboxHttpResponse, path: String): SandboxHttpResponse {
        if (response.status in 200..299) return response
        val body = response.body.take(800)
        error("JSON API $path returned HTTP ${response.status}: $body")
    }

    internal fun activeContractsRequestBody(activeAtOffset: Long, parties: List<String>): String {
        val root = JsonObject()
        root.addProperty("activeAtOffset", activeAtOffset)
        root.add("eventFormat", eventFormat(parties))
        return gson.toJson(root)
    }

    internal fun updatesRequestBody(endInclusive: Long, parties: List<String>, beginExclusive: Long = 0): String {
        val root = JsonObject()
        root.addProperty("beginExclusive", beginExclusive)
        root.addProperty("endInclusive", endInclusive)
        val includeTransactions = JsonObject()
        includeTransactions.addProperty("transactionShape", "TRANSACTION_SHAPE_ACS_DELTA")
        includeTransactions.add("eventFormat", eventFormat(parties))
        val updateFormat = JsonObject()
        updateFormat.add("includeTransactions", includeTransactions)
        updateFormat.add("includeReassignments", eventFormat(parties))
        root.add("updateFormat", updateFormat)
        return gson.toJson(root)
    }

    internal fun parseActiveContracts(body: String): List<LedgerContractRow> {
        val parsed = JsonParser.parseString(body)
        require(parsed.isJsonArray) { "Unexpected ledger response: expected a JSON array." }
        val root = parsed.asJsonArray
        return root.mapNotNull { entry ->
            val active = entry.asJsonObject.obj("contractEntry")?.obj("JsActiveContract") ?: return@mapNotNull null
            val createdEnvelope = active.obj("createdEvent") ?: return@mapNotNull null
            val created = createdEnvelope.obj("CreatedEvent") ?: createdEnvelope
            parseCreatedContract(created, active.string("synchronizerId"), gson.toJson(created))
        }
    }

    internal fun parseUpdateEvents(body: String): List<LedgerEventRow> {
        val parsed = JsonParser.parseString(body)
        require(parsed.isJsonArray) { "Unexpected ledger response: expected a JSON array." }
        val root = parsed.asJsonArray
        return root.flatMap { updateEnvelope ->
            val update = updateEnvelope.asJsonObject.obj("update") ?: return@flatMap emptyList()
            val reassignment = update.obj("Reassignment")?.obj("value")
            if (reassignment != null) {
                return@flatMap reassignment.array("events").mapNotNull { event ->
                    val obj = event.asJsonObject
                    val assigned = obj.obj("JsAssignmentEvent") ?: obj.obj("AssignedEvent")
                    val unassigned = obj.obj("JsUnassignedEvent") ?: obj.obj("UnassignedEvent")
                    when {
                        assigned != null -> reassignmentRow(assigned.obj("value") ?: assigned, "Assigned", reassignment.string("offset"))
                        unassigned != null -> reassignmentRow(unassigned.obj("value") ?: unassigned, "Unassigned", reassignment.string("offset"))
                        else -> null
                    }
                }
            }
            val transaction = update.obj("Transaction")?.obj("value") ?: return@flatMap emptyList()
            val synchronizerId = transaction.string("synchronizerId")
            val transactionOffset = transaction.string("offset")
            transaction.array("events").flatMap { event ->
                val eventObject = event.asJsonObject
                val created = eventObject.obj("CreatedEvent")
                val archived = eventObject.obj("ArchivedEvent")
                when {
                    created != null -> listOf(parseCreatedEvent(created, synchronizerId, transactionOffset, gson.toJson(created)))
                    archived != null -> listOf(parseArchivedEvent(archived, synchronizerId, transactionOffset, gson.toJson(archived)))
                    else -> emptyList()
                }
            }
        }
    }

    internal fun parseInFlightContracts(body: String): List<LedgerEventRow> =
        (JsonParser.parseString(body).asArrayOrNull() ?: JsonArray()).mapNotNull { entry ->
            val contract = entry.asJsonObject.obj("contractEntry") ?: return@mapNotNull null
            val assigned = contract.obj("JsIncompleteAssigned")?.obj("assignedEvent")
            val unassigned = contract.obj("JsIncompleteUnassigned")?.obj("unassignedEvent")
            when {
                assigned != null -> reassignmentRow(assigned, "In-flight assignment", "")
                unassigned != null -> reassignmentRow(unassigned, "In-flight unassignment", "")
                else -> null
            }
        }

    private fun reassignmentRow(event: JsonObject, kind: String, offset: String): LedgerEventRow {
        val created = event.obj("createdEvent")?.let { it.obj("CreatedEvent") ?: it }
        val data = created ?: event
        return LedgerEventRow(kind, data.string("templateId"), shortTemplate(data.string("templateId")),
            data.string("contractId"), offset, if (kind.contains("nassigned") || kind.contains("unassignment")) event.string("source") else event.string("target"),
            data.string("packageName"), data.stringArray("witnessParties"), data.obj("createArgument")?.stringMap().orEmpty(), prettyJson(gson.toJson(event)),
            event.string("source"), event.string("target"), event.string("reassignmentId"))
    }

    private fun parseCreatedContract(event: JsonObject, synchronizerId: String, rawJson: String): LedgerContractRow =
        LedgerContractRow(
            templateId = event.string("templateId"),
            templateName = shortTemplate(event.string("templateId")),
            contractId = event.string("contractId"),
            offset = event.string("offset"),
            synchronizerId = synchronizerId,
            packageName = event.string("packageName"),
            createdAt = event.string("createdAt"),
            signatories = event.stringArray("signatories"),
            observers = event.stringArray("observers"),
            witnessParties = event.stringArray("witnessParties"),
            createArgument = event.obj("createArgument")?.stringMap().orEmpty(),
            rawJson = prettyJson(rawJson)
        )

    private fun parseCreatedEvent(event: JsonObject, synchronizerId: String, transactionOffset: String, rawJson: String): LedgerEventRow =
        LedgerEventRow(
            kind = "Created",
            templateId = event.string("templateId"),
            templateName = shortTemplate(event.string("templateId")),
            contractId = event.string("contractId"),
            offset = event.string("offset").ifBlank { transactionOffset },
            synchronizerId = synchronizerId,
            packageName = event.string("packageName"),
            witnessParties = event.stringArray("witnessParties"),
            createArgument = event.obj("createArgument")?.stringMap().orEmpty(),
            rawJson = prettyJson(rawJson)
        )

    private fun parseArchivedEvent(event: JsonObject, synchronizerId: String, transactionOffset: String, rawJson: String): LedgerEventRow =
        LedgerEventRow(
            kind = "Archived",
            templateId = event.string("templateId"),
            templateName = shortTemplate(event.string("templateId")),
            contractId = event.string("contractId"),
            offset = event.string("offset").ifBlank { transactionOffset },
            synchronizerId = synchronizerId,
            packageName = event.string("packageName"),
            witnessParties = event.stringArray("witnessParties"),
            rawJson = prettyJson(rawJson)
        )

    private fun eventFormat(parties: List<String>): JsonObject {
        val filtersByParty = JsonObject()
        parties.distinct().sorted().forEach { party ->
            val filter = JsonObject()
            filter.add("cumulative", JsonArray())
            filtersByParty.add(party, filter)
        }
        val eventFormat = JsonObject()
        eventFormat.add("filtersByParty", filtersByParty)
        eventFormat.addProperty("verbose", true)
        return eventFormat
    }

    private fun parseOffset(body: String): Long =
        JsonParser.parseString(body).asJsonObject.get("offset")?.asLong ?: 0L

    private fun prettyJson(raw: String): String =
        runCatching { gson.toJson(JsonParser.parseString(raw)) }.getOrDefault(raw)

    private fun shortTemplate(templateId: String): String =
        templateId.substringAfterLast(':').ifBlank { templateId }


    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
    }
}

private fun JsonElement.asArrayOrNull(): JsonArray? =
    takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.array(name: String): JsonArray =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.boolean(name: String): Boolean =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

private fun JsonObject.stringArray(name: String): List<String> =
    array(name).mapNotNull { element ->
        element.takeIf { !it.isJsonNull }?.asString
    }

private fun JsonObject.stringMap(): Map<String, String> =
    entrySet().associate { (key, value) ->
        key to when {
            value.isJsonNull -> "null"
            value.isJsonPrimitive -> value.asString
            else -> value.toString()
        }
    }
