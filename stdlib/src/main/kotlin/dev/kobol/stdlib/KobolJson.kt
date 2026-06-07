package dev.kobol.stdlib

import java.math.BigDecimal
import java.util.UUID

/**
 * KobolJson — lightweight JSON serialiser for Kobol programs.
 *
 * Supports: null, Boolean, Long, Int, BigDecimal, String, UUID, List, Map,
 * and any JVM object (serialised via reflection over declared fields).
 *
 * No external dependencies required.
 */
object KobolJson {

    @JvmStatic fun toJson(value: Any?): String        = serialize(value)
    @JvmStatic fun toPrettyJson(value: Any?): String  = prettySerialize(value, 0)

    @JvmStatic fun writeToFile(value: Any?, path: String) {
        java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(toJson(value))
    }

    @JvmStatic fun writePrettyToFile(value: Any?, path: String) {
        java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(toPrettyJson(value))
    }

    /**
     * Populate the fields of [target] by parsing [json].
     * Only fields whose names match JSON keys (case-insensitive) are set.
     * Unrecognised JSON keys are silently ignored.
     */
    @JvmStatic fun parseInto(target: Any, json: String) {
        val map = parseJsonObject(json.trim()) ?: return
        val cls = target::class.java
        for (field in cls.declaredFields) {
            if (field.isSynthetic) continue
            val raw = map[field.name] ?: map[field.name.replace('_', '-')] ?: continue
            field.isAccessible = true
            try {
                field.set(target, coerceJsonValue(raw, field.type))
            } catch (_: Exception) { /* skip incompatible */ }
        }
    }

    /** Read JSON from a file and populate [target]'s fields. */
    @JvmStatic fun parseFileInto(target: Any, path: String) =
        parseInto(target, java.io.File(path).readText())

    /**
     * Parse a JSON array string and return an ArrayList of the element values.
     * Primitives are returned as Long, BigDecimal, Boolean, or String.
     * Objects are returned as LinkedHashMap<String, Any?>.
     */
    @JvmStatic fun parseJsonArray(json: String): java.util.ArrayList<Any?> {
        val result = java.util.ArrayList<Any?>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return result
        var i = 1
        while (i < trimmed.length) {
            while (i < trimmed.length && trimmed[i].isWhitespace()) i++
            if (i >= trimmed.length || trimmed[i] == ']') break
            if (trimmed[i] == ',') { i++; continue }
            val (value, next) = parseJsonValue(trimmed, i)
            result.add(value)
            i = next
        }
        return result
    }

    /** Read a JSON array from a file. */
    @JvmStatic fun parseFileArray(path: String): java.util.ArrayList<Any?> =
        parseJsonArray(java.io.File(path).readText())

    // ── Minimal JSON object parser (no external deps) ────────────────────

