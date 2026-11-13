package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.ai.AiAvailability
import io.github.inductiveautomation.kindling.ai.AiPreferences
import io.github.inductiveautomation.kindling.ai.ChatRole
import io.github.inductiveautomation.kindling.ai.ChatSession
import io.github.inductiveautomation.kindling.ai.ContextChunk
import io.github.inductiveautomation.kindling.ai.LlmException
import io.github.inductiveautomation.kindling.ai.Markdown
import io.github.inductiveautomation.kindling.ai.PromptTooLargeException
import io.github.inductiveautomation.kindling.ai.RetrievalOutcome
import io.github.inductiveautomation.kindling.ai.ui.ChatPanel
import io.github.inductiveautomation.kindling.ai.ui.ConsentDialog
import io.github.inductiveautomation.kindling.core.Kindling.Preferences.Advanced.HyperlinkStrategy
import io.github.inductiveautomation.kindling.utils.EDT_SCOPE
import io.github.inductiveautomation.kindling.utils.FlatScrollPane
import io.github.inductiveautomation.kindling.utils.escapeHtml
import io.github.inductiveautomation.kindling.utils.getLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.net.URI
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.event.HyperlinkEvent

/**
 * The AI panel for a thread dump tab: a findings summary on top, a chat transcript below.
 */
