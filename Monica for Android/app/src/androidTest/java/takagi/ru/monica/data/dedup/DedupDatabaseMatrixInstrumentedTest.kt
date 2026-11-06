package takagi.ru.monica.data.dedup

import android.net.Uri
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.LegacyImageAttachmentSupport
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.bitwarden.service.BitwardenSyncIssues
import takagi.ru.monica.bitwarden.service.BitwardenCipherSourceStore
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardFaceAttachment
import takagi.ru.monica.data.model.CardFaceConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.MdbxStoredAttachment
import takagi.ru.monica.repository.MdbxAttachmentCekPayload
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.keepass.KeePassNativeEntryKind
import takagi.ru.monica.keepass.KeePassPasskeySyncCodec
import takagi.ru.monica.security.SecurityManager
import org.json.JSONArray
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import java.util.UUID
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** Real KDBX and Rust MDBX files; Bitwarden uses an encrypted synthetic server. */
@RunWith(AndroidJUnit4::class)
class DedupDatabaseMatrixInstrumentedTest {
    private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
    private var previousMdk: Any? = null
    private lateinit var syntheticMdk: ByteArray
    private var previousStore: KeePassMonicaPasswordStore? = null
    private var nativeStore: KeePassMonicaPasswordStore? = null

    @Before fun prepareAuthenticatedSession() {
        previousMdk = mdkField.get(null)
        syntheticMdk = ByteArray(32) { (it + 117).toByte() }
        mdkField.set(null, syntheticMdk)
        previousStore = AttachmentContainer.originalKeePassStore
    }

    @After fun restoreSession() {
        nativeStore?.clear()
        previousStore?.let(AttachmentContainer::bindOriginalKeePassStore)
        mdkField.set(null, previousMdk)
        syntheticMdk.fill(0)
    }
    @Test fun localToMdbx() = runBlocking { combination(ImportDestinationKind.LOCAL, true) }
    @Test fun mdbxToLocal() = runBlocking { combination(ImportDestinationKind.MDBX, false) }
    @Test fun mdbxToAnotherMdbx() = runBlocking { combination(ImportDestinationKind.MDBX, true) }
    @Test fun keepassToLocal() = runBlocking { combination(ImportDestinationKind.KEEPASS, false) }
    @Test fun keepassToMdbx() = runBlocking { combination(ImportDestinationKind.KEEPASS, true) }
    @Test fun bitwardenToLocal() = runBlocking { combination(ImportDestinationKind.BITWARDEN, false) }
    @Test fun bitwardenToMdbx() = runBlocking { combination(ImportDestinationKind.BITWARDEN, true) }
    @Test fun uncachedKeepassAttachmentsAreReadFromTheKdbxFile() = runBlocking { combination(ImportDestinationKind.KEEPASS, true, true) }
    @Test fun uncachedBitwardenAttachmentsAreDownloadedAndDecrypted() = runBlocking { combination(ImportDestinationKind.BITWARDEN, true, true) }

