package io.github.inductiveautomation.kindling.ai

import io.github.inductiveautomation.kindling.utils.getLogger
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.text.Charsets.UTF_8

/**
 * API keys for the LLM endpoint and for kapa.
 *
 * Stored unencrypted. That is a deliberate choice, not an oversight: home-rolled encryption with a
 * machine-derived key is obfuscation, buys nothing against malware running as the user, and breaks
 * on OS reinstall or profile roaming. This is the same posture as `~/.aws/credentials`,
 * `~/.npmrc`, and `gh auth`. The mitigations we *do* provide are real: the file sits outside the
 * one users share for support, it is owner-only where the filesystem supports it, it is never
 * logged, and there is a one-click Clear.
 */
object SecretStore {
    private val logger = getLogger<SecretStore>()

    private const val LLM_ENV = "KINDLING_AI_API_KEY"
    private const val KAPA_ENV = "KINDLING_KAPA_API_KEY"

    private val file: Path get() = AiPaths.directory.resolve("credentials.json")

    /**
     * Secrets never appear in [toString]. Kotlin data classes print every property, and a single
     * `logger.debug("$stored")` would write a live credential into a user's log file forever.
     */
    @Serializable
    private data class Stored(
        val llmApiKey: String = "",
        val kapaApiKey: String = "",
    ) {
        override fun toString(): String = "Stored(llmApiKey=${redact(llmApiKey)}, kapaApiKey=${redact(kapaApiKey)})"

        private fun redact(value: String) = if (value.isEmpty()) "(unset)" else "(${value.length} chars)"
    }

    private fun read(): Stored = runCatching {
        if (!file.exists()) return@runCatching Stored()
        AiJson.decodeFromString(Stored.serializer(), Files.readString(file, UTF_8))
    }.getOrElse { e ->
        logger.debug("Could not read AI credentials; treating as unset", e)
        Stored()
    }

    private fun write(stored: Stored) {
        runCatching {
            AiPaths.writeSecurely(file, AiJson.encodeToString(Stored.serializer(), stored))
        }.onFailure { e ->
            logger.error("Failed to save AI credentials", e)
        }
    }

    /** Environment wins, so CI and the security-conscious can avoid on-disk storage entirely. */
    var llmApiKey: String
        get() = System.getenv(LLM_ENV)?.takeIf(String::isNotBlank) ?: read().llmApiKey
        set(value) = write(read().copy(llmApiKey = value))

    var kapaApiKey: String
        get() = System.getenv(KAPA_ENV)?.takeIf(String::isNotBlank) ?: read().kapaApiKey
        set(value) = write(read().copy(kapaApiKey = value))

    /** True when the value comes from the environment and so cannot be edited in the UI. */
    val llmKeyFromEnvironment: Boolean get() = !System.getenv(LLM_ENV).isNullOrBlank()
    val kapaKeyFromEnvironment: Boolean get() = !System.getenv(KAPA_ENV).isNullOrBlank()

    fun clearAll() {
        runCatching { Files.deleteIfExists(file) }
            .onFailure { e -> logger.error("Failed to clear AI credentials", e) }
    }
}
