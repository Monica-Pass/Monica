package takagi.ru.monica.passkey

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry

class PasskeyPortabilityCacheTest {
    private fun row(alias: String = "fixture-key") = PasskeyEntry(credentialId = "fixture", rpId = "example.invalid",
        rpName = "Fixture", userId = "fixture", userName = "fixture", userDisplayName = "Fixture",
        publicKey = "", privateKeyAlias = alias)

    @Test fun revisitingOrChangingDisplayMetadataReusesResult() = runBlocking {
        val cache = PasskeyPortabilityCache()
        val first = PasskeyPortabilityCache.keyFor(row())
        var reads = 0
        cache.getOrLoad(first) { reads++; PasskeyPortability.KEY_UNAVAILABLE }
        val rebuilt = PasskeyPortabilityCache.keyFor(row().copy(notes = "Edited", lastUsedAt = 42, syncStatus = "SYNCED"))
        assertEquals(first, rebuilt)
        assertEquals(PasskeyPortability.KEY_UNAVAILABLE, cache.peek(rebuilt))
        assertEquals(PasskeyPortability.KEY_UNAVAILABLE, cache.getOrLoad(rebuilt) { reads++; PasskeyPortability.PORTABLE })
        assertEquals(1, reads)
    }

    @Test fun keyAndCompatibilityChangesHaveDifferentCacheKeys() {
        val entry = row()
        val original = PasskeyPortabilityCache.keyFor(entry)
        listOf(entry.copy(privateKeyAlias = "replacement"), entry.copy(backupEligible = false),
            entry.copy(backupState = true), entry.copy(signCount = 1), entry.copy(publicKeyAlgorithm = -257))
            .forEach { assertNotEquals(original, PasskeyPortabilityCache.keyFor(it)) }
        assertEquals(PasskeyPortabilityCache.keyFor(entry.copy(signCount = 1)),
            PasskeyPortabilityCache.keyFor(entry.copy(signCount = 2)))
        assertFalse(original.toString().contains("fixture-key"))
    }

    @Test fun concurrentReadersShareOneInspection() = runBlocking {
        val cache = PasskeyPortabilityCache()
        val key = PasskeyPortabilityCache.keyFor(row())
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val results = (1..8).map { async { cache.getOrLoad(key) { reads++; gate.await(); PasskeyPortability.BACKUP_RESTRICTED } } }
        gate.complete(Unit)
        assertTrue(results.awaitAll().all { it == PasskeyPortability.BACKUP_RESTRICTED })
        assertEquals(1, reads)
    }

    @Test fun invalidationDuringInspectionCannotRepopulateStaleValue() = runBlocking {
        val cache = PasskeyPortabilityCache()
        val key = PasskeyPortabilityCache.keyFor(row())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reading = async { cache.getOrLoad(key) { started.complete(Unit); release.await(); PasskeyPortability.PORTABLE } }
        started.await()
        cache.invalidate("fixture-key")
        release.complete(Unit)
        reading.await()
        assertNull(cache.peek(key))
        assertEquals(PasskeyPortability.KEY_UNAVAILABLE, cache.getOrLoad(key) { PasskeyPortability.KEY_UNAVAILABLE })
    }

    @Test fun expiryKeepsBadgeVisibleWhileRefreshingAndCapacityIsBounded() = runBlocking {
        var now = 0L
        val cache = PasskeyPortabilityCache(capacity = 2, ttlMillis = 100, nowMillis = { now })
        val key = PasskeyPortabilityCache.keyFor(row())
        cache.getOrLoad(key) { PasskeyPortability.KEY_UNAVAILABLE }
        now = 101
        assertEquals(PasskeyPortability.KEY_UNAVAILABLE, cache.peek(key))
        assertEquals(PasskeyPortability.PORTABLE, cache.getOrLoad(key) { PasskeyPortability.PORTABLE })
        cache.getOrLoad(PasskeyPortabilityCache.keyFor(row("two"))) { PasskeyPortability.PORTABLE }
        cache.getOrLoad(PasskeyPortabilityCache.keyFor(row("three"))) { PasskeyPortability.PORTABLE }
        assertNull(cache.peek(key))
        cache.clear()
        assertNull(cache.peek(PasskeyPortabilityCache.keyFor(row("three"))))
    }
}
