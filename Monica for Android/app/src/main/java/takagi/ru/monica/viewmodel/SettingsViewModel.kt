package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.AddButtonBehaviorMode
import takagi.ru.monica.data.AddButtonMenuAction
import takagi.ru.monica.data.AppLauncherIcon
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.CategorySelectionUiMode
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.PasswordSwipeSelectionMode
import takagi.ru.monica.data.PresetCustomField
import takagi.ru.monica.data.QuickSetupPreset
import takagi.ru.monica.data.quickSetupVisibleTabs
import takagi.ru.monica.data.quickSetupTabOrder
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager

/**
 * ViewModel for Settings screen
 */
class SettingsViewModel(
    private val settingsManager: SettingsManager,
    private val secureItemRepository: SecureItemRepository? = null
) : ViewModel() {
    
    private val _settingsLoaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded

    val settings: StateFlow<AppSettings> = settingsManager.settingsFlow
        .onEach { _settingsLoaded.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )
    
    // 预设自定义字段列表
    val presetCustomFields: StateFlow<List<PresetCustomField>> = settingsManager.presetCustomFieldsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 获取所有TOTP验证器
    val totpItems: StateFlow<List<SecureItem>> = secureItemRepository?.getItemsByType(ItemType.TOTP)
        ?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    
    fun updateThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            settingsManager.updateThemeMode(themeMode)
        }
    }

    fun updateOledPureBlackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateOledPureBlackEnabled(enabled)
        }
    }

    fun updateColorScheme(colorScheme: ColorScheme) {
        viewModelScope.launch {
            settingsManager.updateColorScheme(colorScheme)
        }
    }

    fun updateInterfaceScalePercent(percent: Int) {
        viewModelScope.launch {
            settingsManager.updateInterfaceScalePercent(percent)
        }
    }
    
    fun updateLanguage(language: Language) {
        viewModelScope.launch {
            settingsManager.updateLanguage(language)
        }
    }
    
    fun updateBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateBiometricEnabled(enabled)
        }
    }

    fun updateQuickSetupCompleted(completed: Boolean) {
        viewModelScope.launch {
            settingsManager.updateQuickSetupCompleted(completed)
        }
    }

    suspend fun applyQuickSetupPreset(preset: QuickSetupPreset) {
        settingsManager.applyQuickSetupPreset(preset)
        // Advance only after the screen observes the saved layout.
        settings.first { preset.matches(it) }
    }

    suspend fun updateQuickSetupNavigation(order: List<BottomNavContentTab>, tabs: List<BottomNavContentTab>) {
        settingsManager.updateQuickSetupNavigation(order, tabs)
        settings.first {
            it.quickSetupVisibleTabs() == tabs && it.quickSetupTabOrder() == order && !it.autoHideBottomNavWhenSingleTab
        }
    }

    suspend fun completeQuickSetup() {
        settingsManager.updateQuickSetupCompleted(true)
    }
    
    fun updateAutoLockMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsManager.updateAutoLockMinutes(minutes)
        }
    }
    
    fun updateScreenshotProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateScreenshotProtectionEnabled(enabled)
        }
    }

    fun updateClipboardAutoClearSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsManager.updateClipboardAutoClearSeconds(seconds)
        }
    }

    fun updateDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateDynamicColorEnabled(enabled)
        }
    }

    fun updateBottomNavVisibility(tab: BottomNavContentTab, visible: Boolean) {
        viewModelScope.launch {
            settingsManager.updateBottomNavVisibility(tab, visible)
        }
    }

    fun updateBottomNavOrder(order: List<BottomNavContentTab>) {
        viewModelScope.launch {
            settingsManager.updateBottomNavOrder(order)
        }
    }

    fun updateCustomColors(
        primary: Long,
        secondary: Long,
        tertiary: Long,
        neutral: Long = primary,
        neutralVariant: Long = secondary
    ) {
        viewModelScope.launch {
            settingsManager.updateCustomColors(primary, secondary, tertiary, neutral, neutralVariant)
        }
    }

    fun updateStackCardMode(mode: String) {
        viewModelScope.launch {
            settingsManager.updateStackCardMode(mode)
        }
    }

    fun updateWalletStackLoopEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateWalletStackLoopEnabled(enabled)
        }
    }

    fun updatePasswordGroupMode(mode: String) {
        viewModelScope.launch {
            settingsManager.updatePasswordGroupMode(mode)
        }
    }

    fun updatePasswordWebsiteStackMatchMode(mode: String) {
        viewModelScope.launch {
            settingsManager.updatePasswordWebsiteStackMatchMode(mode)
        }
    }

    fun updatePasswordSwipeSelectionMode(mode: PasswordSwipeSelectionMode) {
        viewModelScope.launch {
            settingsManager.updatePasswordSwipeSelectionMode(mode)
        }
    }

    fun updateDisablePasswordVerification(disabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateDisablePasswordVerification(disabled)
        }
    }

    fun updateBitwardenSyncForensicsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateBitwardenSyncForensicsEnabled(enabled)
        }
    }

    fun updateBitwardenSyncForensicsDirectoryUri(uri: String?) {
        viewModelScope.launch {
            settingsManager.updateBitwardenSyncForensicsDirectoryUri(uri)
        }
    }

    fun updateBitwardenSyncForensicsRawCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateBitwardenSyncForensicsRawCaptureEnabled(enabled)
        }
    }

    fun updateValidatorProgressBarStyle(style: takagi.ru.monica.data.ProgressBarStyle) {
        android.util.Log.d("SettingsViewModel", "Updating progress bar style to: $style")
        viewModelScope.launch {
            settingsManager.updateValidatorProgressBarStyle(style)
            android.util.Log.d("SettingsViewModel", "Progress bar style updated successfully")
        }
    }

    fun updateValidatorUnifiedProgressBar(mode: takagi.ru.monica.data.UnifiedProgressBarMode) {
        viewModelScope.launch {
            settingsManager.updateValidatorUnifiedProgressBar(mode)
        }
    }

    fun updateValidatorSmoothProgress(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateValidatorSmoothProgress(enabled)
        }
    }

    fun updateValidatorVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateValidatorVibrationEnabled(enabled)
        }
    }

    fun updateHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateHapticFeedbackEnabled(enabled)
        }
    }

    fun updateHideFabOnScroll(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateHideFabOnScroll(enabled)
        }
    }

    fun updateSecurityAnalysisAutoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateSecurityAnalysisAutoEnabled(enabled)
        }
    }

    fun updatePasswordDetailSecurityAnalysisEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordDetailSecurityAnalysisEnabled(enabled)
        }
    }

    fun updateSteamMiniProfileBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateSteamMiniProfileBackgroundEnabled(enabled)
        }
    }

    fun updateBitwardenBottomStatusBarEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateBitwardenBottomStatusBarEnabled(enabled)
        }
    }

    fun updateCopyNextCodeWhenExpiring(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateCopyNextCodeWhenExpiring(enabled)
        }
    }

    fun updateNotificationValidatorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateNotificationValidatorEnabled(enabled)
        }
    }

    fun updateNotificationValidatorId(id: Long) {
        viewModelScope.launch {
            settingsManager.updateNotificationValidatorId(id)
        }
    }

    fun updateNotificationValidatorAutoMatch(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateNotificationValidatorAutoMatch(enabled)
        }
    }

    fun updatePlusActivated(activated: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePlusActivated(activated)
        }
    }
    
    fun updateAutoHideBottomNavWhenSingleTab(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateAutoHideBottomNavWhenSingleTab(enabled)
        }
    }
    
    // 回收站设置
    fun updateTrashEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateTrashEnabled(enabled)
        }
    }
    
    fun updateTrashAutoDeleteDays(days: Int) {
        viewModelScope.launch {
            settingsManager.updateTrashAutoDeleteDays(days)
        }
    }

    fun updateIconCardsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateIconCardsEnabled(enabled)
        }
    }

    fun updateAppLauncherIcon(icon: AppLauncherIcon) {
        viewModelScope.launch {
            settingsManager.updateAppLauncherIcon(icon)
        }
    }

    fun updateAppLauncherLabel(label: AppLauncherLabel) {
        viewModelScope.launch {
            settingsManager.updateAppLauncherLabel(label)
        }
    }

    fun updatePasswordPageIconEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordPageIconEnabled(enabled)
        }
    }

    fun updateAuthenticatorPageIconEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateAuthenticatorPageIconEnabled(enabled)
        }
    }

    fun updatePasskeyPageIconEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasskeyPageIconEnabled(enabled)
        }
    }

    fun updateUnmatchedIconHandlingStrategy(strategy: takagi.ru.monica.data.UnmatchedIconHandlingStrategy) {
        viewModelScope.launch {
            settingsManager.updateUnmatchedIconHandlingStrategy(strategy)
        }
    }

    fun updatePasswordCardDisplayMode(mode: takagi.ru.monica.data.PasswordCardDisplayMode) {
        viewModelScope.launch {
            settingsManager.updatePasswordCardDisplayMode(mode)
        }
    }

    fun updatePasswordCardDisplayFields(fields: List<takagi.ru.monica.data.PasswordCardDisplayField>) {
        viewModelScope.launch {
            settingsManager.updatePasswordCardDisplayFields(fields)
        }
    }

    fun updatePasswordCardShowAuthenticator(show: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordCardShowAuthenticator(show)
        }
    }

    fun updatePasswordCardHideOtherContentWhenAuthenticator(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordCardHideOtherContentWhenAuthenticator(enabled)
        }
    }

    fun updateAuthenticatorCardDisplayFields(fields: List<takagi.ru.monica.data.AuthenticatorCardDisplayField>) {
        viewModelScope.launch {
            settingsManager.updateAuthenticatorCardDisplayFields(fields)
        }
    }

    fun updateAuthenticatorCardHideCodeByDefault(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateAuthenticatorCardHideCodeByDefault(enabled)
        }
    }

    fun updateAuthenticatorLayoutMode(mode: takagi.ru.monica.data.AuthenticatorLayoutMode) {
        viewModelScope.launch {
            settingsManager.updateAuthenticatorLayoutMode(mode)
        }
    }

    fun updateVaultV2LayoutMode(mode: takagi.ru.monica.data.VaultV2LayoutMode) {
        viewModelScope.launch {
            settingsManager.updateVaultV2LayoutMode(mode)
        }
    }

    fun updateVaultListSort(sort: takagi.ru.monica.data.VaultListSort) {
        viewModelScope.launch { settingsManager.updateVaultListSort(sort) }
    }

    fun updateAutoFocusSearchOnStartup(enabled: Boolean) {
        viewModelScope.launch { settingsManager.updateAutoFocusSearchOnStartup(enabled) }
    }

    fun updateVaultOverviewEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.updateVaultOverviewEnabled(enabled) }
    }

    fun updateVaultOverviewConfig(transform: (takagi.ru.monica.data.VaultOverviewConfig) -> takagi.ru.monica.data.VaultOverviewConfig) {
        viewModelScope.launch { settingsManager.updateVaultOverviewConfig(transform) }
    }

    fun updatePasswordListQuickFiltersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordListQuickFiltersEnabled(enabled)
        }
    }

    fun updatePasswordListQuickFilterItems(items: List<takagi.ru.monica.data.PasswordListQuickFilterItem>) {
        viewModelScope.launch {
            settingsManager.updatePasswordListQuickFilterItems(items)
        }
    }

    fun updatePasswordListCategoryQuickFiltersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordListCategoryQuickFiltersEnabled(enabled)
        }
    }

    fun updatePasswordListQuickFoldersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordListQuickFoldersEnabled(enabled)
        }
    }

    fun updatePasswordListQuickFolderStyle(style: takagi.ru.monica.data.PasswordListQuickFolderStyle) {
        viewModelScope.launch {
            settingsManager.updatePasswordListQuickFolderStyle(style)
        }
    }

    fun updatePasswordListQuickFolderPathBannerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordListQuickFolderPathBannerEnabled(enabled)
        }
    }

    fun updatePasswordListSystemBackToParentFolderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordListSystemBackToParentFolderEnabled(enabled)
        }
    }

    fun updateAddButtonBehaviorMode(mode: AddButtonBehaviorMode) {
        viewModelScope.launch {
            settingsManager.updateAddButtonBehaviorMode(mode)
        }
    }

    fun updateAddButtonMenuOrder(order: List<AddButtonMenuAction>) {
        viewModelScope.launch {
            settingsManager.updateAddButtonMenuOrder(order)
        }
    }

    fun updateAddButtonMenuEnabledActions(actions: List<AddButtonMenuAction>) {
        viewModelScope.launch {
            settingsManager.updateAddButtonMenuEnabledActions(actions)
        }
    }

    fun updatePasswordPageAggregateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordPageAggregateEnabled(enabled)
        }
    }

    fun updatePasswordPageVisibleContentTypes(types: List<PasswordPageContentType>) {
        viewModelScope.launch {
            settingsManager.updatePasswordPageVisibleContentTypes(types)
        }
    }

    fun updateCategorySelectionUiMode(mode: CategorySelectionUiMode) {
        viewModelScope.launch {
            settingsManager.updateCategorySelectionUiMode(mode)
        }
    }

    fun updatePasswordListQuickAccessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordListQuickAccessEnabled(enabled)
        }
    }

    fun updatePasswordListTopModulesOrder(order: List<takagi.ru.monica.data.PasswordListTopModule>) {
        viewModelScope.launch {
            settingsManager.updatePasswordListTopModulesOrder(order)
        }
    }

    fun updateNoteGridLayout(isGrid: Boolean) {
        viewModelScope.launch {
            settingsManager.updateNoteGridLayout(isGrid)
        }
    }

    fun updatePasswordFieldVisibility(field: String, visible: Boolean) {
        viewModelScope.launch {
            settingsManager.updatePasswordFieldVisibility(field, visible)
        }
    }
    
    // ==================== 预设自定义字段管理 ====================
    
    fun addPresetCustomField(field: PresetCustomField) {
        viewModelScope.launch {
            settingsManager.addPresetCustomField(field)
        }
    }
    
    fun updatePresetCustomField(field: PresetCustomField) {
        viewModelScope.launch {
            settingsManager.updatePresetCustomField(field)
        }
    }
    
    fun deletePresetCustomField(fieldId: String) {
        viewModelScope.launch {
            settingsManager.deletePresetCustomField(fieldId)
        }
    }
    
    fun reorderPresetCustomFields(fieldIds: List<String>) {
        viewModelScope.launch {
            settingsManager.reorderPresetCustomFields(fieldIds)
        }
    }
    
    fun clearAllPresetCustomFields() {
        viewModelScope.launch {
            settingsManager.clearAllPresetCustomFields()
        }
    }
    
    /**
     * 更新减少动画设置
     * 开启后将禁用共享元素动画，改为简单的淡入淡出效果
     * 主要用于解决 HyperOS 2 / Android 15 等设备上的动画卡顿问题
     */
    fun updateReduceAnimations(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateReduceAnimations(enabled)
        }
    }

    fun updateSmartDeduplicationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateSmartDeduplicationEnabled(enabled)
        }
    }

    fun updatePasswordContentEditorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.updatePasswordContentEditorEnabled(enabled) }
    }

    fun updateSeparateUsernameAccountEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateSeparateUsernameAccountEnabled(enabled)
        }
    }

    fun updateKeepassDxLikeMutationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateKeepassDxLikeMutationEnabled(enabled)
        }
    }

    fun categoryFilterStateFlow(scope: String): Flow<SavedCategoryFilterState> {
        return settingsManager.categoryFilterStateFlow(scope)
    }

    fun updateCategoryFilterState(scope: String, state: SavedCategoryFilterState) {
        viewModelScope.launch {
            settingsManager.updateCategoryFilterState(scope, state)
        }
    }

    fun updateBitwardenUploadAll(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateBitwardenUploadAll(enabled)
        }
    }
    
    /**
     * 更新自动填充数据源
     */
    fun updateAutofillSources(sources: Set<takagi.ru.monica.data.AutofillSource>) {
        viewModelScope.launch {
            settingsManager.updateAutofillSources(sources)
        }
    }
    
    /**
     * 更新自动填充优先级
     */
    fun updateAutofillPriority(priority: List<takagi.ru.monica.data.AutofillSource>) {
        viewModelScope.launch {
            settingsManager.updateAutofillPriority(priority)
        }
    }
    
}