class ThreadChatPanel(
    private val context: ThreadDumpContext,
    private val findings: ThreadFindings,
    private val scope: CoroutineScope,
    private val onThreadSelected: (Long) -> Unit,
) : JPanel(MigLayout("ins 0, fill")) {
    private val logger = getLogger<ThreadChatPanel>()

    private val session = ChatSession(
        systemPrompt = ThreadAnalysisPrompts.SYSTEM_PROMPT,
        documentPreamble = ThreadAnalysisPrompts.CONTEXT_PREAMBLE,
        acknowledgement = ThreadAnalysisPrompts.acknowledgement(context.threadCount, context.dumpCount),
        // Rendering a multi-megabyte dump is deferred until the first question is actually asked.
        document = { withContext(Dispatchers.Default) { context.render() } },
    )

    private val findingsView = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                if (!handleLink(event.description.orEmpty())) {
                    HyperlinkStrategy.currentValue.handleEvent(event)
                }
            }
        }
    }

    private val chat = ChatPanel(
        scope = scope,
        onLinkActivated = ::handleLink,
        onSend = ::ask,
    )

    init {
        chat.setHeaderComponent(FlatScrollPane(findingsView) { preferredSize = java.awt.Dimension(0, 150) })
        add(chat, "push, grow")

        findings.addChangeListener { renderFindings() }
        renderFindings()

        chat.addBubble(ChatRole.ASSISTANT).setMarkdown(
            ThreadAnalysisPrompts.acknowledgement(context.threadCount, context.dumpCount),
        )
    }

    /** Programmatic entry point, e.g. the "Ask AI about this thread" popup action. */
    fun ask(question: String): Job {
        val bubble = chat.addBubble(ChatRole.ASSISTANT)

        return scope.launch {
            if (!ConsentDialog.ensureConsent(this@ThreadChatPanel)) {
                bubble.setMarkdown("_Cancelled._")
                return@launch
            }

            try {
                val retrieval = retrieveFor(question)
                if (retrieval is RetrievalOutcome.Misconfigured) {
                    chat.showWarning("Knowledge base unavailable: ${retrieval.message.escapeHtml()}")
                }

                val request = session.requestFor(question, AiAvailability.model, retrieval.chunks)
                val client = AiAvailability.client()

                val answer = StringBuilder()
                withContext(Dispatchers.IO) {
                    client.chatStream(request).collect { delta ->
                        answer.append(delta.text)
                        withContext(EDT_SCOPE.coroutineContext) {
                            bubble.setMarkdown(answer.toString())
                            chat.scrollToBottom()
                        }
                    }
                }

                session.record(question, answer.toString(), retrieval.chunks)

                if (retrieval.chunks.isNotEmpty()) {
                    bubble.setMarkdown(answer.toString() + sourcesMarkdown(retrieval.chunks))
                }
            } catch (e: CancellationException) {
                bubble.setMarkdown(
                    buildString {
                        append("_Stopped._")
                    },
                )
                throw e
            } catch (e: Exception) {
                logger.debug("Chat request failed", e)
                bubble.setMarkdown("_The request failed._")
                chat.showWarning(describe(e).escapeHtml())
            }
        }
    }

    private suspend fun retrieveFor(question: String): RetrievalOutcome {
        val provider = AiAvailability.contextProvider() ?: return RetrievalOutcome.Disabled
        return withContext(Dispatchers.IO) {
            provider.retrieve(
                RetrievalQueryBuilder.forQuestion(question),
                AiPreferences.KapaMaxChars.currentValue,
            )
        }
    }

    private fun sourcesMarkdown(chunks: List<ContextChunk>): String = buildString {
        append("\n\n**Sources consulted**\n")
        for (url in chunks.map(ContextChunk::sourceUrl).distinct()) {
            append("\n- [").append(shortLabel(url)).append("](").append(url).append(')')
        }
    }

    private fun shortLabel(url: String): String = runCatching {
        val uri = URI.create(url)
        "${uri.host}${uri.path}".removeSuffix("/")
    }.getOrDefault(url)

    private fun describe(e: Exception): String = when (e) {
        is PromptTooLargeException -> e.detail
        is LlmException -> e.userMessage
        is UnparseableAnalysisException -> e.message.orEmpty()
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Claims `kindling-thread:` links so the model's prose becomes navigation. */
    private fun handleLink(href: String): Boolean {
        val id = href.removePrefix("${Markdown.THREAD_LINK_SCHEME}:").toLongOrNull()
        if (!href.startsWith("${Markdown.THREAD_LINK_SCHEME}:") || id == null) return false

        onThreadSelected(id)
        return true
    }

    private fun renderFindings() {
        findingsView.text = buildString {
            append("<html><body style='font-family: sans-serif; margin: 4px'>")

            when (val state = findings.state) {
                ThreadFindings.State.Idle ->
                    append("<i>Press <b>Analyze Threads</b> to look for threads of interest.</i>")

                ThreadFindings.State.Running -> append("<i>Analyzing...</i>")

                is ThreadFindings.State.Failed -> {
                    append("<b>Analysis failed.</b><br>")
                    append(state.message.escapeHtml())
                }

                is ThreadFindings.State.Complete -> {
                    append("<b>").append(state.count).append(" finding(s)</b><br>")
                    append(state.summary.escapeHtml())

                    if (findings.ordered.isNotEmpty()) append("<ul>")
                    for (finding in findings.ordered) {
                        append("<li><a href='").append(Markdown.THREAD_LINK_SCHEME).append(':')
                        append(finding.threadId).append("'>#").append(finding.threadId).append("</a> ")
                        append("<b>").append(finding.severity).append("</b> ")
                        append(finding.category).append(" - ")
                        append(finding.reason.escapeHtml())
                        append("</li>")
                    }
                    if (findings.ordered.isNotEmpty()) append("</ul>")

                    if (state.discardedIds.isNotEmpty()) {
                        append("<br><i>")
                        append(state.discardedIds.size)
                        append(" finding(s) referenced thread IDs not present in this dump and were discarded.</i>")
                    }

                    if (state.sources.isNotEmpty()) {
                        append("<br><b>Sources</b><ul>")
                        for (url in state.sources.map(ContextChunk::sourceUrl).distinct()) {
                            append("<li><a href='").append(url).append("'>")
                            append(shortLabel(url).escapeHtml())
                            append("</a></li>")
                        }
                        append("</ul>")
                    }
                }
            }

            append("</body></html>")
        }
    }
}