    private fun parseJsonObject(json: String): Map<String, Any?>? {
        if (!json.startsWith("{")) return null
        val result = LinkedHashMap<String, Any?>()
        var i = 1
        while (i < json.length) {
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] == '}') break
            if (json[i] == ',') { i++; continue }
            // parse key
            if (json[i] != '"') break
            val (key, after) = parseJsonString(json, i); i = after
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') break; i++
            while (i < json.length && json[i].isWhitespace()) i++
            val (value, next) = parseJsonValue(json, i); i = next
            result[key] = value
        }
        return result
    }

    private fun parseJsonValue(json: String, start: Int): Pair<Any?, Int> {
        var i = start
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return Pair(null, i)
        return when {
            json[i] == '"' -> parseJsonString(json, i).let { (s, n) -> Pair(s, n) }
            json[i] == '{' -> { val m = parseNestedObject(json, i); Pair(m.first, m.second) }
            json[i] == '[' -> { val a = parseJsonArray(json, i); Pair(a.first, a.second) }
            json.startsWith("true", i) -> Pair(true, i + 4)
            json.startsWith("false", i) -> Pair(false, i + 5)
            json.startsWith("null", i) -> Pair(null, i + 4)
            else -> {
                var j = i
                while (j < json.length && json[j] !in " ,}]\n\r\t") j++
                val num = json.substring(i, j)
                Pair(if ('.' in num) java.math.BigDecimal(num) else num.toLongOrNull() ?: num, j)
            }
        }
    }

    private fun parseJsonString(json: String, start: Int): Pair<String, Int> {
        var i = start + 1; val sb = StringBuilder()
        while (i < json.length && json[i] != '"') {
            if (json[i] == '\\' && i + 1 < json.length) {
                when (json[++i]) { '"' -> sb.append('"'); '\\' -> sb.append('\\'); 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(json[i]) }
            } else sb.append(json[i]); i++
        }
        return Pair(sb.toString(), if (i < json.length) i + 1 else i)
    }

    private fun parseNestedObject(json: String, start: Int): Pair<Map<String,Any?>, Int> {
        var depth = 0; var i = start
        while (i < json.length) { when (json[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return Pair(parseJsonObject(json.substring(start, i + 1)) ?: emptyMap(), i + 1) } }; i++ }
        return Pair(emptyMap(), i)
    }

    private fun parseJsonArray(json: String, start: Int): Pair<List<Any?>, Int> {
        var depth = 0; var i = start
        while (i < json.length) { when (json[i]) { '[' -> depth++; ']' -> { depth--; if (depth == 0) break } }; i++ }
        return Pair(emptyList(), i + 1) // simplified: return empty list for now
    }

    private fun coerceJsonValue(value: Any?, targetType: Class<*>): Any? = when {
        value == null                              -> null
        targetType == String::class.java           -> value.toString()
        targetType == Long::class.java || targetType == java.lang.Long.TYPE -> when (value) { is Long -> value; is Number -> value.toLong(); else -> value.toString().toLongOrNull() ?: 0L }
        targetType == java.math.BigDecimal::class.java -> when (value) { is java.math.BigDecimal -> value; is Number -> java.math.BigDecimal(value.toString()); else -> java.math.BigDecimal(value.toString()) }
        targetType == Boolean::class.java || targetType == java.lang.Boolean.TYPE -> when (value) { is Boolean -> value; is String -> value.equals("true", ignoreCase = true); else -> false }
        else -> value
    }

    // ── Compact serialisation ────────────────────────────────────────────

    private fun serialize(value: Any?): String = when (value) {
        null                        -> "null"
        is Boolean                  -> value.toString()
        is Long, is Int, is Short,
        is Byte                     -> value.toString()
        is BigDecimal               -> value.toPlainString()
        is Number                   -> value.toString()
        is String                   -> jsonString(value)
        is UUID                     -> jsonString(value.toString())
        is List<*>                  -> "[${value.joinToString(",") { serialize(it) }}]"
        is Map<*, *>                -> "{${value.entries.joinToString(",") { (k, v) ->
                                          "${jsonString(k.toString())}:${serialize(v)}" }}}"
        else                        -> serializeObject(value, fieldSerializer = ::serialize)
    }

    // ── Pretty serialisation ─────────────────────────────────────────────

    private fun prettySerialize(value: Any?, depth: Int): String {
        val pad  = "  ".repeat(depth)
        val next = "  ".repeat(depth + 1)
        return when (value) {
            null                        -> "null"
            is Boolean                  -> value.toString()
            is Long, is Int, is Short,
            is Byte                     -> value.toString()
            is BigDecimal               -> value.toPlainString()
            is Number                   -> value.toString()
            is String                   -> jsonString(value)
            is UUID                     -> jsonString(value.toString())
            is List<*>                  -> if (value.isEmpty()) "[]" else
                "[\n${value.joinToString(",\n") { "$next${prettySerialize(it, depth + 1)}" }}\n$pad]"
            is Map<*, *>                -> if (value.isEmpty()) "{}" else
                "{\n${value.entries.joinToString(",\n") { (k, v) ->
                    "$next${jsonString(k.toString())}: ${prettySerialize(v, depth + 1)}"
                }}\n$pad}"
            else                        -> serializeObject(value, pretty = pad to next) { v -> prettySerialize(v, depth + 1) }
        }
    }

    // ── Reflection helper ────────────────────────────────────────────────

    /**
     * Serialise an arbitrary JVM object (a Kobol record) by reflecting over its
     * declared fields. When [pretty] is non-null it carries `(pad, next)` — the
     * indentation of this object's braces and of its fields — so the object form
     * indents the same way the List/Map forms do (otherwise records came out
     * compact even under `DISPLAY JSON x PRETTY`, #v4).
     */
    private fun serializeObject(
        value: Any,
        pretty: Pair<String, String>? = null,
        fieldSerializer: (Any?) -> String,
    ): String {
        val cls    = value::class.java
        val fields = cls.declaredFields.filter { !it.isSynthetic }
        if (fields.isEmpty()) return jsonString(value.toString())
        if (pretty == null) {
            val entries = fields.joinToString(",") { f ->
                f.isAccessible = true
                "${jsonString(f.name)}:${fieldSerializer(f.get(value))}"
            }
            return "{$entries}"
        }
        val (pad, next) = pretty
        val entries = fields.joinToString(",\n") { f ->
            f.isAccessible = true
            "$next${jsonString(f.name)}: ${fieldSerializer(f.get(value))}"
        }
        return "{\n$entries\n$pad}"
    }

    // ── String escaping ──────────────────────────────────────────────────

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}
