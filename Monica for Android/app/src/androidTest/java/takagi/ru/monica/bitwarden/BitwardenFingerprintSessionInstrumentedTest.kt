package takagi.ru.monica.bitwarden

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenAccountFingerprint
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SessionManager

/** Synthetic Repository.sync HTTP paths, complementary to the separate nine native UI tests. */
@RunWith(AndroidJUnit4::class)
class BitwardenFingerprintSessionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun firstFullSyncPublishesProfileIdStampAndImmediatelyReadableFingerprint(): Unit = runBlocking {
        withFixture(firstProfile = true) { f ->
            val before = f.row()
            assertNull(before.userId)
            assertNull(before.revisionDate)
            assertNull(f.sealedIdentity())
            // The preflight intentionally omits privateKey. Only the real /sync response can capture it.
            f.includePreflightPrivateKey = false
            assertSyncSuccess(f.repository.sync(f.vaultId))
            val after = f.row()
            assertEquals(f.userId, after.userId)
            assertEquals(f.identity.get().stamp, after.revisionDate)
            assertEquals(BitwardenVaultIdentity.buildAccountKey(after.serverUrl, f.userId, f.email), after.accountKey)
            assertNotEquals(before.accountKey, after.accountKey)
            assertEquals(listOf(PROFILE, SYNC), f.paths.toList())
            assertNotNull(f.sealedIdentity())
            assertEquals(f.identity.get().phrase, f.repository.getAccountFingerprint(f.vaultId).getOrThrow())
            assertEquals("Reading the first fingerprint must not require a second HTTP request", 2, f.paths.size)
        }
    }

    @Test fun lateChangedStampPreflightCannotRevokeSameTokenRestoredSession(): Unit = runBlocking {
        withFixture { f ->
            f.seedIdentity()
            val held = f.holdNext(PROFILE, f.identity.get().copy(stamp = "synthetic-obsolete-preflight-stamp"))
            assertLateSyncRejected(f, held)
            // Initial /sync only: the revoked preflight must not proceed into another /sync.
            assertEquals(1, f.paths.count { it == SYNC })
        }
    }

    @Test fun lateFullSyncIdentityAndChangedStampCannotReplaceOrRevokeRestoredSession(): Unit = runBlocking {
        withFixture { f ->
            f.seedIdentity()
            listOf(false, true).forEach { changedStamp ->
                val old = f.identity.get()
                val responseIdentity = if (changedStamp) old.copy(stamp = "synthetic-obsolete-full-sync-stamp") else old
                val held = f.holdNext(SYNC, responseIdentity)
                assertLateSyncRejected(f, held)
            }
            assertEquals("Seed plus both held full-sync requests must exercise /sync", 3, f.paths.count { it == SYNC })
        }
    }

    private suspend fun assertLateSyncRejected(f: Fixture, held: HeldResponse) = coroutineScope {
        val oldRequest = async(Dispatchers.IO) { f.repository.sync(f.vaultId) }
        try {
            assertTrue("Old authenticated request must reach its selected HTTP endpoint", held.entered.await(20, TimeUnit.SECONDS))
            f.repository.forceLock(f.vaultId)
            assertFalse(f.repository.isVaultUnlocked(f.vaultId))
            val locked = f.row()
            f.database.bitwardenVaultDao().update(locked.copy(isLocked = false, isConnected = true))
            f.installSession() // Same token and symmetric bytes, but a new key object and vault generation.
            f.rotateIdentity()
            f.includePreflightPrivateKey = true
            assertTrue("The new session must authenticate and capture its own identity", f.repository.getAccountDevices(f.vaultId).isSuccess)
            val current = f.row()
            val newPhrase = f.repository.getAccountFingerprint(f.vaultId).getOrThrow()
            val newSealed = requireNotNull(f.sealedIdentity())
            assertEquals(f.identity.get().phrase, newPhrase)
            assertNotEquals("The replacement fixture must have a distinguishable public identity", held.identity.phrase, newPhrase)
            val callsBeforeRelease = f.paths.size
            held.release.countDown()
            val oldResult = withTimeout(20_000) { oldRequest.await() }
            assertTrue("Revoked sync must fail instead of accepting its old response: $oldResult", oldResult is BitwardenRepository.SyncResult.Error)
            assertTrue("Old CHANGED stamp cannot revoke the new key/token", f.repository.isVaultUnlocked(f.vaultId))
            val after = f.row()
            assertFalse(after.isLocked)
            assertTrue(after.isConnected)
            assertEquals(current.userId, after.userId)
            assertEquals(current.accountKey, after.accountKey)
            assertEquals(current.revisionDate, after.revisionDate)
            assertEquals(current.encryptedAccessToken, after.encryptedAccessToken)
            assertEquals(current.encryptedRefreshToken, after.encryptedRefreshToken)
            assertEquals("Old response cannot clear or replace the current sealed public key", newSealed, f.sealedIdentity())
            assertEquals(newPhrase, f.repository.getAccountFingerprint(f.vaultId).getOrThrow())
            assertEquals("A revoked old request cannot continue with additional HTTP operations", callsBeforeRelease, f.paths.size)
        } finally {
            held.release.countDown()
            oldRequest.cancelAndJoin()
        }
    }

    private suspend fun withFixture(firstProfile: Boolean = false, block: suspend (Fixture) -> Unit) {
        val f = Fixture(firstProfile)
        var failure: Throwable? = null
        try {
            f.initialize()
            block(f)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { f.close() } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun assertSyncSuccess(result: BitwardenRepository.SyncResult) {
        assertTrue("The synthetic empty-vault sync must complete: $result", result is BitwardenRepository.SyncResult.Success)
    }

    private data class Identity(val stamp: String, val envelope: String, val phrase: String)
    private class HeldResponse(val path: String, val identity: Identity) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private inner class Fixture(private val firstProfile: Boolean) {
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        private val originalUnlocked = SessionManager.isUnlocked.value
        private val server = MockWebServer()
        private val nextHeld = AtomicReference<HeldResponse?>()
        private val heldResponses = CopyOnWriteArrayList<HeldResponse>()
        val paths = CopyOnWriteArrayList<String>()
        val identity = AtomicReference<Identity>()
        val userId = UUID.randomUUID().toString()
        val email = "fingerprint-session-$userId@example.invalid"
        private val token = "synthetic-fingerprint-session-token-$userId"
        private var endpoint = ""
        private var savedLastSyncPresent = false
        private var savedLastSyncTime = 0L
        private var preferencesCaptured = false
        @Volatile var includePreflightPrivateKey = false
        var vaultId = 0L

        private fun newKey() = SymmetricCryptoKey(ByteArray(32) { 43 }, ByteArray(32) { 79 })

        suspend fun initialize() {
            check(repository.getAllVaults().none { repository.isVaultUnlocked(it.id) }) {
                "Use a dedicated synthetic user without other unlocked accounts"
            }
            instrumentation.runOnMainSync { SessionManager.markUnlocked() }
            val prefs = preferences()
            savedLastSyncPresent = prefs.contains(LAST_SYNC_TIME)
            savedLastSyncTime = prefs.getLong(LAST_SYNC_TIME, 0L)
            preferencesCaptured = true
            rotateIdentity()
            endpoint = server.url("/").toString().trimEnd('/')
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = email, canonicalEmail = email, userId = if (firstProfile) null else userId,
                accountKey = BitwardenVaultIdentity.buildAccountKey(endpoint, if (firstProfile) null else userId, email),
                displayName = "Synthetic fingerprint session", serverUrl = endpoint,
                identityUrl = "$endpoint/identity", apiUrl = "$endpoint/api/",
                encryptedAccessToken = "synthetic-fingerprint-session-access-marker",
                encryptedRefreshToken = "synthetic-fingerprint-session-refresh-marker",
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                revisionDate = if (firstProfile) null else identity.get().stamp,
                isLocked = false, isConnected = true, syncEnabled = false,
            ))
            installSession()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += path
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    val candidate = nextHeld.get()
                    val held = candidate?.takeIf { it.path == path && nextHeld.compareAndSet(it, null) }
                    val responseIdentity = held?.identity ?: identity.get()
                    val body = when (path) {
                        PROFILE -> profile(responseIdentity, includePreflightPrivateKey).toString()
                        SYNC -> buildJsonObject {
                            put("profile", profile(responseIdentity, includeKey = true))
                            put("ciphers", JsonArray(emptyList()))
                            put("folders", JsonArray(emptyList()))
                            put("sends", JsonArray(emptyList()))
                        }.toString()
                        DEVICES -> """{"data":[{"id":"synthetic-session-device","name":"Synthetic session device","isTrusted":true}]}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    if (held != null) {
                        held.entered.countDown()
                        if (!held.release.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    }
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
                }
            }
        }

        private fun profile(value: Identity, includeKey: Boolean): JsonObject = buildJsonObject {
            put("id", userId)
            put("email", email)
            put("securityStamp", value.stamp)
            if (includeKey) put("privateKey", value.envelope)
        }

        fun rotateIdentity() {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val privateBytes = pair.private.encoded
            val publicBytes = pair.public.encoded
            val key = newKey()
            try {
                val words = context.resources.openRawResource(R.raw.bitwarden_fingerprint_words).bufferedReader().use { it.readLines() }
                identity.set(Identity("synthetic-session-stamp", BitwardenCrypto.encrypt(privateBytes, key),
                    BitwardenAccountFingerprint.phrase(userId, publicBytes, words)))
            } finally {
                privateBytes.fill(0)
                publicBytes.fill(0)
                key.clear()
                runCatching { pair.private.destroy() }
            }
        }

        suspend fun seedIdentity() {
            assertSyncSuccess(repository.sync(vaultId))
            assertEquals(identity.get().phrase, repository.getAccountFingerprint(vaultId).getOrThrow())
        }

        fun installSession() {
            cache<SymmetricCryptoKey>("symmetricKeyCache").put(vaultId, newKey())?.clear()
            cache<String>("accessTokenCache")[vaultId] = token
        }

        fun holdNext(path: String, value: Identity): HeldResponse = HeldResponse(path, value).also {
            check(nextHeld.compareAndSet(null, it))
            heldResponses += it
        }

        suspend fun row(): BitwardenVault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))

        fun sealedIdentity(): String? = preferences().getString("bitwarden_account_identity_v1_$vaultId", null)

        private fun preferences(): SharedPreferences = requireNotNull(
            BitwardenRepository::class.java.getDeclaredMethod("getSecurePrefs").apply { isAccessible = true }
                .invoke(repository) as? SharedPreferences)

        suspend fun close() {
            heldResponses.forEach { it.release.countDown() }
            try {
                if (vaultId != 0L) database.bitwardenVaultDao().getVaultById(vaultId)?.let {
                    check(it.serverUrl == endpoint && it.email == email && (it.userId == null || it.userId == userId)) {
                        "Refuse to clean up a fixture whose owner changed"
                    }
                    check(repository.logout(vaultId)) { "Could not remove owned fingerprint-session fixture" }
                }
            } finally {
                try {
                    server.shutdown()
                } finally {
                    try {
                        if (preferencesCaptured) {
                            val edit = preferences().edit()
                            if (savedLastSyncPresent) edit.putLong(LAST_SYNC_TIME, savedLastSyncTime) else edit.remove(LAST_SYNC_TIME)
                            check(edit.commit()) { "Could not restore previous last-sync timestamp" }
                        }
                    } finally {
                        instrumentation.runOnMainSync {
                            if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            BitwardenRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(repository) as MutableMap<Long, T>
    }

    private companion object {
        const val PROFILE = "/api/accounts/profile"
        const val SYNC = "/api/sync"
        const val DEVICES = "/api/devices"
        const val LAST_SYNC_TIME = "last_sync_time"
    }
}
