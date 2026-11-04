package takagi.ru.monica.bitwarden

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue
import takagi.ru.monica.bitwarden.sync.BitwardenSyncQueueReader
import takagi.ru.monica.security.SecurityManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.bitwarden.sync.SyncTriggerReason
import takagi.ru.monica.bitwarden.ui.BitwardenSettingsScreen
import takagi.ru.monica.bitwarden.ui.BitwardenSyncQueueScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class BitwardenSyncQueueInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun roomQueueIncludesRealDirtyRowsAndOperationsAndIsolatesCollidingLocalIds() = withFixture { f ->
        runBlocking {
            val dao = f.database.bitwardenPendingOperationDao()
            val operation = dao.insert(BitwardenPendingOperation(vaultId = f.first, entryId = f.other.id,
                bitwardenCipherId = "foreign-local-id", itemType = "PASSWORD", operationType = "DELETE",
                targetType = "CIPHER", payloadJson = "SECRET_PAYLOAD", lastError = "SECRET_ERROR", status = "FAILED"))
            dao.insert(BitwardenPendingOperation(vaultId = f.first, bitwardenCipherId = "completed",
                operationType = "DELETE", targetType = "CIPHER", payloadJson = "{}", status = "COMPLETED"))
            f.database.bitwardenSendDao().upsert(BitwardenSend(vaultId = f.first, bitwardenSendId = "send-pending",
                accessId = "synthetic", name = "Synthetic Send confirmation", isDirty = true))
            val rows = dao.observeQueueRecords(f.first).first()
            assertTrue(rows.any { it.title == f.pending.title && it.operation == "CREATE" })
            assertTrue(rows.any { it.itemId == "send-pending" && it.operation == "CONFIRM" })
            assertNull(rows.single { it.queueKey == "operation:$operation" }.title)
            assertFalse(rows.any { it.title == f.other.title || it.itemId == "completed" })
            assertFalse(rows.toString().contains("SECRET_"))
            f.database.passwordEntryDao().update(f.pending.copy(bitwardenCipherId = "accepted", bitwardenLocalModified = false))
            assertFalse(dao.observeQueueRecords(f.first).first().any { it.queueKey == "password:${f.pending.id}" })
        }
    }

    @Test fun actualSettingsOpensLiveQueueCountsChangeAndRetryTargetsOnlyItsAccount() = withFixture { f ->
        var showQueue by mutableStateOf(false)
        compose.setContent { MonicaTheme { Surface {
            if (showQueue) BitwardenSyncQueueScreen(f.model) { showQueue = false }
            else BitwardenSettingsScreen(f.model, onNavigateBack = {}, onNavigateToLogin = {},
                onNavigateToVault = {}, onNavigateToAccount = {}, onNavigateToSyncQueue = { showQueue = true })
        } } }
        compose.waitUntil(10000) { f.model.syncQueueSummary.value.pending == 2 }
        compose.onNodeWithTag("bitwarden_sync_queue_entry").performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_real_sync_queue").assertIsDisplayed()
        compose.onNodeWithText(f.pending.title).performScrollTo().assertIsDisplayed()
        runBlocking { f.database.passwordEntryDao().update(f.pending.copy(bitwardenCipherId = "accepted", bitwardenLocalModified = false)) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(f.pending.title).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { showQueue = false }
        compose.waitUntil(10000) { f.model.syncQueueSummary.value.pending == 1 }
        compose.onNodeWithTag("bitwarden_sync_queue_entry").performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_queue_retry_${f.first}").performScrollTo().performClick()
        compose.waitUntil(10000) { f.model.syncStatusByVault.value[f.first]?.lastTriggerReason == SyncTriggerReason.MANUAL }
        assertNotEquals(SyncTriggerReason.MANUAL, f.model.syncStatusByVault.value[f.second]?.lastTriggerReason)
    }

    @Test fun actualQueueLockRemovesPrivateRowsAndIssueSheetAndRejectsLateReports() = withFixture { f ->
        val report = f.reports.begin(f.first)
        report.record("synthetic-failed", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.UNSUPPORTED,
            "Synthetic failure title")
        report.finish()
        compose.setContent { MonicaTheme { Surface { BitwardenSyncQueueScreen(f.model) {} } } }
        // A failure report adds a row above the pending items; LazyColumn may not compose the title yet.
        compose.waitUntil(10000) { f.model.syncQueueByVault.value[f.first]?.let {
            f.model.isSyncQueueCurrent(it) && it.items.any { row -> row.title == f.pending.title }
        } == true }
        compose.onNodeWithTag("bitwarden_real_sync_queue").performScrollToNode(hasText(f.pending.title))
        compose.onNodeWithText(f.pending.title).assertIsDisplayed()
        compose.onNodeWithTag("bitwarden_queue_issues_${f.first}").performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_sync_issues").assertIsDisplayed()
        val late = f.reports.begin(f.first)
        runBlocking { f.repository.forceLock(f.first) }
        late.record("late", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED, "Late secret title")
        late.finish()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("bitwarden_sync_issues").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(f.pending.title).assertDoesNotExist()
        compose.onNodeWithText("Late secret title").assertDoesNotExist()
        assertTrue(f.model.syncQueueByVault.value[f.first]?.items.isNullOrEmpty())
        compose.onNodeWithTag("bitwarden_real_sync_queue").performScrollToNode(hasText(f.other.title))
        compose.onNodeWithText(f.other.title).assertIsDisplayed()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.waitUntil(10000) { compose.onAllNodesWithText(f.other.title).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun durableAttachmentIntentListsOnlyExactLiveOwnerAndDisappearsAfterPromotion() = withFixture { f -> runBlocking {
        val dao = f.database.attachmentDao()
        fun metadata(owner: Long, name: String) = Attachment(parentPasswordId = owner, source = "LOCAL",
            fileName = name, mimeType = "application/octet-stream", sizeBytes = 4, downloadState = "DOWNLOADED",
            createdAt = 1, updatedAt = 1, localPath = "SYNTHETIC_PATH_MUST_NOT_APPEAR", wrappedCek = "SYNTHETIC_CEK_MUST_NOT_APPEAR")
        val explicit = metadata(f.pending.id, "Synthetic pending attachment").let { it.copy(id = dao.insert(it)) }
        val unrelated = metadata(f.pending.id, "Synthetic unqueued attachment").let { it.copy(id = dao.insert(it)) }
        val foreign = metadata(f.other.id, "Synthetic foreign attachment").let { it.copy(id = dao.insert(it)) }
        val prefs = f.context.getSharedPreferences("credential_import_attachments_v1", 0)
        assertTrue(prefs.edit().putStringSet("vault:${f.first}", setOf(explicit.id.toString(), foreign.id.toString(), "01", "-1", "bad")).commit())
        val recreated = BitwardenImportAttachmentQueue(f.context)
        val ids = withTimeout(10000) { recreated.observePendingIds(f.first).first() }
        assertEquals(setOf(explicit.id, foreign.id), ids)
        val reader = BitwardenSyncQueueReader(f.database, f.repository, f.context)
        suspend fun attachments() = withTimeout(10000) { reader.observeAll().first {
            it[f.first]?.let { row -> !row.loading && !row.locked && !row.failed } == true
        }.getValue(f.first).items.filter { it.kind == "ATTACHMENT" } }
        val rows = attachments()
        assertEquals(listOf("attachment:${explicit.id}"), rows.map { it.queueKey })
        assertEquals("local:${f.pending.id}/attachment:${explicit.id}", rows.single().itemId)
        assertEquals(explicit.fileName, rows.single().title)
        assertFalse(rows.toString().contains("MUST_NOT_APPEAR"))
        assertFalse(rows.any { it.title == unrelated.fileName || it.title == foreign.fileName })
        f.database.passwordEntryDao().update(f.pending.copy(isDeleted = true))
        assertTrue(attachments().isEmpty())
        f.database.passwordEntryDao().update(f.pending)
        assertEquals(1, attachments().size)
        dao.update(explicit.copy(source = "BITWARDEN", bitwardenAttachmentId = "accepted"))
        assertTrue(attachments().isEmpty())
    } }

    @Test fun queueResolvesSealedSendTitleAndNeverFallsBackToUnreadableRawName() = withFixture { f -> runBlocking {
        f.unlockLocal()
        val send = BitwardenSend(vaultId = f.first, bitwardenSendId = "sealed-pending", accessId = "synthetic",
            name = "Synthetic readable Send title", textContent = "Synthetic text", isDirty = true,
            keyBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 33 }),
            shareUrl = "http://127.0.0.1:1/#/send/synthetic/" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 33 }))
        BitwardenSendCache(f.context).upsert(send)
        val raw = f.database.bitwardenSendDao().getSendsByVault(f.first).single()
        assertEquals("", raw.name); assertNotNull(raw.encryptedCache)
        val reader = BitwardenSyncQueueReader(f.database, f.repository, f.context)
        suspend fun snapshot() = withTimeout(10000) { reader.observeAll().first {
            it[f.first]?.let { row -> !row.loading && !row.locked && !row.failed } == true
        }.getValue(f.first) }
        assertEquals(send.name, snapshot().items.single { it.kind == "SEND" }.title)
        f.database.bitwardenSendDao().upsert(raw.copy(name = "RAW_CIPHERTEXT_MUST_NOT_APPEAR", encryptedCache = "MDK|invalid"))
        val unavailable = snapshot().items.single { it.kind == "SEND" }
        assertNull(unavailable.title)
        assertFalse(unavailable.toString().contains("RAW_CIPHERTEXT"))
        f.repository.forceLock(f.first)
        val locked = withTimeout(10000) { reader.observeAll().first { it[f.first]?.locked == true }.getValue(f.first) }
        assertTrue(locked.items.isEmpty())
    } }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally {
            compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)); fixture.models.clear() }
            fixture.close()
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = PasswordDatabase.getDatabase(context)
        val repository = BitwardenRepository.getInstance(context)
        val models = ViewModelStore()
        private val previousAutoSync = repository.isAutoSyncEnabled
        private val previouslyUnlocked = SessionManager.isUnlocked.value
        private val security = SecurityManager(context)
        private val testMasterPassword = InstrumentationRegistry.getArguments().getString("testMasterPassword")
            ?: "Synthetic Bitwarden settings fixture 325!"
        private val keys = mutableListOf<SymmetricCryptoKey>()
        val first: Long
        val second: Long
        val pending: PasswordEntry
        val other: PasswordEntry
        val model: BitwardenViewModel
        val reports = repository.javaClass.getDeclaredField("syncIssues").apply { isAccessible = true }.get(repository) as BitwardenSyncIssues

        init {
            if (!security.isMasterPasswordSet()) check(security.setMasterPassword(testMasterPassword))
            check(security.unlockVaultWithPassword(testMasterPassword)) {
                "Existing test vault password differs from testMasterPassword; refusing to reset it"
            }
            unlockLocal()
            repository.isAutoSyncEnabled = false
            first = addAccount("First")
            second = addAccount("Second")
            pending = addEntry(first, "Synthetic pending first")
            other = addEntry(second, "Synthetic private second")
            model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[BitwardenViewModel::class.java]
        }

        fun unlockLocal() {
            if (!security.isVaultRuntimeUnlocked()) check(security.unlockVaultWithPassword(testMasterPassword)) {
                "Cannot unlock the existing test vault with testMasterPassword; refusing to reset it"
            }
            SessionManager.markUnlocked()
        }

        private fun addAccount(label: String): Long = runBlocking {
            val uuid = UUID.randomUUID().toString()
            val id = database.bitwardenVaultDao().insert(BitwardenVault(email = "$uuid@example.invalid",
                displayName = "Synthetic $label queue account", accountKey = uuid, userId = uuid, isLocked = false,
                serverUrl = "http://127.0.0.1:1", apiUrl = "http://127.0.0.1:1", identityUrl = "http://127.0.0.1:1"))
            val key = SymmetricCryptoKey(ByteArray(32) { 62 }, ByteArray(32) { 63 }).also(keys::add)
            cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
            cache<String>("accessTokenCache")[id] = "synthetic-queue-token"
            id
        }

        private fun addEntry(vaultId: Long, title: String): PasswordEntry = runBlocking {
            val entry = PasswordEntry(title = title, website = "", username = "synthetic", password = "",
                bitwardenVaultId = vaultId, bitwardenLocalModified = true)
            entry.copy(id = database.passwordEntryDao().insert(entry))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String) = repository.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(repository) as ConcurrentHashMap<Long, T>

        fun close() = runBlocking {
            listOf(first, second).forEach { id ->
                repository.forceLock(id)
                reports.clear(id)
                context.getSharedPreferences("credential_import_attachments_v1", 0).edit().remove("vault:$id").commit()
                database.bitwardenPendingOperationDao().deleteByVault(id)
                database.bitwardenSendDao().deleteByVault(id)
            }
            database.passwordEntryDao().delete(pending)
            database.passwordEntryDao().delete(other)
            database.bitwardenVaultDao().deleteById(first)
            database.bitwardenVaultDao().deleteById(second)
            repository.isAutoSyncEnabled = previousAutoSync
            keys.forEach { it.clear() }
            if (previouslyUnlocked) unlockLocal() else SessionManager.markLocked()
        }
    }
}
