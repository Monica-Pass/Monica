package takagi.ru.monica.autofill_ng

import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.text.InputType
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.AutofillSaveTransparentActivity
import takagi.ru.monica.autofill_ng.DomainMatchStrategy
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.ParsedItem
import takagi.ru.monica.autofill_ng.processor.AutofillProcessorNg
import takagi.ru.monica.autofill_ng.auth.AutofillGrantContext
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.autofill_ng.auth.AutofillUnlockRequests
import takagi.ru.monica.autofill_ng.core.AutofillDiagnostics
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.core.safeTextOrNull
import takagi.ru.monica.autofill_ng.BitwardenLikeAutofillMatcherNg
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.service.BrowserAutofillContextStore
import takagi.ru.monica.utils.DeviceUtils
import takagi.ru.monica.utils.SettingsManager
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Autofill service (Bitwarden engine).
 *
 * This service intentionally keeps a single deterministic pipeline:
 * parser -> bitwarden-style matcher -> bw-compatible response.
 */
class MonicaAutofillServiceNg : AutofillService() {
    private companion object {
        private val PACKAGE_NAME_REGEX =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private const val PARSED_ITEM_ACCURACY_THRESHOLD = 1.5f
        private const val PASSWORD_ONLY_DIRECT_FILL_WINDOW_MS = 120_000L
        private const val RESPONSE_STABILITY_WINDOW_MS = 2_000L
        private val fillRequestSequence = AtomicLong(0L)
    }

    /**
     * 跨请求登录字段记忆：缓存各包最近一次识别到的登录字段列表（账号 + 密码）。
     * 用于应对电影猎手等 App 的密码框在部分 FillRequest 中因布局/动画时序而不可见、
     * 被解析器丢弃，连带账号也被低精度过滤清掉，导致登录目标「时有时无」。
     * 仅当缓存的所有登录字段 AutofillId 仍存在于当前 AssistStructure 时才整体回补，
     * 避免注入失效 id。
     */
    private val passwordMemoryByPackage = mutableMapOf<String, List<ParsedItem>>()

    private data class RecentFillSuggestions(
        val key: String,
        val createdAtMs: Long,
        val requestId: Long,
        val targetCount: Int,
        val passwords: List<PasswordEntry>,
    )

