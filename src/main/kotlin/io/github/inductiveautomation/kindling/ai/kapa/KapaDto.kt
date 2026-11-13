package io.github.inductiveautomation.kindling.ai.kapa

import io.github.inductiveautomation.kindling.ai.kapa.KapaDefaults.PROJECT_ID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-secret kapa identifiers, compiled in deliberately.
 *
 * Neither value is a credential. An integration id grants nothing on its own -- kapa answers 401
 * without an API key and 403 with a wrong one -- and kapa publishes integration ids itself: the
 * website widget's `data-website-id` attribute *is* an integration id, sitting in client-side HTML
 * on every documentation site running the widget. A project id is likewise inert, since API keys
 * are themselves project-scoped. **No API key is ever compiled in.**
 */
object KapaDefaults {
    const val BASE_URL = "https://api.kapa.ai"

    /** Inductive Automation's kapa project. Overridable via preferences. */
    const val PROJECT_ID = "2d401f4c-ffa5-438a-b228-ab52d9e3f59e"

    /**
     * Tags Kindling's calls in kapa analytics. Fixed, with no user-facing override: integrations
     * are scoped to a project, so this value is only meaningful alongside [PROJECT_ID].
     */
    const val INTEGRATION_ID = "e9edf1e9-28f6-4f9e-8cd6-8a903ae1777d"
}

@Serializable
internal data class KapaRetrievalRequest(
    val query: String,
    @SerialName("max_chars") val maxChars: Int,
    /**
     * Omitted entirely rather than sent blank: kapa validates this when present, and an empty
     * string fails UUID validation and takes the whole call down with it.
     */
    @SerialName("integration_id") val integrationId: String? = null,
)

/** The retrieval endpoint answers with a bare JSON array of these. */
@Serializable
internal data class KapaChunk(
    @SerialName("source_url") val sourceUrl: String = "",
    val content: String = "",
)

@Serializable
internal data class KapaErrorDetail(
    val detail: String = "",
)
