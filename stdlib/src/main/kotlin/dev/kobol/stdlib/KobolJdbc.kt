package dev.kobol.stdlib

import java.sql.Connection
import java.sql.DriverManager

/**
 * JDBC bridge for Kobol programs using java.sql (always available on JVM).
 *
 * Kobol syntax:
 *   CALL jdbc.connect    USING url [USER u] [PASSWORD p]
 *   CALL jdbc.query      USING sql [PARAMS p1, p2, ...] [INTO var] [AS LIST OF Type]
 *   CALL jdbc.execute    USING sql [PARAMS p1, p2, ...]
 *   CALL jdbc.disconnect
 */
object KobolJdbc {

    private val connection: ThreadLocal<Connection?> = ThreadLocal.withInitial { null }

    @JvmStatic fun connect(url: String, user: String?, password: String?) {
        val conn = when {
            user != null -> DriverManager.getConnection(url, user, password ?: "")
            else         -> DriverManager.getConnection(url)
        }
        connection.set(conn)
    }

    @JvmStatic fun disconnect() {
        connection.get()?.close()
        connection.set(null)
    }

    /**
     * Execute a SELECT query and return results as a Java List of row Maps.
     * Each map has lowercase column-label keys.
     */
    @JvmStatic fun query(sql: String, params: Array<Any?>): java.util.List<java.util.Map<String, Any?>> {
        val conn = connection.get() ?: error("KobolJdbc: no connection — call JDBC.CONNECT first")
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            val rs   = ps.executeQuery()
            val meta = rs.metaData
            val cols = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
            val result = java.util.ArrayList<java.util.Map<String, Any?>>()
            while (rs.next()) {
                val row = java.util.LinkedHashMap<String, Any?>()
                cols.forEachIndexed { i, col -> row[col] = rs.getObject(i + 1) }
                @Suppress("UNCHECKED_CAST")
                result.add(row as java.util.Map<String, Any?>)
            }
            @Suppress("UNCHECKED_CAST")
            return result as java.util.List<java.util.Map<String, Any?>>
        }
    }

    /**
     * Execute an INSERT/UPDATE/DELETE statement.
     * Returns the number of rows affected.
     */
    @JvmStatic fun execute(sql: String, params: Array<Any?>): Long {
        val conn = connection.get() ?: error("KobolJdbc: no connection — call JDBC.CONNECT first")
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            return ps.executeUpdate().toLong()
        }
    }

    /** Convenience: execute with no params. */
    @JvmStatic fun execute(sql: String): Long = execute(sql, emptyArray())
}