    private data class StructuredConfidenceDecision(
        val highConfidence: Boolean,
        val reason: String,
        val structuredCount: Int,
        val bankCardCount: Int,
        val documentCount: Int,
        val dominantCount: Int,
        val keyHintCount: Int,
        val confidentCount: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var passwordRepository: PasswordRepository
    private lateinit var autofillPreferences: AutofillPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var diagnostics: AutofillDiagnostics

    private val parser = EnhancedAutofillStructureParserV2()
    private val matcher = BitwardenLikeAutofillMatcherNg()
    private val bwCompatProcessor by lazy { AutofillProcessorNg(applicationContext) }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                matcher.clear()
                AutofillSessionGrants.clear()
                AutofillUnlockRequests.clear()
                AutofillLogger.i("AUTH", "Temporary autofill grant cleared on screen off")
            }
        }
    }
    private var screenOffReceiverRegistered = false
    @Volatile
    private var recentFillSuggestions: RecentFillSuggestions? = null

    private val strings by lazy { AppLocaleStringResolver(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate() {
        super.onCreate()
        AutofillLogger.initialize(applicationContext)

        val database = PasswordDatabase.getDatabase(applicationContext)
        passwordRepository = PasswordRepository(database.passwordEntryDao())
        autofillPreferences = AutofillPreferences(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        diagnostics = AutofillDiagnostics(applicationContext)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffReceiverRegistered = true
        scope.launch {
            runCatching { autofillPreferences.ensureBitwardenV2EngineMode() }
                .onFailure { AutofillLogger.w("AF", "Failed to enforce V2 engine mode: ${it.message}") }
        }
        scope.launch(Dispatchers.Default) {
            takagi.ru.monica.security.SessionManager.isUnlocked.collect { unlocked ->
                if (!unlocked) {
                    matcher.clear()
                    recentFillSuggestions = null
                }
            }
        }

        AutofillLogger.i("AF", "MonicaAutofillServiceNg created")
    }

    override fun onDestroy() {
        matcher.clear()
        AutofillSessionGrants.clear()
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        if (cancellationSignal.isCanceled) return
        val requestId = fillRequestSequence.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val completed = AtomicBoolean(false)
        AutofillLogger.i(
            "AF",
            "onFillRequest received",
            metadata = mapOf(
                "requestId" to requestId,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "flags" to request.flags,
                "fillContextCount" to request.fillContexts.size,
                "cancelled" to cancellationSignal.isCanceled,
            )
        )
        val job = scope.launch {
            try {
                val response = withContext(Dispatchers.Default) {
                    processFillRequest(request, cancellationSignal, requestId)
                }
                if (cancellationSignal.isCanceled || !completed.compareAndSet(false, true)) return@launch
                AutofillLogger.i(
                    "AF",
                    "onFillRequest callback success",
                    metadata = mapOf(
                        "requestId" to requestId,
                        "hasResponse" to (response != null),
                        "cancelled" to cancellationSignal.isCanceled,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    )
                )
                callback.onSuccess(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancellationSignal.isCanceled || !completed.compareAndSet(false, true)) return@launch
                AutofillLogger.e(
                    "AF",
                    "onFillRequest failed",
                    e,
                    metadata = mapOf(
                        "requestId" to requestId,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    )
                )
                diagnostics.logError("AF", "Fill request failed: ${e.message}", e)
                callback.onFailure(e.message ?: strings.get(R.string.legacy_ui_autofill_failed))
            }
        }
        cancellationSignal.setOnCancelListener {
            completed.set(true)
            AutofillLogger.w(
                "AF",
                "onFillRequest cancelled by system",
                metadata = mapOf(
                    "requestId" to requestId,
                    "elapsedMs" to (System.currentTimeMillis() - startedAt),
                )
            )
            job.cancel()
        }
    }

    private suspend fun processFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        requestId: Long,
    ): FillResponse? {
        if (cancellationSignal.isCanceled) {
            AutofillLogger.w("AF", "Skip fill request: already cancelled", metadata = mapOf("requestId" to requestId))
            return null
        }
        if (!autofillPreferences.isAutofillEnabled.first()) {
            AutofillLogger.i("AF", "Skip fill request: app autofill disabled", metadata = mapOf("requestId" to requestId))
            return null
        }

        val fillContext = request.fillContexts.lastOrNull() ?: run {
            AutofillLogger.i("AF", "Skip fill request: no fill context", metadata = mapOf("requestId" to requestId))
            return null
        }
        val structure = fillContext.structure
        val fallbackPackage = structure.activityComponent?.packageName.orEmpty()

        if (fallbackPackage.isNotBlank() && autofillPreferences.isInBlacklist(fallbackPackage)) {
            AutofillLogger.i(
                "AF",
                "Package blocked by blacklist: $fallbackPackage",
                metadata = mapOf("requestId" to requestId)
            )
            return null
        }

        val respectAutofillOff = autofillPreferences.isV2RespectAutofillOffEnabled.first()
        val isManualRequest = request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0
        var parsed = parser.parse(
            structure = structure,
            respectAutofillOff = respectAutofillOff,
            allowWeakTargets = isManualRequest,
        )
        // 兼容回退（Bitwarden 式）：部分 App（如影视类自定义登录框）的登录字段没有标准
        // autofill hint，首轮保守解析会丢弃全部字段导致不弹面板。此时二次解析开启弱目标，
        // 再由 selectFillableTargets / shouldKeepTarget / isSupportedFillableHint 过滤掉
        // 搜索框、昵称等非登录误报。仅自动模式触发，手动模式本就开启弱目标。
        var usedWeakReparse = false
        if (!isManualRequest) {
            val firstPassTargets = selectFillableTargets(parsed.items, isManualRequest)
            AutofillLogger.d(
                "AF",
                "DIAG firstPassTargets",
                metadata = mapOf(
                    "requestId" to requestId,
                    "firstPassTargets" to firstPassTargets.size,
                    "hints" to firstPassTargets.joinToString { it.hint.name },
                    "parsedItems" to parsed.items.size,
                    "parsedHints" to parsed.items.joinToString { "${it.hint}:${it.accuracy.name}" },
                )
            )
            if (firstPassTargets.isEmpty()) {
                val weakParsed = parser.parse(
                    structure = structure,
                    respectAutofillOff = respectAutofillOff,
                    allowWeakTargets = true,
                    requireExplicitWeakLoginSignal = true,
                )
                AutofillLogger.d(
                    "AF",
                    "Weak-target compatibility reparse triggered",
                    metadata = mapOf(
                        "requestId" to requestId,
                        "firstPassItemCount" to parsed.items.size,
                        "weakItemCount" to weakParsed.items.size,
                    )
                )
                parsed = weakParsed
                usedWeakReparse = true
            }
        }
        // 兼容回退（Bitwarden 式）：仅当屏幕已进入登录上下文（已识别到账号类字段
        // 或密码字段）时，才把当前聚焦的字段纳入可填充目标，用于补全被漏识别的
        // 登录字段（如影视类 App 的账号框/使用 VISIBLE_PASSWORD 变体的密码框）。
        // 非登录屏幕（搜索框/备注/昵称等，且无密码框）不会触发此逻辑，避免误弹密码建议。
        val hasAccountTarget = parsed.items.any {
            it.hint == FieldHint.USERNAME ||
                it.hint == FieldHint.EMAIL_ADDRESS ||
                it.hint == FieldHint.PHONE_NUMBER
        }
        val hasPasswordTarget = parsed.items.any {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }
        val focusedSyntheticItems = if (hasAccountTarget || hasPasswordTarget) {
            buildFocusedSyntheticItems(structure, parsed.items, hasPasswordTarget, parsed.scopeAutofillIds)
        } else {
            emptyList()
        }
        if (focusedSyntheticItems.isNotEmpty()) {
            parsed = parsed.copy(items = parsed.items + focusedSyntheticItems)
        }
        // 跨请求登录字段记忆与回补：电影猎手等 App 的密码框在部分 FillRequest 中因
        // 布局/动画时序而不可见、被解析器连同账号一起丢弃，导致登录目标「时有时无」。
        // 识别到登录字段时缓存（账号+密码）；后续同包请求若缺失密码、且缓存的所有登录
        // 字段 id 仍存在于当前结构（可见或不可见均可）时，整体回补，保证面板稳定出现。
        val pkgKey = listOf(parsed.applicationId ?: fallbackPackage, parsed.webScheme, parsed.webDomain)
            .joinToString("|")
        val currentPasswordItems = parsed.items.filter {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }
        val currentHasLoginContext = parsed.items.any { isLoginHint(it.hint) }
        synchronized(passwordMemoryByPackage) {
            if (currentPasswordItems.isNotEmpty()) {
                passwordMemoryByPackage[pkgKey] = parsed.items.filter { isLoginHint(it.hint) }
            } else if (currentHasLoginContext) {
                val cached = passwordMemoryByPackage[pkgKey]
                if (cached != null && cached.isNotEmpty() &&
                    cached.all { it.id in parsed.scopeAutofillIds }
                ) {
                    val baseIndex = parsed.items.maxOfOrNull { it.traversalIndex } ?: 0
                    val recovered = cached
                        .filter { c -> parsed.items.none { it.id == c.id } }
                        .mapIndexed { index, c ->
                            c.copy(
                                isVisible = findNodeVisibility(structure, c.id) ?: c.isVisible,
                                isFocused = false,
                                accuracy = EnhancedAutofillStructureParserV2.Accuracy.MEDIUM,
                                traversalIndex = baseIndex + index + 1,
                            )
                        }
                    if (recovered.isNotEmpty()) {
                        parsed = parsed.copy(items = parsed.items + recovered)
                        AutofillLogger.d(
                            "AF",
                            "Login targets recovered from memory",
                            metadata = mapOf(
                                "requestId" to requestId,
                                "packageName" to pkgKey,
                                "recoveredCount" to recovered.size,
                                "recoveredHints" to recovered.joinToString { it.hint.name },
                            )
                        )
                    }
                }
            }
        }
        val packageName = resolveEffectivePackageName(
            parsedApplicationId = parsed.applicationId,
            fallbackPackage = fallbackPackage,
        )
        if (isSelfPackage(packageName)) {
            AutofillLogger.i("AF", "Skip fill request for Monica itself: $packageName")
            return null
        }
        val isCompatMode = (request.flags or FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST) == request.flags
        AutofillLogger.i(
            "AF",
            "Autofill request source diagnostics",
            metadata = mapOf(
                "sdk" to Build.VERSION.SDK_INT,
                "requestId" to requestId,
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "windowNodeCount" to structure.windowNodeCount,
                "fallbackPackage" to if (fallbackPackage.isBlank()) "none" else fallbackPackage,
                "parsedApplicationId" to (parsed.applicationId ?: "none"),
                "resolvedPackageName" to if (packageName.isBlank()) "none" else packageName,
                "parsedWebDomain" to (parsed.webDomain ?: "none"),
                "parsedWebScheme" to (parsed.webScheme ?: "none"),
                "parsedItemCount" to parsed.items.size,
                "respectAutofillOff" to respectAutofillOff,
                "compatMode" to isCompatMode,
                "manualRequest" to isManualRequest,
            )
        )
        if (packageName.isBlank()) {
            AutofillLogger.i(
                "AF",
                "Skip request: empty resolved package name",
                metadata = mapOf(
                    "requestId" to requestId,
                    "fallbackPackage" to if (fallbackPackage.isBlank()) "none" else fallbackPackage,
                    "parsedApplicationId" to (parsed.applicationId ?: "none"),
                    "parsedItemCount" to parsed.items.size,
                )
            )
            return null
        }

        // 自动兼容重解析仍使用自动请求的选择门槛。解析器只会把带明确登录术语的
        // 弱账号字段提升到 MEDIUM，不能借用手动请求的全放行权限。
        val fillableTargets = selectFillableTargets(
            items = parsed.items,
            manualRequest = isManualRequest,
        )
        AutofillLogger.d(
            "AF",
            "DIAG fillableTargets",
            metadata = mapOf(
                "requestId" to requestId,
                "fillableTargets" to fillableTargets.size,
                "hints" to fillableTargets.joinToString { it.hint.name },
                "usedWeakReparse" to usedWeakReparse,
                "hasLoginTargets" to fillableTargets.any { isLoginHint(it.hint) },
                "hasPasswordTarget" to (fillableTargets.any { it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD }),
            )
        )
        if (fillableTargets.isEmpty()) {
            AutofillLogger.i(
                "AF",
                "No supported autofill fields detected",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "parsedItemCount" to parsed.items.size,
                    "parsedHintPreview" to parsed.items.take(8).joinToString(",") { it.hint.name },
                )
            )
            return null
        }
        val parsedWebDomain = parsed.webDomain?.takeIf { it.isNotBlank() }
        val browserFallbackDomain = if (parsedWebDomain == null) {
            val fallback = BrowserAutofillContextStore.getRecentDomain(packageName)
            AutofillLogger.i(
                "AF",
                "webDomain is null from AssistStructure, accessibility fallback=${fallback ?: "none"}",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "parsedWebScheme" to (parsed.webScheme ?: "none"),
                    "fallbackDomain" to (fallback ?: "none"),
                ),
            )
            fallback
        } else {
            null
        }
        val webDomain = parsedWebDomain ?: browserFallbackDomain
        val allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
            packageName = packageName,
            webDomain = webDomain,
            isWebView = parsed.webView,
        )
        val loginTargetCount = fillableTargets.count { isLoginHint(it.hint) }
        val structuredTargetCount = fillableTargets.size - loginTargetCount
        val structuredDecision = evaluateStructuredConfidence(fillableTargets)
        if (!isManualRequest && loginTargetCount == 0 && !structuredDecision.highConfidence) {
            AutofillLogger.i(
                "AF",
                "Skip weak structured autofill request",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targetCount" to fillableTargets.size,
                    "structuredTargetCount" to structuredTargetCount,
                    "structuredReason" to structuredDecision.reason,
                    "structuredCount" to structuredDecision.structuredCount,
                    "bankCardCount" to structuredDecision.bankCardCount,
                    "documentCount" to structuredDecision.documentCount,
                    "dominantCount" to structuredDecision.dominantCount,
                    "keyHintCount" to structuredDecision.keyHintCount,
                    "confidentCount" to structuredDecision.confidentCount,
                ),
            )
            return null
        }
        val fieldSignatureKey = buildFieldSignatureKey(
            packageName = packageName,
            webDomain = webDomain,
            credentialTargets = fillableTargets,
        )
        AutofillLogger.i(
            "AF",
            "Autofill target diagnostics",
            metadata = mapOf(
                "requestId" to requestId,
                "packageName" to packageName,
                "webDomain" to (webDomain ?: "none"),
                "webView" to parsed.webView,
                "packageMatchAllowed" to allowPackageMatch,
                "domainSource" to when {
                    parsedWebDomain != null -> "assist_structure"
                    browserFallbackDomain != null -> "accessibility_browser_context"
                    else -> "none"
                },
                "targetCount" to fillableTargets.size,
                "loginTargetCount" to loginTargetCount,
                "structuredTargetCount" to structuredTargetCount,
                "structuredHighConfidence" to structuredDecision.highConfidence,
                "structuredReason" to structuredDecision.reason,
                "structuredCount" to structuredDecision.structuredCount,
                "bankCardCount" to structuredDecision.bankCardCount,
                "documentCount" to structuredDecision.documentCount,
                "dominantCount" to structuredDecision.dominantCount,
                "keyHintCount" to structuredDecision.keyHintCount,
                "confidentCount" to structuredDecision.confidentCount,
                "fieldSignaturePresent" to !fieldSignatureKey.isNullOrBlank(),
                "fieldSignatureKey" to (fieldSignatureKey ?: "none"),
                "focusedTargetCount" to fillableTargets.count { it.isFocused },
                "visibleTargetCount" to fillableTargets.count { it.isVisible },
                "targetRolePreview" to fillableTargets.take(12).mapIndexed { index, target ->
                    "$index:${target.hint.name}:${target.accuracy.name}:" +
                        "${if (target.isFocused) "focused" else "idle"}:" +
                        if (target.isVisible) "visible" else "hidden"
                }.joinToString(separator = ","),
            )
        )
        if (!fieldSignatureKey.isNullOrBlank() &&
            autofillPreferences.isFieldSignatureBlocked(fieldSignatureKey)
        ) {
            AutofillLogger.i(
                "AF",
                "Skip autofill request: blocked field signature",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targetCount" to fillableTargets.size,
                ),
            )
            return blockedAutofillResponse(fillableTargets.map { it.id })
        }
        val effectiveScheme = parsed.webScheme?.takeIf { it.isNotBlank() } ?: "https"
        val requestUri = webDomain?.let { "$effectiveScheme://$it" } ?: "androidapp://$packageName"
        val appDisplayName = resolveAppDisplayName(packageName)
        val interactionContext = AutofillInteractionContextResolver.build(
            packageName = packageName,
            webDomain = webDomain,
        )
        val primaryInteractionIdentifier = interactionContext.primaryIdentifier
        if (!primaryInteractionIdentifier.isNullOrBlank()) {
            autofillPreferences.touchAutofillInteraction(primaryInteractionIdentifier)
        }

        val hasLoginTargets = fillableTargets.any { isLoginHint(it.hint) }
        val isPasswordOnlyLogin = AutofillInteractionContextResolver.isPasswordOnlyLogin(fillableTargets)
        var candidatePasswordCount = 0
        val matchedPasswords = if (hasLoginTargets) {
            val allPasswords = passwordRepository.getAllPasswordEntries().first().filterNot { it.isKeyCredential() }
            val sourceFilter = autofillPreferences.v2DefaultSourceFilter.first()
            val defaultKeepassDatabaseId = autofillPreferences.v2DefaultKeepassDatabaseId.first()
            val defaultBitwardenVaultId = autofillPreferences.v2DefaultBitwardenVaultId.first()
            val scopedPasswords = applyDefaultSourceFilter(
                entries = allPasswords,
                sourceFilter = sourceFilter,
                keepassDatabaseId = defaultKeepassDatabaseId,
                bitwardenVaultId = defaultBitwardenVaultId,
            )
            candidatePasswordCount = scopedPasswords.size
            val strictOnly = autofillPreferences.isBitwardenStrictModeEnabled.first()
            val allowSubdomainToggle = autofillPreferences.isBitwardenSubdomainMatchEnabled.first()
            val uriStrategy = autofillPreferences.domainMatchStrategy.first()
            val uriConfig = resolveUriStrategyConfig(uriStrategy, allowSubdomainToggle)
            if (uriConfig.disableMatch) {
                emptyList()
            } else {
                val rankedMatches = matcher.match(
                    entries = scopedPasswords,
                    packageName = packageName,
                    webDomain = webDomain,
                    appDisplayName = appDisplayName,
                    config = BitwardenLikeAutofillMatcherNg.Config(
                        strictOnly = strictOnly,
                        allowSubdomainMatch = uriConfig.allowSubdomainMatch,
                        allowBaseDomainMatch = uriConfig.allowBaseDomainMatch,
                        exactDomainOnly = uriConfig.exactDomainOnly,
                        allowPackageMatch = allowPackageMatch,
                        maxSuggestions = Int.MAX_VALUE,
                    ),
                )
                val passwordOnlyLastFilledEntry = if (isPasswordOnlyLogin) {
                    resolveLastFilledEntry(
                        entries = scopedPasswords,
                        interactionContext = interactionContext,
                    )
                } else {
                    null
                }
                val directFillEntry = if (isPasswordOnlyLogin) {
                    resolvePasswordOnlyDirectFillEntry(
                        rankedMatches = rankedMatches,
                        lastFilled = passwordOnlyLastFilledEntry,
                        interactionContext = interactionContext,
                    )
                } else {
                    null
                }
                val prioritized = if (directFillEntry != null) {
                    listOf(directFillEntry)
                } else {
                    AutofillInteractionContextResolver.prioritizeLastFilled(
                        entries = rankedMatches,
                        lastFilled = passwordOnlyLastFilledEntry,
                    )
                }
                // 补丁：系统 Wi-Fi 设置页的密码输入框没有 webDomain，也匹配
                // 不到 appPackageName；这里补上所有 WIFI 条目作为候选。
                WifiAutofillAssist.augmentWithWifiEntries(
                    originalRanked = prioritized,
                    allEntries = scopedPasswords,
                    packageName = packageName,
                    maxSuggestions = Int.MAX_VALUE,
                )
            }
        } else {
            emptyList()
        }

        val responseStabilityKey = buildResponseStabilityKey(
            packageName = packageName,
            webDomain = webDomain,
            fieldSignatureKey = fieldSignatureKey,
            fillableTargets = fillableTargets,
        )
        val passwordsForResponse = stabilizeMatchedPasswords(
            key = responseStabilityKey,
            matchedPasswords = matchedPasswords,
            requestId = requestId,
            targetCount = fillableTargets.size,
        )

        diagnostics.logPasswordMatching(
            packageName = packageName,
            domain = webDomain,
            matchStrategy = if (hasLoginTargets) {
                if (isPasswordOnlyLogin) "bitwarden_v2_hybrid_password_only"
                else "bitwarden_v2_hybrid"
            } else {
                "structured_manual_picker"
            },
            totalPasswords = candidatePasswordCount,
            matchedPasswords = passwordsForResponse.size,
        )

        val inlineRequest = if (autofillPreferences.isInlineSuggestionsEnabled.first()) {
            getInlineRequest(request)
        } else {
            null
        }
        val currentSettings = settingsManager.settingsFlow.first()
        val autofillAuthRequired = currentSettings.autofillAuthRequired &&
            DeveloperVerificationPolicy.requiresIdentityVerification(currentSettings)
        val grantContext = AutofillGrantContext(
            packageName = packageName,
            webDomain = webDomain,
            interactionIdentifier = primaryInteractionIdentifier,
            fieldSignatureKey = fieldSignatureKey,
        )
        val grantActive = autofillAuthRequired && AutofillSessionGrants.isGranted(grantContext)
        if (!autofillAuthRequired) {
            AutofillSessionGrants.clear()
        }
        val effectiveAuthenticationRequired = autofillAuthRequired && !grantActive
        AutofillLogger.i(
            "AUTH",
            "Autofill authentication policy resolved",
            metadata = mapOf(
                "settingEnabled" to autofillAuthRequired,
                "grantActive" to grantActive,
                "authenticationRequired" to effectiveAuthenticationRequired,
                "packageName" to packageName,
                "webDomain" to (webDomain ?: "none"),
            )
        )
        val response = bwCompatProcessor.process(
            packageName = packageName,
            uri = requestUri,
            fillableTargets = fillableTargets,
            inlineRequest = inlineRequest,
            isCompatMode = isCompatMode,
            passwords = passwordsForResponse,
            fieldSignatureKey = fieldSignatureKey,
            preferDirectAutoFill = isPasswordOnlyLogin && passwordsForResponse.size == 1,
            passwordSuggestionEnabled = autofillPreferences.isPasswordSuggestionEnabled.first(),
            requireAuthentication = effectiveAuthenticationRequired,
        )

        if (response == null) {
            AutofillLogger.w(
                "AF",
                "No fill response produced",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targets" to fillableTargets.size,
                    "matches" to passwordsForResponse.size,
                    "rawMatches" to matchedPasswords.size,
                    "candidatePasswords" to candidatePasswordCount,
                    "responseStabilityKey" to responseStabilityKey,
                )
            )
        } else {
            AutofillLogger.i(
                "AF",
                "Fill response ready",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targets" to fillableTargets.size,
                    "matches" to passwordsForResponse.size,
                    "rawMatches" to matchedPasswords.size,
                    "candidatePasswords" to candidatePasswordCount,
                    "authRequired" to autofillAuthRequired,
                    "inlineRequest" to (inlineRequest != null),
                    "sdk" to Build.VERSION.SDK_INT,
                    "responseStabilityKey" to responseStabilityKey,
                )
            )
        }
        return response
    }

    private fun buildResponseStabilityKey(
        packageName: String,
        webDomain: String?,
        fieldSignatureKey: String?,
        fillableTargets: List<ParsedItem>,
    ): String {
        val fieldKey = fieldSignatureKey?.takeIf { it.isNotBlank() }
        if (fieldKey != null) {
            return listOf(
                packageName.trim().lowercase(),
                webDomain.orEmpty().trim().lowercase(),
                fieldKey,
            ).joinToString("|")
        }
        val targetShape = fillableTargets
            .sortedBy { it.traversalIndex }
            .joinToString(",") { "${it.hint.name}:${it.isFocused}:${it.isVisible}" }
        return listOf(
            packageName.trim().lowercase(),
            webDomain.orEmpty().trim().lowercase(),
            targetShape,
        ).joinToString("|")
    }

    private fun stabilizeMatchedPasswords(
        key: String,
        matchedPasswords: List<PasswordEntry>,
        requestId: Long,
        targetCount: Int,
    ): List<PasswordEntry> {
        val now = System.currentTimeMillis()
        val cached = recentFillSuggestions
        val cachedIsUsable = cached != null &&
            cached.key == key &&
            now - cached.createdAtMs <= RESPONSE_STABILITY_WINDOW_MS &&
            cached.targetCount >= targetCount &&
            cached.passwords.size > matchedPasswords.size

        if (cachedIsUsable) {
            AutofillLogger.w(
                "AF",
                "Reusing recent stronger fill suggestions",
                metadata = mapOf(
                    "requestId" to requestId,
                    "previousRequestId" to cached!!.requestId,
                    "ageMs" to (now - cached.createdAtMs),
                    "previousMatches" to cached.passwords.size,
                    "currentMatches" to matchedPasswords.size,
                    "targetCount" to targetCount,
                ),
            )
            return cached.passwords
        }

        if (matchedPasswords.isNotEmpty()) {
            recentFillSuggestions = RecentFillSuggestions(
                key = key,
                createdAtMs = now,
                requestId = requestId,
                targetCount = targetCount,
                passwords = matchedPasswords,
            )
        }
        return matchedPasswords
    }

    private suspend fun resolveLastFilledEntry(
        entries: List<PasswordEntry>,
        interactionContext: AutofillInteractionContext,
    ): PasswordEntry? {
        var lastFilledPasswordId: Long? = null
        for (identifier in interactionContext.allIdentifiers) {
            lastFilledPasswordId = autofillPreferences.getLastFilledCredential(identifier)?.passwordId
            if (lastFilledPasswordId != null) break
        }
        val resolvedPasswordId = lastFilledPasswordId ?: return null
        return entries.firstOrNull { it.id == resolvedPasswordId }
    }

    private suspend fun resolvePasswordOnlyDirectFillEntry(
        rankedMatches: List<PasswordEntry>,
        lastFilled: PasswordEntry?,
        interactionContext: AutofillInteractionContext,
    ): PasswordEntry? {
        val candidate = lastFilled ?: return null
        if (rankedMatches.none { it.id == candidate.id }) return null

        val now = System.currentTimeMillis()
        val recentInteraction = interactionContext.allIdentifiers.firstNotNullOfOrNull { identifier ->
            autofillPreferences.getAutofillInteractionState(identifier)
        } ?: return null
        if (!recentInteraction.completed) return null
        if (recentInteraction.lastFilledPasswordId != candidate.id) return null
        if (recentInteraction.lastFilledAt <= 0L) return null
        if (now - recentInteraction.lastFilledAt > PASSWORD_ONLY_DIRECT_FILL_WINDOW_MS) return null

        AutofillLogger.i(
            "AF",
            "Using password-only direct autofill continuation",
            metadata = mapOf(
                "passwordId" to candidate.id,
                "interactionId" to recentInteraction.identifier,
                "elapsedMs" to (now - recentInteraction.lastFilledAt),
            )
        )
        return candidate
    }

    private data class UriStrategyConfig(
        val allowSubdomainMatch: Boolean,
        val allowBaseDomainMatch: Boolean,
        val exactDomainOnly: Boolean,
        val disableMatch: Boolean = false,
    )

    private fun resolveUriStrategyConfig(
        strategy: DomainMatchStrategy,
        allowSubdomainToggle: Boolean,
    ): UriStrategyConfig {
        return when (strategy) {
            DomainMatchStrategy.BASE_DOMAIN -> UriStrategyConfig(
                allowSubdomainMatch = allowSubdomainToggle,
                allowBaseDomainMatch = true,
                exactDomainOnly = false,
            )

            DomainMatchStrategy.DOMAIN -> UriStrategyConfig(
                allowSubdomainMatch = allowSubdomainToggle,
                allowBaseDomainMatch = false,
                exactDomainOnly = false,
            )

            DomainMatchStrategy.EXACT_MATCH -> UriStrategyConfig(
                allowSubdomainMatch = false,
                allowBaseDomainMatch = false,
                exactDomainOnly = true,
            )

            DomainMatchStrategy.NEVER -> UriStrategyConfig(
                allowSubdomainMatch = false,
                allowBaseDomainMatch = false,
                exactDomainOnly = false,
                disableMatch = true,
            )

            DomainMatchStrategy.STARTS_WITH,
            DomainMatchStrategy.REGEX -> {
                AutofillLogger.w(
                    "AF",
                    "URI strategy $strategy is not natively supported by NG matcher; fallback to BASE_DOMAIN",
                )
                UriStrategyConfig(
                    allowSubdomainMatch = allowSubdomainToggle,
                    allowBaseDomainMatch = true,
                    exactDomainOnly = false,
                )
            }
        }
    }

    private fun applyDefaultSourceFilter(
        entries: List<PasswordEntry>,
        sourceFilter: AutofillPreferences.AutofillDefaultSourceFilter,
        keepassDatabaseId: Long?,
        bitwardenVaultId: Long?,
    ): List<PasswordEntry> {
        return when (sourceFilter) {
            AutofillPreferences.AutofillDefaultSourceFilter.ALL -> entries
            AutofillPreferences.AutofillDefaultSourceFilter.LOCAL -> entries.filter { entry ->
                entry.isLocalOnlyEntry()
            }
            AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS -> entries.filter { entry ->
                entry.keepassDatabaseId != null &&
                    (keepassDatabaseId == null || entry.keepassDatabaseId == keepassDatabaseId)
            }
            AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN -> entries.filter { entry ->
                entry.bitwardenVaultId != null &&
                    (bitwardenVaultId == null || entry.bitwardenVaultId == bitwardenVaultId)
            }
        }
    }

    private fun getInlineRequest(request: FillRequest): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!DeviceUtils.supportsInlineSuggestions()) return null
        return request.inlineSuggestionsRequest
    }

    private fun selectFillableTargets(
        items: List<ParsedItem>,
        manualRequest: Boolean,
    ): List<ParsedItem> {
        if (items.isEmpty()) return emptyList()

        val rawCount = items.size
        val hasPasswordTarget = items.any {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }
        val filtered = items.filter { item ->
            isSupportedFillableHint(item.hint) &&
                AutofillDetectionPolicy.shouldKeepTarget(
                    hint = item.hint,
                    accuracy = item.accuracy,
                    hasPasswordTarget = hasPasswordTarget,
                    manualRequest = manualRequest,
                )
        }
        if (filtered.isEmpty()) return emptyList()

        // Keep targets close to Bitwarden behavior:
        // when a field is already populated, avoid anchoring manual-entry datasets on that
        // non-focused field as it can cause the framework to suppress the suggestion row.
        val hasFocusedTarget = filtered.any { it.isFocused }
        val preferredTargets = filtered.filter { item ->
            val hasValue = !item.value.isNullOrBlank()
            when {
                item.isFocused -> true
                !hasValue -> true
                hasFocusedTarget && isLoginHint(item.hint) -> true
                hasFocusedTarget -> false
                else -> true
            }
        }.ifEmpty { filtered }

        val deduped = linkedMapOf<String, ParsedItem>()
        preferredTargets.sortedWith(
            compareByDescending<ParsedItem> { it.isFocused }
                .thenByDescending { hintPriority(it.hint) }
                .thenByDescending { it.accuracy.score }
                .thenBy { it.traversalIndex },
        ).forEach { item ->
            deduped.putIfAbsent(item.id.toString(), item)
        }

        val droppedByHint = rawCount - filtered.size
        val droppedByValueSuppression = filtered.size - preferredTargets.size
        if (droppedByHint > 0 || droppedByValueSuppression > 0) {
            AutofillLogger.d(
                "AF",
                "Target selection pruned candidates",
                metadata = mapOf(
                    "rawCount" to rawCount,
                    "supportedCount" to filtered.size,
                    "preferredCount" to preferredTargets.size,
                    "finalCount" to deduped.size,
                    "droppedByHint" to droppedByHint,
                    "droppedByValueSuppression" to droppedByValueSuppression,
                    "hasFocusedTarget" to hasFocusedTarget,
                    "hasPasswordTarget" to hasPasswordTarget,
                    "manualRequest" to manualRequest,
                ),
            )
        }

        return deduped.values.toList()
    }

    private fun hintPriority(hint: FieldHint): Int = when (hint) {
        FieldHint.PASSWORD, FieldHint.NEW_PASSWORD -> 3
        FieldHint.USERNAME, FieldHint.EMAIL_ADDRESS, FieldHint.PHONE_NUMBER -> 2
        FieldHint.CREDIT_CARD_NUMBER,
        FieldHint.CREDIT_CARD_EXPIRATION_DATE,
        FieldHint.CREDIT_CARD_EXPIRATION_MONTH,
        FieldHint.CREDIT_CARD_EXPIRATION_YEAR,
        FieldHint.CREDIT_CARD_SECURITY_CODE,
        FieldHint.CREDIT_CARD_HOLDER_NAME,
        FieldHint.IDENTITY_NUMBER,
        -> 2
        FieldHint.PERSON_NAME,
        FieldHint.PERSON_FIRST_NAME,
        FieldHint.PERSON_LAST_NAME,
        FieldHint.POSTAL_ADDRESS,
        FieldHint.POSTAL_CODE,
        FieldHint.ADDRESS_CITY,
        FieldHint.ADDRESS_REGION,
        FieldHint.ADDRESS_COUNTRY,
        FieldHint.COMPANY_NAME,
        -> 1
        else -> 0
    }

    private fun evaluateStructuredConfidence(targets: List<ParsedItem>): StructuredConfidenceDecision {
        if (targets.isEmpty()) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "empty_targets",
                structuredCount = 0,
                bankCardCount = 0,
                documentCount = 0,
                dominantCount = 0,
                keyHintCount = 0,
                confidentCount = 0,
            )
        }
        val structured = targets.filterNot { isLoginHint(it.hint) }
        if (structured.size < 2) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "insufficient_structured_targets",
                structuredCount = structured.size,
                bankCardCount = 0,
                documentCount = 0,
                dominantCount = 0,
                keyHintCount = 0,
                confidentCount = 0,
            )
        }
        val bankCardTargets = structured.filter { isBankCardAutofillHint(it.hint.name) }
        val documentTargets = structured.filter { isDocumentAutofillHint(it.hint.name) }
        val dominantTargets = if (bankCardTargets.size >= documentTargets.size) bankCardTargets else documentTargets
        val secondaryCount = if (bankCardTargets.size >= documentTargets.size) documentTargets.size else bankCardTargets.size

        if (dominantTargets.size < 2) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "insufficient_dominant_category",
                structuredCount = structured.size,
                bankCardCount = bankCardTargets.size,
                documentCount = documentTargets.size,
                dominantCount = dominantTargets.size,
                keyHintCount = 0,
                confidentCount = 0,
            )
        }
        val hasBalancedStructuredCategories = abs(bankCardTargets.size - documentTargets.size) < 1 &&
            secondaryCount > 0
        if (hasBalancedStructuredCategories) {
            val mixedKeyHintCount =
                bankCardTargets.count { isBankCardKeyAutofillHint(it.hint.name) } +
                    documentTargets.count { isDocumentKeyAutofillHint(it.hint.name) }
            val mixedConfidentCount = structured.count {
                it.accuracy.score >= PARSED_ITEM_ACCURACY_THRESHOLD
            }
            val highConfidence = mixedKeyHintCount >= 1 && mixedConfidentCount >= 2
            return StructuredConfidenceDecision(
                highConfidence = highConfidence,
                reason = if (highConfidence) {
                    "mixed_structured_categories"
                } else {
                    "weak_mixed_structured_categories"
                },
                structuredCount = structured.size,
                bankCardCount = bankCardTargets.size,
                documentCount = documentTargets.size,
                dominantCount = dominantTargets.size,
                keyHintCount = mixedKeyHintCount,
                confidentCount = mixedConfidentCount,
            )
        }

        val keyHintCount = dominantTargets.count {
            if (bankCardTargets.size >= documentTargets.size) {
                isBankCardKeyAutofillHint(it.hint.name)
            } else {
                isDocumentKeyAutofillHint(it.hint.name)
            }
        }
        if (keyHintCount < 1) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "missing_key_structured_hint",
                structuredCount = structured.size,
                bankCardCount = bankCardTargets.size,
                documentCount = documentTargets.size,
                dominantCount = dominantTargets.size,
                keyHintCount = keyHintCount,
                confidentCount = 0,
            )
        }

        val confidentCount = dominantTargets.count { it.accuracy.score >= PARSED_ITEM_ACCURACY_THRESHOLD }
        val highConfidence = confidentCount >= 2
        return StructuredConfidenceDecision(
            highConfidence = highConfidence,
            reason = if (highConfidence) "high_confidence" else "insufficient_confident_targets",
            structuredCount = structured.size,
            bankCardCount = bankCardTargets.size,
            documentCount = documentTargets.size,
            dominantCount = dominantTargets.size,
            keyHintCount = keyHintCount,
            confidentCount = confidentCount,
        )
    }

    private fun isSupportedFillableHint(hint: FieldHint): Boolean {
        return isLoginHint(hint) ||
            hint == FieldHint.CREDIT_CARD_NUMBER ||
            hint == FieldHint.CREDIT_CARD_EXPIRATION_DATE ||
            hint == FieldHint.CREDIT_CARD_EXPIRATION_MONTH ||
            hint == FieldHint.CREDIT_CARD_EXPIRATION_YEAR ||
            hint == FieldHint.CREDIT_CARD_SECURITY_CODE ||
            hint == FieldHint.CREDIT_CARD_HOLDER_NAME ||
            hint == FieldHint.POSTAL_ADDRESS ||
            hint == FieldHint.POSTAL_CODE ||
            hint == FieldHint.PERSON_NAME ||
            hint == FieldHint.PERSON_FIRST_NAME ||
            hint == FieldHint.PERSON_LAST_NAME ||
            hint == FieldHint.ADDRESS_CITY ||
            hint == FieldHint.ADDRESS_REGION ||
            hint == FieldHint.ADDRESS_COUNTRY ||
            hint == FieldHint.COMPANY_NAME ||
            hint == FieldHint.IDENTITY_NUMBER
    }

    private fun isLoginHint(hint: FieldHint): Boolean {
        return hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER ||
            hint == FieldHint.PASSWORD ||
            hint == FieldHint.NEW_PASSWORD
    }

    private fun buildFieldSignatureKey(
        packageName: String,
        webDomain: String?,
        credentialTargets: List<ParsedItem>,
    ): String? {
        if (credentialTargets.isEmpty()) return null
        val normalizedPackage = packageName.trim().lowercase()
        if (normalizedPackage.isBlank()) return null
        val normalizedDomain = webDomain?.trim()?.lowercase().orEmpty()
        val targetSummary = credentialTargets
            .sortedWith(compareBy<ParsedItem> { it.traversalIndex }.thenBy { it.hint.name })
            .joinToString(separator = "|") { item ->
                buildString {
                    append(item.hint.name)
                    append('@')
                    append(item.traversalIndex)
                    append('@')
                    append(item.parentWebViewNodeId ?: -1)
                    append('@')
                    append(if (item.isVisible) '1' else '0')
                }
            }
        if (targetSummary.isBlank()) return null
        val rawSignature = buildString {
            append(normalizedPackage)
            append('|')
            append(normalizedDomain)
            append('|')
            append(targetSummary)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawSignature.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        scope.launch {
            try {
                if (!autofillPreferences.isRequestSaveDataEnabled.first()) {
                    callback.onSuccess()
                    return@launch
                }

                val saveIntent = withContext(Dispatchers.Default) { buildSaveIntent(request) }
                if (saveIntent == null) {
                    callback.onSuccess()
                    return@launch
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val code = (System.currentTimeMillis().toInt() and 0x7FFFFFFF)
                    val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val pendingIntent = PendingIntent.getActivity(
                        this@MonicaAutofillServiceNg,
                        code,
                        saveIntent,
                        flags,
                    )
                    callback.onSuccess(pendingIntent.intentSender)
                } else {
                    saveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(saveIntent)
                    callback.onSuccess()
                }
            } catch (e: Exception) {
                AutofillLogger.e("AF", "onSaveRequest failed", e)
                callback.onFailure(e.message ?: strings.get(R.string.legacy_ui_autofill_save_failed))
            }
        }
    }

    private suspend fun buildSaveIntent(request: SaveRequest): Intent? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val parsed = parser.parse(
            structure = structure,
            respectAutofillOff = false,
        )

        val usernameId = parsed.items.firstOrNull {
            it.hint == FieldHint.USERNAME ||
                it.hint == FieldHint.EMAIL_ADDRESS ||
                it.hint == FieldHint.PHONE_NUMBER
        }?.id
        val passwordId = parsed.items.firstOrNull {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }?.id

        val username = usernameId?.let { extractTextFromStructure(structure, it) }.orEmpty()
        val password = passwordId?.let { extractTextFromStructure(structure, it) }.orEmpty()
        if (password.isBlank()) {
            AutofillLogger.i("AF", "Skip save request: no password value")
            return null
        }

        val packageName = resolveEffectivePackageName(
            parsedApplicationId = parsed.applicationId,
            fallbackPackage = structure.activityComponent?.packageName.orEmpty(),
        )
        val website = parsed.webDomain.orEmpty()

        if (isSelfPackage(packageName)) {
            AutofillLogger.i("AF", "Skip save request for Monica itself: $packageName")
            return null
        }

        if (packageName.isNotBlank() && autofillPreferences.isInBlacklist(packageName)) {
            AutofillLogger.i("AF", "Skip save request: package in blacklist ($packageName)")
            return null
        }
        if (autofillPreferences.isSaveBlocked(packageName = packageName, webDomain = website)) {
            AutofillLogger.i("AF", "Skip save request: blocked target (pkg=$packageName, domain=$website)")
            return null
        }

        return Intent(this, AutofillSaveTransparentActivity::class.java).apply {
            putExtra(AutofillSaveTransparentActivity.EXTRA_USERNAME, username)
            putExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD, password)
            putExtra(AutofillSaveTransparentActivity.EXTRA_WEBSITE, website)
            putExtra(AutofillSaveTransparentActivity.EXTRA_PACKAGE_NAME, packageName)
        }
    }

    private fun extractTextFromStructure(
        structure: AssistStructure,
        targetId: AutofillId,
    ): String? {
        for (index in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(index).rootViewNode
            val value = extractTextFromNode(root, targetId)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun extractTextFromNode(
        node: AssistStructure.ViewNode,
        targetId: AutofillId,
    ): String? {
        if (node.autofillId == targetId) {
            return node.autofillValue.safeTextOrNull(
                tag = "AF",
                fieldDescription = node.idEntry ?: node.className ?: "field",
            )
        }
        for (childIndex in 0 until node.childCount) {
            val child = node.getChildAt(childIndex) ?: continue
            if (child.visibility != View.VISIBLE) continue
            val value = extractTextFromNode(child, targetId)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    /**
     * 收集当前聚焦且未被已解析条目覆盖的可编辑字段，依据其 inputType 推断 hint 后
     * 合成 ParsedItem，使响应能锚定到用户实际聚焦的框（如密码框），对齐 Bitwarden 行为。
     */
    private fun buildFocusedSyntheticItems(
        structure: AssistStructure,
        existingItems: List<EnhancedAutofillStructureParserV2.ParsedItem>,
        hasPasswordTarget: Boolean,
        scopeAutofillIds: Set<AutofillId>,
    ): List<EnhancedAutofillStructureParserV2.ParsedItem> {
        val existingIds = existingItems.map { it.id }.toSet()
        val out = mutableListOf<EnhancedAutofillStructureParserV2.ParsedItem>()
        for (index in 0 until structure.windowNodeCount) {
            collectFocusedSyntheticItems(
                node = structure.getWindowNodeAt(index).rootViewNode,
                existingIds = existingIds,
                hasPasswordTarget = hasPasswordTarget,
                scopeAutofillIds = scopeAutofillIds,
                out = out,
            )
        }
        return out
    }

    private fun collectFocusedSyntheticItems(
        node: AssistStructure.ViewNode,
        existingIds: Set<AutofillId>,
        hasPasswordTarget: Boolean,
        scopeAutofillIds: Set<AutofillId>,
        out: MutableList<EnhancedAutofillStructureParserV2.ParsedItem>,
    ) {
        if (node.isFocused &&
            node.autofillId != null &&
            node.autofillId in scopeAutofillIds &&
            !existingIds.contains(node.autofillId)
        ) {
            val inferredHint = inferFocusedFieldHint(node, hasPasswordTarget)
            if (inferredHint != null) {
                out += EnhancedAutofillStructureParserV2.ParsedItem(
                    id = node.autofillId!!,
                    hint = inferredHint,
                    accuracy = EnhancedAutofillStructureParserV2.Accuracy.MEDIUM,
                    isFocused = true,
                    isVisible = node.visibility == View.VISIBLE,
                    traversalIndex = 0,
                )
            }
        }
        for (childIndex in 0 until node.childCount) {
            node.getChildAt(childIndex)?.let {
                collectFocusedSyntheticItems(
                    node = it,
                    existingIds = existingIds,
                    hasPasswordTarget = hasPasswordTarget,
                    scopeAutofillIds = scopeAutofillIds,
                    out = out,
                )
            }
        }
    }

    private fun inferFocusedFieldHint(
        node: AssistStructure.ViewNode,
        hasPasswordTarget: Boolean,
    ): FieldHint? {
        if (!node.isEnabled || node.visibility != View.VISIBLE || parser.isSearchField(node)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            node.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO
        ) return null
        val signals = buildList {
            add(node.hint.orEmpty())
            add(node.idEntry.orEmpty())
            node.autofillHints?.let(::addAll)
            node.htmlInfo?.attributes?.filter {
                it.first in setOf("autocomplete", "name", "id", "placeholder", "aria-label")
            }?.mapTo(this) { it.second.orEmpty() }
        }.joinToString(" ")
        if (AutofillDetectionPolicy.matchesOtpField(signals)) return null
        val inputType = node.inputType
        val classBits = inputType and InputType.TYPE_MASK_CLASS
        if (classBits == InputType.TYPE_CLASS_TEXT) {
            when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                -> return FieldHint.PASSWORD

                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> return FieldHint.EMAIL_ADDRESS

            }
        } else if (classBits == InputType.TYPE_CLASS_PHONE) {
            return FieldHint.PHONE_NUMBER
        } else if (classBits == InputType.TYPE_CLASS_NUMBER) {
            if ((inputType and InputType.TYPE_MASK_VARIATION) ==
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return FieldHint.PASSWORD
            }
        }
        // 非密码/邮箱/电话类的聚焦文本框：仅当屏幕上已存在密码框（登录上下文）时，
        // 才当作账号字段合成，用于补全漏识别的账号框（如影视类 App）。
        // 无密码上下文的普通文本框（搜索框/备注/昵称等）不合成，避免误弹密码建议。
        val editableText = node.autofillType == View.AUTOFILL_TYPE_TEXT &&
            (classBits == InputType.TYPE_CLASS_TEXT || classBits == InputType.TYPE_CLASS_NUMBER)
        return if (hasPasswordTarget && editableText) FieldHint.USERNAME else null
    }

    /**
     * 判断指定 [AutofillId] 是否存在于当前 [AssistStructure]（可见或不可见均可）。
     * 用于密码框跨请求携带时校验缓存 id 是否仍有效，避免注入失效 id。
     */
    private fun structureContainsAutofillId(
        structure: AssistStructure,
        id: AutofillId,
    ): Boolean {
        for (i in 0 until structure.windowNodeCount) {
            if (containsAutofillId(structure.getWindowNodeAt(i).rootViewNode, id)) {
                return true
            }
        }
        return false
    }

    private fun containsAutofillId(
        node: AssistStructure.ViewNode,
        id: AutofillId,
    ): Boolean {
        if (node.autofillId == id) return true
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let {
                if (containsAutofillId(it, id)) return true
            }
        }
        return false
    }

    /**
     * 查找指定 [AutofillId] 在当前 [AssistStructure] 中的可见性；不存在则返回 null。
     */
    private fun findNodeVisibility(
        structure: AssistStructure,
        id: AutofillId,
    ): Boolean? {
        for (i in 0 until structure.windowNodeCount) {
            val visibility = findNodeVisibilityInWindow(
                structure.getWindowNodeAt(i).rootViewNode,
                id,
            )
            if (visibility != null) return visibility
        }
        return null
    }

    private fun findNodeVisibilityInWindow(
        node: AssistStructure.ViewNode,
        id: AutofillId,
    ): Boolean? {
        if (node.autofillId == id) return node.visibility == View.VISIBLE
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let {
                val visibility = findNodeVisibilityInWindow(it, id)
                if (visibility != null) return visibility
            }
        }
        return null
    }

    override fun onConnected() {
        super.onConnected()
        AutofillLogger.i("AF", "Service connected")
    }

    override fun onDisconnected() {
        matcher.clear()
        AutofillSessionGrants.clear()
        passwordMemoryByPackage.clear()
        super.onDisconnected()
        AutofillLogger.i("AF", "Service disconnected")
    }

    private fun resolveEffectivePackageName(
        parsedApplicationId: String?,
        fallbackPackage: String,
    ): String {
        val parsedCandidate = parsedApplicationId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore(':')
        val fallbackCandidate = fallbackPackage
            .trim()
            .substringBefore(':')
        return when {
            !parsedCandidate.isNullOrBlank() && isLikelyAndroidPackageName(parsedCandidate) -> parsedCandidate
            fallbackCandidate.isNotBlank() -> fallbackCandidate
            else -> parsedCandidate.orEmpty()
        }
    }

    private fun isLikelyAndroidPackageName(value: String): Boolean {
        if (value.length !in 3..255) return false
        if (!value.contains('.')) return false
        return PACKAGE_NAME_REGEX.matches(value)
    }

    private fun resolveAppDisplayName(packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isSelfPackage(packageName: String): Boolean {
        return packageName.equals(applicationContext.packageName, ignoreCase = true)
    }
}
