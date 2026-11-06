package takagi.ru.monica.ui.password

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import takagi.ru.monica.R
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.sync.*

internal data class AllVaultLoadingState(val databaseCount: Int)

/** Observe existing work only. This presentation never schedules reads or unlocks a vault. */
internal fun resolveAllVaultLoadingState(
    statuses: Collection<SyncTaskStatus>, listPreparing: Boolean,
): AllVaultLoadingState? {
    val active = statuses.filter { it.phase == SyncPhase.RUNNING || it.phase == SyncPhase.QUEUED }
    val databases = active.mapNotNull { status ->
        when (val target = status.target) {
            is SyncTarget.KeePassDatabase -> "kp:${target.databaseId}"
            is SyncTarget.KeePassCompatibilityIndex -> target.databaseId?.let { "kp:$it" }
            is SyncTarget.BitwardenVault -> "bw:${target.vaultId}"
            is SyncTarget.MdbxVault -> "mdbx:${target.databaseId}"
            else -> null
        }
    }.toSet()
    val unknownBatch = active.any { it.target is SyncTarget.KeePassCompatibilityIndex && it.target.databaseId == null }
    return if (listPreparing || databases.isNotEmpty() || unknownBatch) {
        AllVaultLoadingState(if (unknownBatch) 0 else databases.size)
    } else null
}

@Composable
internal fun rememberAllVaultLoadingState(enabled: Boolean, listPreparing: Boolean): AllVaultLoadingState? {
    if (!enabled) return null
    val statuses by SyncTaskRunner.statuses.collectAsState(initial = emptyMap())
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!unlocked || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return null
    return remember(statuses, listPreparing) { resolveAllVaultLoadingState(statuses.values, listPreparing) }
}

@Composable
internal fun AllVaultLoadingStatus(state: AllVaultLoadingState) {
    Surface(Modifier.fillMaxWidth().testTag("all-vault-loading-status"), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (state.databaseCount > 1) stringResource(R.string.kp_list_loading_databases, state.databaseCount)
                else stringResource(R.string.kp_list_loading), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
    }
}
