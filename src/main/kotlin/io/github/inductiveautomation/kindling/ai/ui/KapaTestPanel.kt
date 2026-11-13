package io.github.inductiveautomation.kindling.ai.ui

import io.github.inductiveautomation.kindling.ai.AiPreferences
import io.github.inductiveautomation.kindling.ai.RetrievalOutcome
import io.github.inductiveautomation.kindling.ai.SecretStore
import io.github.inductiveautomation.kindling.ai.kapa.KapaDefaults
import io.github.inductiveautomation.kindling.ai.kapa.KapaRetrievalProvider
import io.github.inductiveautomation.kindling.utils.EDT_SCOPE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.net.URI
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The kapa API key field, plus a connection test.
 *
 * The test is worth its weight: a single retrieval call validates the project id, the API key and
 * the integration id together, and kapa's own error wording ("Integration not found.") is far more
 * actionable than anything we could paraphrase.
 */
class KapaTestPanel : JPanel(MigLayout("ins 0, fillx, gap 6, wrap 1")) {
    private val status = JLabel(" ")

    init {
        add(
            ApiKeyPanel(
                read = { SecretStore.kapaApiKey },
                write = { SecretStore.kapaApiKey = it },
                fromEnvironment = { SecretStore.kapaKeyFromEnvironment },
                environmentVariable = "KINDLING_KAPA_API_KEY",
            ),
            "growx",
        )

        add(
            JPanel(MigLayout("ins 0, gap 6")).apply {
                add(
                    JButton("Test connection").apply {
                        addActionListener { test() }
                    },
                )
                add(status, "growx")
            },
            "growx",
        )
    }

    private fun test() {
        status.text = "Testing..."

        EDT_SCOPE.launch {
            val projectId = AiPreferences.KapaProjectId.currentValue.ifBlank { KapaDefaults.PROJECT_ID }
            val key = SecretStore.kapaApiKey

            if (projectId.isBlank() || key.isBlank()) {
                status.text = "Enter a kapa project ID and API key first."
                return@launch
            }

            val provider = KapaRetrievalProvider(projectId = projectId, apiKey = key)

            val outcome = withContext(Dispatchers.IO) {
                provider.retrieve("Ignition gateway threads blocked deadlock", maxChars = 4_000)
            }

            status.text = when (outcome) {
                is RetrievalOutcome.Success -> {
                    val hosts = outcome.chunks
                        .mapNotNull { runCatching { URI.create(it.sourceUrl).host }.getOrNull() }
                        .distinct()
                    "OK - ${outcome.chunks.size} passage(s) from ${hosts.joinToString(", ").ifEmpty { "the knowledge base" }}"
                }

                is RetrievalOutcome.Misconfigured -> outcome.message
                is RetrievalOutcome.Transient -> "Could not reach kapa: ${outcome.message}"
                RetrievalOutcome.Disabled -> "Not configured."
            }
        }
    }
}
