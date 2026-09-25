package takagi.ru.monica.ui.screens

import java.util.Locale
import java.net.URLDecoder
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassTotpCodec

/**
 * The five fields that make a native KDBX entry immediately useful in Monica.
 * All other fields stay first-class custom fields and are never discarded.
 */
internal enum class NativeEntryStandardSlot {
    TITLE,
    USERNAME,
    PASSWORD,
    URL,
    NOTES,
}

internal data class NativeEntryEditorField(
    val id: Long,
    val name: String,
    val value: String,
    val protected: Boolean,
    val order: Int,
    val slot: NativeEntryStandardSlot? = null,
)

internal fun NativeEntryEditorField.toCustomFieldDraft(): CustomFieldDraft = CustomFieldDraft(
    id = id,
    title = name,
    value = value,
    isProtected = protected,
)

internal data class NativeEntryEditorDraft(
    val fields: List<NativeEntryEditorField>,
) {
    val customFields: List<NativeEntryEditorField>
        get() = fields.filter { it.slot == null }

    fun standard(slot: NativeEntryStandardSlot): NativeEntryEditorField? =
        fields.firstOrNull { it.slot == slot }

    fun toFieldChanges(): List<KeePassFieldChange> = fields
        .sortedBy { it.order }
        .map { field ->
            KeePassFieldChange(
                name = field.name,
                value = field.value,
                protected = field.protected,
            )
        }
}

internal enum class NativeEntryDraftError {
    FIELD_NAME_REQUIRED,
    DUPLICATE_FIELD_NAME,
}

internal fun newNativeEntryEditorDraft(): NativeEntryEditorDraft = NativeEntryEditorDraft(
    fields = listOf(
        NativeEntryEditorField(1L, "Title", "", protected = false, order = 0, slot = NativeEntryStandardSlot.TITLE),
        NativeEntryEditorField(2L, "UserName", "", protected = false, order = 1, slot = NativeEntryStandardSlot.USERNAME),
        NativeEntryEditorField(3L, "Password", "", protected = true, order = 2, slot = NativeEntryStandardSlot.PASSWORD),
        NativeEntryEditorField(4L, "URL", "", protected = false, order = 3, slot = NativeEntryStandardSlot.URL),
        NativeEntryEditorField(5L, "Notes", "", protected = false, order = 4, slot = NativeEntryStandardSlot.NOTES),
    ),
)

internal fun buildNativeEntryEditorDraft(
    fields: List<KeePassFieldChange>,
): NativeEntryEditorDraft {
    val preferredNames = NativeEntryStandardSlot.entries.associateWith { slot ->
        val canonical = when (slot) {
            NativeEntryStandardSlot.TITLE -> "Title"
            NativeEntryStandardSlot.USERNAME -> "UserName"
            NativeEntryStandardSlot.PASSWORD -> "Password"
            NativeEntryStandardSlot.URL -> "URL"
            NativeEntryStandardSlot.NOTES -> "Notes"
        }
        fields.firstOrNull { it.name == canonical }?.name
            ?: fields.firstOrNull { nativeEntryStandardSlot(it.name) == slot }?.name
    }
    val usedSlots = mutableSetOf<NativeEntryStandardSlot>()
    val hasEditableTotp = editableNativeTotpData(fields) != null
    return NativeEntryEditorDraft(
        fields = fields.filterNot { field -> hasEditableTotp && isNativeTotpFieldName(field.name) }.mapIndexed { index, field ->
            val slot = nativeEntryStandardSlot(field.name)
                ?.takeIf { preferredNames[it] == field.name && usedSlots.add(it) }
            NativeEntryEditorField(
                id = index.toLong() + 1L,
                name = field.name,
                value = field.value,
                protected = field.protected,
                order = index,
                slot = slot,
            )
        },
    )
}

internal fun isNativeTotpFieldName(name: String): Boolean {
    return KeePassTotpCodec.isOtpField(name)
}

// Unsupported or malformed OTP formats remain editable as raw fields instead of disappearing.
internal fun editableNativeTotpData(fields: List<KeePassFieldChange>): TotpData? =
    parseNativeTotpFields(fields)?.takeIf { it.otpType == OtpType.TOTP || it.otpType == OtpType.HOTP }

internal fun mergeNativeTotpFields(
    fields: List<KeePassFieldChange>,
    data: TotpData?,
    title: String,
): List<KeePassFieldChange> {
    val retained = fields.filterNot { field -> isNativeTotpFieldName(field.name) }
    if (data == null) return retained
    val encoded = KeePassTotpCodec.toKeePassFields(data, title)
    require(encoded.isNotEmpty()) { "Invalid or unsupported OTP parameters" }
    return retained + encoded.map { (name, value) ->
        KeePassFieldChange(
            name = name,
            value = value,
            protected = KeePassTotpCodec.isSecretField(name),
        )
    }
}

