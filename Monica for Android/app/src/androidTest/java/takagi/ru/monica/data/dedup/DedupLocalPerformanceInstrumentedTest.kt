package takagi.ru.monica.data.dedup

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.io.File
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Diagnostic benchmark of the screenshot's 854 sources -> 848 local writes; no user vault is used. */
@RunWith(AndroidJUnit4::class)
class DedupLocalPerformanceInstrumentedTest {
    @Test fun diskMergeWithAndWithoutAnActiveListObserver(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wasUnlocked = SessionManager.isUnlocked.value
        val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val oldMdk = mdkField.get(null); val testMdk = ByteArray(32) { (it + 57).toByte() }
        mdkField.set(null, testMdk); SessionManager.markUnlocked()
        val security = SecurityManager(context)
        try { for (observeList in listOf(false, true)) {
            val name = "dedup-performance-${UUID.randomUUID()}.db"
            val sourceFile = File(context.filesDir, "$name.kdbx")
            val reads = AtomicInteger()
            val pointReads = AtomicInteger()
            val pointReadSql = "SELECT * FROM password_entries WHERE id = ?"
            val db = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
                .setQueryCallback({ sql, _ ->
                    val normalized = sql.trim().replace(Regex("\\s+"), " ")
                    if (normalized.startsWith("SELECT * FROM password_entries", ignoreCase = true)) {
                        // Destination verification reads one primary key after each insert. Count
                        // every other password query conservatively as a collection read.
                        if (normalized.equals(pointReadSql, ignoreCase = true)) pointReads.incrementAndGet()
                        else reads.incrementAndGet()
                    }
                }, Executor { it.run() }).build()
            try {
                val pointReadPlan = db.openHelper.readableDatabase.query(
                    "EXPLAIN QUERY PLAN $pointReadSql", arrayOf<Any>(1L)
                ).use { cursor -> buildList {
                    val detail = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(detail))
                }.joinToString("; ") }
                assertTrue("Verification must use the primary-key index: $pointReadPlan",
                    pointReadPlan.contains("SEARCH") && pointReadPlan.contains("USING INTEGER PRIMARY KEY"))
                val passwords = PasswordRepository(db.passwordEntryDao())
                val sourceEntries = List(848) { index -> Entry(UUID.randomUUID(), fields = EntryFields.of(
                    "Title" to EntryValue.Plain("Synthetic $index"), "URL" to EntryValue.Plain("https://fixture-$index.invalid"),
                    "UserName" to EntryValue.Plain("fixture-$index"), "Password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-secret-$index")),
                    "Notes" to EntryValue.Plain(""))) }
                val unlock = "Synthetic benchmark source"
                val created = KeePassDatabase.Ver4x.create("Synthetic", Meta(), Credentials.from(EncryptedValue.fromString(unlock)))
                val seed = when (val kdf = created.header.kdfParameters) { is KdfParameters.Aes -> kdf.seed; is KdfParameters.Argon2 -> kdf.salt }
                withContext(Dispatchers.IO) {
                    sourceFile.outputStream().use { created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(10uL, seed)))
                        .modifyParentGroup { copy(entries = sourceEntries + sourceEntries.take(6).map { it.copy(uuid = UUID.randomUUID()) }) }.encode(it) }
                }
                db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = 1, name = "Synthetic source", filePath = sourceFile.name,
                    encryptedPassword = security.encryptData(unlock)))
                val sourceBytes = sourceFile.readBytes()
                val nativeService = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                val nativeStore = KeePassMonicaPasswordStore(nativeService, security)
                passwords.monicaKeePassStore = nativeStore
                val sourceSnapshot = nativeStore.refresh(1)
                assertEquals(854, sourceSnapshot.rows.size)
                assertTrue(sourceSnapshot.sourceBrowser.entries.all { it.attachments.isEmpty() })
                assertTrue(db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(1).isEmpty())
                val service = DedupMergeService(passwords, SecureItemRepository(db.secureItemDao()),
                    PasskeyRepository(db.passkeyDao()), CustomFieldRepository(db.customFieldDao()),
                    db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(),
                    security, AppLocaleStringResolver(context), db)
                val observer = if (observeList) launch(Dispatchers.IO) { passwords.getAllPasswordEntries().collect {} } else null
                try {
                    val started = SystemClock.elapsedRealtime()
                    val plan = withContext(Dispatchers.Default) { service.buildPlan(setOf("keepass:1"), DedupMergeTarget.MonicaLocal) }
                    val scanned = SystemClock.elapsedRealtime()
                    assertEquals(854, plan.totalSourcePasswords)
                    assertEquals(848, plan.writableItems)
                    var firstProgressAt = 0L
                    reads.set(0)
                    pointReads.set(0)
                    val result = withContext(Dispatchers.IO) {
                        service.executePlan(plan) { progress ->
                            if (firstProgressAt == 0L) firstProgressAt = SystemClock.elapsedRealtime()
                            if (progress.completedItems % 200 == 0) Log.i("DedupBenchmark", "observer=$observeList committed=${progress.completedItems}/848 elapsed=${SystemClock.elapsedRealtime() - scanned}ms")
                        }
                    }
                    val finished = SystemClock.elapsedRealtime()
                    val executionReads = reads.get()
                    val verificationReads = pointReads.get()
                    assertEquals(848, result.insertedPasswords)
                    assertEquals(0, result.failedItems)
                    assertEquals("Every inserted password must still receive complete persisted-row verification",
                        result.insertedPasswords, verificationReads)
                    val all = db.passwordEntryDao().getActiveEntries().first()
                    assertEquals(0, all.count { it.keepassDatabaseId == 1L })
                    assertEquals(848, all.count { it.keepassDatabaseId == null })
                    assertEquals(854, nativeStore.refresh(1).rows.size)
                    assertArrayEquals(sourceBytes, sourceFile.readBytes())
                    Log.i("DedupBenchmark", "RESULT observer=$observeList sources=854 inserted=848 scan=${scanned-started}ms firstProgress=${firstProgressAt-scanned}ms execution=${finished-scanned}ms passwordCollectionReads=$executionReads passwordPrimaryKeyReads=$verificationReads primaryKeyPlan=$pointReadPlan")
                    assertTrue("A merge must not requery the complete vault for nearly every entry: $executionReads reads", executionReads < 60)
                } finally { observer?.cancelAndJoin() }
            } finally {
                KeePassKdbxService.invalidateProcessCache(1)
                db.close()
                context.deleteDatabase(name)
                check(sourceFile.canonicalFile.parentFile == context.filesDir.canonicalFile)
                sourceFile.delete()
            }
        } } finally {
            if (!wasUnlocked) SessionManager.markLocked()
            mdkField.set(null, oldMdk); testMdk.fill(0)
        }
    }
}
