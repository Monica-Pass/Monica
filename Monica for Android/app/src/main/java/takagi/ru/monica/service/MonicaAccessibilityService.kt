package takagi.ru.monica.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import takagi.ru.monica.autofill_ng.protection.AutofillProtection
import takagi.ru.monica.autofill_ng.ActiveFillPromptThrottle
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.AutofillDetectionPolicy
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.isLinkedToApp
import takagi.ru.monica.data.linkedAppBindings
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.autofill_ng.ActiveFillNotificationHelper

internal data class TemporaryClipboardSnapshot(
    val text: String?,
    val label: String?,
    val canVerify: Boolean,
)

internal fun shouldRestoreTemporaryClipboard(
    snapshot: TemporaryClipboardSnapshot,
    expectedLabel: String,
    expectedText: String,
): Boolean {
    return !snapshot.canVerify ||
        (snapshot.label == expectedLabel && snapshot.text == expectedText)
}

class MonicaAccessibilityService : AccessibilityService() {
    private var lastPackageName: String = ""
    private var lastUrl: String = ""
    private var lastScanTime = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passwordRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PasswordRepository(PasswordDatabase.getDatabase(applicationContext).passwordEntryDao())
    }
    private val autofillPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AutofillPreferences(applicationContext)
    }
    private val activeFillPromptThrottle = ActiveFillPromptThrottle(ACTIVE_FILL_THROTTLE_MS)
    @Volatile
    private var activeFillNotificationEnabled = false
    private var settingsCollectionJob: Job? = null
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private val temporaryClipboardLock = Any()
    private var temporaryClipboardRestoreRunnable: Runnable? = null
    private var temporaryClipboardGeneration = 0L
    private var temporaryClipboardOriginal: ClipData? = null
    private var temporaryClipboardOriginalWasReadable = false
    private var temporaryClipboardActive = false
    private var temporaryClipboardExpectedText: String? = null

    companion object {
        private const val TAG = "MonicaAccessibility"
        private const val SCAN_THROTTLE_MS = 400L
        private const val MAX_SCAN_DEPTH = 8
        private const val MAX_SCAN_NODES = 300
        private const val MAX_FILL_SCAN_NODES = 400
        private const val SCORE_PASSWORD_SIGNAL = 40
        private const val SCORE_USERNAME_SIGNAL = 24
        private const val SCORE_FOCUSED_BONUS = 12
        private const val SCORE_PASSWORD_FLAG = 90
        private const val SCORE_CONFIRM_PENALTY = 35
        private const val ACTIVE_FILL_THROTTLE_MS = 5000L
        private const val TEMPORARY_CLIPBOARD_LABEL = "Monica autofill"
        private const val TEMPORARY_CLIPBOARD_RESTORE_DELAY_MS = 500L
        private const val WEB_FORM_UPDATE_DELAY_MS = 150L

        @Volatile
        private var activeInstance: MonicaAccessibilityService? = null
        private val mutableConnectionState = MutableStateFlow(false)
        val connectionState = mutableConnectionState.asStateFlow()

        private data class BrowserSpec(
            val packageName: String,
            val urlFieldIds: Set<String>,
        )

        private val browserSpecsByPackage = listOf(
            BrowserSpec(
                packageName = "org.mozilla.fenix",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "org.mozilla.firefox",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "org.mozilla.firefox_beta",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "org.mozilla.fenix.nightly",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "io.github.forkmaintainers.iceraven",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "com.android.chrome",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.chrome.beta",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.chrome.dev",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.chrome.canary",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.google.android.apps.chrome",
                urlFieldIds = setOf("url_bar"),
            ),
        ).associateBy { it.packageName }

        private val trackedEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )

        suspend fun requestCredentialFill(
            targetPackageName: String?,
            username: String,
            password: String,
            preferPasswordField: Boolean,
            beforeWrite: suspend () -> Boolean = { true },
            isAuthorizationCurrent: () -> Boolean = { true },
            expectedWebDomain: String? = null,
        ): Boolean {
            return activeInstance?.fillCredentialsInActiveWindow(
                targetPackageName = targetPackageName,
                username = username,
                password = password,
                preferPasswordField = preferPasswordField,
                beforeWrite = beforeWrite,
                isAuthorizationCurrent = isAuthorizationCurrent,
                expectedWebDomain = expectedWebDomain,
            ) ?: false
        }

        fun getActiveWindowPackageName(): String? {
            return activeInstance?.rootInActiveWindow?.packageName?.toString()
        }

        fun isCredentialFillAvailable(context: Context): Boolean {
            return isServiceEnabled(context) && activeInstance != null
        }

        fun isServiceEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == MonicaAccessibilityService::class.java.name
                }
        }
    }

    private enum class FillFieldType {
        USERNAME,
        PASSWORD,
        EXCLUDED,
        UNKNOWN
    }

    private data class FillCandidate(
        val node: AccessibilityNodeInfo,
        val type: FillFieldType,
        val score: Int,
        val isFocused: Boolean,
        val top: Int,
        val left: Int
    )

    private data class TemporaryClipboardToken(
        val generation: Long,
        val label: String,
        val text: String,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        mutableConnectionState.value = true
        AutofillProtection.restoreIfEnabled(this)
        settingsCollectionJob?.cancel()
        settingsCollectionJob = serviceScope.launch {
            autofillPreferences.isActiveFillNotificationEnabled.collectLatest { enabled ->
                activeFillNotificationEnabled = enabled
                if (enabled) {
                    ActiveFillNotificationHelper.createChannel(this@MonicaAccessibilityService)
                } else {
                    activeFillPromptThrottle.clear()
                    ActiveFillNotificationHelper.dismissNotification(this@MonicaAccessibilityService)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            event ?: return
            if (event.eventType !in trackedEventTypes) return

            val packageName = event.packageName?.toString().orEmpty()
            val browserSpec = browserSpecsByPackage[packageName]
            if (browserSpec != null) {
                val now = System.currentTimeMillis()
                if (packageName == lastPackageName && now - lastScanTime < SCAN_THROTTLE_MS) return
                lastScanTime = now

                val root = rootInActiveWindow ?: event.source ?: return
                val url = findBrowserUrl(root, browserSpec) ?: return

                if (packageName == lastPackageName && url == lastUrl) return

                lastPackageName = packageName
                lastUrl = url
                BrowserAutofillContextStore.update(packageName, url)
                ValidatorContextManager.updateContext(packageName, url)
                Log.d(TAG, "Updated browser context: pkg=$packageName, hasUrl=${url.isNotBlank()}")
                return
            }

            // 阶段1：非浏览器 App 的登录字段聚焦 -> 主动发填充通知
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                maybePromptActiveFill(packageName, event.source)
            }
        }.onFailure { e ->
            Log.w(TAG, "onAccessibilityEvent failed", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
            mutableConnectionState.value = false
        }
        ActiveFillNotificationHelper.dismissNotification(this)
        restoreTemporaryClipboardImmediately()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeInstance === this) {
            activeInstance = null
            mutableConnectionState.value = false
        }
        settingsCollectionJob?.cancel()
        ActiveFillNotificationHelper.dismissNotification(this)
        return super.onUnbind(intent)
    }

    internal suspend fun fillCredentialsInActiveWindow(
        targetPackageName: String?,
        username: String,
        password: String,
        preferPasswordField: Boolean,
        beforeWrite: suspend () -> Boolean = { true },
        isAuthorizationCurrent: () -> Boolean = { true },
        expectedWebDomain: String? = null,
    ): Boolean = runCatching {
        if (!isAuthorizationCurrent()) return@runCatching false
        val root = rootInActiveWindow ?: return@runCatching false
        val activePackageName = root.packageName?.toString().orEmpty()
        val activeWindowId = root.windowId
        if (
            !targetPackageName.isNullOrBlank() &&
            !activePackageName.equals(targetPackageName, ignoreCase = true)
        ) {
            Log.d(TAG, "Skip accessibility fill: active package mismatch ($activePackageName != $targetPackageName)")
            return@runCatching false
        }

        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val candidates = collectFillCandidates(root, focusedNode)
        if (candidates.isEmpty()) {
            Log.d(TAG, "Skip accessibility fill: no editable candidates")
            return@runCatching false
        }

        val focusedCandidate = candidates.firstOrNull { it.isFocused }
        val focusedUnknown = focusedCandidate?.takeIf { it.type == FillFieldType.UNKNOWN }
        val passwordCandidate = selectBestCandidate(
            candidates = candidates,
            preferredType = FillFieldType.PASSWORD,
            excludedNode = null,
            // A missing password field is normal in a username-first flow.
            // Only an explicitly requested password target may use an unknown field.
            fallback = focusedUnknown?.node.takeIf { preferPasswordField },
        )
        val usernameCandidate = selectBestCandidate(
            candidates = candidates,
            preferredType = FillFieldType.USERNAME,
            excludedNode = passwordCandidate?.node,
            fallback = if (!preferPasswordField) {
                focusedUnknown?.node ?: nearestCandidate(
                    candidates.filter { it.type == FillFieldType.UNKNOWN && it.top <= (passwordCandidate?.top ?: Int.MIN_VALUE) },
                    passwordCandidate?.node,
                    passwordCandidate?.node,
                )?.node
            } else {
                nearestCandidate(
                    candidates.filter { it.type == FillFieldType.UNKNOWN && it.top <= (passwordCandidate?.top ?: Int.MIN_VALUE) },
                    passwordCandidate?.node,
                    passwordCandidate?.node,
                )?.node
            }
        )

        var usernameFilled = false
        var passwordFilled = false
        val browserSpec = browserSpecsByPackage[activePackageName]
        val originalUrl = browserSpec?.let { findBrowserUrl(root, it) }
        if (expectedWebDomain != null &&
            takagi.ru.monica.autofill_ng.AutofillHostPolicy.host(originalUrl) != expectedWebDomain) return@runCatching false
        fun targetIsCurrent(): Boolean {
            val current = rootInActiveWindow ?: return false
            return isAuthorizationCurrent() && current.windowId == activeWindowId &&
                current.packageName?.toString() == activePackageName &&
                (browserSpec == null || findBrowserUrl(current, browserSpec) == originalUrl)
        }

        if (username.isNotBlank()) {
            if (!beforeWrite() || !targetIsCurrent()) return@runCatching false
            // Do not retrigger username input handlers during a bounded retry.
            usernameFilled = usernameCandidate?.node?.text?.toString() == username ||
                setNodeText(usernameCandidate?.node, username, ::targetIsCurrent)
        }
        if (password.isNotBlank() && passwordCandidate != null) {
            if (usernameFilled && usernameCandidate != null && isWebInput(usernameCandidate.node)) {
                // Web input handlers can replace the password control. Let the
                // renderer and accessibility tree update, then resolve it again.
                delay(WEB_FORM_UPDATE_DELAY_MS)
            }
            val currentRoot = rootInActiveWindow ?: return@runCatching false
            if (currentRoot.windowId != activeWindowId ||
                currentRoot.packageName?.toString() != activePackageName ||
                (browserSpec != null && findBrowserUrl(currentRoot, browserSpec) != originalUrl)
            ) return@runCatching false
            val currentCandidates = collectFillCandidates(
                currentRoot,
                currentRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT),
            )
            val currentPassword = selectBestCandidate(
                candidates = currentCandidates,
                preferredType = FillFieldType.PASSWORD,
                excludedNode = null,
                fallback = currentCandidates.firstOrNull {
                    preferPasswordField && it.isFocused && it.type == FillFieldType.UNKNOWN
                }?.node,
            ) ?: return@runCatching false
            if (!beforeWrite() || !targetIsCurrent()) return@runCatching false
            passwordFilled = setNodeText(currentPassword.node, password, ::targetIsCurrent)
            if (passwordFilled && isWebInput(currentPassword.node)) {
                // A renderer may acknowledge SET_TEXT before a pending input
                // handler replaces the control. Do not stop the caller's bounded
                // retry while the destination has disappeared or is still empty.
                delay(WEB_FORM_UPDATE_DELAY_MS)
                val verifiedRoot = rootInActiveWindow ?: return@runCatching false
                if (verifiedRoot.windowId != activeWindowId ||
                    verifiedRoot.packageName?.toString() != activePackageName ||
                    (browserSpec != null && findBrowserUrl(verifiedRoot, browserSpec) != originalUrl)
                ) return@runCatching false
                val node = currentPassword.node
                passwordFilled = node.refresh() && node.isVisibleToUser && node.isEnabled &&
                    classifyFillCandidate(node).let { candidate ->
                        candidate.type == FillFieldType.PASSWORD ||
                            (preferPasswordField && candidate.isFocused && candidate.type == FillFieldType.UNKNOWN)
                    } && node.text?.isNotEmpty() != false
                // Null text can be intentionally redacted; masked nonempty values
                // also remain supported. Never log or expose the returned text.
            }
        }

        if (!usernameFilled && !passwordFilled) {
            Log.d(TAG, "Accessibility fill failed: no fields accepted text")
            return@runCatching false
        }

        Log.d(
            TAG,
            "Accessibility fill success: usernameFilled=$usernameFilled, passwordFilled=$passwordFilled, preferPassword=$preferPasswordField"
        )
        // A step can legitimately contain just one of the two fields. If both
        // targets exist, a rejected write still needs the caller's bounded retry.
        (usernameCandidate == null || username.isBlank() || usernameFilled) &&
            (passwordCandidate == null || password.isBlank() || passwordFilled)
    }.onFailure { e ->
        if (e is CancellationException) throw e
        Log.w(TAG, "fillCredentialsInActiveWindow failed", e)
    }.getOrDefault(false)

    private fun isWebInput(node: AccessibilityNodeInfo): Boolean {
        var ancestor: AccessibilityNodeInfo? = node
        repeat(32) {
            val current = ancestor ?: return false
            if (current.className?.toString()?.endsWith("WebView") == true) return true
            ancestor = current.parent
        }
        return false
    }

    private fun maybePromptActiveFill(packageName: String, source: AccessibilityNodeInfo?) {
        if (!activeFillNotificationEnabled) return
        if (packageName.isBlank() || source == null) return
        if (!isLikelyLoginField(source)) return

        val now = System.currentTimeMillis()
        if (!activeFillPromptThrottle.tryAcquire(packageName, now)) return

        serviceScope.launch {
            try {
                val entries = passwordRepository.getAllPasswordEntries().first()
                val match = entries.firstOrNull { entry -> entry.isLinkedToApp(packageName) }
                if (match != null && activeFillNotificationEnabled) {
                    val linkedAppName = match.linkedAppBindings()
                        .firstOrNull { binding ->
                            binding.packageName.equals(packageName, ignoreCase = true)
                        }
                        ?.appName
                        .orEmpty()
                    val shown = ActiveFillNotificationHelper.showActiveFillNotification(
                        this@MonicaAccessibilityService,
                        packageName,
                        linkedAppName.ifBlank { packageName }
                    )
                    Log.d(TAG, "Active fill prompt for pkg=$packageName shown=$shown")
                } else {
                    Log.d(TAG, "No matching credential for pkg=$packageName, skip active fill")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Active fill query failed", e)
            }
        }
    }

    private fun isLikelyLoginField(node: AccessibilityNodeInfo): Boolean {
        val candidate = classifyFillCandidate(node)
        if (candidate.type == FillFieldType.EXCLUDED) return false
        if (candidate.type == FillFieldType.USERNAME || candidate.type == FillFieldType.PASSWORD) return true
        if (node.isPassword) return true
        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (
            (inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ) {
            return true
        }
        if (node.isEditable) {
            val signals = buildList {
                add(node.hintText?.toString().orEmpty())
                add(node.contentDescription?.toString().orEmpty())
                add(node.viewIdResourceName.orEmpty().substringAfterLast('/'))
            }.joinToString(" ").lowercase()
            if (
                signals.contains("password") || signals.contains("passwd") || signals.contains("pwd") ||
                signals.contains("username") || signals.contains("user_name") ||
                signals.contains("email") || signals.contains("login") || signals.contains("account")
            ) {
                return true
            }
        }
        return false
    }

    private fun findBrowserUrl(root: AccessibilityNodeInfo, browserSpec: BrowserSpec): String? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val (node, depth) = queue.removeFirst()
            visited += 1

            runCatching {
                val viewId = node.viewIdResourceName.orEmpty()
                if (browserSpec.urlFieldIds.any { viewId.endsWith(it) }) {
                    extractNodeText(node)?.let { return it }
                }

                if (depth < MAX_SCAN_DEPTH) {
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { child ->
                            queue.add(child to (depth + 1))
                        }
                    }
                }
            }
        }

        return null
    }

    private fun extractNodeText(node: AccessibilityNodeInfo): String? {
        return sequenceOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString(),
        ).mapNotNull { it?.trim() }
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                    candidate.any { it == '.' || it == ':' || it == '/' } &&
                    !candidate.startsWith("Search", ignoreCase = true)
            }
    }

    private fun collectFillCandidates(
        root: AccessibilityNodeInfo,
        focusedNode: AccessibilityNodeInfo?
    ): List<FillCandidate> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = mutableListOf<FillCandidate>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_FILL_SCAN_NODES) {
            val node = queue.removeFirst()
            visited += 1

            runCatching {
                if (node.isVisibleToUser && node.isEnabled && supportsSetText(node)) {
                    candidates += classifyFillCandidate(node)
                }

                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(queue::addLast)
                }
            }
        }

        if (
            focusedNode != null &&
            focusedNode.isVisibleToUser && focusedNode.isEnabled &&
            runCatching { supportsSetText(focusedNode) }.getOrDefault(false) &&
            candidates.none { it.node == focusedNode }
        ) {
            runCatching { candidates += classifyFillCandidate(focusedNode) }
        }

        return runCatching {
            candidates.distinctBy { candidate ->
                val bounds = Rect().also(candidate.node::getBoundsInScreen)
                listOf(
                    candidate.node.viewIdResourceName.orEmpty(),
                    bounds.left.toString(),
                    bounds.top.toString(),
                    bounds.right.toString(),
                    bounds.bottom.toString()
                ).joinToString("|")
            }
        }.getOrDefault(candidates)
    }

    private fun supportsSetText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        return runCatching {
            node.actionList.orEmpty().any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }.getOrDefault(false)
    }

    private fun classifyFillCandidate(node: AccessibilityNodeInfo): FillCandidate {
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        val signalParts = buildList {
            add(node.viewIdResourceName.orEmpty().substringAfterLast('/'))
            add(node.hintText?.toString().orEmpty())
            add(node.contentDescription?.toString().orEmpty())
            // Current input is user data, not a semantic hint. Some WebViews
            // expose the placeholder as text but mark it with isShowingHintText.
            if (node.isShowingHintText) add(node.text?.toString().orEmpty())
            runCatching { node.labeledBy?.text?.toString() }.getOrNull()?.let(::add)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(node.tooltipText?.toString().orEmpty())
            }
        }
        val signals = signalParts.joinToString(" ").lowercase()

        // This path fills existing credentials only. Share the system parser's
        // new/confirmation semantics, including camelCase IDs, before scoring
        // masked controls or falling back to an unknown focused field.
        if (signalParts.any(takagi.ru.monica.autofill_ng.AutofillFormPurposePolicy::isNewPasswordLabel) ||
            AutofillDetectionPolicy.matchesOtpField(signals) ||
            AutofillDetectionPolicy.matchesSearchField(signals) ||
            signals.contains("url_bar") || signals.contains("location_bar") ||
            signals.contains("address_bar")
        ) {
            return FillCandidate(node, FillFieldType.EXCLUDED, 0, node.isFocused, bounds.top, bounds.left)
        }

        var passwordScore = 0
        var usernameScore = 0

        if (node.isPassword) {
            passwordScore += SCORE_PASSWORD_FLAG
        }
        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (
            (inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ) {
            passwordScore += SCORE_PASSWORD_FLAG
        }
        if (
            inputClass == InputType.TYPE_CLASS_TEXT &&
            (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )
        ) {
            usernameScore += SCORE_USERNAME_SIGNAL
        }

        if (AutofillDetectionPolicy.matchesPasswordField(signals)) {
            passwordScore += SCORE_PASSWORD_SIGNAL
        }
        if (Regex("(?:^|[^a-z])pin(?:$|[^a-z])").containsMatchIn(signals)) {
            passwordScore += SCORE_PASSWORD_SIGNAL / 2
        }
        if (
            signals.contains("confirm") ||
            signals.contains("re-enter") ||
            signals.contains("repeat") || signals.contains("确认") || signals.contains("確認") ||
            signals.contains("再次")
        ) {
            passwordScore -= SCORE_CONFIRM_PENALTY
        }

        if (signals.contains("username") || signals.contains("user_name")) {
            usernameScore += SCORE_USERNAME_SIGNAL + 12
        }
        if (
            AutofillDetectionPolicy.matchesExplicitAccountField(signals)
        ) {
            usernameScore += SCORE_USERNAME_SIGNAL
        }
        if (AutofillDetectionPolicy.matchesPhoneFieldName(signals) || inputClass == InputType.TYPE_CLASS_PHONE) {
            usernameScore += SCORE_USERNAME_SIGNAL / 2
        }

        if (node.isFocused) {
            passwordScore += SCORE_FOCUSED_BONUS
            usernameScore += SCORE_FOCUSED_BONUS
        }

        val type = when {
            passwordScore > usernameScore && passwordScore > 0 -> FillFieldType.PASSWORD
            usernameScore > passwordScore && usernameScore > 0 -> FillFieldType.USERNAME
            else -> FillFieldType.UNKNOWN
        }
        val resolvedScore = maxOf(passwordScore, usernameScore)

        return FillCandidate(
            node = node,
            type = type,
            score = resolvedScore,
            isFocused = node.isFocused,
            top = bounds.top,
            left = bounds.left
        )
    }

    private fun selectBestCandidate(
        candidates: List<FillCandidate>,
        preferredType: FillFieldType,
        excludedNode: AccessibilityNodeInfo?,
        fallback: AccessibilityNodeInfo?,
    ): FillCandidate? {
        val exactMatch = candidates
            .asSequence()
            .filter { candidate -> candidate.type == preferredType }
            .filterNot { candidate -> excludedNode != null && candidate.node == excludedNode }
            .sortedWith(
                compareByDescending<FillCandidate> { it.score }
                    .thenByDescending { it.isFocused }
                    .thenBy { it.top }
                    .thenBy { it.left }
            )
            .firstOrNull()
        if (exactMatch != null) return exactMatch

        val fallbackNode = fallback
            ?.takeIf { node -> excludedNode == null || node != excludedNode }
            ?.let { node -> candidates.firstOrNull { it.node == node && it.type == FillFieldType.UNKNOWN } }
        return fallbackNode
    }

    private fun nearestCandidate(
        candidates: List<FillCandidate>,
        anchorNode: AccessibilityNodeInfo?,
        excludedNode: AccessibilityNodeInfo?
    ): FillCandidate? {
        val anchor = anchorNode ?: return null
        val anchorRect = Rect().also(anchor::getBoundsInScreen)
        val anchorCenterX = anchorRect.centerX()
        val anchorCenterY = anchorRect.centerY()
        return candidates
            .asSequence()
            .filterNot { candidate -> candidate.node == anchor }
            .filterNot { candidate -> excludedNode != null && candidate.node == excludedNode }
            .sortedBy { candidate ->
                val dx = candidate.left - anchorCenterX
                val dy = candidate.top - anchorCenterY
                dx * dx + dy * dy
            }
            .firstOrNull()
    }

    private fun setNodeText(node: AccessibilityNodeInfo?, text: String,
        isAuthorizationCurrent: () -> Boolean = { true }): Boolean {
        if (node == null || text.isBlank() || !isAuthorizationCurrent()) return false

        // Address the field itself before relying on the shared clipboard and
        // input focus. WebView may acknowledge PASTE before focus/clipboard work
        // reaches its renderer, so consecutive pastes can land in the same field.
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (runCatching {
                isAuthorizationCurrent() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        ) return true

        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // A consumed focus action is not proof that the destination changed.
        // Let the caller retry after the page settles instead of pasting elsewhere.
        if (!node.refresh() || !node.isFocused || !node.isVisibleToUser || !node.isEnabled) return false

        val existingText = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        val canPasteWithoutAppending = if (existingText.isEmpty()) {
            true
        } else {
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingText.length)
            }
            runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            }.getOrDefault(false)
        }

        if (canPasteWithoutAppending && isAuthorizationCurrent()) {
            val temporaryClipboard = setTemporaryClipboard(text)
            if (temporaryClipboard != null) {
                val pasted = runCatching {
                    isAuthorizationCurrent() && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }.getOrDefault(false)
                scheduleTemporaryClipboardRestore(temporaryClipboard)
                if (pasted) return true
            }
        }

        return false
    }

    private fun setTemporaryClipboard(text: String): TemporaryClipboardToken? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null

        synchronized(temporaryClipboardLock) {
            val startedSession = !temporaryClipboardActive
            if (startedSession) {
                val originalResult = runCatching { clipboard.primaryClip }
                temporaryClipboardOriginal = originalResult.getOrNull()
                temporaryClipboardOriginalWasReadable = originalResult.isSuccess
                temporaryClipboardActive = true
            }

            val generation = temporaryClipboardGeneration + 1L
            val temporaryClip = ClipData.newPlainText(TEMPORARY_CLIPBOARD_LABEL, text).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
            }
            val written = runCatching { clipboard.setPrimaryClip(temporaryClip) }.isSuccess
            if (!written) {
                if (startedSession) {
                    resetTemporaryClipboardSessionLocked()
                }
                return null
            }

            temporaryClipboardGeneration = generation
            temporaryClipboardExpectedText = text
            return TemporaryClipboardToken(
                generation = generation,
                label = TEMPORARY_CLIPBOARD_LABEL,
                text = text,
            )
        }
    }

    private fun scheduleTemporaryClipboardRestore(token: TemporaryClipboardToken) {
        val restoreRunnable = Runnable { restoreTemporaryClipboardIfOwned(token) }
        synchronized(temporaryClipboardLock) {
            if (!temporaryClipboardActive || token.generation != temporaryClipboardGeneration) return
            temporaryClipboardRestoreRunnable?.let(clipboardHandler::removeCallbacks)
            temporaryClipboardRestoreRunnable = restoreRunnable
            clipboardHandler.postDelayed(
                restoreRunnable,
                TEMPORARY_CLIPBOARD_RESTORE_DELAY_MS,
            )
        }
    }

    private fun restoreTemporaryClipboardImmediately() {
        val token = synchronized(temporaryClipboardLock) {
            if (!temporaryClipboardActive) return
            TemporaryClipboardToken(
                generation = temporaryClipboardGeneration,
                label = TEMPORARY_CLIPBOARD_LABEL,
                text = temporaryClipboardExpectedText.orEmpty(),
            )
        }
        restoreTemporaryClipboardIfOwned(token)
    }

    private fun restoreTemporaryClipboardIfOwned(token: TemporaryClipboardToken) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        synchronized(temporaryClipboardLock) {
            if (!temporaryClipboardActive || token.generation != temporaryClipboardGeneration) return

            val snapshot = if (clipboard == null) {
                TemporaryClipboardSnapshot(text = null, label = null, canVerify = false)
            } else {
                runCatching {
                    val currentClip = clipboard.primaryClip
                    TemporaryClipboardSnapshot(
                        text = currentClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(this)
                            ?.toString(),
                        label = currentClip?.description?.label?.toString(),
                        canVerify = true,
                    )
                }.getOrElse {
                    TemporaryClipboardSnapshot(text = null, label = null, canVerify = false)
                }
            }

            if (
                clipboard != null &&
                shouldRestoreTemporaryClipboard(snapshot, token.label, token.text)
            ) {
                val restored = if (
                    temporaryClipboardOriginalWasReadable &&
                    temporaryClipboardOriginal != null
                ) {
                    runCatching {
                        clipboard.setPrimaryClip(requireNotNull(temporaryClipboardOriginal))
                    }.isSuccess
                } else {
                    false
                }
                if (!restored) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                }
            }

            resetTemporaryClipboardSessionLocked()
        }
    }

    private fun resetTemporaryClipboardSessionLocked() {
        temporaryClipboardRestoreRunnable?.let(clipboardHandler::removeCallbacks)
        temporaryClipboardRestoreRunnable = null
        temporaryClipboardOriginal = null
        temporaryClipboardOriginalWasReadable = false
        temporaryClipboardActive = false
        temporaryClipboardExpectedText = null
    }
}
