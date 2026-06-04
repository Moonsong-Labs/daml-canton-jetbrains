package com.moonsonglabs.daml.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name

@State(
    name = "CantonSandboxProfiles",
    storages = [Storage("canton-sandboxes.xml")]
)
@Service(Service.Level.PROJECT)
class SandboxProfileService(private val project: Project) : PersistentStateComponent<SandboxProfileService.State> {

    data class State(
        var profiles: MutableList<SandboxProfile> = mutableListOf(),
        var selectedProfileId: String = ""
    )

    private var state = State()
    private val gson = GsonBuilder().create()
    private val listeners = CopyOnWriteArrayList<(SandboxProfile) -> Unit>()
    private var detectedProfilesLoaded = false

    init {
        project.messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any { isDetectedProfilePath(it.path) }) {
                    refreshDetectedProfiles()
                }
            }
        })
    }

    override fun getState(): State {
        state.profiles.forEach(::normalizeProfile)
        return state
    }

    override fun loadState(state: State) {
        state.profiles.forEach(::normalizeProfile)
        this.state = state
        detectedProfilesLoaded = false
    }

    fun profiles(): List<SandboxProfile> {
        ensureProfile()
        return state.profiles
    }

    fun selectedProfile(): SandboxProfile {
        ensureProfile()
        val selected = state.profiles.firstOrNull { it.id == state.selectedProfileId }
        if (selected != null) return selected
        state.selectedProfileId = state.profiles.first().id
        return state.profiles.first()
    }

    fun selectProfile(id: String) {
        if (state.profiles.any { it.id == id }) {
            state.selectedProfileId = id
            notifyListeners()
        }
    }

    fun upsert(profile: SandboxProfile) {
        normalizeProfile(profile)
        val index = state.profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            state.profiles[index] = profile
        } else {
            state.profiles.add(profile)
        }
        state.selectedProfileId = profile.id
        notifyListeners()
    }

    fun createProfile(): SandboxProfile {
        val profile = SandboxDefaults.newProfile(DamlWorkspaceService.getInstance(project).projectRoot())
        upsert(profile)
        return profile
    }

    fun deleteProfile(id: String) {
        state.profiles.removeIf { it.id == id }
        state.selectedProfileId = state.profiles.firstOrNull()?.id.orEmpty()
        ensureProfile()
        notifyListeners()
    }

    fun addListener(listener: (SandboxProfile) -> Unit): Disposable {
        ensureProfile()
        listeners += listener
        listener(selectedProfile())
        return Disposable { listeners -= listener }
    }

    fun refreshDetectedProfiles() {
        detectedProfilesLoaded = false
        loadDetectedProfiles(force = true)
    }

    private fun ensureProfile() {
        loadDetectedProfiles()
        if (state.profiles.isNotEmpty()) return
        val profile = SandboxDefaults.newProfile(DamlWorkspaceService.getInstance(project).projectRoot())
        upsert(profile)
    }

    private fun loadDetectedProfiles(force: Boolean = false) {
        if (!force && detectedProfilesLoaded && state.profiles.any { !isDefaultManagedProfile(it) }) return
        detectedProfilesLoaded = true

        val existingIds = state.profiles.map { it.id }.toSet()
        val selectedBefore = state.profiles.firstOrNull { it.id == state.selectedProfileId }
        val detected = detectedProfileFiles().mapNotNull { path ->
            readDetectedProfile(path)?.let { DetectedProfile(path, inferredWorkspace(path), it) }
        }.onEach { detectedProfile ->
            normalizeDetectedProfile(detectedProfile.profile, detectedProfile.path, detectedProfile.workspace)
        }.distinctBy(::detectedProfileKey)
        if (detected.isEmpty()) return

        detected.forEach { detectedProfile -> upsertDetected(detectedProfile.profile) }
        val importedNewProfile = detected.any { it.profile.id !in existingIds }
        if (importedNewProfile || selectedBefore == null || isDefaultManagedProfile(selectedBefore)) {
            state.selectedProfileId = preferredDetectedProfile(detected)?.profile?.id ?: detected.first().profile.id
        }
        notifyListeners()
    }

    private fun detectedProfileFiles(): List<Path> {
        val workspaceService = DamlWorkspaceService.getInstance(project)
        val roots = linkedSetOf<Path>()
        workspaceService.projectRoot()?.let(roots::add)
        roots.addAll(workspaceService.discoverWorkspaces())

        val candidates = linkedSetOf<Path>()
        roots.filter { Files.isDirectory(it) }.forEach { root ->
            candidates.add(root.resolve(DETECTED_PROFILE_NAME))
            val generatedRoot = root.resolve(SandboxDefaults.GENERATED_DIR)
            if (Files.isDirectory(generatedRoot)) {
                Files.list(generatedRoot).use { paths ->
                    paths
                        .filter { Files.isDirectory(it) }
                        .map { it.resolve("profile.json") }
                        .forEach(candidates::add)
                }
            }
        }

        return candidates
            .filter { Files.isRegularFile(it) }
            .sortedWith(compareBy<Path>({ detectionPriority(it) }, { it.toString() }))
    }

    private fun readDetectedProfile(path: Path): SandboxProfile? =
        try {
            gson.fromJson(Files.readString(path), SandboxProfile::class.java)
        } catch (error: JsonSyntaxException) {
            thisLogger().warn("Ignoring invalid managed Canton sandbox profile at $path", error)
            null
        } catch (error: RuntimeException) {
            thisLogger().warn("Unable to read managed Canton sandbox profile at $path", error)
            null
        }

    private fun normalizeDetectedProfile(profile: SandboxProfile, path: Path, workspace: Path?) {
        if (profile.id.isBlank()) {
            profile.id = path.parent?.fileName?.toString()?.takeIf { it.isNotBlank() } ?: SandboxDefaults.newProfile(null).id
        }
        if (workspace != null) {
            profile.workspacePath = resolveAgainst(workspace, profile.workspacePath.ifBlank { "." }).toString()
        }
        if (profile.workspacePath.isBlank()) {
            profile.workspacePath = workspace?.toString().orEmpty()
        }
        if (profile.generatedPath.isBlank()) {
            profile.generatedPath = if (path.name == "profile.json" && path.parent?.parent?.name == SandboxDefaults.GENERATED_DIR) {
                path.parent.toString()
            } else if (profile.workspacePath.isNotBlank()) {
                Path.of(profile.workspacePath, SandboxDefaults.GENERATED_DIR, profile.id).toString()
            } else {
                ""
            }
        }
        normalizeProfile(profile)
    }

    private fun resolveAgainst(base: Path, rawPath: String): Path {
        val path = Path.of(rawPath)
        return if (path.isAbsolute) path.normalize() else base.resolve(path).normalize()
    }

    private fun upsertDetected(profile: SandboxProfile) {
        val index = state.profiles.indexOfFirst {
            it.id == profile.id || (profile.generatedPath.isNotBlank() && it.generatedPath == profile.generatedPath)
        }
        if (index >= 0) {
            state.profiles[index] = profile
        } else {
            state.profiles.add(profile)
        }
    }

    private fun notifyListeners() {
        if (listeners.isEmpty() || state.profiles.isEmpty()) return
        val selected = state.profiles.firstOrNull { it.id == state.selectedProfileId } ?: state.profiles.first()
        listeners.forEach { it(selected) }
    }

    private fun preferredDetectedProfile(detected: List<DetectedProfile>): DetectedProfile? {
        val workspaceService = DamlWorkspaceService.getInstance(project)
        val preferredWorkspace = workspaceService.defaultPackageWorkspace()
            ?: workspaceService.defaultWorkspace()
            ?: workspaceService.projectRoot()
        return detected.minWithOrNull(
            compareBy<DetectedProfile>(
                { if (preferredWorkspace != null && it.workspace == preferredWorkspace) 0 else 1 },
                { detectionPriority(it.path) },
                { it.path.toString() }
            )
        )
    }

    private fun detectedProfileKey(detectedProfile: DetectedProfile): String =
        SandboxPaths.generatedRoot(
            detectedProfile.profile,
            detectedProfile.workspace ?: DamlWorkspaceService.getInstance(project).projectRoot()
        ).toString()

    private fun inferredWorkspace(path: Path): Path? =
        when {
            path.name == DETECTED_PROFILE_NAME -> path.parent
            path.name == "profile.json" && path.parent?.parent?.name == SandboxDefaults.GENERATED_DIR -> path.parent.parent.parent
            else -> null
        }

    private fun detectionPriority(path: Path): Int =
        if (path.name == DETECTED_PROFILE_NAME) 0 else 1

    private fun isDetectedProfilePath(path: String): Boolean =
        try {
            val profilePath = Path.of(path)
            profilePath.name == DETECTED_PROFILE_NAME ||
                (profilePath.name == "profile.json" && profilePath.parent?.parent?.name == SandboxDefaults.GENERATED_DIR)
        } catch (_: InvalidPathException) {
            false
        }

    private fun isDefaultManagedProfile(profile: SandboxProfile): Boolean =
        profile.name == "Managed Canton Sandbox" &&
            profile.participants.size == 1 &&
            profile.synchronizers.size == 1 &&
            profile.darAssignments.isEmpty()

    private fun normalizeProfile(profile: SandboxProfile) {
        if (profile.id.isBlank()) profile.id = SandboxDefaults.newProfile(null).id
        if (profile.name.isBlank()) profile.name = "Managed Canton Sandbox"
        if (profile.portBase <= 0) profile.portBase = SandboxDefaults.PORT_BASE
        if (profile.participants.isEmpty()) profile.participants.add(SandboxDefaults.participant(1, profile.portBase))
        if (profile.synchronizers.isEmpty()) profile.synchronizers.add(SandboxDefaults.sharedSynchronizer(profile.portBase))
        SandboxDefaults.ensureSharedSynchronizer(profile)
        if (profile.bindings.isEmpty()) {
            profile.participants.forEach { participant ->
                profile.synchronizers.forEach { synchronizer ->
                    profile.bindings.add(ParticipantSyncBinding(participant.id, synchronizer.id, true))
                }
            }
        }
        val projectRoot = DamlWorkspaceService.getInstance(project).projectRoot()
        val workspace = SandboxPaths.workspaceRoot(profile, projectRoot)
        if (workspace != null) {
            profile.workspacePath = if (projectRoot != null) SandboxPaths.relativePath(projectRoot, workspace) else "."
        } else {
            profile.workspacePath = ""
        }
        val generated = if (profile.generatedPath.isBlank()) {
            workspace?.resolve(SandboxDefaults.GENERATED_DIR)?.resolve(profile.id)
        } else {
            SandboxPaths.generatedRoot(profile, projectRoot)
        }
        if (generated != null && workspace != null) {
            profile.generatedPath = SandboxPaths.relativePath(workspace, generated)
        } else if (profile.generatedPath.isBlank()) {
            profile.generatedPath = ""
        } else {
            profile.generatedPath = SandboxPaths.invariantSeparators(java.nio.file.Path.of(profile.generatedPath).normalize().toString())
        }
        profile.darAssignments.forEach { assignment ->
            assignment.darPath = SandboxPaths.relativeProfilePath(assignment.darPath, profile, projectRoot)
        }
        normalizeDarAssignments(profile)
        val participantIds = profile.participants.map { it.id }.toSet()
        val synchronizerIds = profile.synchronizers.map { it.id }.toSet()
        val topologyNodeIds = participantIds +
            profile.synchronizers.map { it.id } +
            profile.synchronizers.map { it.sequencer.id } +
            profile.synchronizers.map { it.mediator.id }
        profile.bindings.removeIf { it.participantId !in participantIds || it.synchronizerId !in synchronizerIds }
        profile.darAssignments.forEach { it.participantIds.removeIf { id -> id !in participantIds } }
        normalizeDarAssignments(profile)
        profile.partyAllocations.removeIf { it.participantId !in participantIds || it.synchronizerId !in synchronizerIds }
        profile.topologyPositions.removeIf { it.nodeId !in topologyNodeIds || it.x < 0 || it.y < 0 }
    }

    private data class DetectedProfile(
        val path: Path,
        val workspace: Path?,
        val profile: SandboxProfile
    )

    companion object {
        private const val DETECTED_PROFILE_NAME = "managed-sandbox-profile.json"

        fun getInstance(project: Project): SandboxProfileService = project.service()
    }
}
