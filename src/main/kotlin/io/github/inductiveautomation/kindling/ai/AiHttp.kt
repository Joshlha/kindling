package io.github.inductiveautomation.kindling.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.stream.Stream
import kotlin.text.Charsets.UTF_8

/**
 * Deliberately not the [Json] instance in `Kindling.Preferences`: that one sets `prettyPrint`,
 * which would inflate every request body on the wire for no benefit. `explicitNulls = false`
 * keeps null-valued optionals out of request bodies entirely, which several OpenAI-compatible
 * servers reject outright.
 */
internal val AiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Redirects are **not** followed. kapa's endpoints require a trailing slash and redirect without
 * one; a redirect-following client silently converts the POST to a GET and the server answers with
 * a baffling `405 Method "GET" not allowed`. Failing loudly on the redirect is far easier to debug.
 */
internal val aiHttpClient: HttpClient by lazy {
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .executor(
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "kindling-ai-http").apply { isDaemon = true }
            },
        ).build()
}

internal suspend fun HttpRequest.sendForString(): HttpResponse<String> = aiHttpClient.sendAsync(this, HttpResponse.BodyHandlers.ofString(UTF_8)).await()

internal suspend fun HttpRequest.sendForLines(): HttpResponse<Stream<String>> = aiHttpClient.sendAsync(this, HttpResponse.BodyHandlers.ofLines()).await()

internal fun jsonBody(body: String): HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofString(body, UTF_8)

/**
 * Parses one line of a Server-Sent Events stream, returning the `data:` payload or null for
 * comments, blank separators, and non-data fields. Both providers frame responses this way.
 */
internal fun sseData(line: String): String? = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()

internal const val SSE_DONE = "[DONE]"

/** Runs a blocking [java.util.stream.Stream] consumer off the calling thread. */
internal suspend fun <T> withIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
