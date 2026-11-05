package takagi.ru.monica.credentialexchange

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.providerevents.IntentHandler
import androidx.credentials.providerevents.exception.*
import androidx.credentials.providerevents.transfer.ImportCredentialsResponse
import androidx.credentials.providerevents.transfer.ProviderImportCredentialsRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.launchWithSecureStorage
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache
import takagi.ru.monica.utils.BiometricAuthenticationPolicy

/** System entry point. Request validation, fresh authentication, review, then one URI write. */
class CredentialExportActivity : FragmentActivity() {
    private val security by lazy { SecurityManager(this) }
    private val authorization = ExportAuthorization()
    private var request: ProviderImportCredentialsRequest? = null
    private var destination by mutableStateOf(ImportDestination.Local)
    private var prepared by mutableStateOf<CredentialExchangeExporter.Prepared?>(null)
    private var busy by mutableStateOf(false)
    private var showAuthentication by mutableStateOf(false)
    private var password by mutableStateOf("")
    private var passwordError by mutableStateOf(false)
    private var errorText by mutableStateOf<String?>(null)
    private var work: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val incoming = runCatching { IntentHandler.retrieveProviderImportCredentialsRequest(intent) }.getOrNull()
        val receiver = incoming?.let { TransferCallingAppVerifier.verifyExport(this, it) }
        if (incoming == null || receiver == null) {
            finishWithError(ImportCredentialsUnknownCallerException(getString(R.string.exchange_unverified_app)))
            return
        }
        request = incoming
        launchWithSecureStorage { showExport(incoming, receiver) }
    }

    private fun showExport(incoming: ProviderImportCredentialsRequest, receiver: String) {
        setContent {
            val settings by remember { SettingsManager(this) }.settingsFlow.collectAsState(initial = AppSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MonicaTheme(darkTheme = dark, oledPureBlackEnabled = settings.oledPureBlackEnabled,
                colorScheme = settings.colorScheme, customPrimaryColor = settings.customPrimaryColor,
                customSecondaryColor = settings.customSecondaryColor, customTertiaryColor = settings.customTertiaryColor,
                customNeutralColor = settings.customNeutralColor, customNeutralVariantColor = settings.customNeutralVariantColor) {
                ExportScreen(receiver, incoming.callingAppInfo.packageName, settings.biometricEnabled)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ExportScreen(receiver: String, packageName: String, biometricEnabled: Boolean) {
        BackHandler { cancel() }
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.exchange_export_title)) },
                navigationIcon = { IconButton(onClick = ::cancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) } }) },
            bottomBar = {
                Surface {
                    Button(onClick = {
                        if (prepared != null) deliver() else beginAuthentication()
                    }, enabled = !busy && (prepared == null || prepared!!.passwordCount + prepared!!.passkeyCount > 0),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).heightIn(min = 56.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Default.Send, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (prepared == null) R.string.exchange_export_verify else R.string.start_export))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SwapHoriz, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.exchange_export_request, receiver), style = MaterialTheme.typography.headlineSmall)
                        Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.exchange_export_help))
                    }
                }
                TransferDestinationField(destination, { destination = it; prepared = null; authorization.clear(); errorText = null },
                    enabled = !busy, forExport = true)
                prepared?.let {
                    TransferCredentialCounts(it.passwordCount, it.passkeyCount, it.skippedPasskeys, exporting = true)
                    if (it.skippedOtps > 0) Text(stringResource(R.string.exchange_export_otp_skipped, it.skippedOtps))
                    if (it.skippedSharedItems > 0) Text(stringResource(R.string.exchange_export_shared, it.skippedSharedItems))
                    if (it.passwordCount + it.passkeyCount == 0) Text(stringResource(R.string.exchange_empty))
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (showAuthentication) {
            val biometricAvailable = biometricEnabled && BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            M3IdentityVerifyDialog(
                title = getString(R.string.verify_identity), message = getString(R.string.exchange_export_help),
                icon = Icons.Default.Lock, passwordValue = password,
                onPasswordChange = { password = it; passwordError = false },
                onDismiss = { showAuthentication = false; password = "" },
                onConfirm = {
                    if (!busy) {
                        val candidate = password
                        password = ""
                        busy = true
                        work = lifecycleScope.launch {
                            val valid = try {
                                withContext(Dispatchers.IO) { security.unlockVaultWithPassword(candidate) }
                            } finally { busy = false }
                            if (valid) authenticated() else passwordError = true
                        }
                    }
                }, confirmText = getString(R.string.confirm), confirmEnabled = password.isNotEmpty() && !busy,
                destructiveConfirm = false, isPasswordError = passwordError,
                passwordErrorText = getString(R.string.current_password_incorrect),
                onBiometricClick = if (biometricAvailable && !busy) ({ beginBiometric() }) else null,
                showBiometricSlot = biometricAvailable,
            )
        }
    }

    private fun beginAuthentication() {
        errorText = null
        if (security.isMasterPasswordSet()) {
            showAuthentication = true
            return
        }
        errorText = getString(R.string.monica_master_password_required)
    }

    private fun beginBiometric() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (BiometricAuthenticationPolicy.acceptsResult(result.authenticationType, Build.VERSION.SDK_INT) &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && security.unlockVaultWithBiometric()) authenticated()
                else errorText = getString(R.string.exchange_failed)
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.verify_identity))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.cancel)).build())
    }

    private fun authenticated() {
        val incoming = request ?: return
        showAuthentication = false
        password = ""
        passwordError = false
        authorization.grant(destination.key, SystemClock.elapsedRealtime())
        val source = destination
        busy = true
        work = lifecycleScope.launch {
            try {
                prepared = CredentialExchangeExporter(this@CredentialExportActivity).prepare(source, incoming.request.credentialTypes)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { authorization.clear(); errorText = getString(R.string.exchange_failed) }
            finally { busy = false }
        }
    }

    private fun deliver() {
        val incoming = request ?: return
        val payload = prepared ?: return
        if (TransferCallingAppVerifier.verifyExport(this, incoming) == null ||
            !authorization.consume(destination.key, SystemClock.elapsedRealtime())) {
            prepared = null
            beginAuthentication()
            return
        }
        prepared = null
        busy = true
        work = lifecycleScope.launch {
            try {
                // Use a fresh result Intent: the registration secret is never returned to the receiver.
                val result = Intent()
                withContext(Dispatchers.IO) {
                    IntentHandler.setImportCredentialsResponse(this@CredentialExportActivity, incoming.uri,
                        result, ImportCredentialsResponse(payload.json))
                }
                setResult(RESULT_OK, result)
                finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finishWithError(ImportCredentialsUnknownErrorException(getString(R.string.exchange_failed))) }
        }
    }

    private fun cancel() = finishWithError(ImportCredentialsCancellationException())

    private fun finishWithError(error: ImportCredentialsException) {
        authorization.clear()
        prepared = null
        work?.cancel()
        val result = Intent()
        IntentHandler.setImportCredentialsException(result, error)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onStop() {
        authorization.clear()
        prepared = null
        password = ""
        showAuthentication = false
        work?.cancel()
        busy = false
        super.onStop()
    }
}
