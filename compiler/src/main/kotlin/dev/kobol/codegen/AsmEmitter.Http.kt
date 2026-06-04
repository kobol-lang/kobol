package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.emitHttpCall(ctx: MethodContext, stmt: HttpCallStatement) {
        val mv   = ctx.mv
        val HTTP = "dev/kobol/stdlib/KobolHttp"
        val S    = "Ljava/lang/String;"
        val J    = "J"
        // All JDK http methods take (String url, String headers, [String body,] long timeout): String
        emitExpr(ctx, stmt.url)
        // headers — null if absent
        if (stmt.headers != null) emitExpr(ctx, stmt.headers) else mv.visitInsn(ACONST_NULL)
        when (stmt.httpMethod) {
            "GET", "DELETE" -> {
                // (String, String, long) → String
                if (stmt.timeout != null) emitExpr(ctx, stmt.timeout) else { mv.visitLdcInsn(30L) }
                val mname = stmt.httpMethod.lowercase()
                mv.visitMethodInsn(INVOKESTATIC, HTTP, mname, "($S${S}J)$S", false)
            }
            else -> {
                // POST/PUT/PATCH: (String url, String headers, String body, long timeout): String
                if (stmt.body != null) emitExpr(ctx, stmt.body) else mv.visitInsn(ACONST_NULL)
                if (stmt.timeout != null) emitExpr(ctx, stmt.timeout) else { mv.visitLdcInsn(30L) }
                val mname = stmt.httpMethod.lowercase()
                mv.visitMethodInsn(INVOKESTATIC, HTTP, mname, "($S${S}${S}J)$S", false)
            }
        }
        if (stmt.giving != null) emitStore(ctx, stmt.giving)
        else mv.visitInsn(POP)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 11 — JDBC bridge
    // ─────────────────────────────────────────────────────────────────────────

internal fun AsmEmitter.emitJdbcConnect(ctx: MethodContext, stmt: JdbcConnectStatement) {
        val mv   = ctx.mv
        val JDBC = "dev/kobol/stdlib/KobolJdbc"
        val S    = "Ljava/lang/String;"
        emitExpr(ctx, stmt.url)
        if (stmt.user != null) emitExpr(ctx, stmt.user) else mv.visitInsn(ACONST_NULL)
        if (stmt.password != null) emitExpr(ctx, stmt.password) else mv.visitInsn(ACONST_NULL)
        mv.visitMethodInsn(INVOKESTATIC, JDBC, "connect", "($S${S}${S})V", false)
    }

internal fun AsmEmitter.emitJdbcQuery(ctx: MethodContext, stmt: JdbcQueryStatement) {
        val mv   = ctx.mv
        val JDBC = "dev/kobol/stdlib/KobolJdbc"
        val S    = "Ljava/lang/String;"
        val ARR  = "[Ljava/lang/Object;"
        emitExpr(ctx, stmt.sql)
        emitObjectArray(ctx, stmt.params)
        mv.visitMethodInsn(INVOKESTATIC, JDBC, "query", "($S$ARR)Ljava/util/List;", false)
        if (stmt.into != null) emitStore(ctx, stmt.into)
        else mv.visitInsn(POP)
    }

internal fun AsmEmitter.emitJdbcExecute(ctx: MethodContext, stmt: JdbcExecuteStatement) {
        val mv   = ctx.mv
        val JDBC = "dev/kobol/stdlib/KobolJdbc"
        val S    = "Ljava/lang/String;"
        val ARR  = "[Ljava/lang/Object;"
        emitExpr(ctx, stmt.sql)
        emitObjectArray(ctx, stmt.params)
        mv.visitMethodInsn(INVOKESTATIC, JDBC, "execute", "($S$ARR)J", false)
        mv.visitInsn(POP2)  // discard long result
    }

internal fun AsmEmitter.emitJdbcDisconnect(mv: MethodVisitor) {
        mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolJdbc", "disconnect", "()V", false)
    }

    /** Emit: new Object[]{ v0, v1, … } boxing primitives as needed. */
internal fun AsmEmitter.emitObjectArray(ctx: MethodContext, exprs: List<Expression>) {
        val mv = ctx.mv
        mv.visitLdcInsn(exprs.size)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
        for ((i, e) in exprs.withIndex()) {
            mv.visitInsn(DUP)
            mv.visitLdcInsn(i)
            emitExpr(ctx, e)
            val t = inferExprType(e)
            when (t) {
                is KobolType.IntegerType ->
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
                is KobolType.BooleanType ->
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                else -> {}
            }
            mv.visitInsn(AASTORE)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 12 — REST server
    // ─────────────────────────────────────────────────────────────────────────

internal fun AsmEmitter.emitServer(ctx: MethodContext, stmt: ServerStatement) {
        val mv  = ctx.mv
        val SRV = "dev/kobol/stdlib/KobolServer"
        val S   = "Ljava/lang/String;"
        val FN  = "Ljava/util/function/Function;"
        val METAFACTORY = "java/lang/invoke/LambdaMetafactory"
        val MF_DESC = "(Ljava/lang/invoke/MethodHandles\$Lookup;${S}Ljava/lang/invoke/MethodType;" +
                      "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;" +
                      "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"

        for (ep in stmt.endpoints) {
            val epMethod = emitEndpointMethod(ctx.owner, ep)
            // KobolServer.register(method, path, handler)
            mv.visitLdcInsn(ep.method)
            mv.visitLdcInsn(ep.path)
            // Create Function<String,String> via invokedynamic referencing the static endpoint method
            mv.visitInvokeDynamicInsn(
                "apply",
                "()$FN",
                Handle(H_INVOKESTATIC, METAFACTORY, "metafactory", MF_DESC, false),
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                Handle(H_INVOKESTATIC, ctx.owner, epMethod, "($S)$S", false),
                Type.getType("($S)$S"),
            )
            mv.visitMethodInsn(INVOKESTATIC, SRV, "register",
                "($S${S}${FN})V", false)
        }
        // KobolServer.start(port) + awaitShutdown()
        emitExpr(ctx, stmt.port)
        mv.visitInsn(L2I)
        mv.visitMethodInsn(INVOKESTATIC, SRV, "start", "(I)V", false)
        mv.visitMethodInsn(INVOKESTATIC, SRV, "awaitShutdown", "()V", false)
    }

internal fun AsmEmitter.emitEndpointMethod(owner: String, ep: EndpointHandler): String {
        val safePath = ep.path.replace(Regex("[^A-Za-z0-9_]"), "_")
        val methodName = "__endpoint_${ep.method}_$safePath"
        val synPos = dev.kobol.lexer.SourcePosition("<endpoint>", 0, 0)
        val bodyParam = ProcParam("body", TypeSpec.TextType(null, false, synPos), synPos)
        val syntheticProc = ProcedureDecl(methodName, listOf(bodyParam),
            TypeSpec.TextType(null, false, synPos), ep.body, ep.pos)
        pendingEndpoints.add(Triple(owner, syntheticProc, ep))
        return methodName
    }

internal fun AsmEmitter.emitEndpointMethodNow(cw: ClassWriter, owner: String, proc: ProcedureDecl, ep: EndpointHandler) {
        val S   = "Ljava/lang/String;"
        val SRV = "dev/kobol/stdlib/KobolServer"
        val mv  = cw.visitMethod(ACC_PRIVATE or ACC_STATIC, proc.name, "($S)$S", null, null)
        mv.visitCode()
        val ctx = MethodContext(mv, owner, proc)
        ctx.defineLocal("BODY", KobolType.TextType(null), 0)
        // Initialise path-parameter locals: val <PARAM> = KobolServer.currentPathParam("<param>")
        // Keys stored UPPERCASE to match Lexer normalization; Javalin lookup uses original lowercase name.
        for (param in ep.pathParams) {
            mv.visitLdcInsn(param)                                         // lowercase for Javalin
            mv.visitMethodInsn(INVOKESTATIC, SRV, "currentPathParam", "($S)$S", false)
            val slot = ctx.allocLocal(param.uppercase(), KobolType.TextType(null))
            mv.visitVarInsn(ASTORE, slot)
        }
        emitBlock(ctx, ep.body)
        // Fallthrough: return empty string (well-formed endpoints always RESPOND)
        mv.visitLdcInsn("")
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

internal fun AsmEmitter.emitRespond(ctx: MethodContext, stmt: RespondStatement) {
        val mv    = ctx.mv
        val JSON  = "dev/kobol/stdlib/KobolJson"
        val SRV   = "dev/kobol/stdlib/KobolServer"
        val S     = "Ljava/lang/String;"
        // Emit optional STATUS code: KobolServer.respondStatus(n)
        if (stmt.statusCode != null) {
            emitExpr(ctx, stmt.statusCode)
            mv.visitInsn(L2I)  // Kobol INTEGER is long
            mv.visitMethodInsn(INVOKESTATIC, SRV, "respondStatus", "(I)V", false)
        }
        if (stmt.asJson) {
            emitExpr(ctx, stmt.value)
            val t = inferExprType(stmt.value)
            when {
                t is KobolType.TextType -> {} // already a String
                else -> mv.visitMethodInsn(INVOKESTATIC, JSON, "toJson", "(Ljava/lang/Object;)$S", false)
            }
        } else {
            emitExprAsString(ctx, stmt.value)
        }
        mv.visitInsn(ARETURN)
    }

