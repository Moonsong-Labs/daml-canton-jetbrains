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
    val rawJson: String
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
    val warnings: List<String>
)

class SandboxLedgerExplorer(
    private val client: JsonApiClient = JsonApiClient(),
    private val projectRoot: Path? = null
) {
    fun fetch(profile: SandboxProfile, participant: ParticipantNode, token: String?): LedgerExplorerSnapshot {
        val endpoint = EndpointBuilder.participantEndpoints(profile)
            .firstOrNull { it.nodeId == participant.id && it.kind == "json" }
            ?: error("No JSON API endpoint configured for ${participant.name}.")

        val partiesResponse = requireOk(client.request("GET", endpoint.url, "/v2/parties", token, null), "/v2/parties")
        val knownParties = parseParties(partiesResponse.body)
        val warnings = mutableListOf<String>()
        val parties = resolvePartyIds(profile, participant, knownParties)

        if (parties.isEmpty()) {
            warnings += "No parties were found for ${participant.name}. Allocate parties or refresh the running sandbox."
            return LedgerExplorerSnapshot(
                participantName = participant.name,
                endpointUrl = endpoint.url,
                ledgerEnd = 0,
                parties = emptyList(),
                activeContracts = emptyList(),
                archivedContracts = emptyList(),
                events = emptyList(),
                rawActiveResponse = "[]",
                rawUpdatesResponse = "[]",
                warnings = warnings
            )
        }

        val ledgerEndResponse = requireOk(client.request("GET", endpoint.url, "/v2/state/ledger-end", token, null), "/v2/state/ledger-end")
        val ledgerEnd = parseOffset(ledgerEndResponse.body)
        val activeBody = activeContractsRequestBody(ledgerEnd, parties)
        val updatesBody = updatesRequestBody(ledgerEnd, parties)
        val activeResponse = requireOk(
            client.request("POST", endpoint.url, "/v2/state/active-contracts", token, activeBody),
            "/v2/state/active-contracts"
        )
        val updatesResponse = requireOk(
            client.request("POST", endpoint.url, "/v2/updates?limit=200", token, updatesBody),
            "/v2/updates"
        )
        val activeContracts = parseActiveContracts(activeResponse.body)
        val events = parseUpdateEvents(updatesResponse.body)

        return LedgerExplorerSnapshot(
            participantName = participant.name,
            endpointUrl = endpoint.url,
            ledgerEnd = ledgerEnd,
            parties = parties,
            activeContracts = activeContracts,
            archivedContracts = events.filter { it.kind == "Archived" },
            events = events,
            rawActiveResponse = prettyJson(activeResponse.body),
            rawUpdatesResponse = prettyJson(updatesResponse.body),
            warnings = warnings
        )
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

    internal fun updatesRequestBody(endInclusive: Long, parties: List<String>): String {
        val root = JsonObject()
        root.addProperty("beginExclusive", 0)
        root.addProperty("endInclusive", endInclusive)
        val includeTransactions = JsonObject()
        includeTransactions.addProperty("transactionShape", "TRANSACTION_SHAPE_ACS_DELTA")
        includeTransactions.add("eventFormat", eventFormat(parties))
        val updateFormat = JsonObject()
        updateFormat.add("includeTransactions", includeTransactions)
        root.add("updateFormat", updateFormat)
        return gson.toJson(root)
    }

    internal fun parseActiveContracts(body: String): List<LedgerContractRow> {
        val root = JsonParser.parseString(body).asArrayOrNull() ?: return emptyList()
        return root.mapNotNull { entry ->
            val active = entry.asJsonObject.obj("contractEntry")?.obj("JsActiveContract") ?: return@mapNotNull null
            val createdEnvelope = active.obj("createdEvent") ?: return@mapNotNull null
            val created = createdEnvelope.obj("CreatedEvent") ?: createdEnvelope
            parseCreatedContract(created, active.string("synchronizerId"), gson.toJson(created))
        }
    }

    internal fun parseUpdateEvents(body: String): List<LedgerEventRow> {
        val root = JsonParser.parseString(body).asArrayOrNull() ?: return emptyList()
        return root.flatMap { updateEnvelope ->
            val transaction = updateEnvelope
                .asJsonObject
                .obj("update")
                ?.obj("Transaction")
                ?.obj("value")
                ?: return@flatMap emptyList()
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

    private fun parseParties(body: String): List<KnownParty> =
        JsonParser.parseString(body)
            .asJsonObject
            .array("partyDetails")
            .map { KnownParty(it.asJsonObject.string("party"), it.asJsonObject.boolean("isLocal")) }

    private fun resolvePartyIds(
        profile: SandboxProfile,
        participant: ParticipantNode,
        knownParties: List<KnownParty>
    ): List<String> {
        val knownPartyIds = knownParties.map { it.party }.toSet()
        val generatedParties = generatedPartyParticipantMap(profile)
            .filterValues { it == participant.name || it == participant.id }
            .keys
            .filter { it in knownPartyIds }
            .toList()
        if (generatedParties.isNotEmpty()) return generatedParties.sorted()

        val hints = profile.partyAllocations
            .filter { it.participantId == participant.id }
            .map { it.partyHint }
            .distinct()
        val fromHints = knownParties
            .map { it.party }
            .filter { party -> hints.any { hint -> party == hint || party.startsWith("$hint::") } }
        if (fromHints.isNotEmpty()) return fromHints.sorted()

        return knownParties
            .filter { it.isLocal }
            .map { it.party }
            .sorted()
    }

    private fun generatedPartyParticipantMap(profile: SandboxProfile): Map<String, String> {
        val root = generatedRoot(profile) ?: return emptyMap()
        val path = root.resolve("participants.json")
        if (!Files.isRegularFile(path)) return emptyMap()
        return runCatching {
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            json.obj("party_participants")
                ?.entrySet()
                ?.associate { it.key to it.value.asString }
                ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun generatedRoot(profile: SandboxProfile): Path? =
        SandboxPaths.generatedRoot(profile, projectRoot)

    private fun prettyJson(raw: String): String =
        runCatching { gson.toJson(JsonParser.parseString(raw)) }.getOrDefault(raw)

    private fun shortTemplate(templateId: String): String =
        templateId.substringAfterLast(':').ifBlank { templateId }

    private data class KnownParty(val party: String, val isLocal: Boolean)

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
