package io.github.inductiveautomation.kindling.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.inductiveautomation.kindling.ai.kapa.KapaRetrievalProvider
import io.github.inductiveautomation.kindling.ai.openai.OpenAiCompatibleClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.text.Charsets.UTF_8

/**
 * A throwaway loopback HTTP server. Lets the real clients exercise real sockets with no network
 * and no mocking library, which is the only way to prove header, error and SSE handling actually
 * work rather than merely compile.
 */
private class StubServer(
    private val handler: (HttpExchange) -> Unit,
) : AutoCloseable {
    val requests = mutableListOf<String>()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests += exchange.requestBody.readBytes().toString(UTF_8)
                lastHeaders = exchange.requestHeaders.entries.associate { it.key to it.value.joinToString() }
                handler(exchange)
            }
            start()
        }

    var lastHeaders: Map<String, String> = emptyMap()
        private set

    /** com.sun.net.httpserver normalises header casing, so never match on it. */
    fun header(name: String): String? = lastHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

private fun HttpExchange.respond(
    status: Int,
    body: String,
    contentType: String = "application/json",
) {
    val bytes = body.toByteArray(UTF_8)
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

class ClientTests :
    FunSpec(
        {
            context("OpenAiCompatibleClient") {
                test("sends a bearer token and parses a completion") {
                    StubServer { exchange ->
                        exchange.respond(
                            200,
                            """{"model":"m","choices":[{"message":{"role":"assistant","content":"hi"},
                            "finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4}}""",
                        )
                    }.use { server ->
                        val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "sk-test")

                        val response = runBlocking {
                            client.chat(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "hello"))))
                        }

                        response.text shouldBe "hi"
                        response.finishReason shouldBe FinishReason.STOP
                        response.usage.shouldNotBeNull().promptTokens shouldBe 3
                        server.header("Authorization") shouldBe "Bearer sk-test"
                        server.requests.single() shouldContain "\"role\":\"user\""
                    }
                }

                test("omits the Authorization header when no key is set, for local endpoints") {
                    StubServer { exchange ->
                        exchange.respond(200, """{"choices":[{"message":{"content":"x"}}]}""")
                    }.use { server ->
                        val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "")
                        runBlocking { client.chat(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "q")))) }

                        server.header("Authorization").shouldBeNull()
                    }
                }

                test("maps status codes to distinguishable exceptions") {
                    for ((status, expected) in listOf(
                        401 to LlmException.Authentication::class,
                        403 to LlmException.Forbidden::class,
                        404 to LlmException.InvalidRequest::class,
                        500 to LlmException.Transport::class,
                    )) {
                        StubServer { exchange ->
                            exchange.respond(status, """{"error":{"message":"boom"}}""")
                        }.use { server ->
                            val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "k")

                            val thrown = runCatching {
                                runBlocking { client.chat(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "q")))) }
                            }.exceptionOrNull()

                            thrown.shouldNotBeNull()
                            expected.isInstance(thrown) shouldBe true
                            thrown.message shouldContain "boom"
                        }
                    }
                }

                test("extracts the message from Google's array-wrapped error body") {
                    // Google's OpenAI-compatible endpoint wraps the error object in an array AND sends
                    // `code` as an integer where OpenAI sends a string. Verified against the live
                    // endpoint; getting either wrong shows the user 500 characters of raw JSON.
                    StubServer { exchange ->
                        exchange.respond(
                            403,
                            """[{"error":{"code":403,"message":"The provided API key has an IP address restriction.",
                            "status":"PERMISSION_DENIED","details":[{"@type":"x","reason":"y"}]}}]""",
                        )
                    }.use { server ->
                        val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "k")

                        val thrown = runCatching {
                            runBlocking { client.chat(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "q")))) }
                        }.exceptionOrNull().shouldBeInstanceOf<LlmException.Forbidden>()

                        thrown.message shouldBe "The provided API key has an IP address restriction."
                    }
                }

                test("a 429 carries Retry-After through") {
                    StubServer { exchange ->
                        exchange.responseHeaders.add("Retry-After", "30")
                        exchange.respond(429, """{"error":{"message":"slow down"}}""")
                    }.use { server ->
                        val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "k")

                        val thrown = runCatching {
                            runBlocking { client.chat(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "q")))) }
                        }.exceptionOrNull().shouldBeInstanceOf<LlmException.RateLimited>()

                        thrown.retryAfter?.inWholeSeconds shouldBe 30L
                    }
                }

                test("downgrades structured output when the server rejects response_format") {
                    var call = 0
                    StubServer { exchange ->
                        call++
                        if (call == 1) {
                            exchange.respond(400, """{"error":{"message":"response_format is not supported"}}""")
                        } else {
                            exchange.respond(200, """{"choices":[{"message":{"content":"{}"}}]}""")
                        }
                    }.use { server ->
                        val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "k")

                        val response = runBlocking {
                            client.chat(
                                ChatRequest(
                                    "m",
                                    listOf(ChatMessage(ChatRole.USER, "q")),
                                    responseSchema = FindingsSchemaForTest,
                                ),
                            )
                        }

                        response.text shouldBe "{}"
                        call shouldBe 2
                        // First attempt asks for a schema; the retry steps down to json_object.
                        server.requests[0] shouldContain "json_schema"
                        server.requests[1] shouldContain "json_object"
                    }
                }

                test("assembles a streamed response and stops at [DONE]") {
                    StubServer { exchange ->
                        val sse = buildString {
                            appendLine("""data: {"choices":[{"delta":{"content":"Hel"}}]}""")
                            appendLine()
                            appendLine(": a comment that must be ignored")
                            appendLine("""data: {"choices":[{"delta":{"content":"lo"}}]}""")
                            appendLine()
                            appendLine("data: [DONE]")
                            appendLine()
                            appendLine("""data: {"choices":[{"delta":{"content":"IGNORED"}}]}""")
                        }
                        exchange.respond(200, sse, contentType = "text/event-stream")
                    }.use { server ->
                        val client = OpenAiCompatibleClient(server.baseUrl, apiKey = "k")

                        val deltas = runBlocking {
                            client
                                .chatStream(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "q"))))
                                .toList()
                        }

                        deltas.map { it.text } shouldContainExactly listOf("Hel", "lo")
                    }
                }
            }

            context("KapaRetrievalProvider") {
                test("parses a bare array and sends the API key header") {
                    StubServer { exchange ->
                        exchange.respond(
                            200,
                            """[{"source_url":"https://example.com/a","content":"alpha"},
                            {"source_url":"https://example.com/b","content":"beta"}]""",
                        )
                    }.use { server ->
                        val provider = KapaRetrievalProvider(projectId = "p", apiKey = "k", baseUrl = server.baseUrl)

                        val outcome = runBlocking { provider.retrieve("why", 4000) }
                            .shouldBeInstanceOf<RetrievalOutcome.Success>()

                        outcome.chunks shouldHaveSize 2
                        outcome.chunks.first().sourceUrl shouldBe "https://example.com/a"
                        server.header("X-API-KEY") shouldBe "k"
                    }
                }

                test("omits integration_id for a non-default project rather than sending a blank one") {
                    StubServer { exchange -> exchange.respond(200, "[]") }.use { server ->
                        val provider = KapaRetrievalProvider(
                            projectId = "somebody-elses-project",
                            apiKey = "k",
                            baseUrl = server.baseUrl,
                        )

                        runBlocking { provider.retrieve("why", 4000) }

                        // A blank integration_id would fail kapa's UUID validation and take the whole
                        // call down, so the key must be absent entirely.
                        server.requests.single() shouldContain "\"query\""
                        (server.requests.single().contains("integration_id")) shouldBe false
                    }
                }

                test("a configuration error is reported as such and never throws") {
                    StubServer { exchange ->
                        exchange.respond(400, """{"detail":"Integration not found."}""")
                    }.use { server ->
                        val provider = KapaRetrievalProvider(projectId = "p", apiKey = "k", baseUrl = server.baseUrl)

                        val outcome = runBlocking { provider.retrieve("why", 4000) }
                            .shouldBeInstanceOf<RetrievalOutcome.Misconfigured>()

                        outcome.message shouldContain "Integration not found."
                        outcome.chunks shouldHaveSize 0
                    }
                }

                test("a server error is transient, and still never throws") {
                    StubServer { exchange -> exchange.respond(503, "unavailable") }.use { server ->
                        val provider = KapaRetrievalProvider(projectId = "p", apiKey = "k", baseUrl = server.baseUrl)

                        runBlocking { provider.retrieve("why", 4000) }
                            .shouldBeInstanceOf<RetrievalOutcome.Transient>()
                    }
                }

                test("an unreachable host degrades instead of throwing") {
                    // Port 1 is reserved and nothing listens there.
                    val provider = KapaRetrievalProvider(projectId = "p", apiKey = "k", baseUrl = "http://127.0.0.1:1")

                    runBlocking { provider.retrieve("why", 4000) }
                        .shouldBeInstanceOf<RetrievalOutcome.Transient>()
                }

                test("reports itself unconfigured when credentials are missing") {
                    val provider = KapaRetrievalProvider(projectId = "", apiKey = "")
                    provider.isConfigured shouldBe false
                    runBlocking { provider.retrieve("why", 4000) } shouldBe RetrievalOutcome.Disabled
                }
            }
        },
    )

private val FindingsSchemaForTest = ResponseSchema(
    name = "test",
    root = buildJsonObject { put("type", "object") },
)
