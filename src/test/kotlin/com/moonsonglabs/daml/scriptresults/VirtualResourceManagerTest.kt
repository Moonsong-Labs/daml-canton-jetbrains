package com.moonsonglabs.daml.scriptresults

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.lsp.DamlServerInterface
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Files

class VirtualResourceManagerTest : BasePlatformTestCase() {
    fun `test script opens only on its package server and closes there when switching packages`() {
        val directory = Files.createTempDirectory("daml-resource-routing")
        Disposer.register(testRootDisposable) { FileUtil.delete(directory.toFile()) }
        fun source(path: String): VirtualFile {
            val file = directory.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, "module Main where")
            return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        }
        val testFile = source("tests/pool swap/daml/Test.daml")
        val exampleFile = source("examples/pool/daml/Pool.daml")
        val grantFile = source("packages/grant/daml/Grant.daml")
        val notifications = mutableListOf<String>()
        val servers = mutableListOf(
            server("test", testFile, notifications),
            server("example", exampleFile, notifications),
            server("grant", grantFile, notifications)
        )
        val serverManager = proxy(LspServerManager::class.java) { method, _ ->
            if (method == "getServersForProvider") servers else null
        }
        // 2026.2 keeps LspServerManager as a facade but registers the service as LspClientManager.
        // Resolve that service key without making this test depend on a post-261 API.
        @Suppress("UNCHECKED_CAST")
        val serviceType = (LspServerManager::class.java.interfaces
            .firstOrNull { it.simpleName == "LspClientManager" }
            ?: LspServerManager::class.java) as Class<Any>
        project.replaceService(serviceType, serverManager, testRootDisposable)
        val manager = VirtualResourceManager.getInstance(project)
        val open = VirtualResourceManager::class.java.getDeclaredMethod("openVirtualResource", String::class.java)
            .apply { isAccessible = true }
        val testUri = DamlScriptResource.uri(testFile.path, "testAtomicPreparationAndOneShotExecution")
        val exampleUri = DamlScriptResource.uri(exampleFile.path, "testExample")

        open.invoke(manager, testUri)
        assertEquals(listOf("test open $testUri"), notifications)
        // A second click must send a fresh subscription, including after a server restart.
        notifications.clear()
        open.invoke(manager, testUri)
        assertEquals(listOf("test close $testUri", "test open $testUri"), notifications)
        notifications.clear()
        servers[0] = server("restarted-test", testFile, notifications)
        open.invoke(manager, testUri)
        assertEquals(listOf("restarted-test close $testUri", "restarted-test open $testUri"), notifications)
        notifications.clear()
        open.invoke(manager, exampleUri)
        assertEquals(listOf("restarted-test close $testUri", "example open $exampleUri"), notifications)
    }

    private fun server(name: String, source: VirtualFile, notifications: MutableList<String>): LspServer {
        val descriptor = object : LspServerDescriptor(project, name) {
            override fun isSupportedFile(file: VirtualFile): Boolean = file.parent == source.parent
        }
        val documents = proxy(TextDocumentService::class.java) { method, args ->
            when (method) {
                "didOpen" -> notifications.add("$name open ${(args[0] as DidOpenTextDocumentParams).textDocument.uri}")
                "didClose" -> notifications.add("$name close ${(args[0] as DidCloseTextDocumentParams).textDocument.uri}")
            }
            null
        }
        val backend = proxy(DamlServerInterface::class.java) { method, _ ->
            if (method == "getTextDocumentService") documents else null
        }
        return proxy(LspServer::class.java) { method, args ->
            when (method) {
                "getDescriptor" -> descriptor
                "sendNotification" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as (LanguageServer) -> Unit)(backend)
                    null
                }
                else -> null
            }
        }
    }

    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        })
}
