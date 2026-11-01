package takagi.ru.monica.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.lansync.*
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.isUsable
import takagi.ru.monica.ui.components.NearbyPasskeyPreflightDialog
import takagi.ru.monica.ui.components.NearbyPasskeyRestrictionsDialog
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import java.text.DateFormat
import java.util.Date

@Composable
fun LanSyncScreen(onNavigateBack: () -> Unit, onScanPairingQr: () -> Unit) {
    val context = LocalContext.current
    val coordinator = remember { DeviceSyncCoordinator.get(context) }
    val hotspot = remember { NearbyHotspotSession.get(context) }
    val hotspotState by hotspot.state.collectAsStateWithLifecycle()
    var pendingHost by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pendingHost = false }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val requested = pendingHost; pendingHost = false
        if (requested && NearbyHotspotSession.requiredPermissions().all { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }) {
            hotspot.host { coordinator.pair() }
        } else if (requested) hotspot.permissionDenied()
    }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val databaseFlow = remember(context) { PasswordDatabase.getDatabase(context).localMdbxDatabaseDao().getAvailableDatabases() }
    val databases by databaseFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    DisposableEffect(coordinator, hotspot) { onDispose { pendingHost = false; coordinator.cancelPairing(); hotspot.close() } }
    state.passkeyReview?.let { review ->
        NearbyPasskeyPreflightDialog(review, onCancel = coordinator::cancelPasskeySync,
            onContinue = { dontAskAgain -> coordinator.confirmPasskeySync(review, dontAskAgain) })
    }
    NearbySyncContent(state, NearbySyncActions(onNavigateBack, onScanPairingQr, { coordinator.pair() },
        { coordinator.pair(it) }, coordinator::cancelPairing, coordinator::setEnabled, coordinator::setMode,
        coordinator::setInterval, coordinator::syncNow, coordinator::forget, coordinator::setDatabase, coordinator::cancelSync,
        coordinator::setPasskeyNoticeSuppressed, coordinator::refreshPasskeyDiagnostics, coordinator::forgetDevices,
        coordinator::confirmReceivedDeletions,
        createHotspot = {
            if (NearbyHotspotSession.requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                hotspot.host { coordinator.pair() }
            } else { pendingHost = true; permissions.launch(NearbyHotspotSession.requiredPermissions()) }
        }, cancelHotspot = { hotspot.close(); coordinator.cancelPairing() }, hotspotInvitation = hotspot::invitation),
        databases.filter { it.isUsable }, hotspotState)
}

