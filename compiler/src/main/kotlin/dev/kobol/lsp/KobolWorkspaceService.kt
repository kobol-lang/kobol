package dev.kobol.lsp

import dev.kobol.semantic.Symbol
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class KobolWorkspaceService(private val textDocSvc: KobolTextDocumentService) : WorkspaceService {

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) { /* no-op */ }
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams)   { /* no-op */ }

    override fun symbol(
        params: WorkspaceSymbolParams,
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val query = params.query.uppercase()
        val results = mutableListOf<WorkspaceSymbol>()

        for ((uri, state) in textDocSvc.docs) {
            val checker = state.checker ?: continue
            for (name in checker.symbols.allVisibleNames()) {
                if (query.isNotEmpty() && !name.contains(query)) continue
                val sym = checker.symbols.resolve(name) ?: continue
                val kind = sym.toSymbolKind()
                val defLine = (sym.pos.line - 1).coerceAtLeast(0)
                val defCol  = (sym.pos.column - 1).coerceAtLeast(0)
                val range = Range(Position(defLine, defCol), Position(defLine, defCol + name.length))
                val loc: Either<Location, WorkspaceSymbolLocation> =
                    Either.forLeft(Location(uri, range))
                results += WorkspaceSymbol(name, kind, loc)
            }
        }

        return CompletableFuture.completedFuture(Either.forRight(results))
    }

    private fun Symbol.toSymbolKind(): SymbolKind = when (this) {
        is Symbol.ProcedureSymbol -> if (isAsync) SymbolKind.Event else SymbolKind.Function
        is Symbol.RecordSymbol    -> SymbolKind.Struct
        is Symbol.VariantSymbol   -> SymbolKind.Enum
        is Symbol.Variable        -> SymbolKind.Variable
        is Symbol.Constant        -> SymbolKind.Constant
        is Symbol.NamedCondition  -> SymbolKind.Boolean
    }
}
