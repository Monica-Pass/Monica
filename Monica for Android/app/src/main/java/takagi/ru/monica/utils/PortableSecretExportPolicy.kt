package takagi.ru.monica.utils

import java.util.concurrent.CancellationException

internal class PortableSecretExportException(
    val entryTitle: String,
    cause: Throwable
) : IllegalStateException("Sensitive data could not be read for backup", cause)

/** Backups must contain portable values, never ciphertext bound to the current installation. */
internal object PortableSecretExportPolicy {
    fun resolve(
        storedValue: String,
        entryTitle: String,
        decryptIfNeeded: (String) -> String
    ): String {
        if (storedValue.isEmpty()) return ""
        return try {
            decryptIfNeeded(storedValue)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw PortableSecretExportException(entryTitle, error)
        }
    }
}
