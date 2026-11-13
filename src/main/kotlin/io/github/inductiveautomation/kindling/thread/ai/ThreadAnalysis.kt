package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.ai.ResponseSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Three levels, not five. The table can only carry a handful of distinguishable highlight colours
 * before it stops communicating anything, and declaration order doubles as sort order.
 */
@Serializable
enum class Severity {
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
enum class Category {
    DEADLOCK,
    CONTENTION,
    SLOW,
    CPU,
    LEAK,
    EXCEPTION,
    SUSPICIOUS,
}

@Serializable
data class Finding(
    val threadId: Long,
    val severity: Severity = Severity.LOW,
    val category: Category = Category.SUSPICIOUS,
    val reason: String = "",
    /** Which of the open dumps this applies to, zero-based. Empty means "all of them". */
    val dumpIndexes: List<Int> = emptyList(),
)

@Serializable
data class FindingsReport(
    val summary: String = "",
    val findings: List<Finding> = emptyList(),
)

/**
 * Deliberately restricted to the subset every provider accepts: no `$ref`, no `oneOf`, no
 * `format`, no numeric bounds. [io.github.inductiveautomation.kindling.ai.SchemaDialects] adapts
 * it per provider.
 */
val FindingsSchema: ResponseSchema = ResponseSchema(
    name = "thread_findings",
    root = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("summary") {
                put("type", "string")
                put("description", "Two or three sentences assessing the gateway's overall health.")
            }
            putJsonObject("findings") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("threadId") {
                            put("type", "integer")
                            put("description", "A thread id appearing verbatim as id=<n> in the dump.")
                        }
                        putJsonObject("severity") {
                            put("type", "string")
                            putJsonArray("enum") {
                                for (value in Severity.entries) add(JsonPrimitive(value.name))
                            }
                        }
                        putJsonObject("category") {
                            put("type", "string")
                            putJsonArray("enum") {
                                for (value in Category.entries) add(JsonPrimitive(value.name))
                            }
                        }
                        putJsonObject("reason") {
                            put("type", "string")
                            put("description", "One or two sentences naming the specific evidence.")
                        }
                        putJsonObject("dumpIndexes") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "integer") }
                        }
                    }
                    putJsonArray("required") {
                        add("threadId")
                        add("severity")
                        add("category")
                        add("reason")
                        add("dumpIndexes")
                    }
                }
            }
        }
        putJsonArray("required") {
            add("summary")
            add("findings")
        }
    },
)
