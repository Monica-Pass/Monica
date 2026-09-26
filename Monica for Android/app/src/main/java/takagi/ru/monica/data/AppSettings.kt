package takagi.ru.monica.data

/**
 * Settings data classes
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class ColorScheme {
    DEFAULT, 
    OCEAN_BLUE,      // 海洋蓝
    SUNSET_ORANGE,   // 日落橙
    FOREST_GREEN,    // 森林绿
    TECH_PURPLE,     // 科技紫
    BLACK_MAMBA,     // 黑曼巴
    GREY_STYLE,      // 小黑紫
    WATER_LILIES,    // 睡莲
    IMPRESSION_SUNRISE, // 印象·日出
    JAPANESE_BRIDGE, // 日本桥
    HAYSTACKS,       // 干草堆
    ROUEN_CATHEDRAL, // 鲁昂大教堂
    PARLIAMENT_FOG,  // 国会大厦
    CATPPUCCIN_LATTE,     // Catppuccin · Latte（Plus）
    CATPPUCCIN_FRAPPE,    // Catppuccin · Frappé（Plus）
    CATPPUCCIN_MACCHIATO, // Catppuccin · Macchiato（Plus）
    CATPPUCCIN_MOCHA,     // Catppuccin · Mocha（Plus）
    CUSTOM           // 自定义
}

enum class Language {
    SYSTEM,
    ENGLISH,
    CHINESE,
    CLASSICAL_CHINESE,
    VIETNAMESE,
    JAPANESE,
    RUSSIAN,
    KOREAN,
    GERMAN,
    SPANISH,
    FRENCH,
    POLISH,
    NYA,
    SNOW_LEOPARD,
    TRADITIONAL_CHINESE,
    ITALIAN
}

enum class ProgressBarStyle {
    LINEAR,  // 线形进度条
    WAVE     // 波浪形进度条
}

enum class NoteCodeBlockCollapseMode {
    COMPACT,
    BALANCED,
    EXPANDED
}

/**
 * 统一进度条模式
 */
enum class UnifiedProgressBarMode {
    DISABLED,  // 关闭统一进度条，每个卡片单独显示
    ENABLED    // 启用统一进度条（30s周期），标准周期卡片隐藏单独进度条
}

/**
 * V1 底部导航内容标签页
 * 用于经典本地密码库模式
 */
enum class BottomNavContentTab {
    VAULT_V2,
    PASSWORDS,
    AUTHENTICATOR,
    CARD_WALLET,
    GENERATOR,
    NOTES,
    SEND,         // 发送（安全分享）
    PASSKEY,  // 通行密钥
    STEAM;    // Steam local guard

    companion object {
        val DEFAULT_ORDER: List<BottomNavContentTab> = listOf(
            VAULT_V2,
            PASSWORDS,
            AUTHENTICATOR,
            CARD_WALLET,
            PASSKEY,
            NOTES,
            SEND,
            STEAM
        )

        fun sanitizeOrder(order: List<BottomNavContentTab>): List<BottomNavContentTab> {
            val result = mutableListOf<BottomNavContentTab>()
            val allowed = values().toSet()
            order.forEach { tab ->
                if (tab in allowed && tab !in result) {
                    result.add(tab)
                }
            }
            values().forEach { tab ->
                if (tab !in result) {
                    result.add(tab)
                }
            }
            return result
        }
    }
}

