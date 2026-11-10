package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.data.model.isSshKeyEntry
import takagi.ru.monica.util.TotpDataResolver
import java.util.Locale
import java.util.UUID
import takagi.ru.monica.utils.KeePassCustomFieldData
import takagi.ru.monica.keepass.KeePassEntryFieldSupport.parseUuid
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_LOCAL_ID
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_TOTP_DATA
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_ALGORITHM
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_KEY_SIZE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_PUBLIC_KEY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_PRIVATE_KEY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_FINGERPRINT
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_COMMENT
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSH_FORMAT
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_WIFI_SSID
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_WIFI_DATA
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_LOGIN_TYPE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_APP_PACKAGE_NAME
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_APP_NAME
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_EMAIL
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_PHONE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_ADDRESS_LINE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CITY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_STATE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_POSTAL_CODE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_COUNTRY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_NUMBER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_HOLDER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_EXPIRY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_CVV
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_SSO_PROVIDER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_SSO_REF_ENTRY_ID

/** Builds and patches password fields without owning a session or writing a file.
 * The decoder runs at the original OTP conversion point and never caches the result.
 */
internal class KeePassPasswordEntryWriter(
    private val nativeMutation: KeePassNativeMutation,
    private val decryptAuthenticatorKey: (String) -> String,
) {
    fun buildMonicaPasswordFields(
        original: KeePassNativeEntryRecord?,
        row: PasswordEntry,
        previousCustomFieldNames: Set<String>,
        customFields: List<KeePassCustomFieldData>,
        ssoRefUuid: String? = null,
        boundNoteUuid: String? = null,
    ): List<KeePassFieldChange> {
        val keptCustomNames = customFields.mapTo(hashSetOf()) { it.title }
        val base = original?.nativeEntry?.let { entry ->
            entry.copy(fields = EntryFields.of(*entry.fields.entries.filterNot {
                it.key in previousCustomFieldNames && it.key !in keptCustomNames
            }.map { it.key to it.value }.toTypedArray()))
        }
        val safeRow = row.copy(id = 0L)
        val updated = if (base == null) buildEntry(safeRow, row.password, customFields)
            else buildUpdatedEntry(base, safeRow, row.password, customFields)
        val values = updated.fields.toMutableMap()
        // A transient presentation ID must never replace the original file's compatibility ID.
        original?.nativeEntry?.fields?.get(FIELD_MONICA_LOCAL_ID)?.let { values[FIELD_MONICA_LOCAL_ID] = it }
        if (original == null) values.remove(FIELD_MONICA_LOCAL_ID)
        // These exact UI fields have defined missing-value defaults. Do not apply this
        // policy to user fields, standard KeePass fields, or content/stacking metadata.
        fun optional(name: String, value: String?, default: String = "") {
            val text = value.orEmpty()
            val absent = text.isEmpty() || text == default
            val previous = original?.field(name)
            val keepReference = absent && previous != null && previous.rawValue.contains("{REF:", ignoreCase = true) &&
                (previous.displayValue.equals(text, ignoreCase = true) || previous.displayValue.contains("{REF:", ignoreCase = true))
            if (keepReference) values[name] = requireNotNull(previous).nativeValue
            else if (absent) values.remove(name)
            else values[name] = EntryValue.Plain(text)
        }
        optional("MonicaIsFavorite", row.isFavorite.toString(), "false")
        optional("MonicaIsArchived", row.isArchived.toString(), "false")
        optional("MonicaSortOrder", row.sortOrder.toString(), "0")
        optional("MonicaIsGroupCover", row.isGroupCover.toString(), "false")
        optional("MonicaCategoryId", row.categoryId?.toString())
        optional("MonicaSsoRefEntryUuid", ssoRefUuid)
        optional("MonicaBoundNoteUuid", boundNoteUuid)
        listOf("MonicaBoundNoteId" to row.boundNoteId?.toString(), "MonicaPasskeyBindings" to row.passkeyBindings).forEach { (name, value) ->
            optional(name, value)
        }
        listOf("MonicaPasswordGroupId" to row.passwordGroupId, "MonicaReplicaGroupId" to row.replicaGroupId,
            "MonicaCustomIconType" to row.customIconType, "MonicaCustomIconValue" to row.customIconValue).forEach { (name, value) ->
            optional(name, value, if (name == "MonicaCustomIconType") "NONE" else "")
        }
        return values.map { (name, value) ->
            val previous = original?.field(name)
            // Preserve REF syntax and the native protection bit when the displayed value is unchanged.
            val retained = previous?.takeIf { it.displayValue == value.content }
            KeePassFieldChange(name, retained?.rawValue ?: value.content,
                retained?.isProtected ?: (value is EntryValue.Encrypted))
        }
    }

    fun buildEntry(
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Entry {
        val base = Entry(
            uuid = parseUuid(entry.keepassEntryUuid) ?: UUID.randomUUID(),
            fields = buildEntryFields(entry, plainPassword, customFields)
        )
        return nativeMutation.initializeEntry(applyPasswordEntryPresentation(base, entry))
    }

    fun buildUpdatedEntry(
        existingEntry: Entry,
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Entry {
        val base = buildPasswordEntryFieldPatch(entry, plainPassword, customFields, existingEntry).applyTo(existingEntry)
        return applyPasswordEntryPresentation(base, entry)
    }

    fun buildPasswordEntryFieldPatch(
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList(),
        existingEntry: Entry? = null
    ): KeePassEntryFieldPatch {
        val replacementFields = buildEntryFields(entry, plainPassword, customFields)
        val replacesOtp = entry.authenticatorKey.isNotBlank()
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = replacementFields,
            removeManagedField = { name ->
                KeePassFieldRegistry.isPasswordEntryOverlayField(name) ||
                    (replacesOtp && KeePassTotpCodec.isOtpField(name)) ||
                    (entry.isApiKeyEntry() && takagi.ru.monica.data.model.ApiKeyEntryFields.owns(name)) ||
                    (entry.loginType == takagi.ru.monica.data.model.GpgEntryFields.TYPE &&
                        takagi.ru.monica.data.model.GpgEntryFields.owns(name))
            },
            // Explicit removals survive conversion to the persisted change-set patch.
            // GPG custom fields are not part of the generic PASSWORD managed scope.
            removeFieldNames = replacementFields.keys + customFields.map { it.title.trim() } +
                (if (replacesOtp) existingEntry?.fields?.keys.orEmpty().filter {
                    KeePassTotpCodec.isOtpField(it)
                } else emptyList()) +
                (if (entry.isApiKeyEntry()) {
                    existingEntry?.fields?.keys.orEmpty().filter(takagi.ru.monica.data.model.ApiKeyEntryFields::owns)
                } else emptyList()) +
                if (entry.loginType == takagi.ru.monica.data.model.GpgEntryFields.TYPE) {
                    existingEntry?.fields?.keys.orEmpty().filter(takagi.ru.monica.data.model.GpgEntryFields::owns)
                } else emptyList()
        )
    }

    private fun applyPasswordEntryPresentation(
        base: Entry,
        entry: PasswordEntry
    ): Entry {
        // WIFI 条目使用 IRCommunication 图标，与 keepass2android WLan 模板一致。
        return if (entry.isWifiEntry()) {
            base.copy(icon = app.keemobile.kotpass.constants.PredefinedIcon.IRCommunication)
        } else {
            base
        }
    }

    private fun buildEntryFields(
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): EntryFields {
        val monicaId = if (entry.id > 0) entry.id.toString() else ""
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain(entry.title),
            "UserName" to EntryValue.Plain(entry.username),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(plainPassword)),
            "URL" to EntryValue.Plain(entry.website),
            "Notes" to EntryValue.Plain(entry.notes)
        )
        if (monicaId.isNotEmpty()) {
            pairs.add(FIELD_MONICA_LOCAL_ID to EntryValue.Plain(monicaId))
        }
        appendPasswordCompatibilityFields(pairs, entry)
        // WIFI 条目：写入与 keepass2android WLan 模板兼容的标准字段（SSID + Password），
        // 并把 Monica 专属扩展 (安全类型、代理、IP 设置等) 塞到 MonicaWifiData JSON。
        // 这样其他 KeePass 客户端能读到 SSID/Password 基本信息，Monica 自己读回时
        // 则能无损还原完整配置。
        if (entry.isWifiEntry()) {
            pairs += FIELD_MONICA_LOGIN_TYPE to EntryValue.Plain("WIFI")
            val wifi = takagi.ru.monica.data.model.WifiData.fromJsonOrEmpty(entry.wifiMetadata)
            val ssidForStandardField = wifi.ssid.ifBlank { entry.title }
            if (ssidForStandardField.isNotBlank()) {
                pairs += FIELD_WIFI_SSID to EntryValue.Plain(ssidForStandardField)
            }
            if (entry.wifiMetadata.isNotBlank()) {
                pairs += FIELD_MONICA_WIFI_DATA to EntryValue.Plain(entry.wifiMetadata)
            }
        }
        if (entry.isSshKeyEntry()) {
            pairs += FIELD_MONICA_LOGIN_TYPE to EntryValue.Plain("SSH_KEY")
        }
        SshKeyDataCodec.decode(entry.sshKeyData)?.let { ssh ->
            if (ssh.algorithm.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_ALGORITHM to EntryValue.Plain(ssh.algorithm)
            }
            if (ssh.keySize > 0) {
                pairs += FIELD_MONICA_SSH_KEY_SIZE to EntryValue.Plain(ssh.keySize.toString())
            }
            if (ssh.publicKeyOpenSsh.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_PUBLIC_KEY to EntryValue.Plain(ssh.publicKeyOpenSsh)
            }
            if (ssh.privateKeyOpenSsh.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_PRIVATE_KEY to EntryValue.Encrypted(
                    EncryptedValue.fromString(ssh.privateKeyOpenSsh)
                )
            }
            if (ssh.fingerprintSha256.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_FINGERPRINT to EntryValue.Plain(ssh.fingerprintSha256)
            }
            if (ssh.comment.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_COMMENT to EntryValue.Plain(ssh.comment)
            }
            if (ssh.format.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_FORMAT to EntryValue.Plain(ssh.format)
            }
        }
        appendKeePassCustomFields(pairs, customFields)
        if (entry.authenticatorKey.isNotBlank()) {
            val raw = decryptAuthenticatorKey(entry.authenticatorKey)
            val data = requireNotNull(TotpDataResolver.fromAuthenticatorKey(raw, entry.title, entry.username))
            val otpFields = KeePassTotpCodec.toKeePassFields(data, entry.title)
            require(otpFields.isNotEmpty()) { "Invalid or unsupported password OTP parameters" }
            pairs.removeAll { KeePassTotpCodec.isOtpField(it.first) || it.first == FIELD_MONICA_TOTP_DATA }
            otpFields.forEach { (name, value) ->
                pairs += name to if (KeePassTotpCodec.isSecretField(name))
                    EntryValue.Encrypted(EncryptedValue.fromString(value)) else EntryValue.Plain(value)
            }
        }
        return EntryFields.of(*pairs.toTypedArray())
    }

    private fun appendPasswordCompatibilityFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        entry: PasswordEntry
    ) {
        fun addPlain(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Plain(value)
            }
        }

        fun addProtected(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Encrypted(EncryptedValue.fromString(value))
            }
        }

        addPlain(FIELD_APP_PACKAGE_NAME, entry.appPackageName)
        addPlain(FIELD_APP_NAME, entry.appName)
        addPlain(FIELD_EMAIL, entry.email)
        addPlain(FIELD_PHONE, entry.phone)
        addPlain(FIELD_ADDRESS_LINE, entry.addressLine)
        addPlain(FIELD_CITY, entry.city)
        addPlain(FIELD_STATE, entry.state)
        addPlain(FIELD_POSTAL_CODE, entry.zipCode)
        addPlain(FIELD_COUNTRY, entry.country)
        addProtected(FIELD_CARD_NUMBER, entry.creditCardNumber)
        addPlain(FIELD_CARD_HOLDER, entry.creditCardHolder)
        addPlain(FIELD_CARD_EXPIRY, entry.creditCardExpiry)
        addProtected(FIELD_CARD_CVV, entry.creditCardCVV)

        if (entry.loginType.equals("SSO", ignoreCase = true)) {
            pairs += FIELD_MONICA_LOGIN_TYPE to EntryValue.Plain("SSO")
            addPlain(FIELD_SSO_PROVIDER, entry.ssoProvider)
            entry.ssoRefEntryId?.let { addPlain(FIELD_MONICA_SSO_REF_ENTRY_ID, it.toString()) }
        }
    }

    private fun appendKeePassCustomFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        customFields: List<KeePassCustomFieldData>
    ) {
        val usedKeys = pairs.mapTo(mutableSetOf()) { it.first.trim().lowercase(Locale.ROOT) }
        customFields
            .asSequence()
            .sortedWith(compareBy<KeePassCustomFieldData> { it.sortOrder }.thenBy { it.title })
            .forEach { field ->
                val key = field.title
                if (key.isBlank() || key.startsWith("_etm_")) return@forEach
                if (!usedKeys.add(key.lowercase(Locale.ROOT))) return@forEach
                val value = if (field.isProtected) {
                    EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                } else {
                    EntryValue.Plain(field.value)
                }
                pairs += key to value
            }
    }
}
