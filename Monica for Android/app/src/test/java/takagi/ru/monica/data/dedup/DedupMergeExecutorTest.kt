package takagi.ru.monica.data.dedup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.utils.StringResolver

class DedupMergeExecutorTest {
    private val strings = StringResolver { id, arguments ->
        when (id) {
            R.string.dedup_merge_untitled_password -> "Unbenanntes Passwort"
            R.string.dedup_merge_type_note -> "Notiz"
            R.string.dedup_merge_changed_review -> "Review required"
            R.string.dedup_merge_rollback_failed -> "${arguments[0]}; Rückgängig machen fehlgeschlagen: ${arguments[1]}"
            else -> error("Unexpected string resource: $id")
        }
    }

    @Test
    fun executionContinuesAfterIndependentFailuresAndReportsEveryItem() = runBlocking {
        val writer = FakeWriter(
            failingPasswords = setOf("Broken password"),
            failingSecureItems = setOf("Broken note")
        )
        val progress = mutableListOf<DedupMergeExecutionProgress>()

        val result = DedupMergeExecutor(writer, strings).execute(
            passwords = listOf(password("Broken password"), password("Working password")),
            secureItems = listOf(note("Broken note")),
            skippedExistingPasswords = 2,
            skippedExistingSecureItems = 1,
            skippedUnsupportedPasskeys = 4,
            targetLabel = "Target",
            onProgress = progress::add
        )

        assertEquals(1, result.insertedPasswords)
        assertEquals(0, result.insertedSecureItems)
        assertEquals(1, result.failedPasswords)
        assertEquals(1, result.failedSecureItems)
        assertEquals(2, result.failures.size)
        assertTrue(result.hasPartialFailure)
        assertEquals(3, progress.last().completedItems)
        assertEquals(3, progress.last().totalItems)
    }

    @Test
    fun cancellationStopsTheRemainingQueue() {
        val writer = FakeWriter(cancelOnPassword = "Stop")
        var cancelled = false

        try {
            runBlocking {
                DedupMergeExecutor(writer, strings).execute(
                    passwords = listOf(password("Stop"), password("Never written")),
                    secureItems = emptyList(),
                    skippedExistingPasswords = 0,
                    skippedExistingSecureItems = 0,
                    skippedUnsupportedPasskeys = 0,
                    targetLabel = "Target"
                )
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf("Stop"), writer.passwordAttempts)
    }

    @Test
    fun localizedFallbackLabelsHidePotentiallySensitiveProviderErrors() = runBlocking {
        val writer = object : DedupMergeWriter {
            override suspend fun writePassword(resolved: DedupResolvedPassword) {
                throw IllegalStateException("write failed").apply {
                    addSuppressed(IllegalStateException("rollback failed"))
                }
            }

            override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) = Unit
            override suspend fun writePasskey(resolved: DedupResolvedPasskey) = Unit
        }
        val progress = mutableListOf<DedupMergeExecutionProgress>()

        val result = DedupMergeExecutor(writer, strings).execute(
            passwords = listOf(password("")),
            secureItems = listOf(note("")),
            skippedExistingPasswords = 0,
            skippedExistingSecureItems = 0,
            skippedUnsupportedPasskeys = 0,
            targetLabel = "My database",
            onProgress = progress::add
        )

        assertEquals("Unbenanntes Passwort", result.failures.single().label)
        assertEquals(
            "Review required; Rückgängig machen fehlgeschlagen: Review required",
            result.failures.single().reason
        )
        assertEquals(listOf("Unbenanntes Passwort", "Notiz"), progress.map { it.currentLabel })
        assertEquals("My database", result.targetLabel)
        assertEquals(1, result.insertedSecureItems)
    }

