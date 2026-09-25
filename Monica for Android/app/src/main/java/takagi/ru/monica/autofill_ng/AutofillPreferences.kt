package takagi.ru.monica.autofill_ng

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import takagi.ru.monica.autofill_ng.core.AutofillLogger

/**
 * 自动填充配置管理
 */
class AutofillPreferences(private val context: Context) {
    enum class AutofillEngineMode {
        BITWARDEN_V2,
        LEGACY_FROZEN,
    }

    enum class AutofillDefaultSourceFilter {
        ALL,
        LOCAL,
        KEEPASS,
        BITWARDEN,
    }

    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autofill_settings")
        
        // 配置键
        private val KEY_AUTOFILL_ENABLED = booleanPreferencesKey("autofill_enabled")
        private val KEY_ACTIVE_FILL_NOTIFICATION_ENABLED =
            booleanPreferencesKey("active_fill_notification_enabled")
        private val KEY_DOMAIN_MATCH_STRATEGY = stringPreferencesKey("domain_match_strategy")
        private val KEY_FILL_SUGGESTIONS_ENABLED = booleanPreferencesKey("fill_suggestions_enabled")
        private val KEY_MANUAL_SELECTION_ENABLED = booleanPreferencesKey("manual_selection_enabled")
        private val KEY_REQUEST_SAVE_DATA = booleanPreferencesKey("request_save_data")
        private val KEY_AUTO_SAVE_APP_INFO = booleanPreferencesKey("auto_save_app_info")
        private val KEY_AUTO_SAVE_WEBSITE_INFO = booleanPreferencesKey("auto_save_website_info")
        
        // Phase 8: 生物识别快速填充配置
        private val KEY_BIOMETRIC_QUICK_FILL_ENABLED = booleanPreferencesKey("biometric_quick_fill_enabled")
        
        // 新架构：使用增强匹配引擎
        private val KEY_USE_ENHANCED_MATCHING = booleanPreferencesKey("use_enhanced_matching")

        // Autofill V2 引擎模式
        private val KEY_AUTOFILL_ENGINE_MODE = stringPreferencesKey("autofill_engine_mode")
        private val KEY_V2_STRICT_MATCH = booleanPreferencesKey("v2_strict_match")
        private val KEY_V2_ALLOW_SUBDOMAIN_MATCH = booleanPreferencesKey("v2_allow_subdomain_match")
        private val KEY_V2_RESPECT_AUTOFILL_OFF = booleanPreferencesKey("v2_respect_autofill_off")
        private val KEY_V2_DEFAULT_SOURCE_FILTER = stringPreferencesKey("v2_default_source_filter")
        private val KEY_V2_DEFAULT_KEEPASS_DATABASE_ID = longPreferencesKey("v2_default_keepass_database_id")
        private val KEY_V2_DEFAULT_BITWARDEN_VAULT_ID = longPreferencesKey("v2_default_bitwarden_vault_id")

        // 内联建议（输入法候选栏内嵌自动填充）
        private val KEY_INLINE_SUGGESTIONS_ENABLED = booleanPreferencesKey("inline_suggestions_enabled")

        // 是否尊重自动填充禁用标识
        private val KEY_RESPECT_AUTOFILL_DISABLED = booleanPreferencesKey("respect_autofill_disabled")
        
        // OTP验证器设置
        private val KEY_OTP_NOTIFICATION_ENABLED = booleanPreferencesKey("otp_notification_enabled")
        private val KEY_AUTO_COPY_OTP = booleanPreferencesKey("auto_copy_otp")
        private val KEY_OTP_NOTIFICATION_DURATION = intPreferencesKey("otp_notification_duration")
        
        // 🔐 密码建议功能配置
        private val KEY_PASSWORD_SUGGESTION_ENABLED = booleanPreferencesKey("password_suggestion_enabled")
        
        // 密码保存功能配置
        private val KEY_AUTO_UPDATE_DUPLICATE_PASSWORDS = booleanPreferencesKey("auto_update_duplicate_passwords")
        private val KEY_SHOW_SAVE_NOTIFICATION = booleanPreferencesKey("show_save_notification")
        private val KEY_SMART_TITLE_GENERATION = booleanPreferencesKey("smart_title_generation")
        
        // 黑名单配置
        private val KEY_BLACKLIST_ENABLED = booleanPreferencesKey("blacklist_enabled")
        private val KEY_BLACKLIST_PACKAGES = stringSetPreferencesKey("blacklist_packages")
        private val KEY_SAVE_BLOCKED_TARGETS = stringSetPreferencesKey("save_blocked_targets")
        // 填充组件外观: 是否使用横幅（方案2）
        private val KEY_FILL_COMPONENT_USE_BANNER = booleanPreferencesKey("fill_component_use_banner")

