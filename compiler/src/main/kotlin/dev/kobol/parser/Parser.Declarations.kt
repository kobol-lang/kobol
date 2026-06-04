package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.SourcePosition
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

internal fun Parser.parseImport(): ImportDecl {
        val p = currentPos()
        expect(IMPORT, "Expected IMPORT")
        // rawValue preserves original case for Java class names: "LocalDate" not "LOCALDATE"
        val parts = mutableListOf(advance().rawValue)
        while (check(DOT)) { advance(); parts.add(advance().rawValue) }
        val versionConstraint = if (check(VERSION)) { advance(); advance().value.trim('"') } else null
        val alias = if (match(AS)) expectIdent("Expected alias") else null
        return ImportDecl(parts.joinToString("."), alias, versionConstraint, p)
    }

    // -------------------------------------------------------------------------
    // Module declaration
    // -------------------------------------------------------------------------

internal fun Parser.parseModuleDecl(): ModuleDecl {
        val p = currentPos()
        expect(MODULE, "Expected MODULE")
        val parts = mutableListOf(expectAnyIdentifier("Expected module name"))
        while (check(DOT)) { advance(); parts.add(expectAnyIdentifier("Expected module part")) }
        val version = if (check(VERSION)) { advance(); advance().value.trim('"') } else null
        expect(COLON, "Expected ':' after module declaration")
        val exports = mutableListOf<ExportDecl>()
        while (!check(END_MODULE) && !check(EOF)) {
            while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
            if (check(END_MODULE) || check(EOF)) break
            if (check(EXPORT)) exports.add(parseExportDecl())
            else break
        }
        if (check(END_MODULE)) advance()
        return ModuleDecl(parts.joinToString("."), version, exports, p)
    }

