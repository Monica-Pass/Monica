package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import takagi.ru.monica.R
import takagi.ru.monica.data.model.BoardingPass
import takagi.ru.monica.data.model.BoardingPassBarcode
import takagi.ru.monica.data.model.BoardingPassParser
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.WalletPassTemplate
import takagi.ru.monica.ui.screens.QrScannerScreen

internal fun WalletPassTemplate.labelRes(): Int = when (this) {
    WalletPassTemplate.MEMBERSHIP -> R.string.wallet_membership_card
    WalletPassTemplate.REWARDS -> R.string.wallet_rewards_card
    WalletPassTemplate.TRAVEL -> R.string.wallet_travel_pass
}

internal fun WalletPassTemplate.icon(): ImageVector = when (this) {
    WalletPassTemplate.MEMBERSHIP -> Icons.Default.Badge
    WalletPassTemplate.REWARDS -> Icons.Default.Star
    WalletPassTemplate.TRAVEL -> Icons.Default.FlightTakeoff
}

internal fun WalletPassTemplate.fields(): List<Pair<String, Int>> = when (this) {
    WalletPassTemplate.MEMBERSHIP -> listOf("tier" to R.string.wallet_pass_tier,
        "website" to R.string.wallet_pass_website)
    WalletPassTemplate.REWARDS -> listOf("tier" to R.string.wallet_pass_tier,
        "points" to R.string.wallet_pass_points, "pointsExpiry" to R.string.wallet_pass_points_expiry,
        "website" to R.string.wallet_pass_website)
    WalletPassTemplate.TRAVEL -> listOf("serviceNumber" to R.string.wallet_pass_service,
        "origin" to R.string.wallet_pass_origin, "destination" to R.string.wallet_pass_destination,
        "departure" to R.string.wallet_pass_departure, "arrival" to R.string.wallet_pass_arrival,
        "terminal" to R.string.wallet_pass_terminal, "gate" to R.string.wallet_pass_gate,
        "seat" to R.string.wallet_pass_seat, "travelClass" to R.string.wallet_pass_class)
}

