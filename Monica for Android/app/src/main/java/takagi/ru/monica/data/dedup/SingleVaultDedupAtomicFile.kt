package takagi.ru.monica.data.dedup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import takagi.ru.monica.keepass.KeePassSourceRevision
import takagi.ru.monica.keepass.KeePassSourceSafety

/** Same-directory atomic replacement only. No truncating fallback, shared .tmp, or source rename gap. */
internal object SingleVaultDedupAtomicFile {
    fun replace(target: File, encoded: File, expected: KeePassSourceRevision, replacement: KeePassSourceRevision,
        checkBeforeCommit: () -> Unit, syncDirectory: (File) -> Unit) {
        val parent = requireNotNull(target.parentFile).canonicalFile
        require(target.canonicalFile.parentFile == parent && target.isFile)
        val staging = File.createTempFile(".dedup-", ".kdbx", parent)
        try {
            encoded.inputStream().use { input -> FileOutputStream(staging).use { output ->
                input.copyTo(output); output.flush(); output.fd.sync()
            } }
            check(KeePassSourceSafety.revisionOf(staging) == replacement) { "Native dedup staging verification failed" }
            checkBeforeCommit()
            check(KeePassSourceSafety.revisionOf(target) == expected) { "Native dedup source changed" }
            // The full-file read can be slow: repeat the cheap authorization check after it.
            checkBeforeCommit()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(parent)
            check(KeePassSourceSafety.revisionOf(target) == replacement) { "Native dedup committed verification failed" }
        } finally { if (staging.exists()) check(staging.delete()) { "Unable to remove native dedup staging file" } }
    }
}
