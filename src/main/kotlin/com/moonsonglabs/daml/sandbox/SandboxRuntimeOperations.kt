package com.moonsonglabs.daml.sandbox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key

/** Process and probe boundary used by production and deterministic lifecycle tests. */
internal interface SandboxManagedProcess {
    val isTerminated: Boolean
    fun start(onText: (String) -> Unit, onExit: (Int) -> Unit)
    fun terminate()
    fun waitFor(timeoutMs: Long): Boolean
}

internal class CantonManagedProcess(command: GeneralCommandLine) : SandboxManagedProcess {
    private val handler = OSProcessHandler(command)
    override val isTerminated get() = handler.isProcessTerminated
    override fun start(onText: (String) -> Unit, onExit: (Int) -> Unit) {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = onText(event.text)
            override fun processTerminated(event: ProcessEvent) = onExit(event.exitCode)
        })
        handler.startNotify()
    }
    override fun terminate() = handler.destroyProcess()
    override fun waitFor(timeoutMs: Long) = handler.waitFor(timeoutMs)
}

internal data class SandboxPreparedLaunch(
    val profile: SandboxProfile,
    val generated: SandboxGeneratedFiles,
    val command: GeneralCommandLine,
    val version: String
)

internal data class SandboxRuntimeOperations(
    val prepare: (SandboxProfile, () -> Boolean) -> SandboxPreparedLaunch?,
    val launch: (GeneralCommandLine) -> SandboxManagedProcess,
    val probe: (SandboxProfile, () -> Boolean) -> List<HealthSnapshot>
)
