package takagi.ru.monica.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.AddButtonBehaviorMode
import takagi.ru.monica.data.AddButtonMenuAction
import takagi.ru.monica.data.AppLauncherIcon
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.BottomNavVisibility
import takagi.ru.monica.data.CategorySelectionUiMode
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.data.InterfaceScale
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordListQuickFolderStyle
import takagi.ru.monica.data.PasswordSwipeSelectionMode
import takagi.ru.monica.data.PasswordListTopModule
import takagi.ru.monica.data.PresetCustomField
import takagi.ru.monica.data.NoteCodeBlockCollapseMode
import takagi.ru.monica.data.ProgressBarStyle
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.data.UnifiedProgressBarMode
import takagi.ru.monica.data.AutofillSource
import takagi.ru.monica.data.AuthenticatorLayoutMode
import takagi.ru.monica.data.VaultV2LayoutMode
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.QuickSetupPreset
import takagi.ru.monica.data.quickSetupNavigationVisibility

private val Context.dataStore by preferencesDataStore("settings")

data class RememberedStorageTarget(
    val categoryId: Long? = null,
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val mdbxDatabaseId: Long? = null,
    val mdbxFolderId: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null
)

data class SavedCategoryFilterState(
    val type: String = "all",
    val primaryId: Long? = null,
    val secondaryId: Long? = null,
    val text: String? = null,
    val groupUuid: String? = null
)

data class PageAdjustmentPasswordFieldVisibilitySnapshot(
    val securityVerification: Boolean = true,
    val categoryAndNotes: Boolean = true,
    val appBinding: Boolean = true,
    val personalInfo: Boolean = true,
    val addressInfo: Boolean = true,
    val paymentInfo: Boolean = true
)

data class PageAdjustmentSettingsSnapshot(
    val passwordListQuickFiltersEnabled: Boolean = false,
    val passwordListQuickFilterItems: List<String> = emptyList(),
    val passwordListCategoryQuickFiltersEnabled: Boolean = false,
    val passwordListQuickFoldersEnabled: Boolean = false,
    val passwordListQuickFolderStyle: String = takagi.ru.monica.data.PasswordListQuickFolderStyle.CLASSIC.name,
    val passwordListQuickFolderPathBannerEnabled: Boolean = false,
    val passwordListSystemBackToParentFolderEnabled: Boolean = false,
    val addButtonBehaviorMode: String = takagi.ru.monica.data.AddButtonBehaviorMode.DIRECT_PASSWORD.name,
    val addButtonMenuOrder: List<String> = emptyList(),
    val addButtonMenuEnabledActions: List<String> = emptyList(),
    val passwordPageAggregateEnabled: Boolean = false,
    val passwordPageVisibleContentTypes: List<String> = emptyList(),
    val categorySelectionUiMode: String = takagi.ru.monica.data.CategorySelectionUiMode.DEFAULT.name,
    val colorSettingsVersion: Int = 0,
    val oledPureBlackEnabled: Boolean = false,
    val colorScheme: String = takagi.ru.monica.data.ColorScheme.DEFAULT.name,
    val customPrimaryColor: Long = 0xFF6650A4L,
    val customSecondaryColor: Long = 0xFF625B71L,
    val customTertiaryColor: Long = 0xFF7D5260L,
    val customNeutralColor: Long = 0xFF605D66L,
    val customNeutralVariantColor: Long = 0xFF625B71L,
    val bottomNavSettingsVersion: Int = 0,
    val bottomNavOrder: List<String> = emptyList(),
    val bottomNavVisibilityVaultV2: Boolean = false,
    val bottomNavVisibilityPasswords: Boolean = true,
    val bottomNavVisibilityAuthenticator: Boolean = true,
    val bottomNavVisibilityCardWallet: Boolean = true,
    val bottomNavVisibilityGenerator: Boolean = false,
    val bottomNavVisibilityNotes: Boolean = false,
    val bottomNavVisibilitySend: Boolean = false,
    val bottomNavVisibilityPasskey: Boolean = true,
    val bottomNavVisibilitySteam: Boolean = false,
    val useDraggableBottomNav: Boolean = false,
    val autoHideBottomNavWhenSingleTab: Boolean = false,
    val passwordListQuickAccessEnabled: Boolean = true,
    val passwordListTopModulesOrder: List<String> = emptyList(),
    val passwordCardDisplayMode: String = takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL.name,
    val passwordCardDisplayFields: List<String> = emptyList(),
    val passwordCardShowAuthenticator: Boolean = false,
    val passwordCardHideOtherContentWhenAuthenticator: Boolean = false,
    val stackCardMode: String = "AUTO",
    val walletStackLoopEnabled: Boolean = false,
    val passwordGroupMode: String = "smart",
    val passwordWebsiteStackMatchMode: String = "strict",
    val authenticatorCardDisplayFields: List<String> = emptyList(),
    val authenticatorCardHideCodeByDefault: Boolean = false,
    val authenticatorLayoutMode: String = AuthenticatorLayoutMode.STANDARD.name,
    val vaultV2LayoutMode: String = VaultV2LayoutMode.CLASSIC.name,
    val vaultOverviewEnabled: Boolean = true,
    val vaultOverviewConfig: String = "{}",
    val validatorProgressBarStyle: String = ProgressBarStyle.LINEAR.name,
    val validatorUnifiedProgressBar: String = UnifiedProgressBarMode.ENABLED.name,
    val validatorSmoothProgress: Boolean = true,
    val validatorVibrationEnabled: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val copyNextCodeWhenExpiring: Boolean = false,
    val securityAnalysisAutoEnabled: Boolean = false,
    val passwordDetailSecurityAnalysisEnabled: Boolean = true,
    val steamMiniProfileBackgroundEnabled: Boolean = false,
    val autofillAuthRequired: Boolean = true,
    val iconCardsEnabled: Boolean = true,
    val appLauncherIcon: String = takagi.ru.monica.data.AppLauncherIcon.MODERN.name,
    val appLauncherLabel: String = takagi.ru.monica.data.AppLauncherLabel.MONICA_PASS.name,
    val passwordPageIconEnabled: Boolean = true,
    val authenticatorPageIconEnabled: Boolean = true,
    val passkeyPageIconEnabled: Boolean = true,
    val unmatchedIconHandlingStrategy: String = takagi.ru.monica.data.UnmatchedIconHandlingStrategy.DEFAULT_ICON.name,
    val passwordFieldSettingsVersion: Int = 0,
    val separateUsernameAccountEnabled: Boolean = false,
    val presetCustomFieldsJson: String = "[]",
    val passwordFieldVisibility: PageAdjustmentPasswordFieldVisibilitySnapshot =
        PageAdjustmentPasswordFieldVisibilitySnapshot(),
)

/**
 * Settings manager using DataStore
 */
class SettingsManager(private val context: Context) {
    
    private val dataStore: DataStore<Preferences> = context.dataStore