    private suspend fun combination(kind: ImportDestinationKind, intoMdbx: Boolean, discardCache: Boolean = false) {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = destination(kind)
            seed(source)
            if (discardCache) {
                val owners = importedPasswords(source).map { AttachmentOwner.password(it.id) } +
                    db.secureItemDao().getAllItems().first().filter { source.contains(it) }.map { AttachmentOwner.secureItem(it.id) }
                val facade = AttachmentContainer.facade(context)
                for (owner in owners) for (row in facade.list(owner)) {
                    assertTrue(row.sourceEnum != AttachmentSource.LOCAL)
                    val cache = row.localPath?.let { File(File(context.filesDir, "secure_attachments"), it).canonicalFile }
                    if (cache != null) {
                        check(cache.parentFile == File(context.filesDir, "secure_attachments").canonicalFile)
                        cache.delete()
                    }
                    db.attachmentDao().update(row.copy(localPath = null, wrappedCek = null, sha256Hex = null,
                        downloadState = "PENDING"))
                }
            }
            bindNativeSource(source)
            assertSourceAttachments(source)
            val target = if (intoMdbx) mdbx() else ImportDestination.Local
            verifyMerge(listOf(source), target)
        } } finally { fixture.close() }
    }

    @Test fun fourSourcesConsolidateIntoAnIndependentMdbxWithoutChangingSources() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val sources = ImportDestinationKind.entries.map { destination(it).also { seed(it); bindNativeSource(it) } }
            sources.forEach { assertSourceAttachments(it) }
            verifyMerge(sources, mdbx())
        } } finally { fixture.close() }
    }

    private suspend fun TransferFixture.destination(kind: ImportDestinationKind): ImportDestination = when (kind) {
        ImportDestinationKind.LOCAL -> ImportDestination.Local
        ImportDestinationKind.MDBX -> mdbx()
        ImportDestinationKind.KEEPASS -> keepass()
        ImportDestinationKind.BITWARDEN -> bitwarden()
    }

    private fun ImportDestination.sourceKey(): String = when (kind) {
        ImportDestinationKind.LOCAL -> "monica"
        ImportDestinationKind.MDBX -> "mdbx:$databaseId"
        ImportDestinationKind.KEEPASS -> "keepass:$databaseId"
        ImportDestinationKind.BITWARDEN -> "bitwarden:$databaseId"
    }

    private fun TransferFixture.service() = DedupMergeService(passwords, secureItems, passkeys,
        CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(),
        db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db, DedupAttachmentSupport(context, db))

    private suspend fun TransferFixture.bindNativeSource(source: ImportDestination) {
        source.keepassId?.let { id ->
            // A separately constructed native login proves the supported path is nonempty.
            // Explicitly seed history: the legacy Room-owner attachment API used by seed()
            // only updates binaries and does not promise to create a historical version.
            val original = keepassFile(id).inputStream().use {
                KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString("Synthetic transfer fixture password")))
            }
            val exchangeId = UUID.fromString(requireNotNull(importedPasswords(source).single().keepassEntryUuid))
            val historicalBinary = BinaryData.Uncompressed(true, historyPayload)
            var historySeeded = 0
            fun addHistory(group: Group): Group = group.copy(
                entries = group.entries.map { entry -> if (entry.uuid == exchangeId) {
                    historySeeded++
                    val historical = entry.copy(
                        fields = entry.fields + mapOf(
                            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-old-native-password")),
                            "HistoricalRecovery" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-old-native-recovery"))),
                        binaries = listOf(BinaryReference(historicalBinary.hash, "history-only.bin")),
                        history = emptyList())
                    entry.copy(history = entry.history + historical)
                } else entry },
                groups = group.groups.map(::addHistory))
            val withHistory = original.modifyParentGroup(::addHistory)
            assertEquals("Exactly one real native source receives the historical fixture", 1, historySeeded)
            val binary = BinaryData.Uncompressed(false, payload)
            val compatible = Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                "Title" to EntryValue.Plain("$prefix-native-compatible"), "UserName" to EntryValue.Plain(rawUsername),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(rawPassword)),
                "URL" to EntryValue.Plain(website), "Notes" to EntryValue.Plain("Native notes\n保留换行"),
                "Recovery" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-native-hidden"))),
                binaries = listOf(BinaryReference(binary.hash, "fixture.bin")))
            val updated = withHistory.modifyParentGroup { copy(entries = entries + compatible) }
                .modifyBinaries { it + (binary.hash to binary) + (historicalBinary.hash to historicalBinary) }
            keepassFile(id).outputStream().use { updated.encode(it) }
            KeePassKdbxService.invalidateProcessCache(id)
            if (nativeStore == null) {
                val files = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                nativeStore = KeePassMonicaPasswordStore(files, security)
                AttachmentContainer.bindOriginalKeePassStore(requireNotNull(nativeStore))
                AttachmentContainer.registerKeePassService(files)
            }
            passwords.monicaKeePassStore = nativeStore
            val snapshot = requireNotNull(nativeStore).refresh(id)
            val nativeKey = snapshot.passkeys.single()
            // PasskeyEntry carries the database and credential identity, not a native entry UUID.
            val keyRecord = snapshot.sourceBrowser.entries.single { record -> record.fields.any {
                it.name == "MonicaPasskeyCredentialId" && it.rawValue == nativeKey.credentialId
            } }
            assertEquals(id, nativeKey.keepassDatabaseId)
            assertOriginalKey(nativeKey)
            val keyProjection = snapshot.rows.single { it.keepassEntryUuid == keyRecord.identity.entryUuid.toString() }
            // The writer supplies standard UserName/URL on a passkey, so this same native UUID
            // intentionally has both login and passkey projections. It is not another record.
            assertEquals("${nativeKey.rpName} [Passkey]", keyProjection.title)
            assertTrue(keyProjection.username.isNotBlank())
            assertEquals("https://${nativeKey.rpId}", keyProjection.website)
            assertEquals(KeePassNativeEntryKind.PASSKEY, keyRecord.kind)
            assertFalse("A native Passkey must not pass through the ordinary-login compatibility gate",
                DedupMergeCompatibility(db, security).nativePassword(keyRecord, keyProjection,
                    snapshot.customFields[keyProjection.id].orEmpty()))
            assertEquals(setOf("$prefix-exchange", "$prefix-native-compatible", keyProjection.title), snapshot.rows.map { it.title }.toSet())
            assertEquals(3, snapshot.rows.size)
            assertEquals(3, snapshot.secureItems.size)
            assertEquals(1, snapshot.passkeys.size)
            assertTrue(keyRecord.fields.any { it.name == "MonicaPasskeyData" })
            assertTrue(snapshot.rows.all { KeePassMonicaPasswordStore.isUiId(it.id) })
            assertNativeHistory(id)
            assertTrue(snapshot.sourceBrowser.entries.single { it.title == "$prefix-card" }.fields.any { it.name == "Card Number" })
            assertTrue(snapshot.sourceBrowser.entries.single { it.title == "$prefix-note" }.fields.any { it.name == "MonicaItemNotes" })
            assertTrue(snapshot.sourceBrowser.entries.single { it.title == "$prefix-otp" }.fields.any { it.name.contains("otp", true) })
        }
        source.bitwardenId?.let { id ->
            val vaultBefore = requireNotNull(db.bitwardenVaultDao().getVaultById(id))
            val stamp = "$prefix-security-stamp"
            val delegate = server.dispatcher
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = delegate.dispatch(request)
                    val sync = request.path.orEmpty().startsWith("/sync")
                    if (request.method == "GET" && (sync || request.path == "/accounts/profile")) {
                        val document = JSONObject(requireNotNull(response.getBody()).readUtf8())
                        val profile = if (sync) document.getJSONObject("profile") else document
                        check(profile.getString("id") == vaultBefore.userId && profile.getString("email") == vaultBefore.email)
                        // The real session validator requires a server security stamp. The old
                        // minimal transfer mock omitted it and correctly failed MISSING_REMOTE.
                        profile.put("securityStamp", stamp)
                        response.setBody(document.toString())
                    }
                    return response
                }
            }
            // Mirror the server's response shape: request account metadata is not cipher content;
            // uploaded attachments are listed on their real parent, using their encrypted metadata.
            synchronized(remote.created) {
                remote.created.forEach { cipher ->
                    cipher.remove("encryptedFor")
                    val assets = remote.attachmentCreates.filter { it.optString("cipherId") == cipher.getString("id") }
                    if (assets.isNotEmpty()) cipher.put("attachments", JSONArray(assets.map { metadata ->
                        JSONObject().put("id", metadata.getString("id")).put("fileName", metadata.getString("fileName"))
                            .put("key", metadata.getString("key")).put("size", metadata.getString("fileSize"))
                            .put("url", server.url("/blobs/${metadata.getString("id")}").toString())
                    }))
                }
            }
            val captured = BitwardenRepository.getInstance(context).withDedupSource(id, {}) { vault, response, _ ->
                vault to response.ciphers
            }
            assertEquals(stamp, requireNotNull(db.bitwardenVaultDao().getVaultById(id)).revisionDate)
            assertTrue(BitwardenRepository.getInstance(context).isVaultUnlocked(id))
            assertEquals(5, captured.second.size)
            val store = BitwardenCipherSourceStore(context)
            captured.second.forEach { cipher -> store.accept(captured.first, cipher, cipher.revisionDate) }
        }
    }

    private suspend fun TransferFixture.assertNativeHistory(databaseId: Long) {
        val snapshot = requireNotNull(nativeStore).refresh(databaseId)
        val entry = snapshot.sourceBrowser.entries.single { it.title == "$prefix-exchange" }
        val history = entry.history.single { version -> version.fields.any {
            it.name == "Password" && it.rawValue == "synthetic-old-native-password"
        } }
        assertEquals(entry.identity.entryUuid, history.uuid)
        assertTrue(history.fields.single { it.name == "Password" }.isProtected)
        val recovery = history.fields.single { it.name == "HistoricalRecovery" }
        assertTrue(recovery.isProtected)
        assertEquals("synthetic-old-native-recovery", recovery.rawValue)
        val attachment = history.attachments.single()
        assertEquals("history-only.bin", attachment.name)
        val binary = requireNotNull(attachment.binary)
        assertTrue(binary.memoryProtection)
        assertArrayEquals(historyPayload, binary.getContent())
        assertEquals(rawPassword, entry.fields.single { it.name == "Password" }.rawValue)
        assertTrue(entry.attachments.none { it.name == "history-only.bin" })
        assertTrue(snapshot.sourceBrowser.entries.single { it.title == "$prefix-native-compatible" }.history.isEmpty())
    }

    /** Decode the actual KDBX on each invocation, independently of the projected UI caches. */
    private fun TransferFixture.readNativeKey(databaseId: Long): Entry {
        val entry = keepassEntries(databaseId).single {
            it.fields["MonicaPasskeyCredentialId"]?.content == credentialId
        }
        val key = checkNotNull(KeePassPasskeySyncCodec.decode(
            requireNotNull(entry.fields["MonicaPasskeyData"]).content, databaseId, null, null))
        assertOriginalKey(key)
        return entry
    }

    private suspend fun TransferFixture.nativeAttachmentBytes(attachment: MdbxStoredAttachment): ByteArray {
        val storage = AttachmentStorage(context)
        val temporary = "${UUID.randomUUID()}.enc"
        val file = storage.absolutePathOf(temporary)
        try {
            file.writeBytes(attachment.blob)
            val wrapped = MdbxAttachmentCekPayload.toLocalWrappedCek(requireNotNull(attachment.wrappedCek), security::encryptData)
            val cek = AttachmentKeyVault(security).unwrap(wrapped)
            try { return storage.openDecryptedStream(temporary, cek).use { it.readBytes() } }
            finally { cek.fill(0) }
        } finally { if (file.exists()) check(file.delete()) }
    }

    private suspend fun TransferFixture.nativeAttachmentEvidence(databaseId: Long): Map<String, List<Any?>> =
        mdbx.readStoredAttachments(databaseId).associate { attachment -> attachment.attachmentId to listOf(
            attachment.projectId, attachment.entryId, attachment.fileName, attachment.mimeType, attachment.contentHash,
            attachment.originalSize, attachment.createdAtMillis, attachment.updatedAtMillis, attachment.deleted,
            nativeAttachmentBytes(attachment).toList()) }

    private suspend fun TransferFixture.assertSourceAttachments(source: ImportDestination) {
        val snapshot = source.keepassId?.let { requireNotNull(nativeStore).refresh(it) }
        val password = snapshot?.rows?.single { it.title == "$prefix-exchange" } ?: importedPasswords(source).single()
        val note = snapshot?.secureItems?.single { it.itemType == ItemType.NOTE }
            ?: db.secureItemDao().getAllItems().first().single { source.contains(it) && it.title == "$prefix-note" }
        val facade = AttachmentContainer.facade(context)
        for ((owner, uuid, cipherId) in listOf(
            Triple(AttachmentOwner.password(password.id), password.keepassEntryUuid, password.bitwardenCipherId),
            Triple(AttachmentOwner.secureItem(note.id), note.keepassEntryUuid, note.bitwardenCipherId))) {
            val row = facade.list(owner).single()
            val bw = source.bitwardenId?.let { id -> BitwardenRepository.getInstance(context)
                .getAttachmentBitwardenContext(requireNotNull(db.bitwardenVaultDao().getVaultById(id)), cipherId) }
            val kp = source.keepassId?.let { AttachmentFacade.KeePassContext(it, requireNotNull(uuid)) }
            val output = java.io.ByteArrayOutputStream()
            facade.copyAttachmentTo(row.id, output, bw, kp)
            assertArrayEquals(payload, output.toByteArray())
        }
    }

    private suspend fun assertStale(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("A replaced, changed or consumed review must require another analysis", error is IllegalStateException)
    }

    private val payload = ByteArray(4097) { (it * 31).toByte() }
    private val historyPayload = ByteArray(1031) { (it * 17 + 5).toByte() }

    @Test fun targetThatCannotUnlockCreatesNoCopiesAndDoesNotAlterAnySource() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = ImportDestination.Local
            seed(source)
            val passwordsBefore = importedPasswords(source)
            val keysBefore = importedKeys(source)
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val database = db.localMdbxDatabaseDao().getDatabaseById(target.databaseId)!!
            Mdbx2NativeReadSessions.clear()
            db.localMdbxDatabaseDao().updateDatabase(database.copy(encryptedPassword = security.encryptData("Incorrect synthetic password")))
            val result = merger.executePlan(plan)
            assertEquals(result.toString(), 0, result.insertedItems)
            assertEquals(4, result.failedItems)
            assertTrue(importedPasswords(target).isEmpty())
            assertTrue(importedKeys(target).isEmpty())
            assertEquals(passwordsBefore, importedPasswords(source))
            assertEquals(keysBefore, importedKeys(source))
            assertOriginalKey(importedKeys(source).single())
            db.localMdbxDatabaseDao().updateDatabase(database)
        } } finally { fixture.close() }
    }

    @Test fun sameNameDifferentContentSurvivesAndIncompleteTargetDoesNotCountAsADuplicate() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = ImportDestination.Local
            seed(source)
            val target = mdbx()
            val incoming = importedPasswords(source).single()
            val existingId = passwords.insertPasswordEntry(target.password(incoming))
            val existingNative = mdbx.readStoredEntries(target.databaseId)
            val facade = AttachmentContainer.facade(context)
            // A metadata-only matching target must not hide the incoming attachment.
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            assertEquals(1, plan.writablePasswords)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(incoming.id), AttachmentSource.LOCAL, "fixture.bin",
                "application/octet-stream", byteArrayOf(1, 2, 3), true))
            assertStale { merger.executePlan(plan) }
            assertEquals(1, importedPasswords(target).size)
            val reviewed = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val result = merger.executePlan(reviewed)
            assertEquals(result.failures.toString(), 4, result.insertedItems)
            val copy = importedPasswords(target).single { it.id != existingId }
            val attachments = facade.list(AttachmentOwner.password(copy.id))
            assertEquals(2, attachments.size)
            assertEquals(setOf(payload.toList(), listOf<Byte>(1, 2, 3)), attachments.map {
                facade.readAttachmentBytes(it.id, 8192).toList()
            }.toSet())
            assertTrue(facade.list(AttachmentOwner.password(existingId)).isEmpty())
            val nativeAfter = mdbx.readStoredEntries(target.databaseId)
            assertTrue("Creating a complete copy must not replace the existing native target", existingNative.all { it in nativeAfter })
            assertStale { merger.executePlan(reviewed) }
        } } finally { fixture.close() }
    }

    @Test fun unreadableAttachmentIsRetainedBeforeWritingOtherItems() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = ImportDestination.Local
            seed(source)
            val incoming = importedPasswords(source).single()
            val facade = AttachmentContainer.facade(context)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(incoming.id), AttachmentSource.LOCAL, "missing.bin",
                "application/octet-stream", payload, true)).also {
                val directory = File(context.filesDir, "secure_attachments").canonicalFile
                val file = File(directory, it.localPath!!).canonicalFile
                check(file.parentFile == directory)
                assertTrue(file.delete())
            }
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val result = merger.executePlan(plan)
            assertEquals(1, plan.unsupportedItems.size)
            assertEquals(incoming.title, plan.unsupportedItems.single().title)
            assertEquals(0, result.failedPasswords)
            assertEquals(3, result.insertedItems)
            assertTrue(importedPasswords(target).isEmpty())
            assertEquals(incoming, importedPasswords(source).single())
            assertFalse(mdbx.readStoredEntries(target.databaseId).any { !it.deleted && it.entryType == "login" })
        } } finally { fixture.close() }
    }

    @Test fun corruptedAttachmentAfterAnEarlierCopyLeavesNoTargetBlobs() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = ImportDestination.Local
            seed(source)
            val incoming = importedPasswords(source).single()
            val facade = AttachmentContainer.facade(context)
            val corrupt = facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(incoming.id), AttachmentSource.LOCAL, "corrupt.bin",
                "application/octet-stream", payload, true))
            val path = File(File(context.filesDir, "secure_attachments"), corrupt.localPath!!)
            val earlierId = passwords.insertPasswordEntry(incoming.copy(id = 0, title = "$prefix-000-earlier"))
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            assertEquals(earlierId, plan.previewPasswords.first().sourceEntryIds.single())
            var corrupted = false
            val result = merger.executePlan(plan) { progress ->
                if (progress.completedItems == 1 && !corrupted) {
                    val bytes = path.readBytes()
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    path.writeBytes(bytes)
                    corrupted = true
                }
            }
            assertTrue(corrupted)
            assertEquals(1, result.failedPasswords)
            assertEquals("$prefix-000-earlier", importedPasswords(target).single().title)
            val liveAttachments = mdbx.readStoredAttachments(target.databaseId)
            assertEquals("Only the successfully copied note attachment remains", 1, liveAttachments.size)
            assertEquals(incoming, importedPasswords(source).single { it.id == incoming.id })
        } } finally { fixture.close() }
    }

    @Test fun legacyPhotosAndCardFacesHaveIndependentCopiesAndPortableMdbxContent() = runBlocking {
        val fixture = TransferFixture()
        val imageManager = ImageManager(fixture.context)
        val imageFiles = mutableListOf<String>()
        try { with(fixture) {
            val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val path = checkNotNull(imageManager.saveImage(bitmap)).also { imageFiles += it }
            bitmap.recycle()
            val photoBytes = checkNotNull(imageManager.readImageBytes(path))
            val face = CardFaceAttachment.newFileName()
            val card = SecureItem(title = "$prefix-photo-card", itemType = ItemType.BANK_CARD,
                itemData = Json.encodeToString(BankCardData("4111111111111111", "Alice", "09", "2030",
                    cardFace = CardFaceConfig(face))), imagePaths = Json.encodeToString(listOf(path, "")))
            val id = secureItems.insertItem(card)
            // A newer duplicate may contain the UI's empty front/back slots. It must not hide a real photo.
            val emptySlotsId = secureItems.insertItem(card.copy(imagePaths = Json.encodeToString(listOf("", "")),
                updatedAt = java.util.Date(System.currentTimeMillis() + 1000)))
            val missingFaceTitle = "$prefix-missing-card-face"
            val missingFaceId = secureItems.insertItem(card.copy(title = missingFaceTitle,
                imagePaths = Json.encodeToString(listOf("", ""))))
            val facade = AttachmentContainer.facade(context)
            // Each supported owner needs its own card-face attachment, even when the names match.
            for (ownerId in listOf(id, emptySlotsId)) {
                facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(ownerId),
                    AttachmentSource.LOCAL, face, "image/jpeg", photoBytes, true))
            }
            val sourceIds = setOf(id, emptySlotsId, missingFaceId)
            val originalRows = db.secureItemDao().getAllItems().first().filter { it.id in sourceIds }.associateBy { it.id }
            assertEquals(3, originalRows.size)
            val originalAttachments = sourceIds.associateWith { facade.list(AttachmentOwner.secureItem(it)) }
            assertEquals(1, originalAttachments.getValue(id).size)
            assertEquals(1, originalAttachments.getValue(emptySlotsId).size)
            assertTrue(originalAttachments.getValue(missingFaceId).isEmpty())
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf("monica"), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val newest = merger.buildPlan(setOf("monica"), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"), DedupConflictPolicy.NEWEST)
            assertEquals(setOf(card.imagePaths, Json.encodeToString(listOf("", ""))),
                newest.previewSecureItems.filter { it.item.title == card.title }.map { it.item.imagePaths }.toSet())
            assertEquals(listOf(missingFaceTitle), newest.unsupportedItems.map { it.title })
            assertTrue(newest.previewSecureItems.none { missingFaceId in it.sourceItemIds })
            assertStale { merger.executePlan(plan) }
            val firstResult = merger.executePlan(newest)
            assertTrue(firstResult.failures.toString(), firstResult.failures.isEmpty())
            assertEquals(2, firstResult.insertedSecureItems)
            val copies = db.secureItemDao().getAllItems().first().filter { target.contains(it) && it.title == card.title }
            assertEquals(2, copies.size)
            val copy = copies.single { Json.decodeFromString<List<String>>(it.imagePaths).any(String::isNotBlank) }
            val emptySlotsCopy = copies.single { it.id != copy.id }
            assertTrue(Json.decodeFromString<List<String>>(emptySlotsCopy.imagePaths).all(String::isBlank))
            val emptySlotsAttachments = facade.list(AttachmentOwner.secureItem(emptySlotsCopy.id))
            assertEquals(1, emptySlotsAttachments.size)
            assertEquals(face, emptySlotsAttachments.single().fileName)
            assertArrayEquals(photoBytes, facade.readAttachmentBytes(emptySlotsAttachments.single().id, 8192))
            val copiedPath = Json.decodeFromString<List<String>>(copy.imagePaths).first().also { imageFiles += it }
            assertNotEquals(path, copiedPath)
            assertArrayEquals(photoBytes, imageManager.readImageBytes(copiedPath))
            val attachments = facade.list(AttachmentOwner.secureItem(copy.id))
            assertEquals(2, attachments.size)
            assertArrayEquals(photoBytes, facade.readAttachmentBytes(attachments.single { it.fileName == face }.id, 8192))
            Mdbx2NativeReadSessions.clear()
            val firstNativePhotos = mdbx.readStoredAttachments(target.databaseId)
            assertEquals(3, firstNativePhotos.size)
            firstNativePhotos.forEach { assertArrayEquals(photoBytes, nativeAttachmentBytes(it)) }
            imageManager.deleteImage(copiedPath)
            LegacyImageAttachmentSupport(context).restoreMissing(copy, facade)
            assertArrayEquals(photoBytes, imageManager.readImageBytes(copiedPath))
            assertStale { merger.executePlan(newest) }
            val fresh = merger.buildPlan(setOf("monica"), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            assertEquals(2, fresh.writableSecureItems)
            assertEquals(0, fresh.targetExistingSecureItems)
            assertEquals(listOf(missingFaceTitle), fresh.unsupportedItems.map { it.title })
            assertTrue(fresh.previewSecureItems.none { missingFaceId in it.sourceItemIds })
            val secondResult = merger.executePlan(fresh)
            assertTrue(secondResult.failures.toString(), secondResult.failures.isEmpty())
            assertEquals(2, secondResult.insertedSecureItems)
            val allCopies = db.secureItemDao().getAllItems().first().filter { target.contains(it) && it.title == card.title }
            assertEquals(4, allCopies.size)
            assertTrue(copies.all { it in allCopies })
            allCopies.filter { candidate -> copies.none { it.id == candidate.id } }.forEach { newer ->
                Json.decodeFromString<List<String>>(newer.imagePaths).filter(String::isNotBlank).forEach { newPath ->
                    imageFiles += newPath
                    assertNotEquals(copiedPath, newPath)
                    assertArrayEquals(photoBytes, imageManager.readImageBytes(newPath))
                }
            }
            Mdbx2NativeReadSessions.clear()
            val nativePhotos = mdbx.readStoredAttachments(target.databaseId)
            assertEquals(6, nativePhotos.size)
            nativePhotos.forEach { assertArrayEquals(photoBytes, nativeAttachmentBytes(it)) }
            assertTrue(db.secureItemDao().getAllItems().first().none { target.contains(it) && it.title == missingFaceTitle })
            assertEquals(originalRows, db.secureItemDao().getAllItems().first().filter { it.id in sourceIds }.associateBy { it.id })
            for (ownerId in sourceIds) {
                val retained = facade.list(AttachmentOwner.secureItem(ownerId))
                assertEquals(originalAttachments.getValue(ownerId), retained)
                retained.forEach { assertArrayEquals(photoBytes, facade.readAttachmentBytes(it.id, 8192)) }
            }
            assertArrayEquals(photoBytes, imageManager.readImageBytes(path))
            imageManager.deleteImage(path)
            assertArrayEquals(photoBytes, imageManager.readImageBytes(copiedPath))
        } } finally {
            fixture.close()
            imageManager.deleteImages(imageFiles)
        }
    }

    private suspend fun TransferFixture.seed(target: ImportDestination) {
        val original = decoded().items.single()
        val exchange = CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(
            original.copy(passkeys = emptyList()),
            original.copy(id = "standalone-key", title = "$prefix-key", logins = emptyList(), notes = "")
        ), "Fixture", setOf("basic-auth", "passkey", "note")))
        val imported = importer.importExchange(exchange, target)
        assertEquals(imported.toString(), 2, imported.imported)
        assertNull(importedKeys(target).single().boundPasswordId)

        val (noteData, noteContent) = NoteContentCodec.encode("Keep this multilingual note: 换行 Zażółć")
        val file = File(root, "items.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, data: JSONObject) {
                zip.putNextEntry(ZipEntry(path)); zip.write(data.toString().toByteArray()); zip.closeEntry()
            }
            put("notes/note.json", JSONObject().put("id", 710001).put("title", "$prefix-note")
                .put("itemData", noteData).put("notes", noteContent))
            put("totp/totp.json", JSONObject().put("id", 710002).put("title", "$prefix-otp")
                .put("itemData", JSONObject().put("secret", "JBSWY3DPEHPK3PXP")
                    .put("issuer", "Fixture").put("accountName", "alice").toString()))
            put("bank_cards/card.json", JSONObject().put("id", 710003).put("title", "$prefix-card")
                .put("itemData", JSONObject().put("cardNumber", "4111111111111111").put("cardholderName", "Alice")
                    .put("expiryMonth", "09").put("expiryYear", "2030").put("cvv", "123").toString()))
        }
        assertEquals(3, model.importZipBackup(Uri.fromFile(file), null, target).getOrThrow())
        target.bitwardenId?.let { id ->
            val vault = db.bitwardenVaultDao().getVaultById(id)!!
            assertTrue("History writes require an authenticated MDK", security.encryptData("synthetic-history-check").startsWith("MDK|"))
            val issues = BitwardenSyncIssues()
            val session = issues.begin(id)
            val result = BitwardenSyncService(context).uploadLocalEntries(vault, accessToken, vaultKey, session)
            session.finish()
            val categories = issues.reports.value[id]?.items.orEmpty().map { "${it.kind}/${it.phase}/${it.reason}" }
            assertTrue("$result; categories=$categories", result is UploadResult.Success && result.failed == 0)
            assertEquals(5, remote.created.size)
        }
        val password = importedPasswords(target).single()
        val note = db.secureItemDao().getAllItems().first().single {
            target.contains(it) && it.title == "$prefix-note"
        }
        val facade = AttachmentContainer.facade(context)
        suspend fun add(owner: AttachmentOwner, keepassUuid: String?, cipherId: String?) {
            val vault = target.bitwardenId?.let { db.bitwardenVaultDao().getVaultById(it)!! }
            val bitwardenContext = vault?.let {
                BitwardenRepository.getInstance(context).getAttachmentBitwardenContext(it, cipherId)
            }
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                owner = owner, source = when (target.kind) {
                    ImportDestinationKind.KEEPASS -> AttachmentSource.KEEPASS
                    ImportDestinationKind.BITWARDEN -> AttachmentSource.BITWARDEN
                    else -> AttachmentSource.LOCAL
                }, fileName = "fixture.bin", mimeType = "application/octet-stream", bytes = payload,
                isPlusActivated = true, bitwardenPremium = true, kdbxSoftLimitAccepted = true,
                bitwardenContext = bitwardenContext,
                keepassContext = target.keepassId?.let { AttachmentFacade.KeePassContext(it, keepassUuid!!) }))
        }
        add(AttachmentOwner.password(password.id), password.keepassEntryUuid, password.bitwardenCipherId)
        add(AttachmentOwner.secureItem(note.id), note.keepassEntryUuid, note.bitwardenCipherId)
    }

    private suspend fun TransferFixture.verifyMerge(sources: List<ImportDestination>, target: ImportDestination) {
        val passwordsBefore = db.passwordEntryDao().getAllPasswordEntriesSync().filter { p -> sources.any { it.contains(p) } }
        val itemsBefore = db.secureItemDao().getAllItems().first().filter { p -> sources.any { it.contains(p) } }
        val keysBefore = passkeys.getAllPasskeysSync().filter { p -> sources.any { it.contains(p) } }
        val sourceFiles = sources.mapNotNull { it.keepassId }.associateWith { keepassFile(it).readBytes() }
        val nativeKeyContents = sourceFiles.keys.associateWith { SingleVaultDedupKeePassContent.entry(readNativeKey(it)) }
        val sourceMdbx = sources.mapNotNull { it.mdbxId }.associateWith { mdbx.readStoredEntries(it) }
        val sourceMdbxAttachments = sourceMdbx.keys.associateWith { nativeAttachmentEvidence(it) }
        sourceMdbx.forEach { (id, entries) ->
            assertEquals("The retained MDBX source is a real nonempty native vault", 5, entries.count { !it.deleted })
            assertEquals(2, sourceMdbxAttachments.getValue(id).size)
            mdbx.readStoredAttachments(id).forEach { assertArrayEquals(payload, nativeAttachmentBytes(it)) }
        }
        val ciphersBefore = remote.created.map { it.toString() }
        val merger = service()
        val selectedTarget = target.mdbxId?.let { DedupMergeTarget.MdbxDatabase(it, "Target") }
            ?: DedupMergeTarget.MonicaLocal
        val sourceKeys = sources.map { it.sourceKey() }.toSet()
        val plan = merger.buildPlan(sourceKeys, selectedTarget)
        val portableSources = sources.count { it.kind == ImportDestinationKind.LOCAL }
        val retainedSourceKeys = sources.filter { it.kind == ImportDestinationKind.MDBX }.map { it.sourceKey() }.toSet()
        assertEquals(retainedSourceKeys, plan.retainedSources.map { it.key }.toSet())
        assertTrue(plan.retainedSources.all { it.kind == DedupMergeSourceKind.MDBX && it.label.isNotBlank() })
        assertTrue("Native-only MDBX scope must be visible without inventing project counts",
            plan.selectedSources.map { it.key }.containsAll(retainedSourceKeys))
        val nativeSources = sources.count { it.kind == ImportDestinationKind.KEEPASS }
        val bitwardenSources = sources.count { it.kind == ImportDestinationKind.BITWARDEN }
        val ordinaryPasswords = if (portableSources + bitwardenSources > 0) 1 else 0
        val expectedPasswords = ordinaryPasswords + nativeSources
        val expectedSecure = if (portableSources > 0) 3 else if (bitwardenSources > 0) 1 else 0
        val expectedCopies = expectedPasswords + expectedSecure
        // Explicitly seeded native history and richer typed fields are retained. A separate
        // history-free native login still copies its protected field, notes and real attachment.
        val expectedRetainedTitles = buildList {
            repeat(nativeSources) { addAll(listOf("$prefix-exchange", "$prefix-note", "$prefix-card", "$prefix-otp", "$prefix-key [Passkey]")) }
            repeat(bitwardenSources) { addAll(listOf("$prefix-card", "$prefix-otp")) }
        }.sorted()
        assertEquals(expectedRetainedTitles, plan.unsupportedItems.map { it.title }.sorted())
        assertEquals(portableSources + nativeSources + bitwardenSources, plan.unsupportedSourcePasskeys)
        assertEquals(portableSources + nativeSources + bitwardenSources, plan.totalSourcePasskeys)
        assertTrue("Native Passkeys are retained, never copied as nonfunctional ordinary logins",
            plan.previewPasswords.none { it.entry.title == "$prefix-key [Passkey]" })
        assertEquals(plan.totalSourcePasskeys, plan.previewPasskeys.size)
        assertTrue(plan.previewPasskeys.all { !it.writable && it.skipReason == DedupPasskeySkipReason.PRESERVED })
        assertTrue(plan.previewPasswords.none { resolved -> resolved.sourceLabels.any { label -> plan.retainedSources.any { it.label == label } } })
        if (sources.all { it.kind == ImportDestinationKind.MDBX }) {
            assertEquals(0, plan.totalSourceItems)
            assertTrue(plan.previewPasswords.isEmpty() && plan.previewSecureItems.isEmpty() && plan.previewPasskeys.isEmpty())
            assertTrue(plan.unsupportedItems.isEmpty())
        }
        assertEquals(expectedCopies, plan.writableItems)
        val result = merger.executePlan(plan)
        assertEquals(result.failures.toString(), expectedCopies, result.insertedItems)
        assertEquals(0, result.failedItems)
        val copiedPasswords = importedPasswords(target)
        assertEquals(expectedPasswords, copiedPasswords.size)
        copiedPasswords.forEach { password ->
            assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(password.password))
            assertEquals(rawUsername, password.username)
            assertEquals(website, password.website)
            if (password.title == "$prefix-native-compatible") {
                assertEquals("Native notes\n保留换行", password.notes)
                val field = db.customFieldDao().getFieldsByEntryIdSync(password.id).single()
                assertEquals("Recovery", field.title)
                assertTrue(field.isProtected)
                assertEquals("synthetic-native-hidden", security.decryptDataIfMonicaCiphertext(field.value))
            }
        }
        assertTrue("The dedup engine never copies a Passkey to the target", importedKeys(target).isEmpty())
        val items = db.secureItemDao().getAllItems().first().filter { target.contains(it) && it.title.startsWith(prefix) }
        assertEquals(if (portableSources > 0) setOf(ItemType.NOTE, ItemType.TOTP, ItemType.BANK_CARD)
            else if (bitwardenSources > 0) setOf(ItemType.NOTE) else emptySet<ItemType>(), items.map { it.itemType }.toSet())
        assertEquals(expectedSecure, items.size)
        val note = items.singleOrNull { it.itemType == ItemType.NOTE }
        val facade = AttachmentContainer.facade(context)
        val targets = copiedPasswords.map { AttachmentOwner.password(it.id) } + listOfNotNull(note?.let { AttachmentOwner.secureItem(it.id) })
        targets.forEach { owner ->
            val copies = facade.list(owner)
            assertEquals("Merged owner $owner must retain exactly one complete attachment", 1, copies.size)
            assertArrayEquals(payload, facade.readAttachmentBytes(copies.single().id, 8192))
            assertEquals(AttachmentSource.LOCAL, copies.single().sourceEnum)
            assertNull(copies.single().bitwardenAttachmentId)
            assertNull(copies.single().keepassBinaryRef)
        }
        target.mdbxId?.let { id ->
            Mdbx2NativeReadSessions.clear()
            assertEquals(expectedCopies, mdbx.readStoredEntries(id).count { !it.deleted })
            assertEquals(targets.size, mdbx.readStoredAttachments(id).size)
            mdbx.readStoredAttachments(id).forEach { assertArrayEquals(payload, nativeAttachmentBytes(it)) }
            val keys = mdbx.readStoredEntries(id).filter { it.entryType == "passkey" && !it.deleted }
            assertTrue("The native target must contain no dedup-created Passkey", keys.isEmpty())
        }
        assertStale { merger.executePlan(plan) }
        val fresh = merger.buildPlan(sourceKeys, selectedTarget)
        assertEquals(expectedRetainedTitles, fresh.unsupportedItems.map { it.title }.sorted())
        assertEquals(retainedSourceKeys, fresh.retainedSources.map { it.key }.toSet())
        val originalTargetRows = target.mdbxId?.let { mdbx.readStoredEntries(it) }
        val repeatedCopies = if (target.mdbxId != null) expectedPasswords + expectedSecure else 0
        assertEquals(repeatedCopies, fresh.writableItems)
        if (target.mdbxId != null) {
            assertEquals(0, fresh.targetExistingDuplicates + fresh.targetExistingSecureItems + fresh.targetExistingPasskeys)
            assertTrue(fresh.previewPasskeys.all { !it.writable && it.skipReason == DedupPasskeySkipReason.PRESERVED })
        }
        val repeat = merger.executePlan(fresh)
        assertEquals(repeat.toString(), repeatedCopies, repeat.insertedItems)
        assertEquals(if (target.mdbxId != null) 0 else expectedPasswords + expectedSecure, repeat.skippedExistingItems)
        target.mdbxId?.let { id ->
            Mdbx2NativeReadSessions.clear()
            val finalRows = mdbx.readStoredEntries(id)
            assertEquals(expectedCopies + repeatedCopies, finalRows.count { !it.deleted })
            assertTrue(requireNotNull(originalTargetRows).all { it in finalRows })
            val nativeKeys = finalRows.filter { !it.deleted && it.entryType == "passkey" }
            assertTrue(nativeKeys.isEmpty())
            val nativeAssets = mdbx.readStoredAttachments(id)
            assertEquals(targets.size * 2, nativeAssets.size)
            nativeAssets.forEach { assertArrayEquals(payload, nativeAttachmentBytes(it)) }
        }
        assertEquals(passwordsBefore, db.passwordEntryDao().getAllPasswordEntriesSync().filter { p -> sources.any { it.contains(p) } })
        assertEquals(itemsBefore, db.secureItemDao().getAllItems().first().filter { p -> sources.any { it.contains(p) } })
        assertEquals(keysBefore, passkeys.getAllPasskeysSync().filter { p -> sources.any { it.contains(p) } })
        keysBefore.forEach(::assertOriginalKey)
        sourceFiles.forEach { (id, bytes) ->
            assertArrayEquals(bytes, keepassFile(id).readBytes())
            assertTrue("Every native credential field, history and identity must remain intact",
                nativeKeyContents.getValue(id) == SingleVaultDedupKeePassContent.entry(readNativeKey(id)))
            assertNativeHistory(id)
        }
        sourceMdbx.forEach { (id, entries) ->
            Mdbx2NativeReadSessions.invalidate(id)
            assertEquals(entries, mdbx.readStoredEntries(id))
            assertEquals(sourceMdbxAttachments.getValue(id), nativeAttachmentEvidence(id))
            entries.filter { !it.deleted && it.entryType == "passkey" }.forEach {
                assertKeySigns(JSONObject(it.payloadJson).getString("private_key_alias"))
            }
        }
        assertEquals(ciphersBefore, remote.created.map { it.toString() })
        assertFalse(merger.buildPlan(setOf(target.sourceKey()), selectedTarget).writableItems > 0)
    }
}
