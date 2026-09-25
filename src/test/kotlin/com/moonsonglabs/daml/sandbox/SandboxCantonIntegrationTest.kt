package com.moonsonglabs.daml.sandbox

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit acceptance task. Missing pinned runtime/DAR is a failure, never an assumption/skip. */
class SandboxCantonIntegrationTest {
    private val gson = Gson()
    private val http = JsonApiClient()
    private val template = "#simulator-acceptance:Main:Asset"

    @Test fun `pinned Canton runs two participants two synchronizers history reassignment and draft restart`() {
        val jar = Path.of(System.getenv("CANTON_JAR") ?: error("CANTON_JAR must point to Canton 3.4.11"))
        require(Files.isRegularFile(jar)) { "Missing CANTON_JAR: $jar" }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val version = ProcessBuilder(java, "-jar", jar.toString(), "--version").redirectErrorStream(true).start().let {
            val output = it.inputStream.bufferedReader().readText(); assertTrue(it.waitFor(30, TimeUnit.SECONDS)); output
        }
        assertTrue("Acceptance requires 3.4.11, resolved: $version", Regex("\\b3\\.4\\.11\\b").containsMatchIn(version))
        val fixture = Path.of("src/test/resources/sandbox/acceptance").toAbsolutePath()
        val dar = fixture.resolve(".daml/dist/simulator-acceptance-0.1.0.dar")
        require(Files.isRegularFile(dar)) { "Build the acceptance DAR with DPM 3.4.11 in $fixture before running this task." }
        val report = Files.createDirectories(Path.of("build/reports/canton-acceptance").toAbsolutePath())
        val workspace = Files.createTempDirectory("canton-acceptance")
        var profile = SandboxDefaults.newProfile(workspace)
        SandboxTopology.addParticipant(profile); SandboxTopology.addSynchronizer(profile)
        profile = SandboxTopology.rebased(profile, freePortBase())
        profile.darAssignments.add(DarAssignment(dar.toString(), profile.participants.map { it.id }.toMutableList()))
        profile.partyAllocations.clear()
        profile.participants.forEachIndexed { index, participant -> profile.synchronizers.forEach { sync ->
            profile.partyAllocations.add(PartyAllocation(if (index == 0) "Alice" else "Bob", participant.id, sync.id))
        } }
        val generator = SandboxGenerator(workspace)
        val launched = profile.deepCopy()
        val files = generator.generate(launched)
        var process: java.lang.Process? = null
        fun start(files: SandboxGeneratedFiles, logName: String): java.lang.Process {
            val log = report.resolve(logName)
            val next = ProcessBuilder(java, "-Xmx2g", "-XX:ActiveProcessorCount=4", "-jar", jar.toString(),
                "daemon", "-c", files.localConfig.toString(), "--bootstrap", files.localBootstrap.toString(), "--log-level-stdout", "WARN")
                .directory(files.localConfig.parent.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            process = next
            await(120) {
                check(next.isAlive) { "Canton exited: ${Files.readString(log).takeLast(6000)}" }
                Files.readString(log).contains("=== sandbox ready ===")
            }
            return next
        }
        try {
            start(files, "initial.log")
            val p1 = launched.participants[0]; val p2 = launched.participants[1]
            val alice = SandboxParties.fetch(http, url(p1), null).single { it.local && it.id.startsWith("Alice::") }.id
            val bob = SandboxParties.fetch(http, url(p2), null).single { it.local && it.id.startsWith("Bob::") }.id
            val syncs = connected(p1)
            assertEquals(2, syncs.size)
            assertEquals(2, connected(p2).size)
            val source = syncs.getValue(launched.synchronizers[0].name)
            val target = syncs.getValue(launched.synchronizers[1].name)
            val first = create(p1, alice, bob, source, 0)
            val explorer = SandboxLedgerExplorer(http)
            await(30) { explorer.fetch(launched, p2, null, "initial").activeContracts.any { it.contractId == first } }
            submit(p2, bob, source, mapOf("ExerciseCommand" to mapOf("templateId" to template,
                "contractId" to first, "choice" to "Acknowledge", "choiceArgument" to emptyMap<String, String>())))
            repeat(205) { create(p1, alice, bob, source, it + 1) }
            submit(p1, alice, source, mapOf("ExerciseCommand" to mapOf("templateId" to template,
                "contractId" to first, "choice" to "Archive", "choiceArgument" to emptyMap<String, String>())))
            val paged = SandboxLedgerExplorer(http)
            var snapshot = paged.fetch(launched, p1, null, "paged")
            assertFalse(snapshot.historyComplete)
            assertTrue(snapshot.events.any { it.contractId == first && it.kind == "Created" })
            assertTrue(snapshot.events.none { it.contractId == first && it.kind == "Archived" })
            assertTrue(LedgerExplorerRows.from(snapshot).none { it.contractId == first && it.kind == "Active" })
            repeat(10) { if (!snapshot.historyComplete) snapshot = paged.fetch(launched, p1, null, "paged") }
            assertTrue(snapshot.historyComplete)
            assertTrue(snapshot.events.any { it.contractId == first && it.kind == "Archived" })
            assertEquals(205, snapshot.activeContracts.size)
            val moving = snapshot.activeContracts.first().contractId
            val unassigned = reassign(p1, alice, mapOf("UnassignCommand" to mapOf("value" to mapOf(
                "contractId" to moving, "source" to source, "target" to target))))
            Files.writeString(report.resolve("unassignment.json"), gson.toJson(unassigned))
            val events = unassigned.getAsJsonObject("reassignment").getAsJsonArray("events")
            val reassignmentId = events.first().asJsonObject.getAsJsonObject("JsUnassignedEvent").getAsJsonObject("value").get("reassignmentId").asString
            // Canton 3.4.11 completes automatic assignment after an accepted unassignment.
            // Observe its actual event and ACS instead of racing a second assignment command.
            await(30) {
                snapshot = paged.fetch(launched, p1, null, "paged")
                snapshot.events.any { it.kind == "Assigned" && it.reassignmentId == reassignmentId } &&
                    snapshot.activeContracts.any { it.contractId == moving && it.synchronizerId == target }
            }
            assertTrue(snapshot.events.any { it.kind == "Unassigned" && it.contractId == moving && it.sourceSynchronizerId == source && it.targetSynchronizerId == target })
            assertTrue(snapshot.events.any { it.kind == "Assigned" && it.contractId == moving })
            assertEquals(target, snapshot.activeContracts.single { it.contractId == moving }.synchronizerId)
            // Draft edits are detached; generating files does not alter the live process or connectivity.
            val draft = launched.deepCopy()
            draft.bindings.first { it.participantId == p2.id && it.synchronizerId == draft.synchronizers[1].id }.connected = false
            draft.partyAllocations.removeIf { it.participantId == p2.id && it.synchronizerId == draft.synchronizers[1].id }
            val session = SandboxSessionState(profileId = launched.id, sessionId = "initial", launchedProfile = launched,
                status = SandboxSessionStatus.RUNNING, ownsProcess = true)
            assertTrue(session.hasPendingChanges(draft))
            val regenerated = generator.generate(draft)
            assertTrue(process!!.isAlive)
            assertEquals(2, connected(p2).size)
            assertTrue(launched.bindings.all { it.connected })
            stop(process!!)
            start(regenerated, "restarted.log")
            assertEquals(1, connected(draft.participants[1]).size)
            assertEquals(2, connected(draft.participants[0]).size)
            val restarted = paged.fetch(draft, draft.participants[0], null, "restarted")
            assertTrue(restarted.activeContracts.isEmpty())
            assertTrue(restarted.events.isEmpty())
            Files.writeString(report.resolve("summary.txt"), """
                Baseline: ${version.trim()}
                Two participants and two synchronizers: passed
                DAR upload and Alice/Bob allocation on both synchronizers: passed
                Bob command on Alice contract across participants: passed
                206 creates, cross-participant exercise, archive beyond initial 200-update page: passed
                ACS remains authoritative before and after Load More: passed
                Unassignment, assignment, source and target IDs: passed
                Draft disconnect and generation preserve running network: passed
                Restart applies disconnect and clears memory ledger and offsets: passed
                All owned fixture processes stopped in finally.
            """.trimIndent())
        } finally { process?.let(::stop) }
    }

    private fun create(participant: ParticipantNode, alice: String, bob: String, sync: String, number: Int): String {
        val result = submit(participant, alice, sync, mapOf("CreateCommand" to mapOf("templateId" to template,
            "createArguments" to mapOf("owner" to alice, "peer" to bob, "number" to number.toString()))))
        return result.getAsJsonObject("transaction").getAsJsonArray("events").first().asJsonObject.getAsJsonObject("CreatedEvent").get("contractId").asString
    }
    private fun submit(participant: ParticipantNode, party: String, sync: String, command: Map<String, Any>): JsonObject =
        request(participant, "POST", "/v2/commands/submit-and-wait-for-transaction", mapOf("commands" to mapOf(
            "userId" to "participant_admin", "commandId" to UUID.randomUUID().toString(), "actAs" to listOf(party),
            "synchronizerId" to sync, "commands" to listOf(command))))
    private fun reassign(participant: ParticipantNode, party: String, command: Map<String, Any>): JsonObject =
        request(participant, "POST", "/v2/commands/submit-and-wait-for-reassignment", mapOf(
            "reassignmentCommands" to mapOf("workflowId" to "acceptance", "userId" to "participant_admin",
                "commandId" to UUID.randomUUID().toString(), "submissionId" to UUID.randomUUID().toString(),
                "submitter" to party, "commands" to listOf(mapOf("command" to command))),
            "eventFormat" to mapOf("filtersByParty" to mapOf(party to mapOf("cumulative" to emptyList<String>())), "verbose" to true)))
    private fun connected(participant: ParticipantNode): Map<String, String> = request(participant, "GET", "/v2/state/connected-synchronizers")
        .getAsJsonArray("connectedSynchronizers").associate { it.asJsonObject.get("synchronizerAlias").asString to it.asJsonObject.get("synchronizerId").asString }
    private fun request(participant: ParticipantNode, method: String, path: String, body: Any? = null): JsonObject {
        val payload = body?.let(gson::toJson)
        var response = http.request(method, url(participant), path, null, payload)
        // The source participant can publish completion before all target locks are released.
        // Retry only the explicit transient lock verdict, preserving this logical command ID.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (path.endsWith("for-reassignment") && response.status == 409 &&
            response.body.contains("LOCAL_VERDICT_LOCKED_CONTRACTS") && System.nanoTime() < deadline) {
            Thread.sleep(1000)
            response = http.request(method, url(participant), path, null, payload)
        }
        check(response.status in 200..299) { "$method $path: ${response.status} ${response.body}" }
        return JsonParser.parseString(response.body).asJsonObject
    }
    private fun url(participant: ParticipantNode) = "http://127.0.0.1:${participant.jsonPort}"
    private fun stop(process: java.lang.Process) {
        process.destroy()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); check(process.waitFor(10, TimeUnit.SECONDS)) }
    }
    private fun await(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) { check(System.nanoTime() < deadline) { "Canton fixture timed out" }; Thread.sleep(200) }
    }
    private fun freePortBase(): Int = (28000..40000 step 20).first { base ->
        val sockets = mutableListOf<ServerSocket>()
        try { (base + 1..base + 12).forEach { sockets += ServerSocket(it) }; true }
        catch (_: Exception) { false } finally { sockets.forEach { it.close() } }
    }
}
