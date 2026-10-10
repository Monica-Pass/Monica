package takagi.ru.monica.passkey

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.keepass.KeePassPasskeyCredentialConflictException
import kotlin.coroutines.coroutineContext

internal enum class PasskeyMoveIssueReason {
    BOUND_PASSWORD, REFERENCE_ONLY, BITWARDEN_UNSUPPORTED, KEEPASS_CONFLICT,
    UPDATE_FAILED, SOURCE_CLEANUP_FAILED, KEY_UNAVAILABLE, TRANSFER_RESTRICTED, SAME_DATABASE_COPY,
}

/** Keep only display metadata, never private key material or raw exception messages. */
internal data class PasskeyMoveIssue(
    val account: String,
    val relyingParty: String,
    val reason: PasskeyMoveIssueReason,
)

internal data class PasskeyMoveReport(val movedCount: Int, val issues: List<PasskeyMoveIssue>)

internal class PasskeyTransferBlockedException(val reason: PasskeyMoveIssueReason) : IllegalStateException("Passkey transfer unavailable")

internal class PasskeyBitwardenMoveBlockedException : IllegalStateException("Unsupported Bitwarden passkey")

internal class PasskeyMoveSourceCleanupException(cause: Throwable) :
    IllegalStateException("Passkey destination saved; source cleanup incomplete", cause)

internal object PasskeyMoveReporter {
    suspend fun execute(
        entries: List<PasskeyEntry>,
        move: suspend (PasskeyEntry) -> Result<Unit>,
    ): PasskeyMoveReport {
        var moved = 0
        val issues = mutableListOf<PasskeyMoveIssue>()
        for (entry in entries) {
            coroutineContext.ensureActive()
            val blocked = when {
                entry.syncStatus == "REFERENCE" -> PasskeyMoveIssueReason.REFERENCE_ONLY
                entry.boundPasswordId != null -> PasskeyMoveIssueReason.BOUND_PASSWORD
                else -> null
            }
            val reason = blocked ?: run {
                val result = try {
                    move(entry)
                } catch (error: Exception) {
                    Result.failure(error)
                }
                coroutineContext.ensureActive()
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                when {
                    result.isSuccess -> { moved++; null }
                    error is PasskeyTransferBlockedException -> error.reason
                    error is PasskeyBitwardenMoveBlockedException -> PasskeyMoveIssueReason.BITWARDEN_UNSUPPORTED
                    error is KeePassPasskeyCredentialConflictException -> PasskeyMoveIssueReason.KEEPASS_CONFLICT
                    error is PasskeyMoveSourceCleanupException -> PasskeyMoveIssueReason.SOURCE_CLEANUP_FAILED
                    else -> PasskeyMoveIssueReason.UPDATE_FAILED
                }
            }
            if (reason != null) issues += PasskeyMoveIssue(
                account = entry.userName.ifBlank { entry.userDisplayName },
                relyingParty = listOf(entry.rpName, entry.rpId).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                reason = reason,
            )
        }
        return PasskeyMoveReport(moved, issues)
    }
}