        // 最近一次自动填充记录（用于显示“上次填充”卡片）
        private val KEY_LAST_FILLED_IDENTIFIER = stringPreferencesKey("last_filled_identifier")
        private val KEY_LAST_FILLED_PASSWORD_ID = longPreferencesKey("last_filled_password_id")
        private val KEY_LAST_FILLED_AT = longPreferencesKey("last_filled_at")
        private val KEY_INTERACTION_IDENTIFIER = stringPreferencesKey("autofill_interaction_identifier")
        private val KEY_INTERACTION_STARTED_AT = longPreferencesKey("autofill_interaction_started_at")
        private val KEY_INTERACTION_COMPLETED = booleanPreferencesKey("autofill_interaction_completed")
        private val KEY_SUGGESTION_STAGE_IDENTIFIER = stringPreferencesKey("autofill_suggestion_stage_identifier")
        private val KEY_SUGGESTION_STAGE = intPreferencesKey("autofill_suggestion_stage")
        private val KEY_SUGGESTION_STAGE_AT = longPreferencesKey("autofill_suggestion_stage_at")
        private val KEY_LEARNED_FIELD_SIGNATURES = stringSetPreferencesKey("learned_field_signatures")
        private val KEY_BLOCKED_FIELD_SIGNATURES = stringSetPreferencesKey("blocked_field_signatures")
        private val KEY_BLOCKED_FIELD_SIGNATURE_RECORDS = stringSetPreferencesKey("blocked_field_signature_records")
        private const val BLOCKED_FIELD_SIGNATURE_RECORD_DELIMITER = "\u001F"
        
