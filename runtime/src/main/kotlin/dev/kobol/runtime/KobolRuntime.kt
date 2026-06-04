package dev.kobol.runtime

import java.util.stream.Stream

/**
 * General-purpose JVM runtime helpers used by ASM-generated code.
 */
object KobolRuntime {
    /** Identity passthrough for Stream — used as a stub until lambda codegen is complete. */
    @JvmStatic
    fun identity(stream: Stream<*>): Stream<*> = stream

    /** Safe string representation of any value. */
    @JvmStatic
    fun toStr(value: Any?): String = value?.toString() ?: ""

    /**
     * Block the current thread for [millis] milliseconds.
     * Restores the interrupt flag if interrupted.
     */
    @JvmStatic
    fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Reflectively invoke an instance method on [receiver].
     * Searches for the best matching method (handles subtype arguments).
     * Returns the result or null for void methods.
     */
    @JvmStatic
    fun invokeInstanceMethod(receiver: Any, methodName: String, vararg args: Any?): Any? {
        val argTypes = args.map { it?.javaClass }.toTypedArray()
        // First try exact match
        try {
            val method = receiver::class.java.getMethod(methodName, *argTypes)
            return method.invoke(receiver, *args)
        } catch (_: NoSuchMethodException) {
            // Fall through to search
        }
        // Search all public methods with matching name and compatible parameter count
        val candidate = receiver::class.java.methods.firstOrNull { m ->
            m.name == methodName && m.parameterCount == argTypes.size &&
            argTypes.indices.all { i ->
                argTypes[i] == null || m.parameterTypes[i].isAssignableFrom(argTypes[i])
            }
        }
        if (candidate != null) return candidate.invoke(receiver, *args)
        throw NoSuchMethodException("${receiver::class.java.name}.$methodName with args ${argTypes.contentToString()}")
    }

    // -------------------------------------------------------------------------
    // Collection pipeline helpers (FILTER WHERE / SORT BY / TAKE)
    //
    // Reflection-based so the compiler only needs to pass field/condition names.
    // filter is O(n), sort is O(n log n), take is O(1) view — no hidden quadratics.
    // -------------------------------------------------------------------------

    /** Canonical key for name matching: lowercase, strip non-alphanumeric (`credit-limit` == `creditLimit`). */
    private fun normalizeName(s: String): String =
        buildString { for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }

    /** Read a field value (by normalized name) or, failing that, invoke a `cond$name` predicate method. */
    private fun memberValue(obj: Any?, name: String): Any? {
        if (obj == null) return null
        val key = normalizeName(name)
        obj.javaClass.fields.firstOrNull { normalizeName(it.name) == key }?.let { return it.get(obj) }
        val m = obj.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && (normalizeName(it.name) == key || normalizeName(it.name) == "cond$key")
        }
        return m?.invoke(obj)
    }

    /** Keep elements where the named boolean field/condition is true. O(n). */
    @JvmStatic
    fun filterByMember(list: List<Any?>, name: String): List<Any?> =
        list.filter { memberValue(it, name) == true }

    /** Sort by the named field's natural order. Nulls sort first; [descending] reverses. O(n log n). */
    @JvmStatic
    fun sortByField(list: List<Any?>, field: String, descending: Boolean): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        val base = Comparator<Any?> { a, b ->
            val av = memberValue(a, field) as? Comparable<Any?>
            val bv = memberValue(b, field)
            when {
                av == null && bv == null -> 0
                av == null -> -1
                bv == null -> 1
                else -> av.compareTo(bv)
            }
        }
        val cmp = if (descending) base.reversed() else base
        return list.sortedWith(cmp)
    }

    /** First [n] elements (or all, if fewer). O(1) view. */
    @JvmStatic
    fun take(list: List<Any?>, n: Long): List<Any?> =
        if (n >= list.size) list else ArrayList(list.subList(0, n.toInt()))
}
