package takagi.ru.monica.bitwarden.ui

import takagi.ru.monica.ui.navigation.MonicaPageContent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import kotlinx.coroutines.launch
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenAccountDevice
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint
import takagi.ru.monica.security.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenAccountScreen(viewModel: BitwardenViewModel, vaultId: Long, onNavigateBack: () -> Unit) {
    val vaults by viewModel.vaults.collectAsState()
    val loginState by viewModel.loginState.collectAsState()
    val vault = vaults.firstOrNull { it.id == vaultId }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(context) { BitwardenRepository.getInstance(context) }
    val generationFlow = remember(repository, vaultId) { repository.getVaultSessionGenerationFlow(vaultId) }
    val vaultGeneration by generationFlow.collectAsState()
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    val accountIdentity = vault?.let { it.accountKey + notificationAccountFingerprint(it) }
    val unlocked = appUnlocked && vault?.isLocked == false && viewModel.isVaultUnlocked(vaultId)
    var reload by remember(vaultId) { mutableIntStateOf(0) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var foregroundGeneration by remember(lifecycle, vaultId) { mutableLongStateOf(0L) }
    var busy by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf(false) }
    var devices by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf<List<BitwardenAccountDevice>>(emptyList()) }
    var error by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf<String?>(null) }
    var showFingerprint by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf(false) }
    var showManagement by remember(vaultId, accountIdentity, appGeneration) { mutableStateOf(false) }
    var fingerprint by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf<String?>(null) }
    var fingerprintError by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var configurationRequest by remember(vaultId) { mutableLongStateOf(0L) }
    var configurationLoading by remember(vaultId) { mutableStateOf(false) }
    var configurationJob by remember(vaultId) { mutableStateOf<Job?>(null) }
    var configurationSession by remember(vaultId) { mutableStateOf<AccountEnvironmentReauthentication?>(null) }
    fun discardConfiguration() {
        val ownedOperation = configurationSession != null || configurationJob != null || configurationLoading
        configurationRequest++
        configurationJob?.cancel(); configurationJob = null; configurationLoading = false
        configurationSession = null
        if (ownedOperation) viewModel.resetLoginState(cancelInFlight = true)
    }
    val defaultError = stringResource(R.string.bitwarden_account_operation_failed)
    val fingerprintUnavailable = stringResource(R.string.bitwarden_account_fingerprint_unavailable)
    val clearVisibleDevices by rememberUpdatedState<() -> Unit>({
        devices = emptyList(); error = null
        showFingerprint = false; fingerprint = null; fingerprintError = null
    })
    DisposableEffect(lifecycle, vaultId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_STOP -> {
                    foregroundGeneration++
                    resumed = false
                    clearVisibleDevices()
                    // An already formed edit session survives a normal trip to email/OTP apps.
                    // Only a still-pending local preparation is cancelled here.
                    if (configurationJob != null) {
                        configurationRequest++; configurationJob?.cancel(); configurationJob = null; configurationLoading = false
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            foregroundGeneration++
            resumed = false
            clearVisibleDevices()
            discardConfiguration()
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(vaultId, reload, resumed, foregroundGeneration, unlocked, accountIdentity, vaultGeneration, appGeneration) {
        devices = emptyList()
        error = null
        if (!resumed || !unlocked) { busy = false; return@LaunchedEffect }
        val requestForegroundGeneration = foregroundGeneration
        val requestAppGeneration = appGeneration
        val requestVaultGeneration = vaultGeneration
        busy = true
        try {
            val result = viewModel.getAccountDevices(vaultId)
            val current = viewModel.vaults.value.firstOrNull { it.id == vaultId }
            if (resumed && foregroundGeneration == requestForegroundGeneration &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == requestAppGeneration &&
                repository.getVaultSessionGeneration(vaultId) == requestVaultGeneration && viewModel.isVaultUnlocked(vaultId) &&
                current?.let { it.accountKey + notificationAccountFingerprint(it) } == accountIdentity) {
                result.onSuccess { devices = it }.onFailure { error = it.message ?: defaultError }
            }
        } finally { busy = false }
    }
    // Capture scalar generations, not the delegated state objects. An old UI
    // callback must not become eligible again when those objects receive an unlock.
    val renderedAppGeneration = appGeneration
    val renderedVaultGeneration = vaultGeneration
    val renderedForegroundGeneration = foregroundGeneration
    fun fingerprintSessionCurrent(): Boolean = resumed && foregroundGeneration == renderedForegroundGeneration &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && SessionManager.isUnlocked.value &&
        SessionManager.lockGeneration.value == renderedAppGeneration && repository.getVaultSessionGeneration(vaultId) == renderedVaultGeneration &&
        viewModel.isVaultUnlocked(vaultId) && viewModel.vaults.value.firstOrNull { it.id == vaultId }?.let {
            !it.isLocked && it.accountKey + notificationAccountFingerprint(it) == accountIdentity
        } == true
    fun prepareConfiguration() {
        if (configurationLoading || !fingerprintSessionCurrent()) return
        val selected = vault ?: return
        val request = ++configurationRequest
        val expectedAppGeneration = SessionManager.lockGeneration.value
        val expectedIdentity = accountIdentity ?: return
        configurationLoading = true
        viewModel.resetLoginState(cancelInFlight = true)
        configurationJob = scope.launch {
            try {
                val result = repository.loadAccountEnvironmentDraft(vaultId)
                if (configurationRequest != request || !fingerprintSessionCurrent()) return@launch
                val draft = result.getOrNull()
                if (draft == null) { error = defaultError; return@launch }
                // Loading is authorized while unlocked; the candidate is used only after revocation.
                repository.forceLock(vaultId)
                val current = viewModel.vaults.value.firstOrNull { it.id == vaultId }
                if (configurationRequest != request || !resumed ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !SessionManager.isUnlocked.value ||
                    SessionManager.lockGeneration.value != expectedAppGeneration ||
                    current?.let { it.accountKey + notificationAccountFingerprint(it) } != expectedIdentity) return@launch
                configurationSession = AccountEnvironmentReauthentication(draft, selected.email,
                    expectedIdentity, expectedAppGeneration, repository.getVaultSessionGeneration(vaultId))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (configurationRequest == request) error = defaultError }
            finally { if (configurationRequest == request) { configurationLoading = false; configurationJob = null } }
        }
    }
    LaunchedEffect(configurationSession, appGeneration, vaultGeneration, accountIdentity, resumed, loginState) {
        val session = configurationSession ?: return@LaunchedEffect
        if (!SessionManager.isUnlocked.value || appGeneration != session.appGeneration) {
            discardConfiguration()
        } else if (loginState !is BitwardenViewModel.LoginState.Loading && loginState !is BitwardenViewModel.LoginState.Success &&
            (repository.getVaultSessionGeneration(vaultId) != session.lockedGeneration || accountIdentity != session.identity)) {
            // During authentication the repository owns publication/lock checks. Do not cancel its
            // successful publication merely because that publication advances the vault generation.
            discardConfiguration()
        }
    }
    LaunchedEffect(showFingerprint, resumed, unlocked, accountIdentity, vaultGeneration, appGeneration) {
        fingerprint = null
        fingerprintError = null
        if (!showFingerprint || !unlocked || !resumed) { showFingerprint = false; return@LaunchedEffect }
        val result = repository.getAccountFingerprint(vaultId)
        if (showFingerprint && fingerprintSessionCurrent()) {
            result.onSuccess { fingerprint = it }.onFailure { fingerprintError = fingerprintUnavailable }
        }
    }
    if (showFingerprint && unlocked && resumed) {
        BitwardenFingerprintSheet(
            phrase = fingerprint, error = fingerprintError,
            onDismiss = { showFingerprint = false; fingerprint = null; fingerprintError = null },
            onCopy = {
                val phrase = fingerprint
                if (phrase != null && showFingerprint && fingerprintSessionCurrent()) {
                    takagi.ru.monica.utils.ClipboardUtils.copyToClipboard(context, phrase,
                        label = context.getString(R.string.bitwarden_account_fingerprint), sensitive = true)
                }
            },
        )
    }
    val editing = configurationSession
    if (showManagement && appUnlocked && resumed) {
        BitwardenAccountManagementDialog(vaultId, onDismiss = { showManagement = false; reload++ },
            onReauthenticate = {
                showManagement = false
                scope.launch {
                    val selected = vault ?: return@launch
                    try {
                        val expected = SessionManager.lockGeneration.value
                        val draft = repository.loadAccountRecoveryEnvironment(vaultId)
                        repository.forceLock(vaultId)
                        if (SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == expected && resumed) {
                            configurationSession = AccountEnvironmentReauthentication(draft, selected.email,
                                requireNotNull(accountIdentity), expected, repository.getVaultSessionGeneration(vaultId), false)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = defaultError }
                }
            })
    }
    MonicaPageContent(pageKey = editing?.lockedGeneration, modifier = Modifier.fillMaxSize()) {
    if (editing != null) {
        key(editing) {
            BitwardenLoginScreen(viewModel,
                onNavigateBack = { discardConfiguration(); reload++ },
                onLoginSuccess = { configurationSession = null; configurationRequest++; reload++ },
                initialEnvironmentDraft = editing.draft, initialEmail = editing.email,
                existingAccount = true, openServerConfigurationOnStart = editing.editConfiguration,
                reauthentication = BitwardenRepository.ReauthenticationContext(vaultId, editing.lockedGeneration, editing.appGeneration))
        }
    } else {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.bitwarden_account_security)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = { reload++ }, enabled = !busy && unlocked) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } })
    }) { padding ->
        BitwardenAccountContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            displayName = vault?.displayName, email = vault?.email.orEmpty(), serverUrl = vault?.serverUrl.orEmpty(),
            devices = if (unlocked && resumed) devices else emptyList(), busy = busy && unlocked,
            error = if (unlocked) error else stringResource(R.string.legacy_ui_vault_locked),
            onFingerprint = { if (fingerprintSessionCurrent()) showFingerprint = true },
            fingerprintEnabled = unlocked && resumed,
            onServerConfiguration = ::prepareConfiguration,
            serverConfigurationEnabled = unlocked && resumed && !configurationLoading && !repository.isAccountMutationBlocked(vaultId),
            onNativeManagement = { showManagement = true },
            nativeManagementEnabled = appUnlocked && resumed && (unlocked || repository.isAccountMutationBlocked(vaultId)),
            webSettingsEnabled = unlocked && resumed,
            onWebSettings = {
                if (fingerprintSessionCurrent()) scope.launch {
                    try {
                        val draft = repository.loadAccountEnvironmentDraft(vaultId).getOrThrow()
                        if (!fingerprintSessionCurrent()) return@launch
                        val web = withContext(Dispatchers.IO) {
                            BitwardenEnvironmentResolver.resolve(draft, UUID.randomUUID().toString()).value?.web?.toString()
                        } ?: throw IllegalStateException("Invalid account environment")
                        if (!fingerprintSessionCurrent()) return@launch
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BitwardenAccountNavigation.webSettingsUrl(web))))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (fingerprintSessionCurrent()) error = defaultError }
                }
            }
        )
    }
    }
    }
}

