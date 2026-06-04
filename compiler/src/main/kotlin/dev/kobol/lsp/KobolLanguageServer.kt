package dev.kobol.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

// ─────────────────────────────────────────────────────────────────────────────
//  Entry point — called with  kobolc --lsp
// ─────────────────────────────────────────────────────────────────────────────

fun startLspServer() {
    val server   = KobolLanguageServer()
    val launcher = Launcher.createLauncher(
        server,
        LanguageClient::class.java,
        System.`in`,
        System.out,
        Executors.newCachedThreadPool(),
        null,
    )
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()   // blocks until the connection closes
}

// ─────────────────────────────────────────────────────────────────────────────
//  Language server
// ─────────────────────────────────────────────────────────────────────────────

class KobolLanguageServer : LanguageServer, LanguageClientAware {

    internal lateinit var client: LanguageClient
    val textDocSvc   = KobolTextDocumentService(this)
    val workspaceSvc = KobolWorkspaceService(textDocSvc)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val cap = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Full)

            // Existing capabilities
            hoverProvider      = Either.forLeft(true)
            definitionProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            completionProvider = CompletionOptions(true, listOf(".", " ", "\""))
            renameProvider     = Either.forLeft(true)

            // New: outline, signature help, folding, code actions, inlay hints,
            //      workspace symbol search, document formatting
            documentSymbolProvider = Either.forLeft(true)
            workspaceSymbolProvider = Either.forLeft(true)
            signatureHelpProvider = SignatureHelpOptions(listOf(",", " "), emptyList())
            foldingRangeProvider = Either.forLeft(true)
            codeActionProvider = Either.forLeft(true)
            inlayHintProvider = Either.forLeft(true)
            documentFormattingProvider = Either.forLeft(true)
        }
        val info = ServerInfo("kobolc", dev.kobol.VERSION)
        return CompletableFuture.completedFuture(InitializeResult(cap, info))
    }

    override fun initialized(params: InitializedParams) { /* no-op */ }

    override fun shutdown(): CompletableFuture<Any?> =
        CompletableFuture.completedFuture(null)

    override fun exit() = System.exit(0)

    override fun getTextDocumentService(): TextDocumentService = textDocSvc
    override fun getWorkspaceService(): WorkspaceService       = workspaceSvc

    override fun connect(client: LanguageClient) { this.client = client }
}