data class BottomNavVisibility(
    val vaultV2: Boolean = false,
    val passwords: Boolean = true,
    val authenticator: Boolean = true,
    val cardWallet: Boolean = true,
    val generator: Boolean = false,   // 生成器功能默认关闭
    val notes: Boolean = true,        // 笔记功能默认开启
    val send: Boolean = false,        // 发送功能默认关闭
    val passkey: Boolean = true,      // 通行密钥功能默认开启
    val steam: Boolean = false        // Steam 功能默认隐藏
) {
    fun isVisible(tab: BottomNavContentTab): Boolean = when (tab) {
        BottomNavContentTab.VAULT_V2 -> vaultV2
        // BottomNavContentTab.VAULT -> vault
        BottomNavContentTab.PASSWORDS -> passwords
        BottomNavContentTab.AUTHENTICATOR -> authenticator
        BottomNavContentTab.CARD_WALLET -> cardWallet
        BottomNavContentTab.GENERATOR -> generator
        BottomNavContentTab.NOTES -> notes
        BottomNavContentTab.SEND -> send
        BottomNavContentTab.PASSKEY -> passkey
        BottomNavContentTab.STEAM -> steam
    }

    fun visibleCount(): Int = listOf(
        vaultV2,
        passwords,
        authenticator || passkey,
        cardWallet,
        generator,
        notes,
        send,
        steam
    ).count { it }
}

/**
 * 添加/编辑密码页面字段可见性设置
 * 控制哪些字段卡片在添加密码页面显示
 * 注意：如果条目已有该字段数据，即使关闭也会显示
 */
data class PasswordFieldVisibility(
    val securityVerification: Boolean = true,  // 安全验证（TOTP密钥）
    val categoryAndNotes: Boolean = true,      // 分类与备注
    val appBinding: Boolean = true,            // 应用关联
    val personalInfo: Boolean = true,          // 个人信息（邮箱、电话）
    val addressInfo: Boolean = true,           // 地址信息
    val paymentInfo: Boolean = true            // 支付信息（信用卡）
)

/**
 * 预设自定义字段类型
 */
enum class PresetFieldType(val displayName: String, val icon: String) {
    TEXT("文本", "text"),
    PASSWORD("密码", "password"),
    NUMBER("数字", "number"),
    DATE("日期", "date"),
    URL("网址", "url"),
    EMAIL("邮箱", "email"),
    PHONE("电话", "phone")
}

/**
 * 预设自定义字段模板
 * 用户可以在设置中预先定义常用的自定义字段，添加密码时这些字段会自动出现
 * 
 * @property id 唯一标识（UUID字符串）
 * @property fieldName 字段名称（显示给用户的标题）
 * @property fieldType 字段类型
 * @property isSensitive 是否为敏感数据（显示时默认隐藏，复制时标记敏感）
 * @property isRequired 是否必填
 * @property defaultValue 默认值
 * @property placeholder 占位提示文字
 * @property order 排序顺序
 */
