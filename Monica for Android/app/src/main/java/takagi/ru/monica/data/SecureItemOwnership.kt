package takagi.ru.monica.data

sealed class SecureItemOwnership {
    object MonicaLocal : SecureItemOwnership()

    data class KeePass(
        val databaseId: Long,
        val entryUuid: String?
    ) : SecureItemOwnership()

    data class Bitwarden(
        val vaultId: Long?,
        val cipherId: String?
    ) : SecureItemOwnership()

    data class Mdbx(
        val databaseId: Long
    ) : SecureItemOwnership()

    data class Conflict(
        val hasKeePassBinding: Boolean,
        val hasBitwardenBinding: Boolean,
        val hasMdbxBinding: Boolean = false
    ) : SecureItemOwnership()
}

fun SecureItem.resolveOwnership(): SecureItemOwnership = resolveSecureItemOwnership(
    keepassDatabaseId, keepassEntryUuid, keepassGroupUuid, keepassGroupPath,
    bitwardenVaultId, bitwardenCipherId, bitwardenRevisionDate, bitwardenFolderId,
    bitwardenLocalModified, syncStatus, mdbxDatabaseId
)

/** Shared by full records and the content-free backup metadata projection. */
internal fun resolveSecureItemOwnership(
    keepassDatabaseId: Long?,
    keepassEntryUuid: String?,
    keepassGroupUuid: String?,
    keepassGroupPath: String?,
    bitwardenVaultId: Long?,
    bitwardenCipherId: String?,
    bitwardenRevisionDate: String?,
    bitwardenFolderId: String?,
    bitwardenLocalModified: Boolean,
    syncStatus: String,
    mdbxDatabaseId: Long?
): SecureItemOwnership {
    val hasKeePassBinding = keepassDatabaseId != null
    val hasBitwardenBinding = bitwardenVaultId != null || !bitwardenCipherId.isNullOrBlank()
    val hasMdbxBinding = mdbxDatabaseId != null
    val hasConcreteKeePassBinding =
        !keepassEntryUuid.isNullOrBlank() ||
            !keepassGroupUuid.isNullOrBlank() ||
            !keepassGroupPath.isNullOrBlank()
    val hasConcreteBitwardenBinding =
        !bitwardenCipherId.isNullOrBlank() ||
            !bitwardenRevisionDate.isNullOrBlank() ||
            !bitwardenFolderId.isNullOrBlank() ||
            bitwardenLocalModified ||
            !syncStatus.equals("NONE", ignoreCase = true)
    if (hasMdbxBinding && (hasKeePassBinding || hasBitwardenBinding)) {
        return SecureItemOwnership.Conflict(
            hasKeePassBinding = hasKeePassBinding,
            hasBitwardenBinding = hasBitwardenBinding,
            hasMdbxBinding = hasMdbxBinding
        )
    }

    return when {
        hasKeePassBinding && hasBitwardenBinding -> when {
            hasConcreteKeePassBinding && !hasConcreteBitwardenBinding -> SecureItemOwnership.KeePass(
                databaseId = keepassDatabaseId!!,
                entryUuid = keepassEntryUuid
            )

            hasConcreteBitwardenBinding && !hasConcreteKeePassBinding -> SecureItemOwnership.Bitwarden(
                vaultId = bitwardenVaultId,
                cipherId = bitwardenCipherId
            )

            !hasConcreteKeePassBinding && !hasConcreteBitwardenBinding -> SecureItemOwnership.MonicaLocal

            else -> SecureItemOwnership.Conflict(
                hasKeePassBinding = true,
                hasBitwardenBinding = true
            )
        }

        hasKeePassBinding -> SecureItemOwnership.KeePass(
            databaseId = keepassDatabaseId!!,
            entryUuid = keepassEntryUuid
        )
        hasMdbxBinding -> SecureItemOwnership.Mdbx(mdbxDatabaseId!!)
        hasBitwardenBinding -> if (hasConcreteBitwardenBinding) {
            SecureItemOwnership.Bitwarden(
                vaultId = bitwardenVaultId,
                cipherId = bitwardenCipherId
            )
        } else {
            SecureItemOwnership.MonicaLocal
        }
        else -> SecureItemOwnership.MonicaLocal
    }
}

fun SecureItem.hasBitwardenBinding(): Boolean {
    return bitwardenVaultId != null || !bitwardenCipherId.isNullOrBlank()
}

fun SecureItem.asMonicaLocalCopy(categoryId: Long?): SecureItem {
    return copy(
        id = 0,
        categoryId = categoryId,
        keepassDatabaseId = null,
        keepassGroupPath = null,
        keepassEntryUuid = null,
        keepassGroupUuid = null,
        isDeleted = false,
        deletedAt = null,
        bitwardenVaultId = null,
        bitwardenCipherId = null,
        bitwardenFolderId = null,
        bitwardenRevisionDate = null,
        bitwardenLocalModified = false,
        mdbxDatabaseId = null,
        syncStatus = "NONE"
    )
}

fun SecureItem.isBitwardenOwned(): Boolean = resolveOwnership() is SecureItemOwnership.Bitwarden

fun SecureItem.isKeePassOwned(): Boolean = resolveOwnership() is SecureItemOwnership.KeePass

fun SecureItem.isMdbxOwned(): Boolean = resolveOwnership() is SecureItemOwnership.Mdbx

fun SecureItem.hasOwnershipConflict(): Boolean = resolveOwnership() is SecureItemOwnership.Conflict

fun SecureItem.isLocalOnlyItem(): Boolean = resolveOwnership() is SecureItemOwnership.MonicaLocal
