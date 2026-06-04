package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.hasLogStatements(program: Program): Boolean {
        fun scanStmts(stmts: List<Statement>): Boolean = stmts.any { stmt ->
            stmt is LogStatement ||
            (stmt is IfStatement && (scanStmts(stmt.thenBranch) || scanStmts(stmt.elseBranch ?: emptyList()) || stmt.elseIfClauses.any { scanStmts(it.body) })) ||
            (stmt is WhileStatement && scanStmts(stmt.body)) ||
            (stmt is ForEachStatement && scanStmts(stmt.body)) ||
            (stmt is RepeatStatement && scanStmts(stmt.body)) ||
            (stmt is TryStatement && (scanStmts(stmt.body) || stmt.handlers.any { scanStmts(it.body) })) ||
            (stmt is ConcurrentBlock && stmt.branches.any { scanStmts(it) })
        }
        return program.procedures.any { scanStmts(it.body) }
    }

    // MethodContext local variable entry
    data class LocalVar(val name: String, val type: KobolType, val slot: Int)

    // =========================================================================
    //  Group 13 — NoSQL (MongoDB) emit helpers
    // =========================================================================

internal fun AsmEmitter.emitNoSqlConnect(ctx: MethodContext, stmt: NoSqlConnectStatement) {
        emitExpr(ctx, stmt.url)
        emitExpr(ctx, stmt.database)
        ctx.mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "connect", "(Ljava/lang/String;Ljava/lang/String;)V", false)
    }

internal fun AsmEmitter.emitNoSqlFind(ctx: MethodContext, stmt: NoSqlFindStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.collection)
        emitMongoFilter(ctx, stmt.filter)
        if (stmt.findOne) {
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "findOne",
                "(Ljava/lang/String;$BSON_DESC)Ljava/lang/Object;", false)
        } else {
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "find",
                "(Ljava/lang/String;$BSON_DESC)Ljava/util/List;", false)
        }
        emitStore(ctx, stmt.giving)
    }

internal fun AsmEmitter.emitNoSqlSave(ctx: MethodContext, stmt: NoSqlSaveStatement) {
        val mv = ctx.mv
        if (stmt.upsert) {
            // upsert(collectionName, filter, document)
            emitExpr(ctx, stmt.collection)
            mv.visitInsn(ACONST_NULL)  // filter = null (match-all insert-or-replace)
            loadRef(ctx, stmt.document)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "upsert",
                "(Ljava/lang/String;${BSON_DESC}Ljava/util/Map;)V", false)
        } else {
            // save(collectionName, document)
            emitExpr(ctx, stmt.collection)
            loadRef(ctx, stmt.document)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "save",
                "(Ljava/lang/String;Ljava/util/Map;)V", false)
        }
    }

internal fun AsmEmitter.emitNoSqlDelete(ctx: MethodContext, stmt: NoSqlDeleteStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.collection)
        emitMongoFilter(ctx, stmt.filter)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "delete", "(Ljava/lang/String;$BSON_DESC)J", false)
        mv.visitInsn(POP2) // discard deleted-count
    }

internal fun AsmEmitter.emitNoSqlCount(ctx: MethodContext, stmt: NoSqlCountStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.collection)
        emitMongoFilter(ctx, stmt.filter)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "count", "(Ljava/lang/String;$BSON_DESC)J", false)
        emitStore(ctx, stmt.giving)
    }

    /**
     * Emit bytecode to produce a [org.bson.conversions.Bson] filter on the stack.
     * Null expr → pushes null (match-all).
     * BinaryExpr with comparison ops → Filters.eq / lt / gt / ne / lte / gte.
     * BinaryExpr AND / OR → Filters.and / or with a 2-element Bson array.
     */
