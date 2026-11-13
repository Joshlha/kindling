package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.thread.ThreadViewTests
import io.github.inductiveautomation.kindling.thread.model.ThreadDump
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import java.nio.file.Path

private fun dump(name: String): ThreadDump = ThreadDump.fromStream(ThreadViewTests::class.java.getResourceAsStream(name)!!)!!

private fun contextFor(vararg names: String): ThreadDumpContext = ThreadDumpContext(
    paths = names.map { Path.of(it) },
    dumps = names.map(::dump),
)

class ThreadAnalysisTests :
    FunSpec(
        {
            context("ThreadDumpContext rendering") {
                test("every thread id appears exactly once as id=<n>") {
                    val context = contextFor("deadlockThreadDump.json")
                    val rendered = context.render()

                    for (id in context.knownIds) {
                        Regex("""\bid=$id\b""").findAll(rendered).count() shouldBe 1
                    }
                }

                test("knownIds matches the dump") {
                    val context = contextFor("deadlockThreadDump.json")
                    context.knownIds shouldBe dump("deadlockThreadDump.json").threads.map { it.id }.toSet()
                }

                test("rendering is deterministic") {
                    val context = contextFor("deadlockThreadDump.json")
                    context.render() shouldBe context.render()
                }

                test("render(only) restricts to the requested ids but keeps headers") {
                    val context = contextFor("deadlockThreadDump.json")
                    val wanted = context.knownIds.take(2).toSet()
                    val rendered = context.render(wanted)

                    rendered shouldContain "=== THREAD DUMP"
                    for (id in wanted) rendered shouldContain "id=$id"
                    for (id in context.knownIds - wanted) rendered shouldNotContain "--- id=$id "
                }

                test("all four input formats render without throwing") {
                    for (name in listOf(
                        "threadDump.json",
                        "deadlockThreadDump.json",
                        "legacyWebThreadDump.txt",
                        "legacyScriptThreadDump.txt",
                    )) {
                        contextFor(name).render().isNotEmpty() shouldBe true
                    }
                }

                test("multiple dumps are numbered and both contribute ids") {
                    val context = contextFor("threadDump.json", "deadlockThreadDump.json")
                    val rendered = context.render()

                    rendered shouldContain "=== THREAD DUMP 1 of 2"
                    rendered shouldContain "=== THREAD DUMP 2 of 2"
                    context.dumpCount shouldBe 2
                }
            }

            context("RetrievalQueryBuilder - this is all kapa ever receives") {
                val context = contextFor("deadlockThreadDump.json")

                test("stays within the length cap") {
                    RetrievalQueryBuilder.forDump(context).length shouldBeLessThanOrEqual
                        RetrievalQueryBuilder.MAX_LENGTH
                }

                test("never leaks a stack frame or the dump itself") {
                    val query = RetrievalQueryBuilder.forDump(context)

                    query shouldNotContain "--- id="
                    query shouldNotContain "=== THREAD DUMP"
                    query shouldNotContain "stack:"
                    // No frame line, which in this corpus always carries a source location.
                    query shouldNotContain ".java:"
                }

                test("describes the symptoms") {
                    val query = RetrievalQueryBuilder.forDump(context)
                    query shouldContain "Ignition"
                    query shouldContain "deadlock"
                }

                test("is deterministic") {
                    RetrievalQueryBuilder.forDump(context) shouldBe RetrievalQueryBuilder.forDump(context)
                }

                test("a question is passed through, trimmed and capped") {
                    RetrievalQueryBuilder.forQuestion("  why is it slow?  ") shouldBe "why is it slow?"
                    RetrievalQueryBuilder.forQuestion("x".repeat(9999)).length shouldBeLessThanOrEqual
                        RetrievalQueryBuilder.MAX_LENGTH
                }
            }

            context("ThreadAnalysisParser") {
                val known = setOf(1L, 2L, 3L)

                fun parse(raw: String) = ThreadAnalysisParser.parse(raw, known)

                test("parses a clean report") {
                    val result = parse(
                        """{"summary":"ok","findings":[{"threadId":1,"severity":"HIGH","category":"DEADLOCK","reason":"r"}]}""",
                    ).shouldBeInstanceOf<ParseResult.Success>()

                    result.report.findings.single().threadId shouldBe 1L
                    result.discardedIds.shouldBeEmpty()
                }

                test("parses a fenced, prose-wrapped report") {
                    parse(
                        "Here are my findings:\n```json\n{\"summary\":\"s\",\"findings\":[]}\n```\nHope that helps.",
                    ).shouldBeInstanceOf<ParseResult.Success>()
                }

                test("discards hallucinated thread ids and reports them") {
                    val result = parse(
                        """{"summary":"s","findings":[
                        {"threadId":1,"severity":"LOW","category":"SLOW","reason":"a"},
                        {"threadId":999,"severity":"HIGH","category":"CPU","reason":"b"}]}""",
                    ).shouldBeInstanceOf<ParseResult.Success>()

                    result.report.findings.map { it.threadId } shouldContainExactly listOf(1L)
                    result.discardedIds shouldContainExactly listOf(999L)
                }

                test("an unrecognised enum degrades to the default instead of throwing") {
                    val result = parse(
                        """{"summary":"s","findings":[{"threadId":1,"severity":"CATASTROPHIC","category":"WAT","reason":"r"}]}""",
                    ).shouldBeInstanceOf<ParseResult.Success>()

                    result.report.findings.single().severity shouldBe Severity.LOW
                    result.report.findings.single().category shouldBe Category.SUSPICIOUS
                }

                test("a duplicated thread keeps the most severe entry") {
                    val result = parse(
                        """{"summary":"s","findings":[
                        {"threadId":2,"severity":"LOW","category":"SLOW","reason":"low"},
                        {"threadId":2,"severity":"HIGH","category":"DEADLOCK","reason":"high"}]}""",
                    ).shouldBeInstanceOf<ParseResult.Success>()

                    result.report.findings.single().severity shouldBe Severity.HIGH
                }

                test("truncates an over-long reason") {
                    val result = parse(
                        """{"summary":"s","findings":[{"threadId":1,"severity":"LOW","category":"SLOW","reason":"${"x".repeat(900)}"}]}""",
                    ).shouldBeInstanceOf<ParseResult.Success>()

                    result.report.findings.single().reason.length shouldBeLessThanOrEqual 300
                }

                test("an empty object is a success with no findings") {
                    val result = parse("{}").shouldBeInstanceOf<ParseResult.Success>()
                    result.report.findings.shouldBeEmpty()
                }

                test("pure prose is a failure - the expected no-schema case") {
                    parse("I had a look and everything seems fine.").shouldBeInstanceOf<ParseResult.Failure>()
                }

                test("findings come back sorted most severe first") {
                    val result = parse(
                        """{"summary":"s","findings":[
                        {"threadId":3,"severity":"LOW","category":"SLOW","reason":"c"},
                        {"threadId":1,"severity":"HIGH","category":"DEADLOCK","reason":"a"},
                        {"threadId":2,"severity":"MEDIUM","category":"CPU","reason":"b"}]}""",
                    ).shouldBeInstanceOf<ParseResult.Success>()

                    result.report.findings.map { it.severity } shouldContainExactly
                        listOf(Severity.HIGH, Severity.MEDIUM, Severity.LOW)
                }
            }

            context("Prompt and parser agree") {
                val prompt = ThreadAnalysisPrompts.ANALYSIS_PROMPT

                test("every severity the parser knows is described in the prompt") {
                    for (severity in Severity.entries) prompt shouldContain severity.name
                }

                test("every category the parser knows is described in the prompt") {
                    for (category in Category.entries) prompt shouldContain category.name
                }

                test("the prompt teaches the id= spelling the renderer emits") {
                    prompt shouldContain "id=<n>"
                    ThreadAnalysisPrompts.CONTEXT_PREAMBLE shouldContain "id="
                }

                test("the JSON example embedded in the prompt actually parses") {
                    val example = prompt.substringAfterLast("Respond with JSON only").substringAfter('\n').trim()

                    val report = Json { ignoreUnknownKeys = true }
                        .decodeFromString(FindingsReport.serializer(), example)

                    report.findings.single().threadId shouldBe 1842L
                    report.findings.single().severity shouldBe Severity.HIGH
                }
            }

            context("ThreadFindings survives model rebuilds because it is keyed by id") {
                test("stores, orders, and clears") {
                    val findings = ThreadFindings()
                    var fired = 0
                    findings.addChangeListener { fired++ }

                    findings.set(
                        listOf(
                            Finding(threadId = 5, severity = Severity.LOW),
                            Finding(threadId = 1, severity = Severity.HIGH),
                        ),
                        summary = "s",
                        discardedIds = emptyList(),
                        sources = emptyList(),
                    )

                    findings.ordered.map { it.threadId } shouldContainExactly listOf(1L, 5L)
                    findings[1L]?.severity shouldBe Severity.HIGH
                    fired shouldBe 1

                    findings.clear()
                    findings.isEmpty shouldBe true
                    fired shouldBe 2
                }

                test("collapses duplicates to the most severe") {
                    val findings = ThreadFindings()
                    findings.set(
                        listOf(
                            Finding(threadId = 7, severity = Severity.LOW),
                            Finding(threadId = 7, severity = Severity.HIGH),
                        ),
                        summary = "",
                        discardedIds = emptyList(),
                        sources = emptyList(),
                    )

                    findings.ids shouldBe setOf(7L)
                    findings[7L]?.severity shouldBe Severity.HIGH
                }
            }
        },
    )
