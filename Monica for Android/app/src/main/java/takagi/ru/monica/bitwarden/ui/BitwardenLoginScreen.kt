package takagi.ru.monica.bitwarden.ui

import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import takagi.ru.monica.bitwarden.service.BitwardenTwoFactorPolicy
import androidx.compose.ui.platform.testTag
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import takagi.ru.monica.bitwarden.api.BitwardenApiFactory
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.service.BitwardenAuthService
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.rememberBringIntoViewOnFocusModifier
import takagi.ru.monica.viewmodel.ParsedTotpItem
import takagi.ru.monica.util.TotpGenerator

private enum class BitwardenServerPreset(@StringRes val label: Int) {
    US(R.string.legacy_ui_bitwarden_server_us),
    EU(R.string.legacy_ui_bitwarden_server_eu),
    SELF_HOSTED(R.string.legacy_ui_bitwarden_server_self_hosted)
}

/**
 * Bitwarden 登录界面
 * 
 * 支持：
 * - 官方服务器和自托管服务器
 * - 邮箱 + 主密码登录
 * - 两步验证（TOTP、Email、Authenticator 等）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenLoginScreen(
    viewModel: BitwardenViewModel,
    onNavigateBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    totpSuggestions: List<ParsedTotpItem> = emptyList()
) {
    val loginState by viewModel.loginState.collectAsState()
    DisposableEffect(viewModel) {
        onDispose { viewModel.resetLoginState(cancelInFlight = true) }
    }
    val sendingEmail by viewModel.sendingTwoFactorEmail.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    
    // 表单状态
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var selectedServerPresetName by rememberSaveable { mutableStateOf(BitwardenServerPreset.US.name) }
    var serverMenuExpanded by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var masterPassword by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvancedTls by rememberSaveable { mutableStateOf(false) }
    var tlsCertificateAlias by rememberSaveable { mutableStateOf("") }
    var tlsCaCertificatePem by rememberSaveable { mutableStateOf("") }
    var tlsMtlsEnabled by rememberSaveable { mutableStateOf(false) }
    var tlsClientCertPkcs12Base64 by rememberSaveable { mutableStateOf("") }
    var tlsClientCertPassword by rememberSaveable { mutableStateOf("") }
    var showClientCertPassword by rememberSaveable { mutableStateOf(false) }
    val selectedServerPreset = runCatching {
        BitwardenServerPreset.valueOf(selectedServerPresetName)
    }.getOrElse { BitwardenServerPreset.US }
    
    // 两步验证状态
    var showTwoFactorDialog by remember { mutableStateOf(false) }
    var showRegistration by remember { mutableStateOf(false) }
    var showPasswordHint by remember { mutableStateOf(false) }
    var showTotpPicker by remember { mutableStateOf(false) }
    var twoFactorCode by remember { mutableStateOf("") }
    var rememberTwoFactor by remember { mutableStateOf(false) }
    var selectedTwoFactorMethod by remember { mutableStateOf(0) }
    var availableTwoFactorMethods by remember { mutableStateOf<List<Int>>(emptyList()) }
    var twoFactorStatusMessage by remember { mutableStateOf<String?>(null) }
    var showCaptchaDialog by remember { mutableStateOf(false) }
    var captchaResponse by remember { mutableStateOf("") }
    var captchaMessage by remember { mutableStateOf(context.getString(R.string.legacy_ui_captcha_needed)) }
    var captchaForTwoFactor by remember { mutableStateOf(false) }
    var captchaSiteKey by remember { mutableStateOf<String?>(null) }
    var showCaptchaWebView by remember { mutableStateOf(false) }

    fun resolveServerUrlForLogin(): String? {
        return when (selectedServerPreset) {
            BitwardenServerPreset.US -> BitwardenApiFactory.OFFICIAL_VAULT_URL
            BitwardenServerPreset.EU -> BitwardenApiFactory.OFFICIAL_EU_VAULT_URL
            BitwardenServerPreset.SELF_HOSTED -> serverUrl.trim().takeIf { it.isNotBlank() }
        }
    }

    fun buildTlsConfigForLogin(): BitwardenTlsConfig? {
        if (selectedServerPreset != BitwardenServerPreset.SELF_HOSTED) {
            return null
        }
        val config = BitwardenTlsConfig(
            certificateAlias = tlsCertificateAlias.trim().takeIf { it.isNotBlank() },
            caCertificatePem = tlsCaCertificatePem.trim().takeIf { it.isNotBlank() },
            mtlsEnabled = tlsMtlsEnabled,
            clientCertPkcs12Base64 = tlsClientCertPkcs12Base64.trim().takeIf { it.isNotBlank() },
            clientCertPassword = tlsClientCertPassword.takeIf { it.isNotBlank() }
        )
        return if (config.isEmpty()) null else config
    }

    fun submitPrimaryLogin(captcha: String? = null) {
        rememberTwoFactor = false
        val normalizedEmail = email.trim()
        val resolvedServerUrl = resolveServerUrlForLogin()
        if (normalizedEmail.isBlank() || masterPassword.isBlank()) return
        if (selectedServerPreset == BitwardenServerPreset.SELF_HOSTED && resolvedServerUrl.isNullOrBlank()) return
        viewModel.login(
            resolvedServerUrl,
            normalizedEmail,
            masterPassword,
            captchaResponse = captcha,
            tlsConfig = buildTlsConfigForLogin()
        )
    }

    fun submitTwoFactorLogin(captcha: String? = null) {
        if (twoFactorCode.isBlank()) return
        showTwoFactorDialog = true
        viewModel.loginWithTwoFactor(
            twoFactorCode = twoFactorCode,
            twoFactorMethod = selectedTwoFactorMethod,
            captchaResponse = captcha,
            remember = rememberTwoFactor
        )
    }
    
    // 监听事件
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BitwardenViewModel.BitwardenEvent.ShowTwoFactorDialog -> {
                    availableTwoFactorMethods = event.methods
                    selectedTwoFactorMethod = event.methods.singleOrNull() ?: -1
                    twoFactorStatusMessage = null
                    showTwoFactorDialog = true
                }
                is BitwardenViewModel.BitwardenEvent.NavigateToVault -> {
                    showTwoFactorDialog = false
                    twoFactorCode = ""
                    rememberTwoFactor = false
                    onLoginSuccess()
                }
                is BitwardenViewModel.BitwardenEvent.ShowCaptchaDialog -> {
                    captchaForTwoFactor = event.forTwoFactor
                    if (event.forTwoFactor) showTwoFactorDialog = false
                    captchaMessage = event.message
                    captchaSiteKey = event.siteKey
                    showCaptchaDialog = true
                }
                is BitwardenViewModel.BitwardenEvent.ShowSuccess -> if (showTwoFactorDialog) {
                    twoFactorStatusMessage = event.message
                }
                is BitwardenViewModel.BitwardenEvent.ShowError -> if (showTwoFactorDialog) {
                    twoFactorStatusMessage = event.message
                }
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legacy_ui_bitwarden_login)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo 和标题
                Spacer(modifier = Modifier.height(24.dp))
                
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = stringResource(R.string.legacy_ui_bitwarden_connect),
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Text(
                    text = stringResource(R.string.legacy_ui_bitwarden_connect_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 邮箱输入
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text(stringResource(R.string.legacy_ui_email_address)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Email, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 主密码输入
                OutlinedTextField(
                    value = masterPassword,
                    onValueChange = { masterPassword = it },
                    label = { Text(stringResource(R.string.master_password)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrect = false,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            submitPrimaryLogin()
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = serverMenuExpanded,
                    onExpandedChange = { serverMenuExpanded = !serverMenuExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = stringResource(selectedServerPreset.label),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.legacy_ui_server)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = serverMenuExpanded,
                        onDismissRequest = { serverMenuExpanded = false }
                    ) {
                        BitwardenServerPreset.entries.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(stringResource(preset.label)) },
                                onClick = {
                                    selectedServerPresetName = preset.name
                                    serverMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showAdvancedTls = !showAdvancedTls },
                    enabled = selectedServerPreset == BitwardenServerPreset.SELF_HOSTED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showAdvancedTls) stringResource(R.string.legacy_ui_tls_collapse) else stringResource(R.string.legacy_ui_tls_settings))
                }

                if (selectedServerPreset != BitwardenServerPreset.SELF_HOSTED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.legacy_ui_tls_self_hosted_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                AnimatedVisibility(
                    visible = selectedServerPreset == BitwardenServerPreset.SELF_HOSTED,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it.trim() },
                            label = { Text(stringResource(R.string.legacy_ui_self_hosted_url)) },
                            placeholder = { Text("https://vault.example.com") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Cloud, contentDescription = null)
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        AnimatedVisibility(
                            visible = showAdvancedTls,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.legacy_ui_tls_advanced),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.legacy_ui_tls_default_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = tlsCertificateAlias,
                                        onValueChange = { tlsCertificateAlias = it },
                                        label = { Text(stringResource(R.string.legacy_ui_tls_certificate_alias)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    OutlinedTextField(
                                        value = tlsCaCertificatePem,
                                        onValueChange = { tlsCaCertificatePem = it },
                                        label = { Text(stringResource(R.string.legacy_ui_tls_ca_pem)) },
                                        placeholder = { Text("-----BEGIN CERTIFICATE-----") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 96.dp)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = tlsMtlsEnabled,
                                            onCheckedChange = { tlsMtlsEnabled = it }
                                        )
                                        Text(stringResource(R.string.legacy_ui_tls_enable_mtls))
                                    }

                                    AnimatedVisibility(visible = tlsMtlsEnabled) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = tlsClientCertPkcs12Base64,
                                                onValueChange = { tlsClientCertPkcs12Base64 = it },
                                                label = { Text(stringResource(R.string.legacy_ui_tls_client_certificate)) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 96.dp)
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))

                                            OutlinedTextField(
                                                value = tlsClientCertPassword,
                                                onValueChange = { tlsClientCertPassword = it },
                                                label = { Text(stringResource(R.string.legacy_ui_tls_client_password)) },
                                                trailingIcon = {
                                                    IconButton(onClick = { showClientCertPassword = !showClientCertPassword }) {
                                                        Icon(
                                                            if (showClientCertPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                            contentDescription = if (showClientCertPassword) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                                                        )
                                                    }
                                                },
                                                visualTransformation = if (showClientCertPassword) {
                                                    VisualTransformation.None
                                                } else {
                                                    PasswordVisualTransformation()
                                                },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 登录按钮
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        submitPrimaryLogin()
                    },
                    enabled = loginState !is BitwardenViewModel.LoginState.Loading 
                            && email.trim().isNotBlank() 
                            && masterPassword.isNotBlank()
                            && (selectedServerPreset != BitwardenServerPreset.SELF_HOSTED || serverUrl.trim().isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (loginState is BitwardenViewModel.LoginState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.legacy_ui_login))
                    }
                }
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showRegistration = true }, enabled = loginState !is BitwardenViewModel.LoginState.Loading && resolveServerUrlForLogin() != null,
                        modifier = Modifier.weight(1f).testTag("bitwarden_create_account")) {
                        Text(stringResource(R.string.bitwarden_account_create))
                    }
                    TextButton(onClick = { showPasswordHint = true }, enabled = loginState !is BitwardenViewModel.LoginState.Loading && resolveServerUrlForLogin() != null,
                        modifier = Modifier.weight(1f).testTag("bitwarden_request_password_hint")) {
                        Text(stringResource(R.string.bitwarden_account_request_hint))
                    }
                }

                // 错误信息
                if (loginState is BitwardenViewModel.LoginState.Error) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = (loginState as BitwardenViewModel.LoginState.Error).message,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 安全提示
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.legacy_ui_security_explanation),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.legacy_ui_bitwarden_security_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(64.dp))
            }
        }
    }
    
    // 两步验证对话框
    if (showRegistration) {
        BitwardenRegistrationDialog(viewModel, resolveServerUrlForLogin().orEmpty(), email, buildTlsConfigForLogin(),
            onRegistered = { email = it; showRegistration = false }, onDismiss = { showRegistration = false })
    }
    if (showPasswordHint) {
        BitwardenPasswordHintDialog(viewModel, resolveServerUrlForLogin().orEmpty(), email, buildTlsConfigForLogin(),
            onDismiss = { showPasswordHint = false })
    }
    if (showTwoFactorDialog && !showTotpPicker) {
        TwoFactorDialog(
            availableMethods = availableTwoFactorMethods,
            selectedMethod = selectedTwoFactorMethod,
            onMethodSelected = {
                selectedTwoFactorMethod = it
                twoFactorCode = ""
                twoFactorStatusMessage = null
            },
            code = twoFactorCode,
            onCodeChange = { twoFactorCode = it },
            rememberTwoFactor = rememberTwoFactor,
            onRememberTwoFactorChange = { rememberTwoFactor = it },
            onPickFromMonica = if (selectedTwoFactorMethod == BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR) {
                { showTotpPicker = true }
            } else null,
            statusMessage = twoFactorStatusMessage,
            onSendEmailCode = {
                twoFactorStatusMessage = context.getString(R.string.legacy_ui_email_code_requesting)
                viewModel.sendTwoFactorEmailLogin()
            },
            busy = loginState is BitwardenViewModel.LoginState.Loading,
            sendingEmail = sendingEmail,
            onConfirm = {
                submitTwoFactorLogin()
            },
            onDismiss = {
                showTwoFactorDialog = false
                twoFactorCode = ""
                rememberTwoFactor = false
                viewModel.resetLoginState()
            }
        )
    }

    if (showCaptchaDialog) {
        AlertDialog(
            onDismissRequest = {
                showCaptchaDialog = false
                captchaResponse = ""
            },
            icon = {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.legacy_ui_captcha_required_title)) },
            text = {
                Column {
                    Text(
                        text = captchaMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!captchaSiteKey.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { showCaptchaWebView = true }
                        ) {
                            Text(stringResource(R.string.legacy_ui_captcha_auto))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = captchaResponse,
                        onValueChange = { captchaResponse = it },
                        label = { Text(stringResource(R.string.legacy_ui_captcha_response)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (captchaResponse.isNotBlank()) {
                                    if (captchaForTwoFactor) {
                                        submitTwoFactorLogin(captchaResponse)
                                    } else {
                                        submitPrimaryLogin(captchaResponse)
                                    }
                                    showCaptchaDialog = false
                                    captchaResponse = ""
                                }
                            }
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (captchaForTwoFactor) {
                            submitTwoFactorLogin(captchaResponse)
                        } else {
                            submitPrimaryLogin(captchaResponse)
                        }
                        showCaptchaDialog = false
                        captchaResponse = ""
                    },
                    enabled = captchaResponse.isNotBlank()
                ) {
                    Text(stringResource(R.string.legacy_ui_submit))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCaptchaDialog = false
                        captchaResponse = ""
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showCaptchaWebView && !captchaSiteKey.isNullOrBlank()) {
        val effectiveVaultUrl = resolveServerUrlForLogin()
            ?: BitwardenApiFactory.OFFICIAL_VAULT_URL
        CaptchaWebViewDialog(
            siteKey = captchaSiteKey!!,
            baseUrl = effectiveVaultUrl,
            onToken = { token ->
                captchaResponse = token
                showCaptchaWebView = false
                showCaptchaDialog = false
                if (captchaForTwoFactor) {
                    submitTwoFactorLogin(token)
                } else {
                    submitPrimaryLogin(token)
                }
            },
            onError = { message ->
                captchaMessage = context.getString(R.string.legacy_ui_captcha_auto_failed, message)
                showCaptchaWebView = false
            },
            onDismiss = { showCaptchaWebView = false }
        )
    }

    if (showTotpPicker) {
        BitwardenTotpPickerDialog(
            suggestions = totpSuggestions,
            onCodeSelected = { twoFactorCode = it; showTotpPicker = false },
            onDismiss = { showTotpPicker = false },
        )
    }

}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaWebViewDialog(
    siteKey: String,
    baseUrl: String,
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legacy_ui_captcha_verification)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val bridge = object {
                            @JavascriptInterface
                            fun onToken(token: String?) {
                                val value = token?.trim().orEmpty()
                                if (value.isNotBlank()) {
                                    onToken(value)
                                } else {
                                    onError(context.getString(R.string.legacy_ui_captcha_empty_token))
                                }
                            }

                            @JavascriptInterface
                            fun onError(error: String?) {
                                onError(error ?: context.getString(R.string.legacy_ui_captcha_unknown_error))
                            }
                        }

                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            addJavascriptInterface(bridge, "CaptchaBridge")

                            val html = """
                                <!doctype html>
                                <html>
                                <head>
                                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                                  <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
                                </head>
                                <body style="margin:0;padding:16px;font-family:sans-serif;">
                                  <div class="h-captcha"
                                       data-sitekey="$siteKey"
                                       data-callback="onCaptchaSuccess"
                                       data-error-callback="onCaptchaError"></div>
                                  <script>
                                    function onCaptchaSuccess(token) {
                                      CaptchaBridge.onToken(token);
                                    }
                                    function onCaptchaError() {
                                      CaptchaBridge.onError("widget error");
                                    }
                                  </script>
                                </body>
                                </html>
                            """.trimIndent()

                            loadDataWithBaseURL(
                                if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
                                html,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

/**
 * 两步验证对话框
 */
