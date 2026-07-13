package takagi.ru.monica.steam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.UnifiedProgressBarMode
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.core.SteamTotp
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.data.SteamMaFileTransferAction
import takagi.ru.monica.steam.data.SteamStorageSource
import takagi.ru.monica.steam.network.SteamAuthorizedDevice
import takagi.ru.monica.steam.network.SteamConfirmation
import takagi.ru.monica.steam.network.SteamPendingLogin
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.common.state.rememberSaveableLazyListState
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.MonicaModalBottomSheet
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.ui.components.PasswordEntryPickerBottomSheet
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedProgressBar
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import takagi.ru.monica.ui.navigation.easyNotesScreenEnter
import takagi.ru.monica.ui.navigation.easyNotesScreenExit
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu
import takagi.ru.monica.ui.rememberTotpTickerMillis
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.SettingsManager
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val STEAM_AVATAR_TIMEOUT_MS = 4_000
private const val STEAM_AVATAR_CACHE_TTL_MS = 3L * 24L * 60L * 60L * 1000L
private const val STEAM_CONFIRMATION_IMAGE_TIMEOUT_MS = 4_000
private const val STEAM_CONFIRMATION_IMAGE_CACHE_TTL_MS = 3L * 24L * 60L * 60L * 1000L

private data class LegacySteamAuthenticatorCodeSource(
    val item: SecureItem,
    val totpData: TotpData,
    val code: String
)

private enum class SteamSection(
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    CODE(R.string.steam_section_code, Icons.Default.Key),
    CONFIRMATIONS(R.string.steam_section_confirmations, Icons.Default.VerifiedUser)
}

private enum class SteamAddAccountMethod {
    MAFILE,
    KEY_ONLY,
    LOGIN,
    QR_LOGIN
}

private data class ConfirmationActionRequest(
    val confirmations: List<SteamConfirmation>,
    val accept: Boolean
)

private data class LoginActionRequest(
    val login: SteamPendingLogin
)

private data class SteamDeleteAccountsRequest(
    val accounts: List<SteamAccount>
)

private data class SteamTransferAccountsRequest(
    val accounts: List<SteamAccount>
)

private data class SteamTransferTarget(
    val source: SteamStorageSource,
    val label: String,
    val icon: ImageVector
)

private enum class SteamAuthenticatorRemovalMode {
    REMOTE,
    LOCAL_ONLY
}

