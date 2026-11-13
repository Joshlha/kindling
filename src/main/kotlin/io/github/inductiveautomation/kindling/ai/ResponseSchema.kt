package io.github.inductiveautomation.kindling.ai

import kotlinx.serialization.json.JsonObject

/**
 * A provider-neutral description of the JSON we want back.
 *
 * [root] is written in the common subset every provider understands -- `type`, `description`,
 * `enum`, `items`, `properties`, `required` -- and each client renders it into its own dialect via
 * [SchemaDialects]. Authoring once and translating avoids maintaining a schema AST.
 */
data class ResponseSchema(
    val name: String,
    val root: JsonObject,
)

/**
 * How a given endpoint wants structured output requested. Support varies wildly across
 * OpenAI-compatible servers: Ollama, older llama.cpp builds and some vLLM configurations ignore
 * `response_format` entirely, so the client negotiates downwards and remembers what worked.
 */
enum class StructuredOutputMode {
    /** Try [JSON_SCHEMA], fall back to [JSON_OBJECT], then [PROMPT]. Remembered per base URL. */
    AUTO,
    JSON_SCHEMA,
    JSON_OBJECT,

    /** Schema described in the prompt; the response is salvaged by `extractJson`. */
    PROMPT,
}
