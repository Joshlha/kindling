package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.ai.ChatMessage
import io.github.inductiveautomation.kindling.ai.ChatRequest
import io.github.inductiveautomation.kindling.ai.ChatRole
import io.github.inductiveautomation.kindling.ai.ContextBudget
import io.github.inductiveautomation.kindling.ai.ContextChunk
import io.github.inductiveautomation.kindling.ai.ContextProvider
import io.github.inductiveautomation.kindling.ai.LlmClient
import io.github.inductiveautomation.kindling.ai.PromptAssembler
import io.github.inductiveautomation.kindling.ai.RetrievalOutcome
import io.github.inductiveautomation.kindling.ai.TokenEstimate
import io.github.inductiveautomation.kindling.utils.getLogger

/** Everything the UI needs from one analysis run. */
data class AnalysisResult(
    val report: FindingsReport,
    val discardedIds: List<Long>,
    val sources: List<ContextChunk>,
    /** Set when retrieval failed in a way that will not fix itself. */
    val contextWarning: String? = null,
)

/**
 * Runs one thread-dump analysis: retrieve context, ask the model, parse, and repair once.
 *
 * Deliberately free of Swing and of coroutine scopes, so the whole pipeline is testable with a
 * fake [LlmClient] and no network.
 */
class ThreadAnalysisService(
    private val client: LlmClient,
    private val model: String,
    private val contextProvider: ContextProvider? = null,
    private val retrievalMaxChars: Int = 8_000,
) {
    private val logger = getLogger<ThreadAnalysisService>()

    suspend fun analyze(context: ThreadDumpContext): Result<AnalysisResult> {
        val budget = ContextBudget(client.contextWindowTokens)

        // Check before retrieving or generating: a dump that cannot fit should cost nothing.
        val dump = context.render()
        val estimate = TokenEstimate.of(dump) + TokenEstimate.of(ThreadAnalysisPrompts.ANALYSIS_PROMPT)

        budget
            .check(estimate) { estimated, window, reserved ->
                describeTooLarge(context, estimated, window, reserved)
            }.onFailure { return Result.failure(it) }

        val retrieval = retrieveFor(context)

        val messages = buildMessages(context, dump, retrieval.chunks)

        return runCatching {
            val first = client.chat(
                ChatRequest(model = model, messages = messages, responseSchema = FindingsSchema),
            )

            when (val parsed = ThreadAnalysisParser.parse(first.text, context.knownIds)) {
                is ParseResult.Success -> parsed.toResult(retrieval)

                is ParseResult.Failure -> {
                    logger.debug("Model returned unparseable analysis; attempting one repair")

                    val repaired = client.chat(
                        ChatRequest(
                            model = model,
                            messages = messages +
                                ChatMessage(ChatRole.ASSISTANT, first.text) +
                                ChatMessage(ChatRole.USER, ThreadAnalysisPrompts.REPAIR_PROMPT),
                            responseSchema = FindingsSchema,
                        ),
                    )

                    // One repair, never a loop: a model that fails twice will not succeed on the
                    // fifth attempt, and each try re-sends the entire dump.
                    when (val second = ThreadAnalysisParser.parse(repaired.text, context.knownIds)) {
                        is ParseResult.Success -> second.toResult(retrieval)
                        is ParseResult.Failure -> throw UnparseableAnalysisException(second.reason, repaired.text)
                    }
                }
            }
        }
    }

    private fun ParseResult.Success.toResult(retrieval: RetrievalOutcome) = AnalysisResult(
        report = report,
        discardedIds = discardedIds,
        sources = retrieval.chunks,
        contextWarning = (retrieval as? RetrievalOutcome.Misconfigured)?.message,
    )

    private suspend fun retrieveFor(context: ThreadDumpContext): RetrievalOutcome {
        val provider = contextProvider?.takeIf { it.isConfigured } ?: return RetrievalOutcome.Disabled

        val outcome = provider.retrieve(RetrievalQueryBuilder.forDump(context), retrievalMaxChars)

        if (outcome is RetrievalOutcome.Transient) {
            logger.debug("Knowledge base unavailable; continuing without context: {}", outcome.message)
        }
        return outcome
    }

    private fun buildMessages(
        context: ThreadDumpContext,
        dump: String,
        chunks: List<ContextChunk>,
    ): List<ChatMessage> {
        val system = buildString {
            append(ThreadAnalysisPrompts.SYSTEM_PROMPT)
            if (chunks.isNotEmpty()) {
                append("\n\n")
                append(ThreadAnalysisPrompts.CONTEXT_WITH_SOURCES)
            }
        }

        return PromptAssembler.prefix(
            systemPrompt = system,
            documentPreamble = ThreadAnalysisPrompts.CONTEXT_PREAMBLE,
            document = dump,
            acknowledgement = ThreadAnalysisPrompts.acknowledgement(context.threadCount, context.dumpCount),
            standingContext = chunks,
        ) + PromptAssembler.userTurn(ThreadAnalysisPrompts.ANALYSIS_PROMPT)
    }

    private fun describeTooLarge(
        context: ThreadDumpContext,
        estimated: Int,
        window: Int,
        reserved: Int,
    ): String = buildString {
        appendLine("This thread dump is too large for the configured model.")
        appendLine()
        appendLine("  Estimated request size   ~${"%,d".format(estimated)} tokens")
        appendLine("  Threads                  ${"%,d".format(context.threadCount)} across ${context.dumpCount} dump(s)")
        appendLine("  Model                    $model")
        appendLine("  Context window           ${"%,d".format(window)} tokens (${"%,d".format(reserved)} reserved for the reply)")
        appendLine()
        appendLine("To proceed:")
        appendLine("  - Use a model with a larger context window (Preferences -> AI Assistant).")
        appendLine("  - Narrow the thread list with the sidebar filters, then analyze again.")
        appendLine("  - Open fewer thread dumps in this tab.")
    }
}

/** The model could not produce parseable findings even after one repair attempt. */
class UnparseableAnalysisException(
    val reason: String,
    val raw: String,
) : Exception("The model did not return valid findings: $reason")