@Composable
internal fun BitwardenAccountContent(
    displayName: String?, email: String, serverUrl: String,
    devices: List<BitwardenAccountDevice>, busy: Boolean, error: String?,
    onWebSettings: () -> Unit, modifier: Modifier = Modifier,
    onFingerprint: (() -> Unit)? = null, fingerprintEnabled: Boolean = false,
    onServerConfiguration: (() -> Unit)? = null, serverConfigurationEnabled: Boolean = false,
    webSettingsEnabled: Boolean = true,
    onNativeManagement: (() -> Unit)? = null, nativeManagementEnabled: Boolean = false,
) {
    LazyColumn(modifier.testTag("bitwarden_account_screen"),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Surface(shape = bitwardenGroupShape(), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayName?.takeIf { it.isNotBlank() } ?: email, style = MaterialTheme.typography.titleLarge)
                        if (!displayName.isNullOrBlank()) Text(email, style = MaterialTheme.typography.bodyMedium)
                        Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { BitwardenSectionTitle(stringResource(R.string.bitwarden_settings_account_management)) }
        if (onNativeManagement != null) item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_security), Icons.Outlined.ManageAccounts,
                first = true, last = false, onClick = onNativeManagement, enabled = nativeManagementEnabled,
                modifier = Modifier.testTag("bitwarden_account_native_management"))
        }
        item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_web_settings), Icons.Outlined.OpenInNew,
                supporting = stringResource(R.string.bitwarden_account_web_settings_hint),
                onClick = onWebSettings, enabled = webSettingsEnabled && serverUrl.isNotBlank(), first = onNativeManagement == null,
                last = onFingerprint == null && onServerConfiguration == null,
                modifier = Modifier.testTag("bitwarden_account_web_settings"))
        }
        if (onFingerprint != null) item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_fingerprint), Icons.Outlined.Fingerprint,
                supporting = stringResource(R.string.bitwarden_account_fingerprint_hint), first = false, last = onServerConfiguration == null,
                onClick = onFingerprint, enabled = fingerprintEnabled, modifier = Modifier.testTag("bitwarden_account_fingerprint"))
        }
        if (onServerConfiguration != null) item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_server_title), Icons.Outlined.Cloud,
                supporting = stringResource(R.string.bitwarden_server_reauthenticate_hint), first = false,
                onClick = onServerConfiguration, enabled = serverConfigurationEnabled,
                modifier = Modifier.testTag("bitwarden_account_server_configuration"))
        }
        item { BitwardenSectionTitle(stringResource(R.string.bitwarden_account_devices)) }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        error?.let { message -> item {
            BitwardenSettingRow(message, Icons.Outlined.ErrorOutline, destructive = true)
        } }
        if (!busy && error == null && devices.isEmpty()) item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_no_devices), Icons.Outlined.Devices)
        }
        items(devices.size, key = { devices[it].id }) { index ->
            val device = devices[index]
            val trusted = device.isTrusted?.let {
                stringResource(if (it) R.string.bitwarden_account_device_trusted else R.string.bitwarden_account_device_untrusted)
            }
            val created = device.creationDate?.let { stringResource(R.string.created_at) + ": " + it }
            val activity = device.lastActivityDate?.let { stringResource(R.string.bitwarden_account_last_activity) + ": " + it }
            BitwardenSettingRow(device.name.ifBlank { device.id }, Icons.Outlined.Devices,
                supporting = listOfNotNull(trusted, created, activity).joinToString("\n"), first = index == 0, last = index == devices.lastIndex,
                modifier = Modifier.testTag("bitwarden_account_device_${device.id}"))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

internal object BitwardenAccountNavigation {
    fun webSettingsUrl(serverUrl: String): String {
        val uri = URI(serverUrl)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        return serverUrl.trimEnd('/') + "/#/settings"
    }
}

/** UI-owned memory only. Closing the login drops this candidate; it never persists configuration. */
private class AccountEnvironmentReauthentication(
    val draft: BitwardenEnvironmentDraft,
    val email: String,
    val identity: String,
    val appGeneration: Long,
    val lockedGeneration: Long,
    val editConfiguration: Boolean = true,
) {
    override fun toString() = "AccountEnvironmentReauthentication(redacted)"
}
