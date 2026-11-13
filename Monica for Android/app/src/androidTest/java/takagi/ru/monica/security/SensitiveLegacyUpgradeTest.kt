package takagi.ru.monica.security

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real MDK crypto and Room transactions, exclusively inside private synthetic fixtures. */
@RunWith(AndroidJUnit4::class)
class SensitiveLegacyUpgradeTest {
    private val otp = "JBSWY3DPEHPK3PXP"
    private val otherOtp = "GEZDGNBVGY3TQOJQ"
    private val oldDate = Date(1_600_000_000_123)

    private class Fixture(val recovery: RecoveryTestFixture, val db: PasswordDatabase) {
        val context get() = recovery.context
        val security get() = recovery.manager
        val items get() = db.secureItemDao()
        val passwords get() = db.passwordEntryDao()
        fun manager(
            encryptor: SensitiveValueEncryptor = SensitiveValueEncryptor(security::encryptData),
            beforeCommit: suspend () -> Unit = {},
            secureDao: SecureItemDao = items,
            passwordDao: PasswordEntryDao = passwords,
        ) = SensitiveFieldMigrationManager(context, db, security, passwordDao, secureDao, encryptor, beforeCommit)
        suspend fun put(row: SecureItem): SecureItem = row.copy(id = items.insertItem(row))
        suspend fun put(row: PasswordEntry): PasswordEntry = row.copy(id = passwords.insert(row))
        suspend fun current(row: SecureItem) = requireNotNull(items.getItemById(row.id))
        suspend fun current(row: PasswordEntry) = requireNotNull(passwords.getPasswordEntryById(row.id))
        suspend fun assertUpgraded(row: SecureItem) {
            val actual = current(row)
            assertTrue(actual.itemData.startsWith("MDK|"))
            assertArrayEquals(row.itemData.toByteArray(Charsets.UTF_8), security.decryptData(actual.itemData).toByteArray(Charsets.UTF_8))
            assertEquals("Only the sensitive column may change", row, actual.copy(itemData = row.itemData))
        }
        suspend fun assertUpgraded(row: PasswordEntry) {
            val actual = current(row)
            assertTrue(actual.authenticatorKey.startsWith("MDK|"))
            assertArrayEquals(row.authenticatorKey.toByteArray(Charsets.UTF_8), security.decryptData(actual.authenticatorKey).toByteArray(Charsets.UTF_8))
            assertEquals("Dates, password, dirty flags and all metadata must survive", row, actual.copy(authenticatorKey = row.authenticatorKey))
        }
        suspend fun awaitUpgraded(row: SecureItem) = withTimeout(10_000) {
            while (!current(row).itemData.startsWith("MDK|")) delay(20)
            assertUpgraded(row)
        }
        fun reauthenticate() {
            assertTrue(security.unlockVaultWithPassword(recovery.password))
            SessionManager.markUnlocked()
            assertTrue(security.isVaultRuntimeUnlocked())
        }
    }

    private fun exercise(block: suspend Fixture.() -> Unit) = runBlocking {
        RecoveryTestFixture().use { recovery ->
            val db = Room.inMemoryDatabaseBuilder(recovery.context, PasswordDatabase::class.java).build()
            try {
                assertTrue(recovery.manager.isVaultRuntimeUnlocked())
                assertTrue(SessionManager.isUnlocked.value)
                Fixture(recovery, db).block()
            } finally { db.close() }
        }
    }

    private fun item(raw: String = otp, type: ItemType = ItemType.TOTP) = SecureItem(
        itemType = type, title = "Synthetic migration", itemData = raw, notes = "retained note",
        createdAt = oldDate, updatedAt = oldDate, isFavorite = true, sortOrder = 9,
    )
    private fun password(raw: String = otp) = PasswordEntry(
        title = "Synthetic bound OTP", username = "synthetic", website = "https://example.invalid",
        password = "unchanged-synthetic-password-column", authenticatorKey = raw, notes = "retained note",
        createdAt = oldDate, updatedAt = oldDate, isFavorite = true, sortOrder = 8,
    )