internal fun Parser.parseExportDecl(): ExportDecl {
        val p = currentPos()
        expect(EXPORT, "Expected EXPORT")
        val kind = when {
            check(PROCEDURE) -> { advance(); "PROCEDURE" }
            check(RECORD)    -> { advance(); "RECORD" }
            check(VARIANT)   -> { advance(); "VARIANT" }
            matchKeywordValue("TYPE") -> "TYPE"
            else -> throw error("Expected PROCEDURE, RECORD, VARIANT, or TYPE after EXPORT")
        }
        val name = expectIdent("Expected name after $kind in EXPORT")
        return ExportDecl(kind, name, p)
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

internal fun Parser.parseRecord(): RecordDecl {
        val p = currentPos()
        expect(RECORD, "Expected RECORD")
        val name = expectIdent("Expected record name")
        expect(COLON, "Expected ':' after record name")
        skipWs()
        val fields = mutableListOf<FieldDecl>()
        if (match(INDENT)) {
            while (!check(DEDENT) && !check(EOF) && !check(END_RECORD)) {
                skipWs()
                if (check(DEDENT) || check(EOF) || check(END_RECORD)) break
                fields.add(parseFieldDecl())
                skipWs()
            }
            if (check(DEDENT)) advance()
        }
        if (check(END_RECORD)) advance()
        return RecordDecl(name, fields, p)
    }

internal fun Parser.parseFieldDecl(): FieldDecl {
        val p    = currentPos()
        val name = expectIdent("Expected field name")
        expect(COLON, "Expected ':' after field name")
        val type = parseTypeSpec()
        skipWs()
        val conditions = mutableListOf<ConditionDecl>()
        // CONDITION sub-blocks may be indented further (INDENT token precedes them)
        if (check(INDENT)) {
            advance()
            while (check(CONDITION)) conditions.add(parseConditionDecl())
            if (check(DEDENT)) advance()
        } else {
            while (check(CONDITION)) conditions.add(parseConditionDecl())
        }
        return FieldDecl(name, type, conditions, p)
    }

internal fun Parser.parseConditionDecl(): ConditionDecl {
        val p = currentPos()
        expect(CONDITION, "Expected CONDITION")
        val name = expectIdent("Expected condition name")
        expect(WHERE, "Expected WHEN after condition name")
        val expr = parseExpression()
        return ConditionDecl(name, expr, p)
    }

    // CONDITION name WHEN expr  (top-level named boolean expression)
internal fun Parser.parseNamedConditionDecl(): NamedConditionDecl {
        val p = currentPos()
        expect(CONDITION, "Expected CONDITION")
        val name = expectIdent("Expected condition name")
        expect(WHERE, "Expected WHEN after condition name")
        val expr = parseExpression()
        return NamedConditionDecl(name, expr, p)
    }

    // -------------------------------------------------------------------------
    // Type specifications
    // -------------------------------------------------------------------------

internal fun Parser.parseTypeSpec(): TypeSpec {
        val p = currentPos()
        return when {
            match(INTEGER)  -> TypeSpec.IntegerType(p)
            match(SMALLINT) -> TypeSpec.SmallIntType(p)
            match(BOOLEAN)  -> TypeSpec.BooleanType(p)
            match(DATE)     -> TypeSpec.DateType(p)
            match(TIME)     -> TypeSpec.TimeType(p)
            match(DATETIME) -> TypeSpec.DateTimeType(p)

            match(DECIMAL)  -> {
                val (prec, scale) = parsePrecisionScale(p)
                TypeSpec.DecimalType(prec, scale, p)
            }
            match(MONEY)    -> {
                val (prec, scale) = parsePrecisionScale(p)
                TypeSpec.MoneyType(prec, scale, p)
            }
            match(TEXT)     -> {
                val maxLen = if (check(LPAREN)) { advance(); val n = expectInt(); expect(RPAREN,"Expected ')'"); n } else null
                val sensitive = if (check(SENSITIVE)) { advance(); true } else false
                TypeSpec.TextType(maxLen, sensitive, p)
            }
            match(LIST)     -> { expect(OF, "Expected OF after LIST"); TypeSpec.ListOf(parseTypeSpec(), p) }
            match(FUTURE)   -> { expect(OF, "Expected OF after FUTURE"); TypeSpec.FutureOf(parseTypeSpec(), p) }
            match(MAP)      -> {
                expect(OF, "Expected OF after MAP")
                val keyT = parseTypeSpec()
                expect(TO, "Expected TO in MAP OF K TO V")
                TypeSpec.MapOf(keyT, parseTypeSpec(), p)
            }
            match(UUID)     -> TypeSpec.UuidType(p)
            check(IDENTIFIER) || isSoftKeyword(peek().type) -> TypeSpec.NamedType(advance().value, p)
            else -> throw error("Expected type specification, got '${peek().value}'")
        }
    }

internal fun Parser.parsePrecisionScale(p: SourcePosition): Pair<Int, Int> {
        if (!check(LPAREN)) return Pair(10, 2)
        expect(LPAREN, "Expected '('")
        // Handle compact form MONEY(12.2) where lexer emits a single DECIMAL_LIT "12.2"
        if (check(DECIMAL_LIT)) {
            val v = advance().value
            val dot = v.indexOf('.')
            val prec = if (dot >= 0) v.substring(0, dot).toIntOrNull() ?: 10 else v.toIntOrNull() ?: 10
            val scale = if (dot >= 0) v.substring(dot + 1).toIntOrNull() ?: 0 else 0
            expect(RPAREN, "Expected ')'")
            return Pair(prec, scale)
        }
        val prec = expectInt()
        val sep  = if (check(COMMA)) { advance(); true } else if (check(DOT)) { advance(); true } else false
        val scale = if (sep) expectInt() else 0
        expect(RPAREN, "Expected ')'")
        return Pair(prec, scale)
    }

    // -------------------------------------------------------------------------
    // Type aliases  —  DEFINE TYPE Name IS typespec
    // -------------------------------------------------------------------------

internal fun Parser.parseTypeAlias(): TypeAliasDecl {
        val p = currentPos()
        expect(DEFINE, "Expected DEFINE")
        // consume the bare "TYPE" identifier
        if (!check(IDENTIFIER) || peek().value.uppercase() != "TYPE") throw error("Expected TYPE after DEFINE")
        advance()
        val name = expectIdent("Expected type alias name")
        if (!check(IDENTIFIER) || peek().value.uppercase() != "IS") throw error("Expected IS after type alias name")
        advance()
        val target = parseTypeSpec()
        return TypeAliasDecl(name, target, p)
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

internal fun Parser.parseConstant(): ConstantDecl {
        val p = currentPos()
        expect(DEFINE, "Expected DEFINE")
        val name = expectIdent("Expected constant name")
        expect(COLON, "Expected ':' after constant name")
        val type = parseTypeSpec()
        expect(EQ, "Expected '=' after type")
        val value = parseLiteral() ?: throw error("Expected literal value for constant")
        return ConstantDecl(name, type, value, p)
    }

    // -------------------------------------------------------------------------
    // Data section
    // -------------------------------------------------------------------------

internal fun Parser.parseDataSection(): DataSection {
        val p = currentPos()
        expect(DATA, "Expected DATA")
        expect(COLON, "Expected ':' after DATA")
        skipWs()
        val items = mutableListOf<DataItem>()
        if (match(INDENT)) {
            while (!check(DEDENT) && !check(EOF)) {
                skipWs()
                if (check(DEDENT) || check(EOF)) break
                items.add(parseDataItem())
                skipWs()
            }
            if (check(DEDENT)) advance()
        }
        return DataSection(items, p)
    }

internal fun Parser.parseDataItem(): DataItem {
        val p    = currentPos()
        val name = expectIdent("Expected variable name")
        // Support type-inferred form: `name = expr`
        if (check(EQ)) {
            advance()
            val init = parseExpression()
            return DataItem(name, null, init, p)
        }
        expect(COLON, "Expected ':' after variable name")
        val type = parseTypeSpec()
        val init = if (match(EQ)) parseExpression() else null
        return DataItem(name, type, init, p)
    }

    // -------------------------------------------------------------------------
    // File section
    // -------------------------------------------------------------------------

internal fun Parser.parseFileSection(): FileSection {
        val p = currentPos()
        expect(FILES, "Expected FILES")
        expect(COLON, "Expected ':' after FILES")
        skipWs()
        val files = mutableListOf<FileDecl>()
        if (match(INDENT)) {
            while (!check(DEDENT) && !check(EOF)) {
                skipWs(); if (check(DEDENT) || check(EOF)) break
                files.add(parseFileDecl()); skipWs()
            }
            if (check(DEDENT)) advance()
        }
        return FileSection(files, p)
    }

internal fun Parser.parseFileDecl(): FileDecl {
        val p       = currentPos()
        val rawName = peek().rawValue
        val name    = expectIdent("Expected file name")
        expect(AS, "Expected AS")
        val org    = when {
            matchKeywordValue("SEQUENTIAL") -> FileOrg.SEQUENTIAL
            matchKeywordValue("INDEXED")    -> FileOrg.INDEXED
            else                            -> FileOrg.SEQUENTIAL
        }
        val format = when {
            peek().value == "CSV"   -> { advance(); FileFormat.CSV   }
            peek().value == "FIXED" -> { advance(); FileFormat.FIXED }
            peek().value == "BINARY"-> { advance(); FileFormat.BINARY }
            peek().value == "TEXT"  -> { advance(); FileFormat.TEXT  }
            else                    -> FileFormat.TEXT
        }
        expect(RECORD, "Expected RECORD")
        val recType = expectIdent("Expected record type")
        val mode    = when { match(FOR) -> when { match(INPUT) -> FileMode.INPUT; match(OUTPUT) -> FileMode.OUTPUT; match(EXTEND) -> FileMode.EXTEND; else -> null }; else -> null }
        val key     = if (match(KEY)) expectIdent("Expected key field") else null
        return FileDecl(name, org, format, recType, mode, key, p, rawName)
    }

    // -------------------------------------------------------------------------
    // Procedure
    // -------------------------------------------------------------------------

internal fun Parser.parseVariantDecl(): VariantDecl {
        val p = currentPos()
        expect(VARIANT, "Expected VARIANT")
        val name = expectIdent("Expected variant type name")
        if (peek().value != "IS") throw error("Expected IS after variant name")
        advance() // IS
        skipWs()
        val cases = mutableListOf<VariantCase>()
        // Skip optional leading pipe
        if (check(LBRACKET)) advance()  // not expected but handle gracefully
        // Parse: [|] CaseName [WITH field: Type, ...] [| CaseName ...]
        fun parseCase(): VariantCase {
            val cp = currentPos()
            val caseName = expectIdent("Expected variant case name")
            val fields = mutableListOf<FieldDecl>()
            if (match(WITH)) {
                fields.add(parseFieldDecl())
                while (check(COMMA)) { advance(); fields.add(parseFieldDecl()) }
            }
            return VariantCase(caseName, fields, cp)
        }
        // Skip INDENT if present
        if (check(INDENT)) advance()
        while (!check(DEDENT) && !check(END_VARIANT) && !isAtEnd()) {
            skipWs()
            if (check(DEDENT) || check(END_VARIANT) || isAtEnd()) break
            if (check(PIPE)) advance()  // | separator between cases
            skipWs()
            // Check for IDENTIFIER (start of a case name)
            if (!check(IDENTIFIER)) break
            cases.add(parseCase())
            skipWs()
        }
        if (check(DEDENT)) advance()
        if (check(END_VARIANT)) advance()
        return VariantDecl(name, cases, p)
    }

    // MATCH expr: WHEN pattern: body ... [OTHERWISE: body] END-MATCH