        // 默认黑名单应用
        val DEFAULT_BLACKLIST_PACKAGES = setOf(
            "com.tencent.mm",           // 微信
            "com.eg.android.AlipayGphone", // 支付宝
            "com.unionpay"              // 云闪付
        )
    }
    
    /**
     * 是否启用自动填充服务
     */
    val imeKeyboardOptions: Flow<takagi.ru.monica.ime.ImeKeyboardOptions> =
        takagi.ru.monica.ime.ImeKeyboardPreferences.get(context).data

    suspend fun setImeScramblePin(enabled: Boolean) {
        takagi.ru.monica.ime.ImeKeyboardPreferences.get(context).updateData { it.copy(scramblePin = enabled) }
    }

    suspend fun setImeHidePinPreview(enabled: Boolean) {
        takagi.ru.monica.ime.ImeKeyboardPreferences.get(context).updateData { it.copy(hidePinPreview = enabled) }
    }

    val isAutofillEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTOFILL_ENABLED] ?: true  // 默认启用
    }
    
    suspend fun setAutofillEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTOFILL_ENABLED] = enabled
        }
    }

    val isActiveFillNotificationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ACTIVE_FILL_NOTIFICATION_ENABLED] ?: false
    }

    suspend fun setActiveFillNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ACTIVE_FILL_NOTIFICATION_ENABLED] = enabled
        }
    }
    
    /**
     * 域名匹配策略
     */
    val domainMatchStrategy: Flow<DomainMatchStrategy> = context.dataStore.data.map { preferences ->
        val strategyName = preferences[KEY_DOMAIN_MATCH_STRATEGY] ?: DomainMatchStrategy.BASE_DOMAIN.name
        try {
            DomainMatchStrategy.valueOf(strategyName)
        } catch (e: Exception) {
            DomainMatchStrategy.BASE_DOMAIN
        }
    }
    
    suspend fun setDomainMatchStrategy(strategy: DomainMatchStrategy) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DOMAIN_MATCH_STRATEGY] = strategy.name
        }
    }
    
    /**
     * 是否启用填充建议
     */
    val isFillSuggestionsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_FILL_SUGGESTIONS_ENABLED] ?: true
    }
    
    suspend fun setFillSuggestionsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FILL_SUGGESTIONS_ENABLED] = enabled
        }
    }
    
    /**
     * 是否启用手动选择
     */
    val isManualSelectionEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_MANUAL_SELECTION_ENABLED] ?: true
    }
    
    suspend fun setManualSelectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MANUAL_SELECTION_ENABLED] = enabled
        }
    }
    
    /**
     * 是否请求保存数据 (填写表单时询问是否更新密码库)
     */
    val isRequestSaveDataEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_REQUEST_SAVE_DATA] ?: true
    }
    
    suspend fun setRequestSaveDataEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_REQUEST_SAVE_DATA] = enabled
        }
    }
    
    /**
     * 是否自动保存应用信息
     */
    val isAutoSaveAppInfoEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_SAVE_APP_INFO] ?: true
    }
    
    suspend fun setAutoSaveAppInfoEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_SAVE_APP_INFO] = enabled
        }
    }
    
    /**
     * 是否自动保存网站信息
     */
    val isAutoSaveWebsiteInfoEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_SAVE_WEBSITE_INFO] ?: true
    }
    
    suspend fun setAutoSaveWebsiteInfoEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_SAVE_WEBSITE_INFO] = enabled
        }
    }
    
    /**
     * Phase 8: 是否启用生物识别快速填充
     * 启用后,用户选择密码时需要生物识别验证才能自动填充
     */
    val isBiometricQuickFillEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BIOMETRIC_QUICK_FILL_ENABLED] ?: true  // 默认启用
    }
    
    suspend fun setBiometricQuickFillEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BIOMETRIC_QUICK_FILL_ENABLED] = enabled
        }
    }
    
    /**
     * 是否使用增强匹配引擎（新架构）
     * 默认启用
     */
    val useEnhancedMatching: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_USE_ENHANCED_MATCHING] ?: true
    }
    
    suspend fun setUseEnhancedMatching(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_USE_ENHANCED_MATCHING] = enabled
        }
    }

    val autofillEngineMode: Flow<AutofillEngineMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[KEY_AUTOFILL_ENGINE_MODE] ?: AutofillEngineMode.BITWARDEN_V2.name
        runCatching { AutofillEngineMode.valueOf(modeName) }
            .getOrDefault(AutofillEngineMode.BITWARDEN_V2)
    }

    suspend fun setAutofillEngineMode(mode: AutofillEngineMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTOFILL_ENGINE_MODE] = mode.name
        }
    }

    suspend fun ensureBitwardenV2EngineMode() {
        val currentMode = autofillEngineMode.first()
        if (currentMode != AutofillEngineMode.BITWARDEN_V2) {
            setAutofillEngineMode(AutofillEngineMode.BITWARDEN_V2)
        }
    }

    val isBitwardenStrictModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_V2_STRICT_MATCH] ?: true
    }

    suspend fun setBitwardenStrictModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_V2_STRICT_MATCH] = enabled
        }
    }

    val isBitwardenSubdomainMatchEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_V2_ALLOW_SUBDOMAIN_MATCH] ?: true
    }

    suspend fun setBitwardenSubdomainMatchEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_V2_ALLOW_SUBDOMAIN_MATCH] = enabled
        }
    }

    val isV2RespectAutofillOffEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_V2_RESPECT_AUTOFILL_OFF] ?: false
    }

    suspend fun setV2RespectAutofillOffEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_V2_RESPECT_AUTOFILL_OFF] = enabled
        }
    }

    val v2DefaultSourceFilter: Flow<AutofillDefaultSourceFilter> = context.dataStore.data.map { preferences ->
        val name = preferences[KEY_V2_DEFAULT_SOURCE_FILTER] ?: AutofillDefaultSourceFilter.ALL.name
        runCatching { AutofillDefaultSourceFilter.valueOf(name) }
            .getOrDefault(AutofillDefaultSourceFilter.ALL)
    }

    suspend fun setV2DefaultSourceFilter(filter: AutofillDefaultSourceFilter) {
        context.dataStore.edit { preferences ->
            preferences[KEY_V2_DEFAULT_SOURCE_FILTER] = filter.name
        }
    }

    val v2DefaultKeepassDatabaseId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[KEY_V2_DEFAULT_KEEPASS_DATABASE_ID]
    }

    suspend fun setV2DefaultKeepassDatabaseId(databaseId: Long?) {
        context.dataStore.edit { preferences ->
            if (databaseId == null) {
                preferences.remove(KEY_V2_DEFAULT_KEEPASS_DATABASE_ID)
            } else {
                preferences[KEY_V2_DEFAULT_KEEPASS_DATABASE_ID] = databaseId
            }
        }
    }

    val v2DefaultBitwardenVaultId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[KEY_V2_DEFAULT_BITWARDEN_VAULT_ID]
    }

    suspend fun setV2DefaultBitwardenVaultId(vaultId: Long?) {
        context.dataStore.edit { preferences ->
            if (vaultId == null) {
                preferences.remove(KEY_V2_DEFAULT_BITWARDEN_VAULT_ID)
            } else {
                preferences[KEY_V2_DEFAULT_BITWARDEN_VAULT_ID] = vaultId
            }
        }
    }

    /**
     * 是否尊重"禁止自动填充"标识
     * 如果为 true，遇到类似 autocomplete="off" 的字段将不进行填充
     * 默认为 true (遵循标准)
     */
    val isRespectAutofillDisabledEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_RESPECT_AUTOFILL_DISABLED] ?: false // 默认为 false，即强制填充（更符合用户期望）
    }

    suspend fun setRespectAutofillDisabledEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RESPECT_AUTOFILL_DISABLED] = enabled
        }
    }

    /**
     * 是否启用输入法内联建议（键盘候选栏内嵌自动填充）
     * 关闭后仍会使用传统下拉菜单式建议
     */
    val isInlineSuggestionsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_INLINE_SUGGESTIONS_ENABLED] ?: true
    }

    suspend fun setInlineSuggestionsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_INLINE_SUGGESTIONS_ENABLED] = enabled
        }
    }

    /**
     * Auth Notification Settings
     */
    val isOtpNotificationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_OTP_NOTIFICATION_ENABLED] ?: false
    }

    suspend fun setOtpNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OTP_NOTIFICATION_ENABLED] = enabled
        }
    }

    val isAutoCopyOtpEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_COPY_OTP] ?: false
    }

    suspend fun setAutoCopyOtpEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_COPY_OTP] = enabled
        }
    }

    val otpNotificationDuration: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_OTP_NOTIFICATION_DURATION] ?: 30 // Default 30s
    }

    suspend fun setOtpNotificationDuration(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OTP_NOTIFICATION_DURATION] = seconds
        }
    }
    
    /**
     * 🔐 是否启用密码建议功能
     * 启用后，在注册/修改密码时自动提供强密码建议
     * 默认启用
     */
    val isPasswordSuggestionEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_PASSWORD_SUGGESTION_ENABLED] ?: true
    }

    /**
     * 填充组件外观: 是否使用横幅（方案2）。
     * 默认 false（方案1）。
     */
    val isFillComponentBannerEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_FILL_COMPONENT_USE_BANNER] ?: false
    }

    suspend fun setFillComponentBannerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FILL_COMPONENT_USE_BANNER] = enabled
        }
    }
    
    suspend fun setPasswordSuggestionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PASSWORD_SUGGESTION_ENABLED] = enabled
        }
    }

    /**
     * 是否自动更新重复密码
     * 启用后，保存已存在的用户名时自动更新密码而不提示用户
     */
    val isAutoUpdateDuplicatePasswordsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_UPDATE_DUPLICATE_PASSWORDS] ?: false
    }
    
    suspend fun setAutoUpdateDuplicatePasswordsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_UPDATE_DUPLICATE_PASSWORDS] = enabled
        }
    }
    
    /**
     * 保存密码时是否显示通知
     */
    val isShowSaveNotificationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SHOW_SAVE_NOTIFICATION] ?: true
    }
    
    suspend fun setShowSaveNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SHOW_SAVE_NOTIFICATION] = enabled
        }
    }
    
    /**
     * 是否启用智能标题生成
     * 启用后，自动从应用名或域名生成有意义的标题
     */
    val isSmartTitleGenerationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SMART_TITLE_GENERATION] ?: true
    }
    
    suspend fun setSmartTitleGenerationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SMART_TITLE_GENERATION] = enabled
        }
    }
    
    /**
     * 是否启用黑名单功能
     */
    val isBlacklistEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BLACKLIST_ENABLED] ?: true  // 默认启用
    }
    
    suspend fun setBlacklistEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BLACKLIST_ENABLED] = enabled
        }
    }
    
    /**
     * 黑名单应用包名集合
     */
    val blacklistPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_BLACKLIST_PACKAGES] ?: DEFAULT_BLACKLIST_PACKAGES
    }
    
    suspend fun setBlacklistPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BLACKLIST_PACKAGES] = packages
        }
    }
    
    /**
     * 添加应用到黑名单
     */
    suspend fun addToBlacklist(packageName: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_BLACKLIST_PACKAGES] ?: DEFAULT_BLACKLIST_PACKAGES
            preferences[KEY_BLACKLIST_PACKAGES] = current + packageName
        }
    }
    
    /**
     * 从黑名单移除应用
     */
    suspend fun removeFromBlacklist(packageName: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_BLACKLIST_PACKAGES] ?: DEFAULT_BLACKLIST_PACKAGES
            preferences[KEY_BLACKLIST_PACKAGES] = current - packageName
        }
    }

    val saveBlockedTargets: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_SAVE_BLOCKED_TARGETS] ?: emptySet()
    }

    private fun normalizeSavePackage(packageName: String): String? {
        val normalized = packageName.trim().lowercase()
        return normalized.takeIf { it.isNotBlank() }?.let { "pkg:$it" }
    }

    private fun normalizeSaveDomain(rawDomain: String): String? {
        val trimmed = rawDomain.trim().lowercase()
        if (trimmed.isBlank()) return null
        val host = runCatching {
            val uri = if (trimmed.contains("://")) Uri.parse(trimmed) else Uri.parse("https://$trimmed")
            uri.host
        }.getOrNull()
        val normalized = (host ?: trimmed).removePrefix("www.")
        return normalized.takeIf { it.isNotBlank() }?.let { "dom:$it" }
    }

    private fun normalizeSaveBlockedTargetKey(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return when {
            trimmed.startsWith("pkg:", ignoreCase = true) -> {
                normalizeSavePackage(trimmed.substringAfter(':').trim())
            }
            trimmed.startsWith("dom:", ignoreCase = true) -> {
                normalizeSaveDomain(trimmed.substringAfter(':').trim())
            }
            else -> null
        }
    }

    suspend fun addSaveBlockedTarget(packageName: String?, webDomain: String?) {
        val packageTarget = packageName?.let(::normalizeSavePackage)
        val domainTarget = webDomain?.let(::normalizeSaveDomain)
        if (packageTarget == null && domainTarget == null) return
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_SAVE_BLOCKED_TARGETS] ?: emptySet()
            val updated = buildSet {
                addAll(current)
                packageTarget?.let(::add)
                domainTarget?.let(::add)
            }
            preferences[KEY_SAVE_BLOCKED_TARGETS] = updated
        }
    }

    data class SaveBlockedTargetRecord(
        val key: String,
        val packageName: String? = null,
        val webDomain: String? = null,
    )

    val saveBlockedTargetRecords: Flow<List<SaveBlockedTargetRecord>> = saveBlockedTargets.map { targets ->
        targets.mapNotNull(::parseSaveBlockedTargetRecord)
    }

    suspend fun isSaveBlocked(packageName: String?, webDomain: String?): Boolean {
        val blockedTargets = saveBlockedTargets.first()
        if (blockedTargets.isEmpty()) return false
        val packageTarget = packageName?.let(::normalizeSavePackage)
        if (packageTarget != null && blockedTargets.contains(packageTarget)) return true
        val domainTarget = webDomain?.let(::normalizeSaveDomain)
        return domainTarget != null && blockedTargets.contains(domainTarget)
    }

    suspend fun removeSaveBlockedTarget(key: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_SAVE_BLOCKED_TARGETS] ?: emptySet()
            preferences[KEY_SAVE_BLOCKED_TARGETS] = current - key
        }
    }

    suspend fun clearSaveBlockedTargets() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_SAVE_BLOCKED_TARGETS)
        }
    }

    suspend fun importSaveBlockedTargets(
        rawKeys: Collection<String>,
        replaceExisting: Boolean = false,
    ) {
        val normalizedIncoming = rawKeys
            .mapNotNull(::normalizeSaveBlockedTargetKey)
            .toSet()
        if (normalizedIncoming.isEmpty()) return

        context.dataStore.edit { preferences ->
            val current = if (replaceExisting) {
                emptySet()
            } else {
                preferences[KEY_SAVE_BLOCKED_TARGETS] ?: emptySet()
            }
            preferences[KEY_SAVE_BLOCKED_TARGETS] = current + normalizedIncoming
        }
    }
    
    /**
     * 检查应用是否在黑名单中
     */
    suspend fun isInBlacklist(packageName: String): Boolean {
        val enabled = isBlacklistEnabled.first()
        if (!enabled) return false
        
        val packages = blacklistPackages.first()
        return packages.contains(packageName)
    }

    private fun parseSaveBlockedTargetRecord(raw: String): SaveBlockedTargetRecord? {
        return when {
            raw.startsWith("pkg:") -> {
                val packageName = raw.removePrefix("pkg:").trim().takeIf { it.isNotBlank() } ?: return null
                SaveBlockedTargetRecord(
                    key = raw,
                    packageName = packageName,
                )
            }
            raw.startsWith("dom:") -> {
                val webDomain = raw.removePrefix("dom:").trim().takeIf { it.isNotBlank() } ?: return null
                SaveBlockedTargetRecord(
                    key = raw,
                    webDomain = webDomain,
                )
            }
            else -> null
        }
    }

    data class LastFilledCredential(
        val identifier: String,
        val passwordId: Long,
        val timestamp: Long
    )

    data class AutofillInteractionState(
        val identifier: String,
        val startedAt: Long,
        val completed: Boolean,
        val lastFilledPasswordId: Long?,
        val lastFilledAt: Long
    )

    data class BlockedFieldSignatureRecord(
        val signatureKey: String,
        val packageName: String?,
        val webDomain: String?,
        val hints: List<String>,
        val blockedAt: Long,
    )

    private fun normalizeIdentifier(identifier: String): String {
        return identifier.trim().lowercase()
    }

    private fun normalizeFieldSignatureKey(signatureKey: String): String {
        return signatureKey.trim().lowercase()
    }

    private fun encodeBlockedFieldSignatureRecord(record: BlockedFieldSignatureRecord): String {
        return listOf(
            record.signatureKey,
            record.packageName.orEmpty(),
            record.webDomain.orEmpty(),
            record.hints.joinToString(","),
            record.blockedAt.toString(),
        ).joinToString(BLOCKED_FIELD_SIGNATURE_RECORD_DELIMITER) { value ->
            Uri.encode(value)
        }
    }

    private fun decodeBlockedFieldSignatureRecord(value: String): BlockedFieldSignatureRecord? {
        val parts = value.split(BLOCKED_FIELD_SIGNATURE_RECORD_DELIMITER)
        if (parts.size < 5) return null
        val signatureKey = Uri.decode(parts[0]).trim().lowercase()
        if (signatureKey.isBlank()) return null
        val packageName = Uri.decode(parts[1]).trim().ifBlank { null }
        val webDomain = Uri.decode(parts[2]).trim().ifBlank { null }
        val hints = Uri.decode(parts[3])
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val blockedAt = Uri.decode(parts[4]).toLongOrNull() ?: 0L
        return BlockedFieldSignatureRecord(
            signatureKey = signatureKey,
            packageName = packageName,
            webDomain = webDomain,
            hints = hints,
            blockedAt = blockedAt,
        )
    }

    suspend fun beginAutofillInteraction(identifier: String) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            preferences[KEY_INTERACTION_IDENTIFIER] = normalized
            preferences[KEY_INTERACTION_STARTED_AT] = now
            preferences[KEY_INTERACTION_COMPLETED] = false
        }
    }

    suspend fun touchAutofillInteraction(identifier: String) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            val existingIdentifier = preferences[KEY_INTERACTION_IDENTIFIER]
            if (existingIdentifier == normalized) {
                preferences[KEY_INTERACTION_STARTED_AT] = now
            } else {
                preferences[KEY_INTERACTION_IDENTIFIER] = normalized
                preferences[KEY_INTERACTION_STARTED_AT] = now
                preferences[KEY_INTERACTION_COMPLETED] = false
            }
        }
    }

    suspend fun completeAutofillInteraction(identifier: String, passwordId: Long) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        Log.i(
            "AutofillPreferences",
            "completeAutofillInteraction: completed=true, at=$now"
        )
        AutofillLogger.i(
            "PREFERENCES",
            "completeAutofillInteraction: completed=true, at=$now"
        )
        context.dataStore.edit { preferences ->
            val existingIdentifier = preferences[KEY_INTERACTION_IDENTIFIER]
            val existingStartedAt = preferences[KEY_INTERACTION_STARTED_AT]
            preferences[KEY_INTERACTION_IDENTIFIER] = normalized
            preferences[KEY_INTERACTION_STARTED_AT] = if (existingIdentifier == normalized) {
                existingStartedAt ?: now
            } else {
                now
            }
            preferences[KEY_INTERACTION_COMPLETED] = true
            preferences[KEY_LAST_FILLED_IDENTIFIER] = normalized
            preferences[KEY_LAST_FILLED_PASSWORD_ID] = passwordId
            preferences[KEY_LAST_FILLED_AT] = now
            // Reset suggestion stage so the next request starts from
            // "trigger + last filled" after a successful fill.
            preferences[KEY_SUGGESTION_STAGE_IDENTIFIER] = normalized
            preferences[KEY_SUGGESTION_STAGE] = 0
            preferences[KEY_SUGGESTION_STAGE_AT] = now
        }
    }

    suspend fun getAutofillInteractionState(identifier: String): AutofillInteractionState? {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return null
        val preferences = context.dataStore.data.first()
        val interactionIdentifier = preferences[KEY_INTERACTION_IDENTIFIER] ?: return null
        if (interactionIdentifier != normalized) return null
        val startedAt = preferences[KEY_INTERACTION_STARTED_AT] ?: return null
        val completed = preferences[KEY_INTERACTION_COMPLETED] ?: false
        val lastIdentifier = preferences[KEY_LAST_FILLED_IDENTIFIER]
        val lastFilledPasswordId = if (lastIdentifier == normalized) {
            preferences[KEY_LAST_FILLED_PASSWORD_ID]
        } else {
            null
        }
        val lastFilledAt = if (lastIdentifier == normalized) {
            preferences[KEY_LAST_FILLED_AT] ?: 0L
        } else {
            0L
        }
        return AutofillInteractionState(
            identifier = normalized,
            startedAt = startedAt,
            completed = completed,
            lastFilledPasswordId = lastFilledPasswordId,
            lastFilledAt = lastFilledAt
        )
    }

    suspend fun setLastFilledCredential(identifier: String, passwordId: Long) {
        completeAutofillInteraction(identifier, passwordId)
    }

    suspend fun getLastFilledCredential(identifier: String): LastFilledCredential? {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return null
        val preferences = context.dataStore.data.first()
        val storedIdentifier = preferences[KEY_LAST_FILLED_IDENTIFIER] ?: return null
        if (storedIdentifier != normalized) return null
        val passwordId = preferences[KEY_LAST_FILLED_PASSWORD_ID] ?: return null
        val timestamp = preferences[KEY_LAST_FILLED_AT] ?: 0L
        return LastFilledCredential(storedIdentifier, passwordId, timestamp)
    }

    suspend fun getSuggestionStage(identifier: String, validForMs: Long): Int? {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return null
        val preferences = context.dataStore.data.first()
        val stageIdentifier = preferences[KEY_SUGGESTION_STAGE_IDENTIFIER] ?: return null
        if (stageIdentifier != normalized) return null
        val updatedAt = preferences[KEY_SUGGESTION_STAGE_AT] ?: return null
        val now = System.currentTimeMillis()
        if (validForMs > 0 && now - updatedAt > validForMs) return null
        return preferences[KEY_SUGGESTION_STAGE] ?: 0
    }

    suspend fun setSuggestionStage(identifier: String, stage: Int) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            preferences[KEY_SUGGESTION_STAGE_IDENTIFIER] = normalized
            preferences[KEY_SUGGESTION_STAGE] = stage.coerceAtLeast(0)
            preferences[KEY_SUGGESTION_STAGE_AT] = now
        }
    }

    suspend fun clearSuggestionStage(identifier: String? = null) {
        val normalized = identifier?.let(::normalizeIdentifier)?.takeIf { it.isNotBlank() }
        context.dataStore.edit { preferences ->
            val currentIdentifier = preferences[KEY_SUGGESTION_STAGE_IDENTIFIER]
            if (normalized == null || currentIdentifier == normalized) {
                preferences.remove(KEY_SUGGESTION_STAGE_IDENTIFIER)
                preferences.remove(KEY_SUGGESTION_STAGE)
                preferences.remove(KEY_SUGGESTION_STAGE_AT)
            }
        }
    }

    suspend fun markFieldSignatureLearned(signatureKey: String) {
        val normalized = normalizeFieldSignatureKey(signatureKey)
        if (normalized.isBlank()) return
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_LEARNED_FIELD_SIGNATURES] ?: emptySet()
            val updated = if (!current.contains(normalized) && current.size >= 256) {
                (current - current.first()) + normalized
            } else {
                current + normalized
            }
            preferences[KEY_LEARNED_FIELD_SIGNATURES] = updated
        }
    }

    suspend fun isFieldSignatureLearned(signatureKey: String): Boolean {
        val normalized = normalizeFieldSignatureKey(signatureKey)
        if (normalized.isBlank()) return false
        val preferences = context.dataStore.data.first()
        val signatures = preferences[KEY_LEARNED_FIELD_SIGNATURES] ?: emptySet()
        return signatures.contains(normalized)
    }

    val blockedFieldSignatureRecords: Flow<List<BlockedFieldSignatureRecord>> = context.dataStore.data.map { preferences ->
        (preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] ?: emptySet())
            .mapNotNull(::decodeBlockedFieldSignatureRecord)
            .sortedByDescending { it.blockedAt }
    }

    suspend fun markFieldSignatureBlocked(
        signatureKey: String,
        packageName: String? = null,
        webDomain: String? = null,
        hints: List<String> = emptyList(),
    ) {
        val normalized = normalizeFieldSignatureKey(signatureKey)
        if (normalized.isBlank()) return
        val normalizedPackageName = packageName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedWebDomain = webDomain?.trim()?.takeIf { it.isNotBlank() }
        val normalizedHints = hints
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        val record = BlockedFieldSignatureRecord(
            signatureKey = normalized,
            packageName = normalizedPackageName,
            webDomain = normalizedWebDomain,
            hints = normalizedHints,
            blockedAt = System.currentTimeMillis(),
        )
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_BLOCKED_FIELD_SIGNATURES] ?: emptySet()
            val updated = if (!current.contains(normalized) && current.size >= 256) {
                (current - current.first()) + normalized
            } else {
                current + normalized
            }
            preferences[KEY_BLOCKED_FIELD_SIGNATURES] = updated
            val currentRecords = preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] ?: emptySet()
            val filteredRecords = currentRecords.filterNot { stored ->
                decodeBlockedFieldSignatureRecord(stored)?.signatureKey == normalized
            }.toMutableSet()
            filteredRecords += encodeBlockedFieldSignatureRecord(record)
            preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] = filteredRecords
        }
    }

    suspend fun isFieldSignatureBlocked(signatureKey: String): Boolean {
        val normalized = normalizeFieldSignatureKey(signatureKey)
        if (normalized.isBlank()) return false
        val preferences = context.dataStore.data.first()
        val signatures = preferences[KEY_BLOCKED_FIELD_SIGNATURES] ?: emptySet()
        return signatures.contains(normalized)
    }

    suspend fun removeBlockedFieldSignature(signatureKey: String) {
        val normalized = normalizeFieldSignatureKey(signatureKey)
        if (normalized.isBlank()) return
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_BLOCKED_FIELD_SIGNATURES] ?: emptySet()
            preferences[KEY_BLOCKED_FIELD_SIGNATURES] = current - normalized
            val currentRecords = preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] ?: emptySet()
            preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] = currentRecords.filterNot { stored ->
                decodeBlockedFieldSignatureRecord(stored)?.signatureKey == normalized
            }.toSet()
        }
    }

    suspend fun clearBlockedFieldSignatures() {
        context.dataStore.edit { preferences ->
            preferences[KEY_BLOCKED_FIELD_SIGNATURES] = emptySet()
            preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] = emptySet()
        }
    }

    suspend fun importBlockedFieldSignatureRecords(
        records: List<BlockedFieldSignatureRecord>,
        replaceExisting: Boolean = false,
    ) {
        val normalizedIncoming = records.mapNotNull { record ->
            val normalizedSignatureKey = normalizeFieldSignatureKey(record.signatureKey)
            if (normalizedSignatureKey.isBlank()) {
                null
            } else {
                BlockedFieldSignatureRecord(
                    signatureKey = normalizedSignatureKey,
                    packageName = record.packageName?.trim()?.takeIf { it.isNotBlank() },
                    webDomain = record.webDomain?.trim()?.takeIf { it.isNotBlank() },
                    hints = record.hints
                        .map { it.trim().uppercase() }
                        .filter { it.isNotBlank() }
                        .distinct(),
                    blockedAt = record.blockedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
            }
        }
        if (normalizedIncoming.isEmpty()) return

        context.dataStore.edit { preferences ->
            val merged = linkedMapOf<String, BlockedFieldSignatureRecord>()

            if (!replaceExisting) {
                (preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] ?: emptySet())
                    .mapNotNull(::decodeBlockedFieldSignatureRecord)
                    .forEach { record ->
                        merged[record.signatureKey] = record
                    }
            }

            normalizedIncoming.forEach { incoming ->
                val existing = merged[incoming.signatureKey]
                merged[incoming.signatureKey] = when {
                    existing == null -> incoming
                    incoming.blockedAt >= existing.blockedAt -> incoming.copy(
                        packageName = incoming.packageName ?: existing.packageName,
                        webDomain = incoming.webDomain ?: existing.webDomain,
                        hints = (incoming.hints + existing.hints).distinct(),
                    )
                    else -> existing.copy(
                        packageName = existing.packageName ?: incoming.packageName,
                        webDomain = existing.webDomain ?: incoming.webDomain,
                        hints = (existing.hints + incoming.hints).distinct(),
                    )
                }
            }

            val finalRecords = merged.values
                .sortedByDescending { it.blockedAt }
                .take(256)
            preferences[KEY_BLOCKED_FIELD_SIGNATURES] = finalRecords.map { it.signatureKey }.toSet()
            preferences[KEY_BLOCKED_FIELD_SIGNATURE_RECORDS] = finalRecords
                .map(::encodeBlockedFieldSignatureRecord)
                .toSet()
        }
    }
}


