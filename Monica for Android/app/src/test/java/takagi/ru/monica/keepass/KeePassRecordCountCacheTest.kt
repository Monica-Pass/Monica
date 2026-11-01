package takagi.ru.monica.keepass

import org.junit.Assert.*
import org.junit.Test

class KeePassRecordCountCacheTest {
    private class Session {
        var unlocked = true
        var generation = 0L
        val versions = mutableMapOf<Long, Long>()
        val cache = KeePassRecordCountCache({ unlocked }, { generation }, { versions[it] ?: 0L })
    }

    @Test fun independentCountsAndZeroRemainAvailableWithoutDecodedObjects() {
        val s = Session()
        repeat(6) { i -> assertTrue(s.cache.publish(s.cache.capture(i + 1L), i.toLong())) }
        repeat(6) { i -> assertEquals(i.toLong(), s.cache.count(i + 1L)) }
        assertNull(s.cache.count(7))
    }

    @Test fun globalLockAndReunlockCannotExposeOrPublishAnOldTotal() {
        val s = Session()
        val scope = s.cache.capture(1)
        s.cache.publish(scope, 42)
        s.unlocked = false
        s.generation++
        assertNull(s.cache.count(1))
        s.unlocked = true
        assertNull(s.cache.count(1))
        assertFalse(s.cache.publish(scope, 99))
        assertTrue(s.cache.publish(s.cache.capture(1), 43))
        assertEquals(43L, s.cache.count(1))
    }

    @Test fun perDatabaseLockRevokesOnlyItsOwnOldScope() {
        val s = Session()
        val first = s.cache.capture(1)
        s.cache.publish(first, 1)
        s.cache.publish(s.cache.capture(2), 2)
        s.versions[1] = 1
        assertNull(s.cache.count(1))
        assertFalse(s.cache.publish(first, 7))
        assertEquals(2L, s.cache.count(2))
    }

    @Test fun invalidationRejectsALateReadEvenWithoutALockEpochChange() {
        val s = Session()
        val before = s.cache.capture(1)
        s.cache.publish(before, 1)
        s.cache.invalidate(1)
        assertNull(s.cache.count(1))
        assertFalse(s.cache.publish(before, 2))
        assertTrue(s.cache.publish(s.cache.capture(1), 3))
        assertEquals(3L, s.cache.count(1))
    }

    @Test fun readingWhileLockedDoesNotBecomeAuthorizedAfterUnlock() {
        val s = Session()
        s.unlocked = false
        val before = s.cache.capture(1)
        s.unlocked = true
        assertFalse(s.cache.publish(before, 2))
        assertNull(s.cache.count(1))
    }

    @Test fun successfulSameScopeUpdatesReplaceTheDisplayedTotal() {
        val s = Session()
        val scope = s.cache.capture(1)
        assertTrue(s.cache.publish(scope, 20))
        assertTrue(s.cache.publish(scope, 19))
        assertEquals(19L, s.cache.count(1))
        assertEquals(19L, s.cache.state.value[1]!!.count)
    }
}
