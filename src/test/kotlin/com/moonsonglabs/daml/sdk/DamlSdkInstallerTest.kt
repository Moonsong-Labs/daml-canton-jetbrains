package com.moonsonglabs.daml.sdk

import com.intellij.execution.process.ProcessOutput
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DamlSdkInstallerTest {
    @Test
    fun `reports the real compiler startup error instead of installation success`() {
        val output = ProcessOutput().apply {
            exitCode = 1
            appendStderr(CPU_ERROR)
        }
        try {
            DamlSdkInstaller.requireSuccess(output, OPERATION)
            fail("An unusable compiler must not be reported as installed and verified")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains(CPU_ERROR))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `a timeout with zero exit status is still a failure`() {
        DamlSdkInstaller.requireSuccess(ProcessOutput().apply { exitCode = 0; setTimeout() }, OPERATION)
    }

    @Test(expected = IllegalStateException::class)
    fun `a cancelled process cannot report success`() {
        DamlSdkInstaller.requireSuccess(ProcessOutput().apply { exitCode = 0; setCancelled() }, OPERATION)
    }

    companion object {
        private const val CPU_ERROR = "bad CPU type in executable"
        private const val OPERATION = "Starting compiler"
    }
}
