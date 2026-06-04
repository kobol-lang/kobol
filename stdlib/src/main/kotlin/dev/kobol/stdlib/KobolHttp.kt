package dev.kobol.stdlib

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for Kobol programs using JDK's java.net.http.HttpClient (Java 11+).
 *
 * Kobol syntax:
 *   CALL http.GET  USING url [HEADERS "K: V"] [TIMEOUT n] GIVING response
 *   CALL http.POST USING url [HEADERS "K: V"] [BODY payload] [TIMEOUT n] GIVING response
 */
object KobolHttp {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @JvmStatic fun get(url: String, headers: String?, timeoutSecs: Long): String =
        send("GET",    url, headers, null, timeoutSecs)

    @JvmStatic fun post(url: String, headers: String?, body: String?, timeoutSecs: Long): String =
        send("POST",   url, headers, body, timeoutSecs)

    @JvmStatic fun put(url: String, headers: String?, body: String?, timeoutSecs: Long): String =
        send("PUT",    url, headers, body, timeoutSecs)

    @JvmStatic fun delete(url: String, headers: String?, timeoutSecs: Long): String =
        send("DELETE", url, headers, null, timeoutSecs)

    @JvmStatic fun patch(url: String, headers: String?, body: String?, timeoutSecs: Long): String =
        send("PATCH",  url, headers, body, timeoutSecs)

    // Convenience: returns the numeric HTTP status code of the last response
    private val lastStatus = ThreadLocal.withInitial { 0 }
    @JvmStatic fun lastStatus(): Long = lastStatus.get().toLong()

    private fun send(method: String, url: String, headers: String?, body: String?, timeoutSecs: Long): String {
        val builder = HttpRequest.newBuilder().uri(URI.create(url))
        if (timeoutSecs > 0) builder.timeout(Duration.ofSeconds(timeoutSecs))
        // Parse "Key: Value\nKey: Value" header pairs
        headers?.lines()?.forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) builder.header(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        val bodyPub = when {
            body != null                  -> HttpRequest.BodyPublishers.ofString(body)
            method in setOf("GET", "DELETE") -> HttpRequest.BodyPublishers.noBody()
            else                          -> HttpRequest.BodyPublishers.noBody()
        }
        builder.method(method, bodyPub)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        lastStatus.set(response.statusCode())
        return response.body() ?: ""
    }
}
