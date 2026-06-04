package dev.kobol.semantic

import dev.kobol.lexer.SourcePosition
import dev.kobol.parser.ast.Expression

// =============================================================================
//  Kobol runtime type system
// =============================================================================

sealed class KobolType {
    object IntegerType   : KobolType() { override fun toString() = "INTEGER" }
    object SmallIntType  : KobolType() { override fun toString() = "SMALLINT" }
    object BooleanType   : KobolType() { override fun toString() = "BOOLEAN" }
    object DateType      : KobolType() { override fun toString() = "DATE" }
    object TimeType      : KobolType() { override fun toString() = "TIME" }
    object DateTimeType  : KobolType() { override fun toString() = "DATETIME" }
    object JavaObjectType: KobolType() { override fun toString() = "JAVA-OBJECT" }
    object UnknownType   : KobolType() { override fun toString() = "UNKNOWN" }
    object VoidType      : KobolType() { override fun toString() = "VOID" }
    object UuidType      : KobolType() { override fun toString() = "UUID" }

    data class DecimalType(val precision: Int, val scale: Int) : KobolType() {
        override fun toString() = "DECIMAL($precision,$scale)"
    }
    data class MoneyType(val precision: Int, val scale: Int) : KobolType() {
        override fun toString() = "MONEY($precision,$scale)"
    }
    data class TextType(val maxLength: Int?) : KobolType() {
        override fun toString() = if (maxLength != null) "TEXT($maxLength)" else "TEXT"
    }
    data class ListType(val elementType: KobolType) : KobolType() {
        override fun toString() = "LIST OF $elementType"
    }
    data class MapType(val keyType: KobolType, val valueType: KobolType) : KobolType() {
        override fun toString() = "MAP OF $keyType TO $valueType"
    }
    data class FutureType(val elementType: KobolType) : KobolType() {
        override fun toString() = "FUTURE OF $elementType"
    }
    data class RecordRefType(val name: String) : KobolType() {
        override fun toString() = name
    }
    data class VariantRefType(val name: String) : KobolType() {
        override fun toString() = name
    }

    /** True if this type participates in numeric arithmetic. */
    fun isNumeric() = this is IntegerType || this is SmallIntType ||
                      this is DecimalType || this is MoneyType

    /** True if both sides of an assignment are compatible (no explicit conversion needed). */
    fun isAssignableTo(target: KobolType): Boolean = when {
        this == target                          -> true
        this is UnknownType || target is UnknownType -> true
        this is IntegerType && target is SmallIntType -> true
        this is SmallIntType && target is IntegerType -> true
        this.isNumeric() && target.isNumeric()  -> true
        this is TextType && target is TextType  -> true
        else                                    -> false
    }
}

// =============================================================================
//  Symbol hierarchy
// =============================================================================

sealed class Symbol {
    abstract val name: String
    abstract val pos: SourcePosition

    data class Variable(
        override val name: String,
        val type: KobolType,
        val mutable: Boolean = true,
        override val pos: SourcePosition,
    ) : Symbol()

    data class Constant(
        override val name: String,
        val type: KobolType,
        val value: Any,
        override val pos: SourcePosition,
    ) : Symbol()

    data class ProcedureSymbol(
        override val name: String,
        val params: List<Param>,
        val returnType: KobolType?,
        override val pos: SourcePosition,
        val isAsync: Boolean = false,
        val deprecated: String? = null,
    ) : Symbol() {
        data class Param(val name: String, val type: KobolType)
    }

    data class RecordSymbol(
        override val name: String,
        val fields: LinkedHashMap<String, KobolType>,
        val conditions: Map<String, String>,  // conditionName → fieldName
        override val pos: SourcePosition,
    ) : Symbol()

    data class VariantSymbol(
        override val name: String,
        val cases: List<CaseInfo>,
        override val pos: SourcePosition,
    ) : Symbol() {
        data class CaseInfo(val name: String, val fields: LinkedHashMap<String, KobolType>)
    }

    /** Top-level named boolean expression registered by CONDITION declarations. */
    data class NamedCondition(
        override val name: String,
        val expr: Expression,
        override val pos: SourcePosition,
    ) : Symbol()
}

// =============================================================================
//  Symbol table with lexical scoping
// =============================================================================

class SymbolTable {
    private val scopes = ArrayDeque<Scope>()

    // -----------------------------------------------------------------
    // Lookup caches — invalidated on every scope change or define().
    //
    // resolve() cache: maps name → Symbol? (null = "looked up, not found")
    //   getOrPut stores null correctly, so absent-key vs null-value are
    //   distinguished by HashMap's containsKey semantics via getOrPut.
    //
    // allVisibleNames cache: rebuilt lazily; set to null on invalidation.
    // -----------------------------------------------------------------
    private val resolveCache       = HashMap<String, Symbol?>()
    private var visibleNamesCache: Set<String>? = null

    private fun invalidateCaches() {
        resolveCache.clear()
        visibleNamesCache = null
    }

    data class Scope(val name: String, val symbols: LinkedHashMap<String, Symbol> = LinkedHashMap())

    init { enterScope("GLOBAL") }

    fun enterScope(name: String) { scopes.addLast(Scope(name)); invalidateCaches() }

    fun exitScope() {
        check(scopes.size > 1) { "Cannot exit global scope" }
        scopes.removeLast()
        invalidateCaches()
    }

    fun define(symbol: Symbol) {
        scopes.last().symbols[symbol.name] = symbol
        // Invalidate only this key in the resolve cache (the name may now shadow
        // an outer definition), and drop the visible-names snapshot.
        resolveCache.remove(symbol.name)
        visibleNamesCache = null
    }

    /** Returns the existing symbol with this name in the current scope, or null if not yet defined. */
    fun resolveInCurrentScope(name: String): Symbol? = scopes.last().symbols[name]

    /**
     * Resolve name searching from innermost scope outward.
     * Result is cached for O(1) repeated lookups within the same scope frame.
     */
    fun resolve(name: String): Symbol? {
        // getOrPut stores null correctly: subsequent calls with the same key
        // return the cached null without re-walking the scope chain.
        if (resolveCache.containsKey(name)) return resolveCache[name]
        var found: Symbol? = null
        for (scope in scopes.reversed()) {
            val sym = scope.symbols[name]
            if (sym != null) { found = sym; break }
        }
        resolveCache[name] = found
        return found
    }

    /**
     * All names visible in the current scope chain (for "did you mean?" lookup).
     * Built once per scope frame and reused until the frame changes.
     */
    fun allVisibleNames(): Set<String> =
        visibleNamesCache ?: buildVisibleNames().also { visibleNamesCache = it }

    private fun buildVisibleNames(): Set<String> {
        val capacity = scopes.sumOf { it.symbols.size }
        val names = LinkedHashSet<String>(capacity * 2)   // pre-size to avoid rehashing
        for (scope in scopes.reversed()) names.addAll(scope.symbols.keys)
        return names
    }

    /** Names in just the current (innermost) scope. */
    fun currentScopeNames(): Collection<String> = scopes.last().symbols.keys

    fun currentScopeName(): String = scopes.last().name
}
