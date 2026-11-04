package takagi.ru.monica.bitwarden.sync

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.data.bitwarden.BitwardenSyncQueueRecord

class BitwardenSyncQueueTest {
    @Test fun namesRequireTheSameAccountAndBothCurrentLockEpochs() {
        val snapshot = BitwardenSyncQueueSnapshot(7, "server/account", 3, 8, locked = false,
            items = listOf(BitwardenSyncQueueRecord("password:1", "cipher", "Private title", "CIPHER", "UPDATE", "PENDING")))
        assertTrue(snapshot.isCurrent("server/account", 3, 8, true))
        assertFalse(snapshot.isCurrent("other/account", 3, 8, true))
        assertFalse(snapshot.isCurrent("server/account", 4, 8, true))
        assertFalse(snapshot.isCurrent("server/account", 3, 9, true))
        assertFalse(snapshot.isCurrent("server/account", 3, 8, false))
        assertFalse(snapshot.copy(locked = true).isCurrent("server/account", 3, 8, true))
    }

    @Test fun summariesIncludeDirtyAndConfirmationRowsAndDeduplicateFailuresWithoutCrossingAccounts() {
        val rows = listOf(
            BitwardenSyncQueueRecord("password:1", "cipher-1", "Pending", "CIPHER", "CREATE", "PENDING"),
            BitwardenSyncQueueRecord("send:2", "send-1", "Awaiting", "SEND", "CONFIRM", "PENDING"),
            BitwardenSyncQueueRecord("op:3", "cipher-2", "Failed", "CIPHER", "DELETE", "FAILED"))
        val report = BitwardenSyncIssueReport(1, false, listOf(
            BitwardenSyncIssue("cipher-2", SyncIssueKind.CIPHER, SyncIssuePhase.DELETE, SyncIssueReason.REJECTED),
            BitwardenSyncIssue("remote-only", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.UNSUPPORTED)))
        val result = summarizeBitwardenQueue(listOf(
            BitwardenSyncQueueSnapshot(1, locked = false, items = rows),
            BitwardenSyncQueueSnapshot(2, locked = true, items = rows)), mapOf(1L to report, 2L to report, 99L to report))
        assertEquals(BitwardenSyncQueueSummary(pending = 2, failed = 2), result)
        assertEquals(BitwardenSyncQueueSummary(), summarizeBitwardenQueue(emptyList(), mapOf(1L to report)))
    }
}
