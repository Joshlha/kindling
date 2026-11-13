package io.github.inductiveautomation.kindling.ai

import kotlin.math.ceil

/**
 * Cheap token estimation. Deliberately an estimate: tokenizers are model-specific and shipping one
 * for every OpenAI-compatible backend is not worth it when the only decision this informs is
 * "will this plausibly fit".
 */
object TokenEstimate {
    /**
     * Thread dumps are punctuation- and CamelCase-dense, so they tokenize considerably worse than
     * prose (which runs closer to 4). Measured against real Ignition dumps.
     */
    private const val CHARS_PER_TOKEN = 3.4

    /** Errs high, so the pre-flight check fails before the provider does rather than after. */
    private const val SAFETY_FACTOR = 1.15

    fun of(text: String): Int = ceil(text.length / CHARS_PER_TOKEN * SAFETY_FACTOR).toInt()

    fun of(messages: List<ChatMessage>): Int = messages.sumOf { of(it.content) }
}

/** Thrown before any network call when the request cannot possibly fit. */
class PromptTooLargeException(
    val estimatedTokens: Int,
    val windowTokens: Int,
    val reservedForOutput: Int,
    val detail: String,
) : Exception(detail)

/**
 * Decides whether a request is worth sending. When the window is unknown -- the common case for
 * OpenAI-compatible endpoints -- this never blocks; the caller warns instead.
 */
class ContextBudget(
    val windowTokens: Int?,
    val reservedForOutput: Int = 8_192,
) {
    fun check(
        estimatedTokens: Int,
        describe: (estimated: Int, window: Int, reserved: Int) -> String,
    ): Result<Unit> = when {
        windowTokens == null -> Result.success(Unit)
        estimatedTokens + reservedForOutput <= windowTokens -> Result.success(Unit)
        else -> Result.failure(
            PromptTooLargeException(
                estimatedTokens = estimatedTokens,
                windowTokens = windowTokens,
                reservedForOutput = reservedForOutput,
                detail = describe(estimatedTokens, windowTokens, reservedForOutput),
            ),
        )
    }

    /** True when we cannot prove it fits and the payload is large enough to be worth a warning. */
    fun shouldWarnAboutUnknownWindow(estimatedTokens: Int): Boolean = windowTokens == null && estimatedTokens > UNKNOWN_WINDOW_WARN_THRESHOLD

    companion object {
        const val UNKNOWN_WINDOW_WARN_THRESHOLD = 100_000
    }
}