    @Test fun projectFailureRollsBackAllMembersWithoutRetryingRows() = runBlocking {
        val stored = mutableListOf<String>()
        var attempts = 0
        val writer = object : DedupMergeWriter {
            override suspend fun writeLocalBatch(block: suspend () -> Unit): Boolean {
                val before = stored.toList()
                try { block() } catch (error: Exception) { stored.clear(); stored.addAll(before); throw error }
                return true
            }
            override suspend fun writePassword(resolved: DedupResolvedPassword) {
                attempts++
                if (resolved.entry.password == "fail") error("synthetic secret must not leak")
                stored += resolved.entry.password
            }
            override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) = Unit
            override suspend fun writePasskey(resolved: DedupResolvedPasskey) = Unit
        }
        val first = password("First").copy(projectCopyKey = "one-project")
        val second = password("Second").let { it.copy(entry = it.entry.copy(password = "fail"), projectCopyKey = "one-project") }
        val result = DedupMergeExecutor(writer, strings).execute(listOf(first, second), emptyList(), 0, 0, 0, "Target")
        assertEquals(0, result.insertedItems)
        assertEquals(2, result.failedPasswords)
        assertEquals(2, attempts)
        assertTrue(stored.isEmpty())
        assertTrue(result.failures.all { it.reason == "Review required" })
    }

    @Test
    fun fileAndPrivateKeyWritesStayOutsideRetriableRoomTransactions() = runBlocking {
        var inTransaction = false
        val observed = mutableListOf<Pair<String, Boolean>>()
        val writer = object : DedupMergeWriter {
            override suspend fun writeLocalBatch(block: suspend () -> Unit): Boolean {
                inTransaction = true
                try { block() } finally { inTransaction = false }
                return true
            }
            override suspend fun writePassword(resolved: DedupResolvedPassword) {
                observed += resolved.entry.title to inTransaction
            }
            override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) {
                observed += resolved.item.title to inTransaction
            }
            override suspend fun writePasskey(resolved: DedupResolvedPasskey) {
                observed += "Passkey" to inTransaction
            }
        }
        val attached = password("Attachment").copy(attachments = listOf(DedupAttachmentRef(1, "hash")))
        val mdbx = password("MDBX").let { it.copy(entry = it.entry.copy(mdbxDatabaseId = 1)) }
        val photo = note("Photo").let { it.copy(item = it.item.copy(imagePaths = "[\"synthetic.jpg\"]")) }
        val key = DedupResolvedPasskey("key", PasskeyEntry(credentialId = "id", rpId = "example.invalid",
            rpName = "Example", userId = "user", userName = "User", userDisplayName = "User",
            publicKey = "synthetic", privateKeyAlias = "synthetic"), listOf(1), listOf("Source"), "Source")

        val rejected = runCatching { DedupMergeExecutor(writer, strings).execute(
            listOf(password("Local"), attached, mdbx), listOf(note("Note"), photo),
            0, 0, 0, "Target", passkeys = listOf(key.copy(skipReason = null))) }
        assertTrue(rejected.isFailure)
        assertTrue(observed.isEmpty())
        val result = DedupMergeExecutor(writer, strings).execute(
            listOf(password("Local"), attached, mdbx), listOf(note("Note"), photo), 0, 0, 0, "Target")

        assertEquals(5, result.insertedItems)
        assertEquals(listOf("Local" to true, "Attachment" to false, "MDBX" to false,
            "Note" to true, "Photo" to false), observed)
    }

    private fun password(title: String) = DedupResolvedPassword(
        mergeKey = title,
        entry = PasswordEntry(title = title, website = "example.com", username = title, password = "secret"),
        customFields = emptyList(),
        sourceEntryIds = listOf(1L),
        sourceLabels = listOf("Source"),
        conflictFields = emptySet()
    )

    private fun note(title: String) = DedupResolvedSecureItem(
        mergeKey = title,
        item = SecureItem(itemType = ItemType.NOTE, title = title, itemData = "{}"),
        sourceItemIds = listOf(1L),
        sourceLabels = listOf("Source"),
        conflictFields = emptySet()
    )

    private class FakeWriter(
        private val failingPasswords: Set<String> = emptySet(),
        private val failingSecureItems: Set<String> = emptySet(),
        private val cancelOnPassword: String? = null
    ) : DedupMergeWriter {
        val passwordAttempts = mutableListOf<String>()
        override suspend fun writePasskey(resolved: DedupResolvedPasskey) = Unit

        override suspend fun writePassword(resolved: DedupResolvedPassword) {
            val title = resolved.entry.title
            passwordAttempts += title
            if (title == cancelOnPassword) throw CancellationException("cancel")
            if (title in failingPasswords) error("password write failed")
        }

        override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) {
            if (resolved.item.title in failingSecureItems) error("secure item write failed")
        }
    }
}
