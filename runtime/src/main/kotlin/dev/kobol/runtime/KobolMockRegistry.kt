package dev.kobol.runtime

/**
 * Thread-local mock registry for Kobol test blocks.
 *
 * MOCK ProcedureName RETURNS value registers the return value here.
 * Emitted test code checks this registry before calling the real procedure.
 */
object KobolMockRegistry {
    private val mocks: ThreadLocal<HashMap<String, Any?>> =
        ThreadLocal.withInitial { HashMap() }

    @JvmStatic fun register(name: String, value: Any?) { mocks.get()[name] = value }
    @JvmStatic fun get(name: String): Any? = mocks.get()[name]
    @JvmStatic fun isMocked(name: String): Boolean = mocks.get().containsKey(name)
    @JvmStatic fun clear() { mocks.get().clear() }
}