    init {
        sharedSettingsScope.launch {
            migrateLegacyCategorySelectionUiModeIfNeeded()
        }
    }
    
    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val OLED_PURE_BLACK_ENABLED_KEY = booleanPreferencesKey("oled_pure_black_enabled")
        private val COLOR_SCHEME_KEY = stringPreferencesKey("color_scheme")
        private val CUSTOM_PRIMARY_COLOR_KEY = longPreferencesKey("custom_primary_color")
        private val CUSTOM_SECONDARY_COLOR_KEY = longPreferencesKey("custom_secondary_color")
        private val CUSTOM_TERTIARY_COLOR_KEY = longPreferencesKey("custom_tertiary_color")
        private val CUSTOM_NEUTRAL_COLOR_KEY = longPreferencesKey("custom_neutral_color")
        private val CUSTOM_NEUTRAL_VARIANT_COLOR_KEY = longPreferencesKey("custom_neutral_variant_color")
        private val INTERFACE_SCALE_PERCENT_KEY = intPreferencesKey("interface_scale_percent")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val BIOMETRIC_ENABLED_KEY = booleanPreferencesKey("biometric_enabled")
        private val QUICK_SETUP_COMPLETED_KEY = booleanPreferencesKey("quick_setup_completed")
        private val AUTO_LOCK_MINUTES_KEY = intPreferencesKey("auto_lock_minutes")
        private val SCREENSHOT_PROTECTION_KEY = booleanPreferencesKey("screenshot_protection_enabled")
        private val CLIPBOARD_AUTO_CLEAR_SECONDS_KEY = intPreferencesKey("clipboard_auto_clear_seconds")
        private val SHOW_PASSWORDS_TAB_KEY = booleanPreferencesKey("show_passwords_tab")
        private val SHOW_VAULT_V2_TAB_KEY = booleanPreferencesKey("show_vault_v2_tab")
        private val SHOW_AUTHENTICATOR_TAB_KEY = booleanPreferencesKey("show_authenticator_tab")
        private val SHOW_CARD_WALLET_TAB_KEY = booleanPreferencesKey("show_card_wallet_tab")
        private val SHOW_NOTES_TAB_KEY = booleanPreferencesKey("show_notes_tab")
        private val SHOW_LEDGER_TAB_KEY = booleanPreferencesKey("show_ledger_tab")
        private val SHOW_GENERATOR_TAB_KEY = booleanPreferencesKey("show_generator_tab")  // 添加生成器标签键
        private val SHOW_SEND_TAB_KEY = booleanPreferencesKey("show_send_tab")
        private val SHOW_PASSKEY_TAB_KEY = booleanPreferencesKey("show_passkey_tab")  // 添加 Passkey 标签键
        private val SHOW_STEAM_TAB_KEY = booleanPreferencesKey("show_steam_tab")
        private val DYNAMIC_COLOR_ENABLED_KEY = booleanPreferencesKey("dynamic_color_enabled")
        private val BOTTOM_NAV_ORDER_KEY = stringPreferencesKey("bottom_nav_order")
        private val USE_DRAGGABLE_BOTTOM_NAV_KEY = booleanPreferencesKey("use_draggable_bottom_nav")
        private val AUTO_HIDE_BOTTOM_NAV_WHEN_SINGLE_TAB_KEY =
            booleanPreferencesKey("auto_hide_bottom_nav_when_single_tab")
        private val DISABLE_PASSWORD_VERIFICATION_KEY = booleanPreferencesKey("disable_password_verification")
        private val PASSKEY_HYPEROS_BIOMETRIC_BYPASS_ENABLED_KEY =
            booleanPreferencesKey("passkey_hyperos_biometric_bypass_enabled")
        private val BITWARDEN_SYNC_FORENSICS_ENABLED_KEY =
            booleanPreferencesKey("bitwarden_sync_forensics_enabled")
        private val BITWARDEN_SYNC_FORENSICS_DIRECTORY_URI_KEY =
            stringPreferencesKey("bitwarden_sync_forensics_directory_uri")
        private val BITWARDEN_SYNC_FORENSICS_RAW_CAPTURE_ENABLED_KEY =
            booleanPreferencesKey("bitwarden_sync_forensics_raw_capture_enabled")
        private val VALIDATOR_PROGRESS_BAR_STYLE_KEY = stringPreferencesKey("validator_progress_bar_style")
        private val VALIDATOR_UNIFIED_PROGRESS_BAR_KEY = stringPreferencesKey("validator_unified_progress_bar")
        private val VALIDATOR_SMOOTH_PROGRESS_KEY = booleanPreferencesKey("validator_smooth_progress")
        private val VALIDATOR_VIBRATION_ENABLED_KEY = booleanPreferencesKey("validator_vibration_enabled")
        private val HAPTIC_FEEDBACK_ENABLED_KEY = booleanPreferencesKey("haptic_feedback_enabled")
        private val BITWARDEN_BOTTOM_STATUS_BAR_ENABLED_KEY = booleanPreferencesKey("bitwarden_bottom_status_bar_enabled")
        private val COPY_NEXT_CODE_WHEN_EXPIRING_KEY = booleanPreferencesKey("copy_next_code_when_expiring")
        private val NOTIFICATION_VALIDATOR_ENABLED_KEY = booleanPreferencesKey("notification_validator_enabled")
        private val NOTIFICATION_VALIDATOR_AUTO_MATCH_KEY = booleanPreferencesKey("notification_validator_auto_match")
        private val NOTIFICATION_VALIDATOR_ID_KEY = longPreferencesKey("notification_validator_id")
        private val IS_PLUS_ACTIVATED_KEY = booleanPreferencesKey("is_plus_activated")
        private val STACK_CARD_MODE_KEY = stringPreferencesKey("stack_card_mode")
        private val WALLET_STACK_LOOP_ENABLED_KEY = booleanPreferencesKey("wallet_stack_loop_enabled")
        private val PASSWORD_GROUP_MODE_KEY = stringPreferencesKey("password_group_mode")
        private val PASSWORD_WEBSITE_STACK_MATCH_MODE_KEY =
            stringPreferencesKey("password_website_stack_match_mode")
        private val TOTP_TIME_OFFSET_KEY = intPreferencesKey("totp_time_offset") // TOTP时间偏移（秒）
        private val TRASH_ENABLED_KEY = booleanPreferencesKey("trash_enabled") // 回收站功能开关
        private val TRASH_AUTO_DELETE_DAYS_KEY = intPreferencesKey("trash_auto_delete_days") // 回收站自动清空天数
        private val ICON_CARDS_ENABLED_KEY = booleanPreferencesKey("icon_cards_enabled") // 带图标卡片开关
        private val APP_LAUNCHER_ICON_KEY = stringPreferencesKey("app_launcher_icon") // 主应用图标样式
        private val APP_LAUNCHER_LABEL_KEY = stringPreferencesKey("app_launcher_label") // 主应用桌面名称
        private val PASSWORD_PAGE_ICON_ENABLED_KEY = booleanPreferencesKey("password_page_icon_enabled") // 密码页图标开关
        private val AUTHENTICATOR_PAGE_ICON_ENABLED_KEY = booleanPreferencesKey("authenticator_page_icon_enabled") // 验证器页图标开关
        private val PASSKEY_PAGE_ICON_ENABLED_KEY = booleanPreferencesKey("passkey_page_icon_enabled") // 通行密钥页图标开关
        private val UNMATCHED_ICON_HANDLING_STRATEGY_KEY = stringPreferencesKey("unmatched_icon_handling_strategy") // 无匹配图标处理策略
        private val PASSWORD_CARD_DISPLAY_MODE_KEY = stringPreferencesKey("password_card_display_mode") // 密码卡片显示模式
        private val PASSWORD_CARD_DISPLAY_FIELDS_KEY = stringPreferencesKey("password_card_display_fields") // 密码卡片显示字段
        private val PASSWORD_CARD_SHOW_AUTHENTICATOR_KEY = booleanPreferencesKey("password_card_show_authenticator") // 密码卡片显示绑定验证器
        private val PASSWORD_CARD_HIDE_OTHER_CONTENT_WHEN_AUTHENTICATOR_KEY = booleanPreferencesKey("password_card_hide_other_content_when_authenticator") // 显示验证器时隐藏其他内容
        private val AUTHENTICATOR_CARD_DISPLAY_FIELDS_KEY = stringPreferencesKey("authenticator_card_display_fields") // 验证器卡片显示字段
        private val AUTHENTICATOR_CARD_HIDE_CODE_BY_DEFAULT_KEY = booleanPreferencesKey("authenticator_card_hide_code_by_default") // 验证器卡片默认隐藏验证码
        private val AUTHENTICATOR_LAYOUT_MODE_KEY = stringPreferencesKey("authenticator_layout_mode")
        private val VAULT_V2_LAYOUT_MODE_KEY = stringPreferencesKey("vault_v2_layout_mode")
        private val VAULT_OVERVIEW_ENABLED_KEY = booleanPreferencesKey("vault_overview_enabled")
        private val VAULT_OVERVIEW_CONFIG_KEY = stringPreferencesKey("vault_overview_config")
        private val PASSWORD_LIST_QUICK_FILTERS_ENABLED_KEY = booleanPreferencesKey("password_list_quick_filters_enabled") // 密码列表快捷筛选开关
        private val PASSWORD_LIST_QUICK_FILTER_ITEMS_KEY = stringPreferencesKey("password_list_quick_filter_items") // 密码列表快捷筛选显示内容
        private val PASSWORD_LIST_CATEGORY_QUICK_FILTERS_ENABLED_KEY = booleanPreferencesKey("password_list_category_quick_filters_enabled") // 密码列表分类快捷筛选开关
        private val PASSWORD_LIST_QUICK_FOLDERS_ENABLED_KEY = booleanPreferencesKey("password_list_quick_folders_enabled") // 密码列表快捷文件夹开关
        private val PASSWORD_LIST_QUICK_FOLDER_STYLE_KEY = stringPreferencesKey("password_list_quick_folder_style") // 密码列表快捷文件夹展示样式
        private val PASSWORD_LIST_QUICK_FOLDER_PATH_BANNER_ENABLED_KEY = booleanPreferencesKey("password_list_quick_folder_path_banner_enabled") // 密码列表路径横幅开关
        private val PASSWORD_LIST_SYSTEM_BACK_TO_PARENT_FOLDER_ENABLED_KEY =
            booleanPreferencesKey("password_list_system_back_to_parent_folder_enabled") // 密码页系统返回回到父文件夹
        private val ADD_BUTTON_BEHAVIOR_MODE_KEY = stringPreferencesKey("add_button_behavior_mode") // 添加按钮行为
        private val ADD_BUTTON_MENU_ORDER_KEY = stringPreferencesKey("add_button_menu_order") // 添加按钮菜单顺序
        private val ADD_BUTTON_MENU_ENABLED_ACTIONS_KEY = stringPreferencesKey("add_button_menu_enabled_actions") // 添加按钮菜单启用项
        private val PASSWORD_PAGE_AGGREGATE_ENABLED_KEY =
            booleanPreferencesKey("password_page_aggregate_enabled")
        private val PASSWORD_PAGE_VISIBLE_CONTENT_TYPES_KEY =
            stringPreferencesKey("password_page_visible_content_types")
        private val CATEGORY_SELECTION_UI_MODE_KEY = stringPreferencesKey("category_selection_ui_mode") // 分类选择 UI 形式
        private val PASSWORD_LIST_QUICK_ACCESS_ENABLED_KEY = booleanPreferencesKey("password_list_quick_access_enabled") // 密码列表“最近/常用”快捷入口开关
        private val PASSWORD_LIST_TOP_MODULES_ORDER_KEY = stringPreferencesKey("password_list_top_modules_order") // 密码列表顶部模块顺序
        private val PASSWORD_SWIPE_SELECTION_MODE_KEY = stringPreferencesKey("password_swipe_selection_mode") // 密码列表右滑选中模式
        private val HIDE_FAB_ON_SCROLL_KEY = booleanPreferencesKey("hide_fab_on_scroll") // 滚动隐藏 FAB
        private val SECURITY_ANALYSIS_AUTO_ENABLED_KEY = booleanPreferencesKey("security_analysis_auto_enabled") // 安全分析自动分析
        private val PASSWORD_DETAIL_SECURITY_ANALYSIS_ENABLED_KEY =
            booleanPreferencesKey("password_detail_security_analysis_enabled")
        private val STEAM_MINI_PROFILE_BACKGROUND_ENABLED_KEY =
            booleanPreferencesKey("steam_mini_profile_background_enabled")
        private val NOTE_GRID_LAYOUT_KEY = booleanPreferencesKey("note_grid_layout") // 笔记网格布局
        private val NOTE_CODE_BLOCK_COLLAPSE_MODE_KEY = stringPreferencesKey("note_code_block_collapse_mode") // 笔记代码块折叠模式
        private val AUTOFILL_AUTH_REQUIRED_KEY = booleanPreferencesKey("autofill_auth_required") // 自动填充验证
        
        // 密码页面字段可见性
        private val FIELD_SECURITY_VERIFICATION_KEY = booleanPreferencesKey("field_security_verification")
        private val FIELD_CATEGORY_AND_NOTES_KEY = booleanPreferencesKey("field_category_and_notes")
        private val FIELD_APP_BINDING_KEY = booleanPreferencesKey("field_app_binding")
        private val FIELD_PERSONAL_INFO_KEY = booleanPreferencesKey("field_personal_info")
        private val FIELD_ADDRESS_INFO_KEY = booleanPreferencesKey("field_address_info")
        private val FIELD_PAYMENT_INFO_KEY = booleanPreferencesKey("field_payment_info")
        
        // 预设自定义字段 (JSON 格式存储)
        private val PRESET_CUSTOM_FIELDS_KEY = stringPreferencesKey("preset_custom_fields")
        
        // 减少动画 - 解决部分设备动画卡顿问题
        private val REDUCE_ANIMATIONS_KEY = booleanPreferencesKey("reduce_animations")

        // 智能去重
        private val SMART_DEDUPLICATION_ENABLED_KEY = booleanPreferencesKey("smart_deduplication_enabled")
        private val SEPARATE_USERNAME_ACCOUNT_ENABLED_KEY = booleanPreferencesKey("separate_username_account_enabled")
        private val KEEPASS_DX_LIKE_MUTATION_ENABLED_KEY = booleanPreferencesKey("keepass_dx_like_mutation_enabled")
        private val LAST_PASSWORD_CATEGORY_FILTER_TYPE_KEY = stringPreferencesKey("last_password_category_filter_type")
        private val LAST_PASSWORD_CATEGORY_FILTER_PRIMARY_ID_KEY = longPreferencesKey("last_password_category_filter_primary_id")
        private val LAST_PASSWORD_CATEGORY_FILTER_SECONDARY_ID_KEY = longPreferencesKey("last_password_category_filter_secondary_id")
        private val LAST_PASSWORD_CATEGORY_FILTER_TEXT_KEY = stringPreferencesKey("last_password_category_filter_text")
        private val LAST_PASSWORD_CATEGORY_FILTER_GROUP_UUID_KEY = stringPreferencesKey("last_password_category_filter_group_uuid")

        // Bitwarden 同步范围
        private val BITWARDEN_UPLOAD_ALL_KEY = booleanPreferencesKey("bitwarden_upload_all")
        
        private val AUTOFILL_SOURCES_KEY = stringPreferencesKey("autofill_sources")
        private val AUTOFILL_PRIORITY_KEY = stringPreferencesKey("autofill_priority")

        // 分类菜单展开/收缩状态
        private val CATEGORY_MENU_QUICK_FILTERS_EXPANDED_KEY = booleanPreferencesKey("category_menu_quick_filters_expanded")
        private val CATEGORY_MENU_FOLDERS_EXPANDED_KEY = booleanPreferencesKey("category_menu_folders_expanded")