@Composable
internal fun WalletPassFields(template: WalletPassTemplate,
    number: String, onNumber: (String) -> Unit, holder: String, onHolder: (String) -> Unit,
    issuer: String, onIssuer: (String) -> Unit, expiry: String, onExpiry: (String) -> Unit,
    fields: Map<String, String>, onField: (String, String) -> Unit) {
    TemplateFormSection(stringResource(template.labelRes())) {
        Column(Modifier.fillMaxWidth().testTag("wallet_pass_fields"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WalletProfileField(issuer, onIssuer, R.string.wallet_pass_issuer)
            WalletProfileField(number, onNumber, if (template == WalletPassTemplate.TRAVEL) R.string.wallet_pass_booking else R.string.wallet_pass_number)
            WalletProfileField(holder, onHolder, if (template == WalletPassTemplate.TRAVEL) R.string.wallet_pass_passenger else R.string.wallet_pass_holder)
            template.fields().forEach { (key, label) ->
                Box(Modifier.testTag("wallet_pass_$key")) { WalletProfileField(fields[key].orEmpty(), { onField(key, it) }, label) }
            }
            WalletProfileField(expiry, onExpiry, R.string.expiry_date_label)
        }
    }
}

@Composable
internal fun WalletPassDetails(template: WalletPassTemplate, data: DocumentData) {
    WalletProfileDetails(template.labelRes(), buildList {
        add(R.string.wallet_pass_issuer to data.issuedBy)
        template.fields().forEach { (key, label) -> add(label to data.walletFields[key].orEmpty()) }
        add(R.string.expiry_date_label to data.expiryDate)
    })
}

/** A shared M3E action for original boarding codes and saved membership numbers. */
@Composable
internal fun WalletPassCodeButton(data: DocumentData, onAddCode: () -> Unit = {}, keepassDatabaseId: Long? = null) {
    val template = WalletPassTemplate.fromStored(data.walletTemplate)
    val travel = template == WalletPassTemplate.TRAVEL
    if (template == null || (!travel && data.documentNumber.isBlank())) return
    val unlocked by takagi.ru.monica.security.SessionManager.isUnlocked.collectAsState()
    val lockGeneration by takagi.ru.monica.security.SessionManager.lockGeneration.collectAsState()
    val vaultLocks by takagi.ru.monica.utils.KeePassKdbxService.nativeLockVersions.collectAsState()
    val initialVaultLock = remember(keepassDatabaseId) { vaultLocks[keepassDatabaseId] ?: 0L }
    val initialAppLock = remember { lockGeneration }
    var showCode by remember(data, keepassDatabaseId) { mutableStateOf(false) }
    if (!unlocked || lockGeneration != initialAppLock || (vaultLocks[keepassDatabaseId] ?: 0L) != initialVaultLock) return
    val code = data.boardingPassBarcode
    val missing = travel && code == null
    WalletCodeAction(
        title = stringResource(if (missing) R.string.boarding_code_add else if (travel) R.string.boarding_code_show else R.string.wallet_pass_show_code),
        supporting = stringResource(if (missing) R.string.boarding_code_missing else if (travel) R.string.boarding_code_saved else R.string.wallet_code_hint),
        icon = if (missing) Icons.Default.QrCodeScanner else Icons.Default.QrCode2,
        modifier = Modifier.testTag(if (travel) "boarding_pass_show_code" else "wallet_pass_show_code"),
        onClick = { if (missing) onAddCode() else showCode = true }
    )
    if (showCode && travel && code != null) BoardingPassCodePage(code, onDismiss = { showCode = false })
    else if (showCode && !travel) FieldBarcodePage(
        label = stringResource(R.string.wallet_pass_number),
        value = data.documentNumber,
        onDismiss = { showCode = false }
    )
}

@Composable
private fun WalletCodeAction(title: String, supporting: String, icon: ImageVector,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.padding(16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(28.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

/** The caller retains the complete code in the draft and persists only on Save. */
@Composable
internal fun BoardingPassScanButton(hasCode: Boolean = false, onRemove: () -> Unit = {},
    onScanned: (BoardingPass, BoardingPassBarcode) -> Unit) {
    var scanning by rememberSaveable { mutableStateOf(false) }
    WalletCodeAction(
        title = stringResource(if (hasCode) R.string.boarding_code_rescan else R.string.wallet_pass_scan_boarding_pass),
        supporting = stringResource(if (hasCode) R.string.boarding_code_draft_hint else R.string.wallet_pass_scan_boarding_pass_hint),
        icon = Icons.Default.QrCodeScanner,
        modifier = Modifier.testTag("wallet_pass_scan_boarding_pass"),
        onClick = { scanning = true }
    )
    if (hasCode) {
        TextButton(onClick = onRemove, modifier = Modifier.testTag("boarding_pass_remove_code")) {
            Icon(Icons.Default.DeleteOutline, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.boarding_code_remove))
        }
    }
    if (scanning) Dialog(
        onDismissRequest = { scanning = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        QrScannerScreen(
            onQrCodeScanned = {},
            onBarcodeScanned = { raw, format ->
                if (scanning) {
                    BoardingPassParser.parse(raw)?.let { onScanned(it, BoardingPassBarcode(raw, format.name)) }
                    scanning = false
                }
            },
            onNavigateBack = { scanning = false },
            title = stringResource(R.string.wallet_pass_scan_boarding_pass),
            subtitle = stringResource(R.string.wallet_pass_scan_boarding_pass_hint),
            allowedFormats = BoardingPassFormats,
            preserveWhitespace = true,
            resultValidator = { BoardingPassParser.parse(it) != null },
            invalidResultMessage = stringResource(R.string.wallet_pass_boarding_pass_invalid),
        )
    }
}
