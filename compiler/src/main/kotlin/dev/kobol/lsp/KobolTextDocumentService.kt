package dev.kobol.lsp

import dev.kobol.lexer.LexException
import dev.kobol.lexer.Lexer
import dev.kobol.lexer.TokenType
import dev.kobol.parser.ParseException
import dev.kobol.parser.Parser
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI
import java.io.File
import java.util.concurrent.CompletableFuture

// ─────────────────────────────────────────────────────────────────────────────
//  Per-document state kept after each successful (or partially-successful) parse
// ─────────────────────────────────────────────────────────────────────────────

internal data class DocumentState(
    val program: Program?,
    val checker: TypeChecker?,
    val lines:   List<String>,
)

class KobolTextDocumentService(private val server: KobolLanguageServer) : TextDocumentService {

    // uri → last good parse — also used by KobolWorkspaceService
    internal val docs = mutableMapOf<String, DocumentState>()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        analyseAndPublish(doc.uri, doc.text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val doc = params.textDocument
        val text = params.contentChanges.lastOrNull()?.text ?: return
        analyseAndPublish(doc.uri, text)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        docs.remove(uri)
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) { /* full sync — nothing extra */ }

    // ── Hover ──────────────────────────────────────────────────────────────

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(null)
        val (program, checker) = state
        if (program == null || checker == null) return CompletableFuture.completedFuture(null)

        val pos   = params.position
        val token = tokenAtPosition(state.lines, pos.line + 1, pos.character + 1)
            ?: return CompletableFuture.completedFuture(null)

        val sym = checker.symbols.resolve(token)
        val typeStr = when (sym) {
            is Symbol.Variable        -> "**${sym.name}** : ${sym.type}"
            is Symbol.Constant        -> "**const** ${sym.name} : ${sym.type} = ${sym.value}"
            is Symbol.ProcedureSymbol -> {
                val ps = sym.params.joinToString(", ") { "${it.name}: ${it.type}" }
                val ret = sym.returnType?.let { " → $it" } ?: ""
                "**PROCEDURE** ${token}($ps)$ret"
            }
            is Symbol.RecordSymbol -> {
                val fields = sym.fields.entries.joinToString("\n  ") { (k, v) -> "$k : $v" }
                "**RECORD** $token\n  $fields"
            }
            is Symbol.VariantSymbol -> {
                val cases = sym.cases.joinToString(" | ") { it.name }
                "**VARIANT** $token: $cases"
            }
            is Symbol.NamedCondition  -> "**CONDITION** $token: BOOLEAN"
            null                      -> return CompletableFuture.completedFuture(null)
        }

