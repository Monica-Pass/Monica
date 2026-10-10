package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyPortabilityCache
import takagi.ru.monica.passkey.PasskeyPortability

@Composable
internal fun rememberPasskeyPortability(passkey: PasskeyEntry): PasskeyPortability? {
    val context = LocalContext.current.applicationContext
    val cache = PasskeyPortabilityCache.shared
    val revision by cache.revision.collectAsState()
    val cacheKey = remember(passkey.privateKeyAlias, passkey.backupEligible, passkey.backupState,
        passkey.signCount > 0, passkey.publicKeyAlgorithm) { PasskeyPortabilityCache.keyFor(passkey) }
    // Recreate the state for a changed key so another credential's badge is never shown.
    return key(cacheKey, revision) {
        val status by produceState(cache.peek(cacheKey), context, cacheKey) {
            value = withContext(Dispatchers.IO) {
                cache.getOrLoad(cacheKey) { PasskeyPortability.inspect(context, passkey) }
            }
        }
        status
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasskeyPortabilityBadges(passkey: PasskeyEntry, status: PasskeyPortability?, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (passkey.isKeePassCompatible()) PortabilityBadge(stringResource(R.string.passkey_format_keepass))
        if (status != null && status != PasskeyPortability.PORTABLE) {
            PortabilityBadge(stringResource(status.label()), Modifier.testTag("passkey_portability_badge"))
        }
    }
}

@Composable
private fun PortabilityBadge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun PasskeyPortabilityDetails(status: PasskeyPortability?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("passkey_portability_details")) {
        Text(stringResource(R.string.passkey_cross_device), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(status?.label() ?: R.string.passkey_portability_checking), style = MaterialTheme.typography.bodyLarge)
        if (status != null) Text(stringResource(status.description()), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun PasskeyPortability.label(): Int = when (this) {
    PasskeyPortability.PORTABLE -> R.string.passkey_portability_exportable
    PasskeyPortability.DEVICE_ONLY -> R.string.passkey_portability_local_only
    PasskeyPortability.KEY_UNAVAILABLE -> R.string.passkey_portability_key_unavailable
    PasskeyPortability.INVALID_FLAGS -> R.string.passkey_portability_invalid
    PasskeyPortability.UNKNOWN -> R.string.passkey_portability_unknown
    else -> R.string.passkey_portability_restricted
}

private fun PasskeyPortability.description(): Int = when (this) {
    PasskeyPortability.PORTABLE -> R.string.passkey_portability_exportable_hint
    PasskeyPortability.DEVICE_ONLY -> R.string.passkey_portability_local_hint
    PasskeyPortability.KEY_UNAVAILABLE -> R.string.passkey_portability_missing_hint
    PasskeyPortability.BACKUP_RESTRICTED -> R.string.passkey_portability_backup_hint
    PasskeyPortability.COUNTER_HISTORY -> R.string.passkey_portability_counter_hint
    PasskeyPortability.ALGORITHM_RESTRICTED -> R.string.passkey_portability_algorithm_hint
    PasskeyPortability.INVALID_FLAGS -> R.string.passkey_portability_invalid_hint
    PasskeyPortability.UNKNOWN -> R.string.passkey_portability_unknown_hint
}
