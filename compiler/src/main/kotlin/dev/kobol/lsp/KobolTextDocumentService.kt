package dev.kobol.lsp

import dev.kobol.lexer.LexException
import dev.kobol.lexer.Lexer
import dev.kobol.parser.ParseException
import dev.kobol.parser.Parser
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
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

        // AST-driven reference finding: find all positions where this token appears
        // as an identifier (not as part of a keyword sequence)
        val locations = mutableListOf<Location>()
        state.lines.forEachIndexed { lineIdx, lineText ->
            var start = 0
            val upper = lineText.uppercase()
            while (true) {
                val idx = upper.indexOf(token, start)
                if (idx < 0) break
                // Verify it's a whole-word match (not part of a longer identifier)
                val before = if (idx > 0) upper[idx - 1] else ' '
                val after  = if (idx + token.length < upper.length) upper[idx + token.length] else ' '
                if (!before.isIdentChar() && !after.isIdentChar()) {
                    val range = Range(Position(lineIdx, idx), Position(lineIdx, idx + token.length))
                    locations += Location(params.textDocument.uri, range)
                }
                start = idx + 1
            }
        }
        return CompletableFuture.completedFuture(locations)
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
            val code = diag.code?.get()?.toString() ?: continue

            when (code) {
                // E001 undefined variable — offer did-you-mean fix
                "E001" -> {
                    val suggestion = extractSuggestion(diag.message) ?: continue
                    val edit = TextEdit(diag.range, suggestion)
                    val wsEdit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                    val action = CodeAction("Fix: use '$suggestion'")
                    action.kind = CodeActionKind.QuickFix
                    action.diagnostics = listOf(diag)
                    action.edit = wsEdit
                    action.isPreferred = true
                    actions += Either.forRight(action)
                }
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
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(emptyList())
        val lines = state.lines
        val edits = mutableListOf<TextEdit>()
        val indent = " ".repeat(params.options.tabSize.takeIf { it > 0 } ?: 2)

        var depth = 0
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trim()
            val upper   = trimmed.uppercase()

            // Decrease indent before END-* / ELSE / ON / ENSURE / OTHERWISE
            if (DEDENT_KEYWORDS.any { upper.startsWith(it) }) depth = (depth - 1).coerceAtLeast(0)

            val expected = indent.repeat(depth)
            val actual   = line.length - line.trimStart().length
            val actualStr = " ".repeat(actual)

            if (actualStr != expected && trimmed.isNotEmpty()) {
                edits += TextEdit(
                    Range(Position(idx, 0), Position(idx, actual)),
                    expected,
                )
            }

            // Increase indent after lines ending with ':'
            if (trimmed.endsWith(":") && !DEDENT_KEYWORDS.any { upper.startsWith(it) }) {
                depth++
            }
            // Decrease after END-* (already decreased above, not again)
        }

        return CompletableFuture.completedFuture(edits)
    }

    // ── Rename ─────────────────────────────────────────────────────────────

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        val state = docs[params.textDocument.uri] ?: return CompletableFuture.completedFuture(null)
        val token = tokenAtPosition(state.lines, params.position.line + 1, params.position.character + 1)
            ?: return CompletableFuture.completedFuture(null)
        val newName = params.newName

        val edits = mutableListOf<TextEdit>()
        state.lines.forEachIndexed { lineIdx, lineText ->
            var start = 0
            val upper = lineText.uppercase()
            while (true) {
                val idx = upper.indexOf(token, start)
                if (idx < 0) break
                val before = if (idx > 0) upper[idx - 1] else ' '
                val after  = if (idx + token.length < upper.length) upper[idx + token.length] else ' '
                if (!before.isIdentChar() && !after.isIdentChar()) {
                    edits += TextEdit(Range(Position(lineIdx, idx), Position(lineIdx, idx + token.length)), newName)
                }
                start = idx + 1
            }
        }
        return CompletableFuture.completedFuture(WorkspaceEdit(mapOf(params.textDocument.uri to edits)))
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

internal val KOBOL_KEYWORDS = listOf(
    // Core control
    "IF", "ELSE", "END-IF", "FOR", "EACH", "IN", "END-FOR",
    "WHILE", "END-WHILE", "REPEAT", "TIMES", "END-REPEAT",
    "TRY", "ON", "AS", "ENSURE", "END-TRY",
    "STOP", "RUN", "RETURN", "RAISE", "MATCH", "OTHERWISE", "END-MATCH",
    // Declarations
    "PROGRAM", "PROCEDURE", "END-PROCEDURE", "END-PROGRAM",
    "RECORD", "END-RECORD", "FIELDS",
    "DATA", "FILES", "DEFINE", "TYPE", "IS",
    "IMPORT", "VERSION", "AUTHOR",
    "CONDITION", "WHEN", "WHERE",
    "VARIANT", "END-VARIANT",
    "MODULE", "EXPORT", "END-MODULE",
    "TEST", "END-TEST", "TABLE",
    "CONFIG", "END-CONFIG",
    "VALIDATE", "END-VALIDATE",
    "SERVER", "END-SERVER", "ENDPOINT",
    // Operations
    "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE", "ROUND",
    "MOVE", "SET", "LET", "DO", "PERFORM", "CALL", "AWAIT",
    "DISPLAY", "READ", "WRITE", "OPEN", "CLOSE",
    "FILTER", "SORT", "TAKE", "TRANSFORM", "SUM",
    "PARSE", "ASSERT", "MOCK",
    "SLEEP", "LOG",
    // Clauses
    "GIVING", "USING", "WITH", "FROM", "TO", "INTO", "BY",
    "RETURNING", "AND", "OR", "NOT", "IS",
    "POSITIVE", "NEGATIVE", "ZERO", "EMPTY", "BLANK",
    "MILLISECONDS", "SECONDS", "MINUTES",
    "MUST", "SATISFY", "LENGTH", "REQUIRED", "DEFAULT",
    "CONCURRENT", "PARALLEL", "WAIT", "ALL", "ASYNC", "FUTURE",
    "COLUMNS", "ROW",
    "PRECISION", "DECIMAL128", "HALF-UP", "HALF-EVEN",
    // Types
    "INTEGER", "SMALLINT", "DECIMAL", "MONEY",
    "TEXT", "BOOLEAN", "DATE", "TIME", "DATETIME",
    "LIST", "MAP", "OF", "UUID",
    // Literals
    "TRUE", "FALSE",
    // Logging levels
    "TRACE", "DEBUG", "INFO", "WARN", "ERROR",
    // NoSQL / Cache / HTTP
    "NOSQL", "CACHE", "DATABASE", "FIND", "SAVE", "DELETE", "COUNT",
    "GET", "POST", "PUT", "PATCH", "RESPOND", "STATUS",
    "HEADERS", "BODY", "TIMEOUT", "PORT", "AT",
)
