package com.moonsonglabs.daml.sandbox

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

class SandboxGenerator(private val projectRoot: Path? = null) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun generate(profile: SandboxProfile): SandboxGeneratedFiles =
        writeGeneratedFiles(profile, cleanupLegacy = true)

    fun updateGeneratedFiles(profile: SandboxProfile): SandboxGeneratedFiles =
        writeGeneratedFiles(profile, cleanupLegacy = false)

    private fun writeGeneratedFiles(profile: SandboxProfile, cleanupLegacy: Boolean): SandboxGeneratedFiles {
        val root = generatedRoot(profile)
        val localDir = root.resolve("local")
        val scriptsDir = root.resolve("scripts")
        val logsDir = localDir.resolve("log")
        listOf(localDir, scriptsDir).forEach(Files::createDirectories)
        if (cleanupLegacy) deleteLegacyGeneratedDirs(root)

        val profileJson = root.resolve("profile.json")
        val localConfig = localDir.resolve("canton.conf")
        val localBootstrap = localDir.resolve("bootstrap.canton")
        val localRunScript = localDir.resolve("run.sh")
        val uploadScript = scriptsDir.resolve("upload-dars.canton")
        val allocationScript = scriptsDir.resolve("allocate-parties.canton")
        val statusScript = scriptsDir.resolve("status.canton")

        Files.writeString(profileJson, gson.toJson(SandboxPaths.profileForConfig(profile, projectRoot, root)))
        Files.writeString(localConfig, localConfig(profile))
        Files.writeString(localBootstrap, localBootstrap(profile, localDir))
        Files.writeString(localRunScript, localRunScript(profile))
        localRunScript.toFile().setExecutable(true, false)
        Files.writeString(uploadScript, uploadScript(profile, root))
        Files.writeString(allocationScript, allocationScript(profile))
        Files.writeString(statusScript, statusScript(profile))
        writeGeneratedGitignore(root)

        return SandboxGeneratedFiles(
            root = root,
            profileJson = profileJson,
            localConfig = localConfig,
            localBootstrap = localBootstrap,
            localRunScript = localRunScript,
            uploadScript = uploadScript,
            allocationScript = allocationScript,
            statusScript = statusScript,
            logsDir = logsDir
        )
    }

    fun generatedRoot(profile: SandboxProfile): Path =
        SandboxPaths.generatedRoot(profile, projectRoot)

    internal fun localConfig(profile: SandboxProfile): String = buildString {
        appendLine("canton {")
        appendLine("  sequencers {")
        profile.synchronizers.forEach { append(sequencerConfig(it.sequencer, "    ")) }
        appendLine("  }")
        appendLine("  mediators {")
        profile.synchronizers.forEach { append(mediatorConfig(it.mediator, "    ")) }
        appendLine("  }")
        appendLine("  participants {")
        profile.participants.forEach { append(participantConfig(it, "    ")) }
        appendLine("  }")
        appendLine("}")
    }

    internal fun localRunScript(profile: SandboxProfile): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("set -euo pipefail")
        appendLine()
        appendLine("""HERE="$(cd "$(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"""")
        appendLine("""CONF="canton.conf"""")
        appendLine("""BOOT="bootstrap.canton"""")
        appendLine("""LOG_DIR="log"""")
        appendLine("""SDK_VERSION="${'$'}{CANTON_SDK_VERSION:-3.4.11}"""")
        appendLine("""CANTON_JAR_DEFAULT="${'$'}HOME/.daml/sdk/${'$'}SDK_VERSION/canton/canton.jar"""")
        appendLine("""CANTON_DPM_JAR_DEFAULT="${'$'}HOME/.dpm/cache/components/canton-enterprise/${'$'}SDK_VERSION/lib/canton-enterprise-${'$'}SDK_VERSION.jar"""")
        appendLine("""CANTON_JAR="${'$'}{CANTON_JAR:-}"""")
        appendLine("""if [[ -z "${'$'}CANTON_JAR" ]]; then""")
        appendLine("""  if [[ -f "${'$'}CANTON_JAR_DEFAULT" ]]; then""")
        appendLine("    CANTON_JAR=\"${'$'}CANTON_JAR_DEFAULT\"")
        appendLine("""  elif [[ -f "${'$'}CANTON_DPM_JAR_DEFAULT" ]]; then""")
        appendLine("    CANTON_JAR=\"${'$'}CANTON_DPM_JAR_DEFAULT\"")
        appendLine("""  else""")
        appendLine("""    CANTON_JAR="$(find "${'$'}HOME/.dpm/cache/components" -path '*/lib/canton*.jar' -type f 2>/dev/null | sort -r | head -n 1 || true)"""")
        appendLine("""  fi""")
        appendLine("""fi""")
        appendLine()
        appendLine("""if [[ ! -f "${'$'}CANTON_JAR" ]]; then""")
        appendLine("""  echo "canton.jar not found at: ${'$'}CANTON_JAR" >&2""")
        appendLine("""  echo "Set CANTON_JAR or CANTON_SDK_VERSION. Available SDKs/DPM components:" >&2""")
        appendLine("""  ls "${'$'}HOME/.daml/sdk" 2>/dev/null >&2 || true""")
        appendLine("""  find "${'$'}HOME/.dpm/cache/components" -path '*/lib/canton*.jar' -type f 2>/dev/null >&2 || true""")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine()
        appendLine("""cd "${'$'}HERE"""")
        appendLine("""mkdir -p "${'$'}LOG_DIR"""")
        appendLine("""echo "Using ${'$'}CANTON_JAR"""")
        appendLine("""echo "Working dir: ${'$'}HERE"""")
        appendLine("""echo "Logs: ${'$'}LOG_DIR/canton.log"""")
        appendLine("echo")
        appendLine("echo \"=========================================================\"")
        appendLine("echo \" Canton sandbox endpoints\"")
        appendLine("echo \"=========================================================\"")
        profile.synchronizers.forEach { synchronizer ->
            appendLine("echo \"  synchronizer ${shellQuote(synchronizer.name)}\"")
            appendLine("echo \"    sequencer public grpc://localhost:${synchronizer.sequencer.publicPort}\"")
            appendLine("echo \"    sequencer admin  grpc://localhost:${synchronizer.sequencer.adminPort}\"")
            appendLine("echo \"    mediator admin   grpc://localhost:${synchronizer.mediator.adminPort}\"")
        }
        profile.participants.forEach { participant ->
            appendLine("echo \"  participant ${shellQuote(participant.name)}\"")
            appendLine("echo \"    ledger api       grpc://localhost:${participant.ledgerPort}\"")
            appendLine("echo \"    admin api        grpc://localhost:${participant.adminPort}\"")
            appendLine("echo \"    json api         http://localhost:${participant.jsonPort}\"")
        }
        appendLine("echo \"=========================================================\"")
        appendLine()
        appendLine("""exec java -jar "${'$'}CANTON_JAR" daemon -c "${'$'}CONF" --bootstrap "${'$'}BOOT"""")
    }

    internal fun localBootstrap(profile: SandboxProfile): String =
        localBootstrap(profile, generatedRoot(profile).resolve("local"))

    private fun localBootstrap(profile: SandboxProfile, workingDirectory: Path): String = buildString {
        appendLine("// Generated by the DAML JetBrains plugin. Safe to inspect; regenerate from the sandbox profile.")
        appendLine("import com.digitalasset.canton.config.RequireTypes.PositiveInt")
        appendLine("import com.digitalasset.canton.admin.api.client.data.StaticSynchronizerParameters")
        appendLine("import com.digitalasset.canton.version.ProtocolVersion")
        appendLine()
        appendLine("nodes.local.start()")
        appendLine()
        profile.synchronizers.forEach { synchronizer ->
            appendLine("bootstrap.synchronizer(")
            appendLine("  synchronizerName = \"${scalaString(synchronizer.name)}\",")
            appendLine("  sequencers = Seq(${synchronizer.sequencer.name}),")
            appendLine("  mediators = Seq(${synchronizer.mediator.name}),")
            appendLine("  synchronizerOwners = Seq(${synchronizer.sequencer.name}, ${synchronizer.mediator.name}),")
            appendLine("  synchronizerThreshold = PositiveInt.one,")
            appendLine("  staticSynchronizerParameters = StaticSynchronizerParameters.defaultsWithoutKMS(ProtocolVersion.forSynchronizer)")
            appendLine(")")
        }
        appendLine()
        profile.bindings.filter { it.connected }.forEach { binding ->
            val participant = profile.participant(binding.participantId) ?: return@forEach
            val synchronizer = profile.synchronizer(binding.synchronizerId) ?: return@forEach
            appendLine("${participant.name}.synchronizers.connect_local(${synchronizer.sequencer.name}, alias = \"${scalaString(synchronizer.name)}\")")
        }
        appendLine()
        append(uploadScript(profile, workingDirectory))
        appendLine()
        append(allocationScript(profile))
        appendLine()
        appendLine("health.status")
        appendLine("println(\"=== sandbox ready ===\")")
        profile.participants.forEach { participant ->
            appendLine("println(s\"${participant.name}: parties=${'$'}{${participant.name}.parties.list().map(_.party.toString)}\")")
        }
    }

    internal fun uploadScript(profile: SandboxProfile): String =
        uploadScript(profile, null)

    private fun uploadScript(profile: SandboxProfile, workingDirectory: Path?): String = buildString {
        appendLine("// Upload selected DARs to selected participants.")
        profile.darAssignments.forEach { assignment ->
            val darPath = darPathForScript(profile, assignment.darPath, workingDirectory)
            assignment.participantIds.forEach { participantId ->
                val participant = profile.participant(participantId) ?: return@forEach
                val connectedSynchronizers = profile.connectedSynchronizers(participant.id)
                if (connectedSynchronizers.isEmpty()) {
                    appendLine("${participant.name}.dars.upload(\"${scalaString(darPath)}\")")
                } else {
                    connectedSynchronizers.forEach { synchronizer ->
                        appendLine(
                            "${participant.name}.dars.upload(\"${scalaString(darPath)}\", " +
                                "synchronizerId = Some(${participant.name}.synchronizers.id_of(\"${scalaString(synchronizer.name)}\")))"
                        )
                    }
                }
            }
        }
    }

    private fun darPathForScript(
        profile: SandboxProfile,
        rawPath: String,
        workingDirectory: Path?
    ): String {
        val source = SandboxPaths.resolveProfilePath(rawPath, profile, projectRoot)
        return if (workingDirectory != null) {
            SandboxPaths.relativePath(workingDirectory, source)
        } else {
            SandboxPaths.relativeProfilePath(rawPath, profile, projectRoot)
        }
    }

    internal fun allocationScript(profile: SandboxProfile): String = buildString {
        appendLine("// Allocate selected parties on selected participants.")
        profile.partyAllocations.forEach { allocation ->
            val participant = profile.participant(allocation.participantId) ?: return@forEach
            val synchronizer = profile.synchronizer(allocation.synchronizerId) ?: return@forEach
            appendLine(
                "${participant.name}.parties.enable(\"${scalaString(allocation.partyHint)}\", " +
                    "synchronizer = Some(\"${scalaString(synchronizer.name)}\"), " +
                    "synchronizeParticipants = Seq(${participant.name}))"
            )
        }
    }

    internal fun statusScript(profile: SandboxProfile): String = buildString {
        appendLine("// Capture node health, DARs, parties, and synchronizer connectivity.")
        appendLine("health.status")
        profile.participants.forEach { participant ->
            appendLine("${participant.name}.health.status")
            appendLine("${participant.name}.dars.list()")
            appendLine("${participant.name}.parties.list()")
            profile.connectedSynchronizers(participant.id).forEach { sync ->
                appendLine("${participant.name}.synchronizers.is_connected(\"${scalaString(sync.name)}\")")
            }
        }
        profile.synchronizers.forEach { synchronizer ->
            appendLine("${synchronizer.sequencer.name}.health.status")
            appendLine("${synchronizer.mediator.name}.health.status")
        }
    }

    private fun participantConfig(
        participant: ParticipantNode,
        indent: String
    ): String = buildString {
        appendLine("$indent${participant.name} {")
        appendLine("$indent  ledger-api      { address = \"0.0.0.0\", port = ${participant.ledgerPort} }")
        appendLine("$indent  admin-api       { address = \"0.0.0.0\", port = ${participant.adminPort} }")
        appendLine("$indent  http-ledger-api { address = \"0.0.0.0\", port = ${participant.jsonPort} }")
        appendLine("${indent}  storage.type = memory")
        appendLine("$indent}")
    }

    private fun sequencerConfig(
        sequencer: SequencerNode,
        indent: String
    ): String = buildString {
        appendLine("$indent${sequencer.name} {")
        appendLine("$indent  public-api { address = \"0.0.0.0\", port = ${sequencer.publicPort} }")
        appendLine("$indent  admin-api  { address = \"0.0.0.0\", port = ${sequencer.adminPort} }")
        appendLine("${indent}  storage.type = memory")
        appendLine("$indent}")
    }

    private fun mediatorConfig(
        mediator: MediatorNode,
        indent: String
    ): String = buildString {
        appendLine("$indent${mediator.name} {")
        appendLine("$indent  admin-api { address = \"0.0.0.0\", port = ${mediator.adminPort} }")
        appendLine("${indent}  storage.type = memory")
        appendLine("$indent}")
    }

    private fun writeGeneratedGitignore(root: Path) {
        Files.writeString(
            root.resolve(".gitignore"),
            """
            |local/log/
            |data/
            |logs/
            |participants.json
            |parties.json
            |run-*-output.json
            |*.log
            |""".trimMargin()
        )
    }

    private fun deleteLegacyGeneratedDirs(root: Path) {
        root.resolve("logs").toFile().deleteRecursively()
        root.resolve("data").toFile().deleteRecursively()
        root.resolve("dars").toFile().deleteRecursively()
    }

    private fun scalaString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun shellQuote(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
