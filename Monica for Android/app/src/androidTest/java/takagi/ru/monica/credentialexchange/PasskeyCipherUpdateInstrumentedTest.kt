package takagi.ru.monica.credentialexchange

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.CipherUploadProcessor
import takagi.ru.monica.bitwarden.service.UploadItemResult
import takagi.ru.monica.data.PasskeyEntry

/** Actual Room, encrypted payloads, Retrofit and upload processor; synthetic loopback server only. */
class PasskeyCipherUpdateInstrumentedTest {
    private suspend fun scenario(mode: String) {
        val f = TransferFixture()
        try {
            val destination = f.bitwarden(unlocked = false)
            val vault = f.db.bitwardenVaultDao().getVaultById(destination.databaseId)!!
            val cipherId = UUID.randomUUID().toString()
            val material = Base64.getEncoder().encodeToString(f.pair.private.encoded)
            val one = PasskeyEntry(credentialId = f.credentialId, rpId = f.rpId, rpName = f.prefix,
                userId = f.userHandle, userName = "Alice edited", userDisplayName = "Alice", publicKey = "public",
                privateKeyAlias = material, notes = "original notes", bitwardenVaultId = vault.id,
                bitwardenCipherId = cipherId, syncStatus = "PENDING", passkeyMode = PasskeyEntry.MODE_BW_COMPAT)
            val two = one.copy(credentialId = "c2libGluZw", userName = "Bob edited", userDisplayName = "Bob")
            f.db.passkeyDao().insert(one); f.db.passkeyDao().insert(two)
            val rows = f.db.passkeyDao().getByBitwardenVaultId(vault.id)
            var first = rows.single { it.credentialId == one.credentialId }
            val second = rows.single { it.credentialId == two.credentialId }
            if (mode == "move-folder" || mode == "move-root") {
                // Ensure moving to root is an explicit null target, distinct from no intent.
                if (mode == "move-root") {
                    first = first.copy(bitwardenFolderId = "keep-folder")
                    f.db.passkeyDao().update(first)
                }
                f.passkeys.updatePasskey(first.copy(bitwardenFolderId = if (mode == "move-folder") "target-folder" else null))
                first = f.db.passkeyDao().getPasskeyByRecordId(first.id)!!
            }
            fun enc(value: String) = JsonPrimitive(BitwardenCrypto.encryptString(value, f.vaultKey))
            fun credential(row: PasskeyEntry) = buildJsonObject {
                put("credentialId", enc(row.credentialId)); put("rpId", enc(row.rpId)); put("rpName", enc(row.rpName))
                put("userHandle", enc(row.userId)); put("userName", enc("old")); put("userDisplayName", enc(row.userDisplayName))
                put("keyValue", enc(material)); put("counter", enc("0")); put("keyAlgorithm", enc("ECDSA"))
                put("keyCurve", enc("P-256")); put("keyType", enc("public-key")); put("discoverable", enc("true"))
            }
            var remote = buildJsonObject {
                put("id", cipherId); put("type", 1); put("revisionDate", "2026-10-10T00:00:00.000Z")
                put("name", enc("Shared login")); put("notes", enc("original notes")); put("folderId", "keep-folder")
                put("favorite", true); put("futureField", buildJsonObject { put("preserved", true) })
                put("fields", buildJsonArray { add(buildJsonObject { put("name", enc("custom")); put("value", enc("value")); put("type", 0) }) })
                put("login", buildJsonObject {
                    put("username", enc("Login")); put("password", enc("Do not replace")); put("totp", enc("SYNTHETIC"))
                    put("uris", buildJsonArray { add(buildJsonObject { put("uri", enc("https://example.invalid")); put("match", 3) }) })
                    put("fido2Credentials", JsonArray(listOf(credential(first), credential(second))))
                })
            }
            val original = remote
            val reads = AtomicInteger(); val writes = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath != "/ciphers/$cipherId") return MockResponse().setResponseCode(404)
                    if (request.method == "GET") {
                        reads.incrementAndGet()
                        if (mode == "local-before" && reads.get() == 1) runBlocking {
                            f.db.passkeyDao().update(first.copy(userName = "newer local edit"))
                        }
                        return MockResponse().setBody(remote.toString())
                    }
                    if (request.method != "PUT") return MockResponse().setResponseCode(405)
                    writes.incrementAndGet()
                    val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    if (mode == "conflict" || sent["lastKnownRevisionDate"] != remote["revisionDate"]) return MockResponse().setResponseCode(409)
                    if (mode == "local-during") runBlocking { f.db.passkeyDao().update(first.copy(userName = "newer local edit")) }
                    remote = JsonObject(sent + ("revisionDate" to JsonPrimitive("2026-10-10T00:00:0${writes.get()}.000Z")))
                    if (mode == "drops-sibling") {
                        val login = remote["login"]!!.jsonObject
                        remote = JsonObject(remote + ("login" to JsonObject(login + ("fido2Credentials" to JsonArray(listOf(login["fido2Credentials"]!!.jsonArray[0]))))))
                    }
                    return if (mode == "lost-response") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else MockResponse().setBody(remote.toString())
                }
            }
            val processor = CipherUploadProcessor(f.context)
            suspend fun upload(row: PasskeyEntry) = processor.updatePasskey(vault, row, cipherId, f.accessToken, f.vaultKey)
            val result = if (mode == "two-keys") coroutineScope {
                val a = async(Dispatchers.IO) { upload(first) }; val b = async(Dispatchers.IO) { upload(second) }
                assertTrue(a.await() is UploadItemResult.Success); b.await()
            } else upload(first)
            val local = f.db.passkeyDao().getPasskeyByRecordId(first.id)!!
            when (mode) {
                "conflict", "drops-sibling" -> { assertTrue(result.toString(), result is UploadItemResult.Error); assertEquals("FAILED", local.syncStatus) }
                "local-before", "local-during" -> { assertTrue(result is UploadItemResult.Error); assertEquals("newer local edit", local.userName); assertEquals("PENDING", local.syncStatus) }
                else -> { assertTrue(result.toString(), result is UploadItemResult.Success); assertEquals("SYNCED", local.syncStatus) }
            }
            assertEquals(if (mode == "local-before") 0 else if (mode == "two-keys") 2 else 1, writes.get())
            assertEquals(first.privateKeyAlias, local.privateKeyAlias); assertEquals(first.credentialId, local.credentialId)
            if (mode != "drops-sibling") {
                assertEquals(2, remote["login"]!!.jsonObject["fido2Credentials"]!!.jsonArray.size)
                for ((name, value) in original["login"]!!.jsonObject) if (name != "fido2Credentials") assertEquals(value, remote["login"]!!.jsonObject[name])
                for (name in listOf("name", "fields", "favorite", "futureField")) assertEquals(original[name], remote[name])
                when (mode) {
                    "move-folder" -> assertEquals(JsonPrimitive("target-folder"), remote["folderId"])
                    "move-root" -> assertEquals(JsonNull, remote["folderId"])
                    else -> assertEquals(original["folderId"], remote["folderId"])
                }
            }
            if (mode == "two-keys") {
                val keys = remote["login"]!!.jsonObject["fido2Credentials"]!!.jsonArray
                assertEquals(listOf("Alice edited", "Bob edited"), keys.map { BitwardenCrypto.decryptToString(it.jsonObject["userName"]!!.jsonPrimitive.content, f.vaultKey) })
            }
        } finally { f.close() }
    }
    @Test fun editsOneKeyPreservingSharedLogin(): Unit = runBlocking { scenario("normal") }
    @Test fun explicitFolderMovePreservesSiblingCredentials(): Unit = runBlocking { scenario("move-folder") }
    @Test fun explicitMoveBackToRootIsNotIgnored(): Unit = runBlocking { scenario("move-root") }
    @Test fun serializesTwoKeysInTheSameCipher(): Unit = runBlocking { scenario("two-keys") }
    @Test fun conflictDoesNotOverwriteOrMarkSynced(): Unit = runBlocking { scenario("conflict") }
    @Test fun committedWriteWithLostResponseIsVerifiedWithoutResending(): Unit = runBlocking { scenario("lost-response") }
    @Test fun concurrentLocalEditBeforePutIsRetained(): Unit = runBlocking { scenario("local-before") }
    @Test fun concurrentLocalEditDuringPutStaysPending(): Unit = runBlocking { scenario("local-during") }
    @Test fun serverDroppingSiblingIsNotReportedAsSuccess(): Unit = runBlocking { scenario("drops-sibling") }
}