@Composable
fun TwoFactorDialog(
    availableMethods: List<Int>,
    selectedMethod: Int,
    onMethodSelected: (Int) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    onPickFromMonica: (() -> Unit)? = null,
    statusMessage: String?,
    onSendEmailCode: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    sendingEmail: Boolean = false,
    rememberTwoFactor: Boolean = false,
    onRememberTwoFactorChange: (Boolean) -> Unit = {},
) {
    val choosing = selectedMethod !in availableMethods
    val supported = !choosing && BitwardenTwoFactorPolicy.supportsCode(selectedMethod)
    val working = busy || sendingEmail
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        modifier = Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .85f).dp)
            .testTag("bitwarden_two_factor_dialog"),
        title = { Text(if (choosing) stringResource(R.string.bitwarden_choose_method) else getTwoFactorMethodName(selectedMethod)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (choosing) {
                    val methods = availableMethods.filter { BitwardenTwoFactorPolicy.supportsCode(it) }
                    if (methods.isEmpty()) Text(stringResource(R.string.bitwarden_two_factor_unsupported),
                        style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        methods.forEachIndexed { index, method ->
                            val enabled = BitwardenTwoFactorPolicy.supportsCode(method)
                            Surface(onClick = { onMethodSelected(method) }, enabled = enabled && !working,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                    topStart = if (index == 0) 20.dp else 4.dp, topEnd = if (index == 0) 20.dp else 4.dp,
                                    bottomStart = if (index == methods.lastIndex) 20.dp else 4.dp,
                                    bottomEnd = if (index == methods.lastIndex) 20.dp else 4.dp),
                                modifier = Modifier.fillMaxWidth().testTag("bitwarden_two_factor_method_$method")) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(getTwoFactorMethodName(method), style = MaterialTheme.typography.titleSmall)
                                        if (!enabled) Text(stringResource(R.string.bitwarden_two_factor_unsupported_short),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (enabled) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                } else {
                    if (availableMethods.count { BitwardenTwoFactorPolicy.supportsCode(it) } > 1) TextButton(onClick = { onMethodSelected(-1) }, enabled = !working,
                        modifier = Modifier.testTag("bitwarden_two_factor_change")) {
                        Text(stringResource(R.string.bitwarden_change_method))
                    }
                    Text(when {
                        !supported -> stringResource(R.string.bitwarden_two_factor_unsupported)
                        selectedMethod == BitwardenAuthService.TWO_FACTOR_EMAIL -> stringResource(R.string.bitwarden_email_action_hint)
                        selectedMethod == BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR -> stringResource(R.string.bitwarden_totp_action_hint)
                        else -> getTwoFactorInputGuide(selectedMethod)
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (selectedMethod == BitwardenAuthService.TWO_FACTOR_EMAIL) {
                        FilledTonalButton(onClick = onSendEmailCode, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_two_factor_send_email")) {
                            if (sendingEmail) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Email, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (sendingEmail) R.string.legacy_ui_email_code_requesting else R.string.legacy_ui_email_code_send))
                        }
                    }
                    if (selectedMethod == BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR && onPickFromMonica != null) {
                        FilledTonalButton(onClick = onPickFromMonica, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_two_factor_pick_totp")) {
                            Icon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.legacy_ui_totp_from_monica))
                        }
                    }
                    if (!statusMessage.isNullOrBlank()) Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (supported) OutlinedTextField(
                        value = code, onValueChange = onCodeChange, enabled = !working,
                        label = { Text(getTwoFactorFieldLabel(selectedMethod)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isNumericTwoFactorCode(selectedMethod)) KeyboardType.Number else KeyboardType.Text,
                            imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!working && code.isNotBlank()) onConfirm() }),
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bitwarden_two_factor_code"))
                    if (supported && selectedMethod != BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = rememberTwoFactor, onCheckedChange = onRememberTwoFactorChange,
                                enabled = !working, modifier = Modifier.testTag("bitwarden_two_factor_remember"))
                            Text(stringResource(R.string.bitwarden_remember_two_factor),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!choosing && supported) Button(onClick = onConfirm, enabled = !working && code.isNotBlank(),
                modifier = Modifier.testTag("bitwarden_two_factor_submit")) { Text(stringResource(R.string.legacy_ui_verify)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.cancel)) } },
    )
}


