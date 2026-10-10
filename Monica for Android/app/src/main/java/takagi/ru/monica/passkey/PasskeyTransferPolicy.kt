package takagi.ru.monica.passkey

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget

internal class PasskeyTransferPlan(val eligible: List<PasskeyEntry>, val issues: List<PasskeyMoveIssue>)

/** Preflight never writes. The caller must obtain the user's skip decision before executing. */
internal object PasskeyTransferPolicy {
    suspend fun plan(
        entries: List<PasskeyEntry>, target: UnifiedMoveCategoryTarget, action: UnifiedMoveAction,
        inspect: suspend (PasskeyEntry) -> PasskeyPortability
    ): PasskeyTransferPlan {
        val eligible = mutableListOf<PasskeyEntry>()
        val issues = mutableListOf<PasskeyMoveIssue>()
        for (entry in entries) {
            coroutineContext.ensureActive()
            val localMove = action == UnifiedMoveAction.MOVE &&
                (target is UnifiedMoveCategoryTarget.MonicaCategory || target == UnifiedMoveCategoryTarget.Uncategorized) &&
                entry.keepassDatabaseId == null && entry.mdbxDatabaseId == null && entry.bitwardenVaultId == null
            val uniqueStoreCopy = action == UnifiedMoveAction.COPY && when (target) {
                is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> entry.keepassDatabaseId == target.databaseId
                is UnifiedMoveCategoryTarget.KeePassGroupTarget -> entry.keepassDatabaseId == target.databaseId
                is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> entry.mdbxDatabaseId == target.databaseId
                is UnifiedMoveCategoryTarget.MdbxFolderTarget -> entry.mdbxDatabaseId == target.databaseId
                else -> false
            }
            val reason = when {
                entry.syncStatus == "REFERENCE" -> PasskeyMoveIssueReason.REFERENCE_ONLY
                entry.boundPasswordId != null -> PasskeyMoveIssueReason.BOUND_PASSWORD
                uniqueStoreCopy -> PasskeyMoveIssueReason.SAME_DATABASE_COPY
                localMove -> null
                else -> when (inspect(entry)) {
                    PasskeyPortability.PORTABLE -> null
                    PasskeyPortability.KEY_UNAVAILABLE -> PasskeyMoveIssueReason.KEY_UNAVAILABLE
                    else -> PasskeyMoveIssueReason.TRANSFER_RESTRICTED
                }
            }
            if (reason == null) eligible += entry else issues += PasskeyMoveIssue(
                entry.userName.ifBlank { entry.userDisplayName },
                listOf(entry.rpName, entry.rpId).filter(String::isNotBlank).distinct().joinToString(" · "), reason
            )
        }
        return PasskeyTransferPlan(eligible, issues)
    }
}
