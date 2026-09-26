package takagi.ru.monica.ui.screens

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import takagi.ru.monica.R
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.AuthenticatorCardDisplayField
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordCardDisplayField
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.ui.main.navigation.SteamDockIcon
import takagi.ru.monica.data.UnifiedProgressBarMode
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.password.PasswordEntryCard as PasswordEntryCardV2
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.utils.BiometricAuthHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import takagi.ru.monica.data.QuickSetupPreset
import takagi.ru.monica.data.VaultV2LayoutMode
import takagi.ru.monica.data.quickSetupVisibleTabs
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.BottomNavConfigRow
import takagi.ru.monica.ui.components.MonicaExpansionChevron
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import takagi.ru.monica.data.quickSetupTabOrder
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class QuickSetupStep(val titleRes: Int, val subtitleRes: Int) {
    WELCOME(R.string.qs_step_welcome_title, R.string.qs_welcome_description),
    PRESET(R.string.qs_step_presets_title, R.string.qs_step_presets_subtitle),
    BOTTOM_NAV(R.string.qs_step_bottom_nav_title, R.string.qs_dock_subtitle),
    CUSTOMIZE(R.string.qs_step_customize_title, R.string.qs_step_customize_subtitle),
    SECURITY(R.string.qs_step_protect_title, R.string.qs_step_protect_subtitle),
    DATA_IMPORT(R.string.qs_step_data_import_title, R.string.qs_step_data_import_subtitle),
    READY(R.string.qs_ready_title, R.string.qs_ready_subtitle),
}

