package dev.kobol.repl

import dev.kobol.VERSION
import dev.kobol.codegen.AsmEmitter
import dev.kobol.lexer.LexException
import dev.kobol.lexer.Lexer
import dev.kobol.lexer.TokenType
import dev.kobol.parser.ParseException
import dev.kobol.parser.Parser
import dev.kobol.parser.ast.*
import dev.kobol.semantic.TypeChecker
import java.io.File

/**
 * Kobol REPL.
 *
 * Features:
 *   - Readline with history persisted at ~/.kobol_history
 *   - DATA declarations accumulate across lines (persistent session state)
 *   - PROCEDURE blocks accumulate across lines
 *   - Type-annotated output: `=> value : TYPE` for assignments (REPL-B / ER-3 partial)
 *   - `:type <expr>`  — show inferred type
 *   - `:save <file>`  — dump session to .kbl file
 *   - `:load <file>`  — load and type-check a .kbl file
 *   - `:clear`        — reset session state
 *   - `:help`         — show commands
 *   - `:quit` / `:q`  — exit
 *
 * ER-3 milestone: REPL launches, parses input, shows inferred types.
 */
class Repl {

    // Accumulated declarations across REPL entries
    private val dataItems   = mutableListOf<String>()
    private val procedures  = mutableListOf<String>()
    private val sessionLog  = mutableListOf<String>()

    private val historyFile = File(System.getProperty("user.home"), ".kobol_history")
    private val history     = mutableListOf<String>()

    private var lineNo = 1

    fun run() {
        printBanner()
        loadHistory()

        while (true) {
            val line = readLine("> ") ?: break
            if (line.isBlank()) continue
            addHistory(line)

            when {
                line.startsWith(":quit") || line.startsWith(":q") -> { println("Bye!"); break }
                line.startsWith(":help")    -> printHelp()
                line.startsWith(":clear")   -> clearSession()
                line.startsWith(":history") -> history.takeLast(20).forEachIndexed { i, h -> println("${i+1}  $h") }
                line.startsWith(":save ")   -> saveSession(line.removePrefix(":save ").trim())
                line.startsWith(":load ")   -> loadFile(line.removePrefix(":load ").trim())
                line.startsWith(":type ")   -> typeCheck(line.removePrefix(":type ").trim())
                else                        -> evalLine(line)
            }
        }

        saveHistory()
    }

    // -------------------------------------------------------------------------
    // Line evaluation
    // -------------------------------------------------------------------------

    private fun evalLine(input: String) {
        // Accumulate multi-line input until the block is complete
        val fullInput = collectBlock(input)
        if (fullInput.isBlank()) return

        val wrapped = wrapInProgram(fullInput)
        val (program, checker) = parseAndCheck(wrapped) ?: return

        sessionLog.add(fullInput)
        when {
            fullInput.trimStart().startsWith("DATA", ignoreCase = true) -> {
                dataItems.add(fullInput)
                println("  ok  — data section updated")
            }
            fullInput.trimStart().startsWith("PROCEDURE", ignoreCase = true) -> {
                procedures.add(fullInput)
                println("  ok  — procedure '${program.procedures.lastOrNull()?.name ?: "?"}' registered")
            }
            fullInput.trimStart().startsWith("DISPLAY", ignoreCase = true) -> {
                // REPL-C: execute DISPLAY statements by compiling + running in-process
                printTypedSummary(program, checker)
                executeInProcess(program, checker)
            }
            else -> printTypedSummary(program, checker)
        }
        lineNo++
    }

