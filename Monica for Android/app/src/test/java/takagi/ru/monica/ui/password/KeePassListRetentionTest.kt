package takagi.ru.monica.ui.password

import org.junit.Assert.*
import org.junit.Test

class KeePassListRetentionTest {
    @Test fun retainsTheActualPreviousObjectWithoutRebuildingIt() {
        val retained = KeePassListRetention<Any>()
        val original = Any()
        retained.update(KeePassListSource(1, "A", 0), original)
        assertSame(original, retained.previous(emptyMap()))
    }
    @Test fun sourceLockRevokesPreviousEvenWhenAnotherTargetIsUnlocked() {
        val retained = KeePassListRetention<Any>()
        retained.update(KeePassListSource(1, "A", 2), Any())
        assertNotNull(retained.previous(mapOf(1L to 2L, 2L to 0L)))
        assertNull(retained.previous(mapOf(1L to 3L, 2L to 0L)))
        assertNull(retained.previous(mapOf(1L to 2L)))
    }
    @Test fun completedEmptyListReplacesPreviousAndOnlyOneListIsRetained() {
        val retained = KeePassListRetention<List<String>>()
        retained.update(KeePassListSource(1, "A", 0), listOf("A"))
        val empty = emptyList<String>()
        retained.update(KeePassListSource(2, "B", 0), empty)
        assertSame(empty, retained.previous(emptyMap()))
    }
    @Test fun clearingDropsThePreviousList() {
        val retained = KeePassListRetention<Any>()
        retained.update(KeePassListSource(1, "A", 0), Any())
        retained.clear()
        assertNull(retained.previous(emptyMap()))
    }
}
