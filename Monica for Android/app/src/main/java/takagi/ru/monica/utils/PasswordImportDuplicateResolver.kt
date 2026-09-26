package takagi.ru.monica.utils

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager

data class ImportedPasswordSnapshot(
    val title: String,
    val username: String,
    val website: String,
    val password: String,
    val notes: String = "",
    val email: String = "",
    val phone: String = "",
    val authenticatorKey: String = "",
    val addressLine: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    val creditCardNumber: String = "",
    val creditCardHolder: String = "",
    val creditCardExpiry: String = "",
    val creditCardCVV: String = "",
)

object PasswordImportDuplicateResolver {
    suspend fun findMatchingEntry(
        passwordRepository: PasswordRepository,
        securityManager: SecurityManager,
        snapshot: ImportedPasswordSnapshot,
        localOnly: Boolean,
        includeCandidate: suspend (PasswordEntry) -> Boolean = { true },
        deletedOnly: Boolean = false
    ): PasswordEntry? {
        val candidates = if (deletedOnly) {
            passwordRepository.getDeletedEntries().filter { !localOnly || it.isLocalOnlyEntry() }
        } else if (localOnly) {
            passwordRepository.getLocalDuplicateCandidates(
                title = snapshot.title,
                username = snapshot.username,
                website = snapshot.website
            )
        } else {
            passwordRepository.getDuplicateCandidates(
                title = snapshot.title,
                username = snapshot.username,
                website = snapshot.website
            )
        }

        return candidates.firstOrNull { candidate ->
            matches(candidate, snapshot, securityManager) && includeCandidate(candidate)
        }
    }

    internal fun matches(
        candidate: PasswordEntry,
        snapshot: ImportedPasswordSnapshot,
        securityManager: SecurityManager
    ): Boolean {
        if (!normalizedEquals(candidate.title, snapshot.title)) return false
        if (!normalizedEquals(candidate.username, snapshot.username)) return false
        if (!normalizedEquals(candidate.website, snapshot.website)) return false
        if (candidate.notes != snapshot.notes) return false
        if (!normalizedEquals(candidate.email, snapshot.email)) return false
        if (!normalizedEquals(candidate.phone, snapshot.phone)) return false
        if (candidate.addressLine != snapshot.addressLine || candidate.city != snapshot.city ||
            candidate.state != snapshot.state || candidate.zipCode != snapshot.zipCode ||
            candidate.country != snapshot.country || candidate.creditCardHolder != snapshot.creditCardHolder ||
            candidate.creditCardExpiry != snapshot.creditCardExpiry) return false
        if (!secretEquals(candidate.creditCardNumber, snapshot.creditCardNumber, securityManager)) return false
        if (!secretEquals(candidate.creditCardCVV, snapshot.creditCardCVV, securityManager)) return false
        if (!secretEquals(candidate.authenticatorKey, snapshot.authenticatorKey, securityManager)) return false
        return secretEquals(candidate.password, snapshot.password, securityManager)
    }

    private fun normalizedEquals(left: String?, right: String?): Boolean {
        return normalize(left) == normalize(right)
    }

    private fun secretEquals(left: String, right: String, securityManager: SecurityManager): Boolean {
        if (left == right) return true
        val plainLeft = runCatching { securityManager.decryptDataIfMonicaCiphertext(left) }.getOrNull() ?: return false
        val plainRight = runCatching { securityManager.decryptDataIfMonicaCiphertext(right) }.getOrNull() ?: return false
        return plainLeft == plainRight
    }

    private fun normalize(value: String?): String {
        return value
            ?.replace("\r\n", "\n")
            ?.trim()
            .orEmpty()
    }
}
