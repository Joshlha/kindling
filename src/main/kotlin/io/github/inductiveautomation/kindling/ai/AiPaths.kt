package io.github.inductiveautomation.kindling.ai

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.text.Charsets.UTF_8

/**
 * Files under `~/.kindling/ai/`, kept deliberately apart from `preferences.json`.
 *
 * Users routinely attach and screenshot `preferences.json` when asking for help on the forum, so
 * keeping credentials out of its blast radius is worth one extra file.
 */
object AiPaths {
    val directory: Path by lazy {
        Path(System.getProperty("user.home"), ".kindling", "ai").also(Path::createDirectories)
    }

    private val supportsPosix: Boolean
        get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun ownerOnlyAttributes(): Array<FileAttribute<*>> = if (supportsPosix) {
        arrayOf(
            PosixFilePermissions.asFileAttribute(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            ),
        )
    } else {
        // Windows: no POSIX bits. ACL manipulation here is fiddly enough that a botched DACL is a
        // likelier outcome than a security win, and %USERPROFILE% already denies other
        // non-administrator users -- the same posture as ~/.aws/credentials.
        emptyArray()
    }

    /**
     * Writes [content] atomically, creating the file with owner-only permissions **at creation**.
     * Applying permissions after writing would leave a window where the secret is world-readable.
     */
    fun writeSecurely(
        target: Path,
        content: String,
    ) {
        val temp = Files.createTempFile(directory, target.fileName.toString(), ".tmp", *ownerOnlyAttributes())
        try {
            Files.write(temp, content.toByteArray(UTF_8))
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }
}
