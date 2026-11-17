package takagi.ru.monica.repository

import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import java.util.Date

/** Only editor-owned, changed fields are patched. JsonElement retains unknown number text. */
internal object MdbxPasswordEditorPayload {
    private const val FIELD_ID_BASE = 4_000_000_000_000_000_000L
    fun parse(detail: MdbxNativeObjectDetail): JsonObject {
        require(detail.summary.type == "login" && detail.summary.version == 1u) { "Unsupported MDBX password schema" }
        val json = Json.parseToJsonElement(detail.payload).jsonObject
        require(json["kind"] == null || json.string("kind") == "password") { "Unsupported MDBX login payload" }
        require(!json.boolean("bitwarden_mode") && !json.boolean("keepass_mode")) { "MDBX source has another owner" }
        return json
    }

    private fun JsonObject.string(key: String, fallback: String = ""): String = when (val value = this[key]) {
        null -> fallback
        JsonNull -> ""
        is JsonPrimitive -> { require(value.isString) { "Unsupported MDBX field type" }; value.content }
        else -> error("Unsupported MDBX field type")
    }
    private fun JsonObject.boolean(key: String, fallback: Boolean = false): Boolean =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.booleanOrNull ?: fallback
    private fun JsonObject.long(key: String, fallback: Long = 0): Long =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.longOrNull ?: fallback
    private fun JsonObject.alias(key: String, old: String) = if (containsKey(key)) string(key) else string(old)

    fun decode(detail: MdbxNativeObjectDetail, base: PasswordEntry, root: String,
        encrypt: (String) -> String, decryptLegacy: (String) -> String): PasswordEntry {
        val j = parse(detail)
        val password = if (j.containsKey("password_plain")) j.string("password_plain") else decryptLegacy(j.string("password"))
        val wifi = j["wifi_metadata"] ?: j["wifiMetadata"]
        return base.copy(title = detail.summary.title, website = j.string("website"), username = j.string("username"),
            password = encrypt(password), notes = j.string("notes"),
            appPackageName = j.alias("app_package_name", "appPackageName"), appName = j.alias("app_name", "appName"),
            mdbxFolderId = detail.summary.collectionId.takeUnless { it == root },
            replicaGroupId = j.string("monica_entry_id").takeIf { it.startsWith("password:") } ?: detail.summary.id,
            passwordGroupId = j.string("password_group_id").takeIf(String::isNotBlank),
            authenticatorKey = encrypt(decryptLegacy(j.string("authenticator_key"))),
            email = j.string("email"), phone = j.string("phone"), addressLine = j.string("address_line"),
            city = j.string("city"), state = j.string("state"), zipCode = j.string("zip_code"), country = j.string("country"),
            creditCardNumber = j.string("credit_card_number_plain"), creditCardHolder = j.string("credit_card_holder"),
            creditCardExpiry = j.string("credit_card_expiry"), creditCardCVV = j.string("credit_card_cvv_plain"),
            loginType = j.string("login_type", "PASSWORD"), ssoProvider = j.string("sso_provider"),
            sshKeyData = j.string("ssh_key_data"), passkeyBindings = j.string("passkey_bindings"),
            wifiMetadata = when (wifi) { null, JsonNull -> ""; is JsonObject -> wifi.toString(); else -> j.string(if (j.containsKey("wifi_metadata")) "wifi_metadata" else "wifiMetadata") },
            createdAt = Date(j.long("created_at", base.createdAt.time)), isFavorite = j.boolean("is_favorite"),
            sortOrder = j.long("sort_order").toInt(), isDeleted = false, deletedAt = null,
            isArchived = j.boolean("is_archived"),
            boundNoteId = base.boundNoteId.takeIf { j.string("bound_note_entry_id").isNotBlank() },
        )
    }

    fun fields(detail: MdbxNativeObjectDetail, entryId: Long): List<CustomField> {
        val j = parse(detail)
        val array = j["custom_fields"] ?: j["customFields"] ?: return emptyList()
        require(array is JsonArray) { "Unsupported MDBX custom fields" }
        return array.mapIndexed { index, raw ->
            val field = raw.jsonObject
            CustomField(id = FIELD_ID_BASE + index, entryId = entryId,
                title = if (field.containsKey("title")) field.string("title") else field.string("label"),
                value = field.string("value"), isProtected = field.boolean("is_protected", field.boolean("isProtected")),
                sortOrder = index)
        }
    }

