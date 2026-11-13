package io.github.inductiveautomation.kindling.ai.openai

import io.github.inductiveautomation.kindling.ai.AiJson
import io.github.inductiveautomation.kindling.ai.ChatDelta
import io.github.inductiveautomation.kindling.ai.ChatRequest
import io.github.inductiveautomation.kindling.ai.ChatResponse
import io.github.inductiveautomation.kindling.ai.ChatRole
import io.github.inductiveautomation.kindling.ai.FinishReason
import io.github.inductiveautomation.kindling.ai.LlmClient
import io.github.inductiveautomation.kindling.ai.LlmException
import io.github.inductiveautomation.kindling.ai.ResponseSchema
import io.github.inductiveautomation.kindling.ai.SSE_DONE
import io.github.inductiveautomation.kindling.ai.SchemaDialects
import io.github.inductiveautomation.kindling.ai.StructuredOutputMode
import io.github.inductiveautomation.kindling.ai.TokenUsage
import io.github.inductiveautomation.kindling.ai.jsonBody
import io.github.inductiveautomation.kindling.ai.sendForLines
import io.github.inductiveautomation.kindling.ai.sendForString
import io.github.inductiveautomation.kindling.ai.sseData
import io.github.inductiveautomation.kindling.utils.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Speaks the OpenAI chat-completions API, which is the de-facto lingua franca: OpenAI, Ollama,
 * LM Studio, OpenRouter, vLLM and Azure all accept it.
 */
