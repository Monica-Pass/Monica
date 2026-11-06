package takagi.ru.monica.data.dedup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.SingleVaultDedupUiIssue
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcomeStatus
import uniffi.mdbx_ffi.MdbxWriteCommand

/** Actual source adapters, Rust native files and loopback HTTP; never a Room native-source projection. */
@RunWith(AndroidJUnit4::class)
class SingleVaultDedupExternalSourcesInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = PasswordDatabase.getDatabase(context)

    @Test fun nativeMdbxRichProjectsAndUnknownPayloadsRemainUnchangedAndAnalysisOnly(): Unit = runBlocking {
        withSyntheticSession {
            val security = SecurityManager(context)
            val repository = Mdbx2Repository(context, database.localMdbxDatabaseDao(), security)
            val password = "Synthetic dedup native source only!"
            val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
            val id = database.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Dedup native source fixture", filePath = file.absolutePath, workingCopyPath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                storageLocation = MdbxStorageLocation.INTERNAL.name, encryptedPassword = security.encryptData(password)))
            val collection = UUID.randomUUID().toString()
            val accountProject = UUID.randomUUID().toString()
            val types = listOf("login", "login", "note", "bank-card", "document", "totp", "passkey", "steam-mafile", "api-token", "future.synthetic.v9")
            val ids = types.map { UUID.randomUUID().toString() }
            val payloads = types.mapIndexed { index, type -> mdbxPayload(type, index, accountProject) }
            try {
                repository.withVaultForSync(id) { _, vault ->
                    vault.executeWriteOperation(UUID.randomUUID().toString(), "dedup-rich-fixture", buildList {
                        add(MdbxWriteCommand.CreateProject(collection, "Synthetic extended content"))
                        ids.indices.forEach { index -> add(MdbxWriteCommand.CreateEntry(ids[index], collection,
                            types[index], "Synthetic ${types[index]} $index", payloads[index].toString())) }
                    })
                    // A real prior revision exists in addition to all current native values.
                    vault.executeWriteOperation(UUID.randomUUID().toString(), "dedup-rich-history", listOf(
                        MdbxWriteCommand.UpdateEntry(ids[2], collection, types[2], "Synthetic note 2", payloads[2].toString())))
                }
                val before = repository.withReadVaultForSync(id) { _, vault ->
                    vault.incrementalSyncCheckpoint().commitInventory to ids.associateWith { objectId ->
                        val record = requireNotNull(vault.revealObject(objectId).`object`)
                        Triple(vault.getObjectSummary(objectId), record.title, Json.parseToJsonElement(record.payloadJson))
                    }
                }
                assertTrue(database.passwordEntryDao().getByMdbxDatabaseIdSync(id).isEmpty())
                val adapter = SingleVaultDedupMdbxAdapter(context, database, security, id, "Synthetic MDBX", repository)
                val captured = adapter.capture(includeArchived = true, checkActive = {})
                assertEquals(9, captured.projects.size) // The two native password members form one project.
                assertFalse(adapter.source.canClean)
                assertEquals(SingleVaultDedupUiIssue.ANALYSIS_ONLY, adapter.source.issue)
                assertTrue(captured.projects.all { SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT in it.blockers })
                assertTrue(SingleVaultDedupBlockReason.PASSKEY_PRESERVED in captured.projects.single {
                    it.summary.title == "Synthetic passkey 6"
                }.blockers)
                val nodes = captured.projects.flatMap { flatten(requireNotNull(it.content)) }
                val nativePayloads = nodes.filter { it.kind == "mdbx-native-payload" }
                assertEquals(10, nativePayloads.size)
                assertEquals(payloads.toSet(), nativePayloads.map {
                    Json.parseToJsonElement(it.attributes.getValue("payload")).jsonObject
                }.toSet())
                assertEquals(10, nativePayloads.count {
                    "future_extension" in Json.parseToJsonElement(it.attributes.getValue("payload")).jsonObject
                })
                val grouped = captured.projects.single { it.summary.key.endsWith("group:$accountProject") }
                assertEquals(2, flatten(requireNotNull(grouped.content)).count { it.kind == "mdbx-native-payload" })
                assertTrue(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT in grouped.blockers)
                val unknown = captured.projects.single { it.summary.title == "Synthetic future.synthetic.v9 9" }
                assertTrue(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT in unknown.blockers)
                assertTrue(nodes.any { it.kind == "mdbx-history-structure" })
                assertTrue(SingleVaultDedupComparator.analyze(captured.projects).candidates.isEmpty())
                assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED, adapter.clean(captured,
                    listOf(SingleVaultDedupCandidate(captured.projects[0].summary.key, captured.projects[1].summary.key, 1)), {}).status)
                assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED,
                    adapter.restore(unwrittenRecovery(adapter.source.key), {}).status)
                val after = repository.withReadVaultForSync(id) { _, vault ->
                    vault.incrementalSyncCheckpoint().commitInventory to ids.associateWith { objectId ->
                        val record = requireNotNull(vault.revealObject(objectId).`object`)
                        Triple(vault.getObjectSummary(objectId), record.title, Json.parseToJsonElement(record.payloadJson))
                    }
                }
                assertEquals(before, after)
                assertTrue(database.passwordEntryDao().getByMdbxDatabaseIdSync(id).isEmpty())
                // Native disclosure can append security-audit deltas; user commit inventory and all objects must remain identical.
            } finally {
                database.localMdbxDatabaseDao().deleteDatabaseById(id)
                assertTrue(repository.deleteOwnedVaultFile(file))
            }
        }
    }

    @Test fun bitwardenActualNativeSyncPreservesFiveTypesAndTypedAbcCoverage(): Unit = runBlocking {
        bitwardenScenario {
            documents += cipher("A", 1, fieldValues = listOf(1, 2, 3))
            documents += cipher("B", 1, fieldValues = listOf(1, 2))
            documents += cipher("C", 1, fieldValues = listOf(2, 3, 4))
            (2..5).forEach { documents += cipher("type-$it", it) }
            val original = documents.toList()
            val captured = adapter.capture(true) {}
            assertEquals(7, captured.projects.size)
            assertTrue(captured.projects.all { it.blockers.isEmpty() })
            val contextNodes = captured.projects.map { project -> requireNotNull(project.content).children.single { it.kind == "bitwarden-context" } }
            val nativeDocuments = contextNodes.map { Json.parseToJsonElement(it.attributes.getValue("json")).jsonObject }
            assertEquals((1..5).toSet(), nativeDocuments.map { it.getValue("type").jsonPrimitive.int }.toSet())
            val login = nativeDocuments.first { it.getValue("type").jsonPrimitive.int == 1 }.getValue("login").jsonObject
            assertEquals("synthetic-second-factor", login.getValue("totp").jsonPrimitive.content)
            assertEquals(listOf(0, 3), login.getValue("uris").jsonArray.map { it.jsonObject.getValue("match").jsonPrimitive.int })
            assertEquals(listOf("https://example.invalid/account", "https://example.invalid/exact"),
                login.getValue("uris").jsonArray.map { it.jsonObject.getValue("uri").jsonPrimitive.content })
            assertEquals("synthetic-old-password", nativeDocuments.first().getValue("passwordHistory").jsonArray.single()
                .jsonObject.getValue("password").jsonPrimitive.content)
            val a = captured.projects.single { it.summary.key.endsWith(":A") }
            val fields = requireNotNull(a.content).children.filter { it.kind == "bitwarden-field" }
                .map { Json.parseToJsonElement(it.attributes.getValue("json")).jsonObject }
            assertEquals(2, fields.count { it.getValue("name").jsonPrimitive.content == "Repeated" })
            assertEquals(1, fields.single { it.getValue("name").jsonPrimitive.content == "Hidden" }.getValue("type").jsonPrimitive.int)
            assertEquals("synthetic-hidden-value", fields.single { it.getValue("name").jsonPrimitive.content == "Hidden" }.getValue("value").jsonPrimitive.content)
            val analysis = SingleVaultDedupComparator.analyze(captured.projects)
            assertEquals(1, analysis.candidates.size)
            assertEquals("${adapter.source.key}:B", analysis.candidates.single().candidateKey)
            assertEquals("${adapter.source.key}:A", analysis.candidates.single().keeperKey)
            assertAnalysisOnlyAndNoWrites(captured, analysis.candidates)
            assertEquals(original, documents.toList())
            assertEquals(1, requests.count { it.first == "GET" && it.second == "/sync" })
        }
    }

    @Test fun bitwardenActualNativeSyncRetainsLinkedMetadataAttachmentsAndUnknownContent(): Unit = runBlocking {
        bitwardenScenario {
            documents += cipher("linked", 1, extraFields = listOf(field("Linked", null, 3, 100)))
            documents += cipher("credential", 1, extraFields = listOf(field("monica.content.credential",
                """{"version":1,"projectId":"synthetic-project","groupId":"synthetic-account","passwordId":"synthetic-password","primary":true}""")))
            documents += cipher("block", 1, extraFields = listOf(field("monica.content.block",
                """{"version":1,"type":"bank_card","number":"4242424242424242","future":"retained"}""")))
            documents += cipher("attachment", 1, extras = mapOf("attachments" to buildJsonArray { add(buildJsonObject {
                put("id", "synthetic-attachment"); put("fileName", enc("synthetic.bin")); put("size", "7")
                put("url", "https://example.invalid/never-requested")
            }) }))
            documents += cipher("unknown", 1, extras = mapOf("futureNativeEnvelope" to buildJsonObject {
                put("unknown", enc("preserved future field")); put("largeNumber", JsonPrimitive(9007199254740993L))
            }))
            documents += cipher("partial", 1, extras = mapOf("partialData" to buildJsonObject { put("opaque", "retain") }))
            documents += cipher("unreadable", 1, extraFields = listOf(buildJsonObject {
                put("name", enc("Unreadable")); put("value", "broken-synthetic-ciphertext"); put("type", 1)
            }))
            documents += cipher("fido", 1).let { raw -> JsonObject(raw + ("login" to JsonObject(raw.getValue("login").jsonObject +
                ("fido2Credentials" to buildJsonArray { add(buildJsonObject { put("credentialId", enc("synthetic-fido-id")) }) })))) }
            val original = documents.toList()
            val captured = adapter.capture(true) {}
            assertEquals(8, captured.projects.size)
            fun project(id: String) = captured.projects.single { it.summary.key.endsWith(":$id") }
            assertTrue(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE in project("linked").blockers)
            listOf("credential", "block").forEach { assertTrue(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT in project(it).blockers) }
            assertTrue(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT in project("attachment").blockers)
            listOf("unknown", "partial", "fido").forEach { assertTrue(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT in project(it).blockers) }
            assertTrue(SingleVaultDedupBlockReason.PASSKEY_PRESERVED in project("fido").blockers)
            assertTrue(SingleVaultDedupBlockReason.UNREADABLE_CONTENT in project("unreadable").blockers)
            assertTrue(flatten(requireNotNull(project("unknown").content)).any { it.attributes["json"]?.contains("futureNativeEnvelope") == true })
            assertTrue(flatten(requireNotNull(project("attachment").content)).any { it.attributes["json"]?.contains("synthetic-attachment") == true })
            assertTrue(SingleVaultDedupComparator.analyze(captured.projects).candidates.isEmpty())
            assertAnalysisOnlyAndNoWrites(captured, emptyList())
            assertEquals(original, documents.toList())
            assertTrue(requests.all { it.second == "/sync" }) // No attachment fetch or cipher write endpoint was used.
        }
    }

    private fun mdbxPayload(type: String, index: Int, project: String) = buildJsonObject {
        put("username", "synthetic-user"); put("password_plain", "synthetic-password-$index")
        put("website", "https://example.invalid/$index"); put("notes", "Synthetic notes\nwith whitespace")
        put("is_archived", false); put("entry_type", type)
        if (index < 2) put("password_group_id", project)
        put("custom_fields", buildJsonArray {
            listOf(false, true, false).forEachIndexed { number, hidden -> add(buildJsonObject {
                put("title", if (number == 1) "Hidden" else "Repeated"); put("value", "synthetic-field-$number")
                put("is_protected", hidden)
            }) }
            add(buildJsonObject { put("title", "monica.content.credential"); put("value", """{"version":1,"groupId":"synthetic-account","passwordId":"password-$index","projectId":"$project"}""") })
            listOf("note", "bank_card", "document", "address", "totp").forEach { kind -> add(buildJsonObject {
                put("title", "monica.content.block"); put("value", buildJsonObject { put("type", kind); put("value", "synthetic-$kind"); put("hidden", true) }.toString())
            }) }
        })
        put("future_extension", buildJsonObject { put("number", JsonPrimitive(9007199254740993L)); put("list", buildJsonArray { add(JsonNull); add(false); add("preserve") }) })
    }

    private suspend fun bitwardenScenario(block: suspend BitwardenFixture.() -> Unit) = withSyntheticSession {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val fixture = BitwardenFixture(server)
        try { fixture.initialize(); fixture.block() } finally { fixture.close(); server.shutdown() }
    }

    private inner class BitwardenFixture(private val server: MockWebServer) {
        val repository = BitwardenRepository(context)
        val key = SymmetricCryptoKey(ByteArray(32) { 9 }, ByteArray(32) { 7 })
        val documents = CopyOnWriteArrayList<JsonObject>()
        val requests = CopyOnWriteArrayList<Pair<String?, String>>()
        private val account = UUID.randomUUID().toString()
        private val token = "synthetic-dedup-$account"
        private var vaultId: Long? = null
        lateinit var adapter: SingleVaultDedupBitwardenAdapter

        suspend fun initialize() {
            val endpoint = server.url("/").toString()
            val id = database.bitwardenVaultDao().insert(BitwardenVault(email = "$account@example.invalid",
                userId = account, accountKey = account, canonicalEmail = "$account@example.invalid",
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint, isConnected = true, isLocked = false,
                revisionDate = "synthetic-stamp", accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000))
            vaultId = id
            cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
            cache<String>("accessTokenCache")[id] = token
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    requests += request.method to path
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    if (path != "/sync") return MockResponse().setResponseCode(404)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                        put("profile", buildJsonObject { put("id", account); put("email", "$account@example.invalid"); put("securityStamp", "synthetic-stamp") })
                        put("ciphers", JsonArray(documents.toList())); put("folders", JsonArray(emptyList())); put("sends", JsonArray(emptyList()))
                    }.toString())
                }
            }
            adapter = SingleVaultDedupBitwardenAdapter(repository, id, "Synthetic HTTP source")
        }

        fun enc(value: String) = BitwardenCrypto.encryptString(value, key)
        fun field(name: String, value: String?, type: Int = 0, linkedId: Int? = null) = buildJsonObject {
            put("name", enc(name)); put("value", value?.let { JsonPrimitive(enc(it)) } ?: JsonNull); put("type", type)
            linkedId?.let { put("linkedId", it) }
        }

        fun cipher(id: String, type: Int, fieldValues: List<Int> = listOf(1), extraFields: List<JsonObject> = emptyList(),
            extras: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
            put("id", id); put("type", type); put("name", enc(if (type == 1) "Synthetic ABC project" else "Synthetic type $type"))
            put("notes", enc("Synthetic native notes")); put("revisionDate", "2026-11-06T00:00:00Z")
            put("favorite", false); put("reprompt", 0)
            put("fields", JsonArray(listOf(field("Hidden", "synthetic-hidden-value", 1), field("Enabled", "true", 2),
                field("Repeated", "same"), field("Repeated", "same")) + fieldValues.map { field("Field", it.toString()) } + extraFields))
            put("passwordHistory", buildJsonArray { add(buildJsonObject { put("password", enc("synthetic-old-password")); put("lastUsedDate", "2026-11-01T00:00:00Z") }) })
            when (type) {
                1 -> put("login", buildJsonObject {
                    put("username", enc("synthetic-user")); put("password", enc("synthetic-password")); put("totp", enc("synthetic-second-factor"))
                    put("uris", buildJsonArray { listOf(0 to "account", 3 to "exact").forEach { (match, path) -> add(buildJsonObject {
                        put("uri", enc("https://example.invalid/$path")); put("match", match)
                    }) } })
                })
                2 -> put("secureNote", buildJsonObject { put("type", 0) })
                3 -> put("card", buildJsonObject { put("cardholderName", enc("Synthetic")); put("number", enc("4242424242424242")); put("brand", enc("Visa")); put("expMonth", enc("12")); put("expYear", enc("2030")); put("code", enc("123")) })
                4 -> put("identity", buildJsonObject { listOf("firstName", "lastName", "address1", "address2", "city", "country", "email", "phone", "passportNumber").forEach { put(it, enc("synthetic-$it")) } })
                5 -> put("sshKey", buildJsonObject { put("privateKey", enc("synthetic-private-key")); put("publicKey", enc("synthetic-public-key")); put("keyFingerprint", enc("synthetic-fingerprint")) })
            }
            extras.forEach { (name, value) -> put(name, value) }
        }

        suspend fun assertAnalysisOnlyAndNoWrites(captured: SingleVaultDedupCapture, candidates: List<SingleVaultDedupCandidate>) {
            assertFalse(adapter.source.canClean)
            assertEquals(SingleVaultDedupUiIssue.ANALYSIS_ONLY, adapter.source.issue)
            assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED, adapter.clean(captured, candidates, {}).status)
            assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED, adapter.restore(unwrittenRecovery(adapter.source.key), {}).status)
            assertEquals(0, requests.count { it.first != "GET" })
            assertTrue(database.passwordEntryDao().getAllPasswordEntriesSync().none { it.bitwardenVaultId == vaultId })
            assertTrue(database.secureItemDao().getByBitwardenVaultId(requireNotNull(vaultId)).isEmpty())
            assertTrue(database.passkeyDao().getByBitwardenVaultId(requireNotNull(vaultId)).isEmpty())
            documents.forEach { document -> assertNull(database.bitwardenSyncRawEntryRecordDao().getLatestByCipher(
                requireNotNull(vaultId), document.getValue("id").jsonPrimitive.content)) }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String) = repository.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(repository) as MutableMap<Long, T>

        suspend fun close() {
            vaultId?.let { id ->
                cache<SymmetricCryptoKey>("symmetricKeyCache").remove(id)
                cache<String>("accessTokenCache").remove(id)
                database.passwordEntryDao().deleteAllByBitwardenVaultId(id)
                database.secureItemDao().deleteAllByBitwardenVaultId(id)
                database.passkeyDao().deleteAllByBitwardenVaultId(id)
                database.bitwardenSyncRawEntryRecordDao().deleteByVault(id)
                database.bitwardenVaultDao().deleteById(id)
            }
            key.clear()
        }
    }

    private suspend fun withSyntheticSession(block: suspend () -> Unit) {
        val field = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val original = field.get(null)
        val unlocked = SessionManager.isUnlocked.value
        val synthetic = ByteArray(32) { 83 }
        SessionManager.markUnlocked(); field.set(null, synthetic)
        try { block() } finally {
            field.set(null, original); synthetic.fill(0)
            if (!unlocked) SessionManager.markLocked()
        }
    }

    private fun flatten(node: SingleVaultDedupNode): List<SingleVaultDedupNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun unwrittenRecovery(sourceKey: String) = SingleVaultDedupRecoveryEntity("never-persisted", sourceKey, 1L, 1, "unused", "PREPARED")
}
