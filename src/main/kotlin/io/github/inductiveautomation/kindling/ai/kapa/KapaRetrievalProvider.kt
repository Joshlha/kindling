package io.github.inductiveautomation.kindling.ai.kapa

import io.github.inductiveautomation.kindling.ai.AiJson
import io.github.inductiveautomation.kindling.ai.ContextChunk
import io.github.inductiveautomation.kindling.ai.ContextProvider
import io.github.inductiveautomation.kindling.ai.RetrievalOutcome
import io.github.inductiveautomation.kindling.ai.jsonBody
import io.github.inductiveautomation.kindling.ai.sendForString
import io.github.inductiveautomation.kindling.utils.getLogger
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Retrieves knowledge-base passages from kapa to enrich a prompt.
 *
 * **This never sees the thread dump.** It receives only a short derived search query. That is both
 * a privacy property worth stating in the consent dialog and a hard requirement: kapa caps its
 * query field at 25,000 characters.
 *
 * Nothing here throws. Retrieval is an enhancement, so every failure degrades to "no extra
 * context" and the user still gets their answer.
 */
class KapaRetrievalProvider(
    private val projectId: String,
    private val apiKey: String,
    private val baseUrl: String = KapaDefaults.BASE_URL,
    private val timeout: Duration = Duration.ofSeconds(30),
) : ContextProvider {
    private val logger = getLogger<KapaRetrievalProvider>()

    override val displayName: String = "kapa.ai"

    override val isConfigured: Boolean
        get() = projectId.isNotBlank() && apiKey.isNotBlank()

    /**
     * The trailing slash is mandatory. Without it kapa redirects, and a redirect-following client
     * turns the POST into a GET, producing a thoroughly misleading
     * `405 Method "GET" not allowed`. The shared client sets `followRedirects(NEVER)` so that a
     * mistake here fails visibly instead.
     */
    private val endpoint: URI
        get() = URI.create("${baseUrl.trimEnd('/')}/query/v1/projects/$projectId/retrieval/")

    /**
     * Attribution is only valid against the project the integration belongs to, so a user who
     * points Kindling at their own kapa project gets no integration id rather than a guaranteed
     * `400 Integration not found.`
     */
    private val integrationId: String?
        get() = KapaDefaults.INTEGRATION_ID
            .takeIf { it.isNotBlank() && projectId == KapaDefaults.PROJECT_ID }

    override suspend fun retrieve(
        query: String,
        maxChars: Int,
    ): RetrievalOutcome {
        if (!isConfigured) return RetrievalOutcome.Disabled

        return try {
            val body = AiJson.encodeToString(
                KapaRetrievalRequest.serializer(),
                KapaRetrievalRequest(query = query, maxChars = maxChars, integrationId = integrationId),
            )

            val response = HttpRequest
                .newBuilder(endpoint)
                .timeout(timeout)
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(jsonBody(body))
                .build()
                .sendForString()

            when (response.statusCode()) {
                in 200..299 -> RetrievalOutcome.Success(parse(response.body()))

                // These never self-heal: a bad key, a revoked integration, or a project the key
                // cannot reach will fail identically forever. Surfaced once so nobody believes
                // their knowledge base is being consulted when it is not.
                400, 401, 403, 404 -> RetrievalOutcome.Misconfigured(describe(response.statusCode(), response.body()))

                else -> RetrievalOutcome.Transient("kapa returned HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.debug("kapa retrieval failed; continuing without context", e)
            RetrievalOutcome.Transient(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parse(body: String): List<ContextChunk> = runCatching {
        AiJson
            .decodeFromString(ListSerializer(KapaChunk.serializer()), body)
            .filter { it.content.isNotBlank() }
            .map { ContextChunk(sourceUrl = it.sourceUrl, content = it.content) }
    }.getOrElse { e ->
        logger.debug("Could not parse kapa retrieval response", e)
        emptyList()
    }

    /** kapa's own wording is far more actionable than anything we could paraphrase. */
    private fun describe(
        status: Int,
        body: String,
    ): String {
        val detail = runCatching {
            AiJson.decodeFromString(KapaErrorDetail.serializer(), body).detail
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: body.take(300).ifBlank { "HTTP $status" }

        return "kapa: $detail"
    }
}
