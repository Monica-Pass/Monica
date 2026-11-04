package takagi.ru.monica.bitwarden.export

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.data.*
import takagi.ru.monica.transfer.DatabaseExportSnapshot
import takagi.ru.monica.utils.CustomFieldBackupEntry
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenJsonCodecTest {
    private val password = "synthetic-archive-password"
    private fun fixture(name: String) = javaClass.getResourceAsStream("/bitwarden-json/$name.json")!!.use { it.readBytes() }
    private fun plain() = BitwardenJsonCodec.decode(fixture("five-types"))
    private fun root(name: String) = Json.parseToJsonElement(fixture(name).decodeToString()).jsonObject
    private fun replaceItem(document: BitwardenJsonDocument, index: Int, block: (JsonObject) -> JsonObject) =
        document.copy(items = JsonArray(document.items.mapIndexed { n, item -> if (n == index) block(item.jsonObject) else item }))
    private fun rejected(block: () -> Unit): Throwable = try { block(); fail("Expected rejection"); error("unreachable") } catch (error: Exception) { error }

    @Test fun independentPythonPbkdf2AndArgon2VectorsMatchAllFiveTypes() {
        assertEquals(plain(), BitwardenJsonCodec.decode(fixture("pbkdf2"), password))
        assertEquals(plain(), BitwardenJsonCodec.decode(fixture("argon2"), password))
        assertEquals(5, plain().items.size)
        assertEquals(2, plain().folders.size)
    }

    @Test fun pinKdfUsesBase64TextAndHkdfLabels() {
        val key = BitwardenJsonCodec.derive(password, "AAECAwQFBgcICQoLDA0ODw==", BitwardenJsonCodec.Kdf(iterations = 1000))
        try {
            assertEquals("b4c544cc7c160e7344c5d91f64e6c8e7063518089265ef767e3de990418542f5", key.encKey.joinToString("") { "%02x".format(it) })
            assertEquals("1be99a548bcdb7e6f322715081b575cf41f7cd15500f33a25750d8239d9a9e03", key.macKey.joinToString("") { "%02x".format(it) })
        } finally { key.clear() }
    }

    @Test fun ourEncryptedExportCanBeOpenedByIndependentJceImplementation() {
        val document = plain()
        val encoded = BitwardenJsonCodec.encode(document, password, BitwardenJsonCodec.Kdf(iterations = 1000))
        val envelope = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
        val spec = PBEKeySpec(password.toCharArray(), envelope.string("salt")!!.toByteArray(), 1000, 256)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        fun expand(label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(master, "HmacSHA256")); doFinal(label.toByteArray() + byteArrayOf(1))
        }
        val enc = expand("enc"); val macKey = expand("mac")
        fun open(value: String): String {
            val parts = value.removePrefix("2.").split('|').map { Base64.getDecoder().decode(it) }
            val mac = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey, "HmacSHA256")); doFinal(parts[0] + parts[1]) }
            assertTrue(MessageDigest.isEqual(parts[2], mac))
            return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), IvParameterSpec(parts[0])); doFinal(parts[1]).decodeToString()
            }
        }
        try {
            java.util.UUID.fromString(open(envelope.string("encKeyValidation_DO_NOT_EDIT")!!))
            assertEquals(document.json(), Json.parseToJsonElement(open(envelope.string("data")!!)))
        } finally { master.fill(0); enc.fill(0); macKey.fill(0); encoded.fill(0) }
    }

    @Test fun wrongPasswordValidationTamperingAndDataTamperingAreRejected() {
        rejected { BitwardenJsonCodec.decode(fixture("pbkdf2"), "wrong") }
        val valid = root("pbkdf2")
        for (name in listOf("data", "encKeyValidation_DO_NOT_EDIT")) {
            val value = valid.string(name)!!
            val altered = value.dropLast(4) + if (value.takeLast(4) == "AAAA") "BBBB" else "AAAA"
            rejected { BitwardenJsonCodec.decode(JsonObject(valid + (name to JsonPrimitive(altered))).toString().toByteArray(), password) }
        }
        // A valid MAC over non-UUID validation plaintext is still the wrong portable-envelope contract.
        val key = BitwardenJsonCodec.derive(password, valid.string("salt")!!, BitwardenJsonCodec.Kdf(iterations = 1000))
        try {
            val bad = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.encryptString("not a validation UUID", key)
            rejected { BitwardenJsonCodec.decode(JsonObject(valid + ("encKeyValidation_DO_NOT_EDIT" to JsonPrimitive(bad))).toString().toByteArray(), password) }
        } finally { key.clear() }
    }

    @Test fun accountBoundUnauthenticatedAndExcessiveKdfEnvelopesAreRejected() {
        val root = root("pbkdf2")
        val variants = listOf(
            root + ("passwordProtected" to JsonPrimitive(false)),
            root + ("data" to JsonPrimitive("0.invalid")),
            root + ("kdfIterations" to JsonPrimitive(Int.MAX_VALUE)),
            root + ("kdfType" to JsonPrimitive(2)),
            root + ("kdfMemory" to JsonPrimitive(64)),
            root + ("salt" to JsonPrimitive("not-base64!")),
        )
        variants.forEach { rejected { BitwardenJsonCodec.decode(JsonObject(it).toString().toByteArray(), password) } }
    }

    @Test fun duplicateKeysInvalidUtf8UnknownTypesAndFieldsDoNotDisappear() {
        rejected { BitwardenJsonCodec.decode("{\"encrypted\":false,\"encrypted\":false,\"folders\":[],\"items\":[]}".toByteArray()) }
        rejected { BitwardenJsonCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        val modified = replaceItem(replaceItem(plain(), 0) { JsonObject(it + ("futureSecret" to JsonPrimitive("never print me"))) }, 1) {
            JsonObject(it + ("type" to JsonPrimitive(9)))
        }
        val failure = rejected { BitwardenJsonCodec.decode(modified.json().toString().toByteArray()) } as BitwardenJsonRejected
        assertTrue(failure.issues.any { it.itemIndex == 0 && it.path.endsWith("futureSecret") })
        assertTrue(failure.issues.any { it.itemIndex == 1 && it.reason == BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE })
        assertFalse(failure.issues.toString().contains("never print me"))
    }

    @Test fun localPreflightCollectsUnrepresentableFieldsWithoutDroppingThem() {
        val base = plain()
        val modified = replaceItem(base, 0) { item ->
            JsonObject(item + mapOf("reprompt" to JsonPrimitive(1), "fields" to buildJsonArray {
                add(buildJsonObject { put("name", "boolean"); put("value", "true"); put("type", 2) })
                add(buildJsonObject { put("name", "linked"); put("value", JsonNull); put("type", 3); put("linkedId", 100) })
            }))
        }
        assertTrue(BitwardenJsonCodec.validate(modified.json()).isEmpty())
        val issues = BitwardenJsonLocalMapper.problems(modified)
        assertEquals(setOf("items[0].reprompt", "items[0].fields[0].type", "items[0].fields[1].type"), issues.map { it.path }.toSet())
        assertEquals(2, modified.items[0].jsonObject.array("fields").size)
    }

    @Test fun localFiveTypesPreserveUrisHistoryIdentityFolderAndPasskeyMaterial() {
        val original = plain()
        assertEquals(emptyList<BitwardenJsonIssue>(), BitwardenJsonLocalMapper.problems(original))
        val converted = original.items.mapIndexed { i, item -> BitwardenJsonLocalMapper.fromJson(item.jsonObject, i) { "ENC:$it" } }
        val passwords = converted.mapIndexedNotNull { i, item -> item.password?.copy(id = i + 1L, categoryId = 1) }
        val secure = converted.mapIndexedNotNull { i, item -> item.secureItem?.copy(id = i + 1L, categoryId = 1) }
        val fields = converted.mapIndexedNotNull { i, item -> item.password?.let { (i + 1L) to item.fields.map { CustomFieldBackupEntry(it.title, it.value, it.isProtected) } } }.toMap()
        val history = converted.flatMapIndexed { i, item -> item.history.map { it.copy(entryId = i + 1L) } }
        val passkeys = converted.flatMapIndexed { i, item -> item.passkeys.mapIndexed { j, key -> key.copy(id = j + 1L, boundPasswordId = i + 1L, categoryId = 1) } }
        val snapshot = DatabaseExportSnapshot(ImportDestination.Local, passwords, secure, passkeys, fields, mapOf(1L to "Synthetic folder", 2L to "Empty folder"), emptyList(), emptyList())
        val output = BitwardenJsonLocalExporter.export(snapshot, history, { it.removePrefix("ENC:") }, { it }).document
        assertEquals(5, output.items.size); assertEquals(2, output.folders.size)
        val login = output.items.first { it.jsonObject.integer("type") == 1 }.jsonObject
        assertEquals(original.items[0].jsonObject.getValue("login").jsonObject.getValue("uris"), login.getValue("login").jsonObject.getValue("uris"))
        assertEquals(original.items[0].jsonObject["passwordHistory"], login["passwordHistory"])
        assertEquals("2.this-is-a-plain-password", login.getValue("login").jsonObject.string("password"))
        assertEquals(original.items[3].jsonObject["identity"], output.items.first { it.jsonObject.integer("type") == 4 }.jsonObject["identity"])
        assertEquals(original.items[0].jsonObject.getValue("login").jsonObject.array("fido2Credentials")[0].jsonObject.string("keyValue"), login.getValue("login").jsonObject.array("fido2Credentials")[0].jsonObject.string("keyValue"))
    }

    @Test fun historyOrderAndSubmillisecondDatesAreExplicitlyRejected() {
        val modified = replaceItem(plain(), 0) { item -> JsonObject(item + mapOf(
            "revisionDate" to JsonPrimitive("2026-11-04T01:02:03.456789Z"),
            "passwordHistory" to buildJsonArray {
                add(buildJsonObject { put("password", "first"); put("lastUsedDate", "2020-01-01T00:00:00Z") })
                add(buildJsonObject { put("password", "second"); put("lastUsedDate", "2021-01-01T00:00:00Z") })
            })) }
        val issues = BitwardenJsonLocalMapper.problems(modified)
        assertTrue(issues.any { it.path == "items[0].revisionDate" })
        assertTrue(issues.any { it.path == "items[0].passwordHistory[1]" })
    }

    @Test fun canonicalAccountExportUsesPerCipherKeysAndPreservesAllFiveTypes() {
        val vaultKey = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 11 }, ByteArray(32) { 12 })
        val itemKey = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 21 }, ByteArray(32) { 22 })
        try {
            val source = encryptedSource(plain(), vaultKey, itemKey)
            val exported = BitwardenJsonSourceExporter.fromSync(source, vaultKey) {}
            assertEquals(plain().folders, exported.document.folders)
            assertEquals(plain().items, exported.document.items)
            assertEquals(1, exported.attachmentCount)
            assertTrue(vaultKey.encKey.any { it != 0.toByte() })
        } finally { vaultKey.clear(); itemKey.clear() }
    }

    @Test fun canonicalAccountExportReportsUnknownAndArchivedContentBeforeWritingAnything() {
        val key = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 31 }, ByteArray(32) { 32 })
        try {
            val source = encryptedSource(plain(), key, key)
            val first = source.ciphers[0]
            val second = source.ciphers[1]
            val changed = source.copy(ciphers = listOf(
                first.copy(sourceDocument = JsonObject(first.sourceDocument!! + ("futureSecret" to JsonPrimitive("NEVER_INCLUDE_VALUE")))),
                second.copy(sourceDocument = JsonObject(second.sourceDocument!! + ("archivedDate" to JsonPrimitive("2026-11-04T00:00:00Z"))))
            ) + source.ciphers.drop(2))
            val failure = rejected { BitwardenJsonSourceExporter.fromSync(changed, key) {} } as BitwardenJsonRejected
            assertTrue(failure.issues.any { it.path.endsWith("futureSecret") })
            assertTrue(failure.issues.any { it.reason == BitwardenJsonIssue.Reason.ARCHIVED })
            assertFalse(failure.toString().contains("NEVER_INCLUDE_VALUE"))
        } finally { key.clear() }
    }

    private fun encryptedSource(document: BitwardenJsonDocument,
        vaultKey: takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey,
        itemKey: takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey): takagi.ru.monica.bitwarden.api.SyncResponse {
        fun encrypted(value: JsonElement?, key: takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey): JsonElement =
            if (value == null || value == JsonNull) JsonNull else JsonPrimitive(takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.encryptString(value.jsonPrimitive.content, key))
        fun section(value: JsonElement, names: Set<String>): JsonObject = JsonObject(value.jsonObject.mapValues { (name, value) -> if (name in names) encrypted(value, itemKey) else value })
        val ciphers = document.items.mapIndexed { index, element ->
            val item = element.jsonObject.toMutableMap()
            item["name"] = encrypted(item["name"], itemKey); item["notes"] = encrypted(item["notes"], itemKey)
            item["key"] = JsonPrimitive(takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.encrypt(itemKey.encKey + itemKey.macKey, vaultKey))
            item["bankAccount"] = JsonNull; item["driversLicense"] = JsonNull; item["passport"] = JsonNull
            item["fields"] = JsonArray(element.jsonObject.array("fields").map { section(it, setOf("name", "value")) })
            item["passwordHistory"] = JsonArray(element.jsonObject.array("passwordHistory").map { section(it, setOf("password")) })
            when (element.jsonObject.integer("type")) {
                1 -> {
                    val login = section(item.getValue("login"), setOf("username", "password", "totp")).toMutableMap()
                    login["uris"] = JsonArray(login.getValue("uris").jsonArray.map { section(it, setOf("uri")) })
                    login["fido2Credentials"] = JsonArray(login.getValue("fido2Credentials").jsonArray.map { section(it, BitwardenJsonCodec.fidoNames) })
                    item["login"] = JsonObject(login)
                }
                3 -> item["card"] = section(item.getValue("card"), BitwardenJsonCodec.cardNames)
                4 -> item["identity"] = section(item.getValue("identity"), BitwardenJsonCodec.identityNames)
                5 -> item["sshKey"] = section(item.getValue("sshKey"), BitwardenJsonCodec.sshNames)
            }
            if (index == 0) item["attachments"] = buildJsonArray { add(buildJsonObject {
                put("id", "synthetic-attachment"); put("fileName", encrypted(JsonPrimitive("synthetic.bin"), itemKey)); put("size", "4")
            }) }
            takagi.ru.monica.bitwarden.service.BitwardenCipherDocument.decode(JsonObject(item))
        }
        val folders = document.folders.map { takagi.ru.monica.bitwarden.api.FolderApiResponse(it.jsonObject.string("id")!!,
            takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.encryptString(it.jsonObject.string("name")!!, vaultKey)) }
        return takagi.ru.monica.bitwarden.api.SyncResponse(takagi.ru.monica.bitwarden.api.ProfileResponse(), folders, ciphers)
    }

    @Test fun encryptedArchivesLargerThanPerFieldLimitStillUseAuthenticatedCrypto() {
        val document = replaceItem(plain(), 0) { item -> JsonObject(item + ("notes" to JsonPrimitive("a".repeat(250_000)))) }
        val larger = document.copy(items = JsonArray((0..4).map { i -> JsonObject(document.items[0].jsonObject + ("id" to JsonPrimitive("40000000-0000-4000-8000-00000000000$i"))) }))
        val encrypted = BitwardenJsonCodec.encode(larger, password, BitwardenJsonCodec.Kdf(iterations = 1000))
        assertTrue(encrypted.size > 1024 * 1024)
        assertEquals(larger, BitwardenJsonCodec.decode(encrypted, password))
    }
}
