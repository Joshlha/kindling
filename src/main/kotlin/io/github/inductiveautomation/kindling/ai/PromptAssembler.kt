package io.github.inductiveautomation.kindling.ai

/**
 * Builds the message list for a conversation about a large, fixed document.
 *
 * The document goes in the **first user message**, not the system message. Two reasons:
 *
 * 1. OpenAI-compatible backends treat the system slot inconsistently, and llama.cpp/Ollama chat
 *    templates frequently truncate it -- a multi-hundred-kilobyte system message is the most
 *    portability-hostile option available.
 * 2. It keeps the prefix byte-identical on every turn, which is what prompt caching keys on. Since
 *    the document is ~95% of every request, that is the difference between paying full price per
 *    turn and roughly a tenth of it.
 *
 * The corollary matters: **never re-render message index 1.** Per-question retrieval context is
 * appended to the *current* user message instead, so the cached prefix survives.
 */
object PromptAssembler {
    /**
     * The immutable conversation prefix: system instructions, the document, and a synthetic
     * acknowledgement that matches what the UI renders as the opening bubble.
     */
    fun prefix(
        systemPrompt: String,
        documentPreamble: String,
        document: String,
        acknowledgement: String,
        standingContext: List<ContextChunk> = emptyList(),
    ): List<ChatMessage> = listOf(
        ChatMessage(ChatRole.SYSTEM, systemPrompt),
        ChatMessage(
            ChatRole.USER,
            buildString {
                if (standingContext.isNotEmpty()) {
                    append(renderChunks(standingContext))
                    append("\n\n")
                }
                append(documentPreamble)
                append("\n\n")
                append(document)
            },
        ),
        ChatMessage(ChatRole.ASSISTANT, acknowledgement),
    )

    /**
     * A single user turn, optionally carrying retrieval context for *this* question only.
     * Appending here rather than to the prefix is what preserves prompt caching.
     */
    fun userTurn(
        question: String,
        context: List<ContextChunk> = emptyList(),
    ): ChatMessage = ChatMessage(
        ChatRole.USER,
        if (context.isEmpty()) {
            question
        } else {
            buildString {
                append(renderChunks(context))
                append("\n\n")
                append(question)
            }
        },
    )

    /**
     * Chunks are rendered with their source URLs so the model can cite them, and so the UI can
     * cross-reference what it shows in the sources strip against what the model actually saw.
     */
    fun renderChunks(chunks: List<ContextChunk>): String = buildString {
        appendLine("=== REFERENCE MATERIAL FROM THE KNOWLEDGE BASE ===")
        appendLine(
            "The following passages were retrieved as potentially relevant. They may or may not " +
                "apply. Cite a source URL only when you actually rely on it.",
        )
        for (chunk in chunks) {
            appendLine()
            appendLine("--- source: ${chunk.sourceUrl}")
            appendLine(chunk.content)
        }
        appendLine("=== END REFERENCE MATERIAL ===")
    }
}