/**
 * 获取两步验证方式名称
 */
@Composable
private fun getTwoFactorMethodName(method: Int): String {
    return when (method) {
        0 -> stringResource(R.string.legacy_ui_two_factor_authenticator)
        1 -> stringResource(R.string.legacy_ui_two_factor_email_code)
        2 -> "Duo Security"
        3 -> "YubiKey"
        4 -> stringResource(R.string.legacy_ui_two_factor_u2f)
        5 -> stringResource(R.string.legacy_ui_two_factor_remember_device)
        6 -> stringResource(R.string.legacy_ui_two_factor_org_duo)
        7 -> "WebAuthn"
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE -> stringResource(R.string.legacy_ui_two_factor_new_device)
        else -> stringResource(R.string.legacy_ui_two_factor_unknown)
    }
}

@Composable
private fun getTwoFactorMethodHint(method: Int): String {
    return when (method) {
        BitwardenAuthService.TWO_FACTOR_EMAIL ->
            stringResource(R.string.legacy_ui_two_factor_email_hint)
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR ->
            stringResource(R.string.legacy_ui_two_factor_totp_hint)
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE ->
            stringResource(R.string.legacy_ui_two_factor_new_device_hint)
        else -> ""
    }
}

@Composable
private fun getTwoFactorInputGuide(method: Int): String {
    return when (method) {
        BitwardenAuthService.TWO_FACTOR_EMAIL ->
            stringResource(R.string.legacy_ui_two_factor_email_guide)
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR ->
            stringResource(R.string.legacy_ui_two_factor_totp_guide)
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE ->
            stringResource(R.string.legacy_ui_two_factor_new_device_guide)
        else ->
            stringResource(R.string.legacy_ui_two_factor_generic_guide)
    }
}

@Composable
private fun getTwoFactorFieldLabel(method: Int): String {
    return when (method) {
        BitwardenAuthService.TWO_FACTOR_EMAIL -> stringResource(R.string.legacy_ui_two_factor_email_code)
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR -> stringResource(R.string.legacy_ui_two_factor_totp_label)
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE -> stringResource(R.string.legacy_ui_two_factor_new_device_label)
        else -> stringResource(R.string.legacy_ui_verification_code)
    }
}

@Composable
private fun getTwoFactorFieldPlaceholder(method: Int): String {
    return when (method) {
        BitwardenAuthService.TWO_FACTOR_EMAIL ->
            stringResource(R.string.legacy_ui_two_factor_email_placeholder)
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR ->
            stringResource(R.string.legacy_ui_two_factor_totp_placeholder)
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE ->
            stringResource(R.string.legacy_ui_two_factor_new_device_placeholder)
        else -> stringResource(R.string.legacy_ui_verification_code_placeholder)
    }
}

private fun isNumericTwoFactorCode(method: Int): Boolean {
    return method == BitwardenAuthService.TWO_FACTOR_EMAIL ||
        method == BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR ||
        method == BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE
}