        val hover = Hover(MarkupContent(MarkupKind.MARKDOWN, typeStr))
        return CompletableFuture.completedFuture(hover)
    }

    // ── Go-to-definition ───────────────────────────────────────────────────

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val state = docs[params.textDocument.uri] ?: return emptyEither()
        val (program, checker) = state
        if (program == null || checker == null) return emptyEither()

        val pos   = params.position
        val token = tokenAtPosition(state.lines, pos.line + 1, pos.character + 1) ?: return emptyEither()
        val sym   = checker.symbols.resolve(token) ?: return emptyEither()

        val defPos  = sym.pos
        val defLine = defPos.line - 1
        val defCol  = defPos.column - 1
        val range   = Range(Position(defLine, defCol), Position(defLine, defCol + token.length))
        val location = Location(params.textDocument.uri, range)
        return CompletableFuture.completedFuture(Either.forLeft(listOf(location)))
    }

    // ── References ─────────────────────────────────────────────────────────

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(emptyList())
        val token = tokenAtPosition(state.lines, params.position.line + 1, params.position.character + 1)
            ?: return CompletableFuture.completedFuture(emptyList())
        val locations = findWordOccurrences(state.lines, token)
            .map { Location(params.textDocument.uri, it) }
        return CompletableFuture.completedFuture(locations)
    }

    // ── Document Highlight ───────────────────────────────────────────────────

    override fun documentHighlight(
        params: DocumentHighlightParams,
    ): CompletableFuture<List<DocumentHighlight>> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(emptyList())
        val token = tokenAtPosition(state.lines, params.position.line + 1, params.position.character + 1)
            ?: return CompletableFuture.completedFuture(emptyList())
        val highlights = findWordOccurrences(state.lines, token)
            .map { DocumentHighlight(it, DocumentHighlightKind.Text) }
        return CompletableFuture.completedFuture(highlights)
    }

    // ── Code Lens (inline Run / Run Tests) ────────────────────────────────────

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        val program = docs[params.textDocument.uri]?.program
            ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.completedFuture(buildCodeLenses(program))
    }

    /**
     * Build inline action lenses: a "▶ Run" lens over the `Main` procedure and a
     * "▶ Run Tests" lens over every TEST / TEST TABLE block. The commands are the
     * client-side `kobol.runFile` / `kobol.test` commands (no args — they act on
     * the active editor). Pure for unit-testing.
     */
    internal fun buildCodeLenses(program: Program): List<CodeLens> {
        val lenses = mutableListOf<CodeLens>()

        program.procedures
            .filter { it.name.equals("Main", ignoreCase = true) }
            .forEach { proc ->
                lenses += CodeLens(
                    declRange(proc.pos),
                    Command("▶ Run", "kobol.runFile"),
                    null,
                )
            }

        val testPositions = program.tests.map { it.pos } + program.tableTests.map { it.pos }
        for (pos in testPositions) {
            lenses += CodeLens(declRange(pos), Command("▶ Run Tests", "kobol.test"), null)
        }

        return lenses
    }

    /** Zero-width range at a declaration's 0-based start position. */
    private fun declRange(pos: dev.kobol.lexer.SourcePosition): Range {
        val line = (pos.line - 1).coerceAtLeast(0)
        val col  = (pos.column - 1).coerceAtLeast(0)
        return Range(Position(line, col), Position(line, col))
    }

    // ── Call Hierarchy ─────────────────────────────────────────────────────

    override fun prepareCallHierarchy(
        params: CallHierarchyPrepareParams,
    ): CompletableFuture<List<CallHierarchyItem>> {
        val state   = docs[params.textDocument.uri]
        val program = state?.program ?: return CompletableFuture.completedFuture(emptyList())
        val lines   = state.lines

        // Either the procedure named at the cursor, or the procedure enclosing it.
        val token = tokenAtPosition(lines, params.position.line + 1, params.position.character + 1)
        val byName = program.procedures.firstOrNull { it.name.equals(token, ignoreCase = true) }
        val proc = byName ?: enclosingProcedure(program, lines, params.position.line)
            ?: return CompletableFuture.completedFuture(emptyList())

        return CompletableFuture.completedFuture(listOf(procItem(params.textDocument.uri, proc, lines)))
    }

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams,
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> {
        val uri     = params.item.uri
        val state   = docs[uri]
        val program = state?.program ?: return CompletableFuture.completedFuture(emptyList())
        val lines   = state.lines
        val proc = program.procedures.firstOrNull { it.name.equals(params.item.name, ignoreCase = true) }
            ?: return CompletableFuture.completedFuture(emptyList())

        val start = (proc.pos.line - 1).coerceAtLeast(0)
        val end   = findEndKeyword(lines, start, "END-PROCEDURE") ?: lines.lastIndex
        // callee name (upper) → all call-site ranges within this procedure
        val byCallee = procedureCallSites(lines, start, end).groupBy({ it.first }, { it.second })

        val calls = byCallee.mapNotNull { (callee, ranges) ->
            val target = program.procedures.firstOrNull { it.name.equals(callee, ignoreCase = true) }
                ?: return@mapNotNull null
            CallHierarchyOutgoingCall(procItem(uri, target, lines), ranges)
        }
        return CompletableFuture.completedFuture(calls)
    }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams,
    ): CompletableFuture<List<CallHierarchyIncomingCall>> {
        val uri     = params.item.uri
        val state   = docs[uri]
        val program = state?.program ?: return CompletableFuture.completedFuture(emptyList())
        val lines   = state.lines
        val targetName = params.item.name.uppercase()

        val calls = program.procedures.mapNotNull { caller ->
            val start = (caller.pos.line - 1).coerceAtLeast(0)
            val end   = findEndKeyword(lines, start, "END-PROCEDURE") ?: lines.lastIndex
            val hits  = procedureCallSites(lines, start, end).filter { it.first == targetName }.map { it.second }
            if (hits.isEmpty()) null
            else CallHierarchyIncomingCall(procItem(uri, caller, lines), hits)
        }
        return CompletableFuture.completedFuture(calls)
    }

    /** Build a CallHierarchyItem for a procedure declaration. */
    private fun procItem(uri: String, proc: ProcedureDecl, lines: List<String>): CallHierarchyItem {
        val start   = (proc.pos.line - 1).coerceAtLeast(0)
        val col     = (proc.pos.column - 1).coerceAtLeast(0)
        val end     = findEndKeyword(lines, start, "END-PROCEDURE") ?: start
        val item = CallHierarchyItem()
        item.name = proc.name
        item.kind = if (proc.isAsync) SymbolKind.Event else SymbolKind.Function
        item.uri  = uri
        item.range = Range(Position(start, col), Position(end, 0))
        item.selectionRange = Range(Position(start, col), Position(start, col + proc.name.length))
        return item
    }

    /** The procedure whose body region contains the 0-based [line], or null. */
    private fun enclosingProcedure(program: Program, lines: List<String>, line: Int): ProcedureDecl? =
        program.procedures.firstOrNull { proc ->
            val start = (proc.pos.line - 1).coerceAtLeast(0)
            val end   = findEndKeyword(lines, start, "END-PROCEDURE") ?: lines.lastIndex
            line in start..end
        }

    /**
     * All `PERFORM`/`DO` call sites in lines [startLine0]..[endLine0], as
     * (calleeNameUpper, nameRange) pairs. Textual scan — module-qualified calls
     * (`PERFORM Alias.Proc`) contribute the bare procedure name. Pure for testing.
     */
    internal fun procedureCallSites(
        lines: List<String>,
        startLine0: Int,
        endLine0: Int,
    ): List<Pair<String, Range>> {
        val rx = Regex("\\b(?:PERFORM|DO)\\s+([A-Za-z][\\w-]*(?:\\.[A-Za-z][\\w-]*)?)")
        val out = mutableListOf<Pair<String, Range>>()
        for (i in startLine0..endLine0.coerceAtMost(lines.lastIndex)) {
            for (m in rx.findAll(lines[i])) {
                val raw    = m.groupValues[1]
                val dotIdx = raw.lastIndexOf('.')
                val callee = if (dotIdx >= 0) raw.substring(dotIdx + 1) else raw
                val nameStart = m.groups[1]!!.range.first + (if (dotIdx >= 0) dotIdx + 1 else 0)
                out += callee.uppercase() to Range(Position(i, nameStart), Position(i, nameStart + callee.length))
            }
        }
        return out
    }

    // ── Completion ─────────────────────────────────────────────────────────

    override fun completion(
        params: CompletionParams,
    ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val state  = docs[params.textDocument.uri]
        val items  = mutableListOf<CompletionItem>()
        val pos    = params.position
        val lines  = state?.lines ?: emptyList()
        val lineText = lines.getOrNull(pos.line) ?: ""
        val prefix = lineText.substring(0, minOf(pos.character, lineText.length)).trimStart().uppercase()

        // Context-aware completions
        when {
            // After PERFORM <name> USING — suggest variable names
            prefix.contains(Regex("PERFORM\\s+\\w+\\s+USING\\s*")) -> {
                addSymbolCompletions(state, items, CompletionItemKind.Variable)
            }
            // After MOVE — suggest variables
            prefix.startsWith("MOVE") || prefix.startsWith("ADD") || prefix.startsWith("COMPUTE") -> {
                addSymbolCompletions(state, items, CompletionItemKind.Variable)
                addKeywordCompletions(items, listOf("TO", "INTO", "GIVING", "FROM"))
            }
            // After FOR EACH — suggest list variables
            prefix.contains("FOR EACH") -> {
                addKeywordCompletions(items, listOf("IN"))
                addSymbolCompletions(state, items, CompletionItemKind.Variable)
            }
            // Default: all keywords + visible symbols
            else -> {
                KOBOL_KEYWORDS.forEach { kw ->
                    val item = CompletionItem(kw).also {
                        it.kind = CompletionItemKind.Keyword
                        it.detail = KEYWORD_DOC[kw]
                    }
                    items += item
                }
                // Procedure completions with parameter hints
                state?.checker?.symbols?.allVisibleNames()?.forEach { name ->
                    val sym = state.checker.symbols.resolve(name)
                    val item = when (sym) {
                        is Symbol.ProcedureSymbol -> CompletionItem(name).also {
                            it.kind = CompletionItemKind.Function
                            val ps = sym.params.joinToString(", ") { p -> "${p.name}: ${p.type}" }
                            it.detail = "($ps)${sym.returnType?.let { t -> " → $t" } ?: ""}"
                            it.documentation = Either.forLeft("PROCEDURE $name")
                        }
                        is Symbol.RecordSymbol -> CompletionItem(name).also {
                            it.kind = CompletionItemKind.Struct
                            it.detail = "RECORD"
                        }
                        is Symbol.VariantSymbol -> CompletionItem(name).also {
                            it.kind = CompletionItemKind.Enum
                            it.detail = "VARIANT"
                        }
                        else -> CompletionItem(name).also {
                            it.kind = CompletionItemKind.Variable
                            if (sym is Symbol.Variable) it.detail = sym.type.toString()
                        }
                    }
                    items += item
                }
            }
        }

        return CompletableFuture.completedFuture(Either.forLeft(items))
    }

    private fun addSymbolCompletions(
        state: DocumentState?,
        items: MutableList<CompletionItem>,
        kind: CompletionItemKind,
    ) {
        state?.checker?.symbols?.allVisibleNames()?.forEach { name ->
            items += CompletionItem(name).also { it.kind = kind }
        }
    }

    private fun addKeywordCompletions(items: MutableList<CompletionItem>, kws: List<String>) {
        kws.forEach { kw -> items += CompletionItem(kw).also { it.kind = CompletionItemKind.Keyword } }
    }

    // ── Signature Help ─────────────────────────────────────────────────────

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(null)
        val checker = state.checker ?: return CompletableFuture.completedFuture(null)
        val lines = state.lines
        val pos = params.position

        val lineText = lines.getOrNull(pos.line) ?: return CompletableFuture.completedFuture(null)
        val textBefore = lineText.substring(0, minOf(pos.character, lineText.length)).uppercase()

        // Match: PERFORM ProcName USING ...
        val match = Regex("PERFORM\\s+([\\w-]+)\\s+USING").find(textBefore)
            ?: return CompletableFuture.completedFuture(null)

        val procName = match.groupValues[1]
        val proc = checker.symbols.resolve(procName) as? Symbol.ProcedureSymbol
            ?: return CompletableFuture.completedFuture(null)

        // Count commas after USING to determine active parameter
        val afterUsing = textBefore.substringAfterLast("USING", "")
        val activeParam = afterUsing.count { it == ',' }

        val paramLabels = proc.params.map { p ->
            ParameterInformation("${p.name} : ${p.type}")
        }
        val label = "${proc.name} USING ${proc.params.joinToString(", ") { "${it.name} : ${it.type}" }}" +
            (proc.returnType?.let { " RETURNING $it" } ?: "")
        val sig = SignatureInformation(label, "", paramLabels)
        val active = activeParam.coerceAtMost(proc.params.size - 1)

        return CompletableFuture.completedFuture(SignatureHelp(listOf(sig), 0, active))
    }

    // ── Document Symbols (Outline) ─────────────────────────────────────────

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(emptyList())
        val program = state.program ?: return CompletableFuture.completedFuture(emptyList())
        val lines = state.lines
        val lastLine = lines.size - 1

        val symbols = mutableListOf<DocumentSymbol>()

        // Procedures
        for (proc in program.procedures) {
            val startLine = (proc.pos.line - 1).coerceAtLeast(0)
            val startCol  = (proc.pos.column - 1).coerceAtLeast(0)
            val endLine   = findEndKeyword(lines, startLine, "END-PROCEDURE") ?: lastLine
            val paramStr  = proc.params.joinToString(", ") { "${it.name}: ${it.type}" }
            val detail    = if (proc.params.isEmpty()) "" else "($paramStr)"
            val sym = DocumentSymbol(
                proc.name,
                if (proc.isAsync) SymbolKind.Event else SymbolKind.Function,
                Range(Position(startLine, startCol), Position(endLine, 0)),
                Range(Position(startLine, startCol), Position(startLine, startCol + proc.name.length)),
                detail,
            )
            symbols += sym
        }

        // Records
        for (rec in program.records) {
            val startLine = (rec.pos.line - 1).coerceAtLeast(0)
            val startCol  = (rec.pos.column - 1).coerceAtLeast(0)
            val endLine   = findEndKeyword(lines, startLine, "END-RECORD") ?: lastLine
            val sym = DocumentSymbol(
                rec.name, SymbolKind.Struct,
                Range(Position(startLine, startCol), Position(endLine, 0)),
                Range(Position(startLine, startCol), Position(startLine, startCol + rec.name.length)),
            )
            sym.children = rec.fields.map { field ->
                val fl = (field.pos.line - 1).coerceAtLeast(0)
                val fc = (field.pos.column - 1).coerceAtLeast(0)
                DocumentSymbol(
                    field.name, SymbolKind.Field,
                    Range(Position(fl, fc), Position(fl, Int.MAX_VALUE)),
                    Range(Position(fl, fc), Position(fl, fc + field.name.length)),
                    field.type.toString(),
                )
            }
            symbols += sym
        }

        // Variants
        for (variant in program.variants) {
            val startLine = (variant.pos.line - 1).coerceAtLeast(0)
            val startCol  = (variant.pos.column - 1).coerceAtLeast(0)
            val endLine   = findEndKeyword(lines, startLine, "END-VARIANT") ?: lastLine
            val sym = DocumentSymbol(
                variant.name, SymbolKind.Enum,
                Range(Position(startLine, startCol), Position(endLine, 0)),
                Range(Position(startLine, startCol), Position(startLine, startCol + variant.name.length)),
            )
            sym.children = variant.cases.map { case ->
                val cl = (case.pos.line - 1).coerceAtLeast(0)
                val cc = (case.pos.column - 1).coerceAtLeast(0)
                DocumentSymbol(
                    case.name, SymbolKind.EnumMember,
                    Range(Position(cl, cc), Position(cl, Int.MAX_VALUE)),
                    Range(Position(cl, cc), Position(cl, cc + case.name.length)),
                )
            }
            symbols += sym
        }

        // Type aliases
        for (alias in program.typeAliases) {
            val line = (alias.pos.line - 1).coerceAtLeast(0)
            val col  = (alias.pos.column - 1).coerceAtLeast(0)
            symbols += DocumentSymbol(
                alias.name, SymbolKind.TypeParameter,
                Range(Position(line, col), Position(line, Int.MAX_VALUE)),
                Range(Position(line, col), Position(line, col + alias.name.length)),
                alias.target.toString(),
            )
        }

        // Data section items (global variables)
        program.dataSection?.items?.forEach { item ->
            val line = (item.pos.line - 1).coerceAtLeast(0)
            val col  = (item.pos.column - 1).coerceAtLeast(0)
            symbols += DocumentSymbol(
                item.name, SymbolKind.Variable,
                Range(Position(line, col), Position(line, Int.MAX_VALUE)),
                Range(Position(line, col), Position(line, col + item.name.length)),
                item.type?.toString() ?: "",
            )
        }

        // Named conditions
        for (cond in program.namedConditions) {
            val line = (cond.pos.line - 1).coerceAtLeast(0)
            val col  = (cond.pos.column - 1).coerceAtLeast(0)
            symbols += DocumentSymbol(
                cond.name, SymbolKind.Boolean,
                Range(Position(line, col), Position(line, Int.MAX_VALUE)),
                Range(Position(line, col), Position(line, col + cond.name.length)),
                "CONDITION",
            )
        }

        return CompletableFuture.completedFuture(symbols.map { Either.forRight(it) })
    }

    // ── Folding Ranges ─────────────────────────────────────────────────────

    override fun foldingRange(
        params: FoldingRangeRequestParams,
    ): CompletableFuture<List<FoldingRange>> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(emptyList())
        val lines = state.lines
        val ranges = mutableListOf<FoldingRange>()

        // Stack-based matching of BEGIN/END pairs
        val stack = ArrayDeque<Pair<String, Int>>()

        val endToStart = mapOf(
            "END-PROCEDURE"  to "PROCEDURE",
            "END-IF"         to "IF",
            "END-FOR"        to "FOR",
            "END-WHILE"      to "WHILE",
            "END-REPEAT"     to "REPEAT",
            "END-TRY"        to "TRY",
            "END-MATCH"      to "MATCH",
            "END-RECORD"     to "RECORD",
            "END-VARIANT"    to "VARIANT",
            "END-MODULE"     to "MODULE",
            "END-TEST"       to "TEST",
            "END-SERVER"     to "SERVER",
            "END-CONCURRENT" to "CONCURRENT",
            "END-PRECISION"  to "WITH",
            "END-VALIDATE"   to "VALIDATE",
            "END-CONFIG"     to "CONFIG",
        )

        val startKeywords = setOf(
            "PROCEDURE", "IF", "FOR", "WHILE", "REPEAT", "TRY",
            "MATCH", "RECORD", "VARIANT", "MODULE", "TEST", "SERVER",
            "CONCURRENT", "WITH", "VALIDATE", "CONFIG",
        )

        lines.forEachIndexed { idx, line ->
            val trimmed = line.trimStart().uppercase()

            // Check END-* first
            val endKw = endToStart.keys.firstOrNull { trimmed.startsWith(it) }
            if (endKw != null) {
                val startKw = endToStart[endKw]!!
                val stackIdx = stack.indexOfLast { it.first == startKw }
                if (stackIdx >= 0) {
                    val (_, startLine) = stack[stackIdx]
                    stack.removeAt(stackIdx)
                    if (idx > startLine) {
                        val fr = FoldingRange(startLine, idx)
                        fr.kind = FoldingRangeKind.Region
                        ranges += fr
                    }
                }
                return@forEachIndexed
            }

            // Check start keywords
            for (kw in startKeywords) {
                val matches = when (kw) {
                    "IF"   -> trimmed.startsWith("IF ") || trimmed.startsWith("IF:")
                    "FOR"  -> trimmed.startsWith("FOR EACH") || trimmed.startsWith("FOR ")
                    "WITH" -> trimmed.startsWith("WITH PRECISION")
                    "TEST" -> trimmed.startsWith("TEST ") || trimmed.startsWith("TEST TABLE")
                    else   -> trimmed.startsWith("$kw ") || trimmed.startsWith("$kw:")
                }
                if (matches) {
                    stack.addLast(kw to idx)
                    break
                }
            }

            // Line comments folding (-- blocks)
            if (trimmed.startsWith("--") && idx > 0 && !lines[idx - 1].trimStart().startsWith("--")) {
                // Start of a comment block — handled separately if needed
            }
        }

        // DATA: section — fold to first PROCEDURE
        val dataLine  = lines.indexOfFirst { it.trimStart().uppercase().startsWith("DATA:") }
        val procLine  = lines.indexOfFirst { it.trimStart().uppercase().startsWith("PROCEDURE ") }
        if (dataLine >= 0 && procLine > dataLine) {
            val fr = FoldingRange(dataLine, procLine - 1)
            fr.kind = FoldingRangeKind.Region
            ranges += fr
        }

        return CompletableFuture.completedFuture(ranges)
    }

    // ── Code Actions (Quick Fixes) ─────────────────────────────────────────

    override fun codeAction(
        params: CodeActionParams,
    ): CompletableFuture<List<Either<Command, CodeAction>>> {
        val uri     = params.textDocument.uri
        val actions = mutableListOf<Either<Command, CodeAction>>()

        for (diag in params.context.diagnostics) {
            val code = diag.code?.get()?.toString() ?: ""

            // General: ANY diagnostic carrying a "did you mean: X" suggestion gets
            // a one-click replacement — covers undefined variables (E001),
            // unknown types, fields, modules, etc. without a per-code branch.
            extractSuggestion(diag.message)?.let { suggestion ->
                val action = CodeAction("Change to '$suggestion'")
                action.kind = CodeActionKind.QuickFix
                action.diagnostics = listOf(diag)
                action.edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(diag.range, suggestion))))
                action.isPreferred = true
                actions += Either.forRight(action)
            }

            when (code) {
                // E006 undefined procedure — offer PERFORM scaffold
                "E006" -> {
                    val msg = diag.message
                    val procMatch = Regex("'([\\w-]+)'").find(msg)?.groupValues?.get(1) ?: continue
                    val action = CodeAction("Create PROCEDURE $procMatch")
                    action.kind = CodeActionKind.QuickFix
                    action.diagnostics = listOf(diag)
                    // Insert a stub procedure at end of file
                    val endPos = Position(Int.MAX_VALUE, 0)
                    val stubEdit = TextEdit(
                        Range(endPos, endPos),
                        "\nPROCEDURE $procMatch:\n  STOP RUN\nEND-PROCEDURE\n",
                    )
                    action.edit = WorkspaceEdit(mapOf(uri to listOf(stubEdit)))
                    actions += Either.forRight(action)
                }
                // E010 RETURN outside RETURNING procedure
                "E010" -> {
                    val action = CodeAction("Remove RETURN statement")
                    action.kind = CodeActionKind.QuickFix
                    action.diagnostics = listOf(diag)
                    action.edit = WorkspaceEdit(mapOf(uri to listOf(
                        TextEdit(expandToFullLine(diag.range), "")
                    )))
                    actions += Either.forRight(action)
                }
            }
        }

        return CompletableFuture.completedFuture(actions)
    }

    private fun extractSuggestion(message: String): String? {
        return Regex("did you mean[:\\s]+([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)
    }

    private fun expandToFullLine(range: Range): Range =
        Range(Position(range.start.line, 0), Position(range.start.line + 1, 0))

    // ── Inlay Hints ────────────────────────────────────────────────────────

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(emptyList())
        val program = state.program ?: return CompletableFuture.completedFuture(emptyList())
        val hints = mutableListOf<InlayHint>()

        // Show inferred types for LET statements without explicit type annotation
        fun walkBody(stmts: List<Statement>) {
            for (stmt in stmts) {
                if (stmt is LocalVarDecl && stmt.type == null) {
                    val inferredType = guessLiteralType(stmt.initializer) ?: continue
                    val line = (stmt.pos.line - 1).coerceAtLeast(0)
                    // Position hint after the variable name: LET <name>|: TYPE
                    val col  = (stmt.pos.column - 1).coerceAtLeast(0) + "LET ".length + stmt.name.length
                    val hint = InlayHint(Position(line, col), Either.forLeft(": $inferredType"))
                    hint.kind = InlayHintKind.Type
                    hint.paddingLeft = false
                    hint.paddingRight = true
                    hints += hint
                }
                // Recurse into blocks
                when (stmt) {
                    is IfStatement     -> { walkBody(stmt.thenBranch); stmt.elseBranch?.let { walkBody(it) } }
                    is ForEachStatement -> walkBody(stmt.body)
                    is WhileStatement  -> walkBody(stmt.body)
                    is TryStatement    -> { walkBody(stmt.body); stmt.handlers.forEach { walkBody(it.body) } }
                    is MatchStatement  -> stmt.whenClauses.forEach { walkBody(it.body) }
                    else -> {}
                }
            }
        }

        for (proc in program.procedures) walkBody(proc.body)
        for (test in program.tests) walkBody(test.body)

        return CompletableFuture.completedFuture(hints)
    }

    /** Best-effort literal type inference for LET inlay hints. */
    private fun guessLiteralType(expr: Expression): String? = when (expr) {
        is Literal -> when (expr.kind) {
            LiteralKind.INTEGER -> "INTEGER"
            LiteralKind.DECIMAL -> "DECIMAL"
            LiteralKind.STRING  -> "TEXT"
            LiteralKind.BOOLEAN -> "BOOLEAN"
        }
        is UnaryExpr -> if (expr.op == UnaryOp.NEGATE) guessLiteralType(expr.operand) else null
        is BinaryExpr -> when (expr.op) {
            BinaryOp.ADD, BinaryOp.SUBTRACT, BinaryOp.MULTIPLY, BinaryOp.DIVIDE ->
                guessLiteralType(expr.left) ?: guessLiteralType(expr.right)
            BinaryOp.EQ, BinaryOp.NEQ, BinaryOp.LT, BinaryOp.GT,
            BinaryOp.LEQ, BinaryOp.GEQ, BinaryOp.AND, BinaryOp.OR -> "BOOLEAN"
            else -> null
        }
        else -> null
    }

    // ── Document Formatting ────────────────────────────────────────────────

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        val lines = docs[params.textDocument.uri]?.lines
            ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.completedFuture(computeIndentEdits(lines, params.options.tabSize))
    }

    override fun onTypeFormatting(
        params: DocumentOnTypeFormattingParams,
    ): CompletableFuture<List<TextEdit>> {
        val lines = docs[params.textDocument.uri]?.lines
            ?: return CompletableFuture.completedFuture(emptyList())
        // Re-derive whole-document indentation, but only return the edit for the
        // line just typed on — so finishing a block opener (`:`) snaps it to its
        // correct depth without disturbing the rest of the file.
        val edits = computeIndentEdits(lines, params.options.tabSize)
            .filter { it.range.start.line == params.position.line }
        return CompletableFuture.completedFuture(edits)
    }

    /**
     * Depth-based re-indentation edits for the whole document: each block opener
     * (line ending `:`) increases depth; each `END-*`/`ELSE`/`ON`/`ENSURE`/
     * `OTHERWISE` decreases it. Heuristic (no AST), shared by formatting and
     * onTypeFormatting. Pure for testing.
     */
    internal fun computeIndentEdits(lines: List<String>, tabSize: Int): List<TextEdit> {
        val indent = " ".repeat(tabSize.takeIf { it > 0 } ?: 2)
        val edits = mutableListOf<TextEdit>()
        var depth = 0
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trim()
            val upper   = trimmed.uppercase()

            if (DEDENT_KEYWORDS.any { upper.startsWith(it) }) depth = (depth - 1).coerceAtLeast(0)

            val expected  = indent.repeat(depth)
            val actual    = line.length - line.trimStart().length
            val actualStr = " ".repeat(actual)
            if (actualStr != expected && trimmed.isNotEmpty()) {
                edits += TextEdit(Range(Position(idx, 0), Position(idx, actual)), expected)
            }

            if (trimmed.endsWith(":") && !DEDENT_KEYWORDS.any { upper.startsWith(it) }) depth++
        }
        return edits
    }

    // ── Rename ─────────────────────────────────────────────────────────────

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(null)
        val token = tokenAtPosition(state.lines, params.position.line + 1, params.position.character + 1)
            ?: return CompletableFuture.completedFuture(null)
        val newName = params.newName
        val edits = findWordOccurrences(state.lines, token).map { TextEdit(it, newName) }
        return CompletableFuture.completedFuture(WorkspaceEdit(mapOf(params.textDocument.uri to edits)))
    }

    override fun prepareRename(
        params: PrepareRenameParams,
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(null)
        val range = wordRangeAt(state.lines, params.position.line, params.position.character)
            ?: return CompletableFuture.completedFuture(null)
        val word = state.lines[range.start.line]
            .substring(range.start.character, range.end.character)
        // Reserved keywords are not renameable — reject so the editor shows
        // "cannot rename" instead of silently rewriting language syntax.
        if (Lexer.KEYWORDS.containsKey(word.uppercase())) {
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.completedFuture(Either3.forFirst(range))
    }

    /** Range of the identifier word covering the 0-based [line0]/[char0], or null. */
    internal fun wordRangeAt(lines: List<String>, line0: Int, char0: Int): Range? {
        val lineText = lines.getOrNull(line0) ?: return null
        if (lineText.isEmpty()) return null
        val c = char0.coerceIn(0, lineText.length - 1)
        var start = c
        while (start > 0 && lineText[start - 1].isIdentChar()) start--
        var end = c
        while (end < lineText.length && lineText[end].isIdentChar()) end++
        if (start == end || !lineText[start].isIdentChar()) return null
        return Range(Position(line0, start), Position(line0, end))
    }

    /**
     * All whole-word, case-insensitive occurrences of [token] across [lines], as
     * ranges. Shared by references / documentHighlight / rename. This is a
     * textual scan (single-file, scope-unaware) — see #F8 for the planned
     * AST/scope-aware replacement; keeping it in one place makes that a
     * single-point swap.
     */
    internal fun findWordOccurrences(lines: List<String>, token: String): List<Range> {
        val ranges = mutableListOf<Range>()
        lines.forEachIndexed { lineIdx, lineText ->
            var start = 0
            val upper = lineText.uppercase()
            while (true) {
                val idx = upper.indexOf(token, start)
                if (idx < 0) break
                val before = if (idx > 0) upper[idx - 1] else ' '
                val after  = if (idx + token.length < upper.length) upper[idx + token.length] else ' '
                if (!before.isIdentChar() && !after.isIdentChar()) {
                    ranges += Range(Position(lineIdx, idx), Position(lineIdx, idx + token.length))
                }
                start = idx + 1
            }
        }
        return ranges
    }

    // ── Semantic Tokens ──────────────────────────────────────────────────────

    override fun semanticTokensFull(
        params: SemanticTokensParams,
    ): CompletableFuture<SemanticTokens> {
        val state   = docs[params.textDocument.uri]
        val checker = state?.checker
            ?: return CompletableFuture.completedFuture(SemanticTokens(emptyList()))
        return CompletableFuture.completedFuture(
            SemanticTokens(computeSemanticTokens(state.lines, checker)),
        )
    }

    // ── Core: analyse & publish ────────────────────────────────────────────

    internal fun analyseAndPublish(uri: String, text: String) {
        val fileName = runCatching { File(URI(uri)).name }.getOrElse { uri }
        val lines    = text.lines()
        val lspDiags = mutableListOf<Diagnostic>()
        var program: Program? = null
        var checker: TypeChecker? = null

        val tokens = try {
            Lexer(text, fileName).tokenize()
        } catch (e: LexException) {
            lspDiags += e.toLspDiagnostic()
            publish(uri, lspDiags); docs[uri] = DocumentState(null, null, lines); return
        }

        program = try {
            Parser(tokens, fileName).parseProgram()
        } catch (e: ParseException) {
            lspDiags += e.toLspDiagnostic()
            publish(uri, lspDiags); docs[uri] = DocumentState(null, null, lines); return
        }

        checker = TypeChecker(lines)
        checker.analyze(program)
        checker.diagnostics.errors.forEach  { d -> lspDiags += d.toLsp(DiagnosticSeverity.Error)   }
        checker.diagnostics.warnings.forEach { d -> lspDiags += d.toLsp(DiagnosticSeverity.Warning) }

        docs[uri] = DocumentState(program, checker, lines)
        publish(uri, lspDiags)
    }

    private fun publish(uri: String, diags: List<Diagnostic>) {
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, diags))
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Return the word (identifier/keyword) covering [col1based] on the given 1-based line. */
    internal fun tokenAtPosition(lines: List<String>, line1: Int, col1: Int): String? {
        val lineText = lines.getOrNull(line1 - 1) ?: return null
        val col0     = (col1 - 1).coerceIn(0, lineText.length - 1)
        var start = col0
        while (start > 0 && lineText[start - 1].isIdentChar()) start--
        var end = col0
        while (end < lineText.length && lineText[end].isIdentChar()) end++
        if (start == end) return null
        return lineText.substring(start, end).uppercase()
    }

    /**
     * Find the first line >= [fromLine] that starts with [keyword] (trimmed, uppercase).
     * Returns the 0-based line index or null.
     */
    private fun findEndKeyword(lines: List<String>, fromLine: Int, keyword: String): Int? {
        for (i in (fromLine + 1)..lines.lastIndex) {
            if (lines[i].trimStart().uppercase().startsWith(keyword)) return i
        }
        return null
    }

    private fun Char.isIdentChar() = isLetterOrDigit() || this == '-' || this == '_'

    private fun <T> emptyEither() =
        CompletableFuture.completedFuture<Either<List<T>, List<LocationLink>>>(Either.forLeft(emptyList()))
}

