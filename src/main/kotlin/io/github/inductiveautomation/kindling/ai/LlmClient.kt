package io.github.inductiveautomation.kindling.ai

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * A chat-completion provider. Kindling ships one implementation (OpenAI-compatible), but the
 * interface exists so the UI never knows which endpoint it is talking to.
 */
interface LlmClient {
    /** Shown in the UI and in the data-egress consent dialog, e.g. "api.openai.com". */
    val displayName: String

    /**
     * Context window in tokens, or null when unknown -- which is the common case, since
     * OpenAI-compatible endpoints do not advertise it.
     */
    val contextWindowTokens: Int?

    suspend fun chat(request: ChatRequest): ChatResponse

    fun chatStream(request: ChatRequest): Flow<ChatDelta>
}

/**
 * Failures a caller might reasonably handle differently.
 *
 * [InvalidRequest] deliberately carries the provider's own message verbatim: a 400 from a
 * self-hosted model is frequently the only explanation of what it disliked, and paraphrasing it
 * loses the only actionable detail.
 */
sealed class LlmException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 401, or a locally-known missing credential. The UI should point at Preferences. */
    class Authentication(
        message: String,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    /** 403. Credentials are valid but not permitted for this resource. */
    class Forbidden(
        message: String,
    ) : LlmException(message)

    class RateLimited(
        message: String,
        val retryAfter: Duration? = null,
    ) : LlmException(message)

    /** 4xx a retry will not fix: unknown model, rejected schema, payload too large. */
    class InvalidRequest(
        message: String,
    ) : LlmException(message)

    /** Connection refused, DNS, TLS, timeout. Worth retrying. */
    class Transport(
        message: String,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    /** 2xx whose body did not parse, or structured output that was not valid JSON. */
    class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    /** A short, user-facing explanation. Never includes a stack trace. */
    open val userMessage: String
        get() = when (this) {
            is Authentication -> "$message Check your API key in Preferences → AI Assistant."
            is Forbidden -> message.orEmpty()
            is RateLimited -> message.orEmpty()
            is InvalidRequest -> message.orEmpty()
            is Transport -> "Could not reach the model: ${message.orEmpty()}"
            is MalformedResponse -> message.orEmpty()
        }
}
