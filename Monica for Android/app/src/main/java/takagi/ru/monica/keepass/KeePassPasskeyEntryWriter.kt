package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.TimeData
import takagi.ru.monica.data.PasskeyEntry
import java.time.Instant
import java.util.UUID
import takagi.ru.monica.utils.KeePassFieldReferenceResolver.getFieldValue
import takagi.ru.monica.keepass.KeePassEntryFieldSupport.preserveExistingLoginFields
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_PASSKEY_CREDENTIAL_ID
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_PASSKEY_DATA
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_PASSKEY_MODE

/** Converts passkey records to native fields; key access remains explicit caller-supplied operations.
 * Resolution and PEM export keep their original order and receive the original key reference.
 */
internal class KeePassPasskeyEntryWriter(
    private val resolvePrivateKey: (String?) -> String?,
    private val exportPrivateKeyPem: (String?) -> String?,
) {
    fun buildPasskeyEntry(passkey: PasskeyEntry): Entry {
        return Entry(
            uuid = UUID.randomUUID(),
            fields = buildPasskeyFields(passkey),
            times = buildPasskeyTimeData(passkey)
        )
    }

    private fun buildPasskeyTimeData(passkey: PasskeyEntry): TimeData {
        val createdAt = epochMillisToInstant(passkey.createdAt)
        val lastUsedAt = epochMillisToInstant(passkey.lastUsedAt.takeIf { it > 0L } ?: passkey.createdAt)
        val now = Instant.now()
        return TimeData(
            creationTime = createdAt,
            lastAccessTime = lastUsedAt,
            lastModificationTime = now,
            locationChanged = now,
            expiryTime = createdAt,
            expires = false,
            usageCount = passkey.useCount
        )
    }

    private fun epochMillisToInstant(value: Long): Instant {
        return runCatching { Instant.ofEpochMilli(value.takeIf { it > 0L } ?: System.currentTimeMillis()) }
            .getOrDefault(Instant.now())
    }

    private fun buildPasskeyFields(
        passkey: PasskeyEntry,
        existingEntry: Entry? = null
    ): EntryFields {
        val readableTitle = passkey.rpName.ifBlank { passkey.rpId }.ifBlank { "Passkey" }
        val portablePasskey = passkey.copy(
            privateKeyAlias = resolvePrivateKey(passkey.privateKeyAlias).orEmpty()
        )
        val payload = KeePassPasskeySyncCodec.encode(portablePasskey)
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain("$readableTitle [Passkey]"),
            "UserName" to EntryValue.Plain(passkey.userName.ifBlank { passkey.userDisplayName }),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("")),
            "URL" to EntryValue.Plain(
                when {
                    passkey.rpId.isBlank() -> ""
                    "://" in passkey.rpId -> passkey.rpId
                    else -> "https://${passkey.rpId}"
                }
            ),
            "Notes" to EntryValue.Plain(passkey.notes),
            FIELD_MONICA_PASSKEY_CREDENTIAL_ID to EntryValue.Plain(passkey.credentialId),
            FIELD_MONICA_PASSKEY_MODE to EntryValue.Plain(PasskeyEntry.MODE_KEEPASS_COMPAT),
            FIELD_MONICA_PASSKEY_DATA to EntryValue.Encrypted(EncryptedValue.fromString(payload))
        )
        pairs += KeePassDxPasskeyCodec.buildCustomFieldPairs(
            passkey = passkey,
            existingFieldValue = { fieldName ->
                existingEntry?.let { getFieldValue(it, fieldName) }.orEmpty()
            },
            exportPrivateKeyPem = { keyMaterial ->
                exportPrivateKeyPem(keyMaterial)
            }
        )
        return EntryFields.of(*pairs.toTypedArray())
    }

    fun buildUpdatedPasskeyEntry(
        existingEntry: Entry,
        passkey: PasskeyEntry
    ): Entry {
        return buildPasskeyEntryFieldPatch(passkey, existingEntry).applyTo(existingEntry)
    }

    fun buildPasskeyEntryFieldPatch(
        passkey: PasskeyEntry,
        existingEntry: Entry? = null
    ): KeePassEntryFieldPatch {
        val replacementFields = preserveExistingLoginFields(buildPasskeyFields(passkey, existingEntry), existingEntry)
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = replacementFields,
            removeManagedField = KeePassFieldRegistry::isPasskeyEntryOverlayField,
            removeFieldNames = replacementFields.keys
        )
    }
}
