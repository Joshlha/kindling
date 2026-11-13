package io.github.inductiveautomation.kindling.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class OpenAiMessage(
    /**
     * Defaulted so a response that omits it still parses. We always set it explicitly on requests;
     * being strict here only buys failures against the sloppier self-hosted servers.
     */
    val role: String = "",
    val content: String? = null,
)

@Serializable
internal data class OpenAiJsonSchema(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonObject,
)

@Serializable
internal data class OpenAiResponseFormat(
    /** "json_schema" or "json_object". */
    val type: String,
    @SerialName("json_schema") val jsonSchema: OpenAiJsonSchema? = null,
)

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = null,
    /**
     * Deliberately `max_tokens` rather than `max_completion_tokens`. OpenAI deprecated the former
     * for its newest models, but every self-hosted clone still expects it and OpenAI continues to
     * accept it for the models people actually point this at.
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("response_format") val responseFormat: OpenAiResponseFormat? = null,
)

@Serializable
internal data class OpenAiDelta(
    val content: String? = null,
)

@Serializable
internal data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
internal data class OpenAiChatResponse(
    val model: String? = null,
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

/**
 * Only `message` is modelled on purpose. Providers disagree on the types of the neighbouring
 * fields -- OpenAI sends `code` as a string, Google sends an integer -- and declaring them would
 * make the whole decode throw on exactly the responses we most need to read. `ignoreUnknownKeys`
 * skips them harmlessly.
 */
@Serializable
internal data class OpenAiErrorBody(
    val message: String = "",
)

@Serializable
internal data class OpenAiErrorEnvelope(
    val error: OpenAiErrorBody = OpenAiErrorBody(),
)
