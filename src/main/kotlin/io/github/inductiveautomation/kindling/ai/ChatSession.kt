package io.github.inductiveautomation.kindling.ai

import kotlinx.coroutines.flow.Flow

/**
 * Conversation state for one tab. Deliberately free of Swing imports so it is testable directly.
 *
 * The document prefix is materialised lazily: a tab whose chat panel is never opened should never
 * pay to render a multi-megabyte thread dump.
 */
class ChatSession(
    private val systemPrompt: String,
    private val documentPreamble: String,
    private val acknowledgement: String,
    /** Called once, off the EDT, to produce the (large) document text. */
    private val document: suspend () -> String,
) {
    enum class State {
        IDLE,
        SENDING,
    }

    private val history = mutableListOf<ChatMessage>()

    var state: State = State.IDLE
        private set

    /** Messages to render. The prefix is excluded: nobody wants to scroll past a thread dump. */
    val visible: List<ChatMessage>
        get() = history.drop(PREFIX_SIZE)

    val isPrimed: Boolean get() = history.isNotEmpty()

    suspend fun prime(standingContext: List<ContextChunk> = emptyList()) {
        if (history.isNotEmpty()) return
        history += PromptAssembler.prefix(
            systemPrompt = systemPrompt,
            documentPreamble = documentPreamble,
            document = document(),
            acknowledgement = acknowledgement,
            standingContext = standingContext,
        )
    }

    /**
     * Builds the request for [question], appending any per-question [context] to *this* turn
     * rather than to the prefix -- which is what keeps the cached prefix byte-identical.
     */
    suspend fun requestFor(
        question: String,
        model: String,
        context: List<ContextChunk> = emptyList(),
    ): ChatRequest {
        prime()
        return ChatRequest(
            model = model,
            messages = history + PromptAssembler.userTurn(question, context),
        )
    }

    /** Records a completed exchange. Call only after a response arrives. */
    fun record(
        question: String,
        answer: String,
        context: List<ContextChunk> = emptyList(),
    ) {
        history += PromptAssembler.userTurn(question, context)
        history += ChatMessage(ChatRole.ASSISTANT, answer)
    }

    fun beginSending() {
        check(state == State.IDLE) { "A request is already in flight" }
        state = State.SENDING
    }

    fun endSending() {
        state = State.IDLE
    }

    fun reset() {
        history.clear()
        state = State.IDLE
    }

    companion object {
        /** system + document + synthetic acknowledgement. */
        const val PREFIX_SIZE = 3
    }
}

/** Convenience for callers that want the whole reply rather than deltas. */
suspend fun Flow<ChatDelta>.joinText(onDelta: (String) -> Unit = {}): String = buildString {
    collect { delta ->
        append(delta.text)
        onDelta(delta.text)
    }
}