@Composable
fun LanSyncScannerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val coordinator = remember { DeviceSyncCoordinator.get(context) }
    val hotspot = remember { NearbyHotspotSession.get(context) }
    val hotspotState by hotspot.state.collectAsStateWithLifecycle()
    var handled by remember { mutableStateOf(false) }
    var networkInvitation by remember { mutableStateOf<NearbyHotspotInvitation?>(null) }
    var keepConnection by remember { mutableStateOf(false) }
    var waitingPermission by remember { mutableStateOf(false) }
    var interrupted by remember { mutableStateOf(false) }
    var scanEpoch by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    fun proceed(pairing: String) {
        keepConnection = true; networkInvitation = null
        onNavigateBack(); coordinator.pair(pairing)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val requested = waitingPermission; waitingPermission = false
        val invitation = networkInvitation
        if (requested && invitation != null) {
            if (NearbyHotspotSession.requiredPermissions().all { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }) {
                hotspot.join(invitation) { proceed(invitation.pairing) }
            } else hotspot.permissionDenied()
        }
    }
    DisposableEffect(owner, hotspot) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!keepConnection && hotspot.state.value.phase == "connecting") interrupted = true
                waitingPermission = false; networkInvitation = null; handled = false
                scanEpoch++
                if (!keepConnection) hotspot.close()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); waitingPermission = false; networkInvitation = null; if (!keepConnection) hotspot.close() }
    }
    key(scanEpoch) { QrScannerScreen(
        onQrCodeScanned = { if (!handled) {
            handled = true
            val network = NearbyHotspotInvitation.parse(it)
            if (network == null) { onNavigateBack(); coordinator.pair(it) } else networkInvitation = network
        } },
        onNavigateBack = onNavigateBack, title = stringResource(R.string.lan_scan),
        subtitle = stringResource(R.string.nearby_hotspot_scan_hint), allowedFormats = listOf(BarcodeFormat.QR_CODE),
        resultValidator = { !interrupted && (LanPairingInvitation.parse(it)?.version == 2 || NearbyHotspotInvitation.parse(it) != null) },
        invalidResultMessage = stringResource(R.string.device_sync_qr_invalid),
        diagnosticLabel = "lan-pairing", onDiagnostic = DeviceSyncDiagLogger::scanner) }
    if (interrupted) NearbyHotspotInterruptedDialog(onDismiss = { interrupted = false },
        onOpenWifi = { interrupted = false; runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } })
    networkInvitation?.let { invitation ->
        AlertDialog(onDismissRequest = { networkInvitation = null; handled = false; waitingPermission = false; scanEpoch++; hotspot.close() },
            title = { Text(stringResource(R.string.nearby_hotspot_join_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.nearby_hotspot_join_hint, invitation.ssid))
                if (hotspotState.phase == "connecting") {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.nearby_hotspot_connecting))
                }
                hotspotState.error?.let {
                    Text(hotspotError(it), color = MaterialTheme.colorScheme.error)
                    OutlinedButton({ copyNearbyText(context, invitation.passphrase, true) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nearby_hotspot_copy_password))
                    }
                    OutlinedButton({ runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nearby_hotspot_open_wifi))
                    }
                    OutlinedButton({ hotspot.close(); proceed(invitation.pairing) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nearby_hotspot_manual))
                    }
                }
            } },
            confirmButton = { TextButton({
                if (NearbyHotspotSession.requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                    hotspot.join(invitation) { proceed(invitation.pairing) }
                } else { waitingPermission = true; permissions.launch(NearbyHotspotSession.requiredPermissions()) }
            }, enabled = hotspotState.phase != "connecting" && !waitingPermission) { Text(stringResource(R.string.nearby_hotspot_connect)) } },
            dismissButton = { TextButton({ networkInvitation = null; handled = false; waitingPermission = false; scanEpoch++; hotspot.close() }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Keeps only a fixed interruption message; the scanned credentials have already been cleared. */
@Composable
internal fun NearbyHotspotInterruptedDialog(onDismiss: () -> Unit, onOpenWifi: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nearby_hotspot_title)) },
        text = { Text(stringResource(R.string.nearby_hotspot_interrupted), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onOpenWifi) { Text(stringResource(R.string.nearby_hotspot_open_wifi)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

internal data class NearbySyncActions(
    val back: () -> Unit = {}, val scan: () -> Unit = {}, val showQr: () -> Unit = {},
    val connect: (String) -> Unit = {}, val cancelPairing: () -> Unit = {},
    val enabled: (Boolean) -> Unit = {}, val mode: (DeviceSyncMode) -> Unit = {},
    val interval: (Int) -> Unit = {}, val sync: () -> Unit = {}, val forget: (String) -> Unit = {},
    val database: (Long?) -> Unit = {}, val cancelSync: () -> Unit = {},
    val suppressPasskeyNotice: (Boolean) -> Unit = {}, val refreshPasskeys: () -> Unit = {},
    val forgetDevices: ((Set<String>) -> Unit)? = null,
    val receivedDeletions: (DeviceDeletionReview, Boolean) -> Unit = { _, _ -> },
    val createHotspot: (() -> Unit)? = null, val cancelHotspot: () -> Unit = {},
    val hotspotInvitation: (String) -> String? = { null })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearbySyncContent(state: DeviceSyncState, actions: NearbySyncActions, databases: List<LocalMdbxDatabase> = emptyList(), hotspot: NearbyHotspotState = NearbyHotspotState()) {
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var invitation by remember { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("10") }
    var custom by rememberSaveable { mutableStateOf(false) }
    var viewingPasskeys by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            invitation = ""
            if (sheet == "hotspot") { actions.cancelHotspot(); sheet = null }
            if (sheet == "qr") { actions.cancelPairing(); sheet = null }
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { invitation = ""; owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sheet, state.databaseId) { if (sheet == "details") actions.refreshPasskeys() }
    if (viewingPasskeys && state.passkeyReview == null) {
        NearbyPasskeyRestrictionsDialog(state.passkeyDiagnosticIssues.orEmpty()) { viewingPasskeys = false }
    }
    val databaseName = if (state.databaseId == null) stringResource(R.string.nearby_local)
        else databases.firstOrNull { it.id == state.databaseId }?.name ?: stringResource(R.string.nearby_database_unavailable)
    LaunchedEffect(state.phase) {
        if (state.phase == "completed" || state.phase == "locked") {
            if (sheet == "qr" || sheet == "link") sheet = null
            invitation = ""
        }
    }
    fun closeSheet() {
        if (sheet == "qr") actions.cancelPairing()
        if (sheet == "hotspot") actions.cancelHotspot()
        invitation = ""; sheet = null
    }
    if (custom) {
        val value = minutes.toIntOrNull()
        AlertDialog(onDismissRequest = { custom = false }, title = { Text(stringResource(R.string.device_sync_custom)) },
            text = { OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.device_sync_minutes_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = value == null || value !in 1..1440) },
            confirmButton = { TextButton({ actions.interval(requireNotNull(value) * 60); custom = false; sheet = null },
                enabled = value != null && value in 1..1440) { Text(stringResource(R.string.device_sync_save)) } },
            dismissButton = { TextButton({ custom = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (takagi.ru.monica.ui.password.LocalNearbySyncMenu.current == null) state.deletionReview?.let { review ->
        NearbyDeletionReviewDialog(review, actions.cancelSync) { actions.receivedDeletions(review, it) }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = ::closeSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (sheet) {
                "qr" -> {
                    Text(stringResource(R.string.nearby_pair_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.nearby_share_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val link = state.invitations.firstOrNull().orEmpty()
                    if (link.isNotEmpty()) {
                        NearbyPairingCode(link)
                        Text(stringResource(R.string.device_sync_scan_other))
                        Button({ copyNearbyText(context, link, true) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.lan_copy_link))
                        }
                    } else if (state.error == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.nearby_generating))
                    }
                    if (state.error != null) {
                        Text(nearbyError(state), color = MaterialTheme.colorScheme.error)
                        TextButton(actions.showQr, enabled = !state.pairing) { Text(stringResource(R.string.nearby_regenerate)) }
                    }
                    TextButton(::closeSheet, Modifier.fillMaxWidth()) { Text(stringResource(R.string.nearby_cancel_pairing)) }
                }
                "hotspot" -> {
                    Text(stringResource(R.string.nearby_hotspot_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.nearby_hotspot_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val link = actions.hotspotInvitation(state.invitations.firstOrNull().orEmpty()).orEmpty()
                    if (link.isNotEmpty()) {
                        NearbyPairingCode(link)
                        Text(hotspot.networkName, style = MaterialTheme.typography.titleMedium)
                        Button({ copyNearbyText(context, link, true) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.lan_copy_link))
                        }
                    } else if (hotspot.hosting && !state.pairing && state.error == null) {
                        Text(stringResource(R.string.nearby_hotspot_active), style = MaterialTheme.typography.titleMedium)
                        TextButton(actions.showQr) { Text(stringResource(R.string.nearby_regenerate)) }
                    } else if (hotspot.error == null && state.error == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.nearby_hotspot_starting))
                    }
                    hotspot.error?.let { Text(hotspotError(it), color = MaterialTheme.colorScheme.error) }
                    if (state.error != null) Text(nearbyError(state), color = MaterialTheme.colorScheme.error)
                    if (hotspot.error != null || state.error != null) {
                        TextButton({ actions.createHotspot?.invoke() }) { Text(stringResource(R.string.nearby_regenerate)) }
                    }
                    TextButton(::closeSheet, Modifier.fillMaxWidth()) { Text(stringResource(R.string.nearby_hotspot_stop)) }
                }
                "link" -> {
                    Text(stringResource(R.string.device_sync_use_link), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.nearby_paste_hint))
                    val valid = LanPairingInvitation.parse(invitation.trim())?.version == 2
                    OutlinedTextField(invitation, { invitation = it.take(512) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.lan_paste_link)) }, minLines = 2, maxLines = 4,
                        isError = invitation.isNotBlank() && !valid,
                        supportingText = { if (invitation.isNotBlank() && !valid) Text(stringResource(R.string.device_sync_qr_invalid)) })
                    Button({ actions.connect(invitation.trim()); sheet = null }, Modifier.fillMaxWidth(), enabled = valid && !state.pairing) {
                        Text(stringResource(R.string.device_sync_connect))
                    }
                }
                "mode" -> {
                    Text(stringResource(R.string.device_sync_direction), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.device_sync_direction_hint))
                    SettingsPanelGroup("") { DeviceSyncMode.entries.forEach { mode ->
                        SettingsPanelRow(Icons.Default.SyncAlt, modeName(mode), "", onClick = { actions.mode(mode); sheet = null },
                            enabled = !state.pairing, trailing = { RadioButton(state.mode == mode, onClick = null) })
                    } }
                }
                "database" -> {
                    Text(stringResource(R.string.nearby_database), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.nearby_database_hint))
                    SettingsPanelGroup("") {
                        SettingsPanelRow(Icons.Default.Storage, stringResource(R.string.nearby_local), stringResource(R.string.nearby_local_hint),
                            onClick = { actions.database(null); sheet = null }, trailing = { RadioButton(state.databaseId == null, onClick = null) })
                        databases.forEach { database ->
                            SettingsPanelRow(Icons.Default.Storage, database.name, "MDBX · ${database.tigaModeEnum.label}",
                                onClick = { actions.database(database.id); sheet = null }, trailing = { RadioButton(state.databaseId == database.id, onClick = null) })
                        }
                    }
                    Text(stringResource(R.string.nearby_database_scope), style = MaterialTheme.typography.bodySmall)
                }
                "frequency" -> {
                    Text(stringResource(R.string.device_sync_frequency), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.device_sync_frequency_hint))
                    SettingsPanelGroup("") {
                        listOf(0, 60, 300, 900, 1800).forEach { seconds ->
                            SettingsPanelRow(Icons.Default.Schedule, frequencyName(seconds), "", onClick = { actions.interval(seconds); sheet = null },
                                trailing = { RadioButton(state.intervalSeconds == seconds, onClick = null) })
                        }
                        SettingsPanelRow(Icons.Default.Edit, stringResource(R.string.device_sync_custom), "", onClick = {
                            minutes = (state.intervalSeconds / 60).coerceAtLeast(1).toString(); custom = true
                        })
                    }
                }
                "details" -> NearbySyncDetails(state, databaseName, copy = { copyNearbyText(context, it, false) },
                    suppressPasskeyNotice = actions.suppressPasskeyNotice, refreshPasskeys = actions.refreshPasskeys,
                    viewPasskeys = { viewingPasskeys = true })
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.lan_title)) }, navigationIcon = {
        IconButton(actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NearbySyncStatus(state, diagnostics = { sheet = "details" }, cancelPairing = actions.cancelPairing,
                sync = actions.sync, cancelSync = actions.cancelSync)
            Text(stringResource(R.string.nearby_intro), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsPanelGroup(stringResource(R.string.nearby_settings)) {
                SettingsPanelRow(Icons.Default.Storage, stringResource(R.string.nearby_database), databaseName,
                    onClick = { sheet = "database" }, enabled = !state.pairing && !state.manualSync)
                SettingsPanelRow(Icons.Default.Sync, stringResource(R.string.device_sync_automatic),
                    stringResource(if (state.enabled) R.string.nearby_automatic_hint else R.string.nearby_manual_hint),
                    checked = state.enabled, onCheckedChange = actions.enabled)
                SettingsPanelRow(Icons.Default.SyncAlt, stringResource(R.string.device_sync_direction), modeName(state.mode), onClick = { sheet = "mode" }, enabled = !state.pairing && !state.manualSync)
                SettingsPanelRow(Icons.Default.Schedule, stringResource(R.string.nearby_auto_frequency),
                    if (state.enabled) frequencyName(state.intervalSeconds) else stringResource(R.string.nearby_frequency_disabled),
                    onClick = { sheet = "frequency" }, enabled = state.enabled)
            }
            NearbyPairedDevices(state.peers) { ids ->
                actions.forgetDevices?.invoke(ids) ?: ids.forEach(actions.forget)
            }
            SettingsPanelGroup(stringResource(R.string.nearby_add_device)) {
                SettingsPanelRow(Icons.Default.QrCode2, stringResource(R.string.device_sync_show_qr), stringResource(R.string.nearby_generate_hint),
                    onClick = { sheet = "qr"; actions.showQr() }, enabled = !state.pairing)
                SettingsPanelRow(Icons.Default.QrCodeScanner, stringResource(R.string.lan_scan), stringResource(R.string.nearby_scan_subtitle),
                    onClick = actions.scan, enabled = !state.pairing)
                SettingsPanelRow(Icons.Default.Link, stringResource(R.string.device_sync_use_link), stringResource(R.string.nearby_paste_hint),
                    onClick = { sheet = "link" }, enabled = !state.pairing)
                actions.createHotspot?.let { create ->
                    SettingsPanelRow(Icons.Default.WifiTethering, stringResource(if (hotspot.hosting) R.string.nearby_hotspot_active else R.string.nearby_hotspot_create),
                        stringResource(R.string.nearby_hotspot_create_hint), onClick = {
                            sheet = "hotspot"; if (!hotspot.hosting) create()
                        }, enabled = hotspot.hosting || !state.pairing && !state.manualSync)
                }
            }
            SettingsPanelGroup("") {
                SettingsPanelRow(Icons.Default.Info, stringResource(R.string.nearby_details), nearbyStageLabel(context, state), onClick = { sheet = "details" })
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun hotspotError(error: String): String = stringResource(when (error) {
    "permission_denied" -> R.string.nearby_hotspot_permissions
    "unsupported" -> R.string.nearby_hotspot_unsupported
    else -> R.string.nearby_hotspot_failure
})

@Composable private fun modeName(mode: DeviceSyncMode): String = stringResource(when (mode) {
    DeviceSyncMode.BIDIRECTIONAL -> R.string.device_sync_bidirectional
    DeviceSyncMode.SEND_ONLY -> R.string.device_sync_send_only
    DeviceSyncMode.RECEIVE_ONLY -> R.string.device_sync_receive_only
})
@Composable private fun frequencyName(seconds: Int): String = if (seconds == 0) stringResource(R.string.device_sync_realtime)
    else stringResource(R.string.device_sync_every_minutes, seconds / 60)

@Composable private fun NearbyPairingCode(link: String) {
    val pixels = with(LocalDensity.current) { 264.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, link, pixels) {
        value = withContext(Dispatchers.Default) { runCatching { BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, pixels, pixels) }.getOrNull() }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.device_sync_show_qr),
            Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp), filterQuality = FilterQuality.None) }
    }
}
private fun copyNearbyText(context: Context, value: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("Monica", value)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
@Composable internal fun nearbyError(state: DeviceSyncState): String {
    val error = state.error.orEmpty()
    return stringResource(when (error) {
        "connection_failed", "listen_failed", "discovery_unavailable" -> R.string.device_sync_connection_failed
        "pairing_rejected", "authentication_failed", "different_device" -> R.string.device_sync_pairing_failed
        "peer_merge_failed" -> R.string.lan_peer_failed
        "unsupported_protocol", "invalid_invitation" -> R.string.device_sync_qr_invalid
        "pairing_expired" -> R.string.lan_expired
        "same_device" -> R.string.device_sync_same_device
        "too_many_devices" -> R.string.device_sync_limit
        "native_unavailable", "device_sync_unavailable" -> R.string.lan_unavailable
        "invalid_password_payload", "invalid_record", "invalid_vault_payload" -> R.string.device_sync_invalid_data
        "invalid_asset", "asset_unavailable", "invalid_asset_identity" -> R.string.device_sync_asset_failed
        "folder_trash_unavailable", "folder_attachment_trash_unavailable" -> R.string.device_sync_folder_trash_unsupported
        "invalid_passkey_material", "passkey_key_unavailable" -> R.string.device_sync_passkey_failed
        "sync_database_unavailable" -> R.string.nearby_database_unavailable
        "sync_direction_conflict" -> R.string.device_sync_direction_conflict
        "sync_direction_unsupported" -> R.string.device_sync_direction_unsupported
        else -> when {
            error.startsWith("storage_") -> R.string.device_sync_storage_failed
            state.failureStage == "preparing" -> R.string.device_sync_prepare_failed
            state.failureStage in setOf("applying", "received", "remembering") -> R.string.device_sync_apply_failed
            else -> R.string.device_sync_failed
        }
    })
}
