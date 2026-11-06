package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import app.keemobile.kotpass.xml.XmlContentParser
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.EntryContentFields
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.keepass.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassCodecSupport
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcomeStatus

/** Real private KDBX files and persistent recovery Room rows, using only synthetic data. */
class SingleVaultDedupKeePassInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeCleanColdReadAndPersistentPreparedRecoveryPreserveHistoryAndAttachments() = runBlocking {
        scenario {
            val original = readFile()
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            assertEquals(1, candidates.size)
            val candidate = captured.projects.single { it.summary.key == candidates.single().candidateKey }
            val uuid = original.content.group.entries[(candidate.summary.memberIds.single() - 1).toInt()].uuid
            val originalPayload = KeePassLosslessTransfer.captureEntry(original, uuid)
            val result = adapter.clean(captured, candidates) { }
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, result.status)
            val batchId = requireNotNull(result.recoveryBatchId)
            val saved = readFile()
            val session = nativeSession(saved)
            assertTrue(session.entryNodes.single { it.entry.uuid == uuid }.isInRecycleBin)
            assertEquals(2, session.entryNodes.size)
            assertEquals(1, session.entryNodes.count { !it.isInRecycleBin })
            val recovery = requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))
            assertFalse(recovery.sealedPayload.contains("synthetic-password"))
            // Simulate an interrupted acknowledgement after the native commit.
            assertEquals(1, db.singleVaultDedupRecoveryDao().transition(batchId, "COMMITTED", "PREPARED"))
            reopen()
            val persisted = requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))
            assertEquals("PREPARED", persisted.state)
            val restored = adapter.restore(persisted) { }
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, restored.status)
            val coldRestored = readFile()
            assertTrue(SingleVaultDedupKeePassContent.equivalentPayload(originalPayload, KeePassLosslessTransfer.captureEntry(coldRestored, uuid)))
            assertEquals(2, nativeSession(coldRestored).entryNodes.count { !it.isInRecycleBin })
            assertEquals("RESTORED", db.singleVaultDedupRecoveryDao().get(batchId)?.state)
            val finalBytes = file.readBytes()
            assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED, adapter.restore(requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))) { }.status)
            assertArrayEquals(finalBytes, file.readBytes())
        }
    }

    @Test fun changedNativeSourceInvalidatesReviewedSelectionWithoutWriting() = runBlocking {
        scenario {
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            val changed = readFile().modifyParentGroup { copy(entries = entries.mapIndexed { index, entry ->
                if (index == 1) entry.copy(fields = entry.fields + ("Notes" to EntryValue.Plain("later edit"))) else entry
            }) }
            file.outputStream().use { KeePassCodecSupport.encode(changed, it) }
            val bytes = file.readBytes()
            assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, adapter.clean(captured, candidates) { }.status)
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(db.singleVaultDedupRecoveryDao().pending().isEmpty())
        }
    }

    @Test fun nativeLockAndReauthenticationDoNotReviveAnOldSelection() = runBlocking {
        scenario {
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            val bytes = file.readBytes()
            service.lockDatabase(id)
            assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, adapter.clean(captured, candidates) { }.status)
            service.openNativeSession(id).getOrThrow()
            assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, adapter.clean(captured, candidates) { }.status)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun cancellationAfterVerifiedRecoveryPointDoesNotCommitAndPreparedRecordCanBeReconciled() = runBlocking {
        scenario {
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            val bytes = file.readBytes()
            try {
                adapter.clean(captured, candidates) {
                    val prepared = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM single_vault_dedup_recovery").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                    if (prepared > 0) throw CancellationException("Synthetic cancellation after durable preparation")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertArrayEquals(bytes, file.readBytes())
            val batch = db.singleVaultDedupRecoveryDao().pending().single()
            assertEquals("PREPARED", batch.state)
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, adapter.restore(batch) { }.status)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun readOnlyAndDisabledRecycleBinNeverFallBackToPermanentDeletion() = runBlocking {
        scenario {
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            val bytes = file.readBytes()
            service.setDatabaseReadOnly(id, true)
            try { assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, adapter.clean(captured, candidates) { }.status) }
            finally { service.setDatabaseReadOnly(id, false) }
            assertArrayEquals(bytes, file.readBytes())
            val disabled = readFile().modifyContent { copy(meta = meta.copy(recycleBinEnabled = false)) }
            file.outputStream().use { KeePassCodecSupport.encode(disabled, it) }
            KeePassKdbxService.invalidateProcessCache(id)
            service.openNativeSession(id).getOrThrow()
            adapter.capture(false) { }
            assertFalse(adapter.source.canClean)
            val disabledBytes = file.readBytes()
            assertEquals(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED, adapter.clean(captured, candidates) { }.status)
            assertArrayEquals(disabledBytes, file.readBytes())
        }
    }

    @Test fun sourceXmlLossSignalsKeepOriginalFileAndDisableCleanup() = runBlocking {
        for (case in listOf("second-root", "ignored-protection", "missing-binary")) scenario {
            val original = readFile()
            val parser = object : XmlContentParser by KeePassCodecSupport.contentParser {
                override fun marshalContent(context: XmlContext.Encode, content: DatabaseContent, pretty: Boolean): String {
                    val xml = KeePassCodecSupport.contentParser.marshalContent(context, content, pretty)
                    val changed = when (case) {
                        "second-root" -> xml.replace("</Root>", "<Group><UUID>AgAAAAAAAAAAAAAAAAAAAA==</UUID><Name>Additional tree</Name></Group></Root>")
                        "ignored-protection" -> xml.replaceFirst("<Value>", "<Value ProtectInMemory=\"True\">")
                        else -> xml.replace("Ref=\"0\"", "Ref=\"999\"")
                    }
                    check(changed != xml)
                    return changed
                }
            }
            file.outputStream().use { original.encode(it, contentParser = parser, cipherProviders = KeePassCodecSupport.cipherProviders) }
            KeePassKdbxService.invalidateProcessCache(id)
            service.openNativeSession(id).getOrThrow()
            val bytes = file.readBytes()
            val captured = adapter.capture(false) { }
            assertFalse(adapter.source.canClean)
            assertTrue(captured.projects.all { SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT in it.blockers })
            assertTrue(SingleVaultDedupComparator.analyze(captured.projects).candidates.isEmpty())
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun megabyteAttachmentsUseSmallRoomReceiptAndRecoverVerifiedNativeBytes() = runBlocking {
        scenario {
            val bytes = ByteArray(1_300_000).also { java.util.Random(20261106L).nextBytes(it) }
            val attachment = BinaryData.Uncompressed(true, bytes)
            val large = readFile().modifyParentGroup { copy(entries = entries.map { entry ->
                entry.copy(binaries = listOf(BinaryReference(attachment.hash, "large.bin")),
                    history = entry.history.map { it.copy(binaries = listOf(BinaryReference(attachment.hash, "old-large.bin"))) })
            }) }.modifyBinaries { mapOf(attachment.hash to attachment) }
            replaceFile(large)
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            assertEquals(1, candidates.size)
            val candidate = captured.projects.single { it.summary.key == candidates.single().candidateKey }
            val originalId = nativeSession(large).entryNodes[(candidate.summary.memberIds.single() - 1).toInt()].entry.uuid
            val original = KeePassLosslessTransfer.captureEntry(large, originalId)
            val outcome = adapter.clean(captured, candidates) { }
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, outcome.status)
            val batchId = requireNotNull(outcome.recoveryBatchId)
            val receipt = requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))
            assertTrue(receipt.sealedPayload.toByteArray().size < 4096)
            assertTrue(File(context.noBackupFilesDir, "${SingleVaultDedupPayloadStore.DIRECTORY}/$batchId.sealed").length() > 2 * 1024 * 1024)
            reopen()
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, adapter.restore(requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))) { }.status)
            val recovered = KeePassLosslessTransfer.captureEntry(readFile(), originalId)
            assertTrue(SingleVaultDedupKeePassContent.equivalentPayload(original, recovered))
            assertArrayEquals(bytes, recovered.binaryPool.getValue(attachment.hash).getContent())
        }
    }

    @Test fun permanentDeletionRecoversSingleStableCopyAndRetryPreservesKeeperAndTombstone() = runBlocking {
        scenario {
            val source = readFile()
            val captured = adapter.capture(false) { }
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            val candidate = captured.projects.single { it.summary.key == candidates.single().candidateKey }
            val originalId = nativeSession(source).entryNodes[(candidate.summary.memberIds.single() - 1).toInt()].entry.uuid
            val keeperId = source.content.group.entries.single { it.uuid != originalId }.uuid
            val keeper = SingleVaultDedupKeePassContent.payload(source, keeperId)
            val outcome = adapter.clean(captured, candidates) { }
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, outcome.status)
            val batchId = requireNotNull(outcome.recoveryBatchId)
            val deleted = KeePassLosslessTransfer.removeEntry(readFile(), originalId)
            replaceFile(deleted)
            // KDBX 4 stores binary timestamps in seconds. Establish the actual on-disk
            // baseline before recovery; recovery itself must preserve that baseline exactly.
            val persistedDeletion = readFile()
            assertEquals(deleted.content.deletedObjects.map { tombstone ->
                tombstone.copy(deletionTime = Instant.ofEpochSecond(tombstone.deletionTime.epochSecond))
            }, persistedDeletion.content.deletedObjects)
            assertTrue(persistedDeletion.content.deletedObjects.any { it.id == originalId })
            assertTrue(nativeSession(persistedDeletion).entryNodes.none { it.entry.uuid == originalId })
            val batch = requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))
            val restored = adapter.restore(batch) { }
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, restored.status)
            assertEquals(1, restored.restoredCopyCount)
            val copyId = SingleVaultDedupKeePassAdapter.recoveryCopyId(batchId, originalId)
            val recovered = readFile()
            assertEquals(persistedDeletion.content.deletedObjects, recovered.content.deletedObjects)
            assertEquals(keeper, SingleVaultDedupKeePassContent.payload(recovered, keeperId))
            assertTrue(nativeSession(recovered).entryNodes.none { it.entry.uuid == originalId })
            assertEquals(1, nativeSession(recovered).entryNodes.count { it.entry.uuid == copyId && !it.isInRecycleBin })
            val bytes = file.readBytes()
            // Simulate a process ending after the file commit but before acknowledging recovery.
            assertEquals(1, db.singleVaultDedupRecoveryDao().transition(batchId, "RESTORED", "COMMITTED"))
            reopen()
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, adapter.restore(requireNotNull(db.singleVaultDedupRecoveryDao().get(batchId))) { }.status)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED, adapter.restore(batch) { }.status)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun completeMultiPasswordProjectsKeepProtectedFieldsFiveBlocksAndBothPasswordBindings() = runBlocking {
        scenario {
            val base = readFile().content.group.entries.first()
            fun project(): List<Entry> {
                val projectId = UUID.randomUUID().toString()
                val rows = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "synthetic-user",
                    passwords = listOf(ProjectCredentialGroup.Password(value = "synthetic-first-password"), ProjectCredentialGroup.Password(value = "synthetic-second-password")))))
                return rows.map { row -> base.copy(uuid = UUID.randomUUID(), fields = base.fields + mapOf(
                    "Password" to EntryValue.Encrypted(EncryptedValue.fromString(row.password.value)),
                    "MonicaPasswordGroupId" to EntryValue.Plain(projectId),
                    ProjectCredentialGroup.FIELD to EntryValue.Plain(row.metadata.forProject(projectId).raw.toString()))) }
            }
            val all = project() + project()
            replaceFile(readFile().modifyParentGroup { copy(entries = all) })
            val captured = adapter.capture(false) { }
            assertEquals(2, captured.projects.size)
            assertTrue(captured.projects.all { it.blockers.isEmpty() && it.summary.memberIds.size == 2 })
            val candidates = SingleVaultDedupComparator.analyze(captured.projects).candidates
            assertEquals(1, candidates.size)
            val keeperIds = captured.projects.single { it.summary.key == candidates.single().keeperKey }.summary.memberIds.map { all[(it - 1).toInt()].uuid }
            val before = readFile()
            val keeperPayloads = keeperIds.associateWith { SingleVaultDedupKeePassContent.payload(before, it) }
            val result = adapter.clean(captured, candidates) { }
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, result.status)
            val cleaned = readFile()
            assertEquals(2, nativeSession(cleaned).entryNodes.count { !it.isInRecycleBin })
            keeperPayloads.forEach { (id, expected) -> assertEquals(expected, SingleVaultDedupKeePassContent.payload(cleaned, id)) }
            assertEquals(setOf("synthetic-first-password", "synthetic-second-password"), nativeSession(cleaned).entryNodes.filterNot { it.isInRecycleBin }.map { it.entry.fields["Password"]!!.content }.toSet())
            assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, adapter.restore(requireNotNull(db.singleVaultDedupRecoveryDao().get(requireNotNull(result.recoveryBatchId)))) { }.status)
            val restored = readFile()
            all.forEach { assertEquals(SingleVaultDedupKeePassContent.payload(before, it.uuid), SingleVaultDedupKeePassContent.payload(restored, it.uuid)) }
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        val cache = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val old = cache.get(null)
        val wasUnlocked = SessionManager.isUnlocked.value
        val key = ByteArray(32) { (it + 61).toByte() }
        cache.set(null, key)
        SessionManager.markUnlocked()
        val fixture = Fixture()
        try { fixture.initialize(); fixture.block() }
        finally {
            fixture.close()
            if (!wasUnlocked) SessionManager.markLocked()
            cache.set(null, old); key.fill(0)
        }
    }

    private inner class Fixture {
        val name = "dedup-native-${UUID.randomUUID()}"
        val id = 7_000_000L + (UUID.randomUUID().leastSignificantBits and 0xFFFFFL)
        val file = File(context.filesDir, "$name.kdbx")
        val password = "Synthetic native dedup password"
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val security = SecurityManager(context)
        var db = Room.databaseBuilder(context, PasswordDatabase::class.java, "$name.db").build()
        var service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
        var adapter = SingleVaultDedupKeePassAdapter(context, db, security, id, "Synthetic native dedup")
        suspend fun initialize() {
            val now = Instant.parse("2026-11-06T00:00:00Z")
            val binary = BinaryData.Uncompressed(true, "synthetic attachment bytes".toByteArray())
            var drafts = emptyList<CustomFieldDraft>()
            val blocks = PasswordContentBlocks.Kind.entries.map { kind -> PasswordContentBlocks.create(kind).edited(kind.name,
                when (kind) {
                    PasswordContentBlocks.Kind.API_KEY -> mapOf("key" to "synthetic-api-key", "url" to "https://example.invalid/api")
                    PasswordContentBlocks.Kind.API_TOKEN -> mapOf("provider" to "synthetic", "token" to "synthetic-token")
                    PasswordContentBlocks.Kind.SSH_KEY -> mapOf("privateKeyOpenSsh" to "synthetic-not-a-real-ssh-key", "comment" to "test")
                    PasswordContentBlocks.Kind.GPG_KEY -> mapOf("privateKey" to "synthetic-not-a-real-gpg-key", "userId" to "test@example.invalid")
                    PasswordContentBlocks.Kind.QR_CODE -> mapOf("content" to "synthetic QR content")
                }) }
            blocks.forEach { drafts = PasswordContentBlocks.put(drafts, it) }
            drafts = EntryContentFields.withOrder(drafts, blocks.map { PasswordContentBlocks.token(it.id) })
            val fields = EntryFields.of("Title" to EntryValue.Plain("Synthetic duplicate"), "UserName" to EntryValue.Plain("synthetic-user"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-password")), "URL" to EntryValue.Plain("https://example.invalid"), "Notes" to EntryValue.Plain(""),
                "Secondary password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-secondary-password")),
                "Custom address" to EntryValue.Plain("Synthetic Street")) + EntryFields(drafts.associate { it.title to
                    if (it.isProtected) EntryValue.Encrypted(EncryptedValue.fromString(it.value)) else EntryValue.Plain(it.value) })
            val history = Entry(UUID.randomUUID(), fields = fields + ("Password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-old-password"))),
                times = TimeData(now, now, now, now, null), binaries = listOf(BinaryReference(binary.hash, "historic.txt")))
            val first = Entry(UUID.randomUUID(), fields = fields, times = TimeData(now, now, now, now, null), history = listOf(history), binaries = listOf(BinaryReference(binary.hash, "current.txt")))
            val created = KeePassDatabase.Ver4x.create("Synthetic", Meta(recycleBinEnabled = true), credentials)
            val seed = when (val kdf = created.header.kdfParameters) { is KdfParameters.Aes -> kdf.seed; is KdfParameters.Argon2 -> kdf.salt }
            val data = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(10uL, seed)))
                .modifyParentGroup { copy(entries = listOf(first, first.copy(uuid = UUID.randomUUID()))) }.modifyBinaries { mapOf(binary.hash to binary) }
            file.outputStream().use { KeePassCodecSupport.encode(data, it) }
            db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = id, name = name, filePath = file.name,
                storageLocation = KeePassStorageLocation.INTERNAL, sourceType = KeePassDatabaseSourceType.LOCAL_INTERNAL,
                encryptedPassword = security.encryptData(password)))
            service.openNativeSession(id).getOrThrow()
            val initialRead = service.readNativeForDedup(id)
            assertTrue("Normal native fixture must have complete XML/header evidence", initialRead.xmlComplete)
            assertTrue("Normal native fixture must be writable with recycling", initialRead.canClean)
        }
        fun readFile(): KeePassDatabase = file.inputStream().use { KeePassCodecSupport.decode(it, credentials) }
        suspend fun replaceFile(data: KeePassDatabase) {
            file.outputStream().use { KeePassCodecSupport.encode(data, it) }
            KeePassKdbxService.invalidateProcessCache(id)
            service.openNativeSession(id).getOrThrow()
        }
        fun nativeSession(data: KeePassDatabase) = KeePassNativeSessionBuilder.build(id, KeePassSourceRevision("test", 1), data) { parent, group ->
            if (parent == null) group else "$parent/$group"
        }
        suspend fun reopen() {
            KeePassKdbxService.invalidateProcessCache(id)
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, "$name.db").build()
            service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            adapter = SingleVaultDedupKeePassAdapter(context, db, security, id, "Synthetic native dedup")
            service.openNativeSession(id).getOrThrow()
        }
        fun close() {
            KeePassKdbxService.invalidateProcessCache(id)
            val recoveryIds = db.openHelper.readableDatabase.query("SELECT id FROM single_vault_dedup_recovery").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            recoveryIds.forEach { batchId ->
                check(UUID.fromString(batchId).toString() == batchId)
                val root = File(context.noBackupFilesDir, SingleVaultDedupPayloadStore.DIRECTORY).canonicalFile
                val payload = File(root, "$batchId.sealed")
                check(payload.canonicalFile.parentFile == root)
                payload.delete()
            }
            db.close(); context.deleteDatabase("$name.db")
            check(file.canonicalFile.parentFile == context.filesDir.canonicalFile)
            file.delete()
        }
    }
}
