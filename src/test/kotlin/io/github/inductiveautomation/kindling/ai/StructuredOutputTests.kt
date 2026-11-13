package io.github.inductiveautomation.kindling.ai

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.data.blocking.forAll
import io.kotest.data.row
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class StructuredOutputTests :
    FunSpec(
        {
            context("extractJson salvages JSON from real-world model replies") {
                test("recognised shapes") {
                    forAll(
                        row("bare object", """{"a":1}""", """{"a":1}"""),
                        row("json fence", "```json\n{\"a\":1}\n```", """{"a":1}"""),
                        row("bare fence", "```\n{\"a\":1}\n```", """{"a":1}"""),
                        row("prose before", "Here you go:\n{\"a\":1}", """{"a":1}"""),
                        row("prose after", "{\"a\":1}\nHope that helps!", """{"a":1}"""),
                        row("prose both sides", "Sure:\n{\"a\":1}\nDone.", """{"a":1}"""),
                        row("leading whitespace", "   \n  {\"a\":1}  ", """{"a":1}"""),
                        row("nested braces", """{"a":{"b":2}}""", """{"a":{"b":2}}"""),
                    ) { clue, input, expected ->
                        withClue(clue) { extractJson(input) shouldBe expected }
                    }
                }

                test("unterminated fence still yields the object") {
                    // Truncated responses look exactly like this.
                    extractJson("```json\n{\"a\":1}") shouldBe """{"a":1}"""
                }

                test("returns null when there is no object at all") {
                    forAll(
                        row(""),
                        row("   "),
                        row("I could not find any problems."),
                        row("}{"),
                    ) { input ->
                        extractJson(input).shouldBeNull()
                    }
                }
            }

            context("SchemaDialects.toOpenAiStrict") {
                val json = Json { prettyPrint = false }

                fun parse(text: String) = json.decodeFromString(JsonObject.serializer(), text)

                test("adds additionalProperties false and promotes every property into required") {
                    val strict = SchemaDialects.toOpenAiStrict(
                        parse(
                            """
                        {"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}},
                         "required":["a"]}
                            """.trimIndent(),
                        ),
                    )

                    strict["additionalProperties"].toString() shouldBe "false"
                    strict["required"].toString() shouldBe """["a","b"]"""
                }

                test("recurses into array items") {
                    val strict = SchemaDialects.toOpenAiStrict(
                        parse(
                            """
                        {"type":"object","properties":{"xs":{"type":"array",
                         "items":{"type":"object","properties":{"id":{"type":"integer"}}}}}}
                            """.trimIndent(),
                        ),
                    )

                    val items = strict["properties"]!!
                        .let { it as JsonObject }["xs"]!!
                        .let { it as JsonObject }["items"]!!
                        .let { it as JsonObject }

                    items["additionalProperties"].toString() shouldBe "false"
                    items["required"].toString() shouldBe """["id"]"""
                }

                test("strips keywords OpenAI strict mode rejects") {
                    val strict = SchemaDialects.toOpenAiStrict(
                        parse("""{"${'$'}schema":"x","title":"y","type":"object","properties":{"a":{"type":"string"}}}"""),
                    )

                    strict.containsKey("\$schema") shouldBe false
                    strict.containsKey("title") shouldBe false
                }
            }

            context("TokenEstimate and ContextBudget") {
                test("estimate grows with input length") {
                    val short = TokenEstimate.of("a".repeat(100))
                    val long = TokenEstimate.of("a".repeat(1000))
                    (long > short) shouldBe true
                }

                test("an unknown window never blocks") {
                    ContextBudget(windowTokens = null)
                        .check(5_000_000) { _, _, _ -> "" }
                        .isSuccess shouldBe true
                }

                test("fails only once the estimate plus reserved output exceeds the window") {
                    val budget = ContextBudget(windowTokens = 10_000, reservedForOutput = 2_000)

                    budget.check(8_000) { _, _, _ -> "" }.isSuccess shouldBe true
                    budget.check(8_001) { _, _, _ -> "" }.isFailure shouldBe true
                }

                test("warns about large payloads only when the window is unknown") {
                    ContextBudget(null).shouldWarnAboutUnknownWindow(200_000) shouldBe true
                    ContextBudget(null).shouldWarnAboutUnknownWindow(1_000) shouldBe false
                    ContextBudget(1_000_000).shouldWarnAboutUnknownWindow(200_000) shouldBe false
                }
            }
        },
    )
