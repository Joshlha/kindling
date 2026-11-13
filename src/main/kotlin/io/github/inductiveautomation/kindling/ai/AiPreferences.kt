package io.github.inductiveautomation.kindling.ai

import io.github.inductiveautomation.kindling.ai.kapa.KapaDefaults
import io.github.inductiveautomation.kindling.ai.ui.ApiKeyPanel
import io.github.inductiveautomation.kindling.ai.ui.KapaTestPanel
import io.github.inductiveautomation.kindling.core.Preference
import io.github.inductiveautomation.kindling.core.Preference.Companion.PreferenceCheckbox
import io.github.inductiveautomation.kindling.core.Preference.Companion.preference
import io.github.inductiveautomation.kindling.core.PreferenceCategory
import io.github.inductiveautomation.kindling.utils.DocumentAdapter
import org.jdesktop.swingx.JXTextField
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

data object AiPreferences : PreferenceCategory {
    override val displayName: String = "AI Assistant"
    override val serialKey: String = "ai"

    val BaseUrl: Preference<String> = preference(
        name = "LLM Endpoint",
        serialKey = "baseUrl",
        description = "An OpenAI-compatible base URL. Works with OpenAI, Ollama, LM Studio, " +
            "OpenRouter, vLLM, and Azure. A local endpoint means your thread dumps never leave this machine.",
        default = "https://api.openai.com/v1",
        editor = {
            JXTextField("https://api.openai.com/v1").apply {
                text = currentValue
                document.addDocumentListener(
                    DocumentAdapter {
                        currentValue = text
                    },
                )
            }
        },
    )

    val Model: Preference<String> = preference(
        name = "Model",
        serialKey = "model",
        description = "The model name to request, e.g. gpt-4o-mini, llama3.1:8b, or qwen2.5-coder.",
        default = "gpt-4o-mini",
        editor = {
            JXTextField("gpt-4o-mini").apply {
                text = currentValue
                document.addDocumentListener(
                    DocumentAdapter {
                        currentValue = text
                    },
                )
            }
        },
    )

    val ApiKey: Preference<Boolean> = virtual(
        name = "LLM API Key",
        description = "Stored unencrypted at ~/.kindling/ai/credentials.json. Use a key scoped to " +
            "this machine and revoke it if your machine is compromised. Local endpoints usually need no key.",
    ) {
        ApiKeyPanel(
            read = { SecretStore.llmApiKey },
            write = { SecretStore.llmApiKey = it },
            fromEnvironment = { SecretStore.llmKeyFromEnvironment },
            environmentVariable = "KINDLING_AI_API_KEY",
        )
    }

    val ContextWindow: Preference<Int> = preference(
        name = "Context Window",
        serialKey = "contextWindow",
        description = "Token limit of your model, used to check a dump will fit before sending it. " +
            "Leave at 0 if you don't know - Kindling will warn instead of blocking.",
        default = 0,
        editor = {
            JSpinner(SpinnerNumberModel(currentValue, 0, 10_000_000, 1_000)).apply {
                addChangeListener { currentValue = value as Int }
            }
        },
    )

    val RequestTimeout: Preference<Int> = preference(
        name = "Request Timeout",
        serialKey = "requestTimeout",
        description = "Seconds to wait for a response. Whole thread dumps can take a while.",
        default = 180,
        editor = {
            JSpinner(SpinnerNumberModel(currentValue, 10, 3_600, 10)).apply {
                addChangeListener { currentValue = value as Int }
            }
        },
    )

    val EnableKapaContext: Preference<Boolean> = preference(
        name = "Knowledge Base Context",
        serialKey = "enableKapaContext",
        description = "Inductive Automation employees only. Looks up related support tickets and " +
            "internal discussions and includes them as context. Only a short summary of the dump's " +
            "symptoms is sent to kapa - never the thread dump itself.",
        default = false,
        editor = {
            PreferenceCheckbox("Enrich answers with kapa.ai knowledge base context")
        },
    )

    val KapaProjectId: Preference<String> = preference(
        name = "Kapa Project ID",
        serialKey = "kapaProjectId",
        description = "Leave blank to use the default Inductive Automation project.",
        default = KapaDefaults.PROJECT_ID,
        editor = {
            JXTextField("Inductive Automation project")
                .apply {
                    text = currentValue
                    document.addDocumentListener(
                        DocumentAdapter {
                            currentValue = text
                        },
                    )
                }.dependsOn(EnableKapaContext) { enabled -> isEnabled = enabled }
        },
    )

    val KapaApiKey: Preference<Boolean> = virtual(
        name = "Kapa API Key",
        description = "From the kapa dashboard under Settings -> API Keys. Stored unencrypted at " +
            "~/.kindling/ai/credentials.json.",
    ) {
        KapaTestPanel()
    }

    val KapaMaxChars: Preference<Int> = preference(
        name = "Knowledge Base Context Size",
        serialKey = "kapaMaxChars",
        description = "Maximum characters of retrieved reference material to include. Larger means " +
            "more context but a bigger, slower, costlier prompt.",
        default = 8_000,
        editor = {
            JSpinner(SpinnerNumberModel(currentValue, 1_000, 60_000, 1_000)).apply {
                addChangeListener { currentValue = value as Int }
            }
        },
    )

    /**
     * Acknowledgement of the data-egress notice. Hidden: it is set by the dialog, not by hand.
     */
    val ConsentAcknowledged: Preference<Boolean> = preference(
        name = "Data Egress Acknowledged",
        serialKey = "consentAcknowledged",
        default = false,
        editor = null,
    )

    override val preferences: List<Preference<*>> = listOf(
        BaseUrl,
        Model,
        ApiKey,
        ContextWindow,
        RequestTimeout,
        EnableKapaContext,
        KapaProjectId,
        KapaApiKey,
        KapaMaxChars,
    )
}

/**
 * A Preferences entry that renders a component but stores nothing in `preferences.json`.
 *
 * [Preference] never reads or writes on its own -- `currentValue`'s setter is the only write path
 * -- so an editor that never assigns it persists nothing. That is what keeps API keys out of the
 * file users attach to forum posts.
 *
 * This is a mild abuse of the API. The clean fix is for [PreferenceCategory] to be able to
 * contribute non-preference components; worth doing if a third case appears.
 */
private fun PreferenceCategory.virtual(
    name: String,
    description: String,
    editor: () -> JComponent,
): Preference<Boolean> = preference(
    name = name,
    serialKey = "unused${name.filter(Char::isLetterOrDigit)}",
    description = description,
    default = false,
    editor = { editor() },
)