data class PresetCustomField(
    val id: String,
    val fieldName: String,
    val fieldType: PresetFieldType = PresetFieldType.TEXT,
    val isSensitive: Boolean = false,
    val isRequired: Boolean = false,
    val defaultValue: String = "",
    val placeholder: String = "",
    val order: Int = 0
) {
    /**
     * 序列化为 JSON 字符串
     */
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"id\":\"$id\",")
            append("\"fieldName\":\"${fieldName.replace("\"", "\\\"")}\",")
            append("\"fieldType\":\"${fieldType.name}\",")
            append("\"isSensitive\":$isSensitive,")
            append("\"isRequired\":$isRequired,")
            append("\"defaultValue\":\"${defaultValue.replace("\"", "\\\"")}\",")
            append("\"placeholder\":\"${placeholder.replace("\"", "\\\"")}\",")
            append("\"order\":$order")
            append("}")
        }
    }
    
    companion object {
        /**
         * 从 JSON 字符串解析
         */
        fun fromJson(json: String): PresetCustomField? {
            return try {
                // 简单的 JSON 解析
                fun extractString(key: String): String {
                    val pattern = "\"$key\":\"([^\"]*)\""
                    val regex = Regex(pattern)
                    return regex.find(json)?.groupValues?.get(1)
                        ?.replace("\\\"", "\"") ?: ""
                }
                fun extractBoolean(key: String): Boolean {
                    val pattern = "\"$key\":(true|false)"
                    val regex = Regex(pattern)
                    return regex.find(json)?.groupValues?.get(1) == "true"
                }
                fun extractInt(key: String): Int {
                    val pattern = "\"$key\":(\\d+)"
                    val regex = Regex(pattern)
                    return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                
                PresetCustomField(
                    id = extractString("id"),
                    fieldName = extractString("fieldName"),
                    fieldType = try { 
                        PresetFieldType.valueOf(extractString("fieldType")) 
                    } catch (e: Exception) { 
                        PresetFieldType.TEXT 
                    },
                    isSensitive = extractBoolean("isSensitive"),
                    isRequired = extractBoolean("isRequired"),
                    defaultValue = extractString("defaultValue"),
                    placeholder = extractString("placeholder"),
                    order = extractInt("order")
                )
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * 解析预设字段列表的 JSON
         */
        fun listFromJson(json: String): List<PresetCustomField> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                // 移除首尾的 [ ]
                val content = json.trim().removePrefix("[").removeSuffix("]")
                if (content.isBlank()) return emptyList()
                
                // 分割各个对象 - 简单处理，假设对象内没有嵌套
                val objects = mutableListOf<String>()
                var depth = 0
                var current = StringBuilder()
                for (char in content) {
                    when (char) {
                        '{' -> {
                            depth++
                            current.append(char)
                        }
                        '}' -> {
                            current.append(char)
                            depth--
                            if (depth == 0) {
                                objects.add(current.toString())
                                current = StringBuilder()
                            }
                        }
                        ',' -> {
                            if (depth == 0) {
                                // 跳过对象之间的逗号
                            } else {
                                current.append(char)
                            }
                        }
                        else -> {
                            if (depth > 0) {
                                current.append(char)
                            }
                        }
                    }
                }
                
                objects.mapNotNull { fromJson(it) }
                    .filter { it.fieldName.trim().isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
        }
        
        /**
         * 将预设字段列表序列化为 JSON
         */
        fun listToJson(fields: List<PresetCustomField>): String {
            return "[${fields.filter { it.fieldName.trim().isNotEmpty() }.joinToString(",") { it.toJson() }}]"
        }
    }
}

/**
 * 密码列表顶部可配置模块（顺序即显示顺序）
 */
enum class PasswordListTopModule {
    QUICK_FILTERS,
    QUICK_FOLDERS;

    companion object {
        val DEFAULT_ORDER: List<PasswordListTopModule> = listOf(
            QUICK_FILTERS,
            QUICK_FOLDERS
        )

        fun sanitizeOrder(order: List<PasswordListTopModule>): List<PasswordListTopModule> {
            val result = mutableListOf<PasswordListTopModule>()
            val allowed = values().toSet()
            order.forEach { module ->
                if (module in allowed && module !in result) {
                    result.add(module)
                }
            }
            values().forEach { module ->
                if (module !in result) {
                    result.add(module)
                }
            }
            return result
        }
    }
}

/**
 * 密码列表快捷筛选项（顺序即显示顺序）
 */
enum class PasswordListQuickFilterItem {
    FAVORITE,
    TWO_FA,
    NOTES,
    UNCATEGORIZED,
    LOCAL_ONLY,
    MANUAL_STACK_ONLY,
    NEVER_STACK,
    UNSTACKED,
    CARD_WALLET,
    AUTHENTICATOR,
    PASSKEY,
    NOTE,
    ATTACHMENTS;

    companion object {
        val DEFAULT_ORDER: List<PasswordListQuickFilterItem> = listOf(
            FAVORITE,
            TWO_FA,
            NOTES,
            UNCATEGORIZED,
            LOCAL_ONLY,
            CARD_WALLET,
            PASSKEY,
            NOTE,
            ATTACHMENTS
        )

        fun sanitizeOrder(order: List<PasswordListQuickFilterItem>): List<PasswordListQuickFilterItem> {
            val result = mutableListOf<PasswordListQuickFilterItem>()
            val allowed = values().toSet()
            order.forEach { item ->
                val normalized = when (item) {
                    AUTHENTICATOR -> TWO_FA
                    else -> item
                }
                if (normalized in allowed && normalized != AUTHENTICATOR && normalized !in result) {
                    result.add(normalized)
                }
            }
            return result
        }
    }
}

/**
 * 密码列表快捷文件夹展示样式
 */
enum class PasswordListQuickFolderStyle {
    CLASSIC,    // 经典快捷卡片（含返回上级）
    M3_CARD     // M3 卡片模式
}

enum class VaultV2LayoutMode {
    CLASSIC,
    HIERARCHICAL;

    companion object {
        fun fromStoredValue(value: String?): VaultV2LayoutMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: CLASSIC
    }
}

enum class AddButtonBehaviorMode {
    DIRECT_PASSWORD,
    EXPANDABLE_MENU
}

enum class AddButtonMenuAction {
    PASSWORD,
    NOTE,
    AUTHENTICATOR,
    BANK_CARD;

    companion object {
        val DEFAULT_ORDER: List<AddButtonMenuAction> = listOf(
            BANK_CARD,
            AUTHENTICATOR,
            NOTE,
            PASSWORD
        )

        val DEFAULT_ENABLED_ACTIONS: List<AddButtonMenuAction> = DEFAULT_ORDER

        fun sanitizeOrder(order: List<AddButtonMenuAction>): List<AddButtonMenuAction> {
            val result = mutableListOf<AddButtonMenuAction>()
            val allowed = values().toSet()
            order.forEach { action ->
                if (action in allowed && action !in result) {
                    result.add(action)
                }
            }
            values().forEach { action ->
                if (action !in result) {
                    result.add(action)
                }
            }
            return result
        }

        fun normalizeEnabledActions(
            actions: List<AddButtonMenuAction>,
            order: List<AddButtonMenuAction> = DEFAULT_ORDER
        ): List<AddButtonMenuAction> {
            val enabled = actions.toMutableSet().apply {
                add(PASSWORD)
            }
            return sanitizeOrder(order).filter { it in enabled }
        }
    }
}

enum class PasswordPageContentType {
    PASSWORD,
    CARD_WALLET,
    NOTE,
    AUTHENTICATOR,
    PASSKEY;

    companion object {
        val DEFAULT_VISIBLE_TYPES: List<PasswordPageContentType> = listOf(
            PASSWORD,
            CARD_WALLET,
            NOTE,
            AUTHENTICATOR,
            PASSKEY
        )

        fun sanitizeVisibleTypes(types: List<PasswordPageContentType>): List<PasswordPageContentType> {
            val result = mutableListOf(PASSWORD)
            types.forEach { type ->
                if (type != PASSWORD && type !in result) {
                    result.add(type)
                }
            }
            return result
        }

        fun normalizeEnabledTypes(types: List<PasswordPageContentType>): List<PasswordPageContentType> {
            val allowed = values().toSet()
            return sanitizeVisibleTypes(types.filter { it in allowed })
        }
    }
}

enum class CategorySelectionUiMode {
    BOTTOM_SHEET,
    CHIP_MENU;

    companion object {
        val DEFAULT = CHIP_MENU
    }
}

enum class PasswordSwipeSelectionMode {
    SINGLE,
    CONTINUOUS;

    companion object {
        val DEFAULT = SINGLE
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val oledPureBlackEnabled: Boolean = false,
    val colorScheme: ColorScheme = ColorScheme.DEFAULT,
    val customPrimaryColor: Long = 0xFF6650a4, // 默认紫色
    val customSecondaryColor: Long = 0xFF625b71, // 默认紫色灰色
    val customTertiaryColor: Long = 0xFF7D5260, // 默认粉色
    val customNeutralColor: Long = 0xFF605D66, // 默认中性色
    val customNeutralVariantColor: Long = 0xFF625B71, // 默认中性变体色
    val interfaceScalePercent: Int = InterfaceScale.DEFAULT_PERCENT,
    val language: Language = Language.SYSTEM,
    val biometricEnabled: Boolean = false, // 生物识别认证默认关闭
    val autoLockMinutes: Int = 5, // Auto lock after X minutes of inactivity
    val screenshotProtectionEnabled: Boolean = false, // Prevent screenshots by default
    val clipboardAutoClearSeconds: Int = 0, // 复制账号/密码后自动清除剪切板，0=关闭
    val dynamicColorEnabled: Boolean = true, // 动态颜色默认开启
    val quickSetupCompleted: Boolean = false, // 首次快速初始化是否已完成/跳过
    val bottomNavVisibility: BottomNavVisibility = BottomNavVisibility(),
    val bottomNavOrder: List<BottomNavContentTab> = BottomNavContentTab.DEFAULT_ORDER,
    val useDraggableBottomNav: Boolean = false, // 使用可拖拽底部导航栏
    val autoHideBottomNavWhenSingleTab: Boolean = false, // 仅启用一个底栏项时自动隐藏导航栏
    val disablePasswordVerification: Boolean = false, // 开发者选项：关闭密码验证
    val passkeyHyperOsBiometricBypassEnabled: Boolean = false, // 开发者选项：HyperOS Passkey 生物识别兼容旁路
    val bitwardenSyncForensicsEnabled: Boolean = false, // 开发者选项：Bitwarden 同步脱敏取证
    val bitwardenSyncForensicsDirectoryUri: String? = null, // 取证日志外部镜像目录（SAF tree uri）
    val bitwardenSyncForensicsRawCaptureEnabled: Boolean = false, // 开发者选项：Bitwarden 原始请求/响应留存（强脱敏）
    val validatorProgressBarStyle: ProgressBarStyle = ProgressBarStyle.WAVE, // 验证器进度条样式（波浪形）
    val validatorUnifiedProgressBar: UnifiedProgressBarMode = UnifiedProgressBarMode.ENABLED, // 统一进度条模式
    val validatorSmoothProgress: Boolean = true, // 平滑进度条（无停顿感）
    val hapticFeedbackEnabled: Boolean = true, // 触觉反馈总开关
    val validatorVibrationEnabled: Boolean = true, // 验证器震动提醒
    val hideFabOnScroll: Boolean = false, // 滚动时隐藏悬浮按钮
    val securityAnalysisAutoEnabled: Boolean = false, // 安全分析自动分析
    val passwordDetailSecurityAnalysisEnabled: Boolean = true,
    val steamMiniProfileBackgroundEnabled: Boolean = false,
    val bitwardenBottomStatusBarEnabled: Boolean = false, // Bitwarden 底部状态栏（实验）
    val copyNextCodeWhenExpiring: Boolean = true, // 倒计时<=5秒时复制下一个验证码（默认开启）
    val notificationValidatorEnabled: Boolean = false, // 通知栏验证器开关
    val notificationValidatorAutoMatch: Boolean = false, // 通知栏验证器自动匹配
    val notificationValidatorId: Long = -1L, // 通知栏显示的验证器ID
    val isPlusActivated: Boolean = false, // Plus是否已激活
    val stackCardMode: String = "AUTO", // 堆叠卡片模式
    val walletStackLoopEnabled: Boolean = false,
    val passwordGroupMode: String = "smart", // 密码分组模式
    val passwordWebsiteStackMatchMode: String = "strict", // 网站自动堆叠匹配模式：strict/relaxed
    val totpTimeOffset: Int = 0, // TOTP时间偏移（秒），用于校正系统时间误差
    val trashEnabled: Boolean = true, // 回收站功能是否启用
    val trashAutoDeleteDays: Int = 30, // 回收站自动清空天数（0=不自动清空，-1=禁用回收站）
    val iconCardsEnabled: Boolean = true, // 是否启用带图标卡片
    val appLauncherIcon: AppLauncherIcon = AppLauncherIcon.MODERN, // 主应用图标样式
    val appLauncherLabel: AppLauncherLabel = AppLauncherLabel.MONICA_PASS, // 主应用桌面名称
    val passwordPageIconEnabled: Boolean = true, // 密码页图标开关
    val authenticatorPageIconEnabled: Boolean = true, // 验证器页图标开关
    val passkeyPageIconEnabled: Boolean = true, // 通行密钥页图标开关
    val unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy = UnmatchedIconHandlingStrategy.DEFAULT_ICON, // 无匹配图标处理策略
    val passwordCardDisplayMode: PasswordCardDisplayMode = PasswordCardDisplayMode.SHOW_ALL, // 卡片显示模式
    val passwordCardDisplayFields: List<PasswordCardDisplayField> = PasswordCardDisplayField.DEFAULT_ORDER, // 卡片显示字段（顺序即展示顺序）
    val passwordCardShowAuthenticator: Boolean = true, // 密码卡片显示绑定验证器（默认开启）
    val passwordCardHideOtherContentWhenAuthenticator: Boolean = true, // 显示验证器时隐藏其他卡片内容（默认开启）
    val authenticatorCardDisplayFields: List<AuthenticatorCardDisplayField> = AuthenticatorCardDisplayField.DEFAULT_ORDER, // 验证器卡片显示字段（顺序即展示顺序）
    val authenticatorCardHideCodeByDefault: Boolean = false, // 验证器卡片默认隐藏验证码
    val authenticatorLayoutMode: AuthenticatorLayoutMode = AuthenticatorLayoutMode.STANDARD,
    val vaultV2LayoutMode: VaultV2LayoutMode = VaultV2LayoutMode.CLASSIC,
    val vaultOverviewEnabled: Boolean = true,
    val vaultOverviewConfig: VaultOverviewConfig = VaultOverviewConfig(),
    val passwordListQuickFiltersEnabled: Boolean = true, // 密码列表快捷筛选开关（默认开启）
    val passwordListQuickFilterItems: List<PasswordListQuickFilterItem> = PasswordListQuickFilterItem.DEFAULT_ORDER, // 密码列表快捷筛选显示内容
    val passwordListCategoryQuickFiltersEnabled: Boolean = true, // 密码列表分类快捷筛选开关（默认开启）
    val passwordListQuickFoldersEnabled: Boolean = false, // 密码列表快捷文件夹开关
    val passwordListQuickFolderStyle: PasswordListQuickFolderStyle = PasswordListQuickFolderStyle.CLASSIC, // 密码列表快捷文件夹展示样式
    val passwordListQuickFolderPathBannerEnabled: Boolean = false, // 密码列表路径横幅开关
    val passwordListSystemBackToParentFolderEnabled: Boolean = true, // 密码页系统返回回到父文件夹（默认开启）
    val addButtonBehaviorMode: AddButtonBehaviorMode = AddButtonBehaviorMode.DIRECT_PASSWORD, // 添加按钮行为
    val addButtonMenuOrder: List<AddButtonMenuAction> = AddButtonMenuAction.DEFAULT_ORDER, // 添加按钮展开菜单顺序
    val addButtonMenuEnabledActions: List<AddButtonMenuAction> = AddButtonMenuAction.DEFAULT_ENABLED_ACTIONS, // 添加按钮展开菜单启用项
    val passwordPageAggregateEnabled: Boolean = false, // 密码页聚合内容开关（默认关闭）
    val passwordPageVisibleContentTypes: List<PasswordPageContentType> =
        PasswordPageContentType.DEFAULT_VISIBLE_TYPES, // 密码页可显示内容类型
    val categorySelectionUiMode: CategorySelectionUiMode = CategorySelectionUiMode.DEFAULT, // 分类选择 UI 形式
    val passwordListQuickAccessEnabled: Boolean = true, // 密码列表“最近打开/经常打开”快捷入口开关
    val passwordListTopModulesOrder: List<PasswordListTopModule> = PasswordListTopModule.DEFAULT_ORDER, // 密码列表顶部模块顺序
    val passwordSwipeSelectionMode: PasswordSwipeSelectionMode = PasswordSwipeSelectionMode.DEFAULT, // 右滑选中模式
    val noteGridLayout: Boolean = true, // 笔记列表使用网格布局 (true = 网格, false = 列表)
    val noteCodeBlockCollapseMode: NoteCodeBlockCollapseMode = NoteCodeBlockCollapseMode.BALANCED, // 笔记代码块折叠模式
    val autofillAuthRequired: Boolean = true, // 自动填充验证 - 默认开启
    val passwordFieldVisibility: PasswordFieldVisibility = PasswordFieldVisibility(), // 添加密码页面字段定制
    val reduceAnimations: Boolean = false, // 减少动画 - 解决部分设备（如 HyperOS 2/Android 15）动画卡顿问题
    val smartDeduplicationEnabled: Boolean = true, // 智能去重（在“所有”视图中合并显示相同密码）
    val separateUsernameAccountEnabled: Boolean = false, // 用户名/账号分离（实验）
    val keepassDxLikeMutationEnabled: Boolean = false, // KeePass DX-like 写入管线（实验）
    val lastPasswordCategoryFilterType: String = "all", // 上次密码列表分类类型
    val lastPasswordCategoryFilterPrimaryId: Long? = null, // 上次分类主参数（分类ID/库ID等）
    val lastPasswordCategoryFilterSecondaryId: Long? = null, // 上次分类次参数（如 Bitwarden Vault ID）
    val lastPasswordCategoryFilterText: String? = null, // 上次分类文本参数（如组路径/文件夹ID）
    val lastPasswordCategoryFilterGroupUuid: String? = null, // KeePass 分组稳定身份；旧版本继续按路径回退

    // Bitwarden 同步范围设置
    val bitwardenUploadAll: Boolean = false, // 一键上传所有数据到 Bitwarden
    val autofillSources: Set<AutofillSource> = setOf(AutofillSource.V1_LOCAL), // 自动填充数据源
    val autofillPriority: List<AutofillSource> = listOf(AutofillSource.V1_LOCAL) // 自动填充优先级
)

enum class AppLauncherIcon {
    MODERN
}

enum class AppLauncherLabel {
    MONICA,
    MONICA_PASS
}

/**
 * 密码卡片显示模式
 */
enum class PasswordCardDisplayMode {
    SHOW_ALL,       // 显示所有信息（默认）
    TITLE_USERNAME, // 仅显示标题和用户名
    TITLE_ONLY      // 仅显示标题
}

/**
 * 密码卡片可配置显示字段（标题固定显示）
 */
enum class PasswordCardDisplayField {
    USERNAME,
    WEBSITE,
    APP_NAME,
    NOTE_PREVIEW,
    UPDATED_AT;

    companion object {
        val DEFAULT_ORDER: List<PasswordCardDisplayField> = listOf(
            USERNAME
        )
    }
}

/**
 * 验证器卡片可配置显示字段（标题固定显示）
 */
enum class AuthenticatorCardDisplayField {
    ISSUER,
    ACCOUNT_NAME;

    companion object {
        val DEFAULT_ORDER: List<AuthenticatorCardDisplayField> = listOf(
            ACCOUNT_NAME
        )
    }
}

enum class AuthenticatorLayoutMode {
    STANDARD,
    TILE;

    companion object {
        fun fromStoredValue(value: String?): AuthenticatorLayoutMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: STANDARD
    }
}

enum class UnmatchedIconHandlingStrategy {
    DEFAULT_ICON,              // 显示默认图标
    WEBSITE_OR_TITLE_INITIAL,  // 显示网站/标题首字
    HIDE                       // 不显示图标
}

/**
 * 自动填充数据源
 */
enum class AutofillSource {
    V1_LOCAL,    // V1 本地密码库
    BITWARDEN,   // Bitwarden
    KEEPASS      // KeePass（未来支持）
}
