package dev.kobol.stdlib

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for KobolServer (Javalin-backed REST server).
 * Each test registers routes, starts the server on a random port, makes HTTP calls,
 * and tears down cleanly.
 *
 * Uses java.net.http.HttpClient (JDK built-in) as the HTTP client — no extra deps.
 */
class KobolServerTest {

    private val http = HttpClient.newBuilder().build()

    @AfterEach fun stopServer() {
        KobolServer.stop()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun get(url: String): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to (resp.body() ?: "")
    }

    private fun post(url: String, body: String, contentType: String = "application/json"): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to (resp.body() ?: "")
    }

    private fun startOnRandomPort(): Int {
        // Grab a free port, then start KobolServer on it
        val sock = java.net.ServerSocket(0)
        val p = sock.localPort
        sock.close()
        KobolServer.start(p)
        return p
    }

    // ── basic GET ────────────────────────────────────────────────────────────

    @Test fun `GET endpoint returns response`() {
        KobolServer.register("GET", "/hello", Function { _ -> "Hello, Kobol!" })
        val port = startOnRandomPort()
        val (code, body) = get("http://localhost:$port/hello")
        assertEquals(200, code)
        assertEquals("Hello, Kobol!", body)
    }

    // ── POST echo ────────────────────────────────────────────────────────────

    @Test fun `POST endpoint receives request body`() {
        KobolServer.register("POST", "/echo", Function { body -> body })
        val port = startOnRandomPort()
        val (code, body) = post("http://localhost:$port/echo", """{"msg":"hi"}""")
        assertEquals(200, code)
        assertEquals("""{"msg":"hi"}""", body)
    }

    // ── path parameters ──────────────────────────────────────────────────────

    @Test fun `GET with path parameter resolves correctly`() {
        KobolServer.register("GET", "/users/{id}", Function { _ ->
            val id = KobolServer.currentPathParam("id")
            "user:$id"
        })
        val port = startOnRandomPort()
        val (code, body) = get("http://localhost:$port/users/42")
        assertEquals(200, code)
        assertEquals("user:42", body)
    }

    @Test fun `multiple path params are all accessible`() {
        KobolServer.register("GET", "/orgs/{org}/repos/{repo}", Function { _ ->
            val org  = KobolServer.currentPathParam("org")
            val repo = KobolServer.currentPathParam("repo")
            "$org/$repo"
        })
        val port = startOnRandomPort()
        val (code, body) = get("http://localhost:$port/orgs/kobol/repos/stdlib")
        assertEquals(200, code)
        assertEquals("kobol/stdlib", body)
    }

    // ── status codes ─────────────────────────────────────────────────────────

    @Test fun `RESPOND WITH STATUS 404 is returned`() {
        KobolServer.register("GET", "/missing", Function { _ ->
            KobolServer.respondStatus(404)
            "not found"
        })
        val port = startOnRandomPort()
        val (code, body) = get("http://localhost:$port/missing")
        assertEquals(404, code)
        assertEquals("not found", body)
    }

    @Test fun `RESPOND WITH STATUS 201 is returned`() {
        KobolServer.register("POST", "/items", Function { _ ->
            KobolServer.respondStatus(201)
            "created"
        })
        val port = startOnRandomPort()
        val (code, body) = post("http://localhost:$port/items", "{}")
        assertEquals(201, code)
        assertEquals("created", body)
    }

    // ── query parameters ─────────────────────────────────────────────────────

    @Test fun `query parameter is accessible`() {
        KobolServer.register("GET", "/search", Function { _ ->
            KobolServer.currentQueryParam("q")
        })
        val port = startOnRandomPort()
        val (code, body) = get("http://localhost:$port/search?q=kobol")
        assertEquals(200, code)
        assertEquals("kobol", body)
    }

    @Test fun `missing query parameter returns empty string`() {
        KobolServer.register("GET", "/search", Function { _ ->
            "[${KobolServer.currentQueryParam("q")}]"
        })
        val port = startOnRandomPort()
        val (code, body) = get("http://localhost:$port/search")
        assertEquals(200, code)
        assertEquals("[]", body)
    }

    // ── request headers ──────────────────────────────────────────────────────

    @Test fun `incoming request header is accessible`() {
        KobolServer.register("GET", "/secure", Function { _ ->
            KobolServer.currentRequestHeader("X-Api-Key")
        })
        val port = startOnRandomPort()
        val req = HttpRequest.newBuilder(URI.create("http://localhost:$port/secure"))
            .header("X-Api-Key", "secret-123")
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode())
        assertEquals("secret-123", resp.body())
    }

    // ── multiple routes ───────────────────────────────────────────────────────

    @Test fun `multiple routes coexist on same server`() {
        KobolServer.register("GET",  "/a", Function { _ -> "route-a" })
        KobolServer.register("GET",  "/b", Function { _ -> "route-b" })
        KobolServer.register("POST", "/c", Function { _ -> "route-c" })
        val port = startOnRandomPort()
        assertEquals(200 to "route-a", get("http://localhost:$port/a"))
        assertEquals(200 to "route-b", get("http://localhost:$port/b"))
        assertEquals(200 to "route-c", post("http://localhost:$port/c", ""))
    }

    // ── unknown route → 404 ──────────────────────────────────────────────────

    @Test fun `unknown route returns 404`() {
        KobolServer.register("GET", "/known", Function { _ -> "ok" })
        val port = startOnRandomPort()
        val (code, _) = get("http://localhost:$port/unknown")
        assertEquals(404, code)
    }

    // ── concurrent requests ───────────────────────────────────────────────────

    @Test fun `concurrent requests are handled correctly`() {
        KobolServer.register("GET", "/thread", Function { _ ->
            Thread.currentThread().isVirtual.toString()
        })
        val port = startOnRandomPort()
        // Fire 20 concurrent requests and verify all return 200
        val results = (1..20).map {
            Thread.ofVirtual().start { get("http://localhost:$port/thread") }
        }.map { it.join(); get("http://localhost:$port/thread") }
        assertTrue(results.all { (code, _) -> code == 200 })
    }
}
