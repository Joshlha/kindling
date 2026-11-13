package io.github.inductiveautomation.kindling.ai

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** One piece of a model reply: either prose to render as HTML, or code to render verbatim. */
sealed interface Segment {
    data class Prose(
        val html: String,
    ) : Segment

    data class Code(
        val language: String?,
        val text: String,
    ) : Segment
}

/**
 * Splits a markdown reply into prose and code segments, converting the prose to the HTML 3.2
 * subset Swing's [javax.swing.JTextPane] understands.
 *
 * Code is separated out *before* HTML conversion so it can be shown in a real syntax-highlighting
 * editor rather than a `<pre>` block, which matters because the code in these replies is almost
 * always stack frames the user wants to read carefully.
 */
object Markdown {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().build()

    private val THREAD_REFERENCE = Regex("""#(\d{1,19})\b""")

    /** Links `#1842` to the thread table. See `ChatPanel.onLinkActivated`. */
    const val THREAD_LINK_SCHEME = "kindling-thread"

    fun parse(markdown: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val prose = StringBuilder()

        fun flushProse() {
            val text = prose.toString().trim()
            if (text.isNotEmpty()) {
                segments += Segment.Prose(toHtml(text))
            }
            prose.setLength(0)
        }

        var index = 0
        val lines = markdown.lines()

        while (index < lines.size) {
            val line = lines[index]

            if (line.trimStart().startsWith("```")) {
                flushProse()

                val language = line.trimStart().removePrefix("```").trim().ifEmpty { null }
                val body = StringBuilder()
                index++

                // An unterminated fence means a truncated reply; treat the remainder as code.
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    body.appendLine(lines[index])
                    index++
                }
                index++ // consume the closing fence, if there was one

                segments += Segment.Code(language, body.toString().trimEnd())
            } else {
                prose.appendLine(line)
                index++
            }
        }

        flushProse()
        return segments
    }

    private fun toHtml(markdown: String): String {
        val rendered = renderer.render(parser.parse(markdown))
        return linkifyThreadReferences(rendered)
    }

    /**
     * Rewrites `#1842` into a clickable link. Turning the model's prose into navigation is cheap
     * and is the difference between reading about a thread and looking at it.
     */
    internal fun linkifyThreadReferences(html: String): String = THREAD_REFERENCE.replace(html) { match ->
        val id = match.groupValues[1]
        """<a href="$THREAD_LINK_SCHEME:$id">#$id</a>"""
    }
}
