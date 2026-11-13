package io.github.inductiveautomation.kindling.ai

/**
 * Recovers a JSON document from a model response that may be wrapped in prose or a fenced code
 * block.
 *
 * This is load-bearing rather than defensive: many OpenAI-compatible endpoints cannot enforce a
 * schema at all, so prompt-only JSON is a supported mode and not merely a fallback. Do not remove
 * this on the grounds that "we have a schema now".
 */
fun extractJson(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    val unfenced = stripFence(trimmed)

    val start = unfenced.indexOf('{')
    val end = unfenced.lastIndexOf('}')
    if (start == -1 || end == -1 || end <= start) return null

    return unfenced.substring(start, end + 1)
}

/**
 * Removes a leading ```` ```json ```` / ```` ``` ```` fence and its closing counterpart. An
 * unterminated fence is treated as opening-only, which is what truncated responses look like.
 */
private fun stripFence(text: String): String {
    if (!text.startsWith("```")) return text

    val afterOpening = text.indexOf('\n').let { if (it == -1) return text else it + 1 }
    val closing = text.lastIndexOf("```")

    return if (closing > afterOpening) {
        text.substring(afterOpening, closing)
    } else {
        text.substring(afterOpening)
    }
}
