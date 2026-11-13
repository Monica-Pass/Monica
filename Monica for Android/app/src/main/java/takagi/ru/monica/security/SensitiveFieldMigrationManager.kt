package takagi.ru.monica.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemDao
import java.util.concurrent.Callable

/**
 * Upgrades confirmed local legacy values after main-app authentication. A change
 * notification starts a fresh bounded-page scan, including restored old IDs.
 * Old completion preferences are deliberately neither read nor reset.
 */
class SensitiveFieldMigrationManager internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val database: PasswordDatabase,
    private val securityManager: SecurityManager,
    private val passwordEntryDao: PasswordEntryDao = database.passwordEntryDao(),
    private val secureItemDao: SecureItemDao = database.secureItemDao(),
    private val encryptor: SensitiveValueEncryptor = SensitiveValueEncryptor(securityManager::encryptData),
    private val beforeCommit: suspend () -> Unit = {},
) {
    private data class SessionStamp(val app: Long, val key: Long) {
        // Pure snapshots only: no PreparedSecureStore access while holding a DB transaction.
        fun isCurrent(): Boolean = SessionManager.isUnlocked.value &&
            SessionManager.lockGeneration.value == app &&
            SecurityManager.dataReadGeneration.value == key && SecurityManager.hasRuntimeUnlockCache()
    }

    /** Room subscribes before its initial emission, so inserts during the first scan are retained. */
    suspend fun observeWhileUnlocked(): Unit = withContext(Dispatchers.IO) {
        database.invalidationTracker.createFlow("secure_items", "password_entries")
            .conflate().catch { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Local field observer deferred: ${error.javaClass.simpleName}")
            }.collect {
                delay(200)
                try {
                    runUnlockedSmallBatch()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Local field scan deferred: ${error.javaClass.simpleName}")
                }
            }
    }

    /** Single-pass entry point; each pass visits every page, not only three rows. */
    suspend fun runUnlockedSmallBatch(): Unit = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            if (!SessionManager.isUnlocked.value || !securityManager.isVaultRuntimeUnlocked()) return@withLock
            val stamp = SessionStamp(SessionManager.lockGeneration.value, SecurityManager.dataReadGeneration.value)
            for (type in listOf(ItemType.TOTP, ItemType.BANK_CARD, ItemType.DOCUMENT)) {
                var after = 0L
                while (stamp.isCurrent()) {
                    currentCoroutineContext().ensureActive()
                    val rows = secureItemDao.getItemDataMigrationBatch(type, after, BATCH_SIZE)
                    if (rows.isEmpty()) break
                    for (row in rows) {
                        currentCoroutineContext().ensureActive()
                        if (!stamp.isCurrent()) return@withLock
                        if (isStrictlyLocal(row) && LegacySensitiveValuePolicy.acceptsItem(row.itemType, row.itemData)) {
                            upgrade(stamp, row.itemData, row,
                                read = { secureItemDao.getItemForSensitiveMigration(row.id) },
                                local = ::isStrictlyLocal,
                                write = { replacement -> secureItemDao.compareAndSetItemDataForSensitiveMigration(row.id, row.itemData, replacement) })
                        }
                        after = row.id
                    }
                    yield()
                }
            }
            var after = 0L
            while (stamp.isCurrent()) {
                currentCoroutineContext().ensureActive()
                val rows = passwordEntryDao.getAuthenticatorKeyMigrationBatch(after, BATCH_SIZE)
                if (rows.isEmpty()) break
                for (row in rows) {
                    currentCoroutineContext().ensureActive()
                    if (!stamp.isCurrent()) return@withLock
                    if (isStrictlyLocal(row) && LegacySensitiveValuePolicy.acceptsAuthenticatorKey(row.authenticatorKey)) {
                        upgrade(stamp, row.authenticatorKey, row,
                            read = { passwordEntryDao.getEntryForSensitiveMigration(row.id) },
                            local = ::isStrictlyLocal,
                            write = { replacement -> passwordEntryDao.compareAndSetAuthenticatorKeyForSensitiveMigration(row.id, row.authenticatorKey, replacement) })
                    }
                    after = row.id
                }
                yield()
            }
        }
    }

    private suspend fun <T : Any> upgrade(
        stamp: SessionStamp, raw: String, observed: T,
        read: () -> T?, local: (T) -> Boolean, write: (String) -> Int,
    ) {
        try {
            if (!stamp.isCurrent() || !securityManager.isDataReadCurrent(stamp.key)) return
            val encrypted = encryptor.encrypt(raw)
            // Preserve every original character, unknown member and escape; never reserialize.
            check(encrypted.startsWith("MDK|") && securityManager.decryptData(encrypted) == raw)
            beforeCommit()
            val caller = currentCoroutineContext()
            caller.ensureActive()
            if (!stamp.isCurrent() || !securityManager.isDataReadCurrent(stamp.key)) return
            database.runInTransaction(Callable {
                caller.ensureActive()
                check(stamp.isCurrent())
                val latest = read()
                if (latest == null || latest != observed || !local(latest)) return@Callable
                check(write(encrypted) == 1)
                caller.ensureActive()
                check(stamp.isCurrent())
                // No Session/runtime-key monitor inside the DB transaction. A lock after this
                // final check may let prepared ciphertext commit; no plaintext/key is published.
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Local field upgrade deferred: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "SensitiveMigration"
        private const val BATCH_SIZE = 64
        private val migrationMutex = Mutex()

        internal fun isStrictlyLocal(item: SecureItem): Boolean = item.id > 0 &&
            item.keepassDatabaseId == null && item.keepassEntryUuid.isNullOrEmpty() &&
            item.keepassGroupUuid.isNullOrEmpty() && item.keepassGroupPath.isNullOrEmpty() &&
            item.mdbxDatabaseId == null && item.mdbxFolderId.isNullOrEmpty() &&
            item.bitwardenVaultId == null && item.bitwardenCipherId.isNullOrEmpty() &&
            item.bitwardenFolderId.isNullOrEmpty() && item.bitwardenRevisionDate.isNullOrEmpty() &&
            !item.bitwardenLocalModified && item.bitwardenArchiveIntent == null && item.syncStatus == "NONE"

        internal fun isStrictlyLocal(entry: PasswordEntry): Boolean = entry.id > 0 &&
            entry.keepassDatabaseId == null && entry.keepassEntryUuid.isNullOrEmpty() &&
            entry.keepassGroupUuid.isNullOrEmpty() && entry.keepassGroupPath.isNullOrEmpty() &&
            entry.mdbxDatabaseId == null && entry.mdbxFolderId.isNullOrEmpty() &&
            entry.bitwardenVaultId == null && entry.bitwardenCipherId.isNullOrEmpty() &&
            entry.bitwardenFolderId.isNullOrEmpty() && entry.bitwardenRevisionDate.isNullOrEmpty() &&
            !entry.bitwardenLocalModified && entry.bitwardenCipherType == 1
    }
}
