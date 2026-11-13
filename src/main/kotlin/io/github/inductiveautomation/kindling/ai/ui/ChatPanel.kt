package io.github.inductiveautomation.kindling.ai.ui

import io.github.inductiveautomation.kindling.ai.ChatRole
import io.github.inductiveautomation.kindling.utils.FlatScrollPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * A generic chat transcript with an input box. Knows nothing about thread dumps.
 *
 * [scope] must be EDT-bound: every callback here touches Swing directly.
 */
class ChatPanel(
    private val scope: CoroutineScope,
    /** Return true to claim a link; false falls through to Kindling's hyperlink strategy. */
    private val onLinkActivated: (href: String) -> Boolean = { false },
    private val onSend: (question: String) -> Job,
) : JPanel(MigLayout("ins 0, fill, flowy, hidemode 3")) {
    /**
     * Without [Scrollable.getScrollableTracksViewportWidth] returning true, the HTML text panes
     * inside bubbles never wrap -- they report an unbounded preferred width and the transcript
     * scrolls sideways forever. This is the single most load-bearing line in the file.
     */
    private val transcript = object : JPanel(MigLayout("fillx, ins 4, wrap 1, gapy 6")), Scrollable {
        override fun getScrollableTracksViewportWidth() = true

        override fun getScrollableTracksViewportHeight() = false

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ) = 16

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ) = visibleRect.height
    }

    private val transcriptScroll = FlatScrollPane(transcript) { border = null }

    private val input = JTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val status = Throbber()
    private val warning = WarningStrip()
    private val sendButton = JButton("Send")

    private var inFlight: Job? = null

    /** Host for tool-specific extras, e.g. a findings list. */
    private val header = JPanel(MigLayout("ins 0, fillx, hidemode 3"))

    init {
        add(header, "growx")
        add(transcriptScroll, "push, grow")
        add(warning, "growx")
        add(
            JPanel(MigLayout("ins 0, fillx, gap 4")).apply {
                add(FlatScrollPane(input), "push, grow")
                add(status, "newline, growx")
                add(sendButton, "align right")
            },
            "growx",
        )

        sendButton.addActionListener { toggleSend() }

        // Enter sends, Shift+Enter inserts a newline -- what every chat UI does, and the opposite
        // of JTextArea's default.
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SEND_ACTION)
        input.actionMap.put(
            SEND_ACTION,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = toggleSend()
            },
        )
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
    }

    private fun toggleSend() {
        // While a request is running the button means Stop. This is the first cancellation UI in
        // Kindling, and it earns its place: a whole thread dump can take minutes to answer.
        inFlight?.takeIf { it.isActive }?.let {
            it.cancel()
            return
        }

        val question = input.text.trim()
        if (question.isEmpty()) return

        input.text = ""
        warning.hideMessage()
        addBubble(ChatRole.USER).setMarkdown(question)

        val job = onSend(question)
        inFlight = job
        setSending(true)

        scope.launch {
            job.join()
            setSending(false)
        }
    }

    private fun setSending(sending: Boolean) {
        sendButton.text = if (sending) "Stop" else "Send"
        status.isRunning = sending
    }

    fun addBubble(role: ChatRole): ChatBubble = ChatBubble(role, onLinkActivated).also { bubble ->
        transcript.add(bubble, "growx")
        transcript.revalidate()
        scrollToBottom()
    }

    fun showWarning(
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) = warning.show(message, actionLabel, action)

    fun hideWarning() = warning.hideMessage()

    fun addNotice(message: String) {
        addBubble(ChatRole.ASSISTANT).setMarkdown("_${message}_")
    }

    fun scrollToBottom() {
        SwingUtilities.invokeLater {
            transcriptScroll.verticalScrollBar.apply { value = maximum }
        }
    }

    fun clearTranscript() {
        transcript.removeAll()
        transcript.revalidate()
        transcript.repaint()
    }

    /** Lets a host panel install a findings view above the transcript. */
    fun setHeaderComponent(component: JComponent) {
        header.removeAll()
        header.add(component, "growx")
        header.revalidate()
    }

    private companion object {
        const val SEND_ACTION = "kindling-ai-send"
    }
}
