package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenCipherSourceStore
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.SyncResult
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Synthetic Vaultwarden wire objects enter actual HTTP decode, crypto and transactional sync. */
@RunWith(AndroidJUnit4::class)
class BitwardenLegacyVaultwardenSyncTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database get() = PasswordDatabase.getDatabase(context)

    @Test fun camelCaseObjectDataDownloadsAllFiveCipherTypes() = runBlocking {
        scenario { checkFiveTypes(pascal = false) }
    }

    @Test fun pascalCaseObjectDataDownloadsAllFiveCipherTypes() = runBlocking {
        scenario { checkFiveTypes(pascal = true) }
    }

    @Test fun camelObjectDataUploadAcceptsServerRegeneratedMirrorAndColdPull() = runBlocking {
        scenario { checkUploadRoundTrip(WireShape.CAMEL_OBJECT) }
    }

    @Test fun pascalObjectDataUploadAcceptsCamelServerMirrorAndColdPull() = runBlocking {
        scenario { checkUploadRoundTrip(WireShape.PASCAL_OBJECT) }
    }

    @Test fun serializedLegacyDataUploadAcceptsObjectServerMirrorAndColdPull() = runBlocking {
        scenario { checkUploadRoundTrip(WireShape.SERIALIZED_PASCAL) }
    }

    @Test fun typedCipherWithoutDataUploadsAndColdPullsWithoutAddingLegacyData() = runBlocking {
        scenario { checkUploadRoundTrip(WireShape.TYPED_ONLY) }
    }

    @Test fun batchOf262ObjectDataLoginsDownloadsEveryOriginalValueAndRetainsWireDocuments() = runBlocking {
        scenario {
            repeat(262) { index ->
                val id = "legacy-login-$index"
                remote[id] = cipher(id, 1, pascal = index % 2 == 0)
            }
            val result = sync()
            assertTrue("Object data must not reject the complete 262-item response: $result", result is SyncResult.Success)
            assertEquals(262, (result as SyncResult.Success).ciphersAdded)
            assertEquals(262, database.passwordEntryDao().getByBitwardenVaultId(vault.id).size)
            assertTrue(database.secureItemDao().getByBitwardenVaultId(vault.id).isEmpty())
            remote.keys.sorted().forEach { id -> assertLogin(id); assertSource(id) }
            val rowIds = database.passwordEntryDao().getByBitwardenVaultId(vault.id)
                .associate { it.bitwardenCipherId to it.id }
            val repeatResult = sync()
            assertTrue(repeatResult is SyncResult.Success)
            assertEquals(0, (repeatResult as SyncResult.Success).ciphersAdded)
            assertEquals(rowIds, database.passwordEntryDao().getByBitwardenVaultId(vault.id)
                .associate { it.bitwardenCipherId to it.id })
            assertEquals(2, syncRequests.get())
        }
    }

    @Test fun conflictingMirrorsUnknownBlobsAndPartialObjectsCannotReplacePreviouslySyncedRows() = runBlocking {
        scenario {
            val id = "retained-login"
            // Establish the original through the HTTP sync seam, not a fabricated Room row.
            remote[id] = JsonObject(cipher(id, 1) - "data")
            assertTrue(sync() is SyncResult.Success)
            val original = requireNotNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id))
            val originalSource = remote.getValue(id)
            val sourceStore = BitwardenCipherSourceStore(context)
            val valid = cipher(id, 1, revision = REVISION_2)
            val data = valid.getValue("data").jsonObject
            val mutations = listOf(
                "different-name-mirror" to JsonObject(valid + ("data" to JsonObject(data + ("name" to enc("different-name"))))),
                "different-password-mirror" to JsonObject(valid + ("data" to JsonObject(data + ("password" to enc("different-password"))))),
                "unknown-encrypted-blob" to JsonObject(valid + ("data" to enc("opaque-future-document"))),
                "unknown-object" to JsonObject(valid + ("data" to JsonObject(mapOf("futureCiphertext" to enc("unknown"))))),
                "partial-object" to JsonObject(valid + ("partialData" to JsonObject(mapOf("future" to JsonPrimitive(true))))),
            )
            mutations.forEachIndexed { index, (reason, rejected) ->
                remote.clear()
                remote[id] = rejected
                val newId = "rejected-new-$index"
                remote[newId] = JsonObject(rejected + ("id" to JsonPrimitive(newId)))
                assertTrue(reason, sync() is SyncResult.Error)
                assertEquals(reason, original, database.passwordEntryDao().getPasswordEntryById(original.id))
                assertNull(reason, database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, newId))
                assertEquals(reason, 1, database.passwordEntryDao().getByBitwardenVaultId(vault.id).size)
                assertEquals(reason, originalSource,
                    sourceStore.read(vault, id, REVISION_1) { active }?.sourceDocument)
                assertEquals(reason, secret(id), security.decryptData(original.password))
            }
        }
    }

    @Test fun lockDuringObjectDataResponseRetainsOldRowsAndRejectsLatePublication() = runBlocking {
        scenario {
            val id = "locked-login"
            remote[id] = JsonObject(cipher(id, 1) - "data")
            assertTrue(sync() is SyncResult.Success)
            val original = requireNotNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id))
            val originalVault = database.bitwardenVaultDao().getVaultById(vault.id)
            remote[id] = cipher(id, 1, revision = REVISION_2)
            remote["late-new-login"] = cipher("late-new-login", 1)
            val generation = SessionManager.lockGeneration.value
            beforeSyncResponse = {
                active = false
                instrumentation.runOnMainSync { SessionManager.markLocked() }
            }
            assertTrue(sync() is SyncResult.Error)
            assertTrue(SessionManager.lockGeneration.value > generation)
            assertFalse(SessionManager.isUnlocked.value)
            assertEquals(original, database.passwordEntryDao().getPasswordEntryById(original.id))
            assertNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "late-new-login"))
            assertNull(database.bitwardenCipherSourceDao().get(vault.id, id, REVISION_2))
            assertEquals(originalVault, database.bitwardenVaultDao().getVaultById(vault.id))
            assertEquals(2, syncRequests.get())
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("legacyVaultwardenUser") == user.toString()) {
            "Requires an explicitly owned synthetic secondary user via legacyVaultwardenUser"
        }
        val wasUnlocked = SessionManager.isUnlocked.value
        val cache = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val originalMdk = cache.get(null)
        val syntheticMdk = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        cache.set(null, syntheticMdk)
        instrumentation.runOnMainSync { SessionManager.markUnlocked() }
        try {
            MockWebServer().use { server ->
                val fixture = Fixture(server)
                try { fixture.initialize(); fixture.block() } finally { fixture.close() }
            }
        } finally {
            cache.set(null, originalMdk)
            syntheticMdk.fill(0)
            instrumentation.runOnMainSync {
                if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
            }
        }
    }

    private inner class Fixture(private val server: MockWebServer) {
        val account = UUID.randomUUID().toString()
        val token = "synthetic-legacy-vaultwarden-token"
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        val security = SecurityManager(context)
        val remote = ConcurrentHashMap<String, JsonObject>()
        val syncRequests = AtomicInteger()
        val cipherRequests = CopyOnWriteArrayList<String>()
        val updates = CopyOnWriteArrayList<JsonObject>()
        var regenerateDataOnUpdate = true
        @Volatile var active = true
        @Volatile var beforeSyncResponse: (() -> Unit)? = null
        lateinit var vault: BitwardenVault
        private val repository by lazy { BitwardenRepository.getInstance(context) }
        private val service = BitwardenSyncService(context, sessionIsActive = { id, captured ->
            active && id == vault.id && captured === key
        })

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>

        suspend fun initialize() {
            val endpoint = server.url("/").toString()
            val id = database.bitwardenVaultDao().insert(BitwardenVault(
                email = "$account@example.invalid", canonicalEmail = "$account@example.invalid", userId = account,
                accountKey = takagi.ru.monica.bitwarden.BitwardenVaultIdentity.buildAccountKey(endpoint, account, "$account@example.invalid"),
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint, syncEnabled = false, isLocked = false,
            ))
            vault = requireNotNull(database.bitwardenVaultDao().getVaultById(id))
            cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
            cache<String>("accessTokenCache")[id] = token
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    val profile = JsonObject(mapOf("id" to JsonPrimitive(account), "email" to JsonPrimitive(vault.email),
                        "securityStamp" to JsonPrimitive("synthetic-legacy-stamp")))
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path.startsWith("/ciphers/")) {
                        val id = path.removePrefix("/ciphers/")
                        val original = remote[id] ?: return MockResponse().setResponseCode(404)
                        cipherRequests += "${request.method} $id"
                        if (request.method == "GET") return ok(original)
                        if (request.method != "PUT") return MockResponse().setResponseCode(405)
                        val update = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        updates += update
                        val revision = original["revisionDate"] ?: original["RevisionDate"]
                        if (update["lastKnownRevisionDate"] != revision || update["encryptedFor"] != JsonPrimitive(account)) {
                            return MockResponse().setResponseCode(409)
                        }
                        // Vaultwarden persists typed/common inputs and regenerates Data. It does
                        // not round-trip the request's Data object or serialized string verbatim.
                        val typed = update.getValue("login").jsonObject.toMutableMap()
                        typed.putIfAbsent("totp", JsonNull)
                        typed.putIfAbsent("fido2Credentials", JsonNull)
                        val response = (update - setOf("data", "lastKnownRevisionDate", "encryptedFor")).toMutableMap()
                        response["id"] = JsonPrimitive(id)
                        response["revisionDate"] = JsonPrimitive(REVISION_2)
                        response["login"] = JsonObject(typed)
                        if (response["fields"] == null || response["fields"] == JsonNull) response["fields"] = JsonArray(emptyList())
                        response.putIfAbsent("archivedDate", JsonNull)
                        if (regenerateDataOnUpdate) response["data"] = regeneratedMirror(JsonObject(response))
                        val accepted = JsonObject(response)
                        remote[id] = accepted
                        return ok(accepted)
                    }
                    val body = when (path) {
                        "/accounts/profile" -> profile
                        "/sync" -> {
                            syncRequests.incrementAndGet()
                            beforeSyncResponse?.invoke()
                            JsonObject(mapOf("profile" to profile, "ciphers" to JsonArray(remote.toSortedMap().values.toList()),
                                "folders" to JsonArray(emptyList()), "sends" to JsonArray(emptyList())))
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return ok(body)
                }
            }
        }

        suspend fun sync(): SyncResult {
            // The first sync writes the canonical profile identity back to Room. A real repository
            // reloads that vault before the next operation; mirror that boundary in this fixture.
            val currentVault = requireNotNull(database.bitwardenVaultDao().getVaultById(vault.id))
            return service.fullSync(currentVault, token, key)
        }
        fun enc(value: String) = JsonPrimitive(BitwardenCrypto.encryptString(value, key))
        private fun ok(value: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())

        private fun regeneratedMirror(document: JsonObject) = JsonObject(document.getValue("login").jsonObject +
            listOf("name", "notes", "fields", "passwordHistory").associateWith { document[it] ?: JsonNull })

        suspend fun checkUploadRoundTrip(shape: WireShape) {
            val id = "round-trip-${shape.name.lowercase()}"
            val base = cipher(id, 1)
            val login = base.getValue("login").jsonObject
            val uri = login.getValue("uris").jsonArray.single().jsonObject
            val history = base.getValue("passwordHistory").jsonArray.single().jsonObject
            val originalTyped = JsonObject(base - setOf("data", "fields") + mapOf(
                "login" to JsonObject(login + mapOf(
                    "FutureLogin" to JsonPrimitive("retained-typed-metadata"),
                    "uris" to JsonArray(listOf(JsonObject(uri + ("FutureUri" to JsonPrimitive("retained-uri-metadata"))))),
                )),
                "passwordHistory" to JsonArray(listOf(JsonObject(history + ("FutureHistory" to JsonPrimitive("retained-history-metadata"))))),
            ))
            val original = when (shape) {
                WireShape.CAMEL_OBJECT -> JsonObject(originalTyped + ("data" to regeneratedMirror(originalTyped)))
                WireShape.PASCAL_OBJECT -> pascalKeys(JsonObject(originalTyped + ("data" to regeneratedMirror(originalTyped)))).jsonObject
                WireShape.SERIALIZED_PASCAL -> JsonObject(originalTyped +
                    ("data" to JsonPrimitive(pascalKeys(regeneratedMirror(originalTyped)).toString())))
                WireShape.TYPED_ONLY -> originalTyped
            }
            regenerateDataOnUpdate = shape != WireShape.TYPED_ONLY
            remote[id] = original
            assertTrue("Initial $shape download", sync() is SyncResult.Success)
            val local = requireNotNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id))
            val editedTitle = "Synthetic edited $shape"
            val editedPassword = "Synthetic edited password $shape!"
            database.passwordEntryDao().update(local.copy(title = editedTitle,
                password = security.encryptData(editedPassword), bitwardenLocalModified = true))
            val result = service.uploadModifiedEntries(vault, token, key)
            assertTrue("Upload must accept regenerated $shape acknowledgment: $result", result is UploadResult.Success)
            result as UploadResult.Success
            assertEquals(1, result.uploaded)
            assertEquals(0, result.failed)
            assertEquals(listOf("GET $id", "PUT $id"), cipherRequests.toList())
            val sent = updates.single()
            assertEquals(JsonPrimitive(REVISION_1), sent["lastKnownRevisionDate"])
            val accepted = remote.getValue(id)
            assertEquals(editedTitle, BitwardenCrypto.decryptToString(accepted.getValue("name").jsonPrimitive.content, key))
            assertEquals(editedPassword, BitwardenCrypto.decryptToString(accepted.getValue("login").jsonObject.getValue("password").jsonPrimitive.content, key))
            val expectedLogin = originalTyped.getValue("login").jsonObject
            val actualLogin = accepted.getValue("login").jsonObject
            assertEquals(expectedLogin["FutureLogin"], actualLogin["FutureLogin"])
            assertEquals("retained-uri-metadata", actualLogin.getValue("uris").jsonArray.single().jsonObject.getValue("FutureUri").jsonPrimitive.content)
            val actualHistory = accepted.getValue("passwordHistory").jsonArray
            assertEquals(2, actualHistory.size)
            val originalHistory = original["passwordHistory"] ?: original.getValue("PasswordHistory")
            assertEquals(originalHistory.jsonArray.single(), actualHistory.first())
            assertEquals(secret(id), BitwardenCrypto.decryptToString(actualHistory.last().jsonObject.getValue("password").jsonPrimitive.content, key))
            val acceptedFields = accepted["fields"]?.jsonArray.orEmpty()
            val sentFields = sent["fields"]?.jsonArray.orEmpty()
            assertEquals("Vaultwarden keeps the typed custom-field rows it received", sentFields, acceptedFields)
            assertEquals(JsonNull, actualLogin["totp"])
            assertEquals(JsonNull, actualLogin["fido2Credentials"])
            if (regenerateDataOnUpdate) {
                assertTrue(accepted["data"] is JsonObject)
                assertEquals(regeneratedMirror(accepted), accepted["data"])
            } else assertFalse(accepted.containsKey("data"))
            val updated = requireNotNull(database.passwordEntryDao().getPasswordEntryById(local.id))
            assertFalse("Confirmed edit must retire dirty state", updated.bitwardenLocalModified)
            assertEquals(REVISION_2, updated.bitwardenRevisionDate)
            // Remove only this fixture's projection, then decrypt the actual server response again.
            database.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
            assertTrue("Cold $shape pull", sync() is SyncResult.Success)
            val cold = requireNotNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id))
            assertEquals(editedTitle, cold.title)
            assertEquals(editedPassword, security.decryptData(cold.password))
            assertEquals(username(id), cold.username)
            assertFalse(cold.bitwardenLocalModified)
            assertEquals(setOf("previous-$id", secret(id)), database.passwordHistoryDao().getHistoryByEntryIdSync(cold.id)
                .map { security.decryptData(it.password) }.toSet())
            assertEquals(accepted, BitwardenCipherSourceStore(context).read(vault, id, REVISION_2) { active }?.sourceDocument)
            assertEquals(1, database.passwordEntryDao().getByBitwardenVaultId(vault.id).size)
        }

        fun cipher(id: String, type: Int, pascal: Boolean = false, revision: String = REVISION_1): JsonObject {
            val sectionName = when (type) { 1 -> "login"; 2 -> "secureNote"; 3 -> "card"; 4 -> "identity"; 5 -> "sshKey"; else -> error("Unsupported fixture type") }
            val section = when (type) {
                1 -> JsonObject(mapOf("username" to enc(username(id)), "password" to enc(secret(id)),
                    "uris" to JsonArray(listOf(JsonObject(mapOf("uri" to enc("https://legacy.example.invalid/$id"), "match" to JsonPrimitive(0)))))))
                2 -> JsonObject(mapOf("type" to JsonPrimitive(0)))
                3 -> JsonObject(mapOf("number" to enc("4242424242424242"), "cardholderName" to enc("Synthetic Cardholder"),
                    "expMonth" to enc("11"), "expYear" to enc("2030"), "code" to enc("123"), "brand" to enc("Visa")))
                4 -> JsonObject(mapOf("firstName" to enc("Synthetic"), "lastName" to enc("Identity"), "ssn" to enc("synthetic-ssn"),
                    "passportNumber" to enc("SYNTHETIC-PASSPORT"), "email" to enc("identity@example.invalid")))
                else -> JsonObject(mapOf("privateKey" to enc("synthetic-private-key-$id"),
                    "publicKey" to enc("ssh-ed25519 AAAAsynthetic $id@example.invalid"), "keyFingerprint" to enc("SHA256:synthetic-$id")))
            }
            val common = mapOf("name" to enc(title(id)), "notes" to enc(notes(id)),
                "fields" to JsonArray(listOf(JsonObject(mapOf("name" to enc("Synthetic custom"), "value" to enc("custom-$id"), "type" to JsonPrimitive(0))))),
                "passwordHistory" to JsonArray(listOf(JsonObject(mapOf("password" to enc("previous-$id"), "lastUsedDate" to JsonPrimitive(REVISION_0))))))
            // Vaultwarden clones type_data_json and appends these encrypted common mirrors.
            val data = JsonObject(section + common + ("FutureLegacy" to JsonObject(mapOf("preserve" to JsonPrimitive(id)))))
            val document = JsonObject(common + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type),
                "revisionDate" to JsonPrimitive(revision), "favorite" to JsonPrimitive(false), "reprompt" to JsonPrimitive(0),
                "attachments" to JsonArray(emptyList()), sectionName to section, "data" to data))
            return if (pascal) pascalKeys(document).jsonObject else document
        }

        suspend fun checkFiveTypes(pascal: Boolean) {
            for (type in 1..5) remote["legacy-type-$type"] = cipher("legacy-type-$type", type, pascal)
            val result = sync()
            assertTrue("All five object-data cipher types must project: $result", result is SyncResult.Success)
            assertEquals(5, (result as SyncResult.Success).ciphersAdded)
            assertEquals(2, database.passwordEntryDao().getByBitwardenVaultId(vault.id).size)
            assertEquals(3, database.secureItemDao().getByBitwardenVaultId(vault.id).size)
            assertLogin("legacy-type-1")
            val ssh = requireNotNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "legacy-type-5"))
            assertEquals(5, ssh.bitwardenCipherType)
            assertEquals(title("legacy-type-5"), ssh.title)
            assertEquals("synthetic-private-key-legacy-type-5", requireNotNull(SshKeyDataCodec.decode(ssh.sshKeyData)).privateKeyOpenSsh)
            for ((type, itemType) in listOf(2 to ItemType.NOTE, 3 to ItemType.BANK_CARD, 4 to ItemType.DOCUMENT)) {
                val id = "legacy-type-$type"
                val item = requireNotNull(database.secureItemDao().getByBitwardenCipherIdInVault(vault.id, id))
                assertEquals(itemType, item.itemType)
                assertEquals(title(id), item.title)
                assertEquals(notes(id), item.notes)
                assertEquals(REVISION_1, item.bitwardenRevisionDate)
                val fields = Json.parseToJsonElement(security.decryptDataIfMonicaCiphertext(item.itemData)).jsonObject
                when (type) {
                    2 -> assertEquals(notes(id), fields.getValue("content").jsonPrimitive.content)
                    3 -> assertEquals("4242424242424242", fields.getValue("cardNumber").jsonPrimitive.content)
                    4 -> {
                        assertEquals("Synthetic", fields.getValue("firstName").jsonPrimitive.content)
                        assertEquals("SYNTHETIC-PASSPORT", fields.getValue("passportNumber").jsonPrimitive.content)
                    }
                }
            }
            remote.keys.forEach { assertSource(it) }
            assertEquals(1, syncRequests.get())
        }

        suspend fun assertLogin(id: String) {
            val entry = requireNotNull(database.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id))
            assertEquals(title(id), entry.title)
            assertEquals(username(id), entry.username)
            assertEquals(secret(id), security.decryptData(entry.password))
            assertEquals(notes(id), entry.notes)
            assertEquals(REVISION_1, entry.bitwardenRevisionDate)
            assertEquals(1, entry.bitwardenCipherType)
            val custom = database.customFieldDao().getFieldsByEntryIdSync(entry.id).single { it.title == "Synthetic custom" }
            assertEquals("custom-$id", security.decryptDataIfMonicaCiphertext(custom.value))
            val history = database.passwordHistoryDao().getHistoryByEntryIdSync(entry.id)
            assertEquals(listOf("previous-$id"), history.map { security.decryptData(it.password) })
        }

        suspend fun assertSource(id: String) {
            val source = BitwardenCipherSourceStore(context).read(vault, id, REVISION_1) { active }
            assertEquals("Original complete wire object must remain sealed without flattening its data mirror", remote[id], source?.sourceDocument)
        }

        suspend fun close() {
            if (::vault.isInitialized) {
                cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vault.id)
                cache<String>("accessTokenCache").remove(vault.id)
                database.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
                database.secureItemDao().deleteAllByBitwardenVaultId(vault.id)
                database.passkeyDao().deleteAllByBitwardenVaultId(vault.id)
                database.bitwardenConflictBackupDao().deleteByVault(vault.id)
                database.bitwardenSyncRawEntryRecordDao().deleteByVault(vault.id)
                database.bitwardenSendDao().deleteByVault(vault.id)
                database.bitwardenFolderDao().deleteByVault(vault.id)
                database.bitwardenVaultDao().deleteById(vault.id)
            }
            key.clear()
        }
    }

    private fun pascalKeys(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapKeys { (name, _) -> if (name == "ssn") "SSN" else name.replaceFirstChar(Char::uppercaseChar) }
            .mapValues { (_, child) -> pascalKeys(child) })
        is JsonArray -> JsonArray(value.map(::pascalKeys))
        else -> value
    }

    private enum class WireShape { CAMEL_OBJECT, PASCAL_OBJECT, SERIALIZED_PASCAL, TYPED_ONLY }

    companion object {
        private const val REVISION_0 = "2026-11-01T00:00:00Z"
        private const val REVISION_1 = "2026-11-08T00:00:00Z"
        private const val REVISION_2 = "2026-11-08T01:00:00Z"
        private fun title(id: String) = "Synthetic legacy $id"
        private fun username(id: String) = "synthetic-user-$id"
        private fun secret(id: String) = "Synthetic secret $id!"
        private fun notes(id: String) = "Synthetic legacy notes $id 😀"
    }
}
