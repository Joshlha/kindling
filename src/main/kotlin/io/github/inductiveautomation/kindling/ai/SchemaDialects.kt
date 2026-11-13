package io.github.inductiveautomation.kindling.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Translates a neutral [ResponseSchema] into provider-specific dialects.
 *
 * Pure functions over [JsonObject], so they are exhaustively testable without a network or a model.
 */
object SchemaDialects {
    private val STRIPPED_KEYWORDS = setOf("\$schema", "title", "default")

    /**
     * OpenAI strict mode is unusually demanding: every object must set
     * `additionalProperties: false`, and `required` must name *every* property -- optional fields
     * are not permitted at all. Both rules apply recursively, including inside array `items`.
     */
    fun toOpenAiStrict(root: JsonObject): JsonObject = strictify(root)

    private fun strictify(node: JsonObject): JsonObject = buildJsonObject {
        val properties = node["properties"]?.jsonObject

        for ((key, value) in node) {
            when {
                key in STRIPPED_KEYWORDS -> Unit
                key == "properties" && value is JsonObject -> {
                    put(
                        "properties",
                        buildJsonObject {
                            for ((propertyName, propertySchema) in value) {
                                put(
                                    propertyName,
                                    if (propertySchema is JsonObject) strictify(propertySchema) else propertySchema,
                                )
                            }
                        },
                    )
                }

                key == "items" && value is JsonObject -> put("items", strictify(value))
                key == "required" -> Unit // rebuilt below from the full property set
                key == "additionalProperties" -> Unit // rebuilt below
                else -> put(key, value)
            }
        }

        if (properties != null) {
            put("additionalProperties", JsonPrimitive(false))
            put(
                "required",
                buildJsonArray {
                    for (propertyName in properties.keys) {
                        add(JsonPrimitive(propertyName))
                    }
                },
            )
        }
    }

    /**
     * For endpoints with no structured-output support at all. Renders the schema into something a
     * model can follow from the prompt alone.
     */
    fun toPromptInstruction(schema: ResponseSchema): String = buildString {
        appendLine("Respond with JSON only. No prose, no markdown code fence.")
        appendLine("The response must conform to this JSON Schema:")
        appendLine(AiJson.encodeToString(JsonObject.serializer(), stripKeywords(schema.root)))
    }

    private fun stripKeywords(node: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in node) {
            when {
                key in STRIPPED_KEYWORDS -> Unit
                value is JsonObject -> put(key, stripKeywords(value))
                value is JsonArray -> put(key, value)
                else -> put(key, value)
            }
        }
    }
}
