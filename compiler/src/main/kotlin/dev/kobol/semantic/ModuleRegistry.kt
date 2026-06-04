package dev.kobol.semantic

/**
 * Registry of exported symbols from other Kobol modules.
 *
 * Built by [dev.kobol.project.ProjectBuilder] during the pre-pass that parses all source
 * files before compiling. Passed into [TypeChecker] and code generators so that
 * cross-module [dev.kobol.parser.ast.PerformStatement]s can be validated and emitted.
 *
 * Module aliases are registered per-program via [registerAlias] when an IMPORT
 * declaration for a known module is processed.
 */
class ModuleRegistry {

    data class ModuleProcedure(
        val params: List<Symbol.ProcedureSymbol.Param>,
        val returnType: KobolType?,
    )

    data class ModuleInfo(
        val moduleName: String,      // "kobol.billing"
        val version: String?,        // declared VERSION "2.1" or null
        val jvmClassName: String,    // "Billing" (derived from PROGRAM name)
        val procedures: Map<String, ModuleProcedure>,  // UPPERCASE name → signature
        val records:    Map<String, Symbol.RecordSymbol> = emptyMap(),
        val variants:   Map<String, Symbol.VariantSymbol> = emptyMap(),
    )

    private val byModuleName = mutableMapOf<String, ModuleInfo>()

    /** Per-program alias bindings — reset between programs via [clearAliases]. */
    private val aliasByName  = mutableMapOf<String, ModuleInfo>()

    /** Register a module's exported signature. Called once per source file at project-build time. */
    fun register(info: ModuleInfo) {
        byModuleName[info.moduleName.uppercase()] = info
    }

    /**
     * Look up a module by its declared name (e.g. "KOBOL.BILLING" or "kobol.billing").
     * Keys are stored uppercase; incoming names from the AST are already uppercase
     * (the Lexer normalises all identifiers), but external / test callers may pass
     * mixed-case, so we normalise at the boundary here.
     */
    fun resolveByName(moduleName: String): ModuleInfo? = byModuleName[moduleName.uppercase()]

    /**
     * Bind an import alias to a known module for this compilation unit.
     * Returns true if the module was found in the registry.
     * [alias] must be uppercase (guaranteed by Lexer).
     */
    fun registerAlias(alias: String, moduleName: String): Boolean {
        val info = resolveByName(moduleName) ?: return false
        aliasByName[alias] = info
        return true
    }

    /** Look up a module by the alias used in the current program. */
    fun resolveByAlias(alias: String): ModuleInfo? = aliasByName[alias]

    /** True if [alias] has been bound to a known module in the current program. */
    fun isModuleAlias(alias: String): Boolean = alias in aliasByName

    /** Reset per-program alias bindings (call before each program's TypeChecker run). */
    fun clearAliases() { aliasByName.clear() }

    fun isEmpty(): Boolean = byModuleName.isEmpty()
}
