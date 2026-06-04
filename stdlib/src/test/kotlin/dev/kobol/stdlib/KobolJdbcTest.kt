package dev.kobol.stdlib

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for KobolJdbc using H2 in-memory database.
 * Exercises the full connect → execute/query → disconnect lifecycle.
 */
class KobolJdbcTest {

    @BeforeEach fun connect() {
        KobolJdbc.connect("jdbc:h2:mem:kobol_test;DB_CLOSE_DELAY=-1", "sa", "")
    }

    @AfterEach fun disconnect() {
        runCatching { KobolJdbc.execute("DROP TABLE IF EXISTS users") }
        KobolJdbc.disconnect()
    }

    // ── connect / disconnect ────────────────────────────────────────────────

    @Test fun `connect and disconnect succeeds`() {
        // BeforeEach already connected; AfterEach will disconnect — no exception means pass
    }

    // ── execute ─────────────────────────────────────────────────────────────

    @Test fun `execute CREATE TABLE succeeds`() {
        val rows = KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        assertEquals(0L, rows)
    }

    @Test fun `execute INSERT returns affected row count`() {
        KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        val rows = KobolJdbc.execute("INSERT INTO users VALUES (1, 'Alice')")
        assertEquals(1L, rows)
    }

    @Test fun `execute with params prevents SQL injection`() {
        KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        // A raw-string injection attempt should be stored as a literal, not executed
        val injected = "'; DROP TABLE users; --"
        KobolJdbc.execute("INSERT INTO users VALUES (?, ?)", arrayOf(1, injected))
        val result = KobolJdbc.query("SELECT name FROM users WHERE id = 1", emptyArray())
        assertEquals(1, result.size)
        assertEquals(injected, result[0]["name"])
    }

    // ── query ───────────────────────────────────────────────────────────────

    @Test fun `query returns rows as list of maps`() {
        KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        KobolJdbc.execute("INSERT INTO users VALUES (1, 'Alice')", emptyArray())
        KobolJdbc.execute("INSERT INTO users VALUES (2, 'Bob')", emptyArray())

        val rows = KobolJdbc.query("SELECT id, name FROM users ORDER BY id", emptyArray())
        assertEquals(2, rows.size)
        assertEquals(1, rows[0]["id"])
        assertEquals("Alice", rows[0]["name"])
        assertEquals(2, rows[1]["id"])
        assertEquals("Bob", rows[1]["name"])
    }

    @Test fun `query with params filters correctly`() {
        KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        KobolJdbc.execute("INSERT INTO users VALUES (1, 'Alice')", emptyArray())
        KobolJdbc.execute("INSERT INTO users VALUES (2, 'Bob')", emptyArray())

        val rows = KobolJdbc.query("SELECT name FROM users WHERE id = ?", arrayOf(2))
        assertEquals(1, rows.size)
        assertEquals("Bob", rows[0]["name"])
    }

    @Test fun `query returns empty list when no rows match`() {
        KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        val rows = KobolJdbc.query("SELECT * FROM users", emptyArray())
        assertTrue(rows.isEmpty())
    }

    @Test fun `column labels are lowercase`() {
        KobolJdbc.execute("CREATE TABLE users (ID INT PRIMARY KEY, FULL_NAME VARCHAR(100))")
        KobolJdbc.execute("INSERT INTO users VALUES (1, 'Alice')", emptyArray())
        val rows = KobolJdbc.query("SELECT ID, FULL_NAME FROM users", emptyArray())
        assertNotNull(rows[0]["id"])
        assertNotNull(rows[0]["full_name"])
    }

    @Test fun `execute UPDATE returns affected rows`() {
        KobolJdbc.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))")
        KobolJdbc.execute("INSERT INTO users VALUES (1, 'Alice')", emptyArray())
        KobolJdbc.execute("INSERT INTO users VALUES (2, 'Bob')", emptyArray())
        val affected = KobolJdbc.execute("UPDATE users SET name = 'Carol' WHERE id = ?", arrayOf(1))
        assertEquals(1L, affected)
        val result = KobolJdbc.query("SELECT name FROM users WHERE id = 1", emptyArray())
        assertEquals("Carol", result[0]["name"])
    }
}
