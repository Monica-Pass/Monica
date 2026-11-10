package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.util.TotpDataResolver
import java.util.UUID
import takagi.ru.monica.utils.KeePassEntryResolutionContext
import takagi.ru.monica.utils.KeePassFieldReferenceResolver.getFieldValue
import takagi.ru.monica.keepass.KeePassEntryFieldSupport.parseUuid
import takagi.ru.monica.keepass.KeePassEntryFieldSupport.preserveExistingLoginFields
import takagi.ru.monica.keepass.KeePassBankCardFieldCodec.appendBankCardFields
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_ID
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_TOTP_DATA
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_TYPE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_DATA
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_NOTES
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_IMAGE_PATHS
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_IS_FAVORITE

/** Builds secure-item fields while preserving native login fields and reference syntax.
 * Portable data preparation stays with the caller and is invoked only at the existing conversion sites.
 */
internal class KeePassSecureItemFieldCodec(
    private val nativeMutation: KeePassNativeMutation,
    private val portableSecureItemDataForKeePass: (SecureItem) -> String,
) {
    fun buildSecureItemEntry(item: SecureItem): Entry {
        return nativeMutation.initializeEntry(
            Entry(
                uuid = parseUuid(item.keepassEntryUuid) ?: UUID.randomUUID(),
                fields = buildSecureItemFields(item)
            )
        )
    }

    fun buildUpdatedSecureItemEntry(
        existingEntry: Entry,
        item: SecureItem
    ): Entry {
        return buildSecureItemEntryFieldPatch(item, existingEntry).applyTo(existingEntry)
    }

    fun buildSecureItemEntryFieldPatch(
        item: SecureItem, existingEntry: Entry? = null, resolutionContext: KeePassEntryResolutionContext? = null
    ): KeePassEntryFieldPatch {
        if (item.itemType == ItemType.TOTP && existingEntry != null &&
            !getFieldValue(existingEntry, FIELD_MONICA_ITEM_TYPE).equals("TOTP", ignoreCase = true)) {
            // A native login can also carry OTP and passkey credentials. Editing its OTP is
            // a field patch, never a conversion of the entry into a Monica-only secure item.
            val portable = portableSecureItemDataForKeePass(item)
            val fields = mutableListOf<Pair<String, EntryValue>>()
            appendKeePassTotpFields(fields, item, portable)
            require(fields.isNotEmpty()) { "Authenticator data cannot be represented as KeePass OTP fields" }
            fields += FIELD_MONICA_TOTP_DATA to EntryValue.Encrypted(EncryptedValue.fromString(portable))
            return KeePassEntryFieldPatch.fromEntryFields(
                replacementFields = EntryFields.of(*fields.toTypedArray()),
                removeManagedField = { false },
                removeFieldNames = existingEntry.fields.keys.filter(KeePassTotpCodec::isOtpField) + fields.map { it.first }
            )
        }
        val builtFields = buildSecureItemFields(item).toMutableMap()
        // Keep unchanged/unresolved references, but allow an explicit clear of a
        // resolved true/nonempty value to remove the field.
        listOf(FIELD_MONICA_IMAGE_PATHS, FIELD_MONICA_IS_FAVORITE).forEach { name ->
            val requested = if (name == FIELD_MONICA_IS_FAVORITE) item.isFavorite.toString() else item.imagePaths
            val previous = existingEntry?.let { getFieldValue(it, name, resolutionContext) }
            existingEntry?.fields?.get(name)?.takeIf {
                name !in builtFields && it.content.contains("{REF:", ignoreCase = true) &&
                    (previous.equals(requested, ignoreCase = name == FIELD_MONICA_IS_FAVORITE) ||
                        previous?.contains("{REF:", ignoreCase = true) == true)
            }?.let { builtFields[name] = it }
        }
        val replacementFields = preserveExistingLoginFields(EntryFields.of(*builtFields.toList().toTypedArray()), existingEntry)
        val removeManagedField = if (item.itemType == ItemType.TOTP) {
            { name: String ->
                KeePassFieldRegistry.isSecureItemOverlayField(name) ||
                    KeePassFieldRegistry.isKeePassTotpField(name)
            }
        } else {
            KeePassFieldRegistry::isSecureItemOverlayField
        }
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = replacementFields,
            removeManagedField = removeManagedField,
            removeFieldNames = replacementFields.keys + if (item.itemType == ItemType.TOTP)
                existingEntry?.fields?.keys.orEmpty().filter(KeePassTotpCodec::isOtpField) else emptyList()
        )
    }

    fun portableSecureItemFields(item: SecureItem): Map<String, String> =
        buildSecureItemFields(item).map { (name, value) -> name to value.content }.toMap()

    private fun buildSecureItemFields(item: SecureItem): EntryFields {
        val monicaId = if (item.id > 0) item.id.toString() else ""
        val portableItemData = portableSecureItemDataForKeePass(item)
        val noteForExternal = if (item.itemType == ItemType.NOTE) {
            val decoded = NoteContentCodec.decode(portableItemData, item.notes)
            NoteContentCodec.toExternalReadableContent(decoded.content)
        } else {
            item.notes
        }
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain(item.title),
            "UserName" to EntryValue.Plain(""),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("")),
            "URL" to EntryValue.Plain(""),
            "Notes" to EntryValue.Plain(noteForExternal),
            FIELD_MONICA_ITEM_TYPE to EntryValue.Plain(item.itemType.name)
        )
        if (item.imagePaths.isNotEmpty()) pairs += FIELD_MONICA_IMAGE_PATHS to EntryValue.Plain(item.imagePaths)
        if (item.isFavorite) pairs += FIELD_MONICA_IS_FAVORITE to EntryValue.Plain("true")
        if (item.itemType == ItemType.NOTE) {
            // Standard Notes exposes the body to other clients; retain the separate Monica notes too.
            pairs += FIELD_MONICA_ITEM_NOTES to EntryValue.Encrypted(EncryptedValue.fromString(item.notes))
        }
        if (item.itemType == ItemType.BANK_CARD) {
            CardWalletDataCodec.parseBankCardData(portableItemData)?.let { cardData ->
                appendBankCardFields(pairs, cardData)
            }
        } else {
            pairs += FIELD_MONICA_ITEM_DATA to EntryValue.Encrypted(EncryptedValue.fromString(portableItemData))
            if (item.itemType == ItemType.TOTP) {
                appendKeePassTotpFields(pairs, item, portableItemData)
            }
        }
        if (monicaId.isNotEmpty()) {
            pairs.add(FIELD_MONICA_ITEM_ID to EntryValue.Plain(monicaId))
        }
        return EntryFields.of(*pairs.toTypedArray())
    }

    private fun appendKeePassTotpFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        item: SecureItem,
        portableItemData: String
    ) {
        val totpData = TotpDataResolver.parseStoredItemData(
            itemData = portableItemData,
            fallbackIssuer = item.title,
            fallbackAccountName = item.title
        ) ?: return
        val fields = KeePassTotpCodec.toKeePassFields(totpData, item.title)
        fields.forEach { (name, value) ->
            val entryValue = if (KeePassTotpCodec.isSecretField(name))
                EntryValue.Encrypted(EncryptedValue.fromString(value)) else EntryValue.Plain(value)
            pairs += name to entryValue
        }
    }
}