        private val sharedSettingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val sharedSettingsFlowLock = Any()
        @Volatile
        private var sharedSettingsFlow: SharedFlow<AppSettings>? = null
    }

    object StorageTargetScope {
        const val NOTE = "note"
        const val TOTP = "totp"
        const val BANK_CARD = "bank_card"
        const val DOCUMENT = "document"
        const val PASSKEY = "passkey"
    }

    object CategoryFilterScope {
        const val NOTE = "note"
        const val TOTP = "totp"
        const val PASSKEY = "passkey"
        const val CARD_WALLET = "card_wallet"
    }

    private fun storageCategoryKey(scope: String) = longPreferencesKey("last_storage_${scope}_category_id")
    private fun storageKeePassKey(scope: String) = longPreferencesKey("last_storage_${scope}_keepass_database_id")
    private fun storageKeePassGroupPathKey(scope: String) = stringPreferencesKey("last_storage_${scope}_keepass_group_path")
    private fun storageMdbxKey(scope: String) = longPreferencesKey("last_storage_${scope}_mdbx_database_id")
    private fun storageMdbxFolderKey(scope: String) = stringPreferencesKey("last_storage_${scope}_mdbx_folder_id")
    private fun storageBitwardenVaultKey(scope: String) = longPreferencesKey("last_storage_${scope}_bitwarden_vault_id")
    private fun storageBitwardenFolderKey(scope: String) = stringPreferencesKey("last_storage_${scope}_bitwarden_folder_id")

    private fun categoryFilterTypeKey(scope: String) = stringPreferencesKey("last_category_filter_${scope}_type")
    private fun categoryFilterPrimaryKey(scope: String) = longPreferencesKey("last_category_filter_${scope}_primary_id")
    private fun categoryFilterSecondaryKey(scope: String) = longPreferencesKey("last_category_filter_${scope}_secondary_id")
    private fun categoryFilterTextKey(scope: String) = stringPreferencesKey("last_category_filter_${scope}_text")
    private fun categoryFilterGroupUuidKey(scope: String) = stringPreferencesKey("last_category_filter_${scope}_group_uuid")

    private fun fieldsFromMode(
        mode: takagi.ru.monica.data.PasswordCardDisplayMode
    ): List<takagi.ru.monica.data.PasswordCardDisplayField> = when (mode) {
        takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_ONLY -> emptyList()
        takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_USERNAME -> listOf(
            takagi.ru.monica.data.PasswordCardDisplayField.USERNAME
        )
        takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL -> listOf(
            takagi.ru.monica.data.PasswordCardDisplayField.USERNAME,
            takagi.ru.monica.data.PasswordCardDisplayField.WEBSITE
        )
    }

    private fun modeFromFields(
        fields: List<takagi.ru.monica.data.PasswordCardDisplayField>
    ): takagi.ru.monica.data.PasswordCardDisplayMode {
        if (fields.isEmpty()) return takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_ONLY
        if (fields.size == 1 && fields.first() == takagi.ru.monica.data.PasswordCardDisplayField.USERNAME) {
            return takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_USERNAME
        }
        return takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL
    }

    private fun parsePasswordCardDisplayFields(
        raw: String?
    ): List<takagi.ru.monica.data.PasswordCardDisplayField>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching {
                    takagi.ru.monica.data.PasswordCardDisplayField.valueOf(value.trim())
                }.getOrNull()
            }
            .filter { it != takagi.ru.monica.data.PasswordCardDisplayField.APP_NAME }
            .distinct()
        return parsed.ifEmpty { null }
    }

    private fun parseAuthenticatorCardDisplayFields(
        raw: String?
    ): List<takagi.ru.monica.data.AuthenticatorCardDisplayField>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptyList()
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching {
                    takagi.ru.monica.data.AuthenticatorCardDisplayField.valueOf(value.trim())
                }.getOrNull()
            }
            .distinct()
        return parsed
    }

    private fun parsePasswordListTopModulesOrder(
        raw: String?
    ): List<PasswordListTopModule>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching { PasswordListTopModule.valueOf(value.trim()) }.getOrNull()
            }
        if (parsed.isEmpty()) return null
        return PasswordListTopModule.sanitizeOrder(parsed)
    }

    private fun parsePasswordListQuickFilterItems(
        raw: String?
    ): List<PasswordListQuickFilterItem>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptyList()
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching { PasswordListQuickFilterItem.valueOf(value.trim()) }.getOrNull()
            }
        return PasswordListQuickFilterItem.sanitizeOrder(parsed)
    }

    private fun parseAddButtonMenuOrder(
        raw: String?
    ): List<AddButtonMenuAction>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching { AddButtonMenuAction.valueOf(value.trim()) }.getOrNull()
            }
        if (parsed.isEmpty()) return null
        return AddButtonMenuAction.sanitizeOrder(parsed)
    }

    private fun parseAddButtonMenuEnabledActions(
        raw: String?,
        order: List<AddButtonMenuAction>
    ): List<AddButtonMenuAction>? {
        if (raw == null) return null
        if (raw.isBlank()) {
            return listOf(AddButtonMenuAction.PASSWORD)
        }
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching { AddButtonMenuAction.valueOf(value.trim()) }.getOrNull()
            }
        return AddButtonMenuAction.normalizeEnabledActions(parsed, order)
    }

    private fun parsePasswordPageVisibleContentTypes(
        raw: String?
    ): List<PasswordPageContentType>? {
        if (raw == null) return null
        if (raw.isBlank()) return listOf(PasswordPageContentType.PASSWORD)
        val parsed = raw.split(",")
            .mapNotNull { value ->
                runCatching { PasswordPageContentType.valueOf(value.trim()) }.getOrNull()
            }
        return PasswordPageContentType.normalizeEnabledTypes(parsed)
    }

    private fun normalizeCategorySelectionUiMode(mode: CategorySelectionUiMode): CategorySelectionUiMode {
        return when (mode) {
            CategorySelectionUiMode.BOTTOM_SHEET -> CategorySelectionUiMode.CHIP_MENU
            CategorySelectionUiMode.CHIP_MENU -> CategorySelectionUiMode.CHIP_MENU
        }
    }

    private fun normalizeCategorySelectionUiMode(raw: String?): CategorySelectionUiMode {
        val parsed = raw?.trim()?.let { value ->
            runCatching { CategorySelectionUiMode.valueOf(value) }.getOrNull()
        } ?: CategorySelectionUiMode.DEFAULT
        return normalizeCategorySelectionUiMode(parsed)
    }

    private suspend fun migrateLegacyCategorySelectionUiModeIfNeeded() {
        dataStore.edit { preferences ->
            val storedMode = preferences[CATEGORY_SELECTION_UI_MODE_KEY] ?: return@edit
            val normalizedMode = normalizeCategorySelectionUiMode(storedMode)
            if (storedMode != normalizedMode.name) {
                preferences[CATEGORY_SELECTION_UI_MODE_KEY] = normalizedMode.name
            }
        }
    }
    
    val settingsFlow: Flow<AppSettings> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        sharedSettingsFlow ?: synchronized(sharedSettingsFlowLock) {
            sharedSettingsFlow ?: dataStore.data
                .map { preferences -> mapPreferencesToAppSettings(preferences) }
                .distinctUntilChanged()
                .shareIn(
                    scope = sharedSettingsScope,
                    started = SharingStarted.Eagerly,
                    replay = 1
                )
                .also { sharedSettingsFlow = it }
        }
    }

    private fun mapPreferencesToAppSettings(preferences: Preferences): AppSettings {
        val storedOrder = preferences[BOTTOM_NAV_ORDER_KEY]
        val parsedOrder = storedOrder
            ?.split(",")
            ?.mapNotNull { value ->
                runCatching { BottomNavContentTab.valueOf(value) }.getOrNull()
            }
            ?: BottomNavContentTab.DEFAULT_ORDER
        val sanitizedOrder = BottomNavContentTab.sanitizeOrder(parsedOrder)
        val parsedTopModulesOrder = parsePasswordListTopModulesOrder(
            preferences[PASSWORD_LIST_TOP_MODULES_ORDER_KEY]
        ) ?: PasswordListTopModule.DEFAULT_ORDER
        val parsedQuickFilterItems = parsePasswordListQuickFilterItems(
            preferences[PASSWORD_LIST_QUICK_FILTER_ITEMS_KEY]
        ) ?: PasswordListQuickFilterItem.DEFAULT_ORDER
        val parsedAddButtonMenuOrder = parseAddButtonMenuOrder(
            preferences[ADD_BUTTON_MENU_ORDER_KEY]
        ) ?: AddButtonMenuAction.DEFAULT_ORDER
        val parsedAddButtonMenuEnabledActions = parseAddButtonMenuEnabledActions(
            preferences[ADD_BUTTON_MENU_ENABLED_ACTIONS_KEY],
            parsedAddButtonMenuOrder
        ) ?: AddButtonMenuAction.DEFAULT_ENABLED_ACTIONS
        val parsedPasswordPageVisibleContentTypes = parsePasswordPageVisibleContentTypes(
            preferences[PASSWORD_PAGE_VISIBLE_CONTENT_TYPES_KEY]
        ) ?: PasswordPageContentType.DEFAULT_VISIBLE_TYPES

        val isPlusActivated = preferences[IS_PLUS_ACTIVATED_KEY] ?: false

        return AppSettings(
            themeMode = ThemeMode.valueOf(
                preferences[THEME_MODE_KEY] ?: ThemeMode.SYSTEM.name
            ),
            oledPureBlackEnabled = preferences[OLED_PURE_BLACK_ENABLED_KEY] ?: false,
            colorScheme = runCatching {
                ColorScheme.valueOf(
                    preferences[COLOR_SCHEME_KEY] ?: ColorScheme.DEFAULT.name
                )
            }.getOrDefault(ColorScheme.DEFAULT),
            customPrimaryColor = preferences[CUSTOM_PRIMARY_COLOR_KEY] ?: 0xFF6650a4,
            customSecondaryColor = preferences[CUSTOM_SECONDARY_COLOR_KEY] ?: 0xFF625b71,
            customTertiaryColor = preferences[CUSTOM_TERTIARY_COLOR_KEY] ?: 0xFF7D5260,
            customNeutralColor = preferences[CUSTOM_NEUTRAL_COLOR_KEY]
                ?: (preferences[CUSTOM_PRIMARY_COLOR_KEY] ?: 0xFF605D66),
            customNeutralVariantColor = preferences[CUSTOM_NEUTRAL_VARIANT_COLOR_KEY]
                ?: (preferences[CUSTOM_SECONDARY_COLOR_KEY] ?: 0xFF625B71),
            interfaceScalePercent = InterfaceScale.normalizePercent(
                preferences[INTERFACE_SCALE_PERCENT_KEY]
            ),
            language = Language.valueOf(
                preferences[LANGUAGE_KEY] ?: Language.SYSTEM.name
            ),
            biometricEnabled = preferences[BIOMETRIC_ENABLED_KEY] ?: false,
            quickSetupCompleted = preferences[QUICK_SETUP_COMPLETED_KEY] ?: false,
            autoLockMinutes = preferences[AUTO_LOCK_MINUTES_KEY] ?: 5,
            screenshotProtectionEnabled = preferences[SCREENSHOT_PROTECTION_KEY] ?: true,
            clipboardAutoClearSeconds = preferences[CLIPBOARD_AUTO_CLEAR_SECONDS_KEY] ?: 0,
            dynamicColorEnabled = preferences[DYNAMIC_COLOR_ENABLED_KEY] ?: true,
            bottomNavVisibility = BottomNavVisibility(
                vaultV2 = preferences[SHOW_VAULT_V2_TAB_KEY] ?: false,
                passwords = preferences[SHOW_PASSWORDS_TAB_KEY] ?: true,
                authenticator = preferences[SHOW_AUTHENTICATOR_TAB_KEY] ?: true,
                cardWallet = preferences[SHOW_CARD_WALLET_TAB_KEY] ?: true,
                generator = preferences[SHOW_GENERATOR_TAB_KEY] ?: false,
                notes = preferences[SHOW_NOTES_TAB_KEY] ?: false,
                send = preferences[SHOW_SEND_TAB_KEY] ?: false,
                passkey = preferences[SHOW_PASSKEY_TAB_KEY] ?: true,
                steam = preferences[SHOW_STEAM_TAB_KEY] ?: false
            ),
            bottomNavOrder = sanitizedOrder,
            useDraggableBottomNav = preferences[USE_DRAGGABLE_BOTTOM_NAV_KEY] ?: false,
            autoHideBottomNavWhenSingleTab =
                preferences[AUTO_HIDE_BOTTOM_NAV_WHEN_SINGLE_TAB_KEY] ?: false,
            disablePasswordVerification = preferences[DISABLE_PASSWORD_VERIFICATION_KEY] ?: false,
            passkeyHyperOsBiometricBypassEnabled =
                preferences[PASSKEY_HYPEROS_BIOMETRIC_BYPASS_ENABLED_KEY] ?: false,
            bitwardenSyncForensicsEnabled =
                preferences[BITWARDEN_SYNC_FORENSICS_ENABLED_KEY] ?: false,
            bitwardenSyncForensicsDirectoryUri =
                preferences[BITWARDEN_SYNC_FORENSICS_DIRECTORY_URI_KEY],
            bitwardenSyncForensicsRawCaptureEnabled =
                preferences[BITWARDEN_SYNC_FORENSICS_RAW_CAPTURE_ENABLED_KEY] ?: false,
            validatorProgressBarStyle = runCatching {
                val styleString = preferences[VALIDATOR_PROGRESS_BAR_STYLE_KEY]
                    ?: takagi.ru.monica.data.ProgressBarStyle.LINEAR.name
                takagi.ru.monica.data.ProgressBarStyle.valueOf(styleString)
            }.getOrDefault(takagi.ru.monica.data.ProgressBarStyle.LINEAR),
            validatorUnifiedProgressBar = runCatching {
                val modeString = preferences[VALIDATOR_UNIFIED_PROGRESS_BAR_KEY]
                    ?: takagi.ru.monica.data.UnifiedProgressBarMode.ENABLED.name
                takagi.ru.monica.data.UnifiedProgressBarMode.valueOf(modeString)
            }.getOrDefault(takagi.ru.monica.data.UnifiedProgressBarMode.ENABLED),
            validatorSmoothProgress = preferences[VALIDATOR_SMOOTH_PROGRESS_KEY] ?: true,
            validatorVibrationEnabled = preferences[VALIDATOR_VIBRATION_ENABLED_KEY] ?: true,
            hapticFeedbackEnabled = preferences[HAPTIC_FEEDBACK_ENABLED_KEY] ?: true,
            hideFabOnScroll = preferences[HIDE_FAB_ON_SCROLL_KEY] ?: false,
            securityAnalysisAutoEnabled = preferences[SECURITY_ANALYSIS_AUTO_ENABLED_KEY] ?: false,
            passwordDetailSecurityAnalysisEnabled =
                preferences[PASSWORD_DETAIL_SECURITY_ANALYSIS_ENABLED_KEY] ?: true,
            steamMiniProfileBackgroundEnabled =
                preferences[STEAM_MINI_PROFILE_BACKGROUND_ENABLED_KEY] ?: false,
            bitwardenBottomStatusBarEnabled = preferences[BITWARDEN_BOTTOM_STATUS_BAR_ENABLED_KEY] ?: false,
            copyNextCodeWhenExpiring = preferences[COPY_NEXT_CODE_WHEN_EXPIRING_KEY] ?: false,
            // Temporarily hard-disabled for stability.
            notificationValidatorEnabled = false,
            notificationValidatorAutoMatch = false,
            notificationValidatorId = -1L,
            isPlusActivated = isPlusActivated,
            stackCardMode = preferences[STACK_CARD_MODE_KEY] ?: "AUTO",
            walletStackLoopEnabled = preferences[WALLET_STACK_LOOP_ENABLED_KEY] ?: false,
            passwordGroupMode = preferences[PASSWORD_GROUP_MODE_KEY] ?: "smart",
            passwordWebsiteStackMatchMode =
                preferences[PASSWORD_WEBSITE_STACK_MATCH_MODE_KEY] ?: "strict",
            totpTimeOffset = preferences[TOTP_TIME_OFFSET_KEY] ?: 0,
            trashEnabled = preferences[TRASH_ENABLED_KEY] ?: true,
            trashAutoDeleteDays = preferences[TRASH_AUTO_DELETE_DAYS_KEY] ?: 30,
            iconCardsEnabled = preferences[ICON_CARDS_ENABLED_KEY] ?: true,
            appLauncherIcon = runCatching {
                AppLauncherIcon.valueOf(
                    preferences[APP_LAUNCHER_ICON_KEY] ?: AppLauncherIcon.MODERN.name
                )
            }.getOrDefault(AppLauncherIcon.MODERN),
            appLauncherLabel = runCatching {
                AppLauncherLabel.valueOf(
                    preferences[APP_LAUNCHER_LABEL_KEY] ?: AppLauncherLabel.MONICA_PASS.name
                )
            }.getOrDefault(AppLauncherLabel.MONICA_PASS),
            passwordPageIconEnabled = preferences[PASSWORD_PAGE_ICON_ENABLED_KEY]
                ?: (preferences[ICON_CARDS_ENABLED_KEY] ?: true),
            authenticatorPageIconEnabled = preferences[AUTHENTICATOR_PAGE_ICON_ENABLED_KEY]
                ?: (preferences[ICON_CARDS_ENABLED_KEY] ?: true),
            passkeyPageIconEnabled = preferences[PASSKEY_PAGE_ICON_ENABLED_KEY]
                ?: (preferences[ICON_CARDS_ENABLED_KEY] ?: true),
            unmatchedIconHandlingStrategy = runCatching {
                takagi.ru.monica.data.UnmatchedIconHandlingStrategy.valueOf(
                    preferences[UNMATCHED_ICON_HANDLING_STRATEGY_KEY]
                        ?: takagi.ru.monica.data.UnmatchedIconHandlingStrategy.DEFAULT_ICON.name
                )
            }.getOrDefault(takagi.ru.monica.data.UnmatchedIconHandlingStrategy.DEFAULT_ICON),
            passwordCardDisplayMode = runCatching {
                val modeString = preferences[PASSWORD_CARD_DISPLAY_MODE_KEY]
                    ?: takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL.name
                takagi.ru.monica.data.PasswordCardDisplayMode.valueOf(modeString)
            }.getOrDefault(takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL),
            passwordCardDisplayFields = parsePasswordCardDisplayFields(preferences[PASSWORD_CARD_DISPLAY_FIELDS_KEY])
                ?: fieldsFromMode(
                    runCatching {
                        val modeString = preferences[PASSWORD_CARD_DISPLAY_MODE_KEY]
                            ?: takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL.name
                        takagi.ru.monica.data.PasswordCardDisplayMode.valueOf(modeString)
                    }.getOrDefault(takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL)
                ),
            passwordCardShowAuthenticator = preferences[PASSWORD_CARD_SHOW_AUTHENTICATOR_KEY] ?: false,
            passwordCardHideOtherContentWhenAuthenticator = preferences[PASSWORD_CARD_HIDE_OTHER_CONTENT_WHEN_AUTHENTICATOR_KEY] ?: false,
            authenticatorCardDisplayFields = parseAuthenticatorCardDisplayFields(preferences[AUTHENTICATOR_CARD_DISPLAY_FIELDS_KEY])
                ?: takagi.ru.monica.data.AuthenticatorCardDisplayField.DEFAULT_ORDER,
            authenticatorCardHideCodeByDefault =
                preferences[AUTHENTICATOR_CARD_HIDE_CODE_BY_DEFAULT_KEY] ?: false,
            authenticatorLayoutMode = AuthenticatorLayoutMode.fromStoredValue(
                preferences[AUTHENTICATOR_LAYOUT_MODE_KEY]
            ),
            vaultV2LayoutMode = VaultV2LayoutMode.fromStoredValue(
                preferences[VAULT_V2_LAYOUT_MODE_KEY]
            ),
            vaultOverviewEnabled = preferences[VAULT_OVERVIEW_ENABLED_KEY] ?: true,
            vaultOverviewConfig = VaultOverviewConfig.decode(preferences[VAULT_OVERVIEW_CONFIG_KEY]),
            passwordListQuickFiltersEnabled = preferences[PASSWORD_LIST_QUICK_FILTERS_ENABLED_KEY] ?: false,
            passwordListQuickFilterItems = parsedQuickFilterItems,
            passwordListCategoryQuickFiltersEnabled =
                preferences[PASSWORD_LIST_CATEGORY_QUICK_FILTERS_ENABLED_KEY] ?: false,
            passwordListQuickFoldersEnabled = preferences[PASSWORD_LIST_QUICK_FOLDERS_ENABLED_KEY] ?: false,
            passwordListQuickFolderStyle = runCatching {
                PasswordListQuickFolderStyle.valueOf(
                    preferences[PASSWORD_LIST_QUICK_FOLDER_STYLE_KEY]
                        ?: PasswordListQuickFolderStyle.CLASSIC.name
                )
            }.getOrDefault(PasswordListQuickFolderStyle.CLASSIC),
            passwordListQuickFolderPathBannerEnabled =
                preferences[PASSWORD_LIST_QUICK_FOLDER_PATH_BANNER_ENABLED_KEY]
                    ?: (
                        runCatching {
                            PasswordListQuickFolderStyle.valueOf(
                                preferences[PASSWORD_LIST_QUICK_FOLDER_STYLE_KEY]
                                    ?: PasswordListQuickFolderStyle.CLASSIC.name
                            )
                        }.getOrDefault(PasswordListQuickFolderStyle.CLASSIC) ==
                            PasswordListQuickFolderStyle.M3_CARD
                    ),
            passwordListSystemBackToParentFolderEnabled =
                preferences[PASSWORD_LIST_SYSTEM_BACK_TO_PARENT_FOLDER_ENABLED_KEY] ?: false,
            addButtonBehaviorMode = runCatching {
                AddButtonBehaviorMode.valueOf(
                    preferences[ADD_BUTTON_BEHAVIOR_MODE_KEY]
                        ?: AddButtonBehaviorMode.DIRECT_PASSWORD.name
                )
            }.getOrDefault(AddButtonBehaviorMode.DIRECT_PASSWORD),
            addButtonMenuOrder = parsedAddButtonMenuOrder,
            addButtonMenuEnabledActions = parsedAddButtonMenuEnabledActions,
            passwordPageAggregateEnabled =
                preferences[PASSWORD_PAGE_AGGREGATE_ENABLED_KEY] ?: false,
            passwordPageVisibleContentTypes = parsedPasswordPageVisibleContentTypes,
            categorySelectionUiMode = normalizeCategorySelectionUiMode(
                preferences[CATEGORY_SELECTION_UI_MODE_KEY]
            ),
            passwordListQuickAccessEnabled = preferences[PASSWORD_LIST_QUICK_ACCESS_ENABLED_KEY] ?: true,
            passwordListTopModulesOrder = parsedTopModulesOrder,
            passwordSwipeSelectionMode = runCatching {
                PasswordSwipeSelectionMode.valueOf(
                    preferences[PASSWORD_SWIPE_SELECTION_MODE_KEY]
                        ?: PasswordSwipeSelectionMode.DEFAULT.name
                )
            }.getOrDefault(PasswordSwipeSelectionMode.DEFAULT),
            noteGridLayout = preferences[NOTE_GRID_LAYOUT_KEY] ?: true,
            noteCodeBlockCollapseMode = runCatching {
                NoteCodeBlockCollapseMode.valueOf(
                    preferences[NOTE_CODE_BLOCK_COLLAPSE_MODE_KEY]
                        ?: NoteCodeBlockCollapseMode.BALANCED.name
                )
            }.getOrDefault(NoteCodeBlockCollapseMode.BALANCED),
            autofillAuthRequired = preferences[AUTOFILL_AUTH_REQUIRED_KEY] ?: true,
            passwordFieldVisibility = takagi.ru.monica.data.PasswordFieldVisibility(
                securityVerification = preferences[FIELD_SECURITY_VERIFICATION_KEY] ?: true,
                categoryAndNotes = preferences[FIELD_CATEGORY_AND_NOTES_KEY] ?: true,
                appBinding = preferences[FIELD_APP_BINDING_KEY] ?: true,
                personalInfo = preferences[FIELD_PERSONAL_INFO_KEY] ?: true,
                addressInfo = preferences[FIELD_ADDRESS_INFO_KEY] ?: true,
                paymentInfo = preferences[FIELD_PAYMENT_INFO_KEY] ?: true
            ),
            reduceAnimations = preferences[REDUCE_ANIMATIONS_KEY] ?: false,
            smartDeduplicationEnabled = preferences[SMART_DEDUPLICATION_ENABLED_KEY] ?: true,
            separateUsernameAccountEnabled = preferences[SEPARATE_USERNAME_ACCOUNT_ENABLED_KEY] ?: false,
            keepassDxLikeMutationEnabled = preferences[KEEPASS_DX_LIKE_MUTATION_ENABLED_KEY] ?: false,
            lastPasswordCategoryFilterType = preferences[LAST_PASSWORD_CATEGORY_FILTER_TYPE_KEY] ?: "all",
            lastPasswordCategoryFilterPrimaryId = preferences[LAST_PASSWORD_CATEGORY_FILTER_PRIMARY_ID_KEY],
            lastPasswordCategoryFilterSecondaryId = preferences[LAST_PASSWORD_CATEGORY_FILTER_SECONDARY_ID_KEY],
            lastPasswordCategoryFilterText = preferences[LAST_PASSWORD_CATEGORY_FILTER_TEXT_KEY],
            lastPasswordCategoryFilterGroupUuid = preferences[LAST_PASSWORD_CATEGORY_FILTER_GROUP_UUID_KEY],
            bitwardenUploadAll = preferences[BITWARDEN_UPLOAD_ALL_KEY] ?: false,
            autofillSources = runCatching {
                val sourcesStr = preferences[AUTOFILL_SOURCES_KEY] ?: AutofillSource.V1_LOCAL.name
                sourcesStr.split(",").mapNotNull {
                    runCatching { AutofillSource.valueOf(it.trim()) }.getOrNull()
                }.toSet()
            }.getOrDefault(setOf(AutofillSource.V1_LOCAL)),
            autofillPriority = runCatching {
                val priorityStr = preferences[AUTOFILL_PRIORITY_KEY] ?: AutofillSource.V1_LOCAL.name
                priorityStr.split(",").mapNotNull {
                    runCatching { AutofillSource.valueOf(it.trim()) }.getOrNull()
                }
            }.getOrDefault(listOf(AutofillSource.V1_LOCAL))
        )
    }
    
    suspend fun updateThemeMode(themeMode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = themeMode.name
        }
    }

    suspend fun updateOledPureBlackEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[OLED_PURE_BLACK_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateColorScheme(colorScheme: ColorScheme) {
        dataStore.edit { preferences ->
            preferences[COLOR_SCHEME_KEY] = colorScheme.name
        }
    }

    suspend fun updateInterfaceScalePercent(percent: Int) {
        dataStore.edit { preferences ->
            preferences[INTERFACE_SCALE_PERCENT_KEY] = InterfaceScale.normalizePercent(percent)
        }
    }
    
    suspend fun updateLanguage(language: Language) {
        dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language.name
        }
        StartupLanguageCache.write(context, language)
        refreshLauncherEntryForLanguage()
    }

    /**
     * 芝士雪豹语会连带替换桌面图标。语言变更后按最新语言与现有图标/名称偏好
     * 重新应用可见启动入口；切换失败不影响语言本身生效。
     */
    private suspend fun refreshLauncherEntryForLanguage() {
        runCatching {
            val settings = settingsFlow.first()
            AppLauncherIconManager.apply(context, settings.appLauncherIcon, settings.appLauncherLabel)
        }
    }

    suspend fun updateBitwardenUploadAll(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[BITWARDEN_UPLOAD_ALL_KEY] = enabled
        }
    }
    
    suspend fun updateBiometricEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateQuickSetupCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[QUICK_SETUP_COMPLETED_KEY] = completed
        }
    }
    
    suspend fun updateAutoLockMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[AUTO_LOCK_MINUTES_KEY] = minutes
        }
    }
    
    suspend fun updateScreenshotProtectionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SCREENSHOT_PROTECTION_KEY] = enabled
        }
    }

    suspend fun updateClipboardAutoClearSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[CLIPBOARD_AUTO_CLEAR_SECONDS_KEY] = seconds.coerceAtLeast(0)
        }
    }

    suspend fun updateDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateBottomNavVisibility(tab: BottomNavContentTab, visible: Boolean) {
        dataStore.edit { preferences ->
            when (tab) {
                BottomNavContentTab.VAULT_V2 -> preferences[SHOW_VAULT_V2_TAB_KEY] = visible
                // BottomNavContentTab.VAULT -> preferences[SHOW_VAULT_TAB_KEY] = visible
                BottomNavContentTab.PASSWORDS -> preferences[SHOW_PASSWORDS_TAB_KEY] = visible
                BottomNavContentTab.AUTHENTICATOR -> {
                    preferences[SHOW_AUTHENTICATOR_TAB_KEY] = visible
                    preferences[SHOW_PASSKEY_TAB_KEY] = visible
                }
                BottomNavContentTab.CARD_WALLET -> preferences[SHOW_CARD_WALLET_TAB_KEY] = visible
                BottomNavContentTab.GENERATOR -> preferences[SHOW_GENERATOR_TAB_KEY] = visible
                BottomNavContentTab.NOTES -> preferences[SHOW_NOTES_TAB_KEY] = visible
                BottomNavContentTab.SEND -> preferences[SHOW_SEND_TAB_KEY] = visible
                BottomNavContentTab.PASSKEY -> preferences[SHOW_PASSKEY_TAB_KEY] = visible
                BottomNavContentTab.STEAM -> preferences[SHOW_STEAM_TAB_KEY] = visible
            }
        }
    }

    suspend fun updateBottomNavOrder(order: List<BottomNavContentTab>) {
        val sanitizedOrder = BottomNavContentTab.sanitizeOrder(order)
        dataStore.edit { preferences ->
            preferences[BOTTOM_NAV_ORDER_KEY] = sanitizedOrder.joinToString(",") { it.name }
        }
    }

    /** A preset is one transaction, so observers never see a partially configured Dock. */
    suspend fun applyQuickSetupPreset(preset: QuickSetupPreset) {
        dataStore.edit { preferences ->
            preferences.writeQuickSetupNavigation(preset.tabs)
            preset.vaultOverviewEnabled?.let { enabled ->
                preferences[VAULT_OVERVIEW_ENABLED_KEY] = enabled
                preferences[VAULT_V2_LAYOUT_MODE_KEY] = VaultV2LayoutMode.CLASSIC.name
            }
            if (BottomNavContentTab.PASSWORDS in preset.tabs) {
                preferences[PASSWORD_PAGE_AGGREGATE_ENABLED_KEY] = false
            }
        }
    }

    suspend fun updateQuickSetupNavigation(order: List<BottomNavContentTab>, tabs: List<BottomNavContentTab>) {
        require(tabs.isNotEmpty())
        dataStore.edit { preferences -> preferences.writeQuickSetupNavigation(tabs, order) }
    }

    private fun MutablePreferences.writeQuickSetupNavigation(
        tabs: List<BottomNavContentTab>, order: List<BottomNavContentTab> = tabs,
    ) {
        val visibility = quickSetupNavigationVisibility(tabs)
        this[BOTTOM_NAV_ORDER_KEY] = BottomNavContentTab.sanitizeOrder(order).joinToString(",") { it.name }
        this[SHOW_VAULT_V2_TAB_KEY] = visibility.vaultV2
        this[SHOW_PASSWORDS_TAB_KEY] = visibility.passwords
        this[SHOW_AUTHENTICATOR_TAB_KEY] = visibility.authenticator
        this[SHOW_CARD_WALLET_TAB_KEY] = visibility.cardWallet
        this[SHOW_GENERATOR_TAB_KEY] = visibility.generator
        this[SHOW_NOTES_TAB_KEY] = visibility.notes
        this[SHOW_SEND_TAB_KEY] = visibility.send
        this[SHOW_PASSKEY_TAB_KEY] = visibility.passkey
        this[SHOW_STEAM_TAB_KEY] = visibility.steam
        this[AUTO_HIDE_BOTTOM_NAV_WHEN_SINGLE_TAB_KEY] = false
    }

    suspend fun updateUseDraggableBottomNav(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[USE_DRAGGABLE_BOTTOM_NAV_KEY] = enabled
        }
    }

    suspend fun updateAutoHideBottomNavWhenSingleTab(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_HIDE_BOTTOM_NAV_WHEN_SINGLE_TAB_KEY] = enabled
        }
    }

    suspend fun updateCustomColors(
        primary: Long,
        secondary: Long,
        tertiary: Long,
        neutral: Long = primary,
        neutralVariant: Long = secondary
    ) {
        dataStore.edit { preferences ->
            preferences[CUSTOM_PRIMARY_COLOR_KEY] = primary
            preferences[CUSTOM_SECONDARY_COLOR_KEY] = secondary
            preferences[CUSTOM_TERTIARY_COLOR_KEY] = tertiary
            preferences[CUSTOM_NEUTRAL_COLOR_KEY] = neutral
            preferences[CUSTOM_NEUTRAL_VARIANT_COLOR_KEY] = neutralVariant
        }
    }

    suspend fun updateDisablePasswordVerification(disabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[DISABLE_PASSWORD_VERIFICATION_KEY] = disabled
        }
    }

    suspend fun updatePasskeyHyperOsBiometricBypassEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSKEY_HYPEROS_BIOMETRIC_BYPASS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateBitwardenSyncForensicsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[BITWARDEN_SYNC_FORENSICS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateBitwardenSyncForensicsDirectoryUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(BITWARDEN_SYNC_FORENSICS_DIRECTORY_URI_KEY)
            } else {
                preferences[BITWARDEN_SYNC_FORENSICS_DIRECTORY_URI_KEY] = uri
            }
        }
    }

    suspend fun updateBitwardenSyncForensicsRawCaptureEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[BITWARDEN_SYNC_FORENSICS_RAW_CAPTURE_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateValidatorProgressBarStyle(style: takagi.ru.monica.data.ProgressBarStyle) {
        android.util.Log.d("SettingsManager", "Saving progress bar style: ${style.name}")
        dataStore.edit { preferences ->
            preferences[VALIDATOR_PROGRESS_BAR_STYLE_KEY] = style.name
        }
        android.util.Log.d("SettingsManager", "Progress bar style saved to DataStore")
    }

    suspend fun updateValidatorVibrationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VALIDATOR_VIBRATION_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateHapticFeedbackEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAPTIC_FEEDBACK_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateBitwardenBottomStatusBarEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[BITWARDEN_BOTTOM_STATUS_BAR_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateHideFabOnScroll(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[HIDE_FAB_ON_SCROLL_KEY] = enabled
        }
    }

    suspend fun updateSecurityAnalysisAutoEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SECURITY_ANALYSIS_AUTO_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordDetailSecurityAnalysisEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_DETAIL_SECURITY_ANALYSIS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateSteamMiniProfileBackgroundEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[STEAM_MINI_PROFILE_BACKGROUND_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateCopyNextCodeWhenExpiring(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COPY_NEXT_CODE_WHEN_EXPIRING_KEY] = enabled
        }
    }

    suspend fun updateNotificationValidatorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[NOTIFICATION_VALIDATOR_ENABLED_KEY] = false
        }
    }

    suspend fun updateNotificationValidatorAutoMatch(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[NOTIFICATION_VALIDATOR_AUTO_MATCH_KEY] = false
        }
    }

    suspend fun updateNotificationValidatorId(id: Long) {
        dataStore.edit { preferences ->
            preferences[NOTIFICATION_VALIDATOR_ID_KEY] = -1L
        }
    }

    suspend fun updatePlusActivated(activated: Boolean) {
        dataStore.edit { preferences ->
            preferences[IS_PLUS_ACTIVATED_KEY] = activated
        }
    }

    suspend fun updateStackCardMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[STACK_CARD_MODE_KEY] = mode
        }
    }

    suspend fun updateWalletStackLoopEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[WALLET_STACK_LOOP_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordGroupMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_GROUP_MODE_KEY] = mode
        }
    }

    suspend fun updatePasswordWebsiteStackMatchMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_WEBSITE_STACK_MATCH_MODE_KEY] = mode
        }
    }

    suspend fun updateTotpTimeOffset(offset: Int) {
        dataStore.edit { preferences ->
            preferences[TOTP_TIME_OFFSET_KEY] = offset
        }
    }

    suspend fun updateTrashEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[TRASH_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateTrashAutoDeleteDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[TRASH_AUTO_DELETE_DAYS_KEY] = days
        }
    }

    suspend fun updateValidatorUnifiedProgressBar(mode: takagi.ru.monica.data.UnifiedProgressBarMode) {
        dataStore.edit { preferences ->
            preferences[VALIDATOR_UNIFIED_PROGRESS_BAR_KEY] = mode.name
        }
    }

    suspend fun updateValidatorSmoothProgress(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VALIDATOR_SMOOTH_PROGRESS_KEY] = enabled
        }
    }

    suspend fun updateIconCardsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[ICON_CARDS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateAppLauncherIcon(icon: AppLauncherIcon) {
        dataStore.edit { preferences ->
            preferences[APP_LAUNCHER_ICON_KEY] = icon.name
        }
        val label = settingsFlow.first().appLauncherLabel
        AppLauncherIconManager.apply(this.context, icon, label)
    }

    suspend fun updateAppLauncherLabel(label: AppLauncherLabel) {
        dataStore.edit { preferences ->
            preferences[APP_LAUNCHER_LABEL_KEY] = label.name
        }
        val icon = settingsFlow.first().appLauncherIcon
        AppLauncherIconManager.apply(this.context, icon, label)
    }

    suspend fun updatePasswordPageIconEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_PAGE_ICON_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateAuthenticatorPageIconEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTHENTICATOR_PAGE_ICON_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasskeyPageIconEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSKEY_PAGE_ICON_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateUnmatchedIconHandlingStrategy(
        strategy: takagi.ru.monica.data.UnmatchedIconHandlingStrategy
    ) {
        dataStore.edit { preferences ->
            preferences[UNMATCHED_ICON_HANDLING_STRATEGY_KEY] = strategy.name
        }
    }

    suspend fun updatePasswordCardDisplayMode(mode: takagi.ru.monica.data.PasswordCardDisplayMode) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_CARD_DISPLAY_MODE_KEY] = mode.name
            preferences[PASSWORD_CARD_DISPLAY_FIELDS_KEY] = fieldsFromMode(mode).joinToString(",") { it.name }
        }
    }

    suspend fun updatePasswordCardDisplayFields(fields: List<takagi.ru.monica.data.PasswordCardDisplayField>) {
        val normalizedFields = fields
            .filter { it != takagi.ru.monica.data.PasswordCardDisplayField.APP_NAME }
            .distinct()
        dataStore.edit { preferences ->
            preferences[PASSWORD_CARD_DISPLAY_FIELDS_KEY] = normalizedFields.joinToString(",") { it.name }
            preferences[PASSWORD_CARD_DISPLAY_MODE_KEY] = modeFromFields(normalizedFields).name
        }
    }

    suspend fun updatePasswordCardShowAuthenticator(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_CARD_SHOW_AUTHENTICATOR_KEY] = show
        }
    }

    suspend fun updatePasswordCardHideOtherContentWhenAuthenticator(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_CARD_HIDE_OTHER_CONTENT_WHEN_AUTHENTICATOR_KEY] = enabled
        }
    }

    suspend fun updateAuthenticatorCardDisplayFields(fields: List<takagi.ru.monica.data.AuthenticatorCardDisplayField>) {
        val normalizedFields = fields.distinct()
        dataStore.edit { preferences ->
            preferences[AUTHENTICATOR_CARD_DISPLAY_FIELDS_KEY] = normalizedFields.joinToString(",") { it.name }
        }
    }

    suspend fun updateAuthenticatorCardHideCodeByDefault(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTHENTICATOR_CARD_HIDE_CODE_BY_DEFAULT_KEY] = enabled
        }
    }

    suspend fun updateAuthenticatorLayoutMode(mode: AuthenticatorLayoutMode) {
        dataStore.edit { preferences ->
            preferences[AUTHENTICATOR_LAYOUT_MODE_KEY] = mode.name
        }
    }

    suspend fun updateVaultV2LayoutMode(mode: VaultV2LayoutMode) {
        dataStore.edit { preferences ->
            preferences[VAULT_V2_LAYOUT_MODE_KEY] = mode.name
        }
    }

    suspend fun updateVaultOverviewEnabled(enabled: Boolean) {
        dataStore.edit { it[VAULT_OVERVIEW_ENABLED_KEY] = enabled }
    }

    suspend fun updateVaultOverviewConfig(transform: (VaultOverviewConfig) -> VaultOverviewConfig) {
        dataStore.edit { preferences ->
            preferences[VAULT_OVERVIEW_CONFIG_KEY] =
                transform(VaultOverviewConfig.decode(preferences[VAULT_OVERVIEW_CONFIG_KEY])).encode()
        }
    }

    suspend fun updatePasswordListQuickFiltersEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_FILTERS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordListQuickFilterItems(items: List<PasswordListQuickFilterItem>) {
        val normalizedItems = items.distinct()
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_FILTER_ITEMS_KEY] =
                normalizedItems.joinToString(",") { it.name }
        }
    }

    suspend fun updatePasswordListCategoryQuickFiltersEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_CATEGORY_QUICK_FILTERS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordListQuickFoldersEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_FOLDERS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordListQuickFolderStyle(style: PasswordListQuickFolderStyle) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_FOLDER_STYLE_KEY] = style.name
        }
    }

    suspend fun updatePasswordListQuickFolderPathBannerEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_FOLDER_PATH_BANNER_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordListSystemBackToParentFolderEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_SYSTEM_BACK_TO_PARENT_FOLDER_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateAddButtonBehaviorMode(mode: AddButtonBehaviorMode) {
        dataStore.edit { preferences ->
            preferences[ADD_BUTTON_BEHAVIOR_MODE_KEY] = mode.name
        }
    }

    suspend fun updateAddButtonMenuOrder(order: List<AddButtonMenuAction>) {
        dataStore.edit { preferences ->
            val normalizedOrder = AddButtonMenuAction.sanitizeOrder(order)
            preferences[ADD_BUTTON_MENU_ORDER_KEY] =
                normalizedOrder.joinToString(",") { it.name }
        }
    }

    suspend fun updateAddButtonMenuEnabledActions(actions: List<AddButtonMenuAction>) {
        dataStore.edit { preferences ->
            val currentOrder = parseAddButtonMenuOrder(preferences[ADD_BUTTON_MENU_ORDER_KEY])
                ?: AddButtonMenuAction.DEFAULT_ORDER
            val normalizedActions = AddButtonMenuAction.normalizeEnabledActions(actions, currentOrder)
            preferences[ADD_BUTTON_MENU_ENABLED_ACTIONS_KEY] =
                normalizedActions.joinToString(",") { it.name }
        }
    }

    suspend fun updatePasswordPageAggregateEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_PAGE_AGGREGATE_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordPageVisibleContentTypes(types: List<PasswordPageContentType>) {
        dataStore.edit { preferences ->
            val normalizedTypes = PasswordPageContentType.normalizeEnabledTypes(types)
            preferences[PASSWORD_PAGE_VISIBLE_CONTENT_TYPES_KEY] =
                normalizedTypes.joinToString(",") { it.name }
        }
    }

    suspend fun updateCategorySelectionUiMode(mode: CategorySelectionUiMode) {
        dataStore.edit { preferences ->
            preferences[CATEGORY_SELECTION_UI_MODE_KEY] =
                normalizeCategorySelectionUiMode(mode).name
        }
    }

    suspend fun updatePasswordListQuickAccessEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_ACCESS_ENABLED_KEY] = enabled
        }
    }

    suspend fun updatePasswordListTopModulesOrder(order: List<PasswordListTopModule>) {
        val sanitizedOrder = PasswordListTopModule.sanitizeOrder(order)
        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_TOP_MODULES_ORDER_KEY] =
                sanitizedOrder.joinToString(",") { it.name }
        }
    }

    suspend fun updatePasswordSwipeSelectionMode(mode: PasswordSwipeSelectionMode) {
        dataStore.edit { preferences ->
            preferences[PASSWORD_SWIPE_SELECTION_MODE_KEY] = mode.name
        }
    }

    suspend fun updateNoteGridLayout(isGrid: Boolean) {
        dataStore.edit { preferences ->
            preferences[NOTE_GRID_LAYOUT_KEY] = isGrid
        }
    }

    suspend fun updateNoteCodeBlockCollapseMode(mode: NoteCodeBlockCollapseMode) {
        dataStore.edit { preferences ->
            preferences[NOTE_CODE_BLOCK_COLLAPSE_MODE_KEY] = mode.name
        }
    }

    suspend fun updateAutofillAuthRequired(required: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTOFILL_AUTH_REQUIRED_KEY] = required
        }
    }

    suspend fun updatePasswordFieldVisibility(field: String, visible: Boolean) {
        dataStore.edit { preferences ->
            when (field) {
                "securityVerification" -> preferences[FIELD_SECURITY_VERIFICATION_KEY] = visible
                "categoryAndNotes" -> preferences[FIELD_CATEGORY_AND_NOTES_KEY] = visible
                "appBinding" -> preferences[FIELD_APP_BINDING_KEY] = visible
                "personalInfo" -> preferences[FIELD_PERSONAL_INFO_KEY] = visible
                "addressInfo" -> preferences[FIELD_ADDRESS_INFO_KEY] = visible
                "paymentInfo" -> preferences[FIELD_PAYMENT_INFO_KEY] = visible
            }
        }
    }

    suspend fun exportPageAdjustmentSettings(): PageAdjustmentSettingsSnapshot {
        val settings = settingsFlow.first()
        val normalizedPresetCustomFieldsJson = runCatching {
            val rawPresetCustomFieldsJson = dataStore.data.first()[PRESET_CUSTOM_FIELDS_KEY] ?: "[]"
            PresetCustomField.listToJson(PresetCustomField.listFromJson(rawPresetCustomFieldsJson))
        }.getOrDefault("[]")
        return PageAdjustmentSettingsSnapshot(
            passwordListQuickFiltersEnabled = settings.passwordListQuickFiltersEnabled,
            passwordListQuickFilterItems = settings.passwordListQuickFilterItems.map { it.name },
            passwordListCategoryQuickFiltersEnabled = settings.passwordListCategoryQuickFiltersEnabled,
            passwordListQuickFoldersEnabled = settings.passwordListQuickFoldersEnabled,
            passwordListQuickFolderStyle = settings.passwordListQuickFolderStyle.name,
            passwordListQuickFolderPathBannerEnabled = settings.passwordListQuickFolderPathBannerEnabled,
            passwordListSystemBackToParentFolderEnabled =
                settings.passwordListSystemBackToParentFolderEnabled,
            addButtonBehaviorMode = settings.addButtonBehaviorMode.name,
            addButtonMenuOrder = settings.addButtonMenuOrder.map { it.name },
            addButtonMenuEnabledActions = settings.addButtonMenuEnabledActions.map { it.name },
            passwordPageAggregateEnabled = settings.passwordPageAggregateEnabled,
            passwordPageVisibleContentTypes = settings.passwordPageVisibleContentTypes.map { it.name },
            categorySelectionUiMode = settings.categorySelectionUiMode.name,
            colorSettingsVersion = 1,
            oledPureBlackEnabled = settings.oledPureBlackEnabled,
            colorScheme = settings.colorScheme.name,
            customPrimaryColor = settings.customPrimaryColor,
            customSecondaryColor = settings.customSecondaryColor,
            customTertiaryColor = settings.customTertiaryColor,
            customNeutralColor = settings.customNeutralColor,
            customNeutralVariantColor = settings.customNeutralVariantColor,
            bottomNavSettingsVersion = 1,
            bottomNavOrder = settings.bottomNavOrder.map { it.name },
            bottomNavVisibilityVaultV2 = settings.bottomNavVisibility.vaultV2,
            bottomNavVisibilityPasswords = settings.bottomNavVisibility.passwords,
            bottomNavVisibilityAuthenticator = settings.bottomNavVisibility.authenticator,
            bottomNavVisibilityCardWallet = settings.bottomNavVisibility.cardWallet,
            bottomNavVisibilityGenerator = settings.bottomNavVisibility.generator,
            bottomNavVisibilityNotes = settings.bottomNavVisibility.notes,
            bottomNavVisibilitySend = settings.bottomNavVisibility.send,
            bottomNavVisibilityPasskey = settings.bottomNavVisibility.passkey,
            bottomNavVisibilitySteam = settings.bottomNavVisibility.steam,
            useDraggableBottomNav = settings.useDraggableBottomNav,
            autoHideBottomNavWhenSingleTab = settings.autoHideBottomNavWhenSingleTab,
            passwordListQuickAccessEnabled = settings.passwordListQuickAccessEnabled,
            passwordListTopModulesOrder = settings.passwordListTopModulesOrder.map { it.name },
            passwordCardDisplayMode = settings.passwordCardDisplayMode.name,
            passwordCardDisplayFields = settings.passwordCardDisplayFields.map { it.name },
            passwordCardShowAuthenticator = settings.passwordCardShowAuthenticator,
            passwordCardHideOtherContentWhenAuthenticator =
                settings.passwordCardHideOtherContentWhenAuthenticator,
            stackCardMode = settings.stackCardMode,
            walletStackLoopEnabled = settings.walletStackLoopEnabled,
            passwordGroupMode = settings.passwordGroupMode,
            passwordWebsiteStackMatchMode = settings.passwordWebsiteStackMatchMode,
            authenticatorCardDisplayFields = settings.authenticatorCardDisplayFields.map { it.name },
            authenticatorCardHideCodeByDefault = settings.authenticatorCardHideCodeByDefault,
            authenticatorLayoutMode = settings.authenticatorLayoutMode.name,
            vaultV2LayoutMode = settings.vaultV2LayoutMode.name,
            vaultOverviewEnabled = settings.vaultOverviewEnabled,
            vaultOverviewConfig = settings.vaultOverviewConfig.encode(),
            validatorProgressBarStyle = settings.validatorProgressBarStyle.name,
            validatorUnifiedProgressBar = settings.validatorUnifiedProgressBar.name,
            validatorSmoothProgress = settings.validatorSmoothProgress,
            validatorVibrationEnabled = settings.validatorVibrationEnabled,
            hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
            copyNextCodeWhenExpiring = settings.copyNextCodeWhenExpiring,
            securityAnalysisAutoEnabled = settings.securityAnalysisAutoEnabled,
            passwordDetailSecurityAnalysisEnabled = settings.passwordDetailSecurityAnalysisEnabled,
            steamMiniProfileBackgroundEnabled = settings.steamMiniProfileBackgroundEnabled,
            autofillAuthRequired = settings.autofillAuthRequired,
            iconCardsEnabled = settings.iconCardsEnabled,
            appLauncherIcon = settings.appLauncherIcon.name,
            appLauncherLabel = settings.appLauncherLabel.name,
            passwordPageIconEnabled = settings.passwordPageIconEnabled,
            authenticatorPageIconEnabled = settings.authenticatorPageIconEnabled,
            passkeyPageIconEnabled = settings.passkeyPageIconEnabled,
            unmatchedIconHandlingStrategy = settings.unmatchedIconHandlingStrategy.name,
            passwordFieldSettingsVersion = 1,
            separateUsernameAccountEnabled = settings.separateUsernameAccountEnabled,
            presetCustomFieldsJson = normalizedPresetCustomFieldsJson,
            passwordFieldVisibility = PageAdjustmentPasswordFieldVisibilitySnapshot(
                securityVerification = settings.passwordFieldVisibility.securityVerification,
                categoryAndNotes = settings.passwordFieldVisibility.categoryAndNotes,
                appBinding = settings.passwordFieldVisibility.appBinding,
                personalInfo = settings.passwordFieldVisibility.personalInfo,
                addressInfo = settings.passwordFieldVisibility.addressInfo,
                paymentInfo = settings.passwordFieldVisibility.paymentInfo,
            ),
        )
    }

    suspend fun importPageAdjustmentSettings(snapshot: PageAdjustmentSettingsSnapshot) {
        val parsedQuickFilterItems = PasswordListQuickFilterItem.sanitizeOrder(
            snapshot.passwordListQuickFilterItems.mapNotNull { value ->
                runCatching { PasswordListQuickFilterItem.valueOf(value.trim()) }.getOrNull()
            }
        )
        val parsedTopModules = PasswordListTopModule.sanitizeOrder(
            snapshot.passwordListTopModulesOrder.mapNotNull { value ->
                runCatching { PasswordListTopModule.valueOf(value.trim()) }.getOrNull()
            }
        )
        val parsedPasswordCardMode = runCatching {
            takagi.ru.monica.data.PasswordCardDisplayMode.valueOf(snapshot.passwordCardDisplayMode.trim())
        }.getOrDefault(takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL)
        val parsedPasswordCardFields = snapshot.passwordCardDisplayFields.mapNotNull { value ->
            runCatching { takagi.ru.monica.data.PasswordCardDisplayField.valueOf(value.trim()) }.getOrNull()
        }.distinct()
        val normalizedPasswordCardFields = parsedPasswordCardFields.ifEmpty {
            fieldsFromMode(parsedPasswordCardMode)
        }
        val parsedAuthenticatorCardFields = snapshot.authenticatorCardDisplayFields.mapNotNull { value ->
            runCatching { takagi.ru.monica.data.AuthenticatorCardDisplayField.valueOf(value.trim()) }.getOrNull()
        }.distinct()
        val normalizedAuthenticatorCardFields = if (parsedAuthenticatorCardFields.isEmpty()) {
            takagi.ru.monica.data.AuthenticatorCardDisplayField.DEFAULT_ORDER
        } else {
            parsedAuthenticatorCardFields
        }
        val parsedValidatorProgressBarStyle = runCatching {
            ProgressBarStyle.valueOf(snapshot.validatorProgressBarStyle.trim())
        }.getOrDefault(ProgressBarStyle.LINEAR)
        val parsedValidatorUnifiedProgressBar = runCatching {
            UnifiedProgressBarMode.valueOf(snapshot.validatorUnifiedProgressBar.trim())
        }.getOrDefault(UnifiedProgressBarMode.ENABLED)
        val parsedQuickFolderStyle = runCatching {
            PasswordListQuickFolderStyle.valueOf(snapshot.passwordListQuickFolderStyle.trim())
        }.getOrDefault(PasswordListQuickFolderStyle.CLASSIC)
        val parsedAddButtonBehaviorMode = runCatching {
            AddButtonBehaviorMode.valueOf(snapshot.addButtonBehaviorMode.trim())
        }.getOrDefault(AddButtonBehaviorMode.DIRECT_PASSWORD)
        val parsedAddButtonOrder = AddButtonMenuAction.sanitizeOrder(
            snapshot.addButtonMenuOrder.mapNotNull { value ->
                runCatching { AddButtonMenuAction.valueOf(value.trim()) }.getOrNull()
            }
        )
        val normalizedAddButtonOrder = if (parsedAddButtonOrder.isEmpty()) {
            AddButtonMenuAction.DEFAULT_ORDER
        } else {
            parsedAddButtonOrder
        }
        val normalizedAddButtonEnabledActions = AddButtonMenuAction.normalizeEnabledActions(
            snapshot.addButtonMenuEnabledActions.mapNotNull { value ->
                runCatching { AddButtonMenuAction.valueOf(value.trim()) }.getOrNull()
            },
            normalizedAddButtonOrder
        )
        val normalizedPasswordPageVisibleContentTypes = PasswordPageContentType.normalizeEnabledTypes(
            snapshot.passwordPageVisibleContentTypes.mapNotNull { value ->
                runCatching { PasswordPageContentType.valueOf(value.trim()) }.getOrNull()
            }
        )
        val shouldRestoreColorSettings = snapshot.colorSettingsVersion > 0
        val parsedColorScheme = runCatching {
            ColorScheme.valueOf(snapshot.colorScheme.trim())
        }.getOrDefault(ColorScheme.DEFAULT)
        val parsedBottomNavOrder = BottomNavContentTab.sanitizeOrder(
            snapshot.bottomNavOrder.mapNotNull { value ->
                runCatching { BottomNavContentTab.valueOf(value.trim()) }.getOrNull()
            }
        )
        val shouldRestoreBottomNavSettings = snapshot.bottomNavSettingsVersion > 0
        val parsedCategorySelectionUiMode = normalizeCategorySelectionUiMode(
            snapshot.categorySelectionUiMode
        )
        val shouldRestorePasswordFieldSettings = snapshot.passwordFieldSettingsVersion > 0
        val normalizedPresetCustomFieldsJson = runCatching {
            PresetCustomField.listToJson(PresetCustomField.listFromJson(snapshot.presetCustomFieldsJson))
        }.getOrDefault("[]")
        val parsedUnmatchedIconStrategy = runCatching {
            takagi.ru.monica.data.UnmatchedIconHandlingStrategy.valueOf(
                snapshot.unmatchedIconHandlingStrategy.trim()
            )
        }.getOrDefault(takagi.ru.monica.data.UnmatchedIconHandlingStrategy.DEFAULT_ICON)

        dataStore.edit { preferences ->
            preferences[PASSWORD_LIST_QUICK_FILTERS_ENABLED_KEY] = snapshot.passwordListQuickFiltersEnabled
            preferences[PASSWORD_LIST_QUICK_FILTER_ITEMS_KEY] =
                parsedQuickFilterItems.joinToString(",") { it.name }
            preferences[PASSWORD_LIST_CATEGORY_QUICK_FILTERS_ENABLED_KEY] =
                snapshot.passwordListCategoryQuickFiltersEnabled
            preferences[PASSWORD_LIST_QUICK_FOLDERS_ENABLED_KEY] = snapshot.passwordListQuickFoldersEnabled
            preferences[PASSWORD_LIST_QUICK_FOLDER_STYLE_KEY] = parsedQuickFolderStyle.name
            preferences[PASSWORD_LIST_QUICK_FOLDER_PATH_BANNER_ENABLED_KEY] =
                snapshot.passwordListQuickFolderPathBannerEnabled
            preferences[PASSWORD_LIST_SYSTEM_BACK_TO_PARENT_FOLDER_ENABLED_KEY] =
                snapshot.passwordListSystemBackToParentFolderEnabled
            preferences[ADD_BUTTON_BEHAVIOR_MODE_KEY] = parsedAddButtonBehaviorMode.name
            preferences[ADD_BUTTON_MENU_ORDER_KEY] =
                normalizedAddButtonOrder.joinToString(",") { it.name }
            preferences[ADD_BUTTON_MENU_ENABLED_ACTIONS_KEY] =
                normalizedAddButtonEnabledActions.joinToString(",") { it.name }
            preferences[PASSWORD_PAGE_AGGREGATE_ENABLED_KEY] = snapshot.passwordPageAggregateEnabled
            preferences[PASSWORD_PAGE_VISIBLE_CONTENT_TYPES_KEY] =
                normalizedPasswordPageVisibleContentTypes.joinToString(",") { it.name }
            preferences[CATEGORY_SELECTION_UI_MODE_KEY] = parsedCategorySelectionUiMode.name
            if (shouldRestoreColorSettings) {
                preferences[OLED_PURE_BLACK_ENABLED_KEY] = snapshot.oledPureBlackEnabled
                preferences[COLOR_SCHEME_KEY] = parsedColorScheme.name
                preferences[CUSTOM_PRIMARY_COLOR_KEY] = snapshot.customPrimaryColor
                preferences[CUSTOM_SECONDARY_COLOR_KEY] = snapshot.customSecondaryColor
                preferences[CUSTOM_TERTIARY_COLOR_KEY] = snapshot.customTertiaryColor
                preferences[CUSTOM_NEUTRAL_COLOR_KEY] = snapshot.customNeutralColor
                preferences[CUSTOM_NEUTRAL_VARIANT_COLOR_KEY] = snapshot.customNeutralVariantColor
            }
            if (shouldRestoreBottomNavSettings) {
                preferences[BOTTOM_NAV_ORDER_KEY] =
                    parsedBottomNavOrder.joinToString(",") { it.name }
                preferences[SHOW_VAULT_V2_TAB_KEY] = snapshot.bottomNavVisibilityVaultV2
                preferences[SHOW_PASSWORDS_TAB_KEY] = snapshot.bottomNavVisibilityPasswords
                preferences[SHOW_AUTHENTICATOR_TAB_KEY] = snapshot.bottomNavVisibilityAuthenticator
                preferences[SHOW_CARD_WALLET_TAB_KEY] = snapshot.bottomNavVisibilityCardWallet
                preferences[SHOW_GENERATOR_TAB_KEY] = snapshot.bottomNavVisibilityGenerator
                preferences[SHOW_NOTES_TAB_KEY] = snapshot.bottomNavVisibilityNotes
                preferences[SHOW_SEND_TAB_KEY] = snapshot.bottomNavVisibilitySend
                preferences[SHOW_PASSKEY_TAB_KEY] = snapshot.bottomNavVisibilityPasskey
                preferences[SHOW_STEAM_TAB_KEY] = snapshot.bottomNavVisibilitySteam
                preferences[USE_DRAGGABLE_BOTTOM_NAV_KEY] = snapshot.useDraggableBottomNav
                preferences[AUTO_HIDE_BOTTOM_NAV_WHEN_SINGLE_TAB_KEY] =
                    snapshot.autoHideBottomNavWhenSingleTab
            }
            preferences[PASSWORD_LIST_QUICK_ACCESS_ENABLED_KEY] = snapshot.passwordListQuickAccessEnabled
            preferences[PASSWORD_LIST_TOP_MODULES_ORDER_KEY] =
                parsedTopModules.joinToString(",") { it.name }
            preferences[PASSWORD_CARD_DISPLAY_MODE_KEY] = parsedPasswordCardMode.name
            preferences[PASSWORD_CARD_DISPLAY_FIELDS_KEY] =
                normalizedPasswordCardFields.joinToString(",") { it.name }
            preferences[PASSWORD_CARD_SHOW_AUTHENTICATOR_KEY] = snapshot.passwordCardShowAuthenticator
            preferences[PASSWORD_CARD_HIDE_OTHER_CONTENT_WHEN_AUTHENTICATOR_KEY] =
                snapshot.passwordCardHideOtherContentWhenAuthenticator
            preferences[STACK_CARD_MODE_KEY] = snapshot.stackCardMode.ifBlank { "AUTO" }
            preferences[WALLET_STACK_LOOP_ENABLED_KEY] = snapshot.walletStackLoopEnabled
            preferences[PASSWORD_GROUP_MODE_KEY] = snapshot.passwordGroupMode.ifBlank { "smart" }
            preferences[PASSWORD_WEBSITE_STACK_MATCH_MODE_KEY] =
                snapshot.passwordWebsiteStackMatchMode.ifBlank { "strict" }
            preferences[AUTHENTICATOR_CARD_DISPLAY_FIELDS_KEY] =
                normalizedAuthenticatorCardFields.joinToString(",") { it.name }
            preferences[AUTHENTICATOR_CARD_HIDE_CODE_BY_DEFAULT_KEY] =
                snapshot.authenticatorCardHideCodeByDefault
            preferences[AUTHENTICATOR_LAYOUT_MODE_KEY] =
                AuthenticatorLayoutMode.fromStoredValue(snapshot.authenticatorLayoutMode).name
            preferences[VAULT_V2_LAYOUT_MODE_KEY] =
                VaultV2LayoutMode.fromStoredValue(snapshot.vaultV2LayoutMode).name
            preferences[VAULT_OVERVIEW_ENABLED_KEY] = snapshot.vaultOverviewEnabled
            preferences[VAULT_OVERVIEW_CONFIG_KEY] = VaultOverviewConfig.decode(snapshot.vaultOverviewConfig).encode()
            preferences[VALIDATOR_PROGRESS_BAR_STYLE_KEY] = parsedValidatorProgressBarStyle.name
            preferences[VALIDATOR_UNIFIED_PROGRESS_BAR_KEY] = parsedValidatorUnifiedProgressBar.name
            preferences[VALIDATOR_SMOOTH_PROGRESS_KEY] = snapshot.validatorSmoothProgress
            preferences[VALIDATOR_VIBRATION_ENABLED_KEY] = snapshot.validatorVibrationEnabled
            preferences[HAPTIC_FEEDBACK_ENABLED_KEY] = snapshot.hapticFeedbackEnabled
            preferences[COPY_NEXT_CODE_WHEN_EXPIRING_KEY] = snapshot.copyNextCodeWhenExpiring
            preferences[SECURITY_ANALYSIS_AUTO_ENABLED_KEY] = snapshot.securityAnalysisAutoEnabled
            preferences[PASSWORD_DETAIL_SECURITY_ANALYSIS_ENABLED_KEY] =
                snapshot.passwordDetailSecurityAnalysisEnabled
            preferences[STEAM_MINI_PROFILE_BACKGROUND_ENABLED_KEY] =
                snapshot.steamMiniProfileBackgroundEnabled
            preferences[AUTOFILL_AUTH_REQUIRED_KEY] = snapshot.autofillAuthRequired
            preferences[ICON_CARDS_ENABLED_KEY] = snapshot.iconCardsEnabled
            val parsedAppLauncherIcon = runCatching {
                AppLauncherIcon.valueOf(snapshot.appLauncherIcon.trim())
            }.getOrDefault(AppLauncherIcon.MODERN)
            preferences[APP_LAUNCHER_ICON_KEY] = parsedAppLauncherIcon.name
            val parsedAppLauncherLabel = runCatching {
                AppLauncherLabel.valueOf(snapshot.appLauncherLabel.trim())
            }.getOrDefault(AppLauncherLabel.MONICA_PASS)
            preferences[APP_LAUNCHER_LABEL_KEY] = parsedAppLauncherLabel.name
            preferences[PASSWORD_PAGE_ICON_ENABLED_KEY] = snapshot.passwordPageIconEnabled
            preferences[AUTHENTICATOR_PAGE_ICON_ENABLED_KEY] = snapshot.authenticatorPageIconEnabled
            preferences[PASSKEY_PAGE_ICON_ENABLED_KEY] = snapshot.passkeyPageIconEnabled
            preferences[UNMATCHED_ICON_HANDLING_STRATEGY_KEY] = parsedUnmatchedIconStrategy.name
            if (shouldRestorePasswordFieldSettings) {
                preferences[SEPARATE_USERNAME_ACCOUNT_ENABLED_KEY] =
                    snapshot.separateUsernameAccountEnabled
                preferences[PRESET_CUSTOM_FIELDS_KEY] = normalizedPresetCustomFieldsJson
            }
            preferences[FIELD_SECURITY_VERIFICATION_KEY] =
                snapshot.passwordFieldVisibility.securityVerification
            preferences[FIELD_CATEGORY_AND_NOTES_KEY] =
                snapshot.passwordFieldVisibility.categoryAndNotes
            preferences[FIELD_APP_BINDING_KEY] = snapshot.passwordFieldVisibility.appBinding
            preferences[FIELD_PERSONAL_INFO_KEY] = snapshot.passwordFieldVisibility.personalInfo
            preferences[FIELD_ADDRESS_INFO_KEY] = snapshot.passwordFieldVisibility.addressInfo
            preferences[FIELD_PAYMENT_INFO_KEY] = snapshot.passwordFieldVisibility.paymentInfo
        }
        val appliedIcon = runCatching {
            AppLauncherIcon.valueOf(snapshot.appLauncherIcon.trim())
        }.getOrDefault(AppLauncherIcon.MODERN)
        val appliedLabel = runCatching {
            AppLauncherLabel.valueOf(snapshot.appLauncherLabel.trim())
        }.getOrDefault(AppLauncherLabel.MONICA_PASS)
        AppLauncherIconManager.apply(context, appliedIcon, appliedLabel)
    }
    
    // ==================== 预设自定义字段管理 ====================
    
    /**
     * 获取预设自定义字段列表 Flow
     */
    val presetCustomFieldsFlow: Flow<List<PresetCustomField>> = dataStore.data.map { preferences ->
        val json = preferences[PRESET_CUSTOM_FIELDS_KEY] ?: "[]"
        PresetCustomField.listFromJson(json)
    }
    
    /**
     * 添加预设自定义字段
     */
    suspend fun addPresetCustomField(field: PresetCustomField) {
        dataStore.edit { preferences ->
            val currentJson = preferences[PRESET_CUSTOM_FIELDS_KEY] ?: "[]"
            val currentList = PresetCustomField.listFromJson(currentJson).toMutableList()
            // 设置新字段的排序为最后
            val maxOrder = currentList.maxOfOrNull { it.order } ?: -1
            val newField = field.copy(order = maxOrder + 1)
            currentList.add(newField)
            preferences[PRESET_CUSTOM_FIELDS_KEY] = PresetCustomField.listToJson(currentList)
        }
    }
    
    /**
     * 更新预设自定义字段
     */
    suspend fun updatePresetCustomField(field: PresetCustomField) {
        dataStore.edit { preferences ->
            val currentJson = preferences[PRESET_CUSTOM_FIELDS_KEY] ?: "[]"
            val currentList = PresetCustomField.listFromJson(currentJson).toMutableList()
            val index = currentList.indexOfFirst { it.id == field.id }
            if (index >= 0) {
                currentList[index] = field
                preferences[PRESET_CUSTOM_FIELDS_KEY] = PresetCustomField.listToJson(currentList)
            }
        }
    }
    
    /**
     * 删除预设自定义字段
     */
    suspend fun deletePresetCustomField(fieldId: String) {
        dataStore.edit { preferences ->
            val currentJson = preferences[PRESET_CUSTOM_FIELDS_KEY] ?: "[]"
            val currentList = PresetCustomField.listFromJson(currentJson).toMutableList()
            currentList.removeAll { it.id == fieldId }
            // 重新排序
            val reordered = currentList.mapIndexed { index, field -> field.copy(order = index) }
            preferences[PRESET_CUSTOM_FIELDS_KEY] = PresetCustomField.listToJson(reordered)
        }
    }
    
    /**
     * 重新排序预设自定义字段
     */
    suspend fun reorderPresetCustomFields(fieldIds: List<String>) {
        dataStore.edit { preferences ->
            val currentJson = preferences[PRESET_CUSTOM_FIELDS_KEY] ?: "[]"
            val currentList = PresetCustomField.listFromJson(currentJson)
            val fieldMap = currentList.associateBy { it.id }
            val reorderedList = fieldIds.mapIndexedNotNull { index, id ->
                fieldMap[id]?.copy(order = index)
            }
            preferences[PRESET_CUSTOM_FIELDS_KEY] = PresetCustomField.listToJson(reorderedList)
        }
    }
    
    /**
     * 清空所有预设自定义字段
     */
    suspend fun clearAllPresetCustomFields() {
        dataStore.edit { preferences ->
            preferences[PRESET_CUSTOM_FIELDS_KEY] = "[]"
        }
    }
    
    /**
     * 更新减少动画设置
     * 开启后将禁用共享元素动画，改为简单的淡入淡出效果
     * 主要用于解决 HyperOS 2 / Android 15 等设备上的动画卡顿问题
     */
    suspend fun updateReduceAnimations(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[REDUCE_ANIMATIONS_KEY] = enabled
        }
    }

    suspend fun updateSmartDeduplicationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SMART_DEDUPLICATION_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateSeparateUsernameAccountEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SEPARATE_USERNAME_ACCOUNT_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateKeepassDxLikeMutationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEEPASS_DX_LIKE_MUTATION_ENABLED_KEY] = enabled
        }
    }

    suspend fun updateLastPasswordCategoryFilter(
        type: String,
        primaryId: Long? = null,
        secondaryId: Long? = null,
        text: String? = null,
        groupUuid: String? = null
    ) {
        dataStore.edit { preferences ->
            preferences[LAST_PASSWORD_CATEGORY_FILTER_TYPE_KEY] = type
            if (primaryId != null) {
                preferences[LAST_PASSWORD_CATEGORY_FILTER_PRIMARY_ID_KEY] = primaryId
            } else {
                preferences.remove(LAST_PASSWORD_CATEGORY_FILTER_PRIMARY_ID_KEY)
            }
            if (secondaryId != null) {
                preferences[LAST_PASSWORD_CATEGORY_FILTER_SECONDARY_ID_KEY] = secondaryId
            } else {
                preferences.remove(LAST_PASSWORD_CATEGORY_FILTER_SECONDARY_ID_KEY)
            }
            if (text.isNullOrBlank()) {
                preferences.remove(LAST_PASSWORD_CATEGORY_FILTER_TEXT_KEY)
            } else {
                preferences[LAST_PASSWORD_CATEGORY_FILTER_TEXT_KEY] = text
            }
            if (groupUuid.isNullOrBlank()) {
                preferences.remove(LAST_PASSWORD_CATEGORY_FILTER_GROUP_UUID_KEY)
            } else {
                preferences[LAST_PASSWORD_CATEGORY_FILTER_GROUP_UUID_KEY] = groupUuid
            }
        }
    }

    fun rememberedStorageTargetFlow(scope: String): Flow<RememberedStorageTarget> = dataStore.data.map { preferences ->
        RememberedStorageTarget(
            categoryId = preferences[storageCategoryKey(scope)],
            keepassDatabaseId = preferences[storageKeePassKey(scope)],
            keepassGroupPath = preferences[storageKeePassGroupPathKey(scope)],
            mdbxDatabaseId = preferences[storageMdbxKey(scope)],
            mdbxFolderId = preferences[storageMdbxFolderKey(scope)],
            bitwardenVaultId = preferences[storageBitwardenVaultKey(scope)],
            bitwardenFolderId = preferences[storageBitwardenFolderKey(scope)]
        )
    }

    suspend fun updateRememberedStorageTarget(scope: String, target: RememberedStorageTarget) {
        dataStore.edit { preferences ->
            val categoryKey = storageCategoryKey(scope)
            val keepassKey = storageKeePassKey(scope)
            val keepassGroupPathKey = storageKeePassGroupPathKey(scope)
            val mdbxKey = storageMdbxKey(scope)
            val mdbxFolderKey = storageMdbxFolderKey(scope)
            val bitwardenVaultKey = storageBitwardenVaultKey(scope)
            val bitwardenFolderKey = storageBitwardenFolderKey(scope)

            if (target.categoryId != null) preferences[categoryKey] = target.categoryId else preferences.remove(categoryKey)
            if (target.keepassDatabaseId != null) preferences[keepassKey] = target.keepassDatabaseId else preferences.remove(keepassKey)
            if (target.keepassGroupPath.isNullOrBlank()) preferences.remove(keepassGroupPathKey) else preferences[keepassGroupPathKey] = target.keepassGroupPath
            if (target.mdbxDatabaseId != null) preferences[mdbxKey] = target.mdbxDatabaseId else preferences.remove(mdbxKey)
            if (target.mdbxFolderId.isNullOrBlank()) preferences.remove(mdbxFolderKey) else preferences[mdbxFolderKey] = target.mdbxFolderId
            if (target.bitwardenVaultId != null) preferences[bitwardenVaultKey] = target.bitwardenVaultId else preferences.remove(bitwardenVaultKey)
            if (target.bitwardenFolderId.isNullOrBlank()) preferences.remove(bitwardenFolderKey) else preferences[bitwardenFolderKey] = target.bitwardenFolderId
        }
    }

    fun categoryFilterStateFlow(scope: String): Flow<SavedCategoryFilterState> = dataStore.data.map { preferences ->
        SavedCategoryFilterState(
            type = preferences[categoryFilterTypeKey(scope)] ?: "all",
            primaryId = preferences[categoryFilterPrimaryKey(scope)],
            secondaryId = preferences[categoryFilterSecondaryKey(scope)],
            text = preferences[categoryFilterTextKey(scope)],
            groupUuid = preferences[categoryFilterGroupUuidKey(scope)]
        )
    }

    suspend fun updateCategoryFilterState(scope: String, state: SavedCategoryFilterState) {
        dataStore.edit { preferences ->
            val typeKey = categoryFilterTypeKey(scope)
            val primaryKey = categoryFilterPrimaryKey(scope)
            val secondaryKey = categoryFilterSecondaryKey(scope)
            val textKey = categoryFilterTextKey(scope)
            val groupUuidKey = categoryFilterGroupUuidKey(scope)

            preferences[typeKey] = state.type
            if (state.primaryId != null) preferences[primaryKey] = state.primaryId else preferences.remove(primaryKey)
            if (state.secondaryId != null) preferences[secondaryKey] = state.secondaryId else preferences.remove(secondaryKey)
            if (state.text.isNullOrBlank()) preferences.remove(textKey) else preferences[textKey] = state.text
            if (state.groupUuid.isNullOrBlank()) preferences.remove(groupUuidKey) else preferences[groupUuidKey] = state.groupUuid
        }
    }
    
    /**
     * 更新自动填充数据源
     */
    suspend fun updateAutofillSources(sources: Set<AutofillSource>) {
        dataStore.edit { preferences ->
            preferences[AUTOFILL_SOURCES_KEY] = sources.joinToString(",") { it.name }
        }
    }
    
    /**
     * 更新自动填充优先级
     */
    suspend fun updateAutofillPriority(priority: List<AutofillSource>) {
        dataStore.edit { preferences ->
            preferences[AUTOFILL_PRIORITY_KEY] = priority.joinToString(",") { it.name }
        }
    }

    /** 分类菜单"快捷筛选"展开状态（跨进程持久化） */
    val categoryMenuQuickFiltersExpandedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[CATEGORY_MENU_QUICK_FILTERS_EXPANDED_KEY] ?: true
    }

    suspend fun updateCategoryMenuQuickFiltersExpanded(expanded: Boolean) {
        dataStore.edit { preferences ->
            preferences[CATEGORY_MENU_QUICK_FILTERS_EXPANDED_KEY] = expanded
        }
    }

    /** 分类菜单"分类文件夹"展开状态（跨进程持久化） */
    val categoryMenuFoldersExpandedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[CATEGORY_MENU_FOLDERS_EXPANDED_KEY] ?: true
    }

    suspend fun updateCategoryMenuFoldersExpanded(expanded: Boolean) {
        dataStore.edit { preferences ->
            preferences[CATEGORY_MENU_FOLDERS_EXPANDED_KEY] = expanded
        }
    }

}
