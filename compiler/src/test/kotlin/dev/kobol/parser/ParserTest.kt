package dev.kobol.parser

import dev.kobol.lexer.Lexer
import dev.kobol.parser.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    private fun parse(src: String): Program {
        val tokens = Lexer(src, "test.kbl").tokenize()
        return Parser(tokens, "test.kbl").parseProgram()
    }

    // -------------------------------------------------------------------------
    // Program structure
    // -------------------------------------------------------------------------

    @Test fun `minimal program`() {
        val p = parse("""
            PROGRAM Hello
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals("HELLO", p.name)
        assertEquals(1, p.procedures.size)
        assertEquals("MAIN", p.procedures[0].name)
    }

    // #1: the PROGRAM name's original source case is preserved in rawName so codegen
    // can honor it (`PROGRAM DataTypes` → class DataTypes), while name stays UPPERCASE.
    @Test fun `PROGRAM name preserves original case in rawName`() {
        val p = parse("""
            PROGRAM DataTypes
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals("DATATYPES", p.name, "name is normalized UPPERCASE")
        assertEquals("DataTypes", p.rawName, "rawName preserves source case")
    }

    @Test fun `program with version and author`() {
        val p = parse("""
            PROGRAM App
              VERSION "2.0"
              AUTHOR "Alice"
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals("2.0", p.version)
        assertEquals("Alice", p.author)
    }

    @Test fun `script mode wraps in implicit program`() {
        val p = parse("STOP RUN")
        assertEquals("TEST", p.name) // from "test.kbl"
        assertEquals(1, p.procedures.size)
        assertEquals("MAIN", p.procedures[0].name)
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    @Test fun `import with alias`() {
        val p = parse("""
            PROGRAM T
            IMPORT java.time.LocalDate AS LD
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals(1, p.imports.size)
        assertEquals("java.time.LocalDate", p.imports[0].qualifiedName)   // rawValue preserves original case
        assertEquals("LD", p.imports[0].alias)
    }

    // -------------------------------------------------------------------------
    // Record
    // -------------------------------------------------------------------------

    @Test fun `record with two fields`() {
        val p = parse("""
            PROGRAM T
            RECORD Customer:
              name : TEXT
              age  : INTEGER
            END-RECORD
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val rec = p.records.single()
        assertEquals("CUSTOMER", rec.name)
        assertEquals(2, rec.fields.size)
        assertEquals("NAME", rec.fields[0].name)
        assertIs<TypeSpec.TextType>(rec.fields[0].type)
        assertIs<TypeSpec.IntegerType>(rec.fields[1].type)
    }

    @Test fun `record with CONDITION`() {
        val p = parse("""
            PROGRAM T
            RECORD Account:
              balance : DECIMAL(10,2)
              CONDITION Overdrawn WHERE balance < 0
            END-RECORD
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val field = p.records[0].fields[0]
        assertEquals(1, field.conditions.size)
        assertEquals("OVERDRAWN", field.conditions[0].name)
    }

    // -------------------------------------------------------------------------
    // Data section
    // -------------------------------------------------------------------------

    @Test fun `data section items`() {
        val p = parse("""
            PROGRAM T
            DATA:
              count : INTEGER = 0
              name  : TEXT
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val data = assertNotNull(p.dataSection)
        assertEquals(2, data.items.size)
        assertEquals("COUNT", data.items[0].name)
        assertIs<TypeSpec.IntegerType>(data.items[0].type)
        assertNotNull(data.items[0].initializer)
        assertNull(data.items[1].initializer)
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    @Test fun `MOVE statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              MOVE 42 TO count
            END-PROCEDURE
        """.trimIndent())
        val stmt = prog.procedures[0].body[0]
        assertIs<MoveStatement>(stmt)
        assertEquals("COUNT", (stmt.to).parts[0])
    }

    @Test fun `COMPUTE statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE total = price + tax
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<ComputeStatement>(prog.procedures[0].body[0])
        assertEquals("TOTAL", stmt.target.parts[0])
        assertIs<BinaryExpr>(stmt.expr)
    }

    // #v10: an integer-literal precision (spec §12.4 `WITH PRECISION 34`) parses, the
    // numeric text is carried as precisionName, and the spec's END-WITH terminator closes
    // the block (alias of END-PRECISION).
    @Test fun `WITH PRECISION integer literal and END-WITH parses (v10)`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              WITH PRECISION 34 ROUNDING HALF-EVEN:
                COMPUTE y = x * 3
              END-WITH
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<WithPrecisionStatement>(prog.procedures[0].body[0])
        assertEquals("34", stmt.precisionName)
        assertEquals("HALF-EVEN", stmt.roundingMode)
        assertEquals(1, stmt.body.size)
    }

    @Test fun `LET with type annotation creates LocalVarDecl`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              LET x : INTEGER = 5
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<LocalVarDecl>(prog.procedures[0].body[0])
        assertEquals("X", stmt.name)
        assertIs<TypeSpec.IntegerType>(stmt.type)
    }

    @Test fun `LET without type annotation creates ComputeStatement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              LET x = 5
            END-PROCEDURE
        """.trimIndent())
        assertIs<ComputeStatement>(prog.procedures[0].body[0])
    }

    @Test fun `DO desugars to PerformStatement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              DO Process
            END-PROCEDURE
        """.trimIndent())
        assertIs<PerformStatement>(prog.procedures[0].body[0])
    }

    @Test fun `IF statement with ELSE`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              IF count > 0:
                DISPLAY "positive"
              ELSE:
                DISPLAY "non-positive"
              END-IF
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<IfStatement>(prog.procedures[0].body[0])
        assertEquals(1, stmt.thenBranch.size)
        assertNotNull(stmt.elseBranch)
        assertEquals(1, stmt.elseBranch!!.size)
    }

    @Test fun `WHILE statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              WHILE count < 10:
                ADD 1 TO count
              END-WHILE
            END-PROCEDURE
        """.trimIndent())
        assertIs<WhileStatement>(prog.procedures[0].body[0])
    }

    @Test fun `FOR EACH statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              FOR EACH item IN items:
                DISPLAY item
              END-FOR
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<ForEachStatement>(prog.procedures[0].body[0])
        assertEquals("ITEM", stmt.variable)
    }

    @Test fun `STOP RUN`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertIs<StopRunStatement>(prog.procedures[0].body[0])
    }

    @Test fun `RETURN with value`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE Calc RETURNING INTEGER:
              RETURN 42
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<ReturnStatement>(prog.procedures[0].body[0])
        assertNotNull(stmt.value)
    }

    @Test fun `ADD statement with GIVING`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              ADD tax TO total GIVING result
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<AddStatement>(prog.procedures[0].body[0])
        assertNotNull(stmt.giving)
    }

    @Test fun `TRY with ON and ENSURE`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              TRY:
                DISPLAY "ok"
              ON FileError:
                DISPLAY "error"
              ENSURE:
                DISPLAY "cleanup"
              END-TRY
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<TryStatement>(prog.procedures[0].body[0])
        assertEquals(1, stmt.handlers.size)
        assertNotNull(stmt.ensure)
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    @Test fun `binary expression precedence`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE result = 1 + 2 * 3
            END-PROCEDURE
        """.trimIndent())
        val expr = assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr
        val outer = assertIs<BinaryExpr>(expr)
        assertEquals(BinaryOp.ADD, outer.op)
        assertIs<BinaryExpr>(outer.right) // 2*3 is the right subtree
    }

    @Test fun `NOT expression`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              IF NOT active:
                STOP RUN
              END-IF
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<IfStatement>(prog.procedures[0].body[0])
        assertIs<UnaryExpr>(stmt.condition)
    }

    @Test fun `struct literal`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              MOVE Invoice { id: 1, amount: 100.00 } TO inv
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<MoveStatement>(prog.procedures[0].body[0])
        assertIs<RecordLiteralExpr>(stmt.from)
    }

    @Test fun `string interpolation in DISPLAY`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              DISPLAY "Hello {name}!"
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<DisplayStatement>(prog.procedures[0].body[0])
        assertIs<StringTemplateExpr>(stmt.values[0])
    }

    @Test fun `procedure with parameters`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE Greet USING who : TEXT:
              DISPLAY who
            END-PROCEDURE
        """.trimIndent())
        val proc = prog.procedures[0]
        assertEquals(1, proc.params.size)
        assertEquals("WHO", proc.params[0].name)
        assertIs<TypeSpec.TextType>(proc.params[0].type)
    }

    @Test fun `parse error throws ParseException`() {
        assertThrows<ParseException> {
            parse("""
                PROGRAM T
                PROCEDURE M:
                  COMPUTE = broken
                END-PROCEDURE
            """.trimIndent())
        }
    }

    // -------------------------------------------------------------------------
    // Type specifications
    // -------------------------------------------------------------------------

    @Test fun `MONEY with dot notation`() {
        val p = parse("""
            PROGRAM T
            DATA:
              amount : MONEY(12.2) = 0
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val item = p.dataSection!!.items[0]
        val t = assertIs<TypeSpec.MoneyType>(item.type)
        assertEquals(12, t.precision)
        assertEquals(2, t.scale)
    }

    @Test fun `DECIMAL with comma notation`() {
        val p = parse("""
            PROGRAM T
            DATA:
              rate : DECIMAL(10,4)
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val t = assertIs<TypeSpec.DecimalType>(p.dataSection!!.items[0].type)
        assertEquals(10, t.precision); assertEquals(4, t.scale)
    }

    @Test fun `MONEY bare no precision`() {
        val p = parse("""
            PROGRAM T
            DATA:
              fee : MONEY
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertIs<TypeSpec.MoneyType>(p.dataSection!!.items[0].type)
    }

    @Test fun `LIST OF type`() {
        val p = parse("""
            PROGRAM T
            DATA:
              items : LIST OF INTEGER
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val t = assertIs<TypeSpec.ListOf>(p.dataSection!!.items[0].type)
        assertIs<TypeSpec.IntegerType>(t.elementType)
    }

    @Test fun `MAP OF type`() {
        val p = parse("""
            PROGRAM T
            DATA:
              index : MAP OF TEXT TO INTEGER
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val t = assertIs<TypeSpec.MapOf>(p.dataSection!!.items[0].type)
        assertIs<TypeSpec.TextType>(t.keyType)
        assertIs<TypeSpec.IntegerType>(t.valueType)
    }

    @Test fun `DATE and BOOLEAN types`() {
        val p = parse("""
            PROGRAM T
            DATA:
              birth : DATE
              active : BOOLEAN
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertIs<TypeSpec.DateType>(p.dataSection!!.items[0].type)
        assertIs<TypeSpec.BooleanType>(p.dataSection!!.items[1].type)
    }

    @Test fun `LIST OF named record type`() {
        val p = parse("""
            PROGRAM T
            DATA:
              customers : LIST OF Customer
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val t = assertIs<TypeSpec.ListOf>(p.dataSection!!.items[0].type)
        assertIs<TypeSpec.NamedType>(t.elementType)
    }

    // -------------------------------------------------------------------------
    // DEFINE constants
    // -------------------------------------------------------------------------

    @Test fun `DEFINE constant`() {
        val p = parse("""
            PROGRAM T
            DEFINE TAX-RATE : DECIMAL = 8.5
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals(1, p.constants.size)
        assertEquals("TAX-RATE", p.constants[0].name)
        assertIs<TypeSpec.DecimalType>(p.constants[0].type)
    }

    @Test fun `DEFINE TEXT constant`() {
        val p = parse("""
            PROGRAM T
            DEFINE GREETING : TEXT = "Hello"
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val c = p.constants[0]
        assertEquals("GREETING", c.name)
        assertIs<Literal>(c.value)
    }

    // -------------------------------------------------------------------------
    // Arithmetic statements
    // -------------------------------------------------------------------------

    @Test fun `SUBTRACT statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              SUBTRACT tax FROM total
            END-PROCEDURE
        """.trimIndent())
        assertIs<SubtractStatement>(prog.procedures[0].body[0])
    }

    @Test fun `MULTIPLY statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              MULTIPLY rate BY amount
            END-PROCEDURE
        """.trimIndent())
        assertIs<MultiplyStatement>(prog.procedures[0].body[0])
    }

    @Test fun `DIVIDE statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              DIVIDE 100 INTO amount
            END-PROCEDURE
        """.trimIndent())
        assertIs<DivideStatement>(prog.procedures[0].body[0])
    }

    @Test fun `ADD statement accumulates with GIVING`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              ADD fee TO total GIVING result
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<AddStatement>(prog.procedures[0].body[0])
        assertNotNull(stmt.giving)
        assertEquals("RESULT", stmt.giving!!.parts[0])
    }

    // -------------------------------------------------------------------------
    // Control flow
    // -------------------------------------------------------------------------

    @Test fun `IF ELSE IF ELSE chain`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              IF x > 10:
                DISPLAY "high"
              ELSE IF x > 5:
                DISPLAY "mid"
              ELSE:
                DISPLAY "low"
              END-IF
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<IfStatement>(prog.procedures[0].body[0])
        assertEquals(1, stmt.elseIfClauses.size)
        assertNotNull(stmt.elseBranch)
    }

    @Test fun `REPEAT statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              REPEAT 3 TIMES:
                DISPLAY "hello"
              END-REPEAT
            END-PROCEDURE
        """.trimIndent())
        assertIs<RepeatStatement>(prog.procedures[0].body[0])
    }

    @Test fun `RAISE statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              RAISE AppError "something went wrong"
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<RaiseStatement>(prog.procedures[0].body[0])
        assertEquals("APPERROR", stmt.exceptionType)
        assertNotNull(stmt.message)
    }

    @Test fun `TRY with multiple ON handlers`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              TRY:
                DISPLAY "work"
              ON FileError:
                DISPLAY "file err"
              ON NetworkError:
                DISPLAY "net err"
              END-TRY
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<TryStatement>(prog.procedures[0].body[0])
        assertEquals(2, stmt.handlers.size)
        assertNull(stmt.ensure)
    }

    @Test fun `TRY ON AS binding`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              TRY:
                DISPLAY "work"
              ON AppError AS e:
                DISPLAY e
              END-TRY
            END-PROCEDURE
        """.trimIndent())
        val handler = assertIs<TryStatement>(prog.procedures[0].body[0]).handlers[0]
        assertEquals("E", handler.binding)
    }

    // -------------------------------------------------------------------------
    // File I/O statements
    // -------------------------------------------------------------------------

    @Test fun `OPEN READ CLOSE`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              OPEN DataFile FOR INPUT
              READ DataFile INTO rec
              CLOSE DataFile
            END-PROCEDURE
        """.trimIndent())
        val body = prog.procedures[0].body
        assertIs<OpenStatement>(body[0])
        assertIs<ReadStatement>(body[1])
        assertIs<CloseStatement>(body[2])
    }

    @Test fun `WRITE statement`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              OPEN OutFile FOR OUTPUT
              WRITE OutFile FROM rec
              CLOSE OutFile
            END-PROCEDURE
        """.trimIndent())
        assertIs<WriteStatement>(prog.procedures[0].body[1])
    }

    // -------------------------------------------------------------------------
    // CALL statement
    // -------------------------------------------------------------------------

    @Test fun `CALL with GIVING`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              CALL Math.random GIVING result
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<CallStatement>(prog.procedures[0].body[0])
        assertEquals("Math.random", stmt.method)   // rawValue preserves original case
        assertNotNull(stmt.giving)
    }

    @Test fun `CALL without args`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              CALL System.gc
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<CallStatement>(prog.procedures[0].body[0])
        assertEquals("System.gc", stmt.method)   // rawValue preserves original case
        assertEquals(0, stmt.args.size)
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    @Test fun `AND OR expression`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              IF active AND age > 18:
                STOP RUN
              END-IF
            END-PROCEDURE
        """.trimIndent())
        val cond = assertIs<IfStatement>(prog.procedures[0].body[0]).condition
        assertIs<BinaryExpr>(cond)
    }

    @Test fun `comparison operators`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              IF a <> b:
                STOP RUN
              END-IF
            END-PROCEDURE
        """.trimIndent())
        val cond = assertIs<BinaryExpr>(assertIs<IfStatement>(prog.procedures[0].body[0]).condition)
        assertEquals(BinaryOp.NEQ, cond.op)
    }

    @Test fun `negative literal`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE x = -5
            END-PROCEDURE
        """.trimIndent())
        val expr = assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr
        assertIs<UnaryExpr>(expr)
    }

    @Test fun `dotted field access reference`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              DISPLAY customer.name
            END-PROCEDURE
        """.trimIndent())
        val ref = assertIs<Reference>(
            assertIs<DisplayStatement>(prog.procedures[0].body[0]).values[0]
        )
        assertEquals(listOf("CUSTOMER", "NAME"), ref.parts)
    }

    @Test fun `string interpolation with expression`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              DISPLAY "Total: {amount * rate}"
            END-PROCEDURE
        """.trimIndent())
        val tmpl = assertIs<StringTemplateExpr>(
            assertIs<DisplayStatement>(prog.procedures[0].body[0]).values[0]
        )
        assertTrue(tmpl.parts.any { it is StringTemplatePart.Interpolated })
    }

    // -------------------------------------------------------------------------
    // Pipeline stages
    // -------------------------------------------------------------------------

    @Test fun `FILTER pipeline stage`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE active-items = items FILTER WHERE active
            END-PROCEDURE
        """.trimIndent())
        val expr = assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr
        assertIs<PipelineExpr>(expr)
    }

    @Test fun `SORT BY DESCENDING pipeline`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE sorted = items SORT BY score DESCENDING
            END-PROCEDURE
        """.trimIndent())
        val pipe = assertIs<PipelineExpr>(assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr)
        val sort = assertIs<PipelineStage.SortStage>(pipe.stages[0])
        assertTrue(sort.descending)
    }

    @Test fun `TAKE pipeline stage`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE top = items TAKE 5
            END-PROCEDURE
        """.trimIndent())
        val pipe = assertIs<PipelineExpr>(assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr)
        assertIs<PipelineStage.TakeStage>(pipe.stages[0])
    }

    @Test fun `chained pipeline stages`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE result = items FILTER WHERE active SORT BY score DESCENDING TAKE 10
            END-PROCEDURE
        """.trimIndent())
        val pipe = assertIs<PipelineExpr>(assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr)
        assertEquals(3, pipe.stages.size)
    }

    // -------------------------------------------------------------------------
    // Prose numeric builtins + SPLIT (#v2) and list indexing (#v7)
    // -------------------------------------------------------------------------

    private fun computeExpr(src: String): Expression {
        val prog = parse(src.trimIndent())
        return assertIs<ComputeStatement>(prog.procedures[0].body[0]).expr
    }

    @Test fun `prose ROUND TO lowers to ROUND builtin`() {
        val e = computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = ROUND amount TO 2
        """)
        val call = assertIs<BuiltinCall>(e)
        assertEquals("ROUND", call.name)
        assertEquals(2, call.args.size)
    }

    @Test fun `prose ROUND TO USING lowers to ROUND-WITH-MODE`() {
        val call = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = ROUND amount TO 2 USING HALF-UP
        """))
        assertEquals("ROUND-WITH-MODE", call.name)
        assertEquals(3, call.args.size)
        val mode = assertIs<Literal>(call.args[2])
        assertEquals("HALF-UP", mode.value)
    }

    @Test fun `prose ROUND head is a full expression up to TO`() {
        // `ROUND base * pct TO 2` — the value before TO must be `base * pct`, not just `base`.
        val call = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = ROUND base * pct TO 2
        """))
        assertEquals("ROUND", call.name)
        assertIs<BinaryExpr>(call.args[0])
    }

    @Test fun `prose MOD BY and POWER BY lower to builtins`() {
        val mod = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = MOD total BY divisor
        """))
        assertEquals("MOD", mod.name); assertEquals(2, mod.args.size)
        val pow = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = POWER base BY exponent
        """))
        assertEquals("POWER", pow.name); assertEquals(2, pow.args.size)
    }

    @Test fun `prose MAX takes exactly two comma operands`() {
        val mx = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = MAX a, b
        """))
        assertEquals("MAX", mx.name); assertEquals(2, mx.args.size)
    }

    @Test fun `prose SQRT unary still binds outer operator`() {
        // `SQRT x + 1` → `SQRT(x) + 1`, mirroring the prose-string LENGTH precedent.
        val e = computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = SQRT x + 1
        """)
        val add = assertIs<BinaryExpr>(e)
        assertEquals(BinaryOp.ADD, add.op)
        assertEquals("SQRT", assertIs<BuiltinCall>(add.left).name)
    }

    @Test fun `prose SPLIT BY with LIMIT lowers to SPLIT builtin`() {
        val s = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = SPLIT line BY "," LIMIT 3
        """))
        assertEquals("SPLIT", s.name); assertEquals(3, s.args.size)
    }

    @Test fun `the call form still parses after adding prose forms`() {
        val call = assertIs<BuiltinCall>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = ROUND(amount, 2)
        """))
        assertEquals("ROUND", call.name); assertEquals(2, call.args.size)
    }

    @Test fun `list index parses to IndexExpr`() {
        val idx = assertIs<IndexExpr>(computeExpr("""
            PROGRAM T
            PROCEDURE M:
              COMPUTE r = fields[1]
        """))
        assertEquals(listOf("FIELDS"), assertIs<Reference>(idx.target).parts)
        assertEquals(1L, assertIs<Literal>(idx.index).value)
    }

    // -------------------------------------------------------------------------
    // PERFORM with arguments
    // -------------------------------------------------------------------------

    @Test fun `PERFORM with USING arguments`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              PERFORM Greet USING "Alice"
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<PerformStatement>(prog.procedures[0].body[0])
        assertEquals(1, stmt.args.size)
    }

    @Test fun `procedure RETURNING type`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE Square USING n : INTEGER RETURNING INTEGER:
              RETURN n * n
            END-PROCEDURE
        """.trimIndent())
        val proc = prog.procedures[0]
        assertNotNull(proc.returnType)
        assertIs<TypeSpec.IntegerType>(proc.returnType)
    }

    // -------------------------------------------------------------------------
    // FILES section
    // -------------------------------------------------------------------------

    @Test fun `FILES section parses`() {
        val p = parse("""
            PROGRAM T
            FILES:
              DataFile AS SEQUENTIAL CSV RECORD Customer
            PROCEDURE M:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertNotNull(p.fileSection)
        assertEquals(1, p.fileSection!!.files.size)
        assertEquals("DATAFILE", p.fileSection!!.files[0].name)
    }

    // -------------------------------------------------------------------------
    // Multiple procedures and records
    // -------------------------------------------------------------------------

    @Test fun `multiple procedures`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE A:
              STOP RUN
            END-PROCEDURE
            PROCEDURE B:
              STOP RUN
            END-PROCEDURE
            PROCEDURE Main:
              DO A
              DO B
            END-PROCEDURE
        """.trimIndent())
        assertEquals(3, p.procedures.size)
    }

    @Test fun `multiple records`() {
        val p = parse("""
            PROGRAM T
            RECORD Invoice:
              id : INTEGER
            END-RECORD
            RECORD Summary:
              count : INTEGER
            END-RECORD
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals(2, p.records.size)
    }

    @Test fun `STOP RUN with exit code`() {
        val prog = parse("""
            PROGRAM T
            PROCEDURE M:
              STOP RUN WITH EXIT-CODE 1
            END-PROCEDURE
        """.trimIndent())
        val stmt = assertIs<StopRunStatement>(prog.procedures[0].body[0])
        assertNotNull(stmt.exitCode)
    }

    @Test fun `script mode with LET and DISPLAY`() {
        val p = parse("LET name = \"World\"\nDISPLAY \"Hello, {name}!\"")
        assertEquals(1, p.procedures.size)
        val stmts = p.procedures[0].body
        assertIs<ComputeStatement>(stmts[0])
        assertIs<DisplayStatement>(stmts[1])
    }

    // -------------------------------------------------------------------------
    // EXPORT PROCEDURE
    // -------------------------------------------------------------------------

    @Test fun `EXPORT PROCEDURE sets exported flag`() {
        val p = parse("""
            PROGRAM MyLib
            EXPORT PROCEDURE Calculate USING a : INTEGER, b : INTEGER RETURNING INTEGER:
              RETURN a + b
            END-PROCEDURE
        """.trimIndent())
        assertEquals(1, p.procedures.size)
        val proc = p.procedures[0]
        assertEquals("CALCULATE", proc.name)
        assertTrue(proc.exported, "EXPORT PROCEDURE should set exported = true")
    }

    @Test fun `plain PROCEDURE is not exported`() {
        val p = parse("""
            PROGRAM MyLib
            PROCEDURE Helper:
              DISPLAY "private"
            END-PROCEDURE
        """.trimIndent())
        val proc = p.procedures[0]
        assertTrue(!proc.exported, "plain PROCEDURE should have exported = false")
    }

    @Test fun `mixed exported and private procedures`() {
        val p = parse("""
            PROGRAM MyLib
            EXPORT PROCEDURE PublicCalc USING a : INTEGER, b : INTEGER RETURNING INTEGER:
              RETURN a + b
            END-PROCEDURE
            PROCEDURE PrivateHelper:
              DISPLAY "internal"
            END-PROCEDURE
            EXPORT PROCEDURE Greet USING name : TEXT RETURNING TEXT:
              RETURN "Hello, {name}!"
            END-PROCEDURE
        """.trimIndent())
        assertEquals(3, p.procedures.size)
        assertTrue(p.procedures[0].exported,  "PublicCalc should be exported")
        assertTrue(!p.procedures[1].exported, "PrivateHelper should not be exported")
        assertTrue(p.procedures[2].exported,  "Greet should be exported")
    }

    // -------------------------------------------------------------------------
    // Built-In Testing Framework (Phase 13)
    // -------------------------------------------------------------------------

    @Test fun `TEST block parses to TestDecl`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
            TEST "add returns two":
              ASSERT 1 + 1 = 2
            END-TEST
        """.trimIndent())
        assertEquals(1, p.tests.size)
        assertEquals("add returns two", p.tests[0].name)
    }

    @Test fun `TEST block body contains AssertStatement`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
            TEST "check assert":
              ASSERT 1 = 1
            END-TEST
        """.trimIndent())
        val body = p.tests[0].body
        assertEquals(1, body.size)
        assertIs<AssertStatement>(body[0])
    }

    @Test fun `MOCK statement parses inside TEST block`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE GetValue RETURNING INTEGER:
              RETURN 0
            END-PROCEDURE
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
            TEST "mock works":
              MOCK GetValue RETURNS 99
              ASSERT GetValue() = 99
            END-TEST
        """.trimIndent())
        assertEquals(1, p.tests.size)
        val body = p.tests[0].body
        assertIs<MockStatement>(body[0])
        assertEquals("GETVALUE", (body[0] as MockStatement).procedureName)
    }

    @Test fun `TABLE TEST parses to TableTestDecl`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
            TEST TABLE "addition":
              COLUMNS: a, b
              ROW: 1, 2
              ROW: 3, 4
              WHEN:
                DISPLAY a
              THEN:
                ASSERT a < b
            END-TEST
        """.trimIndent())
        assertEquals(1, p.tableTests.size)
        val tt = p.tableTests[0]
        assertEquals("addition", tt.name)
        assertEquals(listOf("A", "B"), tt.columns)
        assertEquals(2, tt.rows.size)
    }

    // -------------------------------------------------------------------------
    // Challenge #3 — DEFINE TYPE Name IS <type> (the IS-keyword parse branch)
    // -------------------------------------------------------------------------

    @Test fun `DEFINE TYPE alias parses (IS is a keyword)`() {
        val p = parse("""
            PROGRAM T
            DEFINE TYPE Rate IS DECIMAL(18, 8)
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals(1, p.typeAliases.size)
        assertEquals("RATE", p.typeAliases[0].name)
    }

    // -------------------------------------------------------------------------
    // Challenge #2 — DIVIDE accepts both BY (English) and INTO (COBOL) directions
    // -------------------------------------------------------------------------

    @Test fun `DIVIDE BY GIVING parses with English direction`() {
        val p = parse("""
            PROGRAM T
            DATA:
              total : DECIMAL(10, 2)
              count : DECIMAL(10, 2)
              avg   : DECIMAL(10, 2)
            PROCEDURE Main:
              DIVIDE total BY count GIVING avg
            END-PROCEDURE
        """.trimIndent())
        val div = p.procedures[0].body[0]
        assertIs<DivideStatement>(div)
        // result = into / divisor → into is the dividend (total), divisor is count
        assertEquals(listOf("TOTAL"), div.into.parts)
        assertEquals(listOf("COUNT"), (div.divisor as Reference).parts)
        assertEquals(listOf("AVG"), div.giving?.parts)
    }

    @Test fun `DIVIDE INTO still parses (no regression)`() {
        val p = parse("""
            PROGRAM T
            DATA:
              total : DECIMAL(10, 2)
              avg   : DECIMAL(10, 2)
            PROCEDURE Main:
              DIVIDE 4 INTO total GIVING avg
            END-PROCEDURE
        """.trimIndent())
        val div = p.procedures[0].body[0]
        assertIs<DivideStatement>(div)
        assertEquals(listOf("TOTAL"), div.into.parts)
    }

    // -------------------------------------------------------------------------
    // F20 — async surface: the IMPLEMENTED form is `PERFORM … GIVING fut` +
    // `AWAIT fut INTO r`. The old §18.4 prose showed `ASYNC DO …` + `AWAIT …
    // GIVING` — neither parses. These tests lock the real surface and prove the
    // drifted forms are rejected, so the spec was corrected to match (not the
    // parser extended with a second, redundant way to launch async).
    // -------------------------------------------------------------------------

    @Test fun `async PERFORM GIVING then AWAIT INTO parses (real async surface, F20)`() {
        val p = parse("""
            PROGRAM T
            DATA:
              fut : FUTURE OF INTEGER
              r   : INTEGER
            ASYNC PROCEDURE Fetch RETURNING INTEGER:
              RETURN 42
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM Fetch GIVING fut
              AWAIT fut INTO r
            END-PROCEDURE
        """.trimIndent())
        val body = p.procedures.first { it.name == "MAIN" }.body
        val perform = body[0]
        assertIs<PerformStatement>(perform)
        assertEquals(listOf("FUT"), perform.giving?.parts)
        val await = body[1]
        assertIs<AwaitStatement>(await)
        assertEquals(listOf("FUT"), await.future.parts)
        assertEquals(listOf("R"), await.into.parts)
    }

    @Test fun `the drifted ASYNC DO launch form does not parse (F20)`() {
        assertThrows<ParseException> {
            parse("""
                PROGRAM T
                PROCEDURE Main:
                  LET task = ASYNC DO Fetch USING "EUR"
                END-PROCEDURE
            """.trimIndent())
        }
    }

    @Test fun `the drifted AWAIT GIVING form does not parse (F20)`() {
        assertThrows<ParseException> {
            parse("""
                PROGRAM T
                DATA:
                  task : FUTURE OF INTEGER
                  rate : INTEGER
                PROCEDURE Main:
                  AWAIT task GIVING rate
                END-PROCEDURE
            """.trimIndent())
        }
    }

    // -------------------------------------------------------------------------
    // #v9 — MATCH guard via WITH (§22.4) vs deconstruction binding (§22.3)
    // -------------------------------------------------------------------------

    private fun firstWhenPattern(src: String): MatchPattern {
        val match = parse(src).procedures.first().body.filterIsInstance<MatchStatement>().first()
        return match.whenClauses.first().pattern
    }

    @Test fun `WHEN Type WITH boolean expr parses as a guard (v9)`() {
        val pattern = firstWhenPattern("""
            PROGRAM T
            PROCEDURE Main:
              MATCH inv:
                WHEN Invoice WITH amount > 10000 AND NOT paid:
                  DISPLAY "x"
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
        val guard = assertIs<MatchPattern.GuardPattern>(pattern)
        assertIs<MatchPattern.VariantPattern>(guard.inner)
        assertEquals(0, (guard.inner as MatchPattern.VariantPattern).bindings.size)
        assertIs<BinaryExpr>(guard.guard)   // the AND
    }

    @Test fun `WHEN Type WITH identifier list still parses as a deconstruction binding (v9)`() {
        val pattern = firstWhenPattern("""
            PROGRAM T
            PROCEDURE Main:
              MATCH s:
                WHEN Active WITH order-date:
                  DISPLAY "x"
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
        val variant = assertIs<MatchPattern.VariantPattern>(pattern)
        assertEquals(listOf("ORDER-DATE"), variant.bindings)  // identifiers are upper-cased in the AST
    }

    @Test fun `WHEN Type WITH NOT field parses as a guard, not a binding (v9)`() {
        val pattern = firstWhenPattern("""
            PROGRAM T
            PROCEDURE Main:
              MATCH inv:
                WHEN Invoice WITH NOT paid:
                  DISPLAY "x"
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
        val guard = assertIs<MatchPattern.GuardPattern>(pattern)
        assertIs<UnaryExpr>(guard.guard)
    }
}