@Composable
fun SteamScreen(
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    modifier: Modifier = Modifier,
    pendingSteamQrResult: String? = null,
    pendingSteamQrAccountId: Long? = null,
    onConsumePendingSteamQrResult: () -> Unit = {},
    onScanSteamQrCode: ((Long?) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val securityManager = remember(context) { SecurityManager(context.applicationContext) }
    val biometricHelper = remember(context) { BiometricHelper(context) }
    val viewModel: SteamViewModel = viewModel(
        factory = remember(context) { SteamViewModel.factory(context) }
    )
    val passwordDatabase = remember(context) {
        PasswordDatabase.getDatabase(context.applicationContext)
    }
    val mdbxDatabasesState by passwordDatabase.localMdbxDatabaseDao()
        .getAllDatabases()
        .collectAsState(initial = null)
    val mdbxDatabases = mdbxDatabasesState.orEmpty()
    val mdbxDatabasesLoaded = mdbxDatabasesState != null
    val settingsManager = remember { SettingsManager(context.applicationContext) }
    val appSettings by settingsManager.settingsFlow.collectAsState(initial = AppSettings())
    val uiState by viewModel.uiState.collectAsState()
    val selectedAccount = uiState.accounts.firstOrNull { it.id == uiState.selectedAccountId }
        ?: uiState.accounts.firstOrNull()
    var detailAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    val detailAccount = uiState.accounts.firstOrNull { it.id == detailAccountId }
    var selectedSection by rememberSaveable { mutableStateOf(SteamSection.CODE) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    var showStorageSourceMenu by remember { mutableStateOf(false) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var addAccountMethod by remember { mutableStateOf<SteamAddAccountMethod?>(null) }
    var steamIdCompletionAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    val steamIdCompletionAccount = uiState.accounts.firstOrNull { it.id == steamIdCompletionAccountId }
    var steamAccountRebindAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    val steamAccountRebindAccount = uiState.accounts.firstOrNull { it.id == steamAccountRebindAccountId }
    var selectedTokenAccountIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var lastSteamQrAccountId by remember { mutableStateOf(readLastSteamQrAccountId(context)) }
    var deleteRequest by remember { mutableStateOf<SteamDeleteAccountsRequest?>(null) }
    var transferRequest by remember { mutableStateOf<SteamTransferAccountsRequest?>(null) }
    var editRemarkAccount by remember { mutableStateOf<SteamAccount?>(null) }
    var removeAuthenticatorRequest by remember { mutableStateOf<SteamAccount?>(null) }
    var removeAuthenticatorVerifyAccount by remember { mutableStateOf<SteamAccount?>(null) }
    var removeAuthenticatorVerifyMode by remember { mutableStateOf(SteamAuthenticatorRemovalMode.REMOTE) }
    var removeAuthenticatorPasswordInput by remember { mutableStateOf("") }
    var removeAuthenticatorPasswordError by remember { mutableStateOf(false) }
    var scannedQrPayload by remember { mutableStateOf<String?>(null) }
    var pendingLoginAction by remember { mutableStateOf<LoginActionRequest?>(null) }
    var autoPromptedLoginClientIds by remember(selectedAccount?.id) { mutableStateOf<Set<Long>>(emptySet()) }
    val pendingConfirmationCount = if (selectedAccount?.canUseConfirmations == true) {
        uiState.confirmations.size
    } else {
        0
    }
    val tokenQrAccount = remember(
        uiState.accounts,
        lastSteamQrAccountId,
        selectedAccount?.id,
        detailAccount,
        selectedSection,
        selectedTokenAccountIds
    ) {
        if (detailAccount == null &&
            selectedSection == SteamSection.CODE &&
            selectedTokenAccountIds.isEmpty()
        ) {
            val approvableAccounts = uiState.accounts.filter { it.canApproveLogins }
            approvableAccounts.firstOrNull { it.id == lastSteamQrAccountId }
                ?: approvableAccounts.firstOrNull { it.id == selectedAccount?.id }
                ?: approvableAccounts.firstOrNull()
        } else {
            null
        }
    }

    fun rememberLastSteamQrAccount(accountId: Long?) {
        lastSteamQrAccountId = accountId
        saveLastSteamQrAccountId(context, accountId)
    }

    LaunchedEffect(steamIdCompletionAccount?.id, steamIdCompletionAccount?.hasRealSteamId) {
        if (steamIdCompletionAccount?.hasRealSteamId == true || steamIdCompletionAccount == null) {
            steamIdCompletionAccountId = null
        }
    }

    fun dismissRemoveAuthenticatorVerify() {
        removeAuthenticatorVerifyAccount = null
        removeAuthenticatorVerifyMode = SteamAuthenticatorRemovalMode.REMOTE
        removeAuthenticatorPasswordInput = ""
        removeAuthenticatorPasswordError = false
    }

    fun requestRemoveAuthenticatorVerification(
        account: SteamAccount,
        mode: SteamAuthenticatorRemovalMode
    ) {
        removeAuthenticatorRequest = null
        removeAuthenticatorVerifyAccount = account
        removeAuthenticatorVerifyMode = mode
        removeAuthenticatorPasswordInput = ""
        removeAuthenticatorPasswordError = false
    }

    fun confirmRemoveAuthenticator(
        account: SteamAccount,
        mode: SteamAuthenticatorRemovalMode
    ) {
        dismissRemoveAuthenticatorVerify()
        when (mode) {
            SteamAuthenticatorRemovalMode.REMOTE -> viewModel.removeAuthenticator(account.id)
            SteamAuthenticatorRemovalMode.LOCAL_ONLY -> {
                if (detailAccountId == account.id) {
                    detailAccountId = null
                    scannedQrPayload = null
                }
                viewModel.deleteLocalAuthenticator(account.id)
            }
        }
    }

    LaunchedEffect(selectedAccount?.id) {
        if (selectedAccount == null) {
            selectedSection = SteamSection.CODE
        }
    }

    LaunchedEffect(uiState.storageSource, mdbxDatabasesLoaded, mdbxDatabases.map { it.id }) {
        val source = uiState.storageSource
        if (
            mdbxDatabasesLoaded &&
            source is SteamStorageSource.Mdbx &&
            mdbxDatabases.none { it.id == source.databaseId }
        ) {
            viewModel.selectStorageSource(SteamStorageSource.Local)
        }
    }

    LaunchedEffect(detailAccountId, uiState.accounts) {
        if (detailAccountId != null && detailAccount == null) {
            detailAccountId = null
        }
    }

    LaunchedEffect(uiState.accounts) {
        val existingIds = uiState.accounts.map { it.id }.toSet()
        val prunedSelection = selectedTokenAccountIds.filter { it in existingIds }
        if (prunedSelection != selectedTokenAccountIds) {
            selectedTokenAccountIds = prunedSelection
        }
        if (uiState.accounts.isNotEmpty() && lastSteamQrAccountId != null && lastSteamQrAccountId !in existingIds) {
            rememberLastSteamQrAccount(null)
        }
    }

    LaunchedEffect(selectedAccount?.id, selectedAccount?.canUseConfirmations) {
        if (selectedAccount?.canUseConfirmations == true) {
            viewModel.refreshConfirmations(silent = true)
        }
    }

    LaunchedEffect(
        selectedAccount?.id,
        selectedAccount?.canApproveLogins,
        selectedSection,
        detailAccountId
    ) {
        if (
            selectedAccount?.canApproveLogins == true &&
            detailAccountId == null &&
            selectedSection == SteamSection.CODE
        ) {
            viewModel.refreshPendingLogins(silent = true)
        }
    }

    LaunchedEffect(detailAccount?.id, detailAccount?.canApproveLogins) {
        if (detailAccount?.canApproveLogins == true) {
            viewModel.refreshPendingLogins(silent = true)
        }
    }

    LaunchedEffect(detailAccount?.id, detailAccount?.accessToken) {
        detailAccount?.let { account ->
            viewModel.refreshAuthorizedDevices(account.id, silent = true)
        }
    }

    LaunchedEffect(uiState.accounts.size) {
        if (uiState.accounts.isNotEmpty()) {
            showAddAccountDialog = false
            addAccountMethod = null
        }
    }

    LaunchedEffect(pendingSteamQrResult, pendingSteamQrAccountId, uiState.accounts, selectedAccount?.id) {
        val qr = pendingSteamQrResult?.trim().orEmpty()
        if (qr.isNotBlank()) {
            val targetAccount = uiState.accounts.firstOrNull { it.id == pendingSteamQrAccountId }
                ?: selectedAccount
            if (pendingSteamQrAccountId == null || targetAccount?.id == pendingSteamQrAccountId) {
                targetAccount?.let { account ->
                    viewModel.selectAccount(account.id)
                    detailAccountId = account.id
                    rememberLastSteamQrAccount(account.id)
                }
                scannedQrPayload = qr
                onConsumePendingSteamQrResult()
            }
        }
    }

    if (detailAccount != null) {
        BackHandler {
            detailAccountId = null
            scannedQrPayload = null
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (message == context.getString(R.string.steam_account_rebind_done)) {
                steamAccountRebindAccountId = null
            }
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(selectedAccount?.id, selectedAccount?.canApproveLogins, uiState.pendingLogins) {
        val activeIds = uiState.pendingLogins.map { it.clientId }.toSet()
        val promptedActiveIds = autoPromptedLoginClientIds.intersect(activeIds)
        if (promptedActiveIds != autoPromptedLoginClientIds) {
            autoPromptedLoginClientIds = promptedActiveIds
        }
        if (selectedAccount?.canApproveLogins == true && pendingLoginAction == null) {
            val login = uiState.pendingLogins.firstOrNull { it.clientId !in promptedActiveIds }
            if (login != null) {
                autoPromptedLoginClientIds = promptedActiveIds + login.clientId
                SteamLoginNotificationHelper.show(context, login)
                pendingLoginAction = LoginActionRequest(login)
            }
        }
    }

    pendingLoginAction?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingLoginAction = null },
            title = {
                Text(stringResource(R.string.steam_login_request_title))
            },
            text = {
                LoginActionDetails(request.login)
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.respondPendingLogin(request.login, false)
                            pendingLoginAction = null
                        }
                    ) {
                        Text(stringResource(R.string.steam_reject))
                    }
                    TextButton(
                        onClick = {
                            viewModel.respondPendingLogin(request.login, true)
                            pendingLoginAction = null
                        }
                    ) {
                        Text(stringResource(R.string.steam_approve))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLoginAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAddAccountDialog) {
        SteamAddMethodDialog(
            onDismissRequest = { showAddAccountDialog = false },
            onSelectMaFile = {
                showAddAccountDialog = false
                addAccountMethod = SteamAddAccountMethod.MAFILE
            },
            onSelectKeyOnly = {
                showAddAccountDialog = false
                addAccountMethod = SteamAddAccountMethod.KEY_ONLY
            },
            onSelectLogin = {
                showAddAccountDialog = false
                addAccountMethod = SteamAddAccountMethod.LOGIN
            },
            onSelectQrLogin = {
                showAddAccountDialog = false
                addAccountMethod = SteamAddAccountMethod.QR_LOGIN
                viewModel.beginSteamQrLogin()
            }
        )
    }

    when (addAccountMethod) {
        SteamAddAccountMethod.MAFILE -> {
            if (uiState.pendingMaFileSteamIdRequest == null) {
                SteamMaFileImportDialog(
                    onDismissRequest = { addAccountMethod = null },
                    onImportMaFile = viewModel::importMaFile
                )
            }
        }
        SteamAddAccountMethod.KEY_ONLY -> SteamKeyOnlyImportDialog(
            onDismissRequest = { addAccountMethod = null },
            onImport = { displayName, accountName, sharedSecret ->
                viewModel.importCodeOnlyKey(displayName, accountName, sharedSecret)
                addAccountMethod = null
            }
        )
        SteamAddAccountMethod.LOGIN -> SteamLoginImportDialog(
            pendingChallenge = uiState.pendingLoginChallenge,
            availableCodeAccounts = uiState.accounts,
            onDismissRequest = {
                viewModel.cancelSteamLoginChallenge()
                addAccountMethod = null
            },
            onBeginLogin = viewModel::beginSteamLogin,
            onSubmitLoginCode = viewModel::submitSteamLoginCode
        )
        SteamAddAccountMethod.QR_LOGIN -> SteamQrLoginImportDialog(
            pendingQrChallenge = uiState.pendingQrLoginChallenge,
            pendingChallenge = uiState.pendingLoginChallenge,
            availableCodeAccounts = uiState.accounts,
            loading = uiState.loading,
            onDismissRequest = {
                viewModel.cancelSteamLoginChallenge()
                addAccountMethod = null
            },
            onRestart = viewModel::beginSteamQrLogin,
            onSubmitLoginCode = viewModel::submitSteamLoginCode
        )
        null -> Unit
    }

    steamIdCompletionAccount?.let { account ->
        SteamLoginImportDialog(
            pendingChallenge = uiState.pendingLoginChallenge,
            availableCodeAccounts = uiState.accounts,
            onDismissRequest = {
                viewModel.cancelSteamLoginChallenge()
                steamIdCompletionAccountId = null
            },
            onBeginLogin = { userName, password, _, credentialEntryId ->
                viewModel.beginSteamIdCompletionLogin(account.id, userName, password, credentialEntryId)
            },
            onSubmitLoginCode = viewModel::submitSteamLoginCode,
            titleRes = R.string.steam_steamid_completion_login_title,
            descriptionRes = R.string.steam_steamid_completion_login_message,
            showRemarkField = false
        )
    }

    steamAccountRebindAccount?.let { account ->
        SteamLoginImportDialog(
            pendingChallenge = uiState.pendingLoginChallenge,
            availableCodeAccounts = uiState.accounts,
            onDismissRequest = {
                viewModel.cancelSteamLoginChallenge()
                steamAccountRebindAccountId = null
            },
            onBeginLogin = { userName, password, _, credentialEntryId ->
                viewModel.beginSteamAccountRebindLogin(account.id, userName, password, credentialEntryId)
            },
            onSubmitLoginCode = viewModel::submitSteamLoginCode,
            titleRes = R.string.steam_account_rebind_login_title,
            descriptionRes = R.string.steam_account_rebind_login_message,
            showRemarkField = false
        )
    }

    uiState.pendingMaFileSteamIdRequest?.let { request ->
        SteamMaFileSteamIdCompletionDialog(
            request = request,
            onDismissRequest = {
                viewModel.clearPendingMaFileSteamIdRequest()
                addAccountMethod = null
            },
            onUseSteamLogin = {
                viewModel.clearPendingMaFileSteamIdRequest()
                addAccountMethod = SteamAddAccountMethod.LOGIN
            },
            onImportCodeOnly = {
                viewModel.importMaFile(
                    request.maFileUri,
                    request.manifestUri,
                    request.password,
                    request.displayName,
                    "",
                    true
                )
                addAccountMethod = null
            },
            onImportMaFile = viewModel::importMaFile
        )
    }

    deleteRequest?.let { request ->
        val accountsToDelete = request.accounts
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = {
                Text(
                    stringResource(
                        if (accountsToDelete.size == 1) {
                            R.string.steam_delete_account_title
                        } else {
                            R.string.steam_delete_accounts_title
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (accountsToDelete.size == 1) {
                                R.string.steam_delete_account_message
                            } else {
                                R.string.steam_delete_accounts_message
                            },
                            if (accountsToDelete.size == 1) {
                                accountsToDelete.first().displayName
                            } else {
                                accountsToDelete.size
                            }
                        )
                    )
                    accountsToDelete.take(8).forEach { account ->
                        Text(
                            text = account.displayName.ifBlank {
                                account.accountName.ifBlank { account.visibleSteamId }
                            }.ifBlank { "Steam" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountsToDelete.forEach { account ->
                            viewModel.deleteAccount(account.id)
                        }
                        selectedTokenAccountIds = emptyList()
                        if (accountsToDelete.any { it.id == detailAccountId }) {
                            detailAccountId = null
                        }
                        deleteRequest = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequest = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    transferRequest?.let { request ->
        SteamMaFileTransferSheet(
            selectedCount = request.accounts.size,
            currentSource = uiState.storageSource,
            mdbxDatabases = mdbxDatabases,
            onDismissRequest = { transferRequest = null },
            onTransfer = { targetSource, action ->
                viewModel.transferAccounts(
                    accountIds = request.accounts.map { it.id },
                    targetSource = targetSource,
                    action = action
                )
                selectedTokenAccountIds = emptyList()
                transferRequest = null
            }
        )
    }

    editRemarkAccount?.let { account ->
        SteamRemarkEditDialog(
            account = uiState.accounts.firstOrNull { it.id == account.id } ?: account,
            onDismissRequest = { editRemarkAccount = null },
            onSave = { remark ->
                viewModel.updateDisplayName(account.id, remark)
                editRemarkAccount = null
            }
        )
    }

    removeAuthenticatorRequest?.let { account ->
        AlertDialog(
            onDismissRequest = { removeAuthenticatorRequest = null },
            title = { Text(stringResource(R.string.steam_remove_authenticator_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.steam_remove_authenticator_message,
                            account.displayName
                                .ifBlank { account.accountName.ifBlank { account.visibleSteamId } }
                                .ifBlank { "Steam" }
                        )
                    )
                    Text(
                        text = stringResource(R.string.steam_remove_authenticator_local_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            requestRemoveAuthenticatorVerification(
                                account,
                                SteamAuthenticatorRemovalMode.LOCAL_ONLY
                            )
                        }
                    ) {
                        Text(stringResource(R.string.steam_remove_authenticator_local_action))
                    }
                    TextButton(
                        onClick = {
                            requestRemoveAuthenticatorVerification(
                                account,
                                SteamAuthenticatorRemovalMode.REMOTE
                            )
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.steam_remove_authenticator_remote_action),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { removeAuthenticatorRequest = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    removeAuthenticatorVerifyAccount?.let { account ->
        val accountLabel = account.displayName
            .ifBlank { account.accountName.ifBlank { account.visibleSteamId } }
            .ifBlank { "Steam" }
        val removalMode = removeAuthenticatorVerifyMode
        val biometricAction = if (
            activity != null &&
            appSettings.biometricEnabled &&
            biometricHelper.isBiometricAvailable()
        ) {
            {
                biometricHelper.authenticate(
                    activity = activity,
                    title = context.getString(R.string.verify_identity),
                    subtitle = context.getString(
                        when (removalMode) {
                            SteamAuthenticatorRemovalMode.REMOTE -> R.string.steam_remove_authenticator_remote_action
                            SteamAuthenticatorRemovalMode.LOCAL_ONLY -> R.string.steam_remove_authenticator_local_action
                        }
                    ),
                    description = context.getString(R.string.biometric_login_description),
                    onSuccess = {
                        confirmRemoveAuthenticator(account, removalMode)
                    },
                    onError = { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    },
                    onFailed = {}
                )
            }
        } else {
            null
        }

        M3IdentityVerifyDialog(
            title = stringResource(R.string.verify_identity),
            message = stringResource(
                when (removalMode) {
                    SteamAuthenticatorRemovalMode.REMOTE -> R.string.steam_remove_authenticator_verify_message
                    SteamAuthenticatorRemovalMode.LOCAL_ONLY -> R.string.steam_remove_authenticator_local_verify_message
                },
                accountLabel
            ),
            passwordValue = removeAuthenticatorPasswordInput,
            onPasswordChange = {
                removeAuthenticatorPasswordInput = it
                removeAuthenticatorPasswordError = false
            },
            onDismiss = { dismissRemoveAuthenticatorVerify() },
            onConfirm = {
                if (securityManager.verifyMasterPassword(removeAuthenticatorPasswordInput)) {
                    confirmRemoveAuthenticator(account, removalMode)
                } else {
                    removeAuthenticatorPasswordError = true
                }
            },
            confirmText = stringResource(
                when (removalMode) {
                    SteamAuthenticatorRemovalMode.REMOTE -> R.string.steam_remove_authenticator_remote_action
                    SteamAuthenticatorRemovalMode.LOCAL_ONLY -> R.string.steam_remove_authenticator_local_action
                }
            ),
            destructiveConfirm = true,
            isPasswordError = removeAuthenticatorPasswordError,
            passwordErrorText = stringResource(R.string.current_password_incorrect),
            onBiometricClick = biometricAction,
            biometricHintText = if (biometricAction == null) {
                stringResource(R.string.biometric_not_available)
            } else {
                null
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AnimatedContent(
                targetState = detailAccount?.id,
                transitionSpec = {
                    easyNotesScreenEnter().togetherWith(easyNotesScreenExit())
                },
                label = "SteamTopBarNavigation"
            ) { animatedDetailAccountId ->
                if (animatedDetailAccountId != null) {
                    val animatedDetailAccount = uiState.accounts.firstOrNull { it.id == animatedDetailAccountId }
                    SteamDetailTopBar(
                        title = stringResource(R.string.nav_steam),
                        onNavigateBack = {
                            detailAccountId = null
                            scannedQrPayload = null
                        },
                        onRemoveAuthenticator = animatedDetailAccount?.let { account ->
                            { removeAuthenticatorRequest = account }
                        },
                        onRebindAccount = animatedDetailAccount?.let { account ->
                            { steamAccountRebindAccountId = account.id }
                        }
                    )
                } else {
                    ExpressiveTopBar(
                        title = stringResource(R.string.nav_steam),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        isSearchExpanded = false,
                        onSearchExpandedChange = {},
                        searchHint = stringResource(R.string.nav_steam),
                        actions = {
                            if (selectedAccount != null && selectedSection == SteamSection.CONFIRMATIONS) {
                                IconButton(onClick = { viewModel.refreshConfirmations() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.refresh),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { showStorageSourceMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = stringResource(R.string.database_source_label),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SteamStorageSourceMenu(
                                    expanded = showStorageSourceMenu,
                                    onDismissRequest = { showStorageSourceMenu = false },
                                    selectedSource = uiState.storageSource,
                                    mdbxDatabases = mdbxDatabases,
                                    onSelectSource = { source ->
                                        showStorageSourceMenu = false
                                        selectedTokenAccountIds = emptyList()
                                        detailAccountId = null
                                        scannedQrPayload = null
                                        viewModel.selectStorageSource(source)
                                    }
                                )
                            }
                            IconButton(
                                onClick = {
                                    showTopActionsMenu = false
                                    showAddAccountDialog = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.steam_add_account_button),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selectedAccount != null) {
                                val targetSection = when (selectedSection) {
                                    SteamSection.CODE -> SteamSection.CONFIRMATIONS
                                    SteamSection.CONFIRMATIONS -> SteamSection.CODE
                                }
                                IconButton(
                                    onClick = {
                                        selectedSection = targetSection
                                        if (targetSection == SteamSection.CONFIRMATIONS) {
                                            viewModel.refreshConfirmations()
                                        }
                                    }
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (targetSection == SteamSection.CONFIRMATIONS && pendingConfirmationCount > 0) {
                                                Badge {
                                                    Text(badgeCountText(pendingConfirmationCount))
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = targetSection.icon,
                                            contentDescription = stringResource(targetSection.labelRes),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (showStandaloneSettingsEntry) {
                                Box {
                                    IconButton(
                                        onClick = {
                                            showTopActionsMenu = true
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.more_options),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    SteamTopActionsMenu(
                                        expanded = showTopActionsMenu,
                                        onDismissRequest = { showTopActionsMenu = false },
                                        showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                                        onOpenStandaloneSettings = {
                                            showTopActionsMenu = false
                                            onOpenStandaloneSettings()
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            val scanQr = onScanSteamQrCode
            val detailQrAccount = detailAccount?.takeIf { it.canApproveLogins }
            val account = detailQrAccount ?: tokenQrAccount
            AnimatedVisibility(
                visible = scanQr != null && account != null,
                enter = fadeIn(animationSpec = tween(160)) +
                    scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120)) +
                    scaleOut(targetScale = 0.9f, animationSpec = tween(140))
            ) {
                if (scanQr != null && account != null && account.hasRealSteamId) {
                    FloatingActionButton(
                        onClick = {
                            val freshAccount = detailQrAccount
                                ?: uiState.accounts.firstOrNull {
                                    it.canApproveLogins && it.id == readLastSteamQrAccountId(context)
                                }
                                ?: account
                            rememberLastSteamQrAccount(freshAccount.id)
                            scanQr(freshAccount.id)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.scan_qr_code)
                        )
                    }
                }
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = detailAccount?.id,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                transitionSpec = {
                    easyNotesScreenEnter().togetherWith(easyNotesScreenExit())
                },
                label = "SteamDetailNavigation"
            ) { animatedDetailAccountId ->
                val animatedDetailAccount = uiState.accounts.firstOrNull { it.id == animatedDetailAccountId }
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (animatedDetailAccount != null) {
                        SteamAccountDetailContent(
                            account = animatedDetailAccount,
                            pendingLogins = uiState.pendingLogins,
                            authorizedDevices = uiState.authorizedDevices,
                            pendingScannedQr = scannedQrPayload,
                            onScannedQrHandled = { scannedQrPayload = null },
                            onEditRemark = { editRemarkAccount = animatedDetailAccount },
                            onCompleteSteamIdLogin = { steamIdCompletionAccountId = animatedDetailAccount.id },
                            onRefreshLogins = { viewModel.refreshPendingLogins() },
                            onRefreshAuthorizedDevices = { viewModel.refreshAuthorizedDevices(animatedDetailAccount.id) },
                            onRevokeAuthorizedDevice = { device, userName, password ->
                                viewModel.revokeAuthorizedDevice(
                                    animatedDetailAccount.id,
                                    device,
                                    userName,
                                    password
                                )
                            },
                            onRespondPending = viewModel::respondPendingLogin,
                            onRespondQr = viewModel::respondQr
                        )
                    } else if (selectedAccount == null) {
                        SteamEmptyAccountContent(
                            onAddAccount = { showAddAccountDialog = true }
                        )
                    } else {
                        when (selectedSection) {
                            SteamSection.CODE -> SteamCodeContent(
                                accounts = uiState.accounts,
                                selectedAccountIds = selectedTokenAccountIds,
                                appSettings = appSettings,
                                onToggleSelection = { account ->
                                    selectedTokenAccountIds = if (account.id in selectedTokenAccountIds) {
                                        selectedTokenAccountIds - account.id
                                    } else {
                                        selectedTokenAccountIds + account.id
                                    }
                                },
                                onClearSelection = {
                                    selectedTokenAccountIds = emptyList()
                                },
                                onSelectAll = {
                                    selectedTokenAccountIds = if (selectedTokenAccountIds.size == uiState.accounts.size) {
                                        emptyList()
                                    } else {
                                        uiState.accounts.map { it.id }
                                    }
                                },
                                onDeleteSelected = {
                                    val targets = uiState.accounts.filter { it.id in selectedTokenAccountIds }
                                    if (targets.isNotEmpty()) {
                                        deleteRequest = SteamDeleteAccountsRequest(targets)
                                    }
                                },
                                onTransferSelected = {
                                    val targets = uiState.accounts.filter { it.id in selectedTokenAccountIds }
                                    if (targets.isNotEmpty()) {
                                        transferRequest = SteamTransferAccountsRequest(targets)
                                    }
                                },
                                onUpdateSortOrders = viewModel::updateSortOrders,
                                onOpenDetail = { account ->
                                    selectedTokenAccountIds = emptyList()
                                    detailAccountId = account.id
                                }
                            )
                            SteamSection.CONFIRMATIONS -> SteamConfirmationsContent(
                                account = selectedAccount,
                                accounts = uiState.accounts,
                                confirmations = uiState.confirmations,
                                selectedIds = uiState.selectedConfirmationIds,
                                onSelectAccount = viewModel::selectAccount,
                                onToggle = viewModel::toggleConfirmation,
                                onSelectAll = viewModel::selectAllConfirmations,
                                onClearSelection = viewModel::clearSelectedConfirmations,
                                onRespond = viewModel::respondConfirmation,
                                onRespondSelected = viewModel::respondSelectedConfirmations
                            )
                        }
                    }
                }
            }

            if (uiState.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteamDetailTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    onRemoveAuthenticator: (() -> Unit)? = null,
    onRebindAccount: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            if (onRebindAccount != null) {
                IconButton(onClick = onRebindAccount) {
                    Icon(
                        imageVector = Icons.Default.Login,
                        contentDescription = stringResource(R.string.steam_account_rebind_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onRemoveAuthenticator != null) {
                IconButton(onClick = onRemoveAuthenticator) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.steam_remove_authenticator_action),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun SteamTopActionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit
) {
    PasswordTopActionsDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (showStandaloneSettingsEntry) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_settings)) },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = onOpenStandaloneSettings
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SteamStorageSourceMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedSource: SteamStorageSource,
    mdbxDatabases: List<LocalMdbxDatabase>,
    onSelectSource: (SteamStorageSource) -> Unit
) {
    UnifiedCategoryFilterChipMenuDropdown(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = UnifiedCategoryFilterChipMenuOffset
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.category_selection_menu_databases),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonicaExpressiveFilterChip(
                    selected = selectedSource is SteamStorageSource.Local,
                    onClick = { onSelectSource(SteamStorageSource.Local) },
                    label = stringResource(R.string.category_selection_menu_local_database),
                    leadingIcon = Icons.Default.Smartphone
                )
                mdbxDatabases.forEach { database ->
                    MonicaExpressiveFilterChip(
                        selected = selectedSource is SteamStorageSource.Mdbx &&
                            selectedSource.databaseId == database.id,
                        onClick = { onSelectSource(SteamStorageSource.Mdbx(database.id)) },
                        label = database.name.ifBlank { "MDBX" },
                        leadingIcon = Icons.Default.Storage,
                        statusDotColor = Color(0xFF22C55E)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SteamMaFileTransferSheet(
    selectedCount: Int,
    currentSource: SteamStorageSource,
    mdbxDatabases: List<LocalMdbxDatabase>,
    onDismissRequest: () -> Unit,
    onTransfer: (SteamStorageSource, SteamMaFileTransferAction) -> Unit
) {
    val localLabel = stringResource(R.string.category_selection_menu_local_database)
    val targets = remember(currentSource, mdbxDatabases, localLabel) {
        buildList {
            if (currentSource !is SteamStorageSource.Local) {
                add(
                    SteamTransferTarget(
                        source = SteamStorageSource.Local,
                        label = localLabel,
                        icon = Icons.Default.Smartphone
                    )
                )
            }
            mdbxDatabases
                .filterNot { database ->
                    currentSource is SteamStorageSource.Mdbx &&
                        currentSource.databaseId == database.id
                }
                .forEach { database ->
                    add(
                        SteamTransferTarget(
                            source = SteamStorageSource.Mdbx(database.id),
                            label = database.name.ifBlank { "MDBX" },
                            icon = Icons.Default.Storage
                        )
                    )
                }
        }
    }
    var selectedAction by remember { mutableStateOf(SteamMaFileTransferAction.MOVE) }
    var selectedTarget by remember(
        currentSource,
        mdbxDatabases.map { it.id }
    ) { mutableStateOf<SteamStorageSource?>(targets.firstOrNull()?.source) }

    LaunchedEffect(targets) {
        if (selectedTarget == null || targets.none { it.source == selectedTarget }) {
            selectedTarget = targets.firstOrNull()?.source
        }
    }

    MonicaModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.steam_transfer_mafile_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.steam_transfer_mafile_count, selectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = stringResource(R.string.steam_transfer_mafile_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonicaExpressiveFilterChip(
                    selected = selectedAction == SteamMaFileTransferAction.MOVE,
                    onClick = { selectedAction = SteamMaFileTransferAction.MOVE },
                    label = stringResource(R.string.move),
                    leadingIcon = Icons.Default.Folder
                )
                MonicaExpressiveFilterChip(
                    selected = selectedAction == SteamMaFileTransferAction.COPY,
                    onClick = { selectedAction = SteamMaFileTransferAction.COPY },
                    label = stringResource(R.string.copy),
                    leadingIcon = Icons.Default.ContentCopy
                )
            }

            Text(
                text = stringResource(R.string.category_selection_menu_databases),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            if (targets.isEmpty()) {
                Text(
                    text = stringResource(R.string.steam_transfer_mafile_no_target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    targets.forEach { target ->
                        SteamTransferTargetRow(
                            target = target,
                            selected = target.source == selectedTarget,
                            onClick = { selectedTarget = target.source }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = selectedTarget != null,
                    onClick = {
                        selectedTarget?.let { target ->
                            onTransfer(target, selectedAction)
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (selectedAction == SteamMaFileTransferAction.COPY) {
                                R.string.copy
                            } else {
                                R.string.move
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamTransferTargetRow(
    target: SteamTransferTarget,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = target.icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = target.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun SteamCodeContent(
    accounts: List<SteamAccount>,
    selectedAccountIds: List<Long>,
    appSettings: AppSettings,
    onToggleSelection: (SteamAccount) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onTransferSelected: () -> Unit,
    onUpdateSortOrders: (List<Pair<Long, Int>>) -> Unit,
    onOpenDetail: (SteamAccount) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHapticFeedback()
    val selectedIds = selectedAccountIds.toSet()
    val selectionMode = selectedIds.isNotEmpty()
    val sharedProgressTimeMillis = rememberTotpTickerMillis(appSettings.validatorSmoothProgress)
    val sharedTickSeconds = sharedProgressTimeMillis / 1000L
    val lazyListState = rememberSaveableLazyListState()
    var localAccounts by remember(accounts) { mutableStateOf(accounts) }

    LaunchedEffect(accounts) {
        localAccounts = accounts
    }

    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (selectionMode) {
            localAccounts = localAccounts.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }

    LaunchedEffect(reorderableLazyListState.isAnyItemDragging) {
        if (!reorderableLazyListState.isAnyItemDragging && selectionMode) {
            val newOrders = localAccounts.mapIndexed { index, account -> account.id to index }
            if (newOrders.isNotEmpty()) {
                onUpdateSortOrders(newOrders)
            }
        }
    }

    fun copyCode(code: String) {
        if (code.isNotBlank()) {
            clipboard.setText(AnnotatedString(code))
            Toast.makeText(
                context,
                context.getString(R.string.verification_code_copied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (appSettings.validatorUnifiedProgressBar == UnifiedProgressBarMode.ENABLED &&
                accounts.isNotEmpty()
            ) {
                UnifiedProgressBar(
                    style = appSettings.validatorProgressBarStyle,
                    currentSeconds = sharedTickSeconds,
                    period = 30,
                    smoothProgress = appSettings.validatorSmoothProgress,
                    timeOffset = (appSettings.totpTimeOffset * 1000).toLong()
                )
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = if (selectionMode) 112.dp else 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(localAccounts, key = { it.id }) { account ->
                    ReorderableItem(
                        reorderableLazyListState,
                        key = account.id,
                        enabled = selectionMode
                    ) { isDragging ->
                        val elevation by animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "steam_token_drag_elevation"
                        )
                        val dragModifier = if (selectionMode) {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = { haptic.performLongPress() },
                                onDragStopped = { haptic.performSuccess() }
                            )
                        } else {
                            Modifier
                        }
                        val totpItem = remember(account) { account.toSteamTotpUiItem() }
                        val totpData = remember(account) { account.toSteamTotpUiData() }
                        SwipeActions(
                            onSwipeLeft = {
                                if (account.id !in selectedIds) {
                                    onToggleSelection(account)
                                }
                            },
                            onSwipeRight = { onToggleSelection(account) },
                            isSwiped = account.id in selectedIds,
                            enabled = !isDragging,
                            allowSwipeLeft = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        shadowElevation = elevation.toPx()
                                    }
                                    .then(dragModifier)
                            ) {
                                TotpCodeCard(
                                    item = totpItem,
                                    parsedTotpData = totpData,
                                    onCardClick = {
                                        if (selectionMode) {
                                            onToggleSelection(account)
                                        } else {
                                            onOpenDetail(account)
                                        }
                                    },
                                    onToggleSelect = { onToggleSelection(account) },
                                    onLongClick = {
                                        copyCode(SteamTotp.generateAuthCode(account.sharedSecret, System.currentTimeMillis() / 1000L))
                                    },
                                    isSelectionMode = selectionMode,
                                    isSelected = account.id in selectedIds,
                                    leadingContent = {
                                        SteamAvatarImage(
                                            account = account,
                                            size = 40.dp
                                        )
                                    },
                                    onCopyCode = ::copyCode,
                                    sharedTickSeconds = sharedTickSeconds,
                                    sharedProgressTimeMillis = sharedProgressTimeMillis,
                                    appSettings = appSettings
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            SelectionActionBar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                selectedCount = selectedIds.size,
                onExit = onClearSelection,
                onSelectAll = onSelectAll,
                onMoveToCategory = onTransferSelected,
                onDelete = onDeleteSelected
            )
        }
    }
}

@Composable
private fun SteamAccountDetailContent(
    account: SteamAccount,
    pendingLogins: List<SteamPendingLogin>,
    authorizedDevices: List<SteamAuthorizedDevice>,
    pendingScannedQr: String?,
    onScannedQrHandled: () -> Unit,
    onEditRemark: () -> Unit,
    onCompleteSteamIdLogin: () -> Unit,
    onRefreshLogins: () -> Unit,
    onRefreshAuthorizedDevices: () -> Unit,
    onRevokeAuthorizedDevice: (SteamAuthorizedDevice, String, String) -> Unit,
    onRespondPending: (SteamPendingLogin, Boolean) -> Unit,
    onRespondQr: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val totpItem = remember(account) { account.toSteamTotpUiItem() }
    val totpData = remember(account) { account.toSteamTotpUiData() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TotpCodeCard(
                item = totpItem,
                parsedTotpData = totpData,
                leadingContent = {
                    SteamAvatarImage(
                        account = account,
                        size = 40.dp
                    )
                },
                onCopyCode = { code ->
                    copySteamText(
                        context = context,
                        clipboard = clipboard,
                        label = context.getString(R.string.steam_code_label),
                        value = code
                    )
                }
            )
        }
        if (!account.hasRealSteamId) {
            item {
                SteamMissingSteamIdPromptCard(
                    hasIdentitySecret = !account.identitySecret.isNullOrBlank(),
                    onLogin = onCompleteSteamIdLogin
                )
            }
        } else {
            item {
                SteamAccountCredentialCard(
                    account = account,
                    context = context,
                    clipboard = clipboard,
                    onEditRemark = onEditRemark
                )
            }
            item {
                SteamLoginApprovalSection(
                    account = account,
                    pendingLogins = pendingLogins,
                    pendingScannedQr = pendingScannedQr,
                    onScannedQrHandled = onScannedQrHandled,
                    onRefresh = onRefreshLogins,
                    onRespondPending = onRespondPending,
                    onRespondQr = onRespondQr
                )
            }
            item {
                SteamAuthorizedDevicesSection(
                    account = account,
                    devices = authorizedDevices,
                    onRefresh = onRefreshAuthorizedDevices,
                    onRevokeDevice = onRevokeAuthorizedDevice
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SteamMissingSteamIdPromptCard(
    hasIdentitySecret: Boolean,
    onLogin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.steam_steamid_completion_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    if (hasIdentitySecret) {
                        R.string.steam_steamid_completion_desc
                    } else {
                        R.string.steam_steamid_completion_code_only_desc
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Login, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.steam_steamid_completion_login_button))
            }
        }
    }
}

@Composable
private fun SteamAccountCredentialCard(
    account: SteamAccount,
    context: Context,
    clipboard: ClipboardManager,
    onEditRemark: () -> Unit
) {
    var revocationCodeVisible by rememberSaveable(account.id) { mutableStateOf(false) }
    val pickerSecurityManager = remember(context) { SecurityManager(context) }
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val passwordEntries by passwordDatabase.passwordEntryDao()
        .getAllPasswordEntries()
        .collectAsState(initial = emptyList())
    val credentialPreferences = remember(context) {
        context.getSharedPreferences("steam_credential_bindings", Context.MODE_PRIVATE)
    }
    val credentialPreferenceKey = remember(account.steamId) {
        "steam_${account.steamId}_password_entry_id"
    }
    var credentialEntryId by rememberSaveable(account.steamId) {
        mutableStateOf(
            credentialPreferences.getLong(credentialPreferenceKey, -1L)
                .takeIf { it > 0L }
        )
    }
    val boundCredentialEntry = passwordEntries.firstOrNull {
        it.id == credentialEntryId && !it.isDeleted && !it.isArchived
    }
    val boundCredentialUserName = boundCredentialEntry?.let { entry ->
        runCatching { pickerSecurityManager.decryptData(entry.username) }
            .getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
            ?: entry.username.trim()
    }.orEmpty()
    val boundCredentialPassword = boundCredentialEntry?.let { entry ->
        runCatching { pickerSecurityManager.decryptData(entry.password) }
            .getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
            ?: entry.password.trim()
    }.orEmpty()
    var boundPasswordVisible by rememberSaveable(account.id) { mutableStateOf(false) }
    var showCredentialPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.steam_credentials_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            SteamRemarkInfoRow(
                account = account,
                onEditRemark = onEditRemark
            )
            SteamDetailInfoRow(
                label = stringResource(R.string.steam_account_label),
                value = account.accountName.ifBlank { account.visibleSteamId }.ifBlank { "Steam" },
                context = context,
                clipboard = clipboard
            )
            SteamDetailInfoRow(
                label = stringResource(R.string.steam_id_label),
                value = account.visibleSteamId.ifBlank { stringResource(R.string.steam_status_code_only) },
                context = context,
                clipboard = clipboard
            )
            SteamDetailInfoRow(
                label = stringResource(R.string.steam_device_label),
                value = account.deviceId,
                context = context,
                clipboard = clipboard
            )
            Text(
                text = stringResource(R.string.steam_revoke_credential_label),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = boundCredentialEntry?.title
                    ?: stringResource(R.string.steam_revoke_credential_not_set),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (boundCredentialEntry != null) {
                SteamDetailInfoRow(
                    label = stringResource(R.string.steam_login_account_label),
                    value = boundCredentialUserName,
                    context = context,
                    clipboard = clipboard
                )
                SteamSensitiveInfoRow(
                    label = stringResource(R.string.steam_login_password_label),
                    value = boundCredentialPassword,
                    visible = boundPasswordVisible,
                    onToggleVisibility = { boundPasswordVisible = !boundPasswordVisible },
                    context = context,
                    copiedMessageRes = R.string.steam_login_password_copied
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showCredentialPicker = true }) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (boundCredentialEntry == null) R.string.steam_revoke_credential_set else R.string.steam_revoke_credential_change))
                }
                if (boundCredentialEntry != null) {
                    TextButton(onClick = {
                        credentialPreferences.edit().remove(credentialPreferenceKey).apply()
                        credentialEntryId = null
                    }) {
                        Text(stringResource(R.string.steam_revoke_credential_clear))
                    }
                }
            }
            SteamSensitiveInfoRow(
                label = stringResource(R.string.steam_revocation_code_label),
                value = account.revocationCode.orEmpty(),
                visible = revocationCodeVisible,
                onToggleVisibility = { revocationCodeVisible = !revocationCodeVisible },
                context = context
            )
        }
    }

    if (showCredentialPicker) {
        PasswordEntryPickerBottomSheet(
            visible = true,
            title = stringResource(R.string.select_password_to_bind),
            passwords = passwordEntries.filter { !it.isDeleted && !it.isArchived },
            onDismiss = { showCredentialPicker = false },
            onSelect = { entry ->
                credentialPreferences.edit().putLong(credentialPreferenceKey, entry.id).apply()
                credentialEntryId = entry.id
                showCredentialPicker = false
                Toast.makeText(
                    context,
                    context.getString(R.string.steam_revoke_credential_bound),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun SteamRemarkInfoRow(
    account: SteamAccount,
    onEditRemark: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.steam_remark_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = account.remarkNameOrEmpty().ifBlank { stringResource(R.string.steam_empty_field) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEditRemark) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.steam_edit_remark_title)
            )
        }
    }
}

@Composable
private fun SteamSensitiveInfoRow(
    label: String,
    value: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    context: Context,
    @StringRes copiedMessageRes: Int = R.string.steam_revocation_code_copied
) {
    val hasValue = value.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    !hasValue -> stringResource(R.string.steam_empty_field)
                    visible -> value
                    else -> "•".repeat(8)
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (hasValue) {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) stringResource(R.string.hide) else stringResource(R.string.show)
                )
            }
            IconButton(
                onClick = {
                    ClipboardUtils.copyToClipboard(
                        context = context,
                        text = value,
                        label = label,
                        sensitive = true
                    )
                    Toast.makeText(
                        context,
                        context.getString(copiedMessageRes),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy)
                )
            }
        }
    }
}

@Composable
private fun SteamDetailInfoRow(
    label: String,
    value: String,
    context: Context,
    clipboard: ClipboardManager,
    copyable: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value.ifBlank { stringResource(R.string.steam_empty_field) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (copyable && value.isNotBlank()) {
            IconButton(
                onClick = {
                    copySteamText(
                        context = context,
                        clipboard = clipboard,
                        label = label,
                        value = value
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy)
                )
            }
        }
    }
}

@Composable
private fun SteamRemarkEditDialog(
    account: SteamAccount,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit
) {
    var remark by remember(account.id, account.displayName) {
        mutableStateOf(account.remarkNameOrEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.steam_edit_remark_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text(stringResource(R.string.steam_remark_optional_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = account.accountName.ifBlank { account.visibleSteamId }.ifBlank { "Steam" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(remark) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun SteamAvatarImage(
    account: SteamAccount,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var avatar by remember(account.steamId) { mutableStateOf<ImageBitmap?>(null) }
    val fallbackText = remember(account.displayName, account.accountName, account.steamId) {
        account.displayName
            .ifBlank { account.accountName }
            .ifBlank { account.visibleSteamId }
            .ifBlank { "S" }
            .take(1)
            .uppercase()
    }

    LaunchedEffect(account.steamId) {
        avatar = if (account.hasRealSteamId) {
            loadSteamAvatar(context, account.steamId)
        } else {
            null
        }
    }

    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        tonalElevation = 2.dp
    ) {
        val snapshot = avatar
        if (snapshot != null) {
            Image(
                bitmap = snapshot,
                contentDescription = stringResource(R.string.steam_account_avatar_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fallbackText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

private fun SteamAccount.toSteamTotpUiItem(): SecureItem {
    return SecureItem(
        id = id,
        itemType = ItemType.TOTP,
        title = displayName.ifBlank { accountName.ifBlank { visibleSteamId } }.ifBlank { "Steam" },
        itemData = "steam://${sharedSecret}"
    )
}

private fun SteamAccount.toSteamTotpUiData(): TotpData {
    return TotpData(
        secret = "steam://${sharedSecret}",
        issuer = "Steam",
        accountName = accountName.ifBlank { visibleSteamId },
        period = 30,
        digits = 5,
        otpType = OtpType.STEAM,
        link = "https://store.steampowered.com",
        associatedApp = "com.valvesoftware.android.steam.community",
        steamDeviceId = deviceId,
        steamSharedSecretBase64 = sharedSecret,
        steamRevocationCode = revocationCode.orEmpty(),
        steamIdentitySecret = identitySecret.orEmpty(),
        steamTokenGid = tokenGid.orEmpty(),
        steamRawJson = rawSteamGuardJson
    )
}

private fun SteamAccount.remarkNameOrEmpty(): String {
    val remark = displayName.trim()
    val account = accountName.ifBlank { visibleSteamId }.trim()
    return remark.takeIf {
        it.isNotBlank() &&
            it != account &&
            it != steamId
    }.orEmpty()
}

private fun copySteamText(
    context: Context,
    clipboard: ClipboardManager,
    label: String,
    value: String
) {
    if (value.isBlank()) return
    clipboard.setText(AnnotatedString(value))
    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
}

private suspend fun loadSteamAvatar(context: Context, steamId: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val cacheFile = steamAvatarCacheFile(context, steamId)
    val cachedAvatar = readSteamAvatarCache(cacheFile)
    if (cachedAvatar != null && !isSteamAvatarCacheExpired(cacheFile)) {
        return@withContext cachedAvatar
    }

    val freshAvatar = runCatching {
        val avatarUrl = fetchSteamAvatarUrl(steamId) ?: return@runCatching null
        downloadSteamAvatarBytes(avatarUrl)?.also { bytes ->
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(bytes)
        }?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()

    freshAvatar ?: cachedAvatar
}

private fun fetchSteamAvatarUrl(steamId: String): String? {
    val normalizedSteamId = steamId.trim()
    if (normalizedSteamId.isBlank() || normalizedSteamId.any { !it.isDigit() }) {
        return null
    }

    val connection = (URL("https://steamcommunity.com/profiles/$normalizedSteamId/?xml=1")
        .openConnection() as HttpURLConnection).apply {
        connectTimeout = STEAM_AVATAR_TIMEOUT_MS
        readTimeout = STEAM_AVATAR_TIMEOUT_MS
        requestMethod = "GET"
    }
    return try {
        connection.inputStream.use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            }
            val document = factory.newDocumentBuilder().parse(stream)
            document.documentElement.normalize()
            listOf("avatarFull", "avatarMedium", "avatarIcon").firstNotNullOfOrNull { tag ->
                document.getElementsByTagName(tag)
                    ?.item(0)
                    ?.textContent
                    ?.trim()
                    ?.takeIf { it.startsWith("https://") }
            }
        }
    } finally {
        connection.disconnect()
    }
}

private fun downloadSteamAvatarBytes(avatarUrl: String): ByteArray? {
    val connection = (URL(avatarUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = STEAM_AVATAR_TIMEOUT_MS
        readTimeout = STEAM_AVATAR_TIMEOUT_MS
        requestMethod = "GET"
    }
    return try {
        connection.inputStream.use { stream ->
            stream.readBytes()
        }
    } finally {
        connection.disconnect()
    }
}

private fun steamAvatarCacheFile(context: Context, steamId: String): File {
    val safeSteamId = steamId.filter { it.isLetterOrDigit() }.ifBlank { "unknown" }
    return File(File(context.cacheDir, "steam_avatars"), "$safeSteamId.png")
}

private fun readSteamAvatarCache(cacheFile: File): ImageBitmap? {
    if (!cacheFile.isFile) return null
    return runCatching {
        BitmapFactory.decodeFile(cacheFile.absolutePath)?.asImageBitmap()
    }.getOrNull()
}

private fun isSteamAvatarCacheExpired(cacheFile: File): Boolean {
    if (!cacheFile.isFile) return true
    val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
    return ageMs > STEAM_AVATAR_CACHE_TTL_MS
}

private suspend fun loadSteamConfirmationImage(context: Context, imageUrl: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeSteamImageUrl(imageUrl)
        if (!normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("http://")) {
            return@withContext null
        }

        val cacheFile = steamConfirmationImageCacheFile(context, normalizedUrl)
        val cachedImage = readSteamAvatarCache(cacheFile)
        if (cachedImage != null && !isSteamConfirmationImageCacheExpired(cacheFile)) {
            return@withContext cachedImage
        }

        val freshImage = runCatching {
            downloadSteamConfirmationImageBytes(normalizedUrl)?.also { bytes ->
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeBytes(bytes)
            }?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()

        freshImage ?: cachedImage
    }

private fun normalizeSteamImageUrl(imageUrl: String): String {
    val trimmed = imageUrl.trim()
    return when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/") -> "https://steamcommunity.com$trimmed"
        else -> trimmed
    }
}

private fun downloadSteamConfirmationImageBytes(imageUrl: String): ByteArray? {
    val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = STEAM_CONFIRMATION_IMAGE_TIMEOUT_MS
        readTimeout = STEAM_CONFIRMATION_IMAGE_TIMEOUT_MS
        requestMethod = "GET"
    }
    return try {
        connection.inputStream.use { stream ->
            stream.readBytes()
        }
    } finally {
        connection.disconnect()
    }
}

private fun steamConfirmationImageCacheFile(context: Context, imageUrl: String): File {
    val safeName = imageUrl
        .hashCode()
        .toUInt()
        .toString(16)
    return File(File(context.cacheDir, "steam_confirmation_images"), "$safeName.png")
}

private fun isSteamConfirmationImageCacheExpired(cacheFile: File): Boolean {
    if (!cacheFile.isFile) return true
    val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
    return ageMs > STEAM_CONFIRMATION_IMAGE_CACHE_TTL_MS
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.width(120.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SteamConfirmationsContent(
    account: SteamAccount?,
    accounts: List<SteamAccount>,
    confirmations: List<SteamConfirmation>,
    selectedIds: Set<String>,
    onSelectAccount: (Long) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRespond: (SteamConfirmation, Boolean) -> Unit,
    onRespondSelected: (Boolean) -> Unit
) {
    var pendingAction by remember { mutableStateOf<ConfirmationActionRequest?>(null) }
    var showBulkActionDialog by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var pendingAccountSwitchId by remember { mutableStateOf<Long?>(null) }
    val selectedConfirmations = confirmations.filter { it.id in selectedIds }
    val selectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(showAccountPicker, pendingAccountSwitchId) {
        val accountId = pendingAccountSwitchId
        if (!showAccountPicker && accountId != null) {
            pendingAccountSwitchId = null
            onSelectAccount(accountId)
        }
    }

    if (showAccountPicker) {
        SteamConfirmationAccountPickerSheet(
            accounts = accounts,
            selectedAccountId = account?.id,
            onSelectAccount = { selected ->
                pendingAccountSwitchId = selected.id
                showAccountPicker = false
            },
            onDismissRequest = { showAccountPicker = false }
        )
    }

    pendingAction?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(
                    stringResource(
                        if (request.accept) {
                            R.string.steam_approve_confirmation_title
                        } else {
                            R.string.steam_reject_confirmation_title
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.steam_confirmation_count, request.confirmations.size))
                    request.confirmations.take(8).forEach { confirmation ->
                        Text(
                            text = confirmation.headline.ifBlank {
                                confirmation.summary.ifBlank { confirmation.id }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (request.confirmations.size > 8) {
                        Text(
                            text = stringResource(
                                R.string.steam_more_items,
                                request.confirmations.size - 8
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (request.confirmations.size == 1) {
                            onRespond(request.confirmations.first(), request.accept)
                        } else {
                            onRespondSelected(request.accept)
                        }
                        pendingAction = null
                    }
                ) {
                    Text(
                        stringResource(
                            if (request.accept) R.string.steam_approve else R.string.steam_reject
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showBulkActionDialog) {
        AlertDialog(
            onDismissRequest = { showBulkActionDialog = false },
            title = { Text(stringResource(R.string.steam_confirmation_action_title)) },
            text = {
                Text(stringResource(R.string.steam_confirmation_action_message, selectedConfirmations.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = ConfirmationActionRequest(
                            confirmations = selectedConfirmations,
                            accept = true
                        )
                        showBulkActionDialog = false
                    }
                ) {
                    Text(stringResource(R.string.steam_approve))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showBulkActionDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            pendingAction = ConfirmationActionRequest(
                                confirmations = selectedConfirmations,
                                accept = false
                            )
                            showBulkActionDialog = false
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.steam_reject),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SteamConfirmationAccountCard(
                account = account,
                onClick = { showAccountPicker = true },
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 10.dp,
                    end = 16.dp,
                    bottom = if (selectionMode) 144.dp else 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (account == null || !account.canUseConfirmations) {
                    item {
                        EmptyState(
                            steamConfirmationUnavailableText(account)
                        )
                    }
                } else if (confirmations.isEmpty()) {
                    item { EmptyState(stringResource(R.string.steam_no_confirmations)) }
                } else {
                    items(confirmations, key = { it.id }) { confirmation ->
                        SwipeActions(
                            onSwipeLeft = {},
                            onSwipeRight = { onToggle(confirmation.id) },
                            isSwiped = confirmation.id in selectedIds,
                            allowSwipeLeft = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ConfirmationRow(
                                confirmation = confirmation,
                                selected = confirmation.id in selectedIds,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        onToggle(confirmation.id)
                                    } else {
                                        pendingAction = ConfirmationActionRequest(
                                            confirmations = listOf(confirmation),
                                            accept = true
                                        )
                                    }
                                },
                                onLongClick = { onToggle(confirmation.id) }
                            )
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 24.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionActionBar(
                    selectedCount = selectedConfirmations.size,
                    onExit = onClearSelection,
                    onSelectAll = onSelectAll,
                    onDelete = null
                )

                Spacer(modifier = Modifier.weight(1f))

                FloatingActionButton(
                    onClick = { showBulkActionDialog = true }
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.steam_confirmation_action_title)
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamConfirmationAccountCard(
    account: SteamAccount?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (account != null) {
                SteamAvatarImage(account = account, size = 36.dp)
            } else {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.steam_current_confirmation_account),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = account?.displayName
                        ?.ifBlank { account.accountName }
                        ?.ifBlank { account.visibleSteamId }
                        ?: stringResource(R.string.steam_empty_field),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(
                    if (account?.canUseConfirmations == true) {
                        R.string.steam_status_ready
                    } else if (account != null && !account.hasRealSteamId) {
                        R.string.steam_status_code_only
                    } else {
                        R.string.steam_status_missing_session
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (account?.canUseConfirmations == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun SteamAuthorizedDevicesSection(
    account: SteamAccount,
    devices: List<SteamAuthorizedDevice>,
    onRefresh: () -> Unit,
    onRevokeDevice: (SteamAuthorizedDevice, String, String) -> Unit
) {
    val context = LocalContext.current
    val pickerSecurityManager = remember(context) { SecurityManager(context) }
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val passwordEntriesForPicker by passwordDatabase.passwordEntryDao()
        .getAllPasswordEntries()
        .collectAsState(initial = emptyList())
    var pendingRevokeDevice by remember { mutableStateOf<SteamAuthorizedDevice?>(null) }
    var revokeUserName by remember(account.id, account.accountName) {
        mutableStateOf(account.accountName)
    }
    var revokePassword by remember { mutableStateOf("") }
    var showRevokePasswordPicker by remember { mutableStateOf(false) }
    var useBoundCredential by remember { mutableStateOf(false) }
    val credentialPreferences = remember(context) {
        context.getSharedPreferences("steam_credential_bindings", Context.MODE_PRIVATE)
    }
    val credentialPreferenceKey = remember(account.steamId) {
        "steam_${account.steamId}_password_entry_id"
    }

    pendingRevokeDevice?.let { device ->
        AlertDialog(
            onDismissRequest = {
                pendingRevokeDevice = null
                useBoundCredential = false
                revokeUserName = account.accountName
                revokePassword = ""
            },
            title = { Text(stringResource(R.string.steam_authorized_devices_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AuthorizedDeviceDetails(device)
                    Text(stringResource(R.string.steam_authorized_device_revoke_password_warning))
                    if (useBoundCredential) {
                        Text(
                            stringResource(R.string.steam_revoke_credential_auto_verify),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        OutlinedButton(
                            onClick = { showRevokePasswordPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.autofill_select_password))
                        }
                        OutlinedTextField(
                            value = revokeUserName,
                            onValueChange = { revokeUserName = it },
                            label = { Text(stringResource(R.string.steam_login_account_label)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = revokePassword,
                            onValueChange = { revokePassword = it },
                            label = { Text(stringResource(R.string.steam_login_password_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = revokeUserName.isNotBlank() && revokePassword.isNotBlank(),
                    onClick = {
                        onRevokeDevice(device, revokeUserName.trim(), revokePassword)
                        pendingRevokeDevice = null
                        useBoundCredential = false
                        revokeUserName = account.accountName
                        revokePassword = ""
                    }
                ) {
                    Text(
                        text = stringResource(R.string.remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRevokeDevice = null
                        useBoundCredential = false
                        revokeUserName = account.accountName
                        revokePassword = ""
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRevokePasswordPicker && pendingRevokeDevice != null) {
        PasswordEntryPickerBottomSheet(
            visible = true,
            title = stringResource(R.string.select_password_to_bind),
            passwords = passwordEntriesForPicker.filter { !it.isDeleted && !it.isArchived },
            onDismiss = { showRevokePasswordPicker = false },
            onSelect = { entry ->
                revokeUserName = runCatching {
                    pickerSecurityManager.decryptData(entry.username)
                }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                    ?: entry.username.trim()
                revokePassword = runCatching {
                    pickerSecurityManager.decryptData(entry.password)
                }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                    ?: entry.password.trim()
                showRevokePasswordPicker = false
                Toast.makeText(
                    context,
                    context.getString(R.string.steam_login_fill_from_password_applied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.steam_authorized_devices_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !account.accessToken.isNullOrBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh)
                    )
                }
            }

            if (account.accessToken.isNullOrBlank()) {
                EmptyState(stringResource(R.string.steam_no_authorized_device_session))
            } else if (devices.isEmpty()) {
                EmptyState(stringResource(R.string.steam_no_authorized_devices))
            } else {
                devices.forEach { device ->
                    SteamAuthorizedDeviceRow(
                        device = device,
                        onRequestRevoke = {
                            val boundEntryId = credentialPreferences
                                .getLong(credentialPreferenceKey, -1L)
                            val boundEntry = passwordEntriesForPicker.firstOrNull {
                                it.id == boundEntryId && !it.isDeleted && !it.isArchived
                            }
                            val boundUserName = boundEntry?.let { entry ->
                                runCatching { pickerSecurityManager.decryptData(entry.username) }
                                    .getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                                    ?: entry.username.trim()
                            }.orEmpty()
                            val boundPassword = boundEntry?.let { entry ->
                                runCatching { pickerSecurityManager.decryptData(entry.password) }
                                    .getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                                    ?: entry.password.trim()
                            }.orEmpty()
                            pendingRevokeDevice = device
                            useBoundCredential = boundUserName.isNotBlank() && boundPassword.isNotBlank()
                            revokeUserName = boundUserName.ifBlank { account.accountName }
                            revokePassword = boundPassword
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamAuthorizedDeviceRow(
    device: SteamAuthorizedDevice,
    onRequestRevoke: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.description.ifBlank { stringResource(R.string.steam_unknown_device) },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        if (device.isCurrent) {
                            R.string.steam_current_device
                        } else if (device.loggedIn) {
                            R.string.steam_device_logged_in
                        } else {
                            R.string.steam_device_logged_out
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (device.isCurrent || device.loggedIn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            AuthorizedDeviceDetails(device)
            if (!device.isCurrent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRequestRevoke) {
                        Text(
                            text = stringResource(R.string.remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun AuthorizedDeviceDetails(device: SteamAuthorizedDevice) {
    val lastSeen = device.lastSeen
    Text(
        text = steamPlatformLabel(device.platformType),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (lastSeen != null) {
        Text(
            text = listOf(
                lastSeen.location.takeIf { it.isNotBlank() },
                lastSeen.timeSeconds.takeIf { it > 0L }?.let {
                    formatSteamLoginTime(it * 1000L)
                }
            ).filterNotNull().joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun steamPlatformLabel(platformType: Int): String {
    return when (platformType) {
        1 -> stringResource(R.string.steam_platform_client)
        2 -> stringResource(R.string.steam_platform_web)
        3 -> stringResource(R.string.steam_platform_mobile)
        else -> stringResource(R.string.steam_platform_unknown)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteamConfirmationAccountPickerSheet(
    accounts: List<SteamAccount>,
    selectedAccountId: Long?,
    onSelectAccount: (SteamAccount) -> Unit,
    onDismissRequest: () -> Unit
) {
    MonicaModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.steam_switch_account),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(accounts, key = { it.id }) { steamAccount ->
                    SteamConfirmationAccountOptionRow(
                        account = steamAccount,
                        selected = steamAccount.id == selectedAccountId,
                        onClick = { onSelectAccount(steamAccount) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamConfirmationAccountOptionRow(
    account: SteamAccount,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SteamAvatarImage(account = account, size = 34.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName.ifBlank { account.accountName }.ifBlank { account.visibleSteamId }.ifBlank { "Steam" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        if (account.canUseConfirmations) {
                            R.string.steam_status_ready
                        } else if (!account.hasRealSteamId) {
                            R.string.steam_status_code_only
                        } else {
                            R.string.steam_status_missing_session
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (account.canUseConfirmations) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.steam_selected_account_marker),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConfirmationRow(
    confirmation: SteamConfirmation,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SteamConfirmationItemImage(confirmation = confirmation)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = confirmation.headline.ifBlank { confirmation.type },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = confirmation.summary.ifBlank { confirmation.id },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selectionMode || selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.select_all),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SteamConfirmationItemImage(confirmation: SteamConfirmation) {
    val context = LocalContext.current
    var image by remember(confirmation.imageUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(confirmation.imageUrl) {
        image = loadSteamConfirmationImage(context, confirmation.imageUrl)
    }

    Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        val snapshot = image
        if (snapshot != null) {
            Image(
                bitmap = snapshot,
                contentDescription = stringResource(R.string.steam_confirmation_item_image),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SteamLoginApprovalSection(
    account: SteamAccount,
    pendingLogins: List<SteamPendingLogin>,
    pendingScannedQr: String?,
    onScannedQrHandled: () -> Unit,
    onRefresh: () -> Unit,
    onRespondPending: (SteamPendingLogin, Boolean) -> Unit,
    onRespondQr: (String, Boolean) -> Unit
) {
    var qrText by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<LoginActionRequest?>(null) }
    var pendingQrAction by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var scannedQrAction by remember { mutableStateOf<String?>(null) }
    val loginApprovalUnavailableReason = if (account.canApproveLogins) {
        null
    } else {
        steamLoginApprovalUnavailableText(account)
    }

    LaunchedEffect(pendingScannedQr) {
        val qr = pendingScannedQr?.trim().orEmpty()
        if (qr.isNotBlank()) {
            qrText = qr
            scannedQrAction = qr
            onScannedQrHandled()
        }
    }

    pendingAction?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(stringResource(R.string.steam_login_request_title))
            },
            text = {
                LoginActionDetails(request.login)
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onRespondPending(request.login, false)
                            pendingAction = null
                        }
                    ) {
                        Text(stringResource(R.string.steam_reject))
                    }
                    TextButton(
                        onClick = {
                            onRespondPending(request.login, true)
                            pendingAction = null
                        }
                    ) {
                        Text(stringResource(R.string.steam_approve))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingQrAction?.let { (rawQr, approve) ->
        AlertDialog(
            onDismissRequest = { pendingQrAction = null },
            title = {
                Text(
                    stringResource(
                        if (approve) {
                            R.string.steam_approve_qr_login_title
                        } else {
                            R.string.steam_reject_qr_login_title
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rawQr, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    loginApprovalUnavailableReason?.let { reason ->
                        Text(
                            text = reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRespondQr(rawQr, approve)
                        pendingQrAction = null
                    },
                    enabled = loginApprovalUnavailableReason == null
                ) {
                    Text(stringResource(if (approve) R.string.steam_approve else R.string.steam_reject))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingQrAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    scannedQrAction?.let { rawQr ->
        AlertDialog(
            onDismissRequest = { scannedQrAction = null },
            title = { Text(stringResource(R.string.steam_qr_login_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rawQr, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    loginApprovalUnavailableReason?.let { reason ->
                        Text(
                            text = reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onRespondQr(rawQr, false)
                            scannedQrAction = null
                        },
                        enabled = loginApprovalUnavailableReason == null
                    ) {
                        Text(stringResource(R.string.steam_reject))
                    }
                    TextButton(
                        onClick = {
                            onRespondQr(rawQr, true)
                            scannedQrAction = null
                        },
                        enabled = loginApprovalUnavailableReason == null
                    ) {
                        Text(stringResource(R.string.steam_approve))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedQrAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.steam_login_approval_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, enabled = account.canApproveLogins) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.refresh))
                }
            }

            if (!account.canApproveLogins) {
                EmptyState(
                    steamLoginApprovalUnavailableText(account)
                )
            } else if (pendingLogins.isEmpty()) {
                EmptyState(stringResource(R.string.steam_no_pending_logins))
            } else {
                pendingLogins.forEach { login ->
                    PendingLoginRow(
                        login = login,
                        onOpenDetails = { target ->
                            pendingAction = LoginActionRequest(target)
                        }
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = qrText,
                    onValueChange = { qrText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.steam_qr_link_label)) },
                    leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pendingQrAction = qrText to true },
                        enabled = account.canApproveLogins && qrText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.steam_approve))
                    }
                    OutlinedButton(
                        onClick = { pendingQrAction = qrText to false },
                        enabled = account.canApproveLogins && qrText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.steam_reject))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginActionDetails(login: SteamPendingLogin) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailLine(
            stringResource(R.string.steam_device_label),
            login.deviceName.ifBlank { stringResource(R.string.steam_unknown_device) }
        )
        DetailLine(stringResource(R.string.steam_location_label), login.location.ifBlank { "-" })
        DetailLine(stringResource(R.string.steam_time_label), formatSteamLoginTime(login.detectedAtMillis))
        DetailLine(stringResource(R.string.steam_ip_label), login.ip.ifBlank { "-" })
        DetailLine(stringResource(R.string.steam_client_label), login.clientId.toString())
    }
}

@Composable
private fun PendingLoginRow(
    login: SteamPendingLogin,
    onOpenDetails: (SteamPendingLogin) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetails(login) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = login.deviceName.ifBlank { stringResource(R.string.steam_login_fallback_title) },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOf(login.location.ifBlank { "-" }, formatSteamLoginTime(login.detectedAtMillis))
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank {
                            stringResource(R.string.steam_client_id_fallback, login.clientId)
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { onOpenDetails(login) }) {
                Text(stringResource(R.string.details))
            }
        }
    }
}

private fun formatSteamLoginTime(timestampMillis: Long): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))
}

@Composable
private fun SteamEmptyAccountContent(
    onAddAccount: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.steam_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.steam_empty_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAddAccount) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.steam_add_account_button))
            }
        }
    }
}

@Composable
private fun SteamAddMethodDialog(
    onDismissRequest: () -> Unit,
    onSelectMaFile: () -> Unit,
    onSelectKeyOnly: () -> Unit,
    onSelectLogin: () -> Unit,
    onSelectQrLogin: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.steam_add_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSelectMaFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.steam_add_method_mafile))
                }
                OutlinedButton(
                    onClick = onSelectKeyOnly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.steam_add_method_key_only))
                }
                OutlinedButton(
                    onClick = onSelectLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.steam_add_method_login))
                }
                OutlinedButton(
                    onClick = onSelectQrLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.steam_add_method_qr_login))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SteamKeyOnlyImportDialog(
    onDismissRequest: () -> Unit,
    onImport: (String, String, String) -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var sharedSecret by remember { mutableStateOf("") }
    val canImport = accountName.isNotBlank() && sharedSecret.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.steam_key_only_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.steam_key_only_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.steam_remark_optional_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text(stringResource(R.string.steam_login_account_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sharedSecret,
                    onValueChange = { sharedSecret = it },
                    label = { Text(stringResource(R.string.steam_key_only_secret_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(displayName.trim(), accountName.trim(), sharedSecret.trim())
                },
                enabled = canImport
            ) {
                Text(stringResource(R.string.steam_key_only_import_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SteamMaFileImportDialog(
    onDismissRequest: () -> Unit,
    onImportMaFile: (Uri, Uri?, String, String, String) -> Unit,
) {
    var maFileUri by remember { mutableStateOf<Uri?>(null) }
    var manifestUri by remember { mutableStateOf<Uri?>(null) }
    var maFilePassword by remember { mutableStateOf("") }
    var maFileDisplayName by remember { mutableStateOf("") }
    val maFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        maFileUri = uri
    }
    val manifestPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        manifestUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.steam_mafile_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { maFilePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.steam_mafile_button))
                }
                OutlinedButton(
                    onClick = { manifestPicker.launch("application/json") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.steam_manifest_button))
                }
                Text(
                    text = maFileUri?.lastPathSegment ?: stringResource(R.string.steam_no_mafile_selected),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = maFilePassword,
                    onValueChange = { maFilePassword = it },
                    label = { Text(stringResource(R.string.steam_mafile_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = maFileDisplayName,
                    onValueChange = { maFileDisplayName = it },
                    label = { Text(stringResource(R.string.steam_remark_optional_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    maFileUri?.let { uri ->
                        onImportMaFile(uri, manifestUri, maFilePassword, maFileDisplayName, "")
                    }
                },
                enabled = maFileUri != null
            ) {
                Text(stringResource(R.string.steam_import_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SteamMaFileSteamIdCompletionDialog(
    request: SteamMaFileSteamIdRequestUi,
    onDismissRequest: () -> Unit,
    onUseSteamLogin: () -> Unit,
    onImportCodeOnly: () -> Unit,
    onImportMaFile: (Uri, Uri?, String, String, String) -> Unit,
) {
    var steamIdInput by remember { mutableStateOf("") }
    val normalizedInput = steamIdInput.trim()
    val steamIdInvalid = normalizedInput.isNotEmpty() && !normalizedInput.isValidSteamIdOrAccountId()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.steam_mafile_steamid_completion_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.steam_mafile_steamid_completion_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.steam_mafile_code_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = request.fileName.ifBlank { stringResource(R.string.steam_no_mafile_selected) },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    onClick = onUseSteamLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.steam_mafile_use_login_button))
                }
                OutlinedTextField(
                    value = steamIdInput,
                    onValueChange = { steamIdInput = it.filterNot(Char::isWhitespace) },
                    label = { Text(stringResource(R.string.steam_mafile_steamid_label)) },
                    supportingText = {
                        Text(
                            if (steamIdInvalid) {
                                stringResource(R.string.steam_mafile_invalid_steamid_message)
                            } else {
                                stringResource(R.string.steam_mafile_steamid_supporting)
                            }
                        )
                    },
                    isError = steamIdInvalid,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImportMaFile(
                        request.maFileUri,
                        request.manifestUri,
                        request.password,
                        request.displayName,
                        normalizedInput
                    )
                },
                enabled = normalizedInput.isNotBlank() && !steamIdInvalid
            ) {
                Text(stringResource(R.string.steam_import_button))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onImportCodeOnly) {
                    Text(stringResource(R.string.steam_mafile_code_only_button))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

private fun String.isValidSteamIdOrAccountId(): Boolean {
    if (matches(Regex("""7656119\d{10}"""))) return true
    val accountId = takeIf { it.matches(Regex("""\d{1,10}""")) }
        ?.toLongOrNull()
        ?: return false
    return accountId in 1L..4_294_967_295L
}

@Composable
private fun steamLoginApprovalUnavailableText(account: SteamAccount?): String {
    return when {
        account == null -> stringResource(R.string.steam_no_login_session)
        !account.hasRealSteamId -> stringResource(R.string.steam_no_login_missing_steamid)
        account.sharedSecret.isBlank() -> stringResource(R.string.steam_no_login_missing_shared_secret)
        account.accessToken.isNullOrBlank() && account.refreshToken.isNullOrBlank() ->
            stringResource(R.string.steam_no_login_missing_session_detail)
        else -> stringResource(R.string.steam_no_login_session)
    }
}

@Composable
private fun steamConfirmationUnavailableText(account: SteamAccount?): String {
    return when {
        account == null -> stringResource(R.string.steam_no_confirmation_session)
        !account.hasRealSteamId -> stringResource(R.string.steam_no_confirmation_missing_steamid)
        account.identitySecret.isNullOrBlank() -> stringResource(R.string.steam_no_confirmation_missing_identity_secret)
        account.accessToken.isNullOrBlank() && account.refreshToken.isNullOrBlank() ->
            stringResource(R.string.steam_no_confirmation_missing_session_detail)
        else -> stringResource(R.string.steam_no_confirmation_session)
    }
}

@Composable
private fun SteamQrLoginImportDialog(
    pendingQrChallenge: SteamQrLoginChallengeUi?,
    pendingChallenge: SteamLoginChallengeUi?,
    availableCodeAccounts: List<SteamAccount>,
    loading: Boolean,
    onDismissRequest: () -> Unit,
    onRestart: () -> Unit,
    onSubmitLoginCode: (String) -> Unit
) {
    val context = LocalContext.current
    val pickerSecurityManager = remember(context) { SecurityManager(context) }
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val legacyTotpItems by passwordDatabase.secureItemDao()
        .getActiveItemsByType(ItemType.TOTP)
        .collectAsState(initial = emptyList())
    var challengeCode by remember { mutableStateOf("") }
    var showMonicaCodePicker by remember { mutableStateOf(false) }
    val waitingForCode = pendingChallenge != null
    val requiresCode = pendingChallenge?.requiresCode == true
    val hasLegacySteamCode = remember(legacyTotpItems, pickerSecurityManager, pendingChallenge?.pendingSessionId) {
        val currentSeconds = System.currentTimeMillis() / 1000L
        legacyTotpItems.any {
            it.toLegacySteamAuthenticatorCodeSource(pickerSecurityManager, currentSeconds) != null
        }
    }
    val canUseMonicaCode = pendingChallenge?.canUseMonicaCode == true &&
        (availableCodeAccounts.any { it.sharedSecret.isNotBlank() } || hasLegacySteamCode)

    LaunchedEffect(pendingChallenge?.pendingSessionId, pendingChallenge?.confirmationType) {
        challengeCode = ""
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                stringResource(
                    if (waitingForCode) {
                        R.string.steam_verification_required
                    } else {
                        R.string.steam_qr_login_import_title
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (pendingChallenge != null) {
                    if (pendingChallenge.canPoll) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = stringResource(R.string.steam_login_waiting_approval),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = pendingChallenge.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (requiresCode) {
                        OutlinedTextField(
                            value = challengeCode,
                            onValueChange = { challengeCode = it },
                            label = { Text(stringResource(R.string.steam_code_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (canUseMonicaCode) {
                            OutlinedButton(
                                onClick = { showMonicaCodePicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.steam_use_monica_code))
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.steam_qr_login_import_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pendingQrChallenge != null) {
                        SteamQrLoginCodeImage(
                            challengeUrl = pendingQrChallenge.challengeUrl,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.steam_qr_login_import_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text(
                            text = stringResource(R.string.steam_qr_login_import_starting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (waitingForCode && requiresCode) {
                TextButton(
                    onClick = { onSubmitLoginCode(challengeCode) },
                    enabled = challengeCode.isNotBlank()
                ) {
                    Text(stringResource(R.string.steam_submit_code_button))
                }
            } else {
                TextButton(
                    onClick = onRestart,
                    enabled = !loading
                ) {
                    Text(stringResource(R.string.steam_qr_login_import_refresh))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showMonicaCodePicker) {
        SteamAuthenticatorCodePickerBottomSheet(
            accounts = availableCodeAccounts,
            legacyTotpItems = legacyTotpItems,
            securityManager = pickerSecurityManager,
            preferredSteamId = pendingChallenge?.steamId,
            onSelectCode = { code ->
                challengeCode = code
                showMonicaCodePicker = false
            },
            onDismissRequest = { showMonicaCodePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteamAuthenticatorCodePickerBottomSheet(
    accounts: List<SteamAccount>,
    legacyTotpItems: List<SecureItem>,
    securityManager: SecurityManager,
    preferredSteamId: String?,
    onSelectCode: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val currentMillis = rememberTotpTickerMillis(smooth = true)
    val currentSeconds = currentMillis / 1000L
    val secondsRemaining = SteamTotp.secondsRemaining(currentSeconds)
    var authenticatorQuery by rememberSaveable { mutableStateOf("") }
    val codeAccounts = remember(accounts, preferredSteamId) {
        accounts
            .filter { it.sharedSecret.isNotBlank() }
            .sortedWith(
                compareByDescending<SteamAccount> { it.matchesSteamId(preferredSteamId) }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id }
            )
    }
    val legacyCodeItems = remember(legacyTotpItems, securityManager, currentSeconds) {
        legacyTotpItems
            .mapNotNull { item ->
                item.toLegacySteamAuthenticatorCodeSource(securityManager, currentSeconds)
            }
            .sortedWith(compareBy<LegacySteamAuthenticatorCodeSource> { it.item.sortOrder }.thenBy { it.item.id })
    }
    val filteredAccounts = remember(codeAccounts, authenticatorQuery) {
        val query = authenticatorQuery.trim()
        if (query.isBlank()) {
            codeAccounts
        } else {
            codeAccounts.filter { account ->
                listOf(
                    account.displayName,
                    account.accountName,
                    account.visibleSteamId,
                    account.steamId
                ).any { it.contains(query, ignoreCase = true) }
            }
        }
    }
    val filteredLegacyCodeItems = remember(legacyCodeItems, authenticatorQuery) {
        val query = authenticatorQuery.trim()
        if (query.isBlank()) {
            legacyCodeItems
        } else {
            legacyCodeItems.filter { source ->
                listOf(
                    source.item.title,
                    source.totpData.issuer,
                    source.totpData.accountName
                ).any { it.contains(query, ignoreCase = true) }
            }
        }
    }
    val filteredCount = filteredAccounts.size + filteredLegacyCodeItems.size

    MonicaModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.steam_authenticator_code_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.password_picker_results_count, filteredCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = authenticatorQuery,
                onValueChange = { authenticatorQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_authenticator)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )

            if (filteredCount == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.steam_authenticator_code_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAccounts, key = { it.id }) { account ->
                        val code = remember(account.sharedSecret, currentSeconds) {
                            runCatching {
                                SteamTotp.generateAuthCode(account.sharedSecret, currentSeconds)
                            }.getOrDefault("")
                        }
                        SteamAuthenticatorCodePickerRow(
                            account = account,
                            code = code,
                            secondsRemaining = secondsRemaining,
                            highlighted = account.matchesSteamId(preferredSteamId),
                            onClick = {
                                if (code.isNotBlank()) {
                                    onSelectCode(code)
                                }
                            }
                        )
                    }
                    items(filteredLegacyCodeItems, key = { "legacy-${it.item.id}" }) { source ->
                        LegacySteamAuthenticatorCodePickerRow(
                            source = source,
                            secondsRemaining = secondsRemaining,
                            onClick = {
                                if (source.code.isNotBlank()) {
                                    onSelectCode(source.code)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamAuthenticatorCodePickerRow(
    account: SteamAccount,
    code: String,
    secondsRemaining: Int,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = code.isNotBlank(), onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SteamAvatarImage(account = account, size = 38.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = account.displayName.ifBlank { account.accountName }
                        .ifBlank { account.visibleSteamId }
                        .ifBlank { "Steam" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = account.accountName.ifBlank { account.visibleSteamId }
                        .ifBlank { account.steamId },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = code.ifBlank { "-" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.steam_seconds_remaining, secondsRemaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegacySteamAuthenticatorCodePickerRow(
    source: LegacySteamAuthenticatorCodeSource,
    secondsRemaining: Int,
    onClick: () -> Unit
) {
    val title = source.item.title
        .ifBlank { source.totpData.accountName }
        .ifBlank { source.totpData.issuer }
        .ifBlank { stringResource(R.string.otp_type_steam) }
    val subtitle = listOf(
        source.totpData.accountName,
        source.totpData.issuer,
        stringResource(R.string.authenticator)
    )
        .map { it.trim() }
        .filter { it.isNotBlank() && it != title }
        .distinct()
        .joinToString(" / ")
        .ifBlank { stringResource(R.string.otp_type_steam) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = source.code.isNotBlank(), onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = source.code.ifBlank { "-" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.steam_seconds_remaining, secondsRemaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun SecureItem.toLegacySteamAuthenticatorCodeSource(
    securityManager: SecurityManager,
    currentSeconds: Long
): LegacySteamAuthenticatorCodeSource? {
    val totpData = TotpDataResolver.parseStoredItemData(
        itemData = itemData,
        fallbackIssuer = title,
        fallbackAccountName = title,
        decryptIfNeeded = { raw -> securityManager.decryptData(raw) }
    ) ?: return null

    val normalized = TotpDataResolver.normalizeTotpData(totpData)
    if (normalized.otpType != OtpType.STEAM) return null

    val code = runCatching {
        TotpGenerator.generateOtp(normalized, currentSeconds = currentSeconds)
    }.getOrDefault("").trim()
    if (!code.isSteamGuardFiveCharacterCode()) return null

    return LegacySteamAuthenticatorCodeSource(
        item = this,
        totpData = normalized,
        code = code
    )
}

private fun String.isSteamGuardFiveCharacterCode(): Boolean {
    return length == 5 && any { it.isLetter() }
}

private fun SteamAccount.matchesSteamId(steamIdCandidate: String?): Boolean {
    val candidate = steamIdCandidate?.trim().orEmpty()
    return candidate.isNotBlank() && (steamId == candidate || visibleSteamId == candidate)
}

@Composable
private fun SteamQrLoginCodeImage(
    challengeUrl: String,
    modifier: Modifier = Modifier
) {
    val qrBitmap = remember(challengeUrl) {
        createSteamQrLoginBitmap(challengeUrl)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = androidx.compose.ui.graphics.Color.White,
        contentColor = androidx.compose.ui.graphics.Color.Black,
        tonalElevation = 0.dp
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap,
                contentDescription = stringResource(R.string.steam_qr_login_import_image_description),
                modifier = Modifier
                    .size(220.dp)
                    .padding(14.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.steam_qr_login_import_failed),
                    color = androidx.compose.ui.graphics.Color.Black
                )
            }
        }
    }
}

private fun createSteamQrLoginBitmap(content: String, size: Int = 768): ImageBitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bitmap.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun SteamLoginImportDialog(
    pendingChallenge: SteamLoginChallengeUi?,
    availableCodeAccounts: List<SteamAccount>,
    onDismissRequest: () -> Unit,
    onBeginLogin: (String, String, String, Long?) -> Unit,
    onSubmitLoginCode: (String) -> Unit,
    @StringRes titleRes: Int = R.string.steam_login_title,
    @StringRes descriptionRes: Int? = null,
    showRemarkField: Boolean = true
) {
    val context = LocalContext.current
    val pickerSecurityManager = remember(context) { SecurityManager(context) }
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val passwordEntriesForPicker by passwordDatabase.passwordEntryDao()
        .getAllPasswordEntries()
        .collectAsState(initial = emptyList())
    val legacyTotpItems by passwordDatabase.secureItemDao()
        .getActiveItemsByType(ItemType.TOTP)
        .collectAsState(initial = emptyList())
    var loginName by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginDisplayName by remember { mutableStateOf("") }
    var selectedPasswordEntryId by remember { mutableStateOf<Long?>(null) }
    var challengeCode by remember { mutableStateOf("") }
    var showSteamPasswordPicker by remember { mutableStateOf(false) }
    var showMonicaCodePicker by remember { mutableStateOf(false) }
    val waitingForCode = pendingChallenge != null
    val requiresCode = pendingChallenge?.requiresCode == true
    val hasLegacySteamCode = remember(legacyTotpItems, pickerSecurityManager, pendingChallenge?.pendingSessionId) {
        val currentSeconds = System.currentTimeMillis() / 1000L
        legacyTotpItems.any {
            it.toLegacySteamAuthenticatorCodeSource(pickerSecurityManager, currentSeconds) != null
        }
    }
    val canUseMonicaCode = pendingChallenge?.canUseMonicaCode == true &&
        (availableCodeAccounts.any { it.sharedSecret.isNotBlank() } || hasLegacySteamCode)

    LaunchedEffect(pendingChallenge?.pendingSessionId, pendingChallenge?.confirmationType) {
        challengeCode = ""
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                stringResource(
                    if (waitingForCode) {
                        R.string.steam_verification_required
                    } else {
                        titleRes
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pendingChallenge == null) {
                    descriptionRes?.let { resId ->
                        Text(
                            text = stringResource(resId),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showSteamPasswordPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.autofill_select_password))
                    }
                    OutlinedTextField(
                        value = loginName,
                        onValueChange = { loginName = it },
                        label = { Text(stringResource(R.string.steam_login_account_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it },
                        label = { Text(stringResource(R.string.steam_login_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (showRemarkField) {
                        OutlinedTextField(
                            value = loginDisplayName,
                            onValueChange = { loginDisplayName = it },
                            label = { Text(stringResource(R.string.steam_remark_optional_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                } else {
                    if (pendingChallenge.canPoll) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = stringResource(R.string.steam_login_waiting_approval),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = pendingChallenge.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (requiresCode) {
                        OutlinedTextField(
                            value = challengeCode,
                            onValueChange = { challengeCode = it },
                            label = { Text(stringResource(R.string.steam_code_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (canUseMonicaCode) {
                            OutlinedButton(
                                onClick = { showMonicaCodePicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.steam_use_monica_code))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!waitingForCode || requiresCode) {
                TextButton(
                    onClick = {
                        if (waitingForCode) {
                            onSubmitLoginCode(challengeCode)
                        } else {
                            onBeginLogin(loginName, loginPassword, loginDisplayName, selectedPasswordEntryId)
                        }
                    },
                    enabled = if (waitingForCode) {
                        challengeCode.isNotBlank()
                    } else {
                        loginName.isNotBlank() && loginPassword.isNotBlank()
                    }
                ) {
                    Text(
                        stringResource(
                            if (waitingForCode) {
                                R.string.steam_submit_code_button
                            } else {
                                R.string.steam_login_button
                            }
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showMonicaCodePicker) {
        SteamAuthenticatorCodePickerBottomSheet(
            accounts = availableCodeAccounts,
            legacyTotpItems = legacyTotpItems,
            securityManager = pickerSecurityManager,
            preferredSteamId = pendingChallenge?.steamId,
            onSelectCode = { code ->
                challengeCode = code
                showMonicaCodePicker = false
            },
            onDismissRequest = { showMonicaCodePicker = false }
        )
    }

    if (showSteamPasswordPicker && pendingChallenge == null) {
        PasswordEntryPickerBottomSheet(
            visible = true,
            title = stringResource(R.string.select_password_to_bind),
            passwords = passwordEntriesForPicker.filter { !it.isDeleted && !it.isArchived },
            onDismiss = { showSteamPasswordPicker = false },
            onSelect = { entry ->
                val resolvedUsername = runCatching { pickerSecurityManager.decryptData(entry.username) }
                    .getOrNull()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: entry.username.trim()
                val resolvedPassword = runCatching { pickerSecurityManager.decryptData(entry.password) }
                    .getOrNull()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: entry.password.trim()

                loginName = resolvedUsername
                loginPassword = resolvedPassword
                selectedPasswordEntryId = entry.id
                showSteamPasswordPicker = false
                Toast.makeText(
                    context,
                    context.getString(R.string.steam_login_fill_from_password_applied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

private fun badgeCountText(count: Int): String {
    return if (count > 99) {
        "99+"
    } else {
        count.toString()
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
