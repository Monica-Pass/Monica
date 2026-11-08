package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Production service, actual HTTP and Room, using only synthetic accounts and records. */
@RunWith(AndroidJUnit4::class)
class BitwardenCipherDocumentInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = PasswordDatabase.getDatabase(context)

    @Test fun unreadableFolderAndSendFieldsReportFailureAndRetainPreviousData(): Unit = runBlocking {
        scenario {
            fun enc(s: String) = JsonPrimitive(BitwardenCrypto.encryptString(s, key))
            val folder = JsonObject(mapOf("id" to JsonPrimitive("$account-folder"), "name" to enc("Readable folder")))
            val material = ByteArray(16) { 71 }
            val sendKey = BitwardenCrypto.deriveSendKey(material)
            val send = try {
                fun sendEnc(s: String) = JsonPrimitive(BitwardenCrypto.encryptString(s, sendKey))
                JsonObject(mapOf("id" to JsonPrimitive("$account-send"), "accessId" to JsonPrimitive("synthetic"),
                    "key" to JsonPrimitive(BitwardenCrypto.encrypt(material, key)), "type" to JsonPrimitive(0),
                    "name" to sendEnc("Readable Send"), "notes" to sendEnc("Readable notes"),
                    "text" to JsonObject(mapOf("text" to sendEnc("Readable content"))),
                    "revisionDate" to JsonPrimitive(revision1), "deletionDate" to JsonPrimitive(revision2)))
            } finally { sendKey.clear(); material.fill(0) }
            extraFolders = JsonArray(listOf(folder)); extraSends = JsonArray(listOf(send))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val originalFolder = db.bitwardenFolderDao().getFoldersByVault(vault.id).single()
            val originalSend = db.bitwardenSendDao().getSendsByVault(vault.id).single()
            val wrong = SymmetricCryptoKey(ByteArray(32) { 55 }, ByteArray(32) { 77 })
            val bad = try { JsonPrimitive(BitwardenCrypto.encryptString("corrupt", wrong)) } finally { wrong.clear() }
            extraFolders = JsonArray(listOf(JsonObject(folder + ("name" to bad))))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Error)
            assertEquals(originalFolder, db.bitwardenFolderDao().getFoldersByVault(vault.id).single())
            extraFolders = JsonArray(listOf(folder))
            for (field in listOf("name", "notes", "text")) {
                val patch = if (field == "text") JsonObject(mapOf("text" to bad)) else bad
                extraSends = JsonArray(listOf(JsonObject(send + (field to patch) + ("revisionDate" to JsonPrimitive(revision2)))))
                assertTrue(field, service.fullSync(vault, token, key) is SyncResult.Error)
                val actual = db.bitwardenSendDao().getSendsByVault(vault.id).single()
                assertEquals(originalSend.name, actual.name); assertEquals(originalSend.notes, actual.notes)
                assertEquals(originalSend.textContent, actual.textContent); assertEquals(revision1, actual.revisionDate)
            }
            extraSends = JsonArray(listOf(send))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
        }
    }

    @Test fun corruptEncryptedFieldsCannotReplaceReadableDataOrAcceptTheirRevision(): Unit = runBlocking {
        scenario {
            for (type in 1..4) seed("field$type", type)
            fun enc(s: String) = JsonPrimitive(BitwardenCrypto.encryptString(s, key))
            val login = remote.getValue("field1")
            remote["field1"] = JsonObject(login + ("fields" to JsonArray(listOf(JsonObject(mapOf("name" to enc("field"), "value" to enc("value"), "type" to JsonPrimitive(1)))))) +
                ("login" to JsonObject(login["login"]!!.jsonObject + ("uris" to JsonArray(listOf(JsonObject(mapOf("uri" to enc("https://synthetic.invalid"), "uriChecksum" to enc("checksum")))))))))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val originals = remote.toMap()
            val damaged = SymmetricCryptoKey(ByteArray(32) { 99 }, ByteArray(32) { 66 })
            val bad = try { JsonPrimitive(BitwardenCrypto.encryptString("damaged", damaged)) } finally { damaged.clear() }
            fun replace(value: JsonElement, path: List<String>): JsonElement {
                if (path.isEmpty()) return bad
                return if (value is JsonArray) JsonArray(value.mapIndexed { i, child -> if (i == path.first().toInt()) replace(child, path.drop(1)) else child })
                else JsonObject(value.jsonObject + (path.first() to replace(value.jsonObject.getValue(path.first()), path.drop(1))))
            }
            val cases = listOf(1 to "notes", 1 to "login.username", 1 to "login.password", 1 to "login.uris.0.uri",
                1 to "login.uris.0.uriChecksum", 1 to "fields.0.name", 1 to "fields.0.value", 1 to "passwordHistory.0.password",
                2 to "notes", 3 to "card.number", 4 to "identity.firstName")
            for ((type, path) in cases) {
                val id = "field$type"
                remote[id] = JsonObject(replace(originals.getValue(id), path.split('.')).jsonObject + ("revisionDate" to JsonPrimitive(revision2)))
                assertTrue(path, service.fullSync(vault, token, key) is SyncResult.Error)
                val bound = if (type == 1) db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id)?.bitwardenRevisionDate
                    else db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, id)?.bitwardenRevisionDate
                assertEquals(path, revision1, bound)
                assertNull(path, db.bitwardenCipherSourceDao().get(vault.id, id, revision2))
                assertEquals(path, originals[id], BitwardenCipherSourceStore(context).read(vault, id, revision1) { active }?.sourceDocument)
                remote[id] = originals.getValue(id)
            }
            assertEquals("Synthetic notes", db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "field1")?.notes)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
        }
    }

    @Test fun serverNormalizingEmptyFieldsAndLegacyPrimaryUriRetiresOnlyConfirmedEdit(): Unit = runBlocking {
        scenario {
            seed("legacy-uri", 1)
            val uri = JsonPrimitive(BitwardenCrypto.encryptString("https://synthetic.example.invalid:30080/login", key))
            val source = remote.getValue("legacy-uri")
            remote["legacy-uri"] = JsonObject(source + ("login" to JsonObject(source["login"]!!.jsonObject +
                ("uri" to uri) + ("uris" to JsonArray(listOf(JsonObject(mapOf("uri" to uri, "match" to JsonPrimitive(3)))))))))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "legacy-uri"))
            assertEquals("https://synthetic.example.invalid:30080/login", entry.website)
            db.passwordEntryDao().update(entry.copy(notes = "Confirmed notes edit", bitwardenLocalModified = true))
            acknowledgmentTransform = { response ->
                val login = response["login"]!!.jsonObject
                JsonObject(response + ("fields" to (response["fields"]?.takeUnless { it == JsonNull } ?: JsonArray(emptyList()))) + ("login" to JsonObject(login +
                    ("uri" to login["uris"]!!.jsonArray.first().jsonObject.getValue("uri")))))
            }
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(1, result.uploaded)
            assertEquals(0, result.failed)
            val actual = remote.getValue("legacy-uri")
            assertEquals(source["FutureEnvelope"], actual["FutureEnvelope"])
            assertEquals(source["passwordHistory"], actual["passwordHistory"])
            assertEquals(actual["login"]!!.jsonObject["uri"], actual["login"]!!.jsonObject["uris"]!!.jsonArray.first().jsonObject["uri"])
            assertFalse(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            assertEquals("Confirmed notes edit", db.passwordEntryDao().getPasswordEntryById(entry.id)?.notes)
            assertEquals(1, db.passwordEntryDao().getAllByBitwardenCipherIdInVault(vault.id, "legacy-uri").size)
        }
    }

    @Test fun newLegacyLoginWithWrappingKeyIdAndPerItemKeyIsPulled(): Unit = runBlocking {
        scenario {
            val itemKey = SymmetricCryptoKey(ByteArray(32) { 67 }, ByteArray(32) { 98 })
            try {
                fun enc(s: String) = JsonPrimitive(BitwardenCrypto.encryptString(s, itemKey))
                val wrappingId = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(byteArrayOf(0xa2.toByte(), 1, 4, 0x20, 0x58, 0x40) + key.encKey + key.macKey)
                    .take(16).joinToString("") { "%02x".format(it) }
                remote["keyguard"] = JsonObject(mapOf("id" to JsonPrimitive("keyguard"), "type" to JsonPrimitive(1),
                    "name" to enc("Synthetic Keyguard style"), "revisionDate" to JsonPrimitive(revision1),
                    "key" to JsonPrimitive(BitwardenCrypto.encrypt(itemKey.encKey + itemKey.macKey, key)),
                    "encryptedByKeyId" to JsonPrimitive(wrappingId),
                    "login" to JsonObject(mapOf("username" to enc("keyguard-user"), "password" to enc("keyguard-secret")))))
                assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
                val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "keyguard"))
                assertEquals("Synthetic Keyguard style", entry.title)
                assertEquals("keyguard-user", entry.username)
                assertEquals("keyguard-secret", security.decryptData(entry.password))
                assertFalse(entry.bitwardenLocalModified)
                assertNotNull(db.bitwardenCipherSourceDao().get(vault.id, "keyguard", revision1))
                assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
                assertEquals(1, db.passwordEntryDao().getAllByBitwardenCipherIdInVault(vault.id, "keyguard").size)
            } finally { itemKey.clear() }
        }
    }

    @Test fun wrongWrappingKeyIdReportsIncompleteSyncAndRetainsReadableLocalData(): Unit = runBlocking {
        scenario {
            seed("healthy", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val healthy = db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "healthy")
            remote["wrong-key"] = JsonObject(remote.getValue("healthy") +
                ("id" to JsonPrimitive("wrong-key")) + ("encryptedByKeyId" to JsonPrimitive("00000000000000000000000000000000")))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Error)
            assertEquals(healthy, db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "healthy"))
            assertNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "wrong-key"))
            remote.remove("wrong-key")
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
        }
    }

    @Test fun loginEditRetainsArchivedStateHistoryAndUnknownDataThroughColdPull(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val original = remote.getValue("login")
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            assertTrue(entry.isArchived)
            db.passwordEntryDao().update(entry.copy(title = "Edited synthetic login", password = security.encryptData("edited-secret"), bitwardenLocalModified = true))
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(1, result.uploaded)
            assertEquals(0, result.failed)
            val actual = remote.getValue("login")
            for (field in listOf("archivedDate", "FutureEnvelope", "attachments")) assertEquals(field, original[field], actual[field])
            val history = actual.getValue("passwordHistory").jsonArray
            assertEquals(original.getValue("passwordHistory").jsonArray, JsonArray(history.dropLast(1)))
            assertEquals(BitwardenCrypto.decryptToString(original.getValue("login").jsonObject.getValue("password").jsonPrimitive.content, key),
                BitwardenCrypto.decryptToString(history.last().jsonObject.getValue("password").jsonPrimitive.content, key))
            assertEquals(original["login"]!!.jsonObject["FutureLogin"], actual["login"]!!.jsonObject["FutureLogin"])
            assertEquals("edited-secret", BitwardenCrypto.decryptToString(actual["login"]!!.jsonObject["password"]!!.jsonPrimitive.content, key))
            assertEquals(revision1, bodies.single()["lastKnownRevisionDate"]!!.jsonPrimitive.content)
            assertFalse(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
            db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val cold = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            assertEquals("Edited synthetic login", cold.title)
            assertTrue(cold.isArchived)
            assertEquals("edited-secret", security.decryptData(cold.password))
        }
    }

    @Test fun noteCardAndIdentityEditsUseSameDocumentLayerAndKeepArchiveAndUnknownSections(): Unit = runBlocking {
        scenario {
            for (type in 2..4) seed("item$type", type)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val originals = remote.toMap()
            for (type in 2..4) {
                val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "item$type"))
                db.secureItemDao().update(item.copy(title = "Edited item $type", bitwardenLocalModified = true))
            }
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(3, result.uploaded)
            assertEquals(0, result.failed)
            for ((id, original) in originals) {
                val actual = remote.getValue(id)
                assertEquals(original["FutureEnvelope"], actual["FutureEnvelope"])
                assertEquals(original["archivedDate"], actual["archivedDate"])
                val section = when (id) { "item2" -> "secureNote"; "item3" -> "card"; else -> "identity" }
                assertEquals(original[section]!!.jsonObject["FutureSection"], actual[section]!!.jsonObject["FutureSection"])
                assertFalse(requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, id)).bitwardenLocalModified == true)
            }
            db.secureItemDao().deleteAllByBitwardenVaultId(vault.id)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            for (type in 2..4) assertEquals("Edited item $type", db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "item$type")?.title)
        }
    }

    @Test fun blobPartialAndFutureTypesNeverReplaceAnExistingProjectionOrCreateEmptyEntries(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            remote["login"] = JsonObject(remote.getValue("login") + ("data" to JsonPrimitive("unsupported-blob")) + ("revisionDate" to JsonPrimitive(revision2)))
            remote["partial"] = JsonObject(remote.getValue("login") + ("id" to JsonPrimitive("partial")) + ("partialData" to JsonObject(mapOf("future" to JsonPrimitive(1)))))
            remote["future"] = JsonObject(remote.getValue("login") + ("id" to JsonPrimitive("future")) + ("type" to JsonPrimitive(8)))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Error)
            assertEquals(entry, db.passwordEntryDao().getPasswordEntryById(entry.id))
            assertNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "partial"))
            assertNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "future"))
            db.passwordEntryDao().update(entry.copy(title = "Pending local edit", bitwardenLocalModified = true))
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, result.uploaded)
            assertTrue(bodies.isEmpty())
            assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
        }
    }

    @Test fun freshRemoteRevisionDoesNotReplaceOriginalEditAuthorizationAndConflictKeepsUnknownData(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            db.passwordEntryDao().update(entry.copy(title = "Local stale edit", bitwardenLocalModified = true))
            remote["login"] = JsonObject(remote.getValue("login") + ("revisionDate" to JsonPrimitive(revision2)))
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, result.uploaded)
            assertEquals(1, result.failed)
            assertTrue(bodies.isEmpty())
            val conflict = db.bitwardenConflictBackupDao().getUnresolvedConflictsByVault(vault.id).single()
            val stored = security.decryptData(org.json.JSONObject(requireNotNull(conflict.serverDataJson)).getString("sealed"))
            assertTrue(stored.contains("FutureEnvelope"))
            assertEquals(revision1, db.passwordEntryDao().getPasswordEntryById(entry.id)?.bitwardenRevisionDate)
        }
    }

    @Test fun malformedSyncNeverTriggersDeletionAndFailedPutKeepsLocalChanges(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            syncOverride = """{"profile":{"id":"$account"},"ciphers":[],"ciphers":[]}"""
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Error)
            assertEquals(entry, db.passwordEntryDao().getPasswordEntryById(entry.id))
            syncOverride = null
            db.passwordEntryDao().update(entry.copy(title = "Pending after failure", bitwardenLocalModified = true))
            updateStatus = 500
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, result.uploaded)
            assertEquals(1, result.failed)
            assertEquals(1, bodies.size)
            val latest = requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id))
            assertTrue(latest.bitwardenLocalModified)
            assertEquals("Pending after failure", latest.title)
            assertEquals(revision1, latest.bitwardenRevisionDate)
        }
    }


    @Test fun sealedSourcesKeepPendingBaselineAndLatestObservationAndRejectAccountOrEnvelopeSwap(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            assertEquals(85, db.openHelper.writableDatabase.version)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            val original = remote.getValue("login")
            db.passwordEntryDao().update(entry.copy(title = "Pending baseline", bitwardenLocalModified = true))
            remote["login"] = JsonObject(original + ("revisionDate" to JsonPrimitive(revision2)))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val sources = db.bitwardenCipherSourceDao()
            val old = requireNotNull(sources.get(vault.id, "login", revision1))
            assertFalse(old.sealedDocument.contains("FutureEnvelope"))
            val store = BitwardenCipherSourceStore(context)
            assertEquals(original, (store.read(vault, "login", revision1) { active })?.sourceDocument)
            assertEquals(remote["login"], (store.read(vault, "login", revision2) { active })?.sourceDocument)
            try {
                store.read(vault.copy(userId = UUID.randomUUID().toString()), "login", revision1) { active }
                fail("Different account accepted")
            } catch (_: IllegalArgumentException) {}
            // Substitute a valid ciphertext of another revision: envelope binding must reject it.
            sources.put(old.copy(sealedDocument = requireNotNull(sources.get(vault.id, "login", revision2)).sealedDocument))
            try { store.read(vault, "login", revision1) { active }; fail("Swapped envelope accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun revocationBetweenSourceReadAndPutKeepsLocalEditAndDoesNotSendMutation(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            db.passwordEntryDao().update(entry.copy(title = "Pending revoked", bitwardenLocalModified = true))
            revokeOnCipherGet = true
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, result.uploaded)
            assertTrue(bodies.isEmpty())
            assertEquals(revision1, db.passwordEntryDao().getPasswordEntryById(entry.id)?.bitwardenRevisionDate)
            assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
        }
    }


    @Test fun explicitArchiveAndUnarchiveUseExistingPendingIntentAndPreserveUnknownFields(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val originalUnknown = remote.getValue("login")["FutureEnvelope"]
            val archive = db.passwordArchiveSyncMetaDao()
            for (archived in listOf(false, true)) {
                val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
                db.passwordEntryDao().update(entry.copy(isArchived = archived,
                    archivedAt = if (archived) java.util.Date() else null, bitwardenLocalModified = true))
                archive.upsert(takagi.ru.monica.data.PasswordArchiveSyncMeta(entryId = entry.id,
                    providerType = takagi.ru.monica.data.PasswordArchiveSyncMeta.PROVIDER_BITWARDEN_NATIVE,
                    syncStatus = takagi.ru.monica.data.PasswordArchiveSyncMeta.STATUS_PENDING))
                val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
                assertEquals(1, result.uploaded)
                assertEquals(0, result.failed)
                val actual = remote.getValue("login")["archivedDate"]
                assertEquals(archived, actual != null && actual != JsonNull)
                assertEquals(originalUnknown, remote.getValue("login")["FutureEnvelope"])
                assertEquals(takagi.ru.monica.data.PasswordArchiveSyncMeta.STATUS_SYNCED, archive.getByEntryId(entry.id)?.syncStatus)
                assertFalse(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
            }
        }
    }

    @Test fun incompleteLoginAcknowledgmentKeepsPendingArchiveOriginalSourceAndEditUntilRetry(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val original = remote.getValue("login")
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            db.passwordEntryDao().update(entry.copy(title = "Pending full acknowledgment", isArchived = false,
                archivedAt = null, bitwardenLocalModified = true))
            val archive = db.passwordArchiveSyncMetaDao()
            archive.upsert(takagi.ru.monica.data.PasswordArchiveSyncMeta(entryId = entry.id,
                providerType = takagi.ru.monica.data.PasswordArchiveSyncMeta.PROVIDER_BITWARDEN_NATIVE,
                syncStatus = takagi.ru.monica.data.PasswordArchiveSyncMeta.STATUS_PENDING))
            for (field in listOf("FutureEnvelope", "passwordHistory", "name")) {
                remote["login"] = original
                acknowledgmentTransform = { JsonObject(it - field) }
                val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
                assertEquals(field, 0, result.uploaded)
                assertEquals(1, result.failed)
                assertEquals(revision1, db.passwordEntryDao().getPasswordEntryById(entry.id)?.bitwardenRevisionDate)
                assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
                assertEquals(takagi.ru.monica.data.PasswordArchiveSyncMeta.STATUS_PENDING, archive.getByEntryId(entry.id)?.syncStatus)
                assertNull(db.bitwardenCipherSourceDao().get(vault.id, "login", revision2))
                assertEquals(original, BitwardenCipherSourceStore(context).read(vault, "login", revision1) { active }?.sourceDocument)
            }
            remote["login"] = original
            acknowledgmentTransform = { it }
            assertEquals(1, (service.uploadModifiedEntries(vault, token, key) as UploadResult.Success).uploaded)
            assertFalse(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
            assertEquals(takagi.ru.monica.data.PasswordArchiveSyncMeta.STATUS_SYNCED, archive.getByEntryId(entry.id)?.syncStatus)
        }
    }

    @Test fun incompleteSecureItemAcknowledgmentKeepsOriginalSourceAndDirtyState(): Unit = runBlocking {
        scenario {
            for (type in 2..4) seed("item$type", type)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val originals = remote.toMap()
            for (type in 2..4) {
                val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "item$type"))
                db.secureItemDao().update(item.copy(title = "Pending item $type", bitwardenLocalModified = true))
            }
            acknowledgmentTransform = { JsonObject(it - "FutureEnvelope") }
            val failed = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, failed.uploaded)
            assertEquals(3, failed.failed)
            for (type in 2..4) {
                val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "item$type"))
                assertTrue(item.bitwardenLocalModified == true)
                assertEquals(revision1, item.bitwardenRevisionDate)
                assertNull(db.bitwardenCipherSourceDao().get(vault.id, "item$type", revision2))
            }
            originals.forEach { (id, source) -> remote[id] = source }
            acknowledgmentTransform = { it }
            assertEquals(3, (service.uploadModifiedEntries(vault, token, key) as UploadResult.Success).uploaded)
            for (type in 2..4) assertFalse(requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "item$type")).bitwardenLocalModified == true)
        }
    }

    @Test fun sessionRevocationAfterPutCannotPublishAcknowledgmentOrRetirePendingEdit(): Unit = runBlocking {
        scenario {
            seed("login", 1)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "login"))
            db.passwordEntryDao().update(entry.copy(title = "Pending revoked response", bitwardenLocalModified = true))
            acknowledgmentTransform = { active = false; it }
            assertEquals(0, (service.uploadModifiedEntries(vault, token, key) as UploadResult.Success).uploaded)
            assertEquals(1, bodies.size)
            assertEquals(revision1, db.passwordEntryDao().getPasswordEntryById(entry.id)?.bitwardenRevisionDate)
            assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
            assertNull(db.bitwardenCipherSourceDao().get(vault.id, "login", revision2))
        }
    }

    @Test fun syncIssuesIdentifyBrokenItemsAndVerifiedTitlesThenClearOnSuccessfulRetry(): Unit = runBlocking {
        scenario {
            seed("readable", 1); seed("broken", 1)
            val original = remote.getValue("broken")
            val wrong = SymmetricCryptoKey(ByteArray(32) { 91 }, ByteArray(32) { 92 })
            val bad = try { JsonPrimitive(BitwardenCrypto.encryptString("private-secret-never-in-report", wrong)) }
                finally { wrong.clear() }
            remote["broken"] = JsonObject(original + ("notes" to bad))
            extraFolders = JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("broken-folder"), "name" to bad))))
            val issues = BitwardenSyncIssues()
            val attempt = issues.begin(vault.id)
            assertTrue(service.fullSync(vault, token, key, attempt) is SyncResult.Error)
            attempt.finish()
            val report = issues.reports.value.getValue(vault.id)
            assertEquals(setOf("broken", "broken-folder"), report.items.map { it.itemId }.toSet())
            assertEquals("Synthetic broken", report.items.single { it.itemId == "broken" }.title)
            assertEquals(SyncIssuePhase.DOWNLOAD, report.items.single { it.itemId == "broken" }.phase)
            assertFalse(report.toString().contains("private-secret-never-in-report"))
            assertFalse(report.toString().contains(bad.jsonPrimitive.content))
            assertNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "readable"))
            assertNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "broken"))
            remote["broken"] = original; extraFolders = JsonArray(emptyList())
            val retry = issues.begin(vault.id)
            assertTrue(service.fullSync(vault, token, key, retry) is SyncResult.Success)
            retry.finish()
            assertTrue(issues.reports.value.getValue(vault.id).items.isEmpty())
            assertNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "broken"))
        }
    }

    @Test fun syncIssuesIdentifyEachFailedUploadAndKeepPendingEditsUntilRetry(): Unit = runBlocking {
        scenario {
            seed("upload-login", 1); seed("upload-note", 2)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val login = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "upload-login"))
            val note = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "upload-note"))
            db.passwordEntryDao().update(login.copy(notes = "Pending login note", bitwardenLocalModified = true))
            db.secureItemDao().update(note.copy(title = "Pending note title", bitwardenLocalModified = true))
            updateStatus = 409
            val issues = BitwardenSyncIssues()
            val attempt = issues.begin(vault.id)
            val result = service.uploadModifiedEntries(vault, token, key, attempt) as UploadResult.Success
            attempt.finish()
            assertEquals(2, result.failed)
            val rows = issues.reports.value.getValue(vault.id).items
            assertEquals(setOf("upload-login", "upload-note"), rows.map { it.itemId }.toSet())
            assertTrue(rows.all { it.phase == SyncIssuePhase.UPDATE && it.reason == SyncIssueReason.CONFLICT })
            assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(login.id)).bitwardenLocalModified)
            assertEquals(true, db.secureItemDao().getItemById(note.id)?.bitwardenLocalModified)
            updateStatus = 200
            val retry = issues.begin(vault.id)
            val saved = service.uploadModifiedEntries(vault, token, key, retry) as UploadResult.Success
            retry.finish()
            assertEquals(2, saved.uploaded); assertEquals(0, saved.failed)
            assertTrue(issues.reports.value.getValue(vault.id).items.isEmpty())
        }
    }

    @Test fun lostCreateRebindsWithoutPostingAgainAndKeepsExplicitHistoryClear(): Unit = runBlocking {
        scenario {
            seed("lost-create", 1)
            val id = db.passwordEntryDao().insert(takagi.ru.monica.data.PasswordEntry(
                title = "Synthetic lost-create", website = "", username = "synthetic-user",
                password = security.encryptData("synthetic-secret"), notes = "Synthetic notes",
                bitwardenVaultId = vault.id, bitwardenCipherType = 1, bitwardenLocalModified = true))
            db.passwordHistoryDao().insert(takagi.ru.monica.data.PasswordHistoryEntry(entryId = id,
                password = security.encryptData("old-synthetic-secret"), lastUsedAt = java.util.Date.from(java.time.Instant.parse(revision1))))
            assertTrue(BitwardenPasswordHistoryStore(context).clear(id))
            // A fresh service/store sees the earlier explicit clear after an uncertain POST.
            val rebound = service.uploadLocalEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, rebound.uploaded); assertEquals(0, rebound.failed)
            val pending = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            assertEquals("lost-create", pending.bitwardenCipherId)
            assertTrue(pending.bitwardenLocalModified)
            assertTrue(db.passwordHistoryDao().getHistoryByEntryIdSync(id).isEmpty())
            assertNotNull(BitwardenCipherSourceStore(context).read(vault, "lost-create", revision1) { active })
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(1, result.uploaded); assertEquals(0, result.failed)
            assertEquals(JsonArray(emptyList()), remote.getValue("lost-create")["passwordHistory"])
            assertEquals(1, bodies.size)
            assertFalse(requireNotNull(db.passwordEntryDao().getPasswordEntryById(id)).bitwardenLocalModified)
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        val unlocked = SessionManager.isUnlocked.value
        val cache = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val original = cache.get(null)
        val synthetic = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        cache.set(null, synthetic)
        SessionManager.markUnlocked()
        try {
            MockWebServer().use { server ->
                val fixture = Fixture(server)
                try { fixture.initialize(); fixture.block() } finally { fixture.close() }
            }
        } finally {
            cache.set(null, original)
            synthetic.fill(0)
            if (!unlocked) SessionManager.markLocked()
        }
    }

    private inner class Fixture(private val server: MockWebServer) {
        val account = UUID.randomUUID().toString()
        val token = "synthetic-document-token"
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        val security = SecurityManager(context)
        var active = true
        var revokeOnCipherGet = false
        val service = BitwardenSyncService(context, sessionIsActive = { _, candidate -> active && candidate === key })
        val remote = ConcurrentHashMap<String, JsonObject>()
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val revision1 = "2026-11-03T00:00:00Z"
        val revision2 = "2026-11-04T01:00:00Z"
        var syncOverride: String? = null
        var extraFolders = JsonArray(emptyList())
        var extraSends = JsonArray(emptyList())
        var updateStatus = 200
        var acknowledgmentTransform: (JsonObject) -> JsonObject = { it }
        lateinit var vault: BitwardenVault
        private val repository by lazy { takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context) }
        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>

        suspend fun initialize() {
            val endpoint = server.url("/").toString()
            val id = db.bitwardenVaultDao().insert(BitwardenVault(email = "$account@example.invalid",
                canonicalEmail = "$account@example.invalid", userId = account,
                accountKey = takagi.ru.monica.bitwarden.BitwardenVaultIdentity.buildAccountKey(endpoint, account, "$account@example.invalid"),
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint, isLocked = false))
            vault = requireNotNull(db.bitwardenVaultDao().getVaultById(id))
            cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
            cache<String>("accessTokenCache")[id] = token
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    val profile = """{"id":"$account","email":"${vault.email}","securityStamp":"synthetic-stamp"}"""
                    if (path == "/accounts/profile") return ok(profile)
                    if (path == "/sync") return ok(syncOverride ?: """{"profile":$profile,"ciphers":${JsonArray(remote.values.toList())},"folders":$extraFolders,"sends":$extraSends}""")
                    val original = remote[path.removePrefix("/ciphers/")] ?: return MockResponse().setResponseCode(404)
                    if (request.method == "GET") { if (revokeOnCipherGet) active = false; return ok(original.toString()) }
                    if (request.method == "PUT") {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        bodies += body
                        if (updateStatus != 200) return MockResponse().setResponseCode(updateStatus)
                        if (body["lastKnownRevisionDate"] != original["revisionDate"] || body["encryptedFor"]?.jsonPrimitive?.content != account)
                            return MockResponse().setResponseCode(409)
                        val actual = acknowledgmentTransform(JsonObject(body + ("id" to original.getValue("id")) + ("revisionDate" to JsonPrimitive(revision2))))
                        remote[original["id"]!!.jsonPrimitive.content] = actual
                        return ok(actual.toString())
                    }
                    return MockResponse().setResponseCode(405)
                }
            }
        }

        fun seed(id: String, type: Int) {
            fun enc(value: String) = JsonPrimitive(BitwardenCrypto.encryptString(value, key))
            val section = when (type) {
                1 -> "login" to JsonObject(mapOf("username" to enc("synthetic-user"), "password" to enc("synthetic-secret"), "FutureLogin" to JsonPrimitive("keep")))
                2 -> "secureNote" to JsonObject(mapOf("type" to JsonPrimitive(0), "FutureSection" to JsonPrimitive("keep")))
                3 -> "card" to JsonObject(mapOf("number" to enc("4242424242424242"), "FutureSection" to JsonPrimitive("keep")))
                else -> "identity" to JsonObject(mapOf("firstName" to enc("Synthetic"), "FutureSection" to JsonPrimitive("keep")))
            }
            remote[id] = JsonObject(mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type), "name" to enc("Synthetic $id"),
                "notes" to enc("Synthetic notes"), "revisionDate" to JsonPrimitive(revision1), "archivedDate" to JsonPrimitive("2026-11-02T00:00:00Z"),
                "FutureEnvelope" to JsonObject(mapOf("keep" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))),
                "passwordHistory" to JsonArray(listOf(JsonObject(mapOf("password" to enc("old-synthetic-secret"), "lastUsedDate" to JsonPrimitive(revision1), "FutureHistory" to JsonPrimitive(99))))),
                "attachments" to JsonArray(emptyList()), section))
        }

        private fun ok(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
        suspend fun close() {
            if (::vault.isInitialized) {
                cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vault.id)
                cache<String>("accessTokenCache").remove(vault.id)
                db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
                db.secureItemDao().deleteAllByBitwardenVaultId(vault.id)
                db.passkeyDao().deleteAllByBitwardenVaultId(vault.id)
                db.bitwardenConflictBackupDao().deleteByVault(vault.id)
                db.bitwardenSyncRawEntryRecordDao().deleteByVault(vault.id)
                db.bitwardenSendDao().deleteByVault(vault.id)
                db.bitwardenFolderDao().deleteByVault(vault.id)
                db.bitwardenVaultDao().deleteById(vault.id)
            }
            key.clear()
        }
    }
}
