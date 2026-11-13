package io.github.inductiveautomation.kindling.ai

/** A single retrieved passage, with the URL it came from. */
data class ContextChunk(
    val sourceUrl: String,
    val content: String,
)

/**
 * Why a retrieval attempt produced no chunks. The distinction matters: a transient failure may
 * work next time and should stay quiet, while a configuration failure never self-heals and would
 * otherwise let someone believe their knowledge base was being consulted for weeks when it was not.
 */
sealed interface RetrievalOutcome {
    val chunks: List<ContextChunk>

    data class Success(
        override val chunks: List<ContextChunk>,
    ) : RetrievalOutcome

    /** Timeout, 5xx, DNS, connection reset. Log at DEBUG and move on. */
    data class Transient(
        val message: String,
    ) : RetrievalOutcome {
        override val chunks: List<ContextChunk> get() = emptyList()
    }

    /** 400/401/403. Surface once per session; it will not fix itself. */
    data class Misconfigured(
        val message: String,
    ) : RetrievalOutcome {
        override val chunks: List<ContextChunk> get() = emptyList()
    }

    /** Not enabled or not configured. Entirely normal; say nothing. */
    data object Disabled : RetrievalOutcome {
        override val chunks: List<ContextChunk> get() = emptyList()
    }
}

/**
 * Supplies extra prompt context from a knowledge base.
 *
 * Implementations **must not throw**. Context is an enhancement, never a dependency: if retrieval
 * fails, the user still gets their answer, just without it.
 */
interface ContextProvider {
    val displayName: String

    val isConfigured: Boolean

    suspend fun retrieve(
        query: String,
        maxChars: Int,
    ): RetrievalOutcome
}