/** Advancing HOTP never rewrites the secret, its encoding, or unrelated URI parameters. */
internal fun advanceNativeHotpFields(fields: List<KeePassFieldChange>, data: TotpData): List<KeePassFieldChange> {
    require(data.otpType == OtpType.HOTP && data.counter in 0 until Long.MAX_VALUE) { "HOTP counter exhausted" }
    val counter = (data.counter + 1).toString()
    fun withCounterQuery(raw: String): String {
        val fragment = raw.indexOf('#').takeIf { it >= 0 } ?: raw.length
        val body = raw.substring(0, fragment)
        val queryIndex = body.indexOf('?')
        val prefix = if (queryIndex >= 0) body.substring(0, queryIndex + 1) else ""
        val query = if (queryIndex >= 0) body.substring(queryIndex + 1) else body
        var found = false
        val changed = query.split('&').map { part ->
            val key = part.substringBefore('=')
            if (runCatching { URLDecoder.decode(key, "UTF-8") }.getOrNull().equals("counter", true)) {
                found = true
                "$key=$counter"
            } else part
        }.toMutableList()
        if (!found) changed += "counter=$counter"
        return prefix + changed.joinToString("&") + raw.substring(fragment)
    }
    val updated = fields.map { field ->
        when {
            field.name.equals("HmacOtp-Counter", true) || field.name.equals("HOTP Counter", true) ||
                field.name.equals("HOTPCounter", true) -> field.copy(value = counter)
            field.name.equals("otp", true) -> when {
                field.value.contains("{REF:", true) || field.value.contains("{S:", true) ->
                    throw IllegalArgumentException("Edit the HOTP counter in the referenced source entry")
                field.value.trim().startsWith("otpauth://hotp/", true) || field.value.contains("key=", true) ->
                    field.copy(value = withCounterQuery(field.value))
                else -> field
            }
            else -> field
        }
    }.toMutableList()
    if (fields.any { it.name.startsWith("HmacOtp-Secret", true) } &&
        updated.none { it.name.equals("HmacOtp-Counter", true) }) {
        updated += KeePassFieldChange("HmacOtp-Counter", counter)
    }
    if (updated.none { it.name.equals("HOTP Counter", true) || it.name.equals("HOTPCounter", true) }) {
        updated += KeePassFieldChange("HOTP Counter", counter)
    }
    return updated
}

internal fun parseNativeTotpFields(
    fields: List<KeePassFieldChange>,
): TotpData? {
    fun value(vararg names: String): String = fields.firstOrNull { field ->
        names.any { name -> field.name.equals(name, ignoreCase = true) }
    }?.value.orEmpty()

    return KeePassTotpCodec.parseFields(
        getField = { value(it) },
        issuer = value("Title", "Name"),
        accountName = value("UserName", "Login", "User"),
        link = value("URL", "URI", "Website"),
    )
}

internal fun newNativeCustomField(
    existing: List<NativeEntryEditorField>,
    name: String = "",
): NativeEntryEditorField {
    val nextId = (existing.minOfOrNull { it.id } ?: 0L) - 1L
    val nextOrder = (existing.maxOfOrNull { it.order } ?: -1) + 1
    return NativeEntryEditorField(
        id = nextId,
        name = name,
        value = "",
        protected = false,
        order = nextOrder,
        slot = null,
    )
}

internal fun ensureNativeEntryEditorStandardFields(
    draft: NativeEntryEditorDraft,
): NativeEntryEditorDraft {
    val result = draft.fields.toMutableList()
    var nextOrder = (result.maxOfOrNull { it.order } ?: -1) + 1
    var nextId = (result.maxOfOrNull { it.id } ?: 0L) + 1L
    NativeEntryStandardSlot.entries.forEach { slot ->
        if (result.none { it.slot == slot }) {
            val (name, protected) = when (slot) {
                NativeEntryStandardSlot.TITLE -> "Title" to false
                NativeEntryStandardSlot.USERNAME -> "UserName" to false
                NativeEntryStandardSlot.PASSWORD -> "Password" to true
                NativeEntryStandardSlot.URL -> "URL" to false
                NativeEntryStandardSlot.NOTES -> "Notes" to false
            }
            result += NativeEntryEditorField(
                id = nextId++,
                name = name,
                value = "",
                protected = protected,
                order = nextOrder++,
                slot = slot,
            )
        }
    }
    return NativeEntryEditorDraft(result)
}

internal fun validateNativeEntryEditorDraft(
    draft: NativeEntryEditorDraft,
): NativeEntryDraftError? {
    if (draft.fields.any { it.name.trim().isBlank() }) {
        return NativeEntryDraftError.FIELD_NAME_REQUIRED
    }
    val names = draft.fields.map { it.name }
    if (names.size != names.distinct().size) {
        return NativeEntryDraftError.DUPLICATE_FIELD_NAME
    }
    return null
}

internal fun nativeEntryStandardSlot(name: String): NativeEntryStandardSlot? {
    return when (name.trim().lowercase(Locale.ROOT)) {
        "title", "name" -> NativeEntryStandardSlot.TITLE
        "username", "user", "login" -> NativeEntryStandardSlot.USERNAME
        "password", "pass", "pwd", "密码", "口令" -> NativeEntryStandardSlot.PASSWORD
        "url", "website", "uri" -> NativeEntryStandardSlot.URL
        "notes", "note", "comment" -> NativeEntryStandardSlot.NOTES
        else -> null
    }
}

internal fun parseNativeEntryTags(value: String): List<String> = value
    .split('\n', '\r', ',', ';').map(String::trim).filter(String::isNotEmpty).distinct()