    /**
     * REPL-C: compile the wrapped program to bytecode and run its Main procedure
     * inside an isolated classloader, capturing stdout.
     */
    private fun executeInProcess(program: dev.kobol.parser.ast.Program, checker: TypeChecker) {
        try {
            val emitter  = AsmEmitter(checker)
            val bytecodes = emitter.emit(program)  // name → bytes

            val loader = object : ClassLoader(Thread.currentThread().contextClassLoader) {
                fun define(name: String, bytes: ByteArray): Class<*> =
                    defineClass(name.replace('/', '.'), bytes, 0, bytes.size)
            }
            val classes = bytecodes.mapValues { (name, bytes) -> loader.define(name, bytes) }
            val mainClass = classes.values.firstOrNull { it.simpleName == emitter.javaClass(program.name) }
                ?: classes.values.firstOrNull()
            if (mainClass != null) {
                val mainMethod = runCatching { mainClass.getMethod("main", Array<String>::class.java) }.getOrNull()
                mainMethod?.invoke(null, arrayOf<String>())
            }
        } catch (e: Exception) {
            // Don't crash the REPL on execution errors — show them
            val cause = (e.cause ?: e)
            println("  runtime: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /**
     * Collect a block: if the first line ends with `:`, keep reading until we
     * see a matching END-* keyword or a blank line.
     */
    private fun collectBlock(firstLine: String): String {
        val sb = StringBuilder(firstLine)
        val needsBlock = firstLine.trimEnd().endsWith(":")
        if (!needsBlock) return firstLine

        while (true) {
            val next = readLine(".. ") ?: break
            sb.appendLine()
            sb.append(next)
            if (next.isBlank() || next.trimStart().startsWith("END", ignoreCase = true)) break
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Parse + type-check  — returns (Program, TypeChecker) pair or null on error
    // -------------------------------------------------------------------------

    private fun parseAndCheck(wrapped: String): Pair<Program, TypeChecker>? {
        return try {
            val tokens  = Lexer(wrapped, "<repl>").tokenize()
            val program = Parser(tokens, "<repl>").parseProgram()
            val lines   = wrapped.lines()
            val checker = TypeChecker(lines)
            checker.analyze(program)
            if (checker.diagnostics.hasErrors) {
                checker.diagnostics.errors.forEach { println("  ${it.render()}") }
                null
            } else {
                checker.diagnostics.warnings.forEach { println("  ${it.render()}") }
                Pair(program, checker)
            }
        } catch (e: LexException) {
            println("  ${e.message}")
            null
        } catch (e: ParseException) {
            println("  ${e.message}")
            null
        }
    }

    private fun typeCheck(exprSrc: String) {
        val wrapped = wrapExprInProgram(exprSrc)
        try {
            val tokens  = Lexer(wrapped, "<repl>").tokenize()
            val program = Parser(tokens, "<repl>").parseProgram()
            val checker = TypeChecker(wrapped.lines())
            checker.analyze(program)
            val mainProc = program.procedures.find { it.name == "MAIN" }
            val displayStmt = mainProc?.body?.filterIsInstance<DisplayStatement>()?.firstOrNull()
            if (displayStmt != null) {
                val exprType = checker.typeOf(displayStmt.values.first())
                println("  : $exprType")
            } else {
                println("  (could not determine type)")
            }
        } catch (e: Exception) {
            println("  error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // REPL-B: typed result output  (=> name : TYPE)
    // -------------------------------------------------------------------------

    private fun printTypedSummary(program: Program, checker: TypeChecker) {
        val main = program.procedures.find { it.name == "MAIN" } ?: return
        val stmts = main.body.dropLast(1)  // drop the synthetic STOP RUN
        if (stmts.isEmpty()) { println("  ok"); return }
        for (stmt in stmts) {
            val line = when (stmt) {
                is LocalVarDecl -> {
                    val t = checker.symbols.resolve(stmt.name)
                    val kType = (t as? dev.kobol.semantic.Symbol.Variable)?.type
                        ?: checker.typeOf(stmt.initializer)
                    "  => ${stmt.name} : $kType"
                }
                is ComputeStatement -> {
                    val name  = stmt.target.parts.joinToString(".")
                    val kType = checker.typeOf(stmt.expr)
                    "  => $name : $kType"
                }
                is MoveStatement -> {
                    val name = stmt.to.parts.joinToString(".")
                    "  => $name  (moved)"
                }
                is DisplayStatement -> "  ${stmtSummary(stmt)}"
                else                -> "  ok  — ${stmtSummary(stmt)}"
            }
            println(line)
        }
    }

    // -------------------------------------------------------------------------
    // Session state wrapping
    // -------------------------------------------------------------------------

    private fun wrapInProgram(input: String): String {
        val trimmed = input.trim()
        // Detect top-level declarations (DATA, PROCEDURE, RECORD)
        if (trimmed.startsWith("PROCEDURE", ignoreCase = true) ||
            trimmed.startsWith("RECORD", ignoreCase = true)) {
            return buildSession(trimmed)
        }
        if (trimmed.startsWith("DATA", ignoreCase = true)) {
            return buildSession(trimmed)
        }
        // Treat as a statement inside an implicit Main procedure
        val stmtBody = trimmed.lines().joinToString("\n") { "  $it" }
        return buildSession(null, stmtBody)
    }

    private fun wrapExprInProgram(expr: String): String =
        buildSession(null, "  DISPLAY $expr")

    private fun buildSession(newDecl: String?, extraBody: String? = null): String {
        return buildString {
            appendLine("PROGRAM ReplSession")
            for (d in dataItems) appendLine(d)
            if (newDecl != null && newDecl.trimStart().startsWith("DATA", ignoreCase = true)) {
                appendLine(newDecl)
            }
            for (p in procedures) appendLine(p)
            if (newDecl != null && newDecl.trimStart().startsWith("PROCEDURE", ignoreCase = true)) {
                appendLine(newDecl)
            }
            appendLine("PROCEDURE Main:")
            if (extraBody != null) appendLine(extraBody)
            appendLine("  STOP RUN")
            appendLine("END-PROCEDURE")
        }
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private fun clearSession() {
        dataItems.clear(); procedures.clear(); sessionLog.clear()
        println("  Session cleared.")
    }

    private fun saveSession(path: String) {
        if (path.isEmpty()) { println("  Usage: :save <filename.kbl>"); return }
        val file = File(path)
        val content = buildString {
            appendLine("PROGRAM SavedSession")
            for (d in dataItems) { appendLine(d); appendLine() }
            for (p in procedures) { appendLine(p); appendLine() }
            appendLine("PROCEDURE Main:")
            appendLine("  STOP RUN")
            appendLine("END-PROCEDURE")
        }
        file.writeText(content)
        println("  Session saved to ${file.absolutePath}")
    }

    private fun loadFile(path: String) {
        val file = File(path)
        if (!file.exists()) { println("  File not found: $path"); return }
        println("  Loading $path...")
        val src = file.readText()
        val result = parseAndCheck(src)
        if (result != null) println("  Loaded OK — ${result.first.procedures.size} procedure(s)")
    }

    // -------------------------------------------------------------------------
    // AST summary helpers
    // -------------------------------------------------------------------------

    private fun unusedPrintAstSummary(program: Program) {
        val main = program.procedures.find { it.name == "MAIN" } ?: return
        val stmts = main.body.dropLast(1)
        if (stmts.isEmpty()) return
        for (stmt in stmts) {
            println("  ${stmtSummary(stmt)}")
        }
    }

    private fun stmtSummary(stmt: Statement): String = when (stmt) {
        is DisplayStatement  -> "DISPLAY ${stmt.values.joinToString(", ") { exprSummary(it) }}"
        is ComputeStatement  -> "COMPUTE ${stmt.target.parts.joinToString(".")} = ${exprSummary(stmt.expr)}"
        is LocalVarDecl      -> "LET ${stmt.name}${stmt.type?.let { " : $it" } ?: ""} = ${exprSummary(stmt.initializer)}"
        is MoveStatement     -> "MOVE ${exprSummary(stmt.from)} TO ${stmt.to.parts.joinToString(".")}"
        is IfStatement       -> "IF ${exprSummary(stmt.condition)} [${stmt.thenBranch.size} stmt(s)]"
        is WhileStatement    -> "WHILE ${exprSummary(stmt.condition)} [${stmt.body.size} stmt(s)]"
        is ForEachStatement  -> "FOR EACH ${stmt.variable} IN ${exprSummary(stmt.iterable)} [${stmt.body.size} stmt(s)]"
        is PerformStatement  -> "PERFORM ${stmt.procedureName}"
        is ReturnStatement   -> "RETURN${stmt.value?.let { " ${exprSummary(it)}" } ?: ""}"
        is StopRunStatement  -> "STOP RUN"
        else                 -> stmt::class.simpleName ?: "?"
    }

    private fun exprSummary(expr: Expression): String = when (expr) {
        is Literal           -> expr.value.toString()
        is Reference         -> expr.parts.joinToString(".")
        is BinaryExpr        -> "${exprSummary(expr.left)} ${expr.op} ${exprSummary(expr.right)}"
        is UnaryExpr         -> "${expr.op} ${exprSummary(expr.operand)}"
        is StringTemplateExpr -> "\"...\""
        is BuiltinCall       -> "${expr.name}(${expr.args.size} arg(s))"
        is RecordLiteralExpr -> "${expr.typeName}{...}"
        is NewExpr           -> "NEW ${expr.owner}(${expr.args.size} arg(s))"
        is CallExpr          -> "CALL ${expr.method}(${expr.args.size} arg(s))"
        is NamedArgument     -> "${expr.paramName}: ${exprSummary(expr.value)}"
        is PipelineExpr      -> "${exprSummary(expr.source)} |> ${expr.stages.size} stage(s)"
        is IndexExpr         -> "${exprSummary(expr.target)}[${exprSummary(expr.index)}]"
    }

    // -------------------------------------------------------------------------
    // I/O helpers
    // -------------------------------------------------------------------------

    private fun readLine(prompt: String): String? {
        print(prompt)
        System.out.flush()
        return kotlin.io.readLine()
    }

    private fun addHistory(line: String) {
        if (history.lastOrNull() != line) history.add(line)
        if (history.size > 500) history.removeFirst()
    }

    private fun loadHistory() {
        if (historyFile.exists()) {
            historyFile.readLines().filter { it.isNotBlank() }.forEach { history.add(it) }
        }
    }

    private fun saveHistory() {
        historyFile.writeText(history.takeLast(500).joinToString("\n"))
    }

    // -------------------------------------------------------------------------
    // Banners
    // -------------------------------------------------------------------------

    private fun printBanner() {
        val top = "┌─────────────────────────────────────────────────┐"
        val bot = "└─────────────────────────────────────────────────┘"
        val inner = top.length - 2                        // cell width between the corners
        fun row(s: String) = "│" + "  $s".padEnd(inner) + "│"
        println(
            listOf(
                top,
                row("Kobol REPL $VERSION"),               // single-sourced version
                row("Type :help for commands, :quit to exit"),
                bot,
            ).joinToString("\n")
        )
    }

    private fun printHelp() {
        println("""
            Commands:
              :type <expr>    — Show inferred type of expression
              :save <file>    — Save session declarations to <file>.kbl
              :load <file>    — Load and type-check a .kbl file
              :clear          — Reset session state
              :history        — Show recent input history
              :help           — Show this help
              :quit / :q      — Exit the REPL

            Multi-line input:
              Lines ending with ':' start a block. Continue on next prompt (..),
              then enter a blank line or END-* to finish the block.

            Examples:
              > LET x = 42
              > DISPLAY "Value: {x}"
              > :type x
              > DATA:
              ..   total : DECIMAL(10,2) = 0
              ..
        """.trimIndent())
    }
}
