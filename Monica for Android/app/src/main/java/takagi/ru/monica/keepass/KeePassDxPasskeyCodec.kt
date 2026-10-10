package takagi.ru.monica.keepass

import android.util.Base64
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.EntryValue
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.passkey.PasskeyRpIdNormalizer

object KeePassDxPasskeyCodec {
    const val FIELD_USERNAME = "KPEX_PASSKEY_USERNAME"
    const val FIELD_PRIVATE_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
    const val FIELD_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"
    const val FIELD_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"
    const val FIELD_RELYING_PARTY = "KPEX_PASSKEY_RELYING_PARTY"
    const val FIELD_FLAG_BE = "KPEX_PASSKEY_FLAG_BE"
    const val FIELD_FLAG_BS = "KPEX_PASSKEY_FLAG_BS"
    const val FIELD_PASSKEY = "Passkey"

    fun isPasskey(getField: (String) -> String): Boolean {
        return listOf(
            FIELD_USERNAME,
            FIELD_PRIVATE_KEY,
            FIELD_CREDENTIAL_ID,
            FIELD_USER_HANDLE,
            FIELD_RELYING_PARTY
        ).any { getField(it).isNotBlank() }
    }

    fun decode(
        getField: (String) -> String,
        title: String,
        notes: String,
        databaseId: Long,
        groupPath: String?,
        groupUuid: String?,
        createdAt: Long = System.currentTimeMillis(),
        lastUsedAt: Long = createdAt,
        useCount: Int = 0
    ): PasskeyEntry? {
        val username = getField(FIELD_USERNAME).trim()
        val privateKeyPem = getField(FIELD_PRIVATE_KEY)
        val rawCredentialId = getField(FIELD_CREDENTIAL_ID).trim()
        val userHandle = getField(FIELD_USER_HANDLE).trim()
        val relyingParty = getField(FIELD_RELYING_PARTY).trim()
        if (username.isBlank() || privateKeyPem.isBlank() || rawCredentialId.isBlank() || userHandle.isBlank() || relyingParty.isBlank()) {
            return null
        }

        val decodedPrivateKey = PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(privateKeyPem)
            ?: return null
        val normalizedCredentialId = PasskeyCredentialIdCodec.normalize(rawCredentialId) ?: rawCredentialId
        val normalizedRpId = PasskeyRpIdNormalizer.normalize(relyingParty) ?: relyingParty
        val rpName = title.removeSuffix(" [Passkey]").ifBlank { normalizedRpId }

        return PasskeyEntry(
            credentialId = normalizedCredentialId,
            rpId = normalizedRpId,
            rpName = rpName,
            userId = userHandle,
            userName = username,
            userDisplayName = username,
            publicKeyAlgorithm = decodedPrivateKey.publicKeyAlgorithm,
            publicKey = "",
            privateKeyAlias = Base64.encodeToString(decodedPrivateKey.pkcs8Bytes, Base64.NO_WRAP),
            createdAt = createdAt,
            lastUsedAt = lastUsedAt,
            useCount = useCount,
            iconUrl = null,
            isDiscoverable = true,
            isUserVerificationRequired = true,
            transports = PasskeyEntry.TRANSPORT_INTERNAL,
            aaguid = "",
            signCount = 0L,
            isBackedUp = parseBooleanCompat(getField(FIELD_FLAG_BS)) == true,
            backupEligible = parseBooleanCompat(getField(FIELD_FLAG_BE)),
            backupState = parseBooleanCompat(getField(FIELD_FLAG_BS)),
            notes = notes,
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            bitwardenVaultId = null,
            bitwardenFolderId = null,
            bitwardenCipherId = null,
            syncStatus = "NONE",
            passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT
        )
    }

    fun buildCustomFieldPairs(
        passkey: PasskeyEntry,
        existingFieldValue: (String) -> String = { "" },
        exportPrivateKeyPem: (String?) -> String? = PasskeyPrivateKeySupport::exportPem
    ): List<Pair<String, EntryValue>> {
        val normalizedCredentialId = PasskeyCredentialIdCodec.toWebAuthnId(passkey.credentialId)
            ?: passkey.credentialId
        val privateKeyPem = exportPrivateKeyPem(passkey.privateKeyAlias)
            ?: existingFieldValue(FIELD_PRIVATE_KEY)
        val flags = takagi.ru.monica.passkey.PasskeyBackupFlags.authenticatorFlags(passkey.backupEligible, passkey.backupState)
        val backupEligibility = ((flags and 0x08) != 0).toString()
        val backupState = ((flags and 0x10) != 0).toString()

        return listOf(
            FIELD_PASSKEY to EntryValue.Plain(""),
            FIELD_USERNAME to EntryValue.Plain(passkey.userName.ifBlank { passkey.userDisplayName }),
            FIELD_PRIVATE_KEY to EntryValue.Encrypted(EncryptedValue.fromString(privateKeyPem)),
            FIELD_CREDENTIAL_ID to EntryValue.Encrypted(EncryptedValue.fromString(normalizedCredentialId)),
            FIELD_USER_HANDLE to EntryValue.Encrypted(
                EncryptedValue.fromString(passkey.userId.ifBlank { existingFieldValue(FIELD_USER_HANDLE) })
            ),
            FIELD_RELYING_PARTY to EntryValue.Plain(
                (PasskeyRpIdNormalizer.normalize(passkey.rpId) ?: passkey.rpId)
                    .ifBlank { existingFieldValue(FIELD_RELYING_PARTY) }
            ),
            FIELD_FLAG_BE to EntryValue.Plain(backupEligibility),
            FIELD_FLAG_BS to EntryValue.Plain(backupState)
        )
    }

    private fun parseBooleanCompat(raw: String): Boolean? {
        return when (raw.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }
}
