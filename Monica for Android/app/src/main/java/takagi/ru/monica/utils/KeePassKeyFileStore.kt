package takagi.ru.monica.utils

import takagi.ru.monica.R

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import takagi.ru.monica.data.LocalKeePassDatabase

/**
 * Keeps an optional encrypted copy of a KeePass key file inside Monica's private
 * no-backup directory. The original SAF URI is never removed or replaced.
 */
class KeePassKeyFileStore(
    context: Context,
) {
    private val strings = AppLocaleStringResolver(context)
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, ROOT_DIRECTORY)
    private val securityManager = takagi.ru.monica.security.SecurityManager(appContext)

    fun copyFromUri(uri: Uri, displayName: String? = null): StoredKeyFile {
        val bytes = appContext.contentResolver.readKeePassKeyFileBytes(
            uri = uri,
            unavailableMessage = strings.get(R.string.cloud_message_keyfile_unreadable),
        )
        return copyBytes(bytes, displayName)
    }

    fun copyBytes(bytes: ByteArray, displayName: String? = null): StoredKeyFile = synchronized(IO_LOCK) {
        require(bytes.isNotEmpty()) { strings.get(R.string.cloud_message_keyfile_empty) }

        val fingerprint = fingerprint(bytes)
        val relativePath = relativePathForFingerprint(fingerprint, strings = strings)
        val target = fileForRelativePath(relativePath)
        val existingCopyIsValid = target.isFile && runCatching {
            KeePassKeyFileStore.fingerprint(readInternal(relativePath)) == fingerprint
        }.getOrDefault(false)
        if (!existingCopyIsValid) {
            val encrypted = securityManager.encryptDataLegacyCompat(
                Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
            replaceEncryptedFile(target, encrypted)
        }

        StoredKeyFile(
            relativePath = relativePath,
            fileName = sanitizeDisplayName(displayName),
            fingerprint = fingerprint,
            sizeBytes = bytes.size,
        )
    }

    fun readInternal(relativePath: String): ByteArray = synchronized(IO_LOCK) {
        val target = fileForRelativePath(relativePath)
        check(target.isFile) { strings.get(R.string.cloud_message_keyfile_copy_missing) }
        val encrypted = target.readText(Charsets.UTF_8)
        val encoded = securityManager.decryptData(encrypted)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { strings.get(R.string.cloud_message_keyfile_copy_empty) }
        bytes
    }

    fun read(database: LocalKeePassDatabase): ByteArray? {
        database.keyFileInternalPath?.takeIf { it.isNotBlank() }?.let { return readInternal(it) }
        return database.keyFileUri?.takeIf { it.isNotBlank() }?.let { uri ->
            appContext.contentResolver.readKeePassKeyFileBytes(
                uri = Uri.parse(uri),
                unavailableMessage = strings.get(R.string.cloud_message_keyfile_unreadable),
            )
        }
    }

    /** Migrate only copies owned by this store; unreadable originals remain untouched. */
    internal fun migrateDeviceEncryptedCopies() = synchronized(IO_LOCK) {
        if (!securityManager.isVaultRuntimeUnlocked()) return@synchronized
        root.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { target ->
            if (!securityManager.isVaultRuntimeUnlocked()) return@synchronized
            if (target.length() > 4 * 1024 * 1024) return@forEach
            runCatching {
                val original = target.readText(Charsets.UTF_8)
                if (!takagi.ru.monica.security.LocalCipherEnvelope.isDeviceBound(original)) return@runCatching
                val plain = securityManager.decryptData(original)
                require(Base64.decode(plain, Base64.NO_WRAP).isNotEmpty())
                val replacement = securityManager.encryptData(plain)
                check(replacement.startsWith("MDK|") && securityManager.decryptData(replacement) == plain)
                if (securityManager.isVaultRuntimeUnlocked()) replaceEncryptedFile(target, replacement)
            }
        }
    }

    fun exportInternal(relativePath: String, targetUri: Uri) {
        val bytes = readInternal(relativePath)
        appContext.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error(strings.get(R.string.keepass_operation_write_failed))
    }

    fun deleteInternal(relativePath: String): Boolean = synchronized(IO_LOCK) {
        val target = fileForRelativePath(relativePath)
        !target.exists() || target.delete()
    }

    private fun replaceEncryptedFile(target: File, encrypted: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        val previous = File(target.parentFile, ".${target.name}.previous")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encrypted.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            if (target.exists()) {
                if (previous.exists()) check(previous.delete()) { strings.get(R.string.cloud_message_keyfile_temp_cleanup) }
                check(target.renameTo(previous)) { strings.get(R.string.cloud_message_keyfile_replace) }
            }

            try {
                check(temporary.renameTo(target)) { strings.get(R.string.cloud_message_keyfile_save) }
            } catch (error: Throwable) {
                if (!target.exists() && previous.exists()) previous.renameTo(target)
                throw error
            }
            previous.delete()
        } finally {
            temporary.delete()
            if (target.exists()) previous.delete()
        }
    }

    private fun fileForRelativePath(relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        require(normalized.startsWith("$ROOT_DIRECTORY/")) { strings.get(R.string.cloud_message_keyfile_path_invalid) }
        require(!normalized.contains("../") && !normalized.endsWith("/..")) {
            strings.get(R.string.cloud_message_keyfile_path_invalid)
        }
        val target = File(appContext.noBackupFilesDir, normalized)
        check(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            strings.get(R.string.cloud_message_keyfile_path_invalid)
        }
        return target
    }

    data class StoredKeyFile(
        val relativePath: String,
        val fileName: String,
        val fingerprint: String,
        val sizeBytes: Int,
    )

    companion object {
        private const val ROOT_DIRECTORY = "keepass_keyfiles"
        private val IO_LOCK = Any()

        internal fun relativePathForFingerprint(fingerprint: String, strings: StringResolver): String {
            val normalized = fingerprint.trim().lowercase()
            require(normalized.matches(Regex("[0-9a-f]{16,128}"))) {
                strings.get(R.string.cloud_message_keyfile_fingerprint_invalid)
            }
            return "$ROOT_DIRECTORY/$normalized.bin"
        }

        fun sanitizeDisplayName(value: String?): String {
            return value.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001F]"), "_")
                .trim()
                .take(120)
                .ifBlank { "keyfile" }
        }

        fun fingerprint(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
