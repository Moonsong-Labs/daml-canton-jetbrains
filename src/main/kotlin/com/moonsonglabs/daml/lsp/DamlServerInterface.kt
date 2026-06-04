package com.moonsonglabs.daml.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Custom DAML LSP surface: extends the standard [LanguageServer] with the non-standard
 * methods the daml damlc ide server understands.
 *
 * Why: DAML's LSP server expects a periodic `daml/keepAlive` ping from the client and will
 * be considered hung if it stops responding. The VSCode extension implements a watchdog
 * around this. We expose the request here so [DamlLanguageClient] can invoke it.
 */
interface DamlServerInterface : LanguageServer {

    @JsonRequest("daml/keepAlive")
    fun keepAlive(): CompletableFuture<Void?>

    @JsonNotification("daml/sdkInstall/cancel")
    fun cancelSdkInstall(payload: Map<String, Any?>)
}
