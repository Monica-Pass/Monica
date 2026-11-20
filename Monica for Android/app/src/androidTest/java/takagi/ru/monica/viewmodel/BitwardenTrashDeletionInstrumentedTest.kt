package takagi.ru.monica.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.security.SessionManager

/** Real recycle-bin ViewModel -> repository -> HTTP -> local Room deletion, using synthetic data. */
@RunWith(AndroidJUnit4::class)
class BitwardenTrashDeletionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = PasswordDatabase.getDatabase(context)

    @Test
    fun clearingTheSelectedScopeDeletesPasswordsAndNotesFromServerAndRoom() = runBlocking {
        scenario {
            val password = password("password")
            val note = note("note")
            val outsideSelection = password("outside-selection")

            val result = delete(listOf(password, note))

            assertTrue(result.toString(), result.isSuccess)
            assertNull(database.passwordEntryDao().getPasswordEntryById(password.id))
            assertNull(database.secureItemDao().getItemById(note.id))
            assertEquals(setOf(cipherId("outside-selection")), remoteIds.toSet())
            assertNotNull(database.passwordEntryDao().getPasswordEntryById(outsideSelection.id))
        }
    }

    @Test
    fun aFailedDeleteRemainsRetryableWhileOtherItemsAndAlreadyMissingCiphersFinish() = runBlocking {
        scenario {
            val denied = password("denied")
            val alreadyGone = password("already-gone")
            val note = note("note")
            val localOnly = password("local", remote = false)
            deleteResponses[cipherId("denied")] = 403
            remoteIds.remove(cipherId("already-gone"))

            val result = delete(listOf(denied, alreadyGone, note, localOnly))

            assertEquals(context.getString(R.string.bitwarden_trash_delete_http_error, 403),
                result.exceptionOrNull()?.message)
            assertTrue(checkNotNull(database.passwordEntryDao().getPasswordEntryById(denied.id)).isDeleted)
            assertNull(database.passwordEntryDao().getPasswordEntryById(alreadyGone.id))
            assertNull(database.secureItemDao().getItemById(note.id))
            assertNull(database.passwordEntryDao().getPasswordEntryById(localOnly.id))
            assertEquals(setOf(cipherId("denied")), remoteIds.toSet())

            deleteResponses.remove(cipherId("denied"))
            assertTrue(deleteOne(denied).isSuccess)
            assertNull(database.passwordEntryDao().getPasswordEntryById(denied.id))
            assertTrue(remoteIds.isEmpty())
        }
    }

    @Test
    fun aLockedVaultReportsTheReasonWithoutDeletingTheLocalItem() = runBlocking {
        scenario(unlocked = false) {
            val item = password("locked")

            val result = deleteOne(item)

            assertEquals(context.getString(R.string.bitwarden_cache_requires_unlock),
                result.exceptionOrNull()?.message)
            assertNotNull(database.passwordEntryDao().getPasswordEntryById(item.id))
            assertEquals(0, server.requestCount)
            assertEquals(setOf(cipherId("locked")), remoteIds.toSet())
        }
    }

    @Test
    fun anExpiredSessionWithoutARefreshTokenReportsSignInAndPreservesTheItem() = runBlocking {
        scenario(expired = true) {
            val item = password("expired")

            val result = deleteOne(item)

            assertEquals(context.getString(R.string.pull_sync_requires_bitwarden_login),
                result.exceptionOrNull()?.message)
            assertNotNull(database.passwordEntryDao().getPasswordEntryById(item.id))
            assertEquals(0, server.requestCount)
        }
    }

    private suspend fun scenario(
        unlocked: Boolean = true,
        expired: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ) {
        assertTrue(takagi.ru.monica.security.SecureStorageStartup.prepareForEntry(context) is takagi.ru.monica.security.SecureStartupResult.Ready)
        val previouslyUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        try { MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                fixture.initialize(unlocked, expired)
                fixture.block()
            } finally {
                fixture.close()
            }
        } } finally {
            if (previouslyUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    private inner class Fixture(val server: MockWebServer) {
        private val prefix = "trash-136-${UUID.randomUUID()}"
        private val repository = BitwardenRepository.getInstance(context)
        private val settings = SettingsManager(context)
        private val key = SymmetricCryptoKey(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        private val passwordIds = mutableListOf<Long>()
        private val noteIds = mutableListOf<Long>()
        private var previousAutoDeleteDays: Int? = null
        private var vaultId = 0L
        private var model: TrashViewModel? = null
        val remoteIds = ConcurrentHashMap.newKeySet<String>()
        val deleteResponses = ConcurrentHashMap<String, Int>()

        suspend fun initialize(unlocked: Boolean, expired: Boolean) {
            previousAutoDeleteDays = settings.settingsFlow.first().trashAutoDeleteDays
            // Exercise only explicit synthetic selections; never start global automatic cleanup.
            settings.updateTrashAutoDeleteDays(0)
            val endpoint = server.url("/").toString()
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = "$prefix@example.invalid", accountKey = prefix,
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = server.url("/api/").toString(),
                isLocked = !unlocked, isConnected = true,
                accessTokenExpiresAt = if (expired) 1L else System.currentTimeMillis() + 3_600_000,
            ))
            if (unlocked) {
                // Seed only this fixture's auth caches; no real login, account or remote vault is used.
                cache<SymmetricCryptoKey>("symmetricKeyCache")[vaultId] = key
                cache<String>("accessTokenCache")[vaultId] = "synthetic-trash-token"
                cache<Long>("vaultAppGenerations")[vaultId] = SessionManager.lockGeneration.value
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer synthetic-trash-token") {
                        return MockResponse().setResponseCode(401)
                    }
                    val id = request.path?.removePrefix("/api/ciphers/") ?: ""
                    if (request.method != "DELETE" || request.path != "/api/ciphers/$id" || '/' in id) {
                        return MockResponse().setResponseCode(405)
                    }
                    val response = deleteResponses[id] ?: if (remoteIds.remove(id)) 204 else 404
                    return MockResponse().setResponseCode(response)
                }
            }
            model = withContext(Dispatchers.Main) { TrashViewModel(context.applicationContext as Application) }
        }

        fun cipherId(name: String) = "$prefix-$name"

        suspend fun password(name: String, remote: Boolean = true): TrashItem {
            val row = PasswordEntry(
                title = "$prefix-$name", username = "synthetic", password = "synthetic-password", website = "",
                isDeleted = true, deletedAt = Date(),
                bitwardenVaultId = vaultId.takeIf { remote }, bitwardenCipherId = cipherId(name).takeIf { remote },
            )
            val id = database.passwordEntryDao().insertPasswordEntry(row)
            passwordIds += id
            if (remote) remoteIds += cipherId(name)
            return TrashItem(id, row.title, ItemType.PASSWORD, checkNotNull(row.deletedAt), -1, row.copy(id = id))
        }

        suspend fun note(name: String): TrashItem {
            val row = SecureItem(
                itemType = ItemType.NOTE, title = "$prefix-$name", itemData = "{}",
                isDeleted = true, deletedAt = Date(), bitwardenVaultId = vaultId, bitwardenCipherId = cipherId(name),
            )
            val id = database.secureItemDao().insertItem(row)
            noteIds += id
            remoteIds += cipherId(name)
            return TrashItem(id, row.title, ItemType.NOTE, checkNotNull(row.deletedAt), -1, row.copy(id = id))
        }

        suspend fun delete(items: List<TrashItem>): Result<Unit> = withTimeout(20_000) {
            val result = CompletableDeferred<Result<Unit>>()
            withContext(Dispatchers.Main) {
                checkNotNull(model).permanentlyDeleteItems(items) { result.complete(it) }
            }
            result.await()
        }

        suspend fun deleteOne(item: TrashItem): Result<Unit> = withTimeout(20_000) {
            val result = CompletableDeferred<Result<Unit>>()
            withContext(Dispatchers.Main) {
                checkNotNull(model).permanentlyDeleteItem(item) { result.complete(it) }
            }
            result.await()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            BitwardenRepository::class.java.getDeclaredField(name).run {
                isAccessible = true
                get(repository) as MutableMap<Long, T>
            }

        suspend fun close() {
            model?.viewModelScope?.cancel()
            passwordIds.forEach { database.passwordEntryDao().deletePasswordEntryById(it) }
            noteIds.forEach { database.secureItemDao().deleteItemById(it) }
            if (vaultId != 0L) {
                repository.forceLock(vaultId)
                database.bitwardenPendingOperationDao().deleteByVault(vaultId)
                database.openHelper.writableDatabase.execSQL("DELETE FROM bitwarden_vaults WHERE id = ?", arrayOf(vaultId))
            }
            key.clear()
            previousAutoDeleteDays?.let { settings.updateTrashAutoDeleteDays(it) }
        }
    }
}