    fun encode(original: MdbxNativeObjectDetail?, before: PasswordEntry?, requested: PasswordEntry,
        fields: List<CustomFieldDraft>, decrypt: (String) -> String, boundNoteEntryId: String? = null): String {
        val source = original?.let(::parse) ?: JsonObject(emptyMap())
        val values = source.toMutableMap()
        fun set(key: String, value: String, previous: String?) {
            if (before == null || value != previous) values[key] = JsonPrimitive(value)
        }
        fun plain(value: String) = decrypt(value)
        set("username", requested.username, before?.username)
        set("website", requested.website, before?.website)
        set("password_plain", requested.password, before?.password?.let(::plain))
        if (before == null || requested.password != plain(before.password)) {
            values["monica_password_encoding"] = JsonPrimitive("plaintext-v1")
            if ("password" in values) values["password"] = JsonPrimitive(requested.password)
        }
        set("notes", requested.notes, before?.notes)
        set("app_package_name", requested.appPackageName, before?.appPackageName)
        set("app_name", requested.appName, before?.appName)
        set("password_group_id", requested.passwordGroupId.orEmpty(), before?.passwordGroupId.orEmpty())
        set("authenticator_key", plain(requested.authenticatorKey), before?.authenticatorKey?.let(::plain))
        set("login_type", requested.loginType, before?.loginType)
        set("sso_provider", requested.ssoProvider, before?.ssoProvider)
        set("ssh_key_data", requested.sshKeyData, before?.sshKeyData)
        set("passkey_bindings", requested.passkeyBindings, before?.passkeyBindings)
        set("email", requested.email, before?.email)
        set("phone", requested.phone, before?.phone)
        set("address_line", requested.addressLine, before?.addressLine)
        set("city", requested.city, before?.city)
        set("state", requested.state, before?.state)
        set("zip_code", requested.zipCode, before?.zipCode)
        set("country", requested.country, before?.country)
        set("credit_card_number_plain", plain(requested.creditCardNumber), before?.creditCardNumber?.let(::plain))
        set("credit_card_holder", requested.creditCardHolder, before?.creditCardHolder)
        set("credit_card_expiry", requested.creditCardExpiry, before?.creditCardExpiry)
        set("credit_card_cvv_plain", plain(requested.creditCardCVV), before?.creditCardCVV?.let(::plain))
        set("wifi_metadata", requested.wifiMetadata, before?.wifiMetadata)
        if (before == null || requested.boundNoteId != before.boundNoteId) {
            require(requested.boundNoteId == null || boundNoteEntryId != null) { "MDBX note binding is unavailable" }
            if (requested.boundNoteId != null || "bound_note_entry_id" in values)
                values["bound_note_entry_id"] = boundNoteEntryId?.let(::JsonPrimitive) ?: JsonNull
            if ("bound_note_room_id" in values) values["bound_note_room_id"] = JsonNull
        }
        if (before == null || requested.isFavorite != before.isFavorite) values["is_favorite"] = JsonPrimitive(requested.isFavorite)
        if (before == null) {
            values["kind"] = JsonPrimitive("password")
            values["created_at"] = JsonPrimitive(requested.createdAt.time)
            values["sort_order"] = JsonPrimitive(requested.sortOrder)
        }
        // Folder identity belongs to the native collection; retain the compatibility hint.
        if (before == null || requested.mdbxFolderId != before.mdbxFolderId)
            values["mdbx_folder_id"] = requested.mdbxFolderId?.let(::JsonPrimitive) ?: JsonNull
        val previous = original?.let { fields(it, requested.id) }.orEmpty()
        val sameFields = fields.map { Triple(it.title, it.value, it.isProtected) } ==
            previous.map { Triple(it.title, it.value, it.isProtected) }
        if (!sameFields || original == null) {
            val rawFields = (source["custom_fields"] ?: source["customFields"]) as? JsonArray ?: JsonArray(emptyList())
            val used = mutableSetOf<Int>()
            values["custom_fields"] = JsonArray(fields.mapIndexed { index, field ->
                val oldIndex = if (field.id >= FIELD_ID_BASE) (field.id - FIELD_ID_BASE).takeIf { it < rawFields.size }?.toInt()
                    else previous.indices.singleOrNull { previous[it].title == field.title && previous[it].value == field.value && previous[it].isProtected == field.isProtected }
                require(oldIndex == null || used.add(oldIndex)) { "Ambiguous MDBX field identity" }
                val raw = oldIndex?.let { rawFields[it].jsonObject }.orEmpty().toMutableMap()
                raw["title"] = JsonPrimitive(field.title); raw["value"] = JsonPrimitive(field.value)
                raw["is_protected"] = JsonPrimitive(field.isProtected); raw["sort_order"] = JsonPrimitive(index)
                JsonObject(raw)
            })
            if ("customFields" in values) values["customFields"] = values.getValue("custom_fields")
        }
        return JsonObject(values).toString()
    }
}
