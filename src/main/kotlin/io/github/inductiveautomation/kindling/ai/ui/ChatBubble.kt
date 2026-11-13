package io.github.inductiveautomation.kindling.ai.ui

import io.github.inductiveautomation.kindling.ai.ChatRole
import io.github.inductiveautomation.kindling.ai.Markdown
import io.github.inductiveautomation.kindling.ai.Segment
import io.github.inductiveautomation.kindling.core.Kindling.Preferences.Advanced.HyperlinkStrategy
import io.github.inductiveautomation.kindling.utils.FlatScrollPane
import io.github.inductiveautomation.kindling.utils.RSyntaxTextArea
import net.miginfocom.swing.MigLayout
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.HyperlinkEvent

/**
 * One message in the transcript.
 *
 * Markdown is split into prose and code before rendering so stack frames land in a real editor
 * component rather than an HTML `<pre>`, which is both monospaced-correct and selectable.
 */
class ChatBubble(
    private val role: ChatRole,
    /** Return true to claim a link; false falls through to Kindling's hyperlink strategy. */
    private val onLinkActivated: (href: String) -> Boolean,
) : JPanel(MigLayout("ins 4, fillx, wrap 1, gapy 4")) {
    private var body: String = ""

    init {
        border = CompoundBorder(
            MatteBorder(0, 3, 0, 0, accentColor()),
            EmptyBorder(4, 8, 4, 4),
        )
        isOpaque = false

        add(
            JLabel(if (role == ChatRole.USER) "You" else "Assistant").apply {
                font = font.deriveFont(Font.BOLD, font.size - 1f)
                foreground = accentColor()
            },
            "growx",
        )
    }

    /** Replaces the rendered content. Used for both final text and streaming updates. */
    fun setMarkdown(markdown: String) {
        body = markdown

        // Remove everything but the role label and rebuild. Rebuilding beats patching: streaming
        // changes fence structure as it arrives, so incremental edits would mis-render.
        while (componentCount > 1) remove(1)

        for (segment in Markdown.parse(markdown)) {
            when (segment) {
                is Segment.Prose -> add(prose(segment.html), "growx")
                is Segment.Code -> add(code(segment.text), "growx")
            }
        }

        revalidate()
        repaint()
    }

    fun appendDelta(delta: String) = setMarkdown(body + delta)

    private fun prose(html: String) = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = null
        text = html

        addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener

            val href = event.description.orEmpty()
            if (!onLinkActivated(href)) {
                HyperlinkStrategy.currentValue.handleEvent(event)
            }
        }
    }

    private fun code(text: String) = FlatScrollPane(
        RSyntaxTextArea {
            this.text = text
            isEditable = false
            isCodeFoldingEnabled = false
            highlightCurrentLine = false
            syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_JAVA
            rows = text.lines().size.coerceAtMost(MAX_CODE_ROWS)
        },
    ) {
        border = MatteBorder(1, 1, 1, 1, UIManager.getColor("Component.borderColor"))
    }

    private fun accentColor() = if (role == ChatRole.USER) {
        UIManager.getColor("Component.focusColor") ?: UIManager.getColor("Component.borderColor")
    } else {
        UIManager.getColor("Component.borderColor")
    }

    private companion object {
        const val MAX_CODE_ROWS = 24
    }
}
