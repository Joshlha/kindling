package io.github.inductiveautomation.kindling.ai

/** Who authored a message in a conversation. */
enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    /** When non-null, the provider is asked to emit JSON conforming to this shape. */
    val responseSchema: ResponseSchema? = null,
)

enum class FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    OTHER,
    ;

    companion object {
        fun fromOpenAi(value: String?): FinishReason = when (value) {
            "stop", null -> STOP
            "length" -> LENGTH
            "content_filter" -> CONTENT_FILTER
            else -> OTHER
        }
    }
}

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
)

data class ChatResponse(
    val text: String,
    val model: String,
    val finishReason: FinishReason,
    val usage: TokenUsage? = null,
)

/** One incremental chunk of a streamed response. */
@JvmInline
value class ChatDelta(
    val text: String,
)
