package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher as HttpDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Actual Repository + Room + protected preferences; HTTP is restricted to the synthetic loopback
 * server. Run only in a dedicated Android test user with no unlocked Bitwarden accounts.
 */
@RunWith(AndroidJUnit4::class)
class BitwardenAccountManagementRepositoryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun firstManagementLoadDoesNotRequireSyncAndIgnoredHintCannotReportSuccess() = runBlocking {
        fixture { f ->
            val info = f.repository.loadAccountManagement(f.row().id).getOrThrow()
            assertEquals(BitwardenAccountManagementState.READY, info.state)
            assertTrue(info.hintPresent)
            assertEquals(INITIAL_HINT, info.hint)
            assertFalse(f.paths.contains(SYNC))
            f.ignoreHint = true
            val result = f.repository.mutateAccount(f.row().id, OLD_PASSWORD,
                BitwardenAccountMutationAction.HintProfile("ignored synthetic hint")).getOrThrow()
            assertEquals(BitwardenAccountManagementResult.UNCERTAIN, result)
            assertEquals(1, f.requests.count { it.method == "PUT" && it.requestUrl!!.encodedPath == PROFILE })
            assertEquals(INITIAL_HINT, f.hint)
            assertTrue(f.repository.isAccountMutationBlocked(f.row().id))
            val before = f.requests.size
            assertEquals(BitwardenAccountManagementState.REAUTHENTICATE,
                f.repository.loadAccountManagement(f.row().id).getOrThrow().state)
            assertEquals(before, f.requests.size)
            f.assertLocalUnchanged()
        }
    }

    @Test fun verifiedHintClearPreservesProfileNameAndDoesNotRewrapTheUserKey() = runBlocking {
        fixture { f ->
            val before = f.row()
            val originalWrapper = f.wrappedUserKey
            assertEquals(BitwardenAccountManagementResult.HINT_SAVED,
                f.repository.mutateAccount(before.id, OLD_PASSWORD, BitwardenAccountMutationAction.HintProfile(null)).getOrThrow())
            val write = f.requests.single { it.method == "PUT" && it.requestUrl!!.encodedPath == PROFILE }
            val body = json.parseToJsonElement(write.body.clone().readUtf8()).jsonObject
            assertEquals(setOf("name", "masterPasswordHint"), body.keys)
            assertEquals(PROFILE_NAME, body["name"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, body["masterPasswordHint"])
            assertEquals(originalWrapper, f.wrappedUserKey)
            assertEquals(before.encryptedEncKey, f.row().encryptedEncKey)
            assertFalse(f.repository.isAccountMutationBlocked(before.id))
            assertTrue(f.repository.isVaultUnlocked(before.id))
            assertNull(f.repository.loadAccountManagement(before.id).getOrThrow().hint)
            f.assertCachedUserKey()
            f.assertLocalUnchanged()
        }
    }

    @Test fun passwordChangeRewrapsTheSameUserKeyAndRecoveryKeepsLocalRowsAndQueues() = runBlocking {
        fixture { f ->
            val before = f.row()
            val oldContext = requireNotNull(f.repository.getAttachmentBitwardenContext(before, f.cipherId))
            try {
                assertEquals(BitwardenAccountManagementResult.REAUTHENTICATE,
                    f.repository.mutateAccount(before.id, OLD_PASSWORD, passwordAction()).getOrThrow())
                f.assertPasswordOrKdfBody(PASSWORD_PATH, BitwardenAccountMutationKdf(0, ITERATIONS), NEW_PASSWORD)
                f.assertPreservedLocalKeys(before)
                f.assertLocalUnchanged()
                f.assertFrozenWithoutHttp(oldContext)
                val beforeLocalUnlock = f.requests.size
                assertEquals(BitwardenRepository.UnlockResult.Success, f.repository.unlock(before.id, OLD_PASSWORD))
                assertEquals("Local recovery unlock must not contact the changed account", beforeLocalUnlock, f.requests.size)
                f.assertCachedUserKey()
                f.assertFrozenWithoutHttp(oldContext)
                val recovered = f.recover(NEW_PASSWORD)
                assertTrue(recovered is BitwardenRepository.RepositoryLoginResult.Success)
                assertEquals(before.id, f.row().id)
                assertEquals(before.transportRevision, f.row().transportRevision)
                assertEquals(before.transportSecretRef, f.row().transportSecretRef)
                assertFalse(f.repository.isAccountMutationBlocked(before.id))
                f.assertCachedUserKey()
                f.assertLocalUnchanged()
                assertEquals(1, f.mutationRequests(PASSWORD_PATH).size)
            } finally { oldContext.wrappingKey.clear() }
        }
    }

    @Test fun kdfChangeUsesNestedAuthenticationAndUnlockMetadataWithoutChangingUserKey() = runBlocking {
        fixture { f ->
            val before = f.row()
            f.nextPassword = OLD_PASSWORD
            val next = BitwardenAccountMutationKdf(0, ITERATIONS + 1)
            assertEquals(BitwardenAccountManagementResult.REAUTHENTICATE,
                f.repository.mutateAccount(before.id, OLD_PASSWORD, BitwardenAccountMutationAction.Kdf(next)).getOrThrow())
            f.assertPasswordOrKdfBody(KDF_PATH, next, OLD_PASSWORD)
            f.assertPreservedLocalKeys(before)
            f.assertLocalUnchanged()
            assertTrue(f.recover(OLD_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(ITERATIONS + 1, f.row().kdfIterations)
            f.assertCachedUserKey()
            f.assertLocalUnchanged()
            assertEquals(1, f.mutationRequests(KDF_PATH).size)
        }
    }

    @Test fun argon2ChangeReauthenticatesWithTheSameExistingUserKey() = runBlocking {
        fixture { f ->
            val before = f.row()
            f.nextPassword = OLD_PASSWORD
            val next = BitwardenAccountMutationKdf(1, 2, 16, 1)
            assertEquals(BitwardenAccountManagementResult.REAUTHENTICATE,
                f.repository.mutateAccount(before.id, OLD_PASSWORD, BitwardenAccountMutationAction.Kdf(next)).getOrThrow())
            f.assertPasswordOrKdfBody(KDF_PATH, next, OLD_PASSWORD)
            f.assertPreservedLocalKeys(before)
            assertTrue(f.recover(OLD_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(1, f.row().kdfType)
            assertEquals(16, f.row().kdfMemory)
            f.assertCachedUserKey(); f.assertLocalUnchanged()
            assertEquals(1, f.mutationRequests(KDF_PATH).size)
        }
    }

    @Test fun lostMutationResponseStaysFrozenAndNeverRepeatsThePasswordRequest() = runBlocking {
        fixture { f ->
            val before = f.row()
            f.loseMutationResponse = true
            assertEquals(BitwardenAccountManagementResult.UNCERTAIN,
                f.repository.mutateAccount(before.id, OLD_PASSWORD, passwordAction()).getOrThrow())
            assertEquals(1, f.mutationRequests(PASSWORD_PATH).size)
            val networkCount = f.requests.size
            assertTrue(f.repository.mutateAccount(before.id, OLD_PASSWORD, passwordAction()).isFailure)
            assertEquals(networkCount, f.requests.size)
            f.assertPreservedLocalKeys(before)
            f.assertLocalUnchanged()
            assertTrue(f.recover(NEW_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(1, f.mutationRequests(PASSWORD_PATH).size)
            f.assertCachedUserKey()
            f.assertLocalUnchanged()
        }
    }

    @Test fun serviceUnavailableRetryAfterZeroDoesNotReplayTheAccountMutation() = runBlocking {
        fixture { f ->
            val original = f.row()
            f.firstMutationUnavailable = true
            assertEquals(BitwardenAccountManagementResult.UNCERTAIN,
                f.repository.mutateAccount(original.id, OLD_PASSWORD, passwordAction()).getOrThrow())
            assertEquals("A 503 Retry-After:0 must not replay an account mutation", 1,
                f.mutationRequests(PASSWORD_PATH).size)
            assertTrue(f.repository.isAccountMutationBlocked(original.id))
            f.assertPreservedLocalKeys(original)
            f.assertLocalUnchanged()
            // This server did not apply the first request, so fresh authentication must
            // reconcile the original wrapper without retrying the mutation itself.
            assertTrue(f.recover(OLD_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertFalse(f.repository.isAccountMutationBlocked(original.id))
            assertEquals(1, f.mutationRequests(PASSWORD_PATH).size)
            f.assertCachedUserKey()
            f.assertLocalUnchanged()
        }
    }

    @Test fun failedRecoveryJournalCleanupKeepsTheOldWrapperRetryableAfterSqlCommit() = runBlocking {
        fixture { f ->
            val original = f.row()
            f.firstMutationUnavailable = true
            assertEquals(BitwardenAccountManagementResult.UNCERTAIN,
                f.repository.mutateAccount(original.id, OLD_PASSWORD, passwordAction()).getOrThrow())
            assertNull(f.row().encryptedAccessToken)
            val originalJournal = f.journalCiphertext()
            f.failNextJournalCleanup()
            assertFalse(f.recover(OLD_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(1, f.failedJournalCleanupCount)
            val afterSqlCommit = f.row()
            assertNotNull("Fresh credentials were committed before journal acknowledgement", afterSqlCommit.encryptedAccessToken)
            assertEquals(original.transportSecretRef, afterSqlCommit.transportSecretRef)
            assertEquals(original.transportRevision, afterSqlCommit.transportRevision)
            assertEquals("Failed cleanup must not relabel the original uncertain request as confirmed",
                originalJournal, f.journalCiphertext())
            assertTrue(f.repository.isAccountMutationBlocked(original.id))
            f.assertLocalUnchanged()
            assertTrue(f.recover(OLD_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertFalse(f.repository.isAccountMutationBlocked(original.id))
            assertEquals(original.id, f.row().id)
            assertEquals(1, f.mutationRequests(PASSWORD_PATH).size)
            f.assertCachedUserKey()
            f.assertLocalUnchanged()
        }
    }

    @Test fun sameEmailWithDifferentServerIdentityOrUserKeyCannotReplaceThePreservedAccount() = runBlocking {
        fixture { f ->
            val id = f.row().id
            assertEquals(BitwardenAccountManagementResult.REAUTHENTICATE,
                f.repository.mutateAccount(id, OLD_PASSWORD, passwordAction()).getOrThrow())
            val preserved = f.row()
            f.returnedUserId = UUID.randomUUID().toString()
            assertFalse(f.recover(NEW_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(preserved, f.row())
            assertTrue(f.repository.isAccountMutationBlocked(id))
            f.assertLocalUnchanged()
            f.returnedUserId = null
            f.wrongWrapper = f.createWrongUserKeyWrapper()
            assertFalse(f.recover(NEW_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(preserved, f.row())
            assertTrue(f.repository.isAccountMutationBlocked(id))
            f.assertLocalUnchanged()
            f.wrongWrapper = null
            assertTrue(f.recover(NEW_PASSWORD) is BitwardenRepository.RepositoryLoginResult.Success)
            assertEquals(id, f.row().id)
            f.assertCachedUserKey()
            f.assertLocalUnchanged()
            assertEquals(1, f.mutationRequests(PASSWORD_PATH).size)
        }
    }

    @Test fun confirmedDeleteKeepsAnOriginalPasswordLocalCopyWithNoAccountTraffic() = runBlocking {
        fixture { f ->
            val before = f.row()
            val oldContext = requireNotNull(f.repository.getAttachmentBitwardenContext(before, f.cipherId))
            try {
                assertEquals(BitwardenAccountManagementResult.DELETED_LOCAL_COPY,
                    f.repository.mutateAccount(before.id, OLD_PASSWORD, BitwardenAccountMutationAction.Delete).getOrThrow())
                val deletion = f.mutationRequests(ACCOUNTS).single()
                assertEquals("DELETE", deletion.method)
                val body = json.parseToJsonElement(deletion.body.clone().readUtf8()).jsonObject
                assertEquals(setOf("MasterPasswordHash", "otp"), body.keys)
                assertEquals(f.initialPasswordHash, body["MasterPasswordHash"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, body["otp"])
                f.assertPreservedLocalKeys(before)
                f.assertLocalUnchanged()
                f.assertFrozenWithoutHttp(oldContext)
                val beforeLocalUnlock = f.requests.size
                assertEquals(BitwardenRepository.UnlockResult.Success, f.repository.unlock(before.id, OLD_PASSWORD))
                assertEquals("Deleted account unlock must remain local", beforeLocalUnlock, f.requests.size)
                assertTrue(f.repository.isVaultUnlocked(before.id))
                f.assertCachedUserKey()
                val networkCount = f.requests.size
                assertEquals(BitwardenAccountManagementState.DELETED_LOCAL_COPY,
                    f.repository.loadAccountManagement(before.id).getOrThrow().state)
                f.assertFrozenWithoutHttp(oldContext)
                assertEquals(networkCount, f.requests.size)
                f.assertLocalUnchanged()
            } finally { oldContext.wrappingKey.clear() }
        }
    }

    private fun passwordAction() = BitwardenAccountMutationAction.Password(NEW_PASSWORD, NEW_PASSWORD,
        BitwardenAccountMutationHint.Preserve)

    private suspend fun fixture(block: suspend (Fixture) -> Unit) = withContext(Dispatchers.IO) {
        val fixture = Fixture()
        var failure: Throwable? = null
        try { fixture.initialize(); block(fixture) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { fixture.close() }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private inner class Fixture {
        val repository = BitwardenRepository(context)
        private val singleton = BitwardenRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private var previousRepository: BitwardenRepository? = null
        val database = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val userId = UUID.randomUUID().toString()
        val email = "account-management-$userId@example.invalid"
        val cipherId = UUID.randomUUID().toString()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val paths get() = requests.map { it.requestUrl!!.encodedPath }
        private val server = MockWebServer()
        private val userKey = SymmetricCryptoKey(ByteArray(32) { 37 }, ByteArray(32) { 73 })
        private val originalUnlocked = SessionManager.isUnlocked.value
        private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        private val originalMdk = mdkField.get(null) as? ByteArray
        private val settings = RecoverableBitwardenSettings.open(context)
        private val oldSettings = listOf("active_vault_id", "last_sync_time").associateWith { name ->
            settings.contains(name) to settings.getLong(name, 0)
        }
        private val protectedRefs = linkedSetOf<String>()
        private val protectedStore = BitwardenTransportSecretStore(
            read = security::getProtectedString,
            write = { ref, value -> protectedRefs += ref; security.putProtectedString(ref, value) },
            remove = security::removeProtectedString)
        private val mutationPrefs get() = context.getSharedPreferences("bitwarden_account_mutations", 0)
        private var started = false
        private var initialized = false
        lateinit var endpoint: String
        lateinit var environment: BitwardenEnvironment
        lateinit var wrappedUserKey: String
        lateinit var initialPasswordHash: String
        private lateinit var passwordHash: String
        private var kdf = BitwardenAccountMutationKdf(0, ITERATIONS)
        @Volatile var nextPassword = NEW_PASSWORD
        private var serverPassword = OLD_PASSWORD
        private var stamp = "synthetic-initial-$userId"
        @Volatile var hint: String? = INITIAL_HINT
        @Volatile var ignoreHint = false
        @Volatile var loseMutationResponse = false
        @Volatile var firstMutationUnavailable = false
        @Volatile var returnedUserId: String? = null
        @Volatile var wrongWrapper: String? = null
        private var deleted = false
        private var serial = 0
        var failedJournalCleanupCount = 0
            private set
        private var passwordId: Long? = null
        private var operationId: Long? = null
        private var baselinePassword: PasswordEntry? = null
        private var baselineOperation: BitwardenPendingOperation? = null
        private var baselineAttachment: Attachment? = null
        private var baselineCiphertext: ByteArray? = null
        private var baselinePendingIds: Set<Long> = emptySet()
        private val attachmentBytes = "Synthetic encrypted account-management attachment; preserve on error".toByteArray()

        suspend fun initialize() {
            check(BitwardenRepository.getInstance(context).getAllVaults().none {
                BitwardenRepository.getInstance(context).isVaultUnlocked(it.id)
            }) { "Use a dedicated synthetic Android user without unlocked accounts" }
            previousRepository = singleton.get(null) as BitwardenRepository
            singleton.set(null, repository)
            initialized = true
            BitwardenRepository::class.java.getDeclaredField("transportSecrets\$delegate").apply { isAccessible = true }
                .set(repository, lazyOf(protectedStore))
            instrumentation.runOnMainSync { SessionManager.markUnlocked() }
            mdkField.set(null, ByteArray(32) { 103 })
            val master = derive(OLD_PASSWORD, kdf)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            try {
                passwordHash = BitwardenCrypto.deriveMasterPasswordHash(master, OLD_PASSWORD)
                initialPasswordHash = passwordHash
                wrappedUserKey = BitwardenCrypto.encrypt(userKey.encKey + userKey.macKey, stretched)
            } finally { master.fill(0); stretched.clear() }
            server.dispatcher = object : HttpDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@Fixture) {
                    requests += request
                    val path = request.requestUrl!!.encodedPath
                    when {
                        path == PRELOGIN -> ok(buildJsonObject {
                            put("kdf", kdf.type); put("kdfIterations", kdf.iterations)
                            kdf.memoryMiB?.let { put("kdfMemory", it) }; kdf.parallelism?.let { put("kdfParallelism", it) }
                        })
                        path == TOKEN -> token(request)
                        path == PROFILE && request.method == "GET" -> { checkBearer(request); ok(profile()) }
                        path == PROFILE && request.method == "PUT" -> {
                            checkBearer(request)
                            val body = requestJson(request)
                            check(body["name"]?.jsonPrimitive?.content == PROFILE_NAME)
                            if (!ignoreHint) hint = body["masterPasswordHint"]?.jsonPrimitive?.contentOrNull
                            MockResponse().setResponseCode(200)
                        }
                        path in setOf(PASSWORD_PATH, KDF_PATH) && request.method == "POST" -> {
                            if (firstMutationUnavailable && mutationRequests(path).size == 1) {
                                checkBearer(request)
                                MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                            } else change(request, path)
                        }
                        path == ACCOUNTS && request.method == "DELETE" -> {
                            checkBearer(request)
                            check(requestJson(request)["MasterPasswordHash"]!!.jsonPrimitive.content == passwordHash)
                            deleted = true
                            MockResponse().setResponseCode(200)
                        }
                        path == SYNC -> ok(buildJsonObject {
                            put("profile", profile()); put("ciphers", JsonArray(emptyList()))
                            put("folders", JsonArray(emptyList())); put("sends", JsonArray(emptyList()))
                        })
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            started = true
            endpoint = server.url("/").toString().trimEnd('/')
            environment = requireNotNull(BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(
                BitwardenEnvironmentPreset.CUSTOM, endpoint, apiUrl = "$endpoint/api/",
                identityUrl = "$endpoint/identity/", webUrl = "$endpoint/web/",
                notificationsUrl = "$endpoint/notifications/"), UUID.randomUUID().toString()).value)
            assertTrue(repository.login(endpoint, email, OLD_PASSWORD, environment = environment)
                is BitwardenRepository.RepositoryLoginResult.Success)
            // Establish the account-management snapshot through production, before any sync/manual identity write.
            assertEquals(BitwardenAccountManagementState.READY, repository.loadAccountManagement(row().id).getOrThrow().state)
            seedLocalRows()
        }

        private fun profile() = buildJsonObject {
            put("id", returnedUserId ?: userId); put("email", email); put("name", PROFILE_NAME)
            put("securityStamp", stamp); put("key", wrongWrapper ?: wrappedUserKey)
            put("masterPasswordHint", hint?.let(::JsonPrimitive) ?: JsonNull)
            put("organizations", JsonArray(emptyList())); put("usesKeyConnector", false)
            put("serverUnknownSetting", "synthetic-preserved-by-server")
        }
        private fun checkBearer(request: RecordedRequest) = check(request.getHeader("Authorization")?.startsWith("Bearer synthetic-account-") == true)
        private fun requestJson(request: RecordedRequest) = json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        private fun ok(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
        private fun token(request: RecordedRequest): MockResponse {
            val form = request.body.clone().readUtf8().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            if (deleted || (form["grant_type"] == "password" && (form["username"] != email || form["password"] != passwordHash))) {
                return MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("{\"error\":\"invalid_grant\"}")
            }
            return ok(buildJsonObject {
                put("access_token", "synthetic-account-$userId-${++serial}"); put("refresh_token", "synthetic-refresh-$userId")
                put("expires_in", 3600); put("Key", wrongWrapper ?: wrappedUserKey)
            })
        }
        private fun change(request: RecordedRequest, path: String): MockResponse {
            checkBearer(request)
            val body = requestJson(request)
            check(body["masterPasswordHash"]!!.jsonPrimitive.content == passwordHash)
            val authentication = body["authenticationData"]!!.jsonObject
            val unlock = body["unlockData"]!!.jsonObject
            check(authentication["salt"]!!.jsonPrimitive.content == email && unlock["salt"]!!.jsonPrimitive.content == email)
            val meta = authentication["kdf"]!!.jsonObject
            check(meta == unlock["kdf"])
            val next = BitwardenAccountMutationKdf(meta["kdfType"]!!.jsonPrimitive.int, meta["iterations"]!!.jsonPrimitive.int,
                meta["memory"]?.jsonPrimitive?.intOrNull, meta["parallelism"]?.jsonPrimitive?.intOrNull)
            val newHash = body["newMasterPasswordHash"]!!.jsonPrimitive.content
            check(authentication["masterPasswordAuthenticationHash"]!!.jsonPrimitive.content == newHash)
            val wrapper = body["key"]!!.jsonPrimitive.content
            check(unlock["masterKeyWrappedUserKey"]!!.jsonPrimitive.content == wrapper)
            val master = derive(nextPassword, next)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            try {
                check(BitwardenCrypto.deriveMasterPasswordHash(master, nextPassword) == newHash)
                val bytes = BitwardenCrypto.decrypt(wrapper, stretched)
                try { check(bytes.contentEquals(userKey.encKey + userKey.macKey)) } finally { bytes.fill(0) }
            } finally { master.fill(0); stretched.clear() }
            if (path == PASSWORD_PATH) hint = body["masterPasswordHint"]?.jsonPrimitive?.contentOrNull
            kdf = next; passwordHash = newHash; wrappedUserKey = wrapper; serverPassword = nextPassword
            stamp = "synthetic-mutated-$userId"
            return if (loseMutationResponse) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else MockResponse().setResponseCode(200)
        }

        suspend fun row() = database.bitwardenVaultDao().getAllVaults().single { it.email == email }
        suspend fun journalCiphertext(): String = requireNotNull(
            mutationPrefs.getString("bitwarden_account_mutation_v1_${row().id}", null))

        fun failNextJournalCleanup() {
            val failOnce = AtomicBoolean(true)
            // Inject only one failed durable remove. Reads, writes and encryption still use
            // the real production journal store, SharedPreferences and SecurityManager.
            val store = BitwardenAccountMutationIntentStore(
                read = { mutationPrefs.getString(it, null) },
                write = { key, value -> mutationPrefs.edit().putString(key, value).commit() },
                remove = { key ->
                    if (failOnce.compareAndSet(true, false)) { failedJournalCleanupCount++; false }
                    else mutationPrefs.edit().remove(key).commit()
                },
                seal = { key, plain -> security.encryptData(key + "\n" + plain) },
                open = { key, sealed -> security.decryptData(sealed).let { plain ->
                    check(plain.startsWith(key + "\n")); plain.removePrefix(key + "\n")
                } },
            )
            BitwardenRepository::class.java.getDeclaredField("accountMutations\$delegate").apply { isAccessible = true }
                .set(repository, lazyOf(store))
        }
        suspend fun recover(password: String): BitwardenRepository.RepositoryLoginResult {
            val original = row()
            val draft = repository.loadAccountRecoveryEnvironment(original.id)
            val candidate = requireNotNull(BitwardenEnvironmentResolver.resolve(draft, UUID.randomUUID().toString()).value)
            return repository.login(endpoint, email, password, environment = candidate,
                reauthentication = BitwardenRepository.ReauthenticationContext(original.id,
                    repository.getVaultSessionGeneration(original.id), SessionManager.lockGeneration.value))
        }
        fun mutationRequests(path: String) = requests.filter { it.requestUrl!!.encodedPath == path && it.method in setOf("POST", "DELETE") }
        suspend fun assertFrozenWithoutHttp(old: AttachmentFacade.BitwardenContext) {
            val count = requests.size
            assertTrue(repository.isAccountMutationBlocked(row().id))
            assertTrue(runCatching { old.requireCurrent() }.isFailure)
            // Call the captured API directly: an outer guard failure alone cannot prove that
            // a stale API object refuses network traffic after the mutation journal is durable.
            val staleCall = runCatching { old.vaultApi.getCipher("Bearer ${old.accessToken}", cipherId) }
            staleCall.getOrNull()?.errorBody()?.close()
            assertTrue("A captured API must reject the obsolete account session",
                staleCall.isFailure || !staleCall.getOrThrow().isSuccessful)
            assertTrue(repository.getAccountDevices(row().id).isFailure)
            assertTrue(repository.sync(row().id) is BitwardenRepository.SyncResult.Error)
            assertEquals("A frozen account must not send even a profile/refresh request", count, requests.size)
        }
        suspend fun assertPreservedLocalKeys(original: BitwardenVault) {
            val current = row()
            assertEquals(original.encryptedMasterKey, current.encryptedMasterKey)
            assertEquals(original.encryptedEncKey, current.encryptedEncKey)
            assertEquals(original.encryptedMacKey, current.encryptedMacKey)
            assertEquals(original.kdfType, current.kdfType); assertEquals(original.kdfIterations, current.kdfIterations)
            assertNull(current.encryptedAccessToken); assertNull(current.encryptedRefreshToken)
            val stored = requireNotNull(mutationPrefs.getString("bitwarden_account_mutation_v1_${original.id}", null))
            assertTrue(stored.startsWith("MDK|"))
            assertFalse(stored.contains(OLD_PASSWORD)); assertFalse(stored.contains(NEW_PASSWORD))
        }
        suspend fun assertCachedUserKey() {
            val key = requireNotNull(repository.getCachedSymmetricKey(row().id))
            try { assertArrayEquals(userKey.encKey, key.encKey); assertArrayEquals(userKey.macKey, key.macKey) }
            finally { key.clear() }
        }
        fun assertPasswordOrKdfBody(path: String, expectedKdf: BitwardenAccountMutationKdf, expectedPassword: String) {
            val request = mutationRequests(path).single()
            assertEquals("POST", request.method)
            val body = requestJson(request)
            val authentication = body["authenticationData"]!!.jsonObject
            val unlock = body["unlockData"]!!.jsonObject
            val commonFields = setOf("masterPasswordHash", "key", "newMasterPasswordHash", "authenticationData", "unlockData")
            assertEquals(if (path == KDF_PATH) commonFields else commonFields + "masterPasswordHint", body.keys)
            assertEquals(setOf("salt", "kdf", "masterPasswordAuthenticationHash"), authentication.keys)
            assertEquals(setOf("salt", "kdf", "masterKeyWrappedUserKey", "containedKeyId"), unlock.keys)
            val expectedMetadata = buildJsonObject {
                put("kdfType", expectedKdf.type); put("iterations", expectedKdf.iterations)
                put("memory", expectedKdf.memoryMiB?.let(::JsonPrimitive) ?: JsonNull)
                put("parallelism", expectedKdf.parallelism?.let(::JsonPrimitive) ?: JsonNull)
            }
            assertEquals(expectedMetadata, authentication["kdf"])
            assertEquals(authentication["kdf"], unlock["kdf"])
            assertEquals(email, authentication["salt"]!!.jsonPrimitive.content)
            assertEquals(email, unlock["salt"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, unlock["containedKeyId"])
            assertEquals(initialPasswordHash, body["masterPasswordHash"]!!.jsonPrimitive.content)
            assertEquals(body["key"], unlock["masterKeyWrappedUserKey"])
            assertEquals(body["newMasterPasswordHash"], authentication["masterPasswordAuthenticationHash"])
            if (path == KDF_PATH) assertFalse(body.containsKey("masterPasswordHint"))
            else assertEquals(INITIAL_HINT, body["masterPasswordHint"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains(OLD_PASSWORD)); assertFalse(body.toString().contains(NEW_PASSWORD))
            val master = derive(expectedPassword, expectedKdf)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            try {
                val unwrapped = BitwardenCrypto.decrypt(body["key"]!!.jsonPrimitive.content, stretched)
                try { assertArrayEquals(userKey.encKey + userKey.macKey, unwrapped) } finally { unwrapped.fill(0) }
            } finally { master.fill(0); stretched.clear() }
        }
        fun createWrongUserKeyWrapper(): String {
            val master = derive(serverPassword, kdf)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            try { return BitwardenCrypto.encrypt(ByteArray(64) { 11 }, stretched) }
            finally { master.fill(0); stretched.clear() }
        }
        private fun derive(password: String, kdf: BitwardenAccountMutationKdf): ByteArray = when (kdf.type) {
            0 -> BitwardenCrypto.deriveMasterKeyPbkdf2(password, email, kdf.iterations)
            else -> BitwardenCrypto.deriveMasterKeyArgon2(password, email, kdf.iterations, kdf.memoryMiB!!, kdf.parallelism!!)
        }
        private suspend fun seedLocalRows() {
            val id = row().id
            passwordId = database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "account-management-$userId",
                website = "https://synthetic.example.invalid", username = "synthetic", password = security.encryptData("synthetic local password"),
                bitwardenVaultId = id, bitwardenCipherId = cipherId, bitwardenLocalModified = true))
            operationId = database.bitwardenPendingOperationDao().insert(BitwardenPendingOperation(vaultId = id,
                entryId = passwordId, bitwardenCipherId = cipherId, operationType = "UPDATE", targetType = "CIPHER",
                payloadJson = "{\"syntheticPending\":true}"))
            val facade = AttachmentContainer.facade(context)
            val attachment = facade.addStreamAttachment(AttachmentFacade.StreamUploadRequest(
                AttachmentOwner.password(requireNotNull(passwordId)), AttachmentSource.LOCAL, "synthetic preserved attachment",
                "application/octet-stream", attachmentBytes.size.toLong(), { attachmentBytes.inputStream() }, true))
            baselineAttachment = attachment
            BitwardenImportAttachmentQueue(context).enqueueAttachment(id, attachment.id)
            baselinePassword = database.passwordEntryDao().getPasswordEntryById(requireNotNull(passwordId))
            baselineOperation = database.bitwardenPendingOperationDao().getById(requireNotNull(operationId))
            baselineAttachment = facade.getById(attachment.id)
            baselineCiphertext = File(context.filesDir, "secure_attachments/${attachment.localPath}").readBytes()
            baselinePendingIds = BitwardenImportAttachmentQueue(context).observePendingIds(id).first()
        }
        suspend fun assertLocalUnchanged() {
            assertEquals(baselinePassword, database.passwordEntryDao().getPasswordEntryById(requireNotNull(passwordId)))
            assertEquals(baselineOperation, database.bitwardenPendingOperationDao().getById(requireNotNull(operationId)))
            val original = requireNotNull(baselineAttachment)
            val facade = AttachmentContainer.facade(context)
            assertEquals(original, facade.getById(original.id))
            assertArrayEquals(baselineCiphertext, File(context.filesDir, "secure_attachments/${original.localPath}").readBytes())
            assertArrayEquals(attachmentBytes, facade.readAttachmentBytes(original.id, 4096))
            assertEquals(baselinePendingIds, BitwardenImportAttachmentQueue(context).observePendingIds(row().id).first())
        }
        suspend fun close() {
            try {
                if (initialized) {
                    // Only fixture-owned local rows/files and the explicit synthetic account are removed.
                    baselineAttachment?.let { AttachmentContainer.facade(context).forgetLocalAttachment(it.id) }
                    passwordId?.let { database.passwordEntryDao().deletePasswordEntryById(it) }
                    val rows = database.bitwardenVaultDao().getAllVaults().filter { it.email == email }
                    rows.forEach { owned ->
                        check(owned.serverUrl.trimEnd('/') == endpoint)
                        database.bitwardenPendingOperationDao().deleteByVault(owned.id)
                        context.getSharedPreferences("credential_import_attachments_v1", 0).edit().remove("vault:${owned.id}").commit()
                        check(repository.logout(owned.id))
                    }
                    protectedRefs.forEach { security.removeProtectedString(it) }
                }
            } finally {
                try { if (started) server.shutdown() }
                finally {
                    userKey.clear(); baselineCiphertext?.fill(0)
                    if (initialized) {
                        singleton.set(null, previousRepository)
                        val edit = settings.edit()
                        oldSettings.forEach { (name, old) -> if (old.first) edit.putLong(name, old.second) else edit.remove(name) }
                        check(edit.commit())
                        instrumentation.runOnMainSync { if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked() }
                        mdkField.set(null, originalMdk)
                    }
                }
            }
        }
    }

    private companion object {
        const val OLD_PASSWORD = "Synthetic account management current password 17!"
        const val NEW_PASSWORD = "Synthetic account management NEW password 85! distinct"
        const val ITERATIONS = 600_000
        const val INITIAL_HINT = "synthetic initial hint"
        const val PROFILE_NAME = "Synthetic account profile"
        const val PRELOGIN = "/identity/accounts/prelogin"
        const val TOKEN = "/identity/connect/token"
        const val PROFILE = "/api/accounts/profile"
        const val SYNC = "/api/sync"
        const val PASSWORD_PATH = "/api/accounts/password"
        const val KDF_PATH = "/api/accounts/kdf"
        const val ACCOUNTS = "/api/accounts"
    }
}