// ─────────────────────────────────────────────────────────────────────────────
//  Conversions: compiler diagnostics → LSP
// ─────────────────────────────────────────────────────────────────────────────

private fun dev.kobol.diagnostic.Diagnostic.toLsp(severity: DiagnosticSeverity): Diagnostic {
    val line = (pos.line - 1).coerceAtLeast(0)
    val col  = (pos.column - 1).coerceAtLeast(0)
    val diag = Diagnostic(
        Range(Position(line, col), Position(line, col + 1)),
        message,
        severity,
        "kobolc",
        code,
    )
    // Embed suggestion in related info so quick-fix code actions can parse it
    diag.message = message
    return diag
}

private fun LexException.toLspDiagnostic(): Diagnostic = Diagnostic(
    Range(Position(0, 0), Position(0, 1)),
    message ?: "Lex error",
    DiagnosticSeverity.Error,
    "kobolc",
)

private fun ParseException.toLspDiagnostic(): Diagnostic {
    val line = (pos.line - 1).coerceAtLeast(0)
    val col  = (pos.column - 1).coerceAtLeast(0)
    return Diagnostic(
        Range(Position(line, col), Position(line, col + 1)),
        message ?: "Parse error",
        DiagnosticSeverity.Error,
        "kobolc",
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Semantic tokens
// ─────────────────────────────────────────────────────────────────────────────

// Legend indices — order MUST match SEMANTIC_TOKEN_TYPES and the legend
// registered in KobolLanguageServer.initialize.
internal const val SEMTOK_FUNCTION = 0
internal const val SEMTOK_STRUCT   = 1
internal const val SEMTOK_ENUM     = 2
internal const val SEMTOK_VARIABLE = 3

/** Semantic token type legend advertised to the client. */
internal val SEMANTIC_TOKEN_TYPES = listOf("function", "struct", "enum", "variable")

/**
 * Compute LSP semantic tokens for a document: re-lexes the source and classifies
 * each identifier by the kind of top-level symbol it resolves to (procedure →
 * function, record → struct, variant → enum, data global → variable). Identifiers
 * that don't resolve to a top-level symbol (locals, params, keywords, literals)
 * are left to the TextMate grammar.
 *
 * Returns the flat 5-ints-per-token delta encoding the LSP protocol expects.
 * Pure (no LSP client / IO) so it is unit-testable directly from a [TypeChecker].
 */
internal fun computeSemanticTokens(lines: List<String>, checker: TypeChecker): List<Int> {
    val tokens = try {
        Lexer(lines.joinToString("\n"), "doc.kbl").tokenize()
    } catch (e: LexException) {
        return emptyList()
    }

    data class Tok(val line: Int, val char: Int, val len: Int, val type: Int)
    val collected = ArrayList<Tok>()
    for (t in tokens) {
        if (t.type != TokenType.IDENTIFIER) continue
        val typeIdx = when (checker.symbols.resolve(t.value)) {
            is Symbol.ProcedureSymbol -> SEMTOK_FUNCTION
            is Symbol.RecordSymbol    -> SEMTOK_STRUCT
            is Symbol.VariantSymbol   -> SEMTOK_ENUM
            is Symbol.Variable,
            is Symbol.Constant        -> SEMTOK_VARIABLE
            else                      -> continue
        }
        collected += Tok(t.pos.line - 1, t.pos.column - 1, t.rawValue.length, typeIdx)
    }
    collected.sortWith(compareBy({ it.line }, { it.char }))

    val data = ArrayList<Int>(collected.size * 5)
    var prevLine = 0
    var prevChar = 0
    for (tk in collected) {
        val dLine = tk.line - prevLine
        val dChar = if (dLine == 0) tk.char - prevChar else tk.char
        data.add(dLine); data.add(dChar); data.add(tk.len); data.add(tk.type); data.add(0)
        prevLine = tk.line
        prevChar = tk.char
    }
    return data
}

// ─────────────────────────────────────────────────────────────────────────────
//  Keyword lists
// ─────────────────────────────────────────────────────────────────────────────

private val DEDENT_KEYWORDS = listOf(
    "END-PROCEDURE", "END-IF", "END-FOR", "END-WHILE", "END-REPEAT",
    "END-TRY", "END-MATCH", "END-RECORD", "END-VARIANT", "END-MODULE",
    "END-TEST", "END-SERVER", "END-CONCURRENT", "END-PRECISION",
    "END-VALIDATE", "END-CONFIG", "ELSE", "ON ", "ENSURE", "OTHERWISE",
)

private val KEYWORD_DOC = mapOf(
    "PERFORM"    to "Call a procedure",
    "COMPUTE"    to "Assign an expression result to a variable",
    "MOVE"       to "Copy a value to a variable",
    "DISPLAY"    to "Print to stdout",
    "IF"         to "Conditional branch",
    "FOR"        to "Loop over a collection",
    "WHILE"      to "Loop while condition is true",
    "MATCH"      to "Pattern match on a value",
    "TRY"        to "Exception handler block",
    "RETURN"     to "Return a value from a procedure",
    "STOP RUN"   to "Terminate the program",
    "LET"        to "Declare a local variable (type inferred or explicit)",
    "LOG"        to "Emit a structured log message (SLF4J)",
    "CONCURRENT" to "Run branches concurrently on virtual threads",
    "VALIDATE"   to "Apply validation constraints to a value",
    "ASSERT"     to "Assert a boolean condition in tests",
    "SLEEP"      to "Pause execution for a duration",
)

/**
 * Clause / contextual words the parser accepts in specific positions but the
 * lexer does NOT reserve (so they are absent from [Lexer.KEYWORDS]). Offered in
 * completion for ergonomics. Hand-maintained but small and additive — anything
 * the lexer actually reserves comes from [Lexer.KEYWORDS] below, never here.
 */
private val CONTEXTUAL_KEYWORDS = listOf(
    // structure / declaration clause words
    "FIELDS", "TYPE", "TABLE", "COLUMNS", "ROW", "RETURNS", "RAISES",
    "GIVEN", "THEN",
    // arithmetic rounding / precision presets (§12.4)
    "DECIMAL32", "DECIMAL64", "DECIMAL128", "UNLIMITED",
    "HALF-UP", "HALF-EVEN", "HALF-DOWN", "CEILING", "FLOOR",
    // condition / validation clause words (§19)
    "POSITIVE", "NEGATIVE", "ZERO", "EMPTY", "BLANK",
    "SATISFY", "LENGTH", "REQUIRED", "DEFAULT", "BE",
    // concurrency clauses (§18)
    "FIRST", "SCOPE", "MAX-THREADS", "ATOMIC",
    // collection-pipeline clauses
    "BY", "ASCENDING", "DESCENDING", "LIMIT",
    // serialization (§26, §30)
    "JSON", "XML", "PRETTY", "ROOT", "NAMESPACES",
    // HTTP verbs not lexer-reserved (§25, §28)
    "POST", "PATCH",
    // CLI / TUI (§27)
    "ACCEPT", "CONFIRM", "TERMINAL", "ARGUMENT", "PROGRESS", "STYLED",
    // env-backed config (§20)
    "ENV", "KEYSTORE",
)

/**
 * All keywords offered in completion: every reserved word from [Lexer.KEYWORDS]
 * — the single source of truth, CI-guarded against the spec by
 * `KeywordSpecSyncTest`, so this list can never drift from the real lexer —
 * plus the contextual clause words above. De-duplicated and sorted for a stable
 * completion order.
 */
internal val KOBOL_KEYWORDS: List<String> =
    (Lexer.KEYWORDS.keys + CONTEXTUAL_KEYWORDS).distinct().sorted()
