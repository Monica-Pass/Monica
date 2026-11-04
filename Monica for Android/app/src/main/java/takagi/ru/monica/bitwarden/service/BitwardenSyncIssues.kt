package takagi.ru.monica.bitwarden.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncIssueKind { CIPHER, FOLDER, SEND, ATTACHMENT, PASSKEY }
enum class SyncIssuePhase { DOWNLOAD, CREATE, UPDATE, DELETE, RESTORE }
enum class SyncIssueReason {
    AUTHENTICATION, NETWORK, CONFLICT, UNSUPPORTED, DECRYPTION, REJECTED, ATTACHMENT, PENDING_PARENT;

    companion object {
        /** Error bodies and exception text may contain secrets. Only retain an allowlisted category. */
        fun from(message: String?): SyncIssueReason {
            val value = message.orEmpty().lowercase(java.util.Locale.ROOT)
            return when {
                value.contains("401") || value.contains("403") || value.contains("locked") ||
                    value.contains("authentication") || value.contains("session changed") -> AUTHENTICATION
                value.contains("conflict") || value.contains("revision") || value.contains("changed") ||
                    value.contains("409") || value.contains("412") -> CONFLICT
                value.contains("unsupported") || value.contains("incomplete") || value.contains("partial") -> UNSUPPORTED
                value.contains("decrypt") || value.contains("key") || value.contains("mac") ||
                    value.contains("cipherstring") -> DECRYPTION
                value.contains("timeout") || value.contains("connect") || value.contains("ioexception") ||
                    value.contains("unknownhost") || value.contains("network") -> NETWORK
                value.contains("attachment") -> ATTACHMENT
                else -> REJECTED
            }
        }
    }
}

data class BitwardenSyncIssue(
    val itemId: String,
    val kind: SyncIssueKind,
    val phase: SyncIssuePhase,
    val reason: SyncIssueReason,
    val title: String? = null,
)

data class BitwardenSyncIssueReport(
    val startedAt: Long,
    val running: Boolean,
    val items: List<BitwardenSyncIssue> = emptyList(),
    val accountFailure: SyncIssueReason? = null,
)

/** Latest attempt only, in memory. Lock/logout revokes writers as well as removing visible titles. */
class BitwardenSyncIssues {
    private val state = MutableStateFlow<Map<Long, BitwardenSyncIssueReport>>(emptyMap())
    val reports: StateFlow<Map<Long, BitwardenSyncIssueReport>> = state.asStateFlow()
    private val sessions = mutableMapOf<Long, Session>()

    @Synchronized fun begin(vaultId: Long, now: Long = System.currentTimeMillis(),
        isCurrent: () -> Boolean = { true }): Session {
        val session = Session(this, vaultId, isCurrent)
        if (!isCurrent()) return session
        sessions[vaultId] = session
        state.value = state.value + (vaultId to BitwardenSyncIssueReport(now, running = true))
        return session
    }

    @Synchronized fun clear(vaultId: Long) {
        sessions.remove(vaultId)
        state.value = state.value - vaultId
    }

    @Synchronized fun clearAll() {
        sessions.clear()
        state.value = emptyMap()
    }

    @Synchronized private fun record(session: Session, issue: BitwardenSyncIssue) {
        if (sessions[session.vaultId] !== session || !session.isCurrent()) return
        val previous = state.value[session.vaultId] ?: return
        if (!previous.running) return
        val items = previous.items.filterNot {
            it.itemId == issue.itemId && it.kind == issue.kind && it.phase == issue.phase
        } + issue
        state.value = state.value + (session.vaultId to previous.copy(items = items))
    }

    @Synchronized private fun finish(session: Session, reason: SyncIssueReason?) {
        if (sessions[session.vaultId] !== session || !session.isCurrent()) return
        val previous = state.value[session.vaultId] ?: return
        state.value = state.value + (session.vaultId to previous.copy(running = false, accountFailure = reason))
        sessions.remove(session.vaultId)
    }

    class Session internal constructor(private val owner: BitwardenSyncIssues, internal val vaultId: Long,
        internal val isCurrent: () -> Boolean) {
        fun record(itemId: String, kind: SyncIssueKind, phase: SyncIssuePhase, reason: SyncIssueReason,
            title: String? = null) {
            // Plain identifiers/titles are only for the unlocked UI, never persisted or logged.
            owner.record(this, BitwardenSyncIssue(itemId.take(256), kind, phase, reason,
                title?.filterNot { it.isISOControl() }?.take(200)?.takeIf { it.isNotBlank() }))
        }
        fun finish(reason: SyncIssueReason? = null) = owner.finish(this, reason)
    }
}
