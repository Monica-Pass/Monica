package takagi.ru.monica.bitwarden.export

import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.BitwardenWireJson
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Only indices, item names and field paths belong in a report; never include field values. */
data class BitwardenJsonIssue(val itemIndex: Int?, val itemName: String?, val path: String, val reason: Reason) {
    enum class Reason { INVALID, UNKNOWN_FIELD, UNSUPPORTED_TYPE, UNSUPPORTED_VALUE, RESOURCE_LIMIT,
        ORGANIZATION, ATTACHMENTS, ARCHIVED, DESTINATION, PENDING_SYNC, UNAVAILABLE }
}

class BitwardenJsonRejected(val issues: List<BitwardenJsonIssue>) :
    IllegalArgumentException("Bitwarden JSON cannot be transferred completely (${issues.size} issues)")

data class BitwardenJsonDocument(val folders: JsonArray, val items: JsonArray) {
    fun json(): JsonObject = buildJsonObject {
        put("encrypted", false); put("folders", folders); put("items", items)
    }
}

/** Portable export format, independently bounded from ordinary per-field cipher strings. */
object BitwardenJsonCodec {
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    const val MAX_FILE_BYTES = 12 * 1024 * 1024
    const val MAX_ITEMS = 10_000
    const val MAX_CHILDREN = 1_000
    private const val MAX_STRING_CHARS = 256 * 1024
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val sections = mapOf(1 to "login", 2 to "secureNote", 3 to "card", 4 to "identity", 5 to "sshKey")
    val identityNames = "title firstName middleName lastName address1 address2 address3 city state postalCode country company email phone ssn username passportNumber licenseNumber".split(' ').toSet()
    val cardNames = "cardholderName brand number expMonth expYear code".split(' ').toSet()
    val sshNames = setOf("privateKey", "publicKey", "keyFingerprint")
    val fidoNames = "credentialId keyType keyAlgorithm keyCurve keyValue rpId userHandle userName counter rpName userDisplayName discoverable creationDate".split(' ').toSet()

    data class Kdf(val type: Int = 0, val iterations: Int = 600_000, val memory: Int? = null, val parallelism: Int? = null) {
        fun validate() {
            require(when (type) {
                0 -> iterations in 1..2_000_000 && memory == null && parallelism == null
                1 -> iterations in 1..10 && memory in 8..128 && parallelism in 1..8 &&
                    memory!!.toLong() * iterations <= 512 && memory * 1024 >= parallelism!! * 8
                else -> false
            }) { "Unsupported or excessive Bitwarden JSON KDF parameters" }
        }
    }

    fun requiresPassword(bytes: ByteArray): Boolean {
        val root = readRoot(bytes, MAX_FILE_BYTES)
        return root["encrypted"] == JsonPrimitive(true) && root["passwordProtected"] == JsonPrimitive(true)
    }

    fun decode(bytes: ByteArray, password: String? = null): BitwardenJsonDocument {
        var root = readRoot(bytes, MAX_FILE_BYTES)
        if (root["encrypted"] == JsonPrimitive(true)) {
            require(root["passwordProtected"] == JsonPrimitive(true)) {
                "Account-bound encrypted JSON is not supported; export password-protected JSON in Bitwarden"
            }
            requireNotNull(password) { "Bitwarden JSON file password required" }
            require(root.keys.all { it in setOf("encrypted", "passwordProtected", "salt", "kdfType", "kdfIterations", "kdfMemory", "kdfParallelism", "encKeyValidation_DO_NOT_EDIT", "data") }) { "Unknown encrypted JSON envelope field" }
            val salt = requireNotNull(root.string("salt"))
            require(salt.length in 16..128 && Base64.getDecoder().decode(salt).size in 8..64) { "Invalid export salt" }
            val kdf = Kdf(requireNotNull(root.integer("kdfType")), requireNotNull(root.integer("kdfIterations")), root.optionalInteger("kdfMemory"), root.optionalInteger("kdfParallelism"))
            val key = derive(password, salt, kdf)
            try {
                val validation = decryptEnvelope(requireNotNull(root.string("encKeyValidation_DO_NOT_EDIT")), key, 128)
                try { require(uuid.matches(utf8(validation))) { "Invalid export key validation" } } finally { validation.fill(0) }
                val plain = decryptEnvelope(requireNotNull(root.string("data")), key, MAX_PLAINTEXT_BYTES)
                try { root = readRoot(plain, MAX_PLAINTEXT_BYTES) } finally { plain.fill(0) }
            } finally { key.clear() }
        } else require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Bitwarden JSON plaintext exceeds 8 MiB" }
        val issues = validate(root)
        if (issues.isNotEmpty()) throw BitwardenJsonRejected(issues)
        return BitwardenJsonDocument(root.getValue("folders").jsonArray, root.getValue("items").jsonArray)
    }

