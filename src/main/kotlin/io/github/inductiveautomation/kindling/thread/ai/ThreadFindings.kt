package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.ai.ContextChunk

/**
 * Analysis results for one thread-dump tab, keyed by thread id.
 *
 * Keyed by id rather than row index on purpose: `MultiThreadView.updateData()` rebuilds the whole
 * `ThreadModel` on every filter or search change, so anything index-based would be destroyed the
 * first time a user typed in the search box. Keeping this on the view rather than on `Thread` also
 * avoids putting UI state on a serializable DTO, and avoids the trap that the column lists are
 * shared `data object` singletons -- a findings column would leak one tab's analysis into another.
 */
class ThreadFindings {
    sealed interface State {
        data object Idle : State

        data object Running : State

        data class Complete(
            val count: Int,
            val summary: String,
            val discardedIds: List<Long>,
            val sources: List<ContextChunk>,
        ) : State

        data class Failed(
            val message: String,
            val raw: String? = null,
        ) : State
    }

    private val byId = HashMap<Long, Finding>()
    private val listeners = mutableListOf<() -> Unit>()

    var state: State = State.Idle
        set(value) {
            field = value
            fire()
        }

    operator fun get(id: Long): Finding? = byId[id]

    val ids: Set<Long> get() = byId.keys

    val isEmpty: Boolean get() = byId.isEmpty()

    /** Most severe first, then by id, so the UI ordering is stable. */
    val ordered: List<Finding>
        get() = byId.values.sortedWith(compareBy({ it.severity }, { it.threadId }))

    fun set(
        findings: List<Finding>,
        summary: String,
        discardedIds: List<Long>,
        sources: List<ContextChunk>,
    ) {
        byId.clear()
        for (finding in findings) {
            val existing = byId[finding.threadId]
            if (existing == null || finding.severity < existing.severity) {
                byId[finding.threadId] = finding
            }
        }
        state = State.Complete(byId.size, summary, discardedIds, sources)
    }

    fun clear() {
        byId.clear()
        state = State.Idle
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun fire() = listeners.forEach { it() }
}
