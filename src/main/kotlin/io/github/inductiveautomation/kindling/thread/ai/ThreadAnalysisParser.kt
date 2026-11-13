package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.ai.extractJson
import kotlinx.serialization.json.Json

/** Outcome of turning a model response into findings. */
sealed interface ParseResult {
    data class Success(
        val report: FindingsReport,
        /** Ids the model cited that do not exist in the dump. A quality signal worth surfacing. */
        val discardedIds: List<Long>,
    ) : ParseResult

    data class Failure(
        val reason: String,
        val raw: String,
    ) : ParseResult
}

/**
 * Turns a model response into findings, tolerantly.
 *
 * This is the primary defence, not a fallback: plenty of OpenAI-compatible endpoints cannot
 * enforce a schema at all, so a well-formed object wrapped in prose is an expected success case.
 */
object ThreadAnalysisParser {
    private const val MAX_REASON_LENGTH = 300

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // An unrecognised severity or category falls back to the declared default instead of
        // throwing, so model drift degrades gracefully rather than losing the whole report.
        coerceInputValues = true
    }

    fun parse(
        raw: String,
        knownIds: Set<Long>,
    ): ParseResult {
        val body = extractJson(raw)
            ?: return ParseResult.Failure("No JSON object found in the response.", raw)

        val report = runCatching {
            json.decodeFromString(FindingsReport.serializer(), body)
        }.getOrElse { e ->
            return ParseResult.Failure(e.message ?: "Malformed JSON.", raw)
        }

        val (valid, hallucinated) = report.findings.partition { it.threadId in knownIds }

        // Keep the most severe entry when a model reports the same thread twice. Severity's
        // declaration order runs HIGH -> LOW, so the minimum is the most severe.
        val deduplicated = valid
            .groupBy(Finding::threadId)
            .map { (_, group) -> group.minBy { it.severity } }
            .map { it.copy(reason = it.reason.take(MAX_REASON_LENGTH)) }
            .sortedWith(compareBy({ it.severity }, { it.threadId }))

        return ParseResult.Success(
            report = report.copy(findings = deduplicated),
            discardedIds = hallucinated.map(Finding::threadId),
        )
    }
}
