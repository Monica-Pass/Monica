package takagi.ru.monica.credentialexchange

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.animateMonicaContentSize
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService

/** Shared import/export controls, based on the editable M3E Canvas transfer design. */
@Composable
fun TransferChoiceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    index: Int = 0,
    count: Int = 1,
    titleDetail: String? = null,
) {
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 24.dp else 6.dp,
        topEnd = if (index == 0) 24.dp else 6.dp,
        bottomStart = if (index == count - 1) 24.dp else 6.dp,
        bottomEnd = if (index == count - 1) 24.dp else 6.dp,
    )
    Surface(
        onClick = onClick, enabled = enabled, shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    titleDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferDestinationField(
    destination: ImportDestination,
    onSelect: (ImportDestination) -> Unit,
    enabled: Boolean = true,
    forExport: Boolean = false,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val options by produceState<List<ImportDestinationOption>?>(null, context, show, forExport) {
        value = ImportDestinationWriter.destinations(context, forExport)
    }
    val current = options?.firstOrNull { it.destination == destination }
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lockVersions by KeePassKdbxService.nativeLockVersions.collectAsState()
    val counter = remember(context) { DatabaseRecordCounts(context) }
    val counts by produceState<Map<ImportDestination, DatabaseRecordCount>>(emptyMap(),
        options, destination, show, forExport, unlocked, lockVersions) {
        value = emptyMap()
        if (forExport && unlocked) {
            val requested = if (show) options.orEmpty().sortedBy { it.destination != destination }
                .map { it.destination } else listOf(destination)
            for (source in requested) value = value + (source to counter.load(source))
        }
    }
    @Composable fun countLabel(source: ImportDestination): String? {
        if (!forExport) return null
        return when (val state = if (counter.isLocked(source)) DatabaseRecordCount.Locked else counts[source]) {
            is DatabaseRecordCount.Ready -> stringResource(R.string.export_source_record_count, state.count)
            DatabaseRecordCount.Locked -> stringResource(R.string.export_source_count_locked)
            DatabaseRecordCount.Unavailable -> stringResource(R.string.export_source_count_unavailable)
            null -> stringResource(R.string.export_source_count_loading)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (forExport) R.string.exchange_source else R.string.exchange_save_to),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
        TransferChoiceRow(
            title = current?.title ?: if (destination == ImportDestination.Local) "Monica" else stringResource(R.string.exchange_destination_unavailable),
            subtitle = current?.subtitle.orEmpty(), icon = Icons.Default.Storage,
            enabled = enabled, onClick = { show = true },
            titleDetail = countLabel(destination),
        )
    }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(if (forExport) R.string.exchange_source else R.string.exchange_save_to),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(options.orEmpty(), key = { _, it -> it.destination.key }) { index, option ->
                TransferChoiceRow(option.title, option.subtitle, Icons.Default.Storage,
                    enabled = option.available, selected = destination == option.destination,
                    index = index, count = options.orEmpty().size,
                    titleDetail = countLabel(option.destination),
                    onClick = { onSelect(option.destination); show = false })
            }
        }
    }
}

@Composable
fun TransferSummary(summary: ImportResultSummary) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateMonicaContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (summary.failed == 0) Icons.Default.CheckCircle else Icons.Default.Info, null)
            Text(stringResource(R.string.exchange_import_done), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.exchange_result, summary.imported, summary.skipped, summary.failed))
            if (summary.failed > 0) Text(stringResource(R.string.exchange_import_partial), style = MaterialTheme.typography.bodySmall)
            if (summary.queuedToBitwarden) Text(stringResource(R.string.exchange_pending), style = MaterialTheme.typography.bodySmall)
            TransferSkippedReasons(summary.skippedDuringDecode)
        }
    }
}

@Composable
fun TransferCredentialCounts(passwords: Int, passkeys: Int, skipped: Int, exporting: Boolean = false,
    skippedReasons: Map<CxfCredentialCodec.SkipReason, Int> = emptyMap()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.exchange_password_count, passwords), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.exchange_passkey_count, passkeys), style = MaterialTheme.typography.titleMedium)
        if (skipped > 0) Text(
            stringResource(if (exporting) R.string.exchange_export_skipped else R.string.exchange_skipped_count, skipped),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!exporting) TransferSkippedReasons(skippedReasons)
    }
}

@Composable
private fun TransferSkippedReasons(reasons: Map<CxfCredentialCodec.SkipReason, Int>) {
    val rows = CxfCredentialCodec.SkipReason.entries.filter { (reasons[it] ?: 0) > 0 }
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.exchange_skip_reasons), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 8.dp))
        rows.forEachIndexed { index, reason ->
            val label = when (reason) {
                CxfCredentialCodec.SkipReason.UNSUPPORTED_CREDENTIAL -> R.string.exchange_skip_unsupported
                CxfCredentialCodec.SkipReason.UNSUPPORTED_TOTP -> R.string.exchange_skip_totp
                CxfCredentialCodec.SkipReason.INVALID_CREDENTIAL -> R.string.exchange_skip_invalid
                CxfCredentialCodec.SkipReason.PASSKEY_EXTENSION -> R.string.exchange_skip_extension
                CxfCredentialCodec.SkipReason.PASSKEY_PRF -> R.string.exchange_skip_prf
                CxfCredentialCodec.SkipReason.PASSKEY_BLOB -> R.string.exchange_skip_blob
                CxfCredentialCodec.SkipReason.PASSKEY_PAYMENT -> R.string.exchange_skip_payment
                CxfCredentialCodec.SkipReason.PRIVATE_KEY -> R.string.exchange_skip_private_key
                CxfCredentialCodec.SkipReason.NONZERO_COUNTER -> R.string.exchange_skip_counter
            }
            Surface(shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 4.dp,
                topEnd = if (index == 0) 20.dp else 4.dp,
                bottomStart = if (index == rows.lastIndex) 20.dp else 4.dp,
                bottomEnd = if (index == rows.lastIndex) 20.dp else 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(label, reasons.getValue(reason)), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp))
            }
        }
    }
}
