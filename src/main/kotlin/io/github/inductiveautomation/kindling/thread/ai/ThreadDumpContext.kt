package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.ai.TokenEstimate
import io.github.inductiveautomation.kindling.thread.model.Thread
import io.github.inductiveautomation.kindling.thread.model.ThreadDump
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Renders the thread dumps open in a tab into prompt text, and owns the set of thread ids the
 * model is allowed to cite.
 *
 * The rendering is compact and normalised, which does three jobs at once: it is ~17% smaller than
 * the raw dump JSON, it collapses all four of Kindling's input formats into one shape so a single
 * prompt handles them, and it fixes the `id=<n>` spelling that findings are matched against.
 */
class ThreadDumpContext(
    private val paths: List<Path>,
    private val dumps: List<ThreadDump>,
) {
    /** Every id the model may legitimately cite. Anything else is a hallucination. */
    val knownIds: Set<Long> = dumps.flatMapTo(HashSet()) { dump -> dump.threads.map(Thread::id) }

    val threadCount: Int = dumps.sumOf { it.threads.size }

    val dumpCount: Int = dumps.size

    val version: String = dumps.firstOrNull()?.version.orEmpty()

    val hasDeadlocks: Boolean = dumps.any { it.deadlockIds.isNotEmpty() }

    /** Rendering the full dump is not cheap, and the result is used more than once per request. */
    private val fullRender: String by lazy { renderUncached(null) }

    val estimatedTokens: Int by lazy { TokenEstimate.of(fullRender) }

    /** [only] restricts output to a subset of thread ids, preserving the dump headers. */
    fun render(only: Set<Long>? = null): String = if (only == null) fullRender else renderUncached(only)

    private fun renderUncached(only: Set<Long>?): String = buildString {
        dumps.forEachIndexed { index, dump ->
            if (index > 0) appendLine()
            appendDumpHeader(index, dump)
            appendLine()

            val threads = if (only == null) dump.threads else dump.threads.filter { it.id in only }
            threads.forEachIndexed { threadIndex, thread ->
                if (threadIndex > 0) appendLine()
                appendThread(thread)
            }
        }
    }

    private fun StringBuilder.appendDumpHeader(
        index: Int,
        dump: ThreadDump,
    ) {
        val name = paths.getOrNull(index)?.name ?: "dump ${index + 1}"
        appendLine(
            "=== THREAD DUMP ${index + 1} of ${dumps.size} - $name - Ignition ${dump.version} - " +
                "${dump.threads.size} threads ===",
        )
        appendLine(
            "Deadlocked thread ids: " +
                dump.deadlockIds.joinToString(", ").ifEmpty { "none" },
        )
    }

    private fun StringBuilder.appendThread(thread: Thread) {
        append("--- id=").append(thread.id)
        append(" \"").append(thread.name).append('"')
        append(" state=").append(thread.state)
        append(" daemon=").append(thread.isDaemon)
        thread.cpuUsage?.let { append(" cpu=").append(String.format("%.3f", it)).append('%') }
        thread.system?.let { append(" system=").append(it) }
        thread.scope?.let { append(" scope=").append(it) }
        append(" depth=").append(thread.stacktrace.size)
        appendLine()

        thread.blocker?.let { appendLine("  waiting for: $it") }

        if (thread.lockedMonitors.isNotEmpty()) {
            val rendered = thread.lockedMonitors.joinToString(", ") { monitor ->
                if (monitor.frame != null) "${monitor.lock} (at ${monitor.frame})" else monitor.lock
            }
            appendLine("  locked monitors: $rendered")
        }

        if (thread.lockedSynchronizers.isNotEmpty()) {
            appendLine("  locked synchronizers: ${thread.lockedSynchronizers.joinToString(", ")}")
        }

        if (thread.stacktrace.isNotEmpty()) {
            appendLine("  stack:")
            for (frame in thread.stacktrace) {
                append("    ").appendLine(frame)
            }
        }
    }

    /** All threads, flattened across dumps. Used to derive a retrieval query. */
    internal fun allThreads(): List<Thread> = dumps.flatMap { it.threads }

    internal fun deadlockIds(): List<Long> = dumps.flatMap { it.deadlockIds }
}
