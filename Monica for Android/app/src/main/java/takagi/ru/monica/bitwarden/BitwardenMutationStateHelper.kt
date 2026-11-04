package takagi.ru.monica.bitwarden

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem

object BitwardenMutationStateHelper {

    fun normalizePasswordInsert(candidate: PasswordEntry): PasswordEntry {
        if (!candidate.hasBitwardenBinding()) return candidate
        if (candidate.hasBitwardenCipherBinding()) return candidate
        if (candidate.bitwardenLocalModified) return candidate

        return candidate.copy(bitwardenLocalModified = true)
    }

    fun normalizePasswordUpdate(
        existing: PasswordEntry?,
        candidate: PasswordEntry
    ): PasswordEntry {
        if (existing == null) return candidate
        if (!existing.hasBitwardenCipherBinding()) return candidate
        if (!candidate.hasBitwardenBinding()) return candidate
        if (!hasSameRemotePasswordBinding(existing, candidate)) return candidate
        if (candidate.bitwardenLocalModified) return candidate

        return if (hasRemoteRelevantPasswordChanges(existing, candidate)) {
            candidate.copy(bitwardenLocalModified = true)
        } else {
            candidate
        }
    }

    fun normalizeSecureItemUpdate(
        existing: SecureItem?,
        candidate: SecureItem
    ): SecureItem {
        if (existing == null) return candidate
        if (existing.bitwardenVaultId == null || existing.bitwardenCipherId.isNullOrBlank()) return candidate
        if (candidate.bitwardenVaultId == null) return candidate
        if (!hasSameRemoteSecureItemBinding(existing, candidate)) return candidate
        if (candidate.bitwardenLocalModified && candidate.syncStatus == "PENDING") return candidate

        return if (hasRemoteRelevantSecureItemChanges(existing, candidate)) {
            candidate.copy(
                bitwardenLocalModified = true,
                syncStatus = if (candidate.syncStatus == "REFERENCE") "REFERENCE" else "PENDING"
            )
        } else {
            candidate
        }
    }

    private fun hasSameRemotePasswordBinding(
        existing: PasswordEntry,
        candidate: PasswordEntry
    ): Boolean {
        return existing.bitwardenVaultId == candidate.bitwardenVaultId &&
            existing.bitwardenCipherId == candidate.bitwardenCipherId
    }

    private fun hasSameRemoteSecureItemBinding(
        existing: SecureItem,
        candidate: SecureItem
    ): Boolean {
        return existing.bitwardenVaultId == candidate.bitwardenVaultId &&
            existing.bitwardenCipherId == candidate.bitwardenCipherId
    }

    private fun hasRemoteRelevantPasswordChanges(
        existing: PasswordEntry,
        candidate: PasswordEntry
    ): Boolean {
        return existing.title != candidate.title ||
            existing.website != candidate.website ||
            existing.username != candidate.username ||
            existing.password != candidate.password ||
            existing.notes != candidate.notes ||
            existing.isFavorite != candidate.isFavorite ||
            existing.appPackageName != candidate.appPackageName ||
            existing.appName != candidate.appName ||
            existing.email != candidate.email ||
            existing.phone != candidate.phone ||
            existing.addressLine != candidate.addressLine ||
            existing.city != candidate.city ||
            existing.state != candidate.state ||
            existing.zipCode != candidate.zipCode ||
            existing.country != candidate.country ||
            existing.creditCardNumber != candidate.creditCardNumber ||
            existing.creditCardHolder != candidate.creditCardHolder ||
            existing.creditCardExpiry != candidate.creditCardExpiry ||
            existing.creditCardCVV != candidate.creditCardCVV ||
            existing.authenticatorKey != candidate.authenticatorKey ||
            existing.loginType != candidate.loginType ||
            existing.ssoProvider != candidate.ssoProvider ||
            existing.ssoRefEntryId != candidate.ssoRefEntryId
    }

    private fun hasRemoteRelevantSecureItemChanges(
        existing: SecureItem,
        candidate: SecureItem
    ): Boolean {
        return existing.title != candidate.title ||
            existing.notes != candidate.notes ||
            existing.isFavorite != candidate.isFavorite ||
            existing.itemData != candidate.itemData ||
            existing.imagePaths != candidate.imagePaths ||
            existing.isArchived != candidate.isArchived ||
            existing.archivedAt != candidate.archivedAt ||
            existing.bitwardenArchiveIntent != candidate.bitwardenArchiveIntent
    }
}
