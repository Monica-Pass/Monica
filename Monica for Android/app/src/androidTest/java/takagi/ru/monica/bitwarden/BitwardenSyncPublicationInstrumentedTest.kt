package takagi.ru.monica.bitwarden

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.BitwardenApiManager
import takagi.ru.monica.bitwarden.api.FolderApiResponse
import takagi.ru.monica.bitwarden.api.ProfileResponse
import takagi.ru.monica.bitwarden.api.SendApiResponse
import takagi.ru.monica.bitwarden.api.SendTextApiData
import takagi.ru.monica.bitwarden.api.SyncResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.mapper.BitwardenSendMapper
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenSendCache
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.SyncResult
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Synthetic captured leases with real /sync, Room rollback and MDK-encrypted Send cache. */
@RunWith(AndroidJUnit4::class)
class BitwardenSyncPublicationInstrumentedTest {
    @Test fun revokedFolderPublicationCannotInsertOrReportSuccess() = scenario {
        var reached = false
        enqueue(folders = listOf(remoteFolder))
        val result = service { action ->
            reached = true
            current = false
            database.withTransaction { requireCurrent(); action() }
        }.fullSync(vault, token, key)
        assertTrue(reached)
        assertTrue(result is SyncResult.Error)
        assertNull(database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id))
        assertEquals(vault, row())
        assertEquals(1, server.requestCount)
    }

    @Test fun folderMutationRetirementRollsBackAndDoesNotContinueIntoCleanup() = scenario {
        val password = insertPassword()
        var mutated = false
        enqueue(folders = listOf(remoteFolder))
        val result = service { action ->
            database.withTransaction {
                requireCurrent()
                action()
                assertEquals("Synthetic folder", database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id)?.name)
                mutated = true
                current = false
                requireCurrent()
            }
        }.fullSync(vault, token, key)
        assertTrue(mutated)
        assertTrue(result is SyncResult.Error)
        assertNull(database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id))
        assertEquals(password, database.passwordEntryDao().getPasswordEntryById(password.id))
        assertEquals(vault, row())
    }

    @Test fun cleanupRetirementRollsBackFolderAndCipherDeletesTogether() = scenario {
        val password = insertPassword()
        database.bitwardenFolderDao().upsert(BitwardenFolder(
            vaultId = vault.id, bitwardenFolderId = remoteFolder.id, name = "Existing folder", revisionDate = "old"))
        val folder = database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id)!!
        var mutated = false
        enqueue()
        val result = service { action ->
            database.withTransaction {
                requireCurrent()
                action()
                assertNull(database.passwordEntryDao().getPasswordEntryById(password.id))
                assertNull(database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id))
                mutated = true
                current = false
                requireCurrent()
            }
        }.fullSync(vault, token, key)
        assertTrue(mutated)
        assertTrue(result is SyncResult.Error)
        assertEquals(password, database.passwordEntryDao().getPasswordEntryById(password.id))
        assertEquals(folder, database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id))
        assertEquals(vault, row())
    }

    @Test fun sendUpsertRetirementRestoresOriginalEncryptedEnvelope() = scenario {
        val send = mappedSend("original content").copy(createdAt = 1, updatedAt = 1)
        cache.upsert(send)
        val before = rawSend(send.bitwardenSendId)!!
        var publications = 0
        var mutated = false
        enqueue(sends = listOf(remoteSend("late remote replacement")))
        val result = service { action ->
            database.withTransaction {
                requireCurrent()
                action()
                if (++publications == 2) {
                    assertNotEquals(before.encryptedCache, rawSend(send.bitwardenSendId)?.encryptedCache)
                    mutated = true
                    current = false
                }
                requireCurrent()
            }
        }.fullSync(vault, token, key)
        assertTrue(mutated)
        assertEquals(2, publications)
        assertTrue(result is SyncResult.Error)
        assertEquals(before, rawSend(send.bitwardenSendId))
        assertEquals("original content", cache.getBySendId(vault.id, send.bitwardenSendId)?.textContent)
        assertEquals(vault, row())
    }

    @Test fun sendCleanupRetirementRestoresOriginalEncryptedEnvelope() = scenario {
        val send = mappedSend("preserved cleanup content").copy(createdAt = 1, updatedAt = 1)
        cache.upsert(send)
        val before = rawSend(send.bitwardenSendId)!!
        var publications = 0
        var mutated = false
        enqueue(sends = emptyList())
        val result = service { action ->
            database.withTransaction {
                requireCurrent()
                action()
                if (++publications == 2) {
                    assertNull(rawSend(send.bitwardenSendId))
                    mutated = true
                    current = false
                }
                requireCurrent()
            }
        }.fullSync(vault, token, key)
        assertTrue(mutated)
        assertEquals(2, publications)
        assertTrue(result is SyncResult.Error)
        assertEquals(before, rawSend(send.bitwardenSendId))
        assertEquals(vault, row())
    }

    @Test fun sendCapturedBeforeTransportChangeCannotPublishUnderNewConfiguration() = scenario {
        val send = mappedSend("captured before transport change")
        cache.upsert(send)
        val before = rawSend(send.bitwardenSendId)!!
        database.bitwardenVaultDao().update(vault.copy(transportRevision = UUID.randomUUID().toString()))
        var published = false
        val failure = runCatching {
            cache.upsertCaptured(send.copy(textContent = "late content"), vault, {}) { action ->
                published = true
                database.withTransaction { action() }
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(published)
        assertEquals(before, rawSend(send.bitwardenSendId))
    }

    @Test fun currentFullSyncPublishesFoldersAndReadableEncryptedSend() = scenario {
        enqueue(folders = listOf(remoteFolder), sends = listOf(remoteSend("current content")))
        val result = service { action -> database.withTransaction { requireCurrent(); action(); requireCurrent() } }
            .fullSync(vault, token, key)
        assertTrue("Current full sync must succeed: $result", result is SyncResult.Success)
        assertEquals("Synthetic folder", database.bitwardenFolderDao().getFolderByBitwardenId(remoteFolder.id)?.name)
        val raw = rawSend(sendId)!!
        assertEquals(1, raw.cacheVersion)
        assertEquals("", raw.name)
        assertNull(raw.textContent)
        assertTrue(raw.encryptedCache!!.startsWith("MDK|"))
        assertEquals("current content", cache.getBySendId(vault.id, sendId)?.textContent)
        assertNotNull(row().lastSyncAt)
    }

    @Test fun capturedSendCleanupPreservesDirtyNewAndUnreadableRows() = scenario {
        val base = mappedSend("current cleanup content").copy(createdAt = 1, updatedAt = 1)
        val eligible = base.copy(bitwardenSendId = "$sendId-eligible")
        val dirty = base.copy(bitwardenSendId = "$sendId-dirty", isDirty = true)
        val recent = base.copy(bitwardenSendId = "$sendId-recent", createdAt = Long.MAX_VALUE)
        val unreadable = base.copy(bitwardenSendId = "$sendId-unreadable", encryptedCache = "MDK|unreadable", cacheVersion = 1)
        listOf(eligible, dirty, recent).forEach { cache.upsert(it) }
        database.bitwardenSendDao().upsert(unreadable)
        val retained = listOf(dirty, recent, unreadable).associate { it.bitwardenSendId to rawSend(it.bitwardenSendId) }
        cache.deleteNotInCaptured(vault, emptyList(), System.currentTimeMillis(), ::requireCurrent) { action ->
            database.withTransaction { requireCurrent(); action(); requireCurrent() }
        }
        assertNull(rawSend(eligible.bitwardenSendId))
        retained.forEach { (id, before) -> assertEquals(before, rawSend(id)) }
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.initialize(); fixture.block() } finally { fixture.close() }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = PasswordDatabase.getDatabase(context)
        private val repository = BitwardenRepository.getInstance(context)
        val cache = BitwardenSendCache(context)
        val server = MockWebServer()
        private val apiManager = BitwardenApiManager()
        val key = SymmetricCryptoKey(ByteArray(32) { 19 }, ByteArray(32) { 41 })
        val token = "synthetic-sync-publication-token"
        private val stamp = "synthetic-sync-publication-stamp"
        val sendId = UUID.randomUUID().toString()
        val remoteFolder = FolderApiResponse(UUID.randomUUID().toString(),
            BitwardenCrypto.encryptString("Synthetic folder", key), "2026-11-05T00:00:00Z")
        lateinit var vault: BitwardenVault
        @Volatile var current = true
        private val ownedPasswords = mutableListOf<Long>()

        suspend fun initialize() {
            val security = SecurityManager(context)
            val password = InstrumentationRegistry.getArguments().getString("testMasterPassword")
                ?: "Synthetic Bitwarden settings fixture 325!"
            if (!security.isMasterPasswordSet()) check(security.setMasterPassword(password))
            // Verify the supplied fixture password even if another test loaded a runtime MDK.
            check(security.unlockVaultWithPassword(password)) {
                "This test requires its own synthetic guest master password; existing data is not reset"
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { SessionManager.markUnlocked() }
            check(security.isVaultRuntimeUnlocked()) { "Synthetic fixture MDK was not loaded" }
            val endpoint = server.url("/").toString()
            val userId = UUID.randomUUID().toString()
            val email = "$userId@example.invalid"
            val candidate = BitwardenVault(email = email, canonicalEmail = email, userId = userId,
                accountKey = BitwardenVaultIdentity.buildAccountKey(endpoint, userId, email),
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint, isLocked = false,
                isConnected = true, syncEnabled = false, revisionDate = stamp)
            val id = database.bitwardenVaultDao().insert(candidate)
            vault = candidate.copy(id = id)
            sessionCache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
            sessionCache<String>("accessTokenCache")[id] = token
            check(repository.isVaultUnlocked(id))
        }

        fun requireCurrent() { check(current) { "Synthetic captured sync lease retired" } }

        fun service(publish: suspend (suspend () -> Unit) -> Unit) = BitwardenSyncService(
            context, apiManager, sessionIsActive = { id, captured ->
                current && id == vault.id && captured === key && repository.isVaultUnlocked(id)
            }, transportIsActive = { current }, publication = { _, _, action -> publish(action) })

        fun enqueue(folders: List<FolderApiResponse> = emptyList(), sends: List<SendApiResponse>? = null) {
            val response = SyncResponse(ProfileResponse(id = vault.userId!!, email = vault.email, securityStamp = stamp),
                folders = folders, sends = sends)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(Json { encodeDefaults = true }.encodeToString(response)))
        }

        fun remoteSend(content: String): SendApiResponse {
            val material = ByteArray(16) { 31 }
            val sendKey = BitwardenCrypto.deriveSendKey(material)
            try {
                return SendApiResponse(id = sendId, accessId = "synthetic-access", key = BitwardenCrypto.encrypt(material, key),
                    name = BitwardenCrypto.encryptString("Synthetic Send", sendKey),
                    text = SendTextApiData(BitwardenCrypto.encryptString(content, sendKey), false),
                    revisionDate = "2026-11-05T00:00:00Z")
            } finally { material.fill(0); sendKey.clear() }
        }

        fun mappedSend(content: String): BitwardenSend = requireNotNull(BitwardenSendMapper.mapApiToEntity(
            vault.id, vault.serverUrl, remoteSend(content), key, apiManager.environment(vault)))

        suspend fun row() = database.bitwardenVaultDao().getVaultById(vault.id)!!
        suspend fun rawSend(id: String) = database.bitwardenSendDao().getBySendId(vault.id, id)

        suspend fun insertPassword(): PasswordEntry {
            val candidate = PasswordEntry(title = "Synthetic sync publication", website = "https://example.invalid",
                username = "synthetic", password = "synthetic", bitwardenVaultId = vault.id,
                bitwardenCipherId = UUID.randomUUID().toString(), bitwardenLocalModified = false)
            val id = database.passwordEntryDao().insertPasswordEntry(candidate)
            ownedPasswords += id
            return database.passwordEntryDao().getPasswordEntryById(id)!!
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> sessionCache(name: String): MutableMap<Long, T> =
            BitwardenRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(repository) as MutableMap<Long, T>

        suspend fun close() {
            try {
                if (::vault.isInitialized) {
                    check(row().email == vault.email && row().serverUrl == vault.serverUrl)
                    ownedPasswords.forEach { database.passwordEntryDao().deletePasswordEntryById(it) }
                    database.bitwardenSendDao().deleteByVault(vault.id)
                    database.bitwardenFolderDao().deleteByVault(vault.id)
                    BitwardenVaultPremiumStore.clear(context, vault.id)
                    repository.forceLock(vault.id)
                    database.openHelper.writableDatabase.execSQL("DELETE FROM bitwarden_vaults WHERE id = ?", arrayOf(vault.id))
                }
            } finally { apiManager.clearCache(); server.shutdown(); key.clear() }
        }
    }
}
