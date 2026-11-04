package takagi.ru.monica.bitwarden.service

import org.junit.Assert.*
import org.junit.Test

class BitwardenSyncIssuesTest {
    @Test fun lockRevokesInFlightReportAndUnlockCannotReviveOldTitles() {
        val store = BitwardenSyncIssues()
        val old = store.begin(1, 100)
        old.record("a", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.DECRYPTION, "Private title")
        store.clear(1)
        val current = store.begin(1, 200)
        old.record("b", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED, "Late title")
        old.finish(SyncIssueReason.REJECTED)
        assertTrue(store.reports.value.getValue(1).items.isEmpty())
        assertTrue(store.reports.value.getValue(1).running)
        current.finish()
        assertFalse(store.reports.value.getValue(1).running)
    }

    @Test fun staleAttemptCannotBeginAfterLockOrReplaceNewSessionReport() {
        val store = BitwardenSyncIssues()
        var generation = 1
        val captured = generation
        generation++
        val current = store.begin(1)
        current.record("current", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED)
        val stale = store.begin(1, isCurrent = { generation == captured })
        stale.record("old-title", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED)
        stale.finish()
        assertEquals("current", store.reports.value.getValue(1).items.single().itemId)
        assertTrue(store.reports.value.getValue(1).running)
    }

    @Test fun accountsAreIsolatedAndSuccessReplacesPriorAttempt() {
        val store = BitwardenSyncIssues()
        val first = store.begin(1)
        val second = store.begin(2)
        first.record("same", SyncIssueKind.CIPHER, SyncIssuePhase.UPDATE, SyncIssueReason.CONFLICT, "First")
        second.record("same", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.DECRYPTION, "Second")
        first.finish()
        store.begin(1).finish()
        assertTrue(store.reports.value.getValue(1).items.isEmpty())
        assertEquals("Second", store.reports.value.getValue(2).items.single().title)
        store.clearAll()
        second.record("late", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED)
        assertTrue(store.reports.value.isEmpty())
    }

    @Test fun oneItemCanFailInDifferentPhasesButDuplicateReportsDoNotMultiply() {
        val store = BitwardenSyncIssues()
        val session = store.begin(7)
        repeat(3) { session.record("item", SyncIssueKind.CIPHER, SyncIssuePhase.UPDATE, SyncIssueReason.CONFLICT) }
        session.record("item", SyncIssueKind.ATTACHMENT, SyncIssuePhase.DOWNLOAD, SyncIssueReason.ATTACHMENT)
        session.finish()
        session.record("late", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED)
        assertEquals(2, store.reports.value.getValue(7).items.size)
    }

    @Test fun untrustedErrorsBecomeCategoriesWithoutKeepingServerBodies() {
        assertEquals(SyncIssueReason.REJECTED, SyncIssueReason.from("response contains synthetic-secret-value"))
        assertEquals(SyncIssueReason.CONFLICT, SyncIssueReason.from("HTTP 409"))
        assertEquals(SyncIssueReason.AUTHENTICATION, SyncIssueReason.from("HTTP 401"))
        assertEquals(SyncIssueReason.DECRYPTION, SyncIssueReason.from("MAC verification failed"))
        assertEquals(SyncIssueReason.UNSUPPORTED, SyncIssueReason.from("Unsupported or incomplete cipher"))
        assertEquals(SyncIssueReason.NETWORK, SyncIssueReason.from("UnknownHostException"))
    }
}
