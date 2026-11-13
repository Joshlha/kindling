package io.github.inductiveautomation.kindling.ai

import io.github.inductiveautomation.kindling.ai.kapa.KapaDefaults
import io.github.inductiveautomation.kindling.ai.kapa.KapaRetrievalProvider
import io.github.inductiveautomation.kindling.ai.openai.OpenAiCompatibleClient
import java.time.Duration

/**
 * The single place that answers "can the AI features run, and against what".
 *
 * Everything is read fresh rather than cached, because preferences can change while a tool tab is
 * open and a stale client would silently keep talking to the old endpoint.
 */
object AiAvailability {
    /**
     * A key is not required: local endpoints (Ollama, LM Studio) routinely need none, so demanding
     * one would lock out the configuration with the best privacy properties.
     */
    val isConfigured: Boolean
        get() = AiPreferences.BaseUrl.currentValue.isNotBlank() && AiPreferences.Model.currentValue.isNotBlank()

    val unavailableReason: String?
        get() = when {
            AiPreferences.BaseUrl.currentValue.isBlank() -> "Set an LLM endpoint in Preferences → AI Assistant."
            AiPreferences.Model.currentValue.isBlank() -> "Set a model name in Preferences → AI Assistant."
            else -> null
        }

    val model: String
        get() = AiPreferences.Model.currentValue

    fun client(): LlmClient = OpenAiCompatibleClient(
        baseUrl = AiPreferences.BaseUrl.currentValue,
        apiKey = SecretStore.llmApiKey,
        contextWindowTokens = AiPreferences.ContextWindow.currentValue.takeIf { it > 0 },
        timeout = Duration.ofSeconds(AiPreferences.RequestTimeout.currentValue.toLong()),
    )

    /** Null when the knowledge base is switched off or not configured. */
    fun contextProvider(): ContextProvider? {
        if (!AiPreferences.EnableKapaContext.currentValue) return null

        val projectId = AiPreferences.KapaProjectId.currentValue.ifBlank { KapaDefaults.PROJECT_ID }
        val apiKey = SecretStore.kapaApiKey
        if (projectId.isBlank() || apiKey.isBlank()) return null

        return KapaRetrievalProvider(projectId = projectId, apiKey = apiKey)
    }

    /** Named destinations for the data-egress consent dialog. */
    fun egressTargets(): List<String> = buildList {
        add(client().displayName)
        contextProvider()?.let { add(it.displayName) }
    }
}