@Composable
fun QuickSetupScreen(
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onOpenMasterPassword: () -> Unit,
    onOpenSecurityQuestions: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onOpenBitwardenSettings: () -> Unit,
    onOpenWebDavBackup: () -> Unit,
    onOpenLocalKeePass: () -> Unit,
    onOpenImportData: (fromApps: Boolean) -> Unit,
    onOpenMonicaPlus: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsState()
    val steps = QuickSetupStep.entries
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingPresetName by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dockDragging by remember { mutableStateOf(false) }
    val pendingPreset = pendingPresetName?.let { name -> QuickSetupPreset.entries.firstOrNull { it.name == name } }
    val step = steps[stepIndex.coerceIn(steps.indices)]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val stepState = rememberSaveableStateHolder()

    fun saveAndContinue(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.qs_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    fun finish(onCompleted: () -> Unit) = saveAndContinue {
        settingsViewModel.completeQuickSetup()
        onCompleted()
    }

    BackHandler(enabled = stepIndex > 0 || saving || dockDragging) {
        if (!saving && !dockDragging) stepIndex -= 1
    }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.qs_step_counter, stepIndex + 1, steps.size),
                        Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { finish(onSkip) }, enabled = !saving && !dockDragging) {
                        Text(stringResource(R.string.qs_skip))
                    }
                }
                LinearProgressIndicator(
                    progress = { (stepIndex + 1f) / steps.size },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        },
        bottomBar = {
            QuickSetupBottomBar(
                primaryText = stringResource(when {
                    step == QuickSetupStep.WELCOME -> R.string.qs_start
                    step == QuickSetupStep.PRESET && pendingPreset != null -> R.string.qs_use_preset
                    step == QuickSetupStep.READY -> R.string.qs_enter_monica
                    else -> R.string.qs_next
                }),
                saving = saving,
                enabled = !dockDragging,
                onBack = if (stepIndex > 0) ({ stepIndex -= 1 }) else null,
                onNext = {
                    when {
                        step == QuickSetupStep.READY -> finish(onFinish)
                        step == QuickSetupStep.PRESET && pendingPreset != null -> saveAndContinue {
                            settingsViewModel.applyQuickSetupPreset(pendingPreset)
                            // Returning here keeps all subsequent edits until a preset is explicitly chosen again.
                            pendingPresetName = null
                            stepIndex += 1
                        }
                        else -> stepIndex += 1
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            label = "quick_setup_step",
            transitionSpec = {
                if (settings.reduceAnimations) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(220)) { it * direction / 8 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(180)) { -it * direction / 8 } + fadeOut(tween(140)))
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { targetStep ->
            stepState.SaveableStateProvider(targetStep.name) {
                if (targetStep == QuickSetupStep.BOTTOM_NAV) {
                    BottomNavStep(
                        settings = settings, enabled = !saving,
                        onDraggingChanged = { dockDragging = it },
                        onTabsChange = { order, tabs ->
                            saveAndContinue { settingsViewModel.updateQuickSetupNavigation(order, tabs) }
                        },
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().testTag("quick_setup_${targetStep.name}")
                            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (targetStep != QuickSetupStep.WELCOME) {
                            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(targetStep.titleRes), style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold)
                                Text(stringResource(targetStep.subtitleRes), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when (targetStep) {
                            QuickSetupStep.WELCOME -> WelcomeStep(settings.language, settingsViewModel::updateLanguage)
                            QuickSetupStep.PRESET -> QuickSetupPresetStep(
                                settings, pendingPreset, !saving,
                                onPresetSelected = { pendingPresetName = it?.name },
                            )
                            QuickSetupStep.BOTTOM_NAV -> Unit
                            QuickSetupStep.CUSTOMIZE -> PageAdjustmentsStep(settings, settingsViewModel)
                            QuickSetupStep.SECURITY -> {
                                SecurityStep(
                                    biometricEnabled = settings.biometricEnabled,
                                    masterPasswordSet = securityManager.isMasterPasswordSet(),
                                    securityQuestionsSet = securityManager.areSecurityQuestionsSet(),
                                    onBiometricChange = settingsViewModel::updateBiometricEnabled,
                                    onOpenMasterPassword = onOpenMasterPassword,
                                    onOpenSecurityQuestions = onOpenSecurityQuestions,
                                )
                                AutofillStep(onOpenAutofillSettings)
                            }
                            QuickSetupStep.DATA_IMPORT -> DataImportStep(
                                onOpenBitwardenSettings, onOpenWebDavBackup, onOpenLocalKeePass, onOpenImportData,
                            )
                            QuickSetupStep.READY -> {
                                SetupSection(stringResource(R.string.qs_bottom_preview), Icons.Default.Widgets) {
                                    Text(
                                        stringResource(QuickSetupPreset.entries.firstOrNull { it.matches(settings) }
                                            ?.titleRes() ?: R.string.qs_current_layout),
                                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    )
                                    MonicaBottomNavPreview(settings.quickSetupVisibleTabs())
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    SetupActionCard(Icons.Default.DashboardCustomize,
                                        stringResource(R.string.qs_step_bottom_nav_title), stringResource(R.string.qs_dock_subtitle),
                                        stringResource(R.string.qs_change), { stepIndex = QuickSetupStep.BOTTOM_NAV.ordinal }, 0, 3)
                                    SetupActionCard(Icons.Default.Shield,
                                        stringResource(R.string.qs_step_protect_title),
                                        stringResource(if (securityManager.isMasterPasswordSet()) R.string.qs_master_password_set else R.string.qs_master_password_unset),
                                        stringResource(R.string.qs_change), { stepIndex = QuickSetupStep.SECURITY.ordinal }, 1, 3)
                                    SetupActionCard(Icons.Default.UploadFile,
                                        stringResource(R.string.qs_step_data_import_title), stringResource(R.string.qs_data_optional),
                                        stringResource(R.string.qs_go_import), { stepIndex = QuickSetupStep.DATA_IMPORT.ordinal }, 2, 3)
                                }
                                SetupExpandableGroup(stringResource(R.string.qs_step_monica_plus_title), Icons.Default.AutoAwesome) {
                                    MonicaPlusStep(settings.isPlusActivated, onOpenMonicaPlus)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(selectedLanguage: Language, onLanguageSelected: (Language) -> Unit) {
    var languageExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Shield, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.qs_welcome_heading), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(stringResource(R.string.qs_welcome_description), Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SetupActionCard(
        Icons.Default.Language, stringResource(languageLabelRes(selectedLanguage)),
        stringResource(R.string.qs_language_selection), stringResource(R.string.qs_change),
        onClick = { languageExpanded = true },
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(12.dp)) {
        listOf(
            Icons.Default.DashboardCustomize to R.string.qs_step_presets_title,
            Icons.Default.Shield to R.string.qs_step_protect_title,
            Icons.Default.UploadFile to R.string.qs_step_data_import_title,
        ).forEach { (icon, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconSurface(icon)
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (languageExpanded) {
        LanguageSelectionDialog(selectedLanguage, { language ->
            languageExpanded = false
            if (language != selectedLanguage) onLanguageSelected(language)
        }, { languageExpanded = false })
    }
}

@Composable
internal fun QuickSetupPresetStep(
    settings: AppSettings,
    selectedPreset: QuickSetupPreset?,
    enabled: Boolean,
    onPresetSelected: (QuickSetupPreset?) -> Unit,
) {
    val previewSettings = selectedPreset?.applyTo(settings) ?: settings
    SetupSection(stringResource(R.string.qs_bottom_preview), Icons.Default.Widgets) {
        Text(stringResource(selectedPreset?.titleRes() ?: R.string.qs_current_layout),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        MonicaBottomNavPreview(previewSettings.quickSetupVisibleTabs())
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val options = QuickSetupPreset.entries + listOf(null)
        options.forEachIndexed { index, preset ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = settingsSectionItemShape(index, options.size),
                color = if (preset == selectedPreset) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("quick_setup_preset_${preset?.name ?: "CURRENT"}")
                        .selectable(selected = preset == selectedPreset, enabled = enabled, role = Role.RadioButton,
                            onClick = { onPresetSelected(preset) })
                        .heightIn(min = 88.dp).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(preset?.icon() ?: Icons.Default.SettingsSuggest, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(preset?.titleRes() ?: R.string.qs_keep_layout),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(preset?.descriptionRes() ?: R.string.qs_keep_layout_desc),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = preset == selectedPreset, enabled = enabled, onClick = null)
                }
            }
        }
    }
    Text(stringResource(R.string.qs_presets_hint), Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun QuickSetupPreset.titleRes(): Int = when (this) {
    QuickSetupPreset.BITWARDEN_PREVIEW -> R.string.qs_preset_bitwarden_preview
    QuickSetupPreset.BITWARDEN_LIST -> R.string.qs_preset_bitwarden_list
    QuickSetupPreset.AUTHENTICATOR -> R.string.qs_preset_authenticator
    QuickSetupPreset.PAGED -> R.string.qs_preset_paged
    QuickSetupPreset.EVERYDAY -> R.string.qs_preset_everyday
    QuickSetupPreset.MINIMAL -> R.string.qs_preset_minimal
}

private fun QuickSetupPreset.descriptionRes(): Int = when (this) {
    QuickSetupPreset.BITWARDEN_PREVIEW -> R.string.qs_preset_bitwarden_preview_desc
    QuickSetupPreset.BITWARDEN_LIST -> R.string.qs_preset_bitwarden_list_desc
    QuickSetupPreset.AUTHENTICATOR -> R.string.qs_preset_authenticator_desc
    QuickSetupPreset.PAGED -> R.string.qs_preset_paged_desc
    QuickSetupPreset.EVERYDAY -> R.string.qs_preset_everyday_desc
    QuickSetupPreset.MINIMAL -> R.string.qs_preset_minimal_desc
}

private fun QuickSetupPreset.icon(): ImageVector = when (this) {
    QuickSetupPreset.BITWARDEN_PREVIEW -> Icons.Default.Home
    QuickSetupPreset.BITWARDEN_LIST -> Icons.Default.ViewList
    QuickSetupPreset.AUTHENTICATOR -> Icons.Default.Security
    QuickSetupPreset.PAGED -> Icons.Default.DashboardCustomize
    QuickSetupPreset.EVERYDAY -> Icons.Default.AutoAwesome
    QuickSetupPreset.MINIMAL -> Icons.Default.Lock
}

@Composable
private fun SecurityStep(
    biometricEnabled: Boolean,
    masterPasswordSet: Boolean,
    securityQuestionsSet: Boolean,
    onBiometricChange: (Boolean) -> Unit,
    onOpenMasterPassword: () -> Unit,
    onOpenSecurityQuestions: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricHelper = remember(context) { BiometricAuthHelper(context) }
    val onBiometricChangeLatest by rememberUpdatedState(onBiometricChange)
    var biometricAuthPending by remember { mutableStateOf(false) }
    var biometricAvailable by remember(biometricHelper) {
        mutableStateOf(biometricHelper.isBiometricAvailable())
    }
    var biometricStatusMessage by remember(biometricHelper) {
        mutableStateOf(biometricHelper.getBiometricStatusMessage())
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        biometricAvailable = biometricHelper.isBiometricAvailable()
        biometricStatusMessage = biometricHelper.getBiometricStatusMessage()
    }
    DisposableEffect(biometricHelper) {
        onDispose {
            if (biometricAuthPending) {
                biometricAuthPending = false
                biometricHelper.cancelAuthentication()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SetupActionCard(
            icon = Icons.Default.Password,
            title = stringResource(R.string.qs_master_password),
            description = stringResource(if (masterPasswordSet) R.string.qs_master_password_set else R.string.qs_master_password_unset),
            badge = stringResource(if (masterPasswordSet) R.string.qs_completed else R.string.qs_go_setup),
            onClick = onOpenMasterPassword, groupIndex = 0, groupSize = 3
        )
        SetupSwitchCard(
            icon = Icons.Default.Fingerprint,
            title = stringResource(R.string.qs_biometric),
            description = if (biometricAvailable) {
                stringResource(R.string.qs_biometric_desc)
            } else {
                biometricStatusMessage
            },
            checked = biometricEnabled, groupIndex = 1, groupSize = 3,
            enabled = !biometricAuthPending &&
                (biometricEnabled || (biometricAvailable && activity != null)),
            onCheckedChange = biometricChange@{ enabled ->
                if (biometricAuthPending) return@biometricChange
                if (!enabled) {
                    onBiometricChangeLatest(false)
                    return@biometricChange
                }
                if (activity == null || !biometricHelper.isBiometricAvailable()) {
                    biometricAvailable = biometricHelper.isBiometricAvailable()
                    biometricStatusMessage = biometricHelper.getBiometricStatusMessage()
                    return@biometricChange
                }

                // Persist the opt-in only after Android confirms the user's identity.
                biometricAuthPending = true
                try {
                    biometricHelper.authenticate(
                        activity = activity,
                        title = context.getString(R.string.biometric_unlock),
                        subtitle = context.getString(R.string.biometric_login_subtitle),
                        description = context.getString(R.string.qs_biometric_desc),
                        negativeButtonText = context.getString(R.string.cancel),
                        onSuccess = {
                            if (biometricAuthPending) {
                                biometricAuthPending = false
                                onBiometricChangeLatest(true)
                            }
                        },
                        onError = { _, message ->
                            if (biometricAuthPending) {
                                biometricAuthPending = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.biometric_auth_error, message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onCancel = { biometricAuthPending = false }
                    )
                } catch (_: Exception) {
                    biometricAuthPending = false
                    biometricHelper.cancelAuthentication()
                    Toast.makeText(context, R.string.biometric_cannot_enable, Toast.LENGTH_SHORT).show()
                }
            }
        )
        SetupActionCard(
            icon = Icons.Default.QuestionAnswer,
            title = stringResource(R.string.qs_security_questions),
            description = stringResource(if (securityQuestionsSet) R.string.qs_security_questions_set else R.string.qs_security_questions_unset),
            badge = stringResource(if (securityQuestionsSet) R.string.qs_completed else R.string.qs_go_setup),
            onClick = onOpenSecurityQuestions, groupIndex = 2, groupSize = 3
        )
    }
}

@Composable
private fun AutofillStep(onOpenAutofillSettings: () -> Unit) {
    SetupActionCard(Icons.Default.Security, stringResource(R.string.qs_autofill_enable),
        stringResource(R.string.qs_autofill_enable_desc), stringResource(R.string.qs_open_autofill_settings), onOpenAutofillSettings)
}

@Composable
private fun AppearanceStep(
    selectedScheme: ColorScheme,
    onSchemeSelected: (ColorScheme) -> Unit
) {
    val recommended = listOf(
        ColorScheme.DEFAULT,
        ColorScheme.OCEAN_BLUE,
        ColorScheme.FOREST_GREEN,
        ColorScheme.SUNSET_ORANGE,
        ColorScheme.GREY_STYLE,
        ColorScheme.BLACK_MAMBA
    )
    SetupSection(title = stringResource(R.string.qs_color_scheme), icon = Icons.Default.Palette) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            recommended.forEach { scheme ->
                ColorSchemeRow(
                    scheme = scheme,
                    selected = selectedScheme == scheme,
                    onClick = { onSchemeSelected(scheme) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavStep(
    settings: AppSettings,
    enabled: Boolean,
    onDraggingChanged: (Boolean) -> Unit,
    onTabsChange: (List<BottomNavContentTab>, List<BottomNavContentTab>) -> Unit,
) {
    val listState = rememberLazyListState()
    var order by remember(settings.bottomNavOrder) { mutableStateOf(settings.quickSetupTabOrder()) }
    val visible = settings.quickSetupVisibleTabs().toSet()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        // Header/footer positions are not tab positions. Stable keys also handle hidden pages.
        val fromIndex = order.indexOfFirst { it.name == from.key }
        val toIndex = order.indexOfFirst { it.name == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            order = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
    LaunchedEffect(enabled) {
        // Restore the persisted order after a failed save as well as after a successful one.
        if (enabled) order = settings.quickSetupTabOrder()
    }
    DisposableEffect(Unit) { onDispose { onDraggingChanged(false) } }

    fun saveOrder() {
        onDraggingChanged(false)
        if (order != settings.quickSetupTabOrder()) onTabsChange(order, order.filter { it in visible })
    }
    val moveUpLabel = stringResource(R.string.qs_move_up)
    val moveDownLabel = stringResource(R.string.qs_move_down)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("quick_setup_BOTTOM_NAV"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = !reorderableState.isAnyItemDragging,
    ) {
        item(key = "dock_header") {
            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.qs_step_bottom_nav_title), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.bottom_nav_reorder_hint), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MonicaBottomNavPreview(order.filter { it in visible })
            }
        }
        itemsIndexed(order, key = { _, tab -> tab.name }) { index, tab ->
            val shown = tab in visible
            val canToggle = enabled && !reorderableState.isAnyItemDragging && (!shown || visible.size > 1)
            ReorderableItem(reorderableState, key = tab.name, enabled = enabled) { dragging ->
                val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "setup_dock_drag_elevation")
                val title = stringResource(tabLabelRes(tab))
                BottomNavConfigRow(
                    icon = tabIcon(tab),
                    title = title,
                    subtitle = stringResource(if (shown) R.string.bottom_nav_toggle_subtitle else R.string.hidden),
                    checked = shown,
                    switchEnabled = canToggle,
                    onCheckedChange = { checked ->
                        val nextVisible = if (checked) visible + tab else visible - tab
                        onTabsChange(order, order.filter { it in nextVisible })
                    },
                    dragHandleModifier = Modifier.testTag("quick_setup_drag_${tab.name}")
                        .longPressDraggableHandle(
                            enabled = enabled,
                            onDragStarted = { onDraggingChanged(true) },
                            onDragStopped = { saveOrder() },
                        ),
                    modifier = Modifier.shadow(elevation, MaterialTheme.shapes.medium),
                    switchModifier = Modifier.testTag("quick_setup_toggle_${tab.name}").semantics {
                        contentDescription = title
                        customActions = if (enabled && !reorderableState.isAnyItemDragging) buildList {
                            if (index > 0) add(CustomAccessibilityAction(moveUpLabel) {
                                order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                saveOrder(); true
                            })
                            if (index < order.lastIndex) add(CustomAccessibilityAction(moveDownLabel) {
                                order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                saveOrder(); true
                            })
                        } else emptyList()
                    },
                )
            }
        }
        item(key = "dock_settings") {
            BottomNavConfigRow(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.nav_settings),
                subtitle = stringResource(R.string.qs_settings_kept),
                checked = true,
                switchEnabled = false,
                onCheckedChange = {},
                showDragHandle = false,
            )
        }
        item(key = "dock_minimum") {
            Text(stringResource(R.string.qs_keep_at_least_one_tab), Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Same tab labels and icons as the app; overflow scrolls instead of squeezing long translations. */
@Composable
private fun MonicaBottomNavPreview(tabs: List<BottomNavContentTab>) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        val minItemWidth = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val width = maxOf(maxWidth, minItemWidth * (tabs.size + 1))
        key(tabs) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                NavigationBar(modifier = Modifier.width(width), windowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(selected = index == 0, onClick = {},
                            modifier = Modifier.testTag("quick_setup_preview_${tab.name}"),
                            icon = { Icon(tabIcon(tab), contentDescription = null) },
                            label = { Text(stringResource(tabShortLabelRes(tab)), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    NavigationBarItem(selected = false, onClick = {},
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings_short), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}

@Composable
private fun PageAdjustmentsStep(settings: AppSettings, viewModel: SettingsViewModel) {
    val tabs = settings.quickSetupVisibleTabs()
    if (BottomNavContentTab.VAULT_V2 in tabs) {
        SetupExpandableGroup(stringResource(R.string.nav_v2_vault_short), Icons.Default.Home) {
            SetupSwitchRow(Icons.Default.Home, stringResource(R.string.qs_vault_overview), settings.vaultOverviewEnabled,
                viewModel::updateVaultOverviewEnabled)
            SetupSwitchRow(Icons.Default.Folder, stringResource(R.string.vault_v2_hierarchical_layout_title),
                settings.vaultV2LayoutMode == VaultV2LayoutMode.HIERARCHICAL,
                { viewModel.updateVaultV2LayoutMode(if (it) VaultV2LayoutMode.HIERARCHICAL else VaultV2LayoutMode.CLASSIC) })
        }
    }
    if (BottomNavContentTab.PASSWORDS in tabs) {
        SetupExpandableGroup(stringResource(R.string.qs_step_password_list_title), Icons.Default.ViewList) {
            PasswordListAdjustmentStep(settings.passwordPageAggregateEnabled, settings.passwordListQuickFiltersEnabled,
                settings.passwordListCategoryQuickFiltersEnabled, settings.passwordListQuickAccessEnabled,
                viewModel::updatePasswordPageAggregateEnabled, viewModel::updatePasswordListQuickFiltersEnabled,
                viewModel::updatePasswordListCategoryQuickFiltersEnabled, viewModel::updatePasswordListQuickAccessEnabled)
        }
    }
    if (BottomNavContentTab.PASSWORDS in tabs || BottomNavContentTab.VAULT_V2 in tabs) {
        SetupExpandableGroup(stringResource(R.string.qs_step_password_card_title), Icons.Default.Password) {
            PasswordCardAdjustmentStep(settings, settings.passwordCardDisplayFields,
                settings.passwordCardShowAuthenticator, settings.passwordCardHideOtherContentWhenAuthenticator,
                viewModel::updatePasswordCardDisplayFields, viewModel::updatePasswordCardShowAuthenticator,
                viewModel::updatePasswordCardHideOtherContentWhenAuthenticator)
        }
    }
    if (BottomNavContentTab.AUTHENTICATOR in tabs) {
        SetupExpandableGroup(stringResource(R.string.qs_step_authenticator_card_title), Icons.Default.Security) {
            AuthenticatorCardAdjustmentStep(settings, settings.authenticatorCardDisplayFields,
                settings.validatorUnifiedProgressBar == UnifiedProgressBarMode.ENABLED, settings.validatorSmoothProgress,
                viewModel::updateAuthenticatorCardDisplayFields,
                { viewModel.updateValidatorUnifiedProgressBar(if (it) UnifiedProgressBarMode.ENABLED else UnifiedProgressBarMode.DISABLED) },
                viewModel::updateValidatorSmoothProgress)
        }
    }
    SetupExpandableGroup(stringResource(R.string.qs_step_appearance_title), Icons.Default.Palette,
        subtitle = stringResource(colorSchemeLabelRes(settings.colorScheme))) {
        AppearanceStep(settings.colorScheme, viewModel::updateColorScheme)
    }
}

@Composable
private fun SetupExpandableGroup(
    title: String, icon: ImageVector, subtitle: String? = null, tag: String = title,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().testTag(tag).clip(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand)) { expanded = !expanded }
                .padding(16.dp).heightIn(min = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                MonicaExpansionChevron(expanded, contentDescription = null)
            }
            MonicaExpandableContent(expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
private fun DataImportStep(
    onOpenBitwardenSettings: () -> Unit, onOpenWebDavBackup: () -> Unit,
    onOpenLocalKeePass: () -> Unit, onOpenImportData: (fromApps: Boolean) -> Unit,
) {
    Text(stringResource(R.string.qs_data_optional), Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SetupActionCard(Icons.Default.SwapHoriz, stringResource(R.string.qs_import_from_apps),
            stringResource(R.string.qs_import_from_apps_desc), stringResource(R.string.qs_go_import),
            { onOpenImportData(true) }, 0, 5)
        SetupActionCard(Icons.Default.Shield, stringResource(R.string.qs_link_bitwarden),
            stringResource(R.string.qs_link_bitwarden_desc), stringResource(R.string.qs_go_link), onOpenBitwardenSettings, 1, 5)
        SetupActionCard(Icons.Default.Key, stringResource(R.string.qs_link_keepass),
            stringResource(R.string.qs_link_keepass_desc), stringResource(R.string.qs_go_link), onOpenLocalKeePass, 2, 5)
        SetupActionCard(Icons.Default.Link, stringResource(R.string.qs_link_webdav),
            stringResource(R.string.qs_link_webdav_desc), stringResource(R.string.qs_go_setup), onOpenWebDavBackup, 3, 5)
        SetupActionCard(Icons.Default.UploadFile, stringResource(R.string.qs_manual_import),
            stringResource(R.string.qs_manual_import_desc), stringResource(R.string.qs_go_import),
            { onOpenImportData(false) }, 4, 5)
    }
}

@Composable
private fun PasswordListAdjustmentStep(
    aggregateEnabled: Boolean,
    quickFiltersEnabled: Boolean,
    categoryQuickFiltersEnabled: Boolean,
    quickAccessEnabled: Boolean,
    onAggregateChange: (Boolean) -> Unit,
    onQuickFiltersChange: (Boolean) -> Unit,
    onCategoryQuickFiltersChange: (Boolean) -> Unit,
    onQuickAccessChange: (Boolean) -> Unit
) {
    SetupSection(title = stringResource(R.string.qs_list_content), icon = Icons.Default.DashboardCustomize) {
        SetupSwitchRow(
            icon = Icons.Default.Widgets,
            title = stringResource(R.string.qs_aggregate_all_items),
            checked = aggregateEnabled,
            onCheckedChange = onAggregateChange
        )
        SetupSwitchRow(
            icon = Icons.Default.Security,
            title = stringResource(R.string.qs_quick_access),
            checked = quickAccessEnabled,
            onCheckedChange = onQuickAccessChange
        )
    }
    SetupSection(title = stringResource(R.string.qs_filter), icon = Icons.Default.Storage) {
        SetupSwitchRow(
            icon = Icons.Default.Check,
            title = stringResource(R.string.qs_quick_filters),
            checked = quickFiltersEnabled,
            onCheckedChange = onQuickFiltersChange
        )
        SetupSwitchRow(
            icon = Icons.Default.DashboardCustomize,
            title = stringResource(R.string.qs_category_quick_filters),
            checked = categoryQuickFiltersEnabled,
            onCheckedChange = onCategoryQuickFiltersChange
        )
    }
}

@Composable
private fun PasswordCardAdjustmentStep(
    settings: AppSettings,
    selectedFields: List<PasswordCardDisplayField>,
    showAuthenticator: Boolean,
    hideOtherContentWhenAuthenticator: Boolean,
    onFieldsChange: (List<PasswordCardDisplayField>) -> Unit,
    onShowAuthenticatorChange: (Boolean) -> Unit,
    onHideOtherContentWhenAuthenticatorChange: (Boolean) -> Unit
) {
    PasswordCardLivePreview(settings = settings, selectedFields = selectedFields)
    SetupSection(title = stringResource(R.string.qs_display_fields), icon = Icons.Default.Password) {
        SetupSwitchRow(
            icon = Icons.Default.Key,
            title = stringResource(R.string.qs_show_username),
            checked = PasswordCardDisplayField.USERNAME in selectedFields,
            onCheckedChange = {
                onFieldsChange(togglePasswordCardField(selectedFields, PasswordCardDisplayField.USERNAME, it))
            }
        )
        SetupSwitchRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.qs_show_website),
            checked = PasswordCardDisplayField.WEBSITE in selectedFields,
            onCheckedChange = {
                onFieldsChange(togglePasswordCardField(selectedFields, PasswordCardDisplayField.WEBSITE, it))
            }
        )
    }
    SetupSection(title = stringResource(R.string.qs_authenticator_link), icon = Icons.Default.Security) {
        SetupSwitchRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.qs_show_bound_authenticator),
            checked = showAuthenticator,
            onCheckedChange = onShowAuthenticatorChange
        )
        SetupSwitchRow(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.qs_hide_other_when_authenticator),
            checked = hideOtherContentWhenAuthenticator,
            onCheckedChange = onHideOtherContentWhenAuthenticatorChange
        )
    }
}

@Composable
private fun AuthenticatorCardAdjustmentStep(
    settings: AppSettings,
    selectedFields: List<AuthenticatorCardDisplayField>,
    unifiedProgressEnabled: Boolean,
    smoothProgressEnabled: Boolean,
    onFieldsChange: (List<AuthenticatorCardDisplayField>) -> Unit,
    onUnifiedProgressChange: (Boolean) -> Unit,
    onSmoothProgressChange: (Boolean) -> Unit
) {
    AuthenticatorCardLivePreview(settings = settings, selectedFields = selectedFields)
    SetupSection(title = stringResource(R.string.qs_display_fields), icon = Icons.Default.Security) {
        SetupSwitchRow(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.qs_show_issuer),
            checked = AuthenticatorCardDisplayField.ISSUER in selectedFields,
            onCheckedChange = {
                onFieldsChange(toggleAuthenticatorField(selectedFields, AuthenticatorCardDisplayField.ISSUER, it))
            }
        )
        SetupSwitchRow(
            icon = Icons.Default.Key,
            title = stringResource(R.string.qs_show_account_name),
            checked = AuthenticatorCardDisplayField.ACCOUNT_NAME in selectedFields,
            onCheckedChange = {
                onFieldsChange(toggleAuthenticatorField(selectedFields, AuthenticatorCardDisplayField.ACCOUNT_NAME, it))
            }
        )
    }
    SetupSection(title = stringResource(R.string.qs_progress_display), icon = Icons.Default.AutoAwesome) {
        SetupSwitchRow(
            icon = Icons.Default.Widgets,
            title = stringResource(R.string.qs_unified_progress_bar),
            checked = unifiedProgressEnabled,
            onCheckedChange = onUnifiedProgressChange
        )
        SetupSwitchRow(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.qs_smooth_progress_animation),
            checked = smoothProgressEnabled,
            onCheckedChange = onSmoothProgressChange
        )
    }
}

@Composable
private fun PasswordCardLivePreview(
    settings: AppSettings,
    selectedFields: List<PasswordCardDisplayField>
) {
    val previewEntry = remember {
        PasswordEntry(
            title = "GitHub - Monica-all",
            website = "github.com",
            username = "joyins",
            password = "******",
            appName = "GitHub",
            authenticatorKey = "JBSWY3DPEHPK3PXP"
        )
    }
    SetupSection(title = stringResource(R.string.qs_live_preview), icon = Icons.Default.Password) {
        PasswordEntryCardV2(
            entry = previewEntry,
            onClick = {},
            isSingleCard = true,
            iconCardsEnabled = settings.iconCardsEnabled && settings.passwordPageIconEnabled,
            unmatchedIconHandlingStrategy = settings.unmatchedIconHandlingStrategy,
            passwordCardDisplayMode = settings.passwordCardDisplayMode,
            passwordCardDisplayFields = selectedFields,
            showAuthenticator = settings.passwordCardShowAuthenticator,
            hideOtherContentWhenAuthenticator = settings.passwordCardHideOtherContentWhenAuthenticator,
            totpTimeOffsetSeconds = settings.totpTimeOffset,
            smoothAuthenticatorProgress = settings.validatorSmoothProgress,
            enableSharedBounds = false
        )
        Text(
            text = stringResource(R.string.qs_preview_note_3_fields),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuthenticatorCardLivePreview(
    settings: AppSettings,
    selectedFields: List<AuthenticatorCardDisplayField>
) {
    val previewItem = remember {
        SecureItem(
            itemType = ItemType.TOTP,
            title = "GitHub",
            itemData = Json.encodeToString(
                TotpData(
                    secret = "JBSWY3DPEHPK3PXP",
                    issuer = "GitHub",
                    accountName = "joyins@example.com",
                    link = "github.com"
                )
            )
        )
    }
    SetupSection(title = stringResource(R.string.qs_live_preview), icon = Icons.Default.Security) {
        TotpCodeCard(
            item = previewItem,
            onCopyCode = {},
            appSettings = settings.copy(
                authenticatorCardDisplayFields = selectedFields,
                iconCardsEnabled = settings.iconCardsEnabled && settings.authenticatorPageIconEnabled
            )
        )
        Text(
            text = stringResource(R.string.qs_authenticator_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonicaPlusStep(
    isPlusActivated: Boolean,
    onOpenMonicaPlus: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(if (isPlusActivated) R.string.qs_plus_activated else R.string.qs_plus_prompt),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(if (isPlusActivated) R.string.qs_plus_activated_desc else R.string.qs_plus_prompt_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isPlusActivated) {
            Button(
                onClick = onOpenMonicaPlus,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.qs_open_monica_plus))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
    }
}

private fun togglePasswordCardField(
    fields: List<PasswordCardDisplayField>,
    field: PasswordCardDisplayField,
    enabled: Boolean
): List<PasswordCardDisplayField> {
    val order = listOf(PasswordCardDisplayField.USERNAME, PasswordCardDisplayField.WEBSITE)
    return order.filter { candidate ->
        if (candidate == field) enabled else candidate in fields
    }
}

private fun toggleAuthenticatorField(
    fields: List<AuthenticatorCardDisplayField>,
    field: AuthenticatorCardDisplayField,
    enabled: Boolean
): List<AuthenticatorCardDisplayField> {
    val order = listOf(
        AuthenticatorCardDisplayField.ISSUER,
        AuthenticatorCardDisplayField.ACCOUNT_NAME
    )
    return order.filter { candidate ->
        if (candidate == field) enabled else candidate in fields
    }
}

@Composable
private fun QuickSetupBottomBar(primaryText: String, saving: Boolean, enabled: Boolean, onBack: (() -> Unit)?, onNext: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, enabled = !saving && enabled, modifier = Modifier.testTag("quick_setup_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.qs_previous))
                }
            }
            Button(onClick = onNext, enabled = !saving && enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("quick_setup_next"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(primaryText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 12.dp).size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SetupSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun SetupActionCard(
    icon: ImageVector, title: String, description: String, badge: String, onClick: () -> Unit,
    groupIndex: Int = 0, groupSize: Int = 1,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = settingsSectionItemShape(groupIndex, groupSize), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconSurface(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupSwitchCard(
    icon: ImageVector, title: String, description: String, checked: Boolean,
    enabled: Boolean = true, groupIndex: Int = 0, groupSize: Int = 1,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(shape = settingsSectionItemShape(groupIndex, groupSize), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconSurface(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun SetupSwitchRow(
    icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true, tag: String = title,
) {
    Row(Modifier.fillMaxWidth().testTag(tag).clip(RoundedCornerShape(16.dp))
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ColorSchemeRow(
    scheme: ColorScheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ColorSchemePreviewIcon(scheme = scheme)
        Text(
            text = stringResource(colorSchemeLabelRes(scheme)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null)
        }
    }
}

@Composable
private fun ColorSchemePreviewIcon(scheme: ColorScheme) {
    val swatches = schemeSwatches(scheme)
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            swatches.forEach { color ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(color)
                )
            }
        }
    }
}

@Composable
private fun IconSurface(icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@StringRes
private fun languageLabelRes(language: Language): Int = when (language) {
    Language.SYSTEM -> R.string.qs_lang_system
    Language.ENGLISH -> R.string.qs_lang_english
    Language.CHINESE -> R.string.qs_lang_chinese
    Language.TRADITIONAL_CHINESE -> R.string.language_chinese_traditional
    Language.CLASSICAL_CHINESE -> R.string.language_classical_chinese
    Language.VIETNAMESE -> R.string.qs_lang_vietnamese
    Language.JAPANESE -> R.string.qs_lang_japanese
    Language.RUSSIAN -> R.string.qs_lang_russian
    Language.KOREAN -> R.string.qs_lang_korean
    Language.GERMAN -> R.string.qs_lang_german
    Language.SPANISH -> R.string.qs_lang_spanish
    Language.FRENCH -> R.string.qs_lang_french
    Language.POLISH -> R.string.language_polish
    Language.ITALIAN -> R.string.language_italian
    Language.NYA -> R.string.qs_lang_nya
    Language.SNOW_LEOPARD -> R.string.qs_lang_snow_leopard
}

@StringRes
private fun colorSchemeLabelRes(scheme: ColorScheme): Int = when (scheme) {
    ColorScheme.DEFAULT -> R.string.color_scheme_default
    ColorScheme.OCEAN_BLUE -> R.string.ocean_blue_scheme
    ColorScheme.SUNSET_ORANGE -> R.string.sunset_orange_scheme
    ColorScheme.FOREST_GREEN -> R.string.forest_green_scheme
    ColorScheme.TECH_PURPLE -> R.string.tech_purple_scheme
    ColorScheme.BLACK_MAMBA -> R.string.black_mamba_scheme
    ColorScheme.GREY_STYLE -> R.string.grey_style_scheme
    ColorScheme.WATER_LILIES -> R.string.water_lilies_scheme
    ColorScheme.IMPRESSION_SUNRISE -> R.string.impression_sunrise_scheme
    ColorScheme.JAPANESE_BRIDGE -> R.string.japanese_bridge_scheme
    ColorScheme.HAYSTACKS -> R.string.haystacks_scheme
    ColorScheme.ROUEN_CATHEDRAL -> R.string.rouen_cathedral_scheme
    ColorScheme.PARLIAMENT_FOG -> R.string.parliament_fog_scheme
    ColorScheme.CATPPUCCIN_LATTE -> R.string.catppuccin_latte_scheme
    ColorScheme.CATPPUCCIN_FRAPPE -> R.string.catppuccin_frappe_scheme
    ColorScheme.CATPPUCCIN_MACCHIATO -> R.string.catppuccin_macchiato_scheme
    ColorScheme.CATPPUCCIN_MOCHA -> R.string.catppuccin_mocha_scheme
    ColorScheme.CUSTOM -> R.string.color_scheme_custom
}

private fun schemeSwatches(scheme: ColorScheme): List<Color> = when (scheme) {
    ColorScheme.OCEAN_BLUE -> listOf(Color(0xFF0B57D0), Color(0xFF00A1C9), Color(0xFFB9E9F2))
    ColorScheme.SUNSET_ORANGE -> listOf(Color(0xFFB84A00), Color(0xFFFF8A50), Color(0xFFFFD7C2))
    ColorScheme.FOREST_GREEN -> listOf(Color(0xFF006C47), Color(0xFF3E8F65), Color(0xFFC8E6C9))
    ColorScheme.GREY_STYLE -> listOf(Color(0xFF4B465C), Color(0xFF7C748D), Color(0xFFE5E0EC))
    ColorScheme.BLACK_MAMBA -> listOf(Color(0xFF0B0B0D), Color(0xFFD9A900), Color(0xFF8F5CFF))
    else -> listOf(Color(0xFF6750A4), Color(0xFF625B71), Color(0xFFEADDFF))
}

@StringRes
private fun tabLabelRes(tab: BottomNavContentTab): Int = when (tab) {
    BottomNavContentTab.VAULT_V2 -> R.string.nav_v2_vault
    BottomNavContentTab.PASSWORDS -> R.string.nav_passwords
    BottomNavContentTab.AUTHENTICATOR -> R.string.nav_authenticator
    BottomNavContentTab.CARD_WALLET -> R.string.nav_card_wallet
    BottomNavContentTab.GENERATOR -> R.string.nav_generator
    BottomNavContentTab.NOTES -> R.string.nav_notes
    BottomNavContentTab.SEND -> R.string.nav_v2_send
    BottomNavContentTab.PASSKEY -> R.string.nav_passkey
    BottomNavContentTab.STEAM -> R.string.nav_steam
}

@StringRes
private fun tabShortLabelRes(tab: BottomNavContentTab): Int = when (tab) {
    BottomNavContentTab.VAULT_V2 -> R.string.nav_v2_vault_short
    BottomNavContentTab.PASSWORDS -> R.string.nav_passwords_short
    BottomNavContentTab.AUTHENTICATOR -> R.string.nav_authenticator_short
    BottomNavContentTab.CARD_WALLET -> R.string.nav_card_wallet_short
    BottomNavContentTab.GENERATOR -> R.string.nav_generator_short
    BottomNavContentTab.NOTES -> R.string.nav_notes_short
    BottomNavContentTab.SEND -> R.string.nav_v2_send_short
    BottomNavContentTab.PASSKEY -> R.string.nav_passkey_short
    BottomNavContentTab.STEAM -> R.string.nav_steam_short
}

private fun tabIcon(tab: BottomNavContentTab): ImageVector = when (tab) {
    BottomNavContentTab.VAULT_V2 -> Icons.Default.Home
    BottomNavContentTab.PASSWORDS -> Icons.Default.Lock
    BottomNavContentTab.AUTHENTICATOR -> Icons.Default.Security
    BottomNavContentTab.CARD_WALLET -> Icons.Default.Wallet
    BottomNavContentTab.GENERATOR -> Icons.Default.AutoAwesome
    BottomNavContentTab.NOTES -> Icons.Default.Note
    BottomNavContentTab.SEND -> Icons.Default.Send
    BottomNavContentTab.PASSKEY -> Icons.Default.Key
    BottomNavContentTab.STEAM -> SteamDockIcon
}
