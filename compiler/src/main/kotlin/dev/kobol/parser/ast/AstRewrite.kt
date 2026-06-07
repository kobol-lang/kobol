package dev.kobol.parser.ast

/**
 * Rewrite bare references whose head name is in [fieldNames] by prepending [prefix],
 * turning `amount` into `prefix.amount`. Used in two places that share the exact
 * same need — bare field names that must resolve against an implicit receiver:
 *
 *   - record CONDITION bodies, where bare fields read `this` (`prefix = ["__self"]`);
 *   - `MATCH` guard clauses over a record subject (§22.4,
 *     `WHEN Invoice WITH amount > 10000 AND NOT paid`), where bare fields read the
 *     subject (`prefix = the subject's reference parts`).
 *
 * A reference whose head is already in [prefix] (e.g. an explicit `inv.amount`, or a
 * previously-rewritten `__self.x`) is left alone, so the rewrite is idempotent and
 * never double-prefixes. Pure and deterministic, so the TypeChecker and the emitter
 * can each call it and see identical output.
 */
fun rewriteBareFieldRefs(expr: Expression, fieldNames: Set<String>, prefix: List<String>): Expression =
    when (expr) {
        is Reference ->
            if (expr.parts.first() in fieldNames && expr.parts.first() !in prefix)
                Reference(prefix + expr.parts, expr.pos)
            else expr
        is BinaryExpr -> BinaryExpr(
            expr.op,
            rewriteBareFieldRefs(expr.left, fieldNames, prefix),
            rewriteBareFieldRefs(expr.right, fieldNames, prefix),
            expr.pos,
        )
        is UnaryExpr -> UnaryExpr(expr.op, rewriteBareFieldRefs(expr.operand, fieldNames, prefix), expr.pos)
        is BuiltinCall -> BuiltinCall(expr.name, expr.args.map { rewriteBareFieldRefs(it, fieldNames, prefix) }, expr.pos)
        is IndexExpr -> IndexExpr(
            rewriteBareFieldRefs(expr.target, fieldNames, prefix),
            rewriteBareFieldRefs(expr.index, fieldNames, prefix),
            expr.pos,
        )
        else -> expr
    }