internal fun AsmEmitter.emitMongoFilter(ctx: MethodContext, expr: Expression?) {
        val mv = ctx.mv
        if (expr == null) { mv.visitInsn(ACONST_NULL); return }
        when {
            expr is BinaryExpr && expr.op == BinaryOp.AND -> {
                // Filters.and(Bson[]) — build a 2-element array
                mv.visitInsn(ICONST_2)
                mv.visitTypeInsn(ANEWARRAY, "org/bson/conversions/Bson")
                mv.visitInsn(DUP)
                mv.visitInsn(ICONST_0)
                emitMongoFilter(ctx, expr.left)
                mv.visitInsn(AASTORE)
                mv.visitInsn(DUP)
                mv.visitInsn(ICONST_1)
                emitMongoFilter(ctx, expr.right)
                mv.visitInsn(AASTORE)
                mv.visitMethodInsn(INVOKESTATIC, FILTERS, "and",
                    "([Lorg/bson/conversions/Bson;)$BSON_DESC", false)
            }
            expr is BinaryExpr && expr.op == BinaryOp.OR -> {
                mv.visitInsn(ICONST_2)
                mv.visitTypeInsn(ANEWARRAY, "org/bson/conversions/Bson")
                mv.visitInsn(DUP)
                mv.visitInsn(ICONST_0)
                emitMongoFilter(ctx, expr.left)
                mv.visitInsn(AASTORE)
                mv.visitInsn(DUP)
                mv.visitInsn(ICONST_1)
                emitMongoFilter(ctx, expr.right)
                mv.visitInsn(AASTORE)
                mv.visitMethodInsn(INVOKESTATIC, FILTERS, "or",
                    "([Lorg/bson/conversions/Bson;)$BSON_DESC", false)
            }
            expr is BinaryExpr && expr.op in COMPARISON_OPS -> {
                val fieldName = mongoFieldName(expr.left)
                mv.visitLdcInsn(fieldName)
                emitExprBoxed(ctx, expr.right)
                val methodName = when (expr.op) {
                    BinaryOp.EQ  -> "eq"
                    BinaryOp.NEQ -> "ne"
                    BinaryOp.LT  -> "lt"
                    BinaryOp.GT  -> "gt"
                    BinaryOp.LEQ -> "lte"
                    BinaryOp.GEQ -> "gte"
                    else         -> "eq"
                }
                mv.visitMethodInsn(INVOKESTATIC, FILTERS, methodName,
                    "(Ljava/lang/String;Ljava/lang/Object;)$BSON_DESC", false)
            }
            else -> {
                // Fallback: emit as a literal boolean expression and use a pass-all null
                mv.visitInsn(ACONST_NULL)
            }
        }
    }

    /** Derive the MongoDB field name from a Kobol Reference (e.g. STATUS → "status", CUSTOMER-NAME → "customerName"). */
internal fun AsmEmitter.mongoFieldName(expr: Expression): String =
        if (expr is Reference) expr.parts.joinToString(".") { javaIdent(it) }
        else "value"

    /** Emit expr and box primitive types to their Object wrapper for Object-typed API calls. */
internal fun AsmEmitter.emitExprBoxed(ctx: MethodContext, expr: Expression) {
        emitExpr(ctx, expr)
        when (inferExprType(expr)) {
            is KobolType.IntegerType ->
                ctx.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            is KobolType.BooleanType ->
                ctx.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            is KobolType.DecimalType ->
                ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/math/BigDecimal", "toString", "()Ljava/lang/String;", false)
            else -> {} // reference type, already an Object
        }
    }

    // =========================================================================
    //  Group 14 — Cache (Redis) emit helpers
    // =========================================================================

internal fun AsmEmitter.emitCacheGet(ctx: MethodContext, stmt: CacheGetStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.key)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "get", "(Ljava/lang/String;)Ljava/lang/String;", false)
        emitStore(ctx, stmt.giving)
    }

internal fun AsmEmitter.emitCacheSet(ctx: MethodContext, stmt: CacheSetStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.key)
        emitExpr(ctx, stmt.value)
        if (stmt.ttlSeconds != null) {
            emitExpr(ctx, stmt.ttlSeconds)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "setEx",
                "(Ljava/lang/String;Ljava/lang/String;J)V", false)
        } else {
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "set",
                "(Ljava/lang/String;Ljava/lang/String;)V", false)
        }
    }

internal fun AsmEmitter.emitCacheExists(ctx: MethodContext, stmt: CacheExistsStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.key)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "exists", "(Ljava/lang/String;)Z", false)
        emitStore(ctx, stmt.giving)
    }

    // -------------------------------------------------------------------------
    // MethodContext — local variable registry per method
    // -------------------------------------------------------------------------

