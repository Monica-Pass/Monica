package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.executor.LocalAttachmentExecutor
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.*

/** Actual Room, encryption, attachment streams, receipts and service authorization; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class SingleVaultDedupLocalSafetyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var adapter: SingleVaultDedupLocalAdapter
    private lateinit var service: SingleVaultDedupService
    private lateinit var reader: LocalAttachmentExecutor
    private val ownBlobNames = mutableSetOf<String>()
    private val ownBatches = mutableSetOf<String>()
    private var wasUnlocked = false

    @Before fun setup(): Unit = runBlocking {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        reader = LocalAttachmentExecutor(context, AttachmentStorage(context), AttachmentKeyVault(security))
        adapter = SingleVaultDedupLocalAdapter(context, db, security, "Synthetic local")
        service = SingleVaultDedupService(db) { listOf(adapter) }
        service.sources()
    }

    @After fun cleanup() = runBlocking {
        service.invalidate()
        ownBlobNames += db.singleVaultDedupLocalDao().attachments().mapNotNull { it.localPath }
        val assets = SingleVaultDedupLocalAssets(context)
        ownBatches.forEach { id -> db.singleVaultDedupRecoveryDao().get(id)?.let { receipt ->
            val json = Json.parseToJsonElement(security.decryptTimelineSnapshot(receipt.sealedPayload)).jsonObject
            val backups = json.getValue("blobs").jsonArray.map { value -> value.jsonObject.let {
                SingleVaultDedupLocalAssets.Backup(it.getValue("id").jsonPrimitive.long, it.getValue("file").jsonPrimitive.content, it.getValue("hash").jsonPrimitive.content)
            } }
            assets.discardBackup(id, backups)
        } }
        assets.discardPrepared(ownBlobNames)
        db.close()
        if (!wasUnlocked) SessionManager.markLocked()
    }

    private suspend fun abc(attachments: Boolean = false, history: Boolean = false) {
        for (id in 1L..3L) {
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(id, "Example", "https://example.invalid", "user",
                security.encryptData("synthetic-password"), createdAt = Date(1_793_878_896_981), updatedAt = Date(1_793_878_896_982)))
            val values = when (id) { 1L -> listOf("1", "2", "3"); 2L -> listOf("1", "2"); else -> listOf("2", "3", "4") }
            db.customFieldDao().insertAll(values.mapIndexed { index, value -> CustomField(entryId = id, title = "Value $value", value = security.encryptData("value-$value"), sortOrder = index) })
            if (history) db.singleVaultDedupLocalDao().insertHistory(listOf(PasswordHistoryEntry(entryId = id,
                password = security.encryptData("older-password"), lastUsedAt = Date(1_700_000_000_001))))
            if (attachments) {
                val row = reader.writeFromBytes(AttachmentOwner.password(id), "proof.bin", "application/octet-stream", PAYLOAD)
                ownBlobNames += requireNotNull(row.localPath)
                db.attachmentDao().insert(row)
            }
        }
    }
    private suspend fun analyze(): SingleVaultDedupUiAnalysis = service.analyze("local", false).also {
        assertEquals(it.projects.toString(), listOf("password:2"), it.candidates.map { row -> row.candidateKey })
        assertEquals("password:1", it.candidates.single().keeperKey)
    }
    private suspend fun clean(): SingleVaultDedupUiOutcome {
        val plan = analyze()
        return service.clean(plan.planId, setOf("password:2")).also { outcome ->
            outcome.recoveryBatchId?.let(ownBatches::add)
        }
    }

    @Test fun inboundPasskeyRetainsWholeLocalProjectAndRejectsForgedCleanup() = runBlocking {
        val projectId = java.util.UUID.randomUUID().toString()
        for (id in 1L..3L) db.passwordEntryDao().insertPasswordEntry(PasswordEntry(id,
            "Synthetic bound project", "https://example.invalid", "user", security.encryptData("synthetic-password"),
            passwordGroupId = projectId.takeIf { id < 3L }))
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val key = PasskeyEntry(id = 77, credentialId = "synthetic-local-bound-id", rpId = "example.invalid",
            rpName = "Synthetic preserved key", userId = "dXNlcg", userName = "user", userDisplayName = "User",
            publicKey = java.util.Base64.getEncoder().encodeToString(pair.public.encoded),
            privateKeyAlias = java.util.Base64.getEncoder().encodeToString(pair.private.encoded), boundPasswordId = 2)
        db.passkeyDao().insert(key)
        val originalRows = db.passwordEntryDao().getAllPasswordEntriesSync()
        val originalKeys = db.passkeyDao().getAllPasskeysSync()
        val capture = adapter.capture(false) { }
        val boundProject = capture.projects.single { it.summary.key == "project:$projectId" }
        assertEquals(listOf(1L, 2L), boundProject.summary.memberIds)
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_REFERENCE in boundProject.blockers)
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_PRESERVED in capture.projects.single {
            it.summary.key == "passkey:77"
        }.blockers)
        assertTrue(SingleVaultDedupComparator.analyze(capture.projects).candidates.isEmpty())
        assertTrue(runCatching { adapter.clean(capture,
            listOf(SingleVaultDedupCandidate(boundProject.summary.key, "password:3", 1))) { } }.isFailure)
        assertEquals(originalRows, db.passwordEntryDao().getAllPasswordEntriesSync())
        assertEquals(originalKeys, db.passkeyDao().getAllPasskeysSync())
        val after = db.passkeyDao().getPasskeyByRecordId(77)!!
        val retainedPrivate = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(
            java.util.Base64.getDecoder().decode(after.privateKeyAlias)))
        val challenge = "synthetic-dedup-preserved-binding".toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(retainedPrivate); update(challenge)
        }.sign()
        assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public); update(challenge)
        }.verify(signature))
    }

    @Test fun nestedCredentialLabelsAreRetainedWhileOrdinaryNameFieldsCanBeDeduplicated() = runBlocking {
        fun payload(label: String, value: String) = buildJsonObject {
            put("cardNumber", "4111111111111111"); put("cardholderName", "Synthetic Person")
            put("expiryMonth", "12"); put("expiryYear", "2035")
            put("customFields", buildJsonArray { add(buildJsonObject {
                put("label", security.encryptData(label)); put("value", security.encryptData(value)); put("type", "HIDDEN")
            }) })
        }.toString()
        val preservedPayload = payload("Passkey / FIDO2", "synthetic-credential-material")
        val ordinaryPayload = payload("姓名", "An ordinary name field may mention Passkey in its text")
        for (id in 1L..4L) db.secureItemDao().insert(SecureItem(id = id,
            title = if (id < 3L) "Synthetic nested credential" else "Synthetic ordinary card",
            itemType = ItemType.BANK_CARD, itemData = if (id < 3L) preservedPayload else ordinaryPayload))
        val originalItems = db.secureItemDao().getAllItems().first().associateBy { it.id }
        val originalKeys = db.passkeyDao().getAllPasskeysSync()
        val capture = adapter.capture(false) { }
        assertTrue(capture.projects.filter { it.summary.key in setOf("secure:1", "secure:2") }.all {
            SingleVaultDedupBlockReason.PASSKEY_PRESERVED in it.blockers
        })
        val candidates = SingleVaultDedupComparator.analyze(capture.projects).candidates
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().candidateKey in setOf("secure:3", "secure:4"))
        assertTrue(candidates.single().keeperKey in setOf("secure:3", "secure:4"))
        val result = adapter.clean(capture, candidates) { }
        result.recoveryBatchId?.let(ownBatches::add)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, result.status)
        for (id in 1L..2L) assertEquals(originalItems.getValue(id), db.secureItemDao().getItemById(id))
        assertEquals(originalKeys, db.passkeyDao().getAllPasskeysSync())
        assertEquals(preservedPayload, db.secureItemDao().getItemById(1)!!.itemData)
        assertEquals("synthetic-credential-material", security.decryptData(Json.parseToJsonElement(
            db.secureItemDao().getItemById(1)!!.itemData).jsonObject.getValue("customFields").jsonArray.single()
            .jsonObject.getValue("value").jsonPrimitive.content))
    }

    @Test fun abcRemovesOnlyContainedProjectAndReplaysCannotDeleteAgain() = runBlocking {
        abc()
        val plan = analyze()
        val result = service.clean(plan.planId, setOf("password:2"))
        result.recoveryBatchId?.let(ownBatches::add)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, result.status)
        assertEquals(listOf(2L), db.singleVaultDedupLocalDao().passwords().filter { it.isDeleted }.map { it.id })
        assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, service.clean(plan.planId, setOf("password:2")).status)
        assertEquals(1, db.singleVaultDedupRecoveryDao().pending().size)
    }

    @Test fun independentEncryptedPackageRestoresAfterPermanentRemovalAndKeepsKeeperEdits() = runBlocking {
        abc(attachments = true, history = true)
        val originals = db.singleVaultDedupLocalDao().passwords()
        val fields = db.singleVaultDedupLocalDao().fields().filter { it.entryId == 2L }
        val histories = db.singleVaultDedupLocalDao().history().filter { it.entryId == 2L }
        val asset = db.singleVaultDedupLocalDao().attachments().single { it.parentPasswordId == 2L }
        val result = clean()
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, result.status)
        val batch = requireNotNull(result.recoveryBatchId)
        val receipt = requireNotNull(db.singleVaultDedupRecoveryDao().get(batch))
        assertFalse(receipt.sealedPayload.contains("synthetic-password"))
        db.passwordEntryDao().deletePasswordEntryById(2)
        reader.delete(asset)
        val editedKeeper = originals.single { it.id == 1L }.copy(notes = "Later keeper edit")
        db.passwordEntryDao().updatePasswordEntry(editedKeeper)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, service.restore(batch).status)
        assertEquals(originals.single { it.id == 2L }, db.passwordEntryDao().getPasswordEntryById(2))
        assertEquals(editedKeeper, db.passwordEntryDao().getPasswordEntryById(1))
        assertEquals(fields, db.singleVaultDedupLocalDao().fields().filter { it.entryId == 2L })
        assertEquals(histories, db.singleVaultDedupLocalDao().history().filter { it.entryId == 2L })
        val restored = db.singleVaultDedupLocalDao().attachments().single { it.parentPasswordId == 2L }
        assertNotEquals(asset.localPath, restored.localPath)
        assertArrayEquals(PAYLOAD, reader.openDecrypted(restored).use { it.readBytes() })
        assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED, service.restore(batch).status)
    }

    @Test fun changedFieldsAfterReviewPreventAnyTombstoneOrReceipt() = runBlocking {
        abc()
        val plan = analyze()
        db.customFieldDao().insertAll(listOf(CustomField(entryId = 2, title = "New field", value = "new")))
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, service.clean(plan.planId, setOf("password:2")).status)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
        assertTrue(db.singleVaultDedupRecoveryDao().pending().isEmpty())
    }

    @Test fun changedHistoryAfterReviewPreventsCleanup() = runBlocking {
        abc()
        val plan = analyze()
        db.singleVaultDedupLocalDao().insertHistory(listOf(PasswordHistoryEntry(entryId = 2, password = security.encryptData("new history"))))
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, service.clean(plan.planId, setOf("password:2")).status)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
    }

    @Test fun changedAttachmentBytesAfterReviewPreventCleanup() = runBlocking {
        abc(attachments = true)
        val plan = analyze()
        val asset = db.singleVaultDedupLocalDao().attachments().single { it.parentPasswordId == 2L }
        val original = File(context.filesDir, "secure_attachments/${asset.localPath}")
        original.appendBytes(byteArrayOf(7))
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, service.clean(plan.planId, setOf("password:2")).status)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
        assertTrue(db.singleVaultDedupRecoveryDao().pending().isEmpty())
    }

    @Test fun cancellationAtFinalCommitCheckRollsBackReceiptAndAllTombstones() = runBlocking {
        abc()
        val wrapped = object : SingleVaultDedupSourceAdapter by adapter {
            override suspend fun clean(captured: SingleVaultDedupCapture, selected: List<SingleVaultDedupCandidate>, checkActive: () -> Unit): SingleVaultDedupUiOutcome =
                adapter.clean(captured, selected) {
                    checkActive()
                    val receiptExists = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM single_vault_dedup_recovery").use { it.moveToFirst(); it.getInt(0) > 0 }
                    if (receiptExists) throw CancellationException("synthetic cancellation before commit")
                }
        }
        service = SingleVaultDedupService(db) { listOf(wrapped) }
        service.sources()
        val plan = analyze()
        assertTrue(runCatching { service.clean(plan.planId, setOf("password:2")) }.exceptionOrNull() is CancellationException)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
        assertTrue(db.singleVaultDedupRecoveryDao().pending().isEmpty())
    }

    @Test fun lockAndExplicitInvalidationRevokeThePlan() = runBlocking {
        abc()
        val plan = analyze()
        SessionManager.markLocked(); SessionManager.markUnlocked()
        assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, service.clean(plan.planId, setOf("password:2")).status)
        val next = analyze()
        service.invalidate()
        assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, service.clean(next.planId, setOf("password:2")).status)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
    }

    @Test fun realViewModelCanSelectAndRescanAfterItRevokesOldAdapters() = runBlocking {
        abc()
        val discoveries = java.util.concurrent.atomic.AtomicInteger()
        service = SingleVaultDedupService(db) { discoveries.incrementAndGet(); listOf(adapter) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var vm: SingleVaultDedupViewModel
        instrumentation.runOnMainSync { vm = SingleVaultDedupViewModel(service) }
        try {
            withTimeout(15_000) { vm.uiState.first { !it.isLoading && it.sources.isNotEmpty() } }
            instrumentation.runOnMainSync { vm.selectSource("local"); vm.analyze() }
            val initial = withTimeout(15_000) { vm.uiState.first { !it.isAnalyzing && (it.analysis != null || it.outcome != null) } }
            assertEquals(listOf("password:2"), initial.analysis?.candidates?.map { it.candidateKey })
            instrumentation.runOnMainSync { vm.setIncludeArchived(true); vm.analyze() }
            val repeated = withTimeout(15_000) { vm.uiState.first { !it.isAnalyzing && (it.analysis != null || it.outcome != null) } }
            assertEquals(listOf("password:2"), repeated.analysis?.candidates?.map { it.candidateKey })
            assertNotEquals(initial.analysis?.planId, repeated.analysis?.planId)
            assertTrue(discoveries.get() >= 3)
            assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
        } finally { instrumentation.runOnMainSync { vm.invalidate() } }
    }

    @Test fun invalidationDuringAdapterRediscoveryCannotPublishANewPlan() = runBlocking {
        abc()
        var revoke = false
        service = SingleVaultDedupService(db) {
            if (revoke) service.invalidate()
            listOf(adapter)
        }
        val previous = analyze()
        service.invalidate()
        revoke = true
        assertTrue(runCatching { service.analyze("local", false) }.isFailure)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, service.clean(previous.planId, setOf("password:2")).status)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
    }

    @Test fun newInboundReferencePreventsRestoreWithoutOverwritingAnything() = runBlocking {
        abc()
        val result = clean()
        val batch = requireNotNull(result.recoveryBatchId)
        val original = requireNotNull(db.passwordEntryDao().getPasswordEntryById(3))
        db.passwordEntryDao().updatePasswordEntry(original.copy(ssoRefEntryId = 2))
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, service.restore(batch).status)
        assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(2)).isDeleted)
        assertEquals(2L, db.passwordEntryDao().getPasswordEntryById(3)?.ssoRefEntryId)
    }

    @Test fun scanningDoesNotCreateOptionalDeviceSyncTables() = runBlocking {
        abc()
        analyze()
        val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name GLOB 'device_sync_*'").use { it.count }
        assertEquals(0, tables)
    }

    @Test fun internalRecoveryAndAttachmentDirectoriesSupportFsync() {
        val assets = SingleVaultDedupLocalAssets(context)
        val directories = listOf(File(context.noBackupFilesDir, "dedup-fsync-${java.util.UUID.randomUUID()}"),
            File(context.filesDir, "dedup-fsync-${java.util.UUID.randomUUID()}"))
        try {
            directories.forEach { directory ->
                check(directory.mkdir())
                assets.syncDirectory(directory)
                assets.syncDirectory(requireNotNull(directory.parentFile))
            }
        } finally { directories.forEach { it.delete() } }
    }

    @Test fun opaqueSyncExtensionRetainsOwnerAndBlocksUnknownInboundReferences() = runBlocking {
        abc()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE device_sync_content_extensions(local_id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO device_sync_content_extensions VALUES(?,?)",
            arrayOf("local:2", security.encryptData("{\"future_relations\":[\"unknown\"]}")))
        val captured = adapter.capture(false) {}
        assertTrue(captured.projects.single { it.summary.key == "password:2" }.blockers.contains(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE))
        assertTrue(captured.projects.all { SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT in it.blockers })
        assertTrue(SingleVaultDedupComparator.analyze(captured.projects).candidates.isEmpty())
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
    }

    @Test fun pendingBindingParticipatingProjectIsRetained() = runBlocking {
        abc()
        pendingBinding("local:2")
        val captured = adapter.capture(false) {}
        assertTrue(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE in captured.projects.single { it.summary.key == "password:2" }.blockers)
        assertTrue(SingleVaultDedupComparator.analyze(captured.projects).candidates.isEmpty())
    }

    @Test fun lateSyncTableAndBindingInvalidateReviewedCleanup() = runBlocking {
        abc()
        val plan = analyze()
        pendingBinding("local:2")
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, service.clean(plan.planId, setOf("password:2")).status)
        assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
        assertTrue(db.singleVaultDedupRecoveryDao().pending().isEmpty())
    }

    @Test fun lateSyncBindingPreventsRestorationAndRetainsRecoveryPoint() = runBlocking {
        abc()
        val batch = requireNotNull(clean().recoveryBatchId)
        pendingBinding("local:2")
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, service.restore(batch).status)
        assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(2)).isDeleted)
        assertEquals("COMMITTED", db.singleVaultDedupRecoveryDao().get(batch)?.state)
    }

    @Test fun unknownProtocolTableAndOpaqueRootMetadataRetainEveryProject() = runBlocking {
        abc()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE device_sync_future_relations(owner TEXT,payload TEXT)")
        assertTrue(adapter.capture(false) {}.projects.all { SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT in it.blockers })
        db.openHelper.writableDatabase.execSQL("DROP TABLE device_sync_future_relations")
        db.openHelper.writableDatabase.execSQL("CREATE TABLE device_sync_vault_metadata(global_id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL,deleted INTEGER NOT NULL)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO device_sync_vault_metadata VALUES(?,?,?)", arrayOf("synthetic-root", security.encryptData("{}"), 1))
        assertTrue(adapter.capture(false) {}.projects.all { SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT in it.blockers })
    }

    private fun pendingBinding(address: String) {
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS device_sync_pending_bindings(local_id TEXT PRIMARY KEY NOT NULL,guard TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO device_sync_pending_bindings VALUES(?,?)", arrayOf(address, "synthetic-guard"))
    }

    companion object { private val PAYLOAD = ByteArray(65539) { (it % 251).toByte() } }
}
