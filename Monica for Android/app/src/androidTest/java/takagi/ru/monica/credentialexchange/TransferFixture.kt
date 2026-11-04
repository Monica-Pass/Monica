package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.BitwardenMutationSyncBridge
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.DataExportImportViewModel

/** Synthetic data only. Never clear the shared AVD app or remove a pre-existing database. */
internal class TransferFixture {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val prefix = "credential-transfer-${UUID.randomUUID()}"
    val root = File(context.filesDir, prefix).apply { mkdirs() }
    val db = PasswordDatabase.getDatabase(context)
    val security = SecurityManager(context)
    val mdbx = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security,
        passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(),
        customFieldDao = db.customFieldDao())
    val passwords = PasswordRepository(db.passwordEntryDao(), mdbxRepository = mdbx)
    val secureItems = SecureItemRepository(db.secureItemDao(), mdbxRepository = mdbx,
        decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
    val passkeys = PasskeyRepository(db.passkeyDao(), mdbx, context)
    val importer = TargetedImportCoordinator(context, passwords, secureItems)
    val model = DataExportImportViewModel(secureItems, passwords, context)
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val credentialId = CxfCredentialCodec.base64Url(UUID.randomUUID().toString().toByteArray())
    val userHandle = CxfCredentialCodec.base64Url(byteArrayOf(0, 1, 2, -1))
    val rawPassword = "  p;word:next,\"quoted\"\r\n换行🔑  "
    val rawUsername = "  user:with;delimiters,\"quoted\"  "
    val website = "https://login.example.invalid/path;param?q=a:b"
    val rpId = "login.example.invalid"
    private val unlockPassword = "Synthetic transfer fixture password"
    private val wasUnlocked = SessionManager.isUnlocked.value
    private val wasForeground = Mdbx2NativeReadSessions.isForeground
    private val keepassFiles = linkedMapOf<Long, File>()
    private val mdbxFiles = linkedMapOf<Long, File>()
    val vaultIds = mutableSetOf<Long>()
    val server = MockWebServer()
    val remote = Remote()
    val vaultKey = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 65).toByte() })
    val accessToken = "synthetic-transfer-test-token"

    init {
        Mdbx2NativeReadSessions.updateForeground(true)
        SessionManager.markUnlocked()
        server.dispatcher = remote
        BitwardenMutationSyncBridge.register(this) { it in vaultIds }
    }

    fun decoded(suffix: String = "exchange"): CxfCredentialCodec.Decoded {
        val material = checkNotNull(CxfPasskeyMaterial.decode(pair.private.encoded))
        val item = CxfCredentialCodec.Item("fixture", "$prefix-$suffix", listOf(website),
            listOf(CxfCredentialCodec.Login(rawUsername, rawPassword)),
            listOf(CxfCredentialCodec.Passkey(credentialId, rpId, rawUsername, "测试 Alice", userHandle,
                CxfCredentialCodec.base64Url(pair.private.encoded), material.algorithm, material.cosePublicKey)),
            createdAt = 1_700_000_000_000, notes = "Fixture notes\nZażółć")
        return CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(item), "Fixture", setOf("basic-auth", "passkey", "note")))
    }

    suspend fun keepass(): ImportDestination {
        val file = File(root, "${UUID.randomUUID()}.kdbx")
        val credentials = Credentials.from(EncryptedValue.fromString(unlockPassword))
        val created = KeePassDatabase.Ver4x.create("Root", Meta(generator = "Transfer fixture", name = prefix), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val fastFixture = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = seed)))
        file.outputStream().use { fastFixture.encode(it) }
        val id = db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(name = "$prefix-KDBX",
            filePath = file.relativeTo(context.filesDir).path, encryptedPassword = security.encryptData(unlockPassword)))
        keepassFiles[id] = file
        return ImportDestination(ImportDestinationKind.KEEPASS, id)
    }

    suspend fun mdbx(): ImportDestination {
        val file = mdbx.createInitializedVaultFile(MdbxTigaMode.SKY, unlockPassword)
        val id = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "$prefix-MDBX",
            filePath = file.absolutePath, storageLocation = MdbxStorageLocation.INTERNAL.name,
            sourceType = MdbxSourceType.LOCAL_INTERNAL.name, engineType = MdbxEngineType.RUST_MDBX2.name,
            encryptedPassword = security.encryptData(unlockPassword), unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
        mdbxFiles[id] = file
        return ImportDestination(ImportDestinationKind.MDBX, id)
    }

    suspend fun bitwarden(unlocked: Boolean = true): ImportDestination {
        val email = "${UUID.randomUUID()}@example.invalid"
        val remoteUserId = UUID.randomUUID().toString()
        remote.registerAccount(remoteUserId, email)
        fun stored(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
        fun storedBytes(value: ByteArray) = stored(Base64.getEncoder().encodeToString(value))
        val master = BitwardenCrypto.deriveMasterKeyPbkdf2(unlockPassword, email, 100)
        val endpoint = server.url("/").toString()
        val id = db.bitwardenVaultDao().insert(BitwardenVault(email = email, canonicalEmail = email, userId = remoteUserId,
            accountKey = "$prefix-${UUID.randomUUID()}", serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint,
            encryptedMasterKey = storedBytes(master), encryptedEncKey = storedBytes(vaultKey.encKey),
            encryptedMacKey = storedBytes(vaultKey.macKey), encryptedAccessToken = stored(accessToken),
            accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000, isConnected = true, kdfIterations = 100,
            syncEnabled = false, displayName = "$prefix-Bitwarden"))
        master.fill(0)
        vaultIds += id
        if (unlocked) {
            val result = BitwardenRepository.getInstance(context).unlock(id, unlockPassword)
            assertTrue("Synthetic vault must unlock without contacting the network", BitwardenRepository.getInstance(context).isVaultUnlocked(id))
        }
        return ImportDestination(ImportDestinationKind.BITWARDEN, id)
    }

    fun keepassEntries(id: Long): List<Entry> = keepassFiles.getValue(id).inputStream().use {
        fun collect(group: Group): List<Entry> = group.entries + group.groups.flatMap(::collect)
        collect(KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(unlockPassword))).content.group)
    }

    fun keepassFile(id: Long) = keepassFiles.getValue(id)

    suspend fun importedPasswords(destination: ImportDestination) = db.passwordEntryDao().getAllPasswordEntriesSync()
        .filter { destination.contains(it) && it.title.startsWith(prefix) }
    suspend fun importedKeys(destination: ImportDestination) = db.passkeyDao().getAllPasskeysSync()
        .filter { destination.contains(it) && it.credentialId == credentialId }

    fun assertOriginalKey(key: PasskeyEntry) {
        assertEquals(credentialId, key.credentialId)
        assertEquals(userHandle, key.userId)
        assertEquals(rpId, key.rpId)
        assertEquals(0L, key.signCount)
        assertKeySigns(PasskeyPrivateKeyStore.resolve(context, key.privateKeyAlias))
    }

    fun assertKeySigns(material: String?) {
        val decoded = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(material))
        val challenge = "Real Android signature using a migrated test key".toByteArray()
        val signature = PasskeyPrivateKeySupport.createSignature(decoded.privateKey, decoded.publicKeyAlgorithm)
            .apply { update(challenge) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signature))
    }

    suspend fun close() {
        model.viewModelScope.cancel()
        BitwardenMutationSyncBridge.unregister(this)
        val attachmentFacade = AttachmentContainer.facade(context)
        db.passkeyDao().getAllPasskeysSync().filter { it.credentialId == credentialId || it.rpName.startsWith(prefix) }
            .forEach { passkeys.deletePasskeyByRecordId(it.id) }
        (db.secureItemDao().getAllItems().first() + db.secureItemDao().getDeletedItemsSync()).distinctBy { it.id }.filter { it.title.startsWith(prefix) || it.keepassDatabaseId in keepassFiles ||
            it.mdbxDatabaseId in mdbxFiles || it.bitwardenVaultId in vaultIds }.forEach {
            attachmentFacade.list(takagi.ru.monica.attachments.model.AttachmentOwner.secureItem(it.id))
                .forEach { attachment -> attachmentFacade.forgetLocalAttachment(attachment.id) }
            secureItems.deleteItemById(it.id)
        }
        (db.passwordEntryDao().getAllPasswordEntriesSync() + db.passwordEntryDao().getDeletedEntriesSync()).distinctBy { it.id }.filter { it.title.startsWith(prefix) || it.keepassDatabaseId in keepassFiles ||
            it.mdbxDatabaseId in mdbxFiles || it.bitwardenVaultId in vaultIds }.forEach {
            attachmentFacade.purgeByPassword(it.id)
            passwords.deletePasswordEntryById(it.id)
        }
        keepassFiles.keys.forEach {
            KeePassKdbxService.invalidateProcessCache(it)
            db.keepassPendingChangeDao().deleteByDatabase(it)
            db.localKeePassDatabaseDao().deleteDatabaseById(it)
        }
        Mdbx2NativeReadSessions.clear()
        mdbxFiles.forEach { (id, file) -> db.localMdbxDatabaseDao().deleteDatabaseById(id); mdbx.deleteOwnedVaultFile(file) }
        vaultIds.forEach {
            takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore.clear(context, it)
            db.bitwardenPendingOperationDao().deleteByVault(it)
            db.bitwardenSyncRawEntryRecordDao().deleteByVault(it)
            db.bitwardenFolderDao().deleteByVault(it)
            db.openHelper.writableDatabase.execSQL("DELETE FROM bitwarden_vaults WHERE id = ?", arrayOf(it))
            context.getSharedPreferences("credential_import_attachments_v1", 0).edit().remove("vault:$it").commit()
        }
        db.categoryDao().getAllCategories().first().filter { it.name.startsWith(prefix) }
            .forEach { db.categoryDao().delete(it) }
        Mdbx2NativeReadSessions.updateForeground(wasForeground)
        if (!wasUnlocked) SessionManager.markLocked()
        server.shutdown()
        vaultKey.clear()
        check(root.canonicalFile.parentFile == context.filesDir.canonicalFile && root.name == prefix)
        root.deleteRecursively()
    }

    class Remote : Dispatcher() {
        val created = mutableListOf<JSONObject>()
        val folders = mutableListOf<JSONObject>()
        private val profiles = linkedMapOf<String, JSONObject>()
        val profileReads = java.util.concurrent.atomic.AtomicInteger()
        fun registerAccount(userId: String, email: String) = synchronized(created) {
            check(UUID.fromString(userId).toString() == userId)
            profiles[userId] = JSONObject().put("id", userId).put("email", email)
        }
        private fun currentProfile(): JSONObject = JSONObject(requireNotNull(profiles.values.firstOrNull()).toString())
        @Volatile var rejectFolderRenameId: String? = null
        val attachmentCreates = mutableListOf<JSONObject>()
        val attachmentUploads = mutableMapOf<String, ByteArray>()
        @Volatile var rejectWrites = false
        @Volatile var rejectAttachments = false
        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(created) {
            when {
                request.method == "GET" && request.path == "/accounts/profile" -> {
                    profileReads.incrementAndGet()
                    check(request.getHeader("Authorization") == "Bearer synthetic-transfer-test-token")
                    MockResponse().setBody(currentProfile().toString())
                }
                request.method == "GET" && request.path.orEmpty().startsWith("/sync") -> MockResponse().setBody(
                    JSONObject().put("ciphers", JSONArray(created.map { JSONObject(it.toString()) })).put("folders", JSONArray(folders.map { JSONObject(it.toString()) }))
                        .put("profile", currentProfile()).toString())
                request.method == "POST" && request.path == "/folders" -> {
                    val folder = JSONObject(request.body.readUtf8()).put("id", UUID.randomUUID().toString()).put("revisionDate", "2026-10-21T00:00:00Z")
                    folders += folder
                    MockResponse().setBody(folder.toString())
                }
                request.method == "PUT" && request.path.orEmpty().startsWith("/folders/") -> {
                    val id = request.path!!.substringAfterLast('/')
                    if (id == rejectFolderRenameId) MockResponse().setResponseCode(503)
                    else folders.firstOrNull { it.optString("id") == id }?.let { folder ->
                        folder.put("name", JSONObject(request.body.readUtf8()).getString("name"))
                        MockResponse().setBody(folder.toString())
                    } ?: MockResponse().setResponseCode(404)
                }
                request.method == "POST" && request.path == "/ciphers" && rejectWrites -> MockResponse().setResponseCode(503)
                request.method == "POST" && request.path == "/ciphers" -> {
                    val cipher = JSONObject(request.body.readUtf8()).put("id", UUID.randomUUID().toString())
                        .put("revisionDate", "2026-09-16T00:00:00.000Z").put("creationDate", "2026-09-16T00:00:00.000Z")
                    val encryptedFor = cipher.optString("encryptedFor")
                    check(profiles.containsKey(encryptedFor)) { "Parent request must bind a registered authenticated UUID" }
                    created += cipher
                    MockResponse().setBody(cipher.toString())
                }
                request.method == "POST" && request.path.orEmpty().endsWith("/attachment/v2") -> {
                    if (rejectAttachments) MockResponse().setResponseCode(503) else {
                        val id = UUID.randomUUID().toString()
                        val meta = JSONObject(request.body.readUtf8()).put("id", id)
                            .put("cipherId", request.path!!.split('/')[2])
                        attachmentCreates += meta
                        MockResponse().setBody(JSONObject().put("attachmentId", id).put("fileUploadType", 1)
                            .put("url", request.requestUrl!!.resolve("/blobs/$id").toString()).toString())
                    }
                }
                request.method == "PUT" && request.path.orEmpty().startsWith("/blobs/") -> {
                    attachmentUploads[request.path!!.substringAfterLast('/')] = request.body.readByteArray()
                    MockResponse().setResponseCode(200)
                }
                request.method == "GET" && request.path.orEmpty().startsWith("/blobs/") ->
                    attachmentUploads[request.path!!.substringAfterLast('/')]?.let {
                        MockResponse().setBody(okio.Buffer().write(it)).setHeader("Content-Type", "application/octet-stream")
                    } ?: MockResponse().setResponseCode(404)
                request.method == "GET" && request.path.orEmpty().contains("/attachment/") -> {
                    val id = request.path!!.substringAfterLast('/')
                    attachmentCreates.firstOrNull { it.optString("id") == id && it.optString("cipherId") == request.path!!.split('/')[2] }
                        ?.let { metadata ->
                            MockResponse().setBody(JSONObject(metadata.toString())
                                .put("url", request.requestUrl!!.resolve("/blobs/$id").toString())
                                .put("size", metadata.optString("fileSize")).toString())
                        } ?: MockResponse().setResponseCode(404)
                }
                request.method == "GET" && request.path.orEmpty().startsWith("/ciphers/") ->
                    created.firstOrNull { it.optString("id") == request.path!!.substringAfterLast('/') }
                        ?.let { MockResponse().setBody(it.toString()) } ?: MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}