    @Test fun fullPassCrossesEveryDomainPageBoundaryIncludingArchiveAndTrashAndIsIdempotent() = exercise {
        val payloads = listOf(
            ItemType.TOTP to """ { "secret" : "$otp", "unknown":{"escaped":"\u4e2d","rows":[true,null,3]} } """,
            ItemType.BANK_CARD to """{"cardNumber":"4242424242424242","cardholderName":"Synthetic","expiryMonth":"12","expiryYear":"30","unknown":{"keep":"exact"}}""",
            ItemType.DOCUMENT to """{"documentType":"PASSPORT","documentNumber":"SYNTHETIC-001","fullName":"Synthetic","unknown":[{"x":1}]}""",
        )
        val rows = mutableListOf<SecureItem>()
        for ((type, raw) in payloads) repeat(65) { index ->
            rows += put(item(raw, type).copy(title = "Synthetic $type $index", isArchived = index % 3 == 1,
                archivedAt = oldDate.takeIf { index % 3 == 1 }, isDeleted = index % 3 == 2,
                deletedAt = oldDate.takeIf { index % 3 == 2 }))
        }
        val entries = (0..64).map { index -> put(password().copy(title = "Synthetic bound $index",
            isArchived = index % 3 == 1, archivedAt = oldDate.takeIf { index % 3 == 1 },
            isDeleted = index % 3 == 2, deletedAt = oldDate.takeIf { index % 3 == 2 })) }
        val writes = AtomicInteger()
        val migration = manager(encryptor = SensitiveValueEncryptor { writes.incrementAndGet(); security.encryptData(it) })
        migration.runUnlockedSmallBatch()
        rows.forEach { assertUpgraded(it) }
        entries.forEach { assertUpgraded(it) }
        assertEquals(260, writes.get())
        val firstItems = rows.map { current(it) }
        val firstEntries = entries.map { current(it) }
        migration.runUnlockedSmallBatch()
        assertEquals(260, writes.get())
        assertEquals(firstItems, rows.map { current(it) })
        assertEquals(firstEntries, entries.map { current(it) })
    }