    fun encode(document: BitwardenJsonDocument, password: String? = null, kdf: Kdf = Kdf()): ByteArray {
        val issues = validate(document.json())
        if (issues.isNotEmpty()) throw BitwardenJsonRejected(issues)
        val plain = document.json().toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Bitwarden JSON plaintext exceeds 8 MiB" }
        if (password == null) return plain
        val saltBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val salt = Base64.getEncoder().encodeToString(saltBytes)
        saltBytes.fill(0)
        val key = derive(password, salt, kdf)
        return try {
            buildJsonObject {
                put("encrypted", true); put("passwordProtected", true); put("salt", salt)
                put("kdfType", kdf.type); put("kdfIterations", kdf.iterations)
                put("kdfMemory", kdf.memory?.let(::JsonPrimitive) ?: JsonNull)
                put("kdfParallelism", kdf.parallelism?.let(::JsonPrimitive) ?: JsonNull)
                put("encKeyValidation_DO_NOT_EDIT", BitwardenCrypto.encryptString(UUID.randomUUID().toString(), key))
                put("data", BitwardenCrypto.encrypt(plain, key))
            }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FILE_BYTES) }
        } finally { plain.fill(0); key.clear() }
    }

    internal fun derive(password: String, saltText: String, kdf: Kdf): BitwardenCrypto.SymmetricCryptoKey {
        kdf.validate()
        require(password.isNotEmpty() && password.length <= 4096) { "Invalid export password length" }
        // Upstream PinKey uses the BASE64 TEXT, never the decoded random salt.
        val master = if (kdf.type == 0) BitwardenCrypto.deriveMasterKeyPbkdf2(password, saltText, kdf.iterations)
            else BitwardenCrypto.deriveMasterKeyArgon2(password, saltText, kdf.iterations, kdf.memory!!, kdf.parallelism!!)
        return try { BitwardenCrypto.stretchMasterKey(master) } finally { master.fill(0) }
    }

    private fun decryptEnvelope(value: String, key: BitwardenCrypto.SymmetricCryptoKey, maxBytes: Int): ByteArray {
        require(value.length <= ((maxBytes.toLong() + 32) * 4 / 3 + 128)) { "Encrypted export exceeds limit" }
        require(value.startsWith("2.")) { "Export encryption must be authenticated" }
        val parts = value.substring(2).split('|')
        require(parts.size == 3) { "Invalid encrypted export" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0]); val data = decoder.decode(parts[1]); val mac = decoder.decode(parts[2])
        return try {
            require(iv.size == 16 && mac.size == 32 && data.isNotEmpty() && data.size % 16 == 0 && data.size <= maxBytes + 16) { "Invalid encrypted export sizes" }
            BitwardenCrypto.decrypt(BitwardenCrypto.ParsedCipherString(2, iv, data, mac), key).also { require(it.size <= maxBytes) }
        } finally { iv.fill(0); data.fill(0); mac.fill(0) }
    }

    private fun readRoot(bytes: ByteArray, limit: Int): JsonObject {
        require(bytes.size <= limit) { "Bitwarden JSON file exceeds size limit" }
        return BitwardenWireJson.parseObject(utf8(bytes).removePrefix("\uFEFF"), limit)
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun validate(root: JsonObject): List<BitwardenJsonIssue> {
        val check = Validator()
        check.obj(root, setOf("encrypted", "folders", "items"), "")
        if (root["encrypted"] != JsonPrimitive(false)) check.issue("encrypted")
        val folders = check.array(root["folders"], "folders", MAX_ITEMS, required = true)
        val folderIds = mutableSetOf<String>()
        folders?.forEachIndexed { index, element ->
            val path = "folders[$index]"
            val folder = check.obj(element, setOf("id", "name"), path) ?: return@forEachIndexed
            check.string(folder, "id", path, required = true)
            check.string(folder, "name", path, required = true)
            folder.string("id")?.let { if (!uuid.matches(it) || !folderIds.add(it)) check.issue("$path.id") }
        }
        val items = check.array(root["items"], "items", MAX_ITEMS, required = true)
        val itemIds = mutableSetOf<String>()
        items?.forEachIndexed { index, element ->
            check.index = index; check.name = (element as? JsonObject)?.string("name")?.take(200)
            val path = "items[$index]"
            val item = check.obj(element, setOf("id", "folderId", "organizationId", "collectionIds", "name", "notes", "type", "favorite", "reprompt", "fields", "passwordHistory", "revisionDate", "creationDate", "deletedDate") + sections.values, path) ?: return@forEachIndexed
            check.string(item, "name", path, required = true)
            listOf("id", "folderId", "organizationId", "notes").forEach { check.string(item, it, path) }
            item.string("id")?.let { if (!uuid.matches(it) || !itemIds.add(it)) check.issue("$path.id") }
            item.string("folderId")?.let { if (it !in folderIds) check.issue("$path.folderId") }
            if (item["organizationId"].present()) check.issue("$path.organizationId", BitwardenJsonIssue.Reason.ORGANIZATION)
            val collections = check.array(item["collectionIds"], "$path.collectionIds", MAX_CHILDREN)
            if (!collections.isNullOrEmpty()) check.issue("$path.collectionIds", BitwardenJsonIssue.Reason.ORGANIZATION)
            if (item["favorite"] != null && item["favorite"]?.let { it is JsonPrimitive && !it.isString && it.booleanOrNull != null } != true) check.issue("$path.favorite")
            if (item["reprompt"] != null && item.integer("reprompt") !in 0..1) check.issue("$path.reprompt")
            listOf("revisionDate", "creationDate", "deletedDate").forEach { check.date(item, it, path) }
            val type = item.integer("type")
            if (type !in sections) check.issue("$path.type", BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE)
            sections.forEach { (number, section) ->
                if (number != type && item[section].present()) check.issue("$path.$section")
            }
            when (type) {
                1 -> check.login(item["login"], "$path.login")
                2 -> check.obj(item["secureNote"], setOf("type"), "$path.secureNote")?.let {
                    if (it.integer("type") != 0) check.issue("$path.secureNote.type", BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE)
                }
                3, 4, 5 -> {
                    val section = sections.getValue(type)
                    val names = when (type) { 3 -> cardNames; 4 -> identityNames; else -> sshNames }
                    check.obj(item[section], names, "$path.$section")?.let { data -> names.forEach { check.string(data, it, "$path.$section") } }
                }
            }
            check.array(item["fields"], "$path.fields", MAX_CHILDREN)?.forEachIndexed { n, field ->
                val fieldPath = "$path.fields[$n]"
                check.obj(field, setOf("name", "value", "type", "linkedId"), fieldPath)?.let { data ->
                    check.string(data, "name", fieldPath); check.string(data, "value", fieldPath)
                    if (data.integer("type") !in 0..3) check.issue("$fieldPath.type", BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE)
                    if (data["linkedId"].present() && data.integer("linkedId") == null) check.issue("$fieldPath.linkedId")
                    if (data.integer("type") != 3 && data["linkedId"].present()) check.issue("$fieldPath.linkedId")
                    if (data.integer("type") == 2 && data.string("value") !in listOf("true", "false")) check.issue("$fieldPath.value")
                }
            }
            check.array(item["passwordHistory"], "$path.passwordHistory", MAX_CHILDREN)?.forEachIndexed { n, history ->
                val historyPath = "$path.passwordHistory[$n]"
                check.obj(history, setOf("password", "lastUsedDate"), historyPath)?.let { data ->
                    check.string(data, "password", historyPath, required = true); check.date(data, "lastUsedDate", historyPath, required = true)
                }
            }
        }
        return check.issues
    }

    private class Validator {
        val issues = mutableListOf<BitwardenJsonIssue>()
        var index: Int? = null
        var name: String? = null
        fun issue(path: String, reason: BitwardenJsonIssue.Reason = BitwardenJsonIssue.Reason.INVALID) {
            issues += BitwardenJsonIssue(index, name, path, reason)
        }
        fun obj(value: JsonElement?, allowed: Set<String>, path: String): JsonObject? {
            if (value !is JsonObject) { issue(path); return null }
            value.keys.filter { it !in allowed }.forEach { issue("$path.${it.take(100)}", BitwardenJsonIssue.Reason.UNKNOWN_FIELD) }
            return value
        }
        fun array(value: JsonElement?, path: String, limit: Int, required: Boolean = false): JsonArray? {
            if (!value.present() && !required) return null
            if (value !is JsonArray) { issue(path); return null }
            if (value.size > limit) { issue(path, BitwardenJsonIssue.Reason.RESOURCE_LIMIT); return null }
            return value
        }
        fun string(data: JsonObject, key: String, path: String, required: Boolean = false) {
            val value = data[key]
            if (!value.present() && !required) return
            if (value !is JsonPrimitive || !value.isString) issue("$path.$key")
            else if (value.content.length > MAX_STRING_CHARS) issue("$path.$key", BitwardenJsonIssue.Reason.RESOURCE_LIMIT)
            else {
                var index = 0
                while (index < value.content.length) {
                    val char = value.content[index++]
                    if (Character.isHighSurrogate(char)) {
                        if (index >= value.content.length || !Character.isLowSurrogate(value.content[index++])) { issue("$path.$key"); break }
                    } else if (Character.isLowSurrogate(char)) { issue("$path.$key"); break }
                }
            }
        }
        fun date(data: JsonObject, key: String, path: String, required: Boolean = false) {
            string(data, key, path, required)
            data.string(key)?.let { if (runCatching { Instant.parse(it) }.isFailure) issue("$path.$key") }
        }
        fun login(value: JsonElement?, path: String) {
            val data = obj(value, setOf("username", "password", "uris", "totp", "fido2Credentials"), path) ?: return
            listOf("username", "password", "totp").forEach { string(data, it, path) }
            array(data["uris"], "$path.uris", MAX_CHILDREN)?.forEachIndexed { n, uri ->
                val uriPath = "$path.uris[$n]"
                obj(uri, setOf("uri", "match"), uriPath)?.let {
                    string(it, "uri", uriPath)
                    if (it["match"].present() && it.integer("match") !in 0..5) issue("$uriPath.match")
                }
            }
            array(data["fido2Credentials"], "$path.fido2Credentials", MAX_CHILDREN)?.forEachIndexed { n, fido ->
                val fidoPath = "$path.fido2Credentials[$n]"
                obj(fido, fidoNames, fidoPath)?.let {
                    fidoNames.forEach { field -> string(it, field, fidoPath, required = field in setOf("credentialId", "keyType", "keyAlgorithm", "keyCurve", "keyValue", "rpId", "counter", "discoverable", "creationDate")) }
                    date(it, "creationDate", fidoPath, required = true)
                    if (it.string("counter")?.toLongOrNull() !in 0L..0xffffffffL) issue("$fidoPath.counter")
                    if (it.string("discoverable") !in listOf("true", "false")) issue("$fidoPath.discoverable")
                }
            }
        }
    }
}

internal fun JsonElement?.present() = this != null && this != JsonNull
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
internal fun JsonObject.optionalInteger(name: String): Int? {
    if (!this[name].present()) return null
    return requireNotNull(integer(name)) { "Invalid KDF integer" }
}
