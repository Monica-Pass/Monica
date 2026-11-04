package takagi.ru.monica.bitwarden.export

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.service.BitwardenLoginUriRules
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import java.security.interfaces.ECPrivateKey
import java.time.Instant
import java.util.Date

/** The preflight admits only semantics the ordinary local editors can retain. */
internal object BitwardenJsonLocalMapper {
    private val json = Json { encodeDefaults = true }
    data class LocalItem(val sourceFolder: String?, val password: PasswordEntry? = null,
        val secureItem: SecureItem? = null, val fields: List<CustomField> = emptyList(),
        val history: List<PasswordHistoryEntry> = emptyList(), val passkeys: List<PasskeyEntry> = emptyList())

    fun problems(document: BitwardenJsonDocument): List<BitwardenJsonIssue> = buildList {
        document.items.forEachIndexed { index, element ->
            val item = element.jsonObject
            fun issue(path: String, reason: BitwardenJsonIssue.Reason = BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE) {
                add(BitwardenJsonIssue(index, item.string("name")?.take(200), "items[$index].$path", reason))
            }
            val type = item.integer("type")!!
            if (item.integer("reprompt") == 1) issue("reprompt")
            if (item.string("name").isNullOrBlank()) issue("name")
            listOf("creationDate", "revisionDate", "deletedDate").forEach { name ->
                item.string(name)?.let { if (!millisecondDate(it)) issue(name) }
            }
            item.array("fields").forEachIndexed { n, field ->
                val data = field.jsonObject
                if (data.integer("type") == 3 || (type in setOf(1, 5) && data.integer("type") == 2)) issue("fields[$n].type")
                if (data.string("name").isNullOrBlank()) issue("fields[$n].name")
                if (data.string("name") == PasswordUrlRules.FIELD) issue("fields[$n].name")
            }
            val history = item.array("passwordHistory")
            if (type !in setOf(1, 5) && history.isNotEmpty()) issue("passwordHistory")
            var last = Long.MAX_VALUE
            history.forEachIndexed { n, entry ->
                val date = entry.jsonObject.string("lastUsedDate")!!
                if (!millisecondDate(date)) issue("passwordHistory[$n].lastUsedDate")
                val current = runCatching { Instant.parse(date).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
                if (current > last) issue("passwordHistory[$n]", BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE)
                last = current
            }
            if (type == 5) {
                val ssh = item.getValue("sshKey").jsonObject
                if (ssh.string("privateKey").isNullOrBlank() && ssh.string("publicKey").isNullOrBlank()) issue("sshKey")
            }
            if (type == 1) {
                val login = item.getValue("login").jsonObject
                login.array("uris").forEachIndexed { n, uri ->
                    if (uri.jsonObject.string("uri").isNullOrBlank()) issue("login.uris[$n].uri")
                }
                val ids = mutableSetOf<String>()
                login.array("fido2Credentials").forEachIndexed { n, key ->
                    val data = key.jsonObject
                    val path = "login.fido2Credentials[$n]"
                    if (data.string("keyType") != "public-key") issue("$path.keyType")
                    if (data.string("keyAlgorithm") != "ECDSA" || data.string("keyCurve") != "P-256") issue("$path.keyAlgorithm")
                    val decoded = PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(data.string("keyValue"))
                    try {
                        if (decoded?.publicKeyAlgorithm != PasskeyEntry.ALGORITHM_ES256 ||
                            (decoded.privateKey as? ECPrivateKey)?.params?.order?.toString(16) != "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551") issue("$path.keyValue")
                    } finally { decoded?.pkcs8Bytes?.fill(0) }
                    val id = data.string("credentialId").orEmpty()
                    if (!PasskeyCredentialIdCodec.isValid(id) || !ids.add(PasskeyCredentialIdCodec.normalize(id).orEmpty())) issue("$path.credentialId")
                    if (data.string("rpId").isNullOrBlank()) issue("$path.rpId")
                    if (!millisecondDate(data.string("creationDate")!!)) issue("$path.creationDate")
                    // Deleting a local login does not revoke independently stored Passkeys.
                    if (item["deletedDate"].present()) issue("$path", BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE)
                }
            }
        }
    }

    fun fromJson(item: JsonObject, order: Int, encrypt: (String) -> String): LocalItem {
        val type = item.integer("type")!!
        val now = System.currentTimeMillis()
        fun date(name: String) = Date(item.string(name)?.let { Instant.parse(it).toEpochMilli() } ?: now)
        val deleted = item.string("deletedDate")?.let { Date(Instant.parse(it).toEpochMilli()) }
        val fields = item.array("fields").map { it.jsonObject }
        val secureFields = fields.map { SecureCustomField(it.string("name")!!, it.string("value").orEmpty(),
            when (it.integer("type")) { 1 -> SecureCustomFieldType.HIDDEN; 2 -> SecureCustomFieldType.BOOLEAN; else -> SecureCustomFieldType.TEXT }) }
        val regular = fields.mapIndexed { index, value -> CustomField(entryId = 0, title = value.string("name")!!,
            value = value.string("value").orEmpty(), isProtected = value.integer("type") == 1, sortOrder = index) }
        val history = item.array("passwordHistory").map { value -> value.jsonObject.let {
            PasswordHistoryEntry(entryId = 0, password = encrypt(it.string("password")!!), lastUsedAt = Date(Instant.parse(it.string("lastUsedDate")!!).toEpochMilli()))
        } }
        val title = item.string("name")!!
        val notes = item.string("notes").orEmpty()
        val favorite = item["favorite"] == JsonPrimitive(true)
        val folder = item.string("folderId")
        if (type in setOf(1, 5)) {
            val login = item["login"] as? JsonObject ?: JsonObject(emptyMap())
            val uris = login.array("uris").map { it.jsonObject }.map {
                PasswordUrlRules.Rule(it.string("uri")!!, BitwardenLoginUriRules.mode(it.integer("match"))) }
            val ssh = (item["sshKey"] as? JsonObject)?.let { SshKeyDataCodec.encode(SshKeyData(
                publicKeyOpenSsh = it.string("publicKey").orEmpty(), privateKeyOpenSsh = it.string("privateKey").orEmpty(),
                fingerprintSha256 = it.string("keyFingerprint").orEmpty())) }.orEmpty()
            val password = PasswordEntry(title = title, website = uris.joinToString("\n") { it.url },
                username = login.string("username").orEmpty(), password = encrypt(login.string("password").orEmpty()), notes = notes,
                authenticatorKey = login.string("totp")?.let(encrypt).orEmpty(), sshKeyData = ssh,
                loginType = if (type == 5) LOGIN_TYPE_SSH_KEY else "PASSWORD", sortOrder = order, isFavorite = favorite,
                createdAt = date("creationDate"), updatedAt = date("revisionDate"), isDeleted = deleted != null, deletedAt = deleted)
            val ruleFields = if (type == 1) listOf(CustomField(entryId = 0, title = PasswordUrlRules.FIELD,
                value = PasswordUrlRules.encode(uris, native = true), sortOrder = regular.size)) else emptyList()
            val passkeys = login.array("fido2Credentials").map { element ->
                val key = element.jsonObject
                PasskeyEntry(credentialId = key.string("credentialId")!!, rpId = key.string("rpId")!!,
                    rpName = key.string("rpName").orEmpty(), userId = key.string("userHandle").orEmpty(),
                    userName = key.string("userName").orEmpty(), userDisplayName = key.string("userDisplayName").orEmpty(),
                    publicKey = "", privateKeyAlias = key.string("keyValue")!!, publicKeyAlgorithm = PasskeyEntry.ALGORITHM_ES256,
                    createdAt = Instant.parse(key.string("creationDate")!!).toEpochMilli(), lastUsedAt = 0,
                    signCount = key.string("counter")!!.toLong(), isDiscoverable = key.string("discoverable") == "true",
                    passkeyMode = PasskeyEntry.MODE_BW_COMPAT, backupEligible = true, backupState = true)
            }
            return LocalItem(folder, password = password, fields = regular + ruleFields, history = history, passkeys = passkeys)
        }
        val itemData = when (type) {
            2 -> json.encodeToString(NoteData(content = notes, customFields = secureFields))
            3 -> item.getValue("card").jsonObject.let { card -> json.encodeToString(BankCardData(
                cardNumber = card.string("number").orEmpty(), cardholderName = card.string("cardholderName").orEmpty(),
                expiryMonth = card.string("expMonth").orEmpty(), expiryYear = card.string("expYear").orEmpty(),
                cvv = card.string("code").orEmpty(), brand = card.string("brand").orEmpty(), customFields = secureFields)) }
            4 -> item.getValue("identity").jsonObject.let { data ->
                val converted = data.mapValues { (_, value) -> if (value == JsonNull) JsonPrimitive("") else value }.toMutableMap()
                converted["stateProvince"] = converted.remove("state") ?: JsonPrimitive("")
                converted["documentType"] = JsonPrimitive("OTHER"); converted["documentNumber"] = JsonPrimitive("")
                converted["fullName"] = JsonPrimitive(""); converted["customFields"] = json.encodeToJsonElement(secureFields)
                json.encodeToString(json.decodeFromJsonElement<DocumentData>(JsonObject(converted)))
            }
            else -> error("Unsupported import type")
        }
        return LocalItem(folder, secureItem = SecureItem(itemType = when (type) { 2 -> ItemType.NOTE; 3 -> ItemType.BANK_CARD; else -> ItemType.DOCUMENT },
            title = title, notes = notes, itemData = if (type == 2) itemData else encrypt(itemData), isFavorite = favorite, sortOrder = order,
            createdAt = date("creationDate"), updatedAt = date("revisionDate"), isDeleted = deleted != null, deletedAt = deleted))
    }

    private fun millisecondDate(value: String): Boolean = runCatching {
        val instant = Instant.parse(value)
        instant == Instant.ofEpochMilli(instant.toEpochMilli())
    }.getOrDefault(false)
}

internal fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