    @Test fun observerIgnoresOldCompletedMarkersAndEncryptsLaterRestoredSameIds() = exercise {
        val progress = context.getSharedPreferences("monica_sensitive_field_migration", Context.MODE_PRIVATE)
        val edit = progress.edit().putInt("migration_version", 1)
        for (domain in listOf("totp_item_data", "bank_card_item_data", "document_item_data", "password_authenticator_key")) {
            edit.putBoolean("${domain}_completed", true).putLong("${domain}_last_id", 9000)
                .putInt("${domain}_failure_count", 2).putLong("${domain}_blocked_id", 7000)
        }
        assertTrue(edit.commit())
        val originalProgress = progress.all.toMap()
        val row = put(item().copy(id = 1))
        put(item(security.encryptData(otp)).copy(id = 100))
        coroutineScope {
            val observer = launch(Dispatchers.IO) { manager().observeWhileUnlocked() }
            try {
                awaitUpgraded(row)
                val restored = row.copy(itemData = otherOtp, title = "Synthetic restored same ID")
                items.updateItem(restored)
                awaitUpgraded(restored)
                val laterLowerId = put(item().copy(id = 2))
                awaitUpgraded(laterLowerId)
                assertEquals(originalProgress, progress.all)
                assertTrue(observer.isActive)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test fun roomChangesDuringInitialScanAreNotLostBelowItsCurrentId() = exercise {
        val initial = put(item().copy(id = 1))
        put(item("unrecognized old record").copy(id = 100))
        val first = AtomicBoolean(true)
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        coroutineScope {
            val observer = launch(Dispatchers.IO) {
                manager(beforeCommit = { if (first.getAndSet(false)) { reached.complete(Unit); resume.await() } }).observeWhileUnlocked()
            }
            try {
                withTimeout(10_000) { reached.await() }
                val insertedBehindSnapshot = put(item(otherOtp).copy(id = 2))
                resume.complete(Unit)
                awaitUpgraded(initial)
                awaitUpgraded(insertedBehindSnapshot)
            } finally { resume.complete(Unit); observer.cancelAndJoin() }
        }
    }

    @Test fun observerSurvivesOneScanFailureAndRetriesOnTheNextRoomChange() = exercise {
        val row = put(item())
        val actualDao = items
        val first = AtomicBoolean(true)
        val failed = CompletableDeferred<Unit>()
        val dao = object : SecureItemDao by actualDao {
            override suspend fun getItemDataMigrationBatch(itemType: ItemType, afterId: Long, limit: Int): List<SecureItem> {
                if (first.getAndSet(false)) {
                    failed.complete(Unit)
                    throw IllegalStateException("Synthetic first scan failure")
                }
                return actualDao.getItemDataMigrationBatch(itemType, afterId, limit)
            }
        }
        coroutineScope {
            val observer = launch(Dispatchers.IO) { manager(secureDao = dao).observeWhileUnlocked() }
            try {
                withTimeout(10_000) { failed.await() }
                assertEquals(row, current(row))
                val later = put(item(otherOtp))
                awaitUpgraded(row)
                awaitUpgraded(later)
                assertTrue("A failed scan must not terminate the Room subscription", observer.isActive)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test fun encryptedMalformedAmbiguousAndUnrelatedValuesRemainByteIdentical() = exercise {
        val values = listOf("MDK|broken", "V2|broken", "C2|broken", "V44|unknown", "A".repeat(40),
            Base64.getEncoder().encodeToString(ByteArray(60) { it.toByte() }), security.encryptData(otp),
            """{"secret":"MDK|inner-ciphertext"}""", """{"secret":{}}""", "unknown text", "", " ", "A".repeat(1048577))
        val rows = values.map { put(item(it)) } + put(item("{}", ItemType.BANK_CARD)) +
            put(item("{}", ItemType.DOCUMENT)) + put(item(otp, ItemType.NOTE))
        val entries = values.map { put(password(it)) } + put(password("""{"secret":"$otp"}"""))
        val calls = AtomicInteger()
        manager(encryptor = SensitiveValueEncryptor { calls.incrementAndGet(); security.encryptData(it) }).runUnlockedSmallBatch()
        assertEquals(0, calls.get())
        rows.forEach { assertEquals(it, current(it)) }
        entries.forEach { assertEquals(it, current(it)) }
    }

    @Test fun everyExternalOwnershipResiduePreventsMigrationEvenWithoutItsDatabaseId() = exercise {
        val i = item()
        val rows = listOf(i.copy(keepassDatabaseId = 51), i.copy(keepassEntryUuid = "synthetic-entry"),
            i.copy(keepassGroupUuid = "synthetic-group"), i.copy(keepassGroupPath = "Synthetic/Group"),
            i.copy(mdbxDatabaseId = 52), i.copy(mdbxFolderId = "synthetic-folder"),
            i.copy(bitwardenVaultId = 53), i.copy(bitwardenCipherId = "synthetic-cipher"),
            i.copy(bitwardenFolderId = "synthetic-folder"), i.copy(bitwardenRevisionDate = "synthetic-revision"),
            i.copy(bitwardenLocalModified = true), i.copy(bitwardenArchiveIntent = false), i.copy(syncStatus = "PENDING")).map { put(it) }
        val p = password()
        val entries = listOf(p.copy(keepassDatabaseId = 51), p.copy(keepassEntryUuid = "synthetic-entry"),
            p.copy(keepassGroupUuid = "synthetic-group"), p.copy(keepassGroupPath = "Synthetic/Group"),
            p.copy(mdbxDatabaseId = 52), p.copy(mdbxFolderId = "synthetic-folder"),
            p.copy(bitwardenVaultId = 53), p.copy(bitwardenCipherId = "synthetic-cipher"),
            p.copy(bitwardenFolderId = "synthetic-folder"), p.copy(bitwardenRevisionDate = "synthetic-revision"),
            p.copy(bitwardenLocalModified = true), p.copy(bitwardenCipherType = 2),
            p.copy(bitwardenCipherType = 3), p.copy(bitwardenCipherType = 4)).map { put(it) }
        val calls = AtomicInteger()
        manager(encryptor = SensitiveValueEncryptor { calls.incrementAndGet(); security.encryptData(it) }).runUnlockedSmallBatch()
        assertEquals(0, calls.get())
        rows.forEach { assertEquals(it, current(it)) }
        entries.forEach { assertEquals(it, current(it)) }
    }

    @Test fun unlockedFlagWithoutRuntimeMdkCannotStartMigration() = exercise {
        val row = put(item())
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue(SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        manager().runUnlockedSmallBatch()
        assertEquals(row, current(row))
        reauthenticate()
        manager().runUnlockedSmallBatch()
        assertUpgraded(row)
    }

    @Test fun encryptionFailureDoesNotWriteOrPreventLaterRowsAndCanRetry() = exercise {
        val failed = put(item())
        val later = put(item(otherOtp))
        val calls = AtomicInteger()
        manager(encryptor = SensitiveValueEncryptor {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("Synthetic encryption failure")
            security.encryptData(it)
        }).runUnlockedSmallBatch()
        assertEquals(2, calls.get())
        assertEquals(failed, current(failed))
        assertUpgraded(later)
        manager().runUnlockedSmallBatch()
        assertUpgraded(failed)
    }

    @Test fun wrongRoundtripAndPlaintextEncryptorOutputNeverCommit() = exercise {
        val row = put(item())
        val commits = AtomicInteger()
        manager(encryptor = SensitiveValueEncryptor { security.encryptData("different synthetic value") },
            beforeCommit = { commits.incrementAndGet() }).runUnlockedSmallBatch()
        assertEquals(row, current(row))
        manager(encryptor = SensitiveValueEncryptor { it }, beforeCommit = { commits.incrementAndGet() }).runUnlockedSmallBatch()
        assertEquals(row, current(row))
        assertEquals(0, commits.get())
    }

    @Test fun concurrentMetadataEditWithUnchangedSensitiveBytesDefersTheEntireRow() = exercise {
        val row = put(item())
        val newer = row.copy(title = "Synthetic edited title", notes = "Synthetic edited notes", updatedAt = Date(oldDate.time + 1))
        val calls = AtomicInteger()
        manager(beforeCommit = { calls.incrementAndGet(); items.updateItem(newer) }).runUnlockedSmallBatch()
        assertEquals(1, calls.get())
        assertEquals(newer, current(row))
        manager().runUnlockedSmallBatch()
        assertUpgraded(newer)
    }

    @Test fun concurrentMovesToEveryExternalStoreArePreserved() = exercise {
        for (destination in 0..2) {
            val row = put(item())
            val moved = when (destination) {
                0 -> row.copy(keepassDatabaseId = 51, keepassEntryUuid = "synthetic-entry")
                1 -> row.copy(mdbxDatabaseId = 52, mdbxFolderId = "synthetic-folder")
                else -> row.copy(bitwardenVaultId = 53, bitwardenCipherId = "synthetic-cipher")
            }
            val reached = AtomicInteger()
            manager(beforeCommit = { reached.incrementAndGet(); items.updateItem(moved) }).runUnlockedSmallBatch()
            assertEquals(1, reached.get())
            assertEquals(moved, current(row))
        }
    }

    @Test fun concurrentBoundOtpOwnershipResidueIsNotOverwritten() = exercise {
        val row = put(password())
        val moved = row.copy(mdbxFolderId = "synthetic-new-source", updatedAt = Date(oldDate.time + 1))
        val reached = AtomicInteger()
        manager(beforeCommit = { reached.incrementAndGet(); passwords.updatePasswordEntry(moved) }).runUnlockedSmallBatch()
        assertEquals(1, reached.get())
        assertEquals(moved, current(row))
    }

    @Test fun concurrentDeletionIsNotRecreatedAndSoftDeletionIsDeferredUntilNextPass() = exercise {
        val deleted = put(item())
        manager(beforeCommit = { items.deleteItemById(deleted.id) }).runUnlockedSmallBatch()
        assertNull(items.getItemById(deleted.id))
        val trashed = put(item())
        val newerTrash = trashed.copy(isDeleted = true, deletedAt = oldDate)
        manager(beforeCommit = { items.updateItem(newerTrash) }).runUnlockedSmallBatch()
        assertEquals(newerTrash, current(trashed))
        manager().runUnlockedSmallBatch()
        assertUpgraded(newerTrash)
        assertNull(items.getItemById(deleted.id))
    }

    @Test fun cancellingAfterEncryptionBeforeCommitKeepsOriginalRows() = exercise {
        val row = put(item())
        val reached = CompletableDeferred<Unit>()
        coroutineScope {
            val worker = launch(Dispatchers.IO) { manager(beforeCommit = { reached.complete(Unit); awaitCancellation() }).runUnlockedSmallBatch() }
            try { withTimeout(10_000) { reached.await() } } finally { worker.cancelAndJoin() }
            assertTrue(worker.isCancelled)
        }
        assertEquals(row, current(row))
        manager().runUnlockedSmallBatch()
        assertUpgraded(row)
    }

    @Test fun lockAndReunlockBeforeCommitCannotReuseTheOldAuthorization() = exercise {
        val row = put(item())
        val originalGeneration = SessionManager.lockGeneration.value
        val reached = AtomicInteger()
        manager(beforeCommit = { reached.incrementAndGet(); SessionManager.markLocked(); reauthenticate() }).runUnlockedSmallBatch()
        assertEquals(1, reached.get())
        assertTrue(SessionManager.isUnlocked.value)
        assertTrue(SessionManager.lockGeneration.value > originalGeneration)
        assertEquals(row, current(row))
        manager().runUnlockedSmallBatch()
        assertUpgraded(row)
    }

    @Test fun runtimeKeyGenerationChangeWithoutAppLockStillRejectsThePreparedWrite() = exercise {
        val row = put(item())
        val originalApp = SessionManager.lockGeneration.value
        val originalKey = SecurityManager.dataReadGeneration.value
        val reached = AtomicInteger()
        manager(beforeCommit = { reached.incrementAndGet(); SecurityManager.clearRuntimeUnlockCache(); reauthenticate() }).runUnlockedSmallBatch()
        assertEquals(1, reached.get())
        assertEquals(originalApp, SessionManager.lockGeneration.value)
        assertTrue(SecurityManager.dataReadGeneration.value > originalKey)
        assertTrue(SecurityManager.hasRuntimeUnlockCache())
        assertEquals(row, current(row))
        manager().runUnlockedSmallBatch()
        assertUpgraded(row)
    }

    @Test fun lockAfterColumnCasRollsBackTheTransaction() = exercise {
        val row = put(item())
        val actualDao = items
        val reached = AtomicInteger()
        val dao = object : SecureItemDao by actualDao {
            override fun compareAndSetItemDataForSensitiveMigration(id: Long, expected: String, replacement: String): Int {
                val changed = actualDao.compareAndSetItemDataForSensitiveMigration(id, expected, replacement)
                reached.incrementAndGet()
                assertEquals(1, changed)
                SessionManager.markLocked()
                return changed
            }
        }
        manager(secureDao = dao).runUnlockedSmallBatch()
        assertEquals(1, reached.get())
        assertEquals(row, current(row))
        reauthenticate()
        manager().runUnlockedSmallBatch()
        assertUpgraded(row)
    }

    @Test fun cancellationAfterBoundOtpCasRollsBackTheTransaction() = exercise {
        val row = put(password())
        val actualDao = passwords
        val reached = AtomicInteger()
        coroutineScope {
            lateinit var worker: Job
            val dao = object : PasswordEntryDao by actualDao {
                override fun compareAndSetAuthenticatorKeyForSensitiveMigration(id: Long, expected: String, replacement: String): Int {
                    val changed = actualDao.compareAndSetAuthenticatorKeyForSensitiveMigration(id, expected, replacement)
                    assertEquals(1, changed)
                    reached.incrementAndGet()
                    worker.cancel()
                    return changed
                }
            }
            worker = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { manager(passwordDao = dao).runUnlockedSmallBatch() }
            worker.start()
            withTimeout(10_000) { worker.join() }
            assertTrue(worker.isCancelled)
        }
        assertEquals(1, reached.get())
        assertEquals(row, current(row))
        manager().runUnlockedSmallBatch()
        assertUpgraded(row)
    }
}