class OpenAiCompatibleClient(
    baseUrl: String,
    private val apiKey: String,
    override val contextWindowTokens: Int? = null,
    private val timeout: Duration = Duration.ofSeconds(120),
    requestedMode: StructuredOutputMode = StructuredOutputMode.AUTO,
) : LlmClient {
    private val logger = getLogger<OpenAiCompatibleClient>()

    private val endpoint = URI.create("${baseUrl.trimEnd('/')}/chat/completions")

    override val displayName: String = runCatching { URI.create(baseUrl).host }.getOrNull() ?: baseUrl

    /**
     * Negotiated downwards on first rejection and remembered for the session, so a server that
     * cannot honour schemas costs one failed request rather than one per call.
     */
    @Volatile
    private var mode: StructuredOutputMode = requestedMode

    override suspend fun chat(request: ChatRequest): ChatResponse {
        var attempt = effectiveMode(request.responseSchema)

        while (true) {
            val response = runTransport {
                requestFor(body(request, attempt, stream = false), streaming = false).sendForString()
            }

            if (response.statusCode() in 200..299) {
                return parse(response.body())
            }

            val downgraded = downgradeFor(response.statusCode(), response.body(), attempt)
            if (downgraded == null) {
                throw errorFor(response.statusCode(), response.body(), response.headers())
            }

            logger.debug("{} rejected {}; retrying as {}", displayName, attempt, downgraded)
            mode = downgraded
            attempt = downgraded
        }
    }

    override fun chatStream(request: ChatRequest): Flow<ChatDelta> = flow {
        val response = runTransport {
            requestFor(body(request, effectiveMode(request.responseSchema), stream = true), streaming = true)
                .sendForLines()
        }

        if (response.statusCode() !in 200..299) {
            val body = response.body().use { lines -> lines.toList().joinToString("\n") }
            throw errorFor(response.statusCode(), body, response.headers())
        }

        response.body().use { lines ->
            for (line in lines) {
                val data = sseData(line) ?: continue
                if (data == SSE_DONE) break

                val chunk = runCatching {
                    AiJson.decodeFromString(OpenAiChatResponse.serializer(), data)
                }.getOrNull() ?: continue

                val text = chunk.choices.firstOrNull()?.delta?.content
                if (!text.isNullOrEmpty()) {
                    emit(ChatDelta(text))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun effectiveMode(schema: ResponseSchema?): StructuredOutputMode = when {
        schema == null -> StructuredOutputMode.PROMPT
        mode == StructuredOutputMode.AUTO -> StructuredOutputMode.JSON_SCHEMA
        else -> mode
    }

    private fun requestFor(
        body: String,
        streaming: Boolean,
    ): HttpRequest = HttpRequest
        .newBuilder(endpoint)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", if (streaming) "text/event-stream" else "application/json")
        .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
        .POST(jsonBody(body))
        .build()

    private inline fun <T> runTransport(block: () -> T): T = try {
        block()
    } catch (e: HttpTimeoutException) {
        throw LlmException.Transport("the request timed out after ${timeout.toSeconds()}s", e)
    } catch (e: IOException) {
        throw LlmException.Transport(e.message ?: e.javaClass.simpleName, e)
    }

    private fun body(
        request: ChatRequest,
        structuredMode: StructuredOutputMode,
        stream: Boolean,
    ): String {
        val schema = request.responseSchema

        val messages = request.messages.map { message ->
            OpenAiMessage(role = message.role.wireName, content = message.content)
        }

        // In PROMPT mode the schema cannot ride as a request parameter, so it is appended to the
        // final user turn instead.
        val withInstruction = if (schema != null && structuredMode == StructuredOutputMode.PROMPT) {
            messages.dropLast(1) +
                messages.last().let { last ->
                    last.copy(content = "${last.content.orEmpty()}\n\n${SchemaDialects.toPromptInstruction(schema)}")
                }
        } else {
            messages
        }

        val responseFormat = when {
            schema == null -> null
            structuredMode == StructuredOutputMode.JSON_SCHEMA -> OpenAiResponseFormat(
                type = "json_schema",
                jsonSchema = OpenAiJsonSchema(
                    name = schema.name,
                    strict = true,
                    schema = SchemaDialects.toOpenAiStrict(schema.root),
                ),
            )

            structuredMode == StructuredOutputMode.JSON_OBJECT -> OpenAiResponseFormat(type = "json_object")
            else -> null
        }

        return AiJson.encodeToString(
            OpenAiChatRequest.serializer(),
            OpenAiChatRequest(
                model = request.model,
                messages = withInstruction,
                temperature = request.temperature,
                maxTokens = request.maxOutputTokens,
                stream = stream,
                responseFormat = responseFormat,
            ),
        )
    }

    private fun parse(body: String): ChatResponse {
        val decoded = runCatching {
            AiJson.decodeFromString(OpenAiChatResponse.serializer(), body)
        }.getOrElse { e ->
            throw LlmException.MalformedResponse("The model returned a response that could not be parsed.", e)
        }

        val choice = decoded.choices.firstOrNull()
            ?: throw LlmException.MalformedResponse("The model returned no choices.")

        return ChatResponse(
            text = choice.message?.content.orEmpty(),
            model = decoded.model.orEmpty(),
            finishReason = FinishReason.fromOpenAi(choice.finishReason),
            usage = decoded.usage?.let { TokenUsage(it.promptTokens, it.completionTokens) },
        )
    }

    /**
     * A 400 mentioning `response_format` means this server does not support the mode we asked for.
     * Step down rather than failing: this is the most common incompatibility across self-hosted
     * OpenAI clones, and a hard failure here would make the feature look broken on Ollama.
     */
    private fun downgradeFor(
        status: Int,
        body: String,
        attempted: StructuredOutputMode,
    ): StructuredOutputMode? {
        if (status != 400) return null
        if (!body.contains("response_format", ignoreCase = true) &&
            !body.contains("json_schema", ignoreCase = true)
        ) {
            return null
        }

        return when (attempted) {
            StructuredOutputMode.JSON_SCHEMA -> StructuredOutputMode.JSON_OBJECT
            StructuredOutputMode.JSON_OBJECT -> StructuredOutputMode.PROMPT
            else -> null
        }
    }

    private fun errorFor(
        status: Int,
        body: String,
        headers: HttpHeaders,
    ): LlmException = buildException(status, extractErrorMessage(body) ?: body.take(500).ifBlank { "HTTP $status" }, headers)

    /**
     * Pulls the human-readable message out of an error body.
     *
     * Two shapes in the wild: OpenAI and most clones return `{"error": {...}}`, while Google's
     * OpenAI-compatible endpoint wraps the same object in an array. Falling back to the raw body
     * is correct but shows the user 500 characters of JSON instead of one sentence.
     */
    private fun extractErrorMessage(body: String): String? {
        val asObject = runCatching {
            AiJson.decodeFromString(OpenAiErrorEnvelope.serializer(), body).error.message
        }.getOrNull()
        if (!asObject.isNullOrBlank()) return asObject

        val asArray = runCatching {
            AiJson
                .decodeFromString(ListSerializer(OpenAiErrorEnvelope.serializer()), body)
                .firstOrNull()
                ?.error
                ?.message
        }.getOrNull()

        return asArray?.takeIf(String::isNotBlank)
    }

    private fun buildException(
        status: Int,
        detail: String,
        headers: HttpHeaders,
    ): LlmException = when (status) {
        401 -> LlmException.Authentication(detail)
        403 -> LlmException.Forbidden(detail)
        429 -> LlmException.RateLimited(
            detail,
            headers
                .firstValue("Retry-After")
                .orElse(null)
                ?.toLongOrNull()
                ?.seconds,
        )

        in 400..499 -> LlmException.InvalidRequest(detail)
        else -> LlmException.Transport("HTTP $status: $detail")
    }
}

private val ChatRole.wireName: String
    get() = when (this) {
        ChatRole.SYSTEM -> "system"
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
    }
