package dev.kobol.stdlib

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.URI

/**
 * Redis key-value cache bridge for Kobol programs.
 *
 * Kobol syntax:
 *   CACHE CONNECT TO "redis://localhost:6379"
 *   CACHE DISCONNECT
 *
 *   CACHE GET session-key GIVING session-data
 *   CACHE SET session-key TO session-data
 *   CACHE SET session-key TO session-data EXPIRES IN 3600 SECONDS
 *   CACHE DELETE session-key
 *   CACHE EXISTS session-key GIVING found
 *
 * Uses a JedisPool so concurrent ASYNC PROCEDUREs share the pool safely.
 * Each operation borrows a connection, uses it, and returns it immediately.
 *
 * Add the client to your project's kobol.toml [dependencies]:
 *   "redis.clients:jedis" = "5.1.0"
 */
object KobolRedis {

    // A single shared pool per JVM process.  Connections are borrowed per-call
    // and returned to the pool via try-with-resources (Jedis implements Closeable).
    @Volatile private var pool: JedisPool? = null

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @JvmStatic fun connect(url: String) {
        val config = JedisPoolConfig().apply {
            maxTotal = 16
            maxIdle  = 8
            minIdle  = 2
            testOnBorrow = true
        }
        pool = JedisPool(config, URI(url))
    }

    @JvmStatic fun disconnect() {
        pool?.close()
        pool = null
    }

    // ------------------------------------------------------------------
    // GET — returns the value or null if the key does not exist
    // ------------------------------------------------------------------

    @JvmStatic fun get(key: String): String? =
        jedisPool().resource.use { it.get(key) }

    // ------------------------------------------------------------------
    // SET — store without expiry
    // ------------------------------------------------------------------

    @JvmStatic fun set(key: String, value: String) {
        jedisPool().resource.use { it.set(key, value) }
    }

    // ------------------------------------------------------------------
    // SET … EXPIRES IN n SECONDS — store with TTL
    // ------------------------------------------------------------------

    @JvmStatic fun setEx(key: String, value: String, ttlSeconds: Long) {
        jedisPool().resource.use { it.setex(key, ttlSeconds, value) }
    }

    // ------------------------------------------------------------------
    // DELETE — remove a key; returns 1 if it existed, 0 otherwise
    // ------------------------------------------------------------------

    @JvmStatic fun delete(key: String): Long =
        jedisPool().resource.use { it.del(key) }

    // ------------------------------------------------------------------
    // EXISTS — check whether a key is present
    // ------------------------------------------------------------------

    @JvmStatic fun exists(key: String): Boolean =
        jedisPool().resource.use { it.exists(key) }

    // ------------------------------------------------------------------

    private fun jedisPool() =
        pool ?: error("KobolRedis: no connection — use CACHE CONNECT TO first")
}
