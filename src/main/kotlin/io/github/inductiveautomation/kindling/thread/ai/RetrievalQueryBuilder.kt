package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.thread.model.Thread
import java.lang.Thread.State

/**
 * Derives a short search query describing a dump's symptoms.
 *
 * This is the **only** thing sent to the knowledge base. It must never contain a stack frame, a
 * thread name, or any portion of the dump itself -- both because kapa's query field is capped at
 * 25,000 characters, and because the consent dialog promises the dump is never sent there.
 * `RetrievalQueryBuilderTests` enforces both properties.
 */
object RetrievalQueryBuilder {
    const val MAX_LENGTH = 400

    private const val TOP_POOLS = 3

    /** Standard JVM and idle-pool frames carry no diagnostic signal, so they never drive a query. */
    private val UNINTERESTING_FRAME = Regex(
        """^(java\.base|java\.|jdk\.|sun\.)""",
    )

    fun forDump(context: ThreadDumpContext): String {
        val threads = context.allThreads()

        val parts = buildList {
            add("Ignition ${context.version}")
            add("thread dump analysis")

            if (context.hasDeadlocks) add("deadlock")

            val blocked = threads.filter { it.state == State.BLOCKED }
            if (blocked.isNotEmpty()) {
                add("${blocked.size} threads BLOCKED")
                addAll(topPools(blocked))
            } else {
                addAll(topPools(threads.filter { it.state == State.RUNNABLE }))
            }

            topFrame(blocked.ifEmpty { threads })?.let(::add)
        }

        return parts
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .take(MAX_LENGTH)
    }

    /** Pool names are structural (`webserver-3` -> `webserver`), not customer data. */
    private fun topPools(threads: List<Thread>): List<String> = threads
        .mapNotNull(Thread::pool)
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(TOP_POOLS)
        .map { it.key }

    /**
     * The most common non-JDK frame across the given threads. A fully-qualified class and method
     * is a library identifier, not customer content.
     */
    private fun topFrame(threads: List<Thread>): String? = threads
        .mapNotNull { thread -> thread.stacktrace.firstOrNull { frame -> !UNINTERESTING_FRAME.containsMatchIn(frame) } }
        .map { frame -> frame.substringBefore('(').substringAfter('/') }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    /** For a chat turn the user's own question is already a good search query. */
    fun forQuestion(question: String): String = question.trim().take(MAX_LENGTH)
}
