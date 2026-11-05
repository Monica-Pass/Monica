package takagi.ru.monica.autofill_ng

import takagi.ru.monica.security.launchWithSecureStorage

import takagi.ru.monica.autofill_ng.auth.AutofillGrantContext
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.autofill.Dataset
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.ParsedItem
import takagi.ru.monica.autofill_ng.builder.AutofillDatasetBuilder
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.utils.SettingsManager

class AutofillCipherCallbackActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ARGS = "extra_args"
        private const val EXTRA_ARGS_BUNDLE = "extra_args_bundle"
        private const val EXTRA_ARGS_TOKEN = "extra_args_token"
        internal const val EXTRA_DIRECT_DATASET = "monica_direct_dataset"
        private const val TAG = "AutofillCipherCallback"
        private val pendingArgsByToken = ConcurrentHashMap<String, Args>()

        fun getIntent(context: Context, args: Args): Intent {
            val token = UUID.randomUUID().toString()
            pendingArgsByToken[token] = args
            return Intent(context, AutofillCipherCallbackActivity::class.java).apply {
                putExtra(EXTRA_ARGS_TOKEN, token)
                putExtra(
                    EXTRA_ARGS_BUNDLE,
                    Bundle().apply {
                        // Android's system process may eagerly unpack PendingIntent extras.
                        // Only framework types may cross that boundary; a custom Parcelable
                        // here can cause Android 12 to discard ALL extras, including the token.
                        putLong("password_id", args.passwordId)
                        putString("application_id", args.applicationId)
                        putString("web_domain", args.webDomain)
                        putString("interaction_id", args.interactionIdentifier)
                        putStringArrayList("aliases", args.interactionIdentifierAliases)
                        putParcelableArrayList("autofill_ids", args.autofillIds)
                        putStringArrayList("autofill_hints", args.autofillHints)
                        putString("signature", args.fieldSignatureKey)
                        putBoolean("remember", args.rememberLastFilled)
                        putBoolean("authenticate", args.requireAuthentication)
                    }
                )
            }
        }
    }

    @Parcelize
    data class Args(
        val passwordId: Long,
        val applicationId: String? = null,
        val webDomain: String? = null,
        val interactionIdentifier: String? = null,
        val interactionIdentifierAliases: ArrayList<String>? = null,
        val autofillIds: ArrayList<AutofillId>? = null,
        val autofillHints: ArrayList<String>? = null,
        val fieldSignatureKey: String? = null,
        val rememberLastFilled: Boolean = true,
        val requireAuthentication: Boolean = false,
    ) : Parcelable

    private var callbackArgs: Args? = null
    private var callbackArgsToken: String? = null
    private lateinit var securityManager: SecurityManager
    private lateinit var settingsManager: SettingsManager
    private var resultPublished = false

    private fun grantContext() = AutofillGrantContext(
        callbackArgs?.applicationId.orEmpty(), callbackArgs?.webDomain,
        callbackArgs?.interactionIdentifier, callbackArgs?.fieldSignatureKey,
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithSecureStorage { onSecureCreate(savedInstanceState) }
    }

    private fun onSecureCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_transparent)
        callbackArgs = resolveArgsFromIntent(intent)

        val args = callbackArgs
        AutofillLogger.i(
            "CALLBACK",
            "Autofill callback activity started",
            metadata = mapOf(
                "hasArgs" to (args != null),
                "requireAuthentication" to (args?.requireAuthentication ?: false),
                "passwordId" to (args?.passwordId ?: -1L),
            )
        )
        lifecycleScope.launch {
            if (rejectBlockedRequest()) return@launch
            val directDataset = readDirectDataset()
            if (args != null && !args.requireAuthentication && directDataset != null) {
                completeDirectAutofill(args, directDataset)
                return@launch
            }
            securityManager = SecurityManager(applicationContext)
            settingsManager = SettingsManager(applicationContext)
            if (args?.requireAuthentication == true) {
                startAuthentication()
            } else {
                completeCipherAutofill()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Do not apply an in-flight verification to a replacement credential/request.
        cancelAndFinish("request_replaced")
    }

    private fun startAuthentication() {
        lifecycleScope.launch {
            val generation = SecurityManager.dataReadGeneration.value
            val settings = settingsManager.settingsFlow.first()
            if (DeveloperVerificationPolicy.bypassesIdentityVerification(settings)) {
                securityManager.unlockVaultForDeveloperBypass(settings.autoLockMinutes)
                completeCipherAutofill(generation = generation)
                return@launch
            }
            if (settings.autofillKeepUnlocked && AutofillSessionGrants.isGranted(grantContext())) {
                completeCipherAutofill(generation = generation)
                return@launch
            }
            setContent {
                takagi.ru.monica.autofill_ng.auth.AutofillDeviceVerification(
                    onSuccess = { verifiedGeneration ->
                        lifecycleScope.launch { completeCipherAutofill(authenticationVerified = true, generation = verifiedGeneration) }
                    },
                    onCancel = { cancelAndFinish("authentication_cancelled") },
                )
            }
        }
    }

    private suspend fun rejectBlockedRequest(): Boolean {
        val args = callbackArgs ?: return false
        if (!isAutofillRequestBlocked(applicationContext, args.fieldSignatureKey)) return false
        resultPublished = true
        callbackArgsToken?.let { pendingArgsByToken.remove(it) }
        AutofillLogger.i("CALLBACK", "Discard blocked cached autofill dataset")
        finishBlockedAutofillRequest(args.autofillIds.orEmpty())
        return true
    }

    private suspend fun completeCipherAutofill(authenticationVerified: Boolean = false,
        generation: Long = SecurityManager.dataReadGeneration.value) {
        if (resultPublished || rejectBlockedRequest()) return
        if (!securityManager.isDataReadCurrent(generation)) { cancelAndFinish("authentication_revoked"); return }
        val callbackArgs = callbackArgs ?: run {
            cancelAndFinish("missing_args")
            return
        }
        val repository = PasswordRepository(
            PasswordDatabase.getDatabase(applicationContext).passwordEntryDao()
        )
        val passwordEntry = withContext(Dispatchers.IO) {
            repository.getPasswordEntryById(callbackArgs.passwordId)
        } ?: run {
            cancelAndFinish("missing_password_entry")
            return
        }

        if (passwordEntry.isDeleted || passwordEntry.isArchived) {
            cancelAndFinish("credential_no_longer_available")
            return
        }
        if (resultPublished || isFinishing || isDestroyed || rejectBlockedRequest()) return
        val credentials = takagi.ru.monica.security.BoundedSecureDataRead.read(securityManager, generation) {
            val account = AccountFillPolicy.resolveAccountIdentifier(passwordEntry, securityManager)
            ensureActive()
            account to AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = passwordEntry.password,
            logTag = TAG,
        )
        } ?: run { cancelAndFinish("credential_read_unavailable"); return }
        val (accountValue, decryptedPassword) = credentials
        val resolvedTargets = resolveAutofillTargets(callbackArgs)
        if (resolvedTargets.ids.isEmpty()) {
            cancelAndFinish("missing_autofill_ids")
            return
        }
        val filledValues = resolveFilledValues(
            accountValue = accountValue,
            decryptedPassword = decryptedPassword,
            autofillIds = resolvedTargets.ids,
            autofillHints = resolvedTargets.hints,
        )
        if (filledValues.isEmpty()) {
            cancelAndFinish("no_resolved_values")
            return
        }

        val title = passwordEntry.title.ifBlank {
            getString(R.string.autofill_manual_entry_title)
        }
        val subtitle = accountValue.ifBlank {
            callbackArgs.webDomain
                ?: callbackArgs.applicationId
                ?: getString(R.string.app_name)
        }
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordEntry(
            context = this,
            title = title,
            username = subtitle
        )
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        filledValues.forEach { (autofillId, value) ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                value = AutofillValue.forText(value),
                presentation = menuPresentation
            )
        }
        val dataset = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) { null }.build()

        withContext(Dispatchers.IO) {
            if (callbackArgs.rememberLastFilled) {
                rememberLastFilledCredential(
                    passwordId = passwordEntry.id,
                    primaryIdentifier = callbackArgs.interactionIdentifier,
                    aliases = callbackArgs.interactionIdentifierAliases.orEmpty(),
                )
            }
            rememberLearnedFieldSignature(callbackArgs.fieldSignatureKey)
        }
        if (resultPublished || isFinishing || isDestroyed || rejectBlockedRequest()) return

        if (authenticationVerified) {
            val settings = settingsManager.settingsFlow.first()
            if (!securityManager.isDataReadCurrent(generation)) { cancelAndFinish("authentication_revoked"); return }
            AutofillSessionGrants.grant(grantContext(),
                enabled = settings.autofillKeepUnlocked && settings.autofillAuthRequired)
        }

        AutofillLogger.i(
            "CALLBACK",
            "Returning authenticated dataset without picker UI",
            metadata = mapOf(
                "passwordId" to passwordEntry.id,
                "filledCount" to filledValues.size,
                "applicationId" to (callbackArgs.applicationId ?: "none"),
                "webDomain" to (callbackArgs.webDomain ?: "none"),
                "targetSource" to resolvedTargets.source,
                "targetHintPreview" to resolvedTargets.hints
                    .take(12)
                    .mapIndexed { index, hint -> "$index:${hint.trim().uppercase()}" }
                    .joinToString(separator = ","),
            )
        )

        if (!securityManager.isDataReadCurrent(generation) || isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            getSystemService(android.os.PowerManager::class.java)?.isInteractive != true ||
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false) {
            cancelAndFinish("credential_read_revoked")
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
        )
        resultPublished = true
        if (resolvedTargets.hints.any { it.equals(FieldHint.PASSWORD.name, ignoreCase = true) } &&
            resolvedTargets.hints.none { it.equals(FieldHint.OTP_CODE.name, ignoreCase = true) }
        ) AutofillOtpActions.launchAfterFill(applicationContext, passwordEntry.id)
        finishWithoutAnimation()
    }

    private fun readDirectDataset(): Dataset? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DIRECT_DATASET, Dataset::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Dataset>(EXTRA_DIRECT_DATASET)
        }
    }.getOrNull()

    private suspend fun completeDirectAutofill(args: Args, dataset: Dataset) {
        if (resultPublished || rejectBlockedRequest()) return
        // Commit the successful fill before optional database/clipboard/notification work.
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET, true)
            }
        })
        resultPublished = true
        AutofillOtpActions.launchAfterFill(applicationContext, args.passwordId)
        finishWithoutAnimation()
    }

    private fun resolveArgsFromIntent(intent: Intent?): Args? {
        if (intent == null) return null
        callbackArgsToken = intent.getStringExtra(EXTRA_ARGS_TOKEN)
        callbackArgsToken
            ?.let { pendingArgsByToken[it] }
            ?.let { return it }

        intent.getBundleExtra(EXTRA_ARGS_BUNDLE)
            ?.apply { classLoader = Args::class.java.classLoader }
            ?.let { bundle ->
                if (bundle.containsKey("password_id")) {
                    @Suppress("DEPRECATION")
                    return Args(
                        passwordId = bundle.getLong("password_id"),
                        applicationId = bundle.getString("application_id"),
                        webDomain = bundle.getString("web_domain"),
                        interactionIdentifier = bundle.getString("interaction_id"),
                        interactionIdentifierAliases = bundle.getStringArrayList("aliases"),
                        autofillIds = bundle.getParcelableArrayList("autofill_ids"),
                        autofillHints = bundle.getStringArrayList("autofill_hints"),
                        fieldSignatureKey = bundle.getString("signature"),
                        rememberLastFilled = bundle.getBoolean("remember", true),
                        requireAuthentication = bundle.getBoolean("authenticate", false),
                    )
                }
                // Compatibility with callbacks already issued by an older process.
                val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getParcelable(EXTRA_ARGS, Args::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    bundle.getParcelable(EXTRA_ARGS)
                }
                if (args != null) return args
            }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ARGS, Args::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ARGS)
        }
    }

    private data class ResolvedAutofillTargets(
        val ids: List<AutofillId>,
        val hints: List<String>,
        val source: String,
    )

    private fun resolveAutofillTargets(callbackArgs: Args): ResolvedAutofillTargets {
        val assistStructure = getAssistStructureOrNull()
        if (assistStructure != null) {
            val parsedTargets = runCatching {
                val parser = EnhancedAutofillStructureParserV2()
                val parsed = parser.parse(assistStructure, respectAutofillOff = false)
                selectLoginFillableTargets(parsed.items)
            }.getOrDefault(emptyList())

            if (parsedTargets.isNotEmpty()) {
                return ResolvedAutofillTargets(
                    ids = parsedTargets.map { it.id },
                    hints = parsedTargets.map { it.semanticHint ?: it.hint.name },
                    source = "assist_structure",
                )
            }
        }

        return ResolvedAutofillTargets(
            ids = callbackArgs.autofillIds?.distinct().orEmpty(),
            hints = callbackArgs.autofillHints.orEmpty(),
            source = "callback_args",
        )
    }

    private fun getAssistStructureOrNull(): AssistStructure? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
    }

    private fun selectLoginFillableTargets(items: List<ParsedItem>): List<ParsedItem> {
        if (items.isEmpty()) return emptyList()
        val filtered = items.filter { isLoginHint(it.hint) }
        if (filtered.isEmpty()) return emptyList()

        val deduped = linkedMapOf<String, ParsedItem>()
        filtered.sortedWith(
            compareByDescending<ParsedItem> { it.isFocused }
                .thenByDescending { loginHintPriority(it.hint) }
                .thenByDescending { it.accuracy.score }
                .thenBy { it.traversalIndex }
        ).forEach { item ->
            deduped.putIfAbsent(item.id.toString(), item)
        }
        return deduped.values.toList()
    }

    private fun loginHintPriority(hint: FieldHint): Int = when (hint) {
        FieldHint.PASSWORD, FieldHint.NEW_PASSWORD -> 3
        FieldHint.USERNAME, FieldHint.EMAIL_ADDRESS, FieldHint.PHONE_NUMBER -> 2
        else -> 0
    }

    private fun isLoginHint(hint: FieldHint): Boolean {
        return hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER ||
            hint == FieldHint.PASSWORD ||
            hint == FieldHint.NEW_PASSWORD
    }

    private fun resolveFilledValues(
        accountValue: String,
        decryptedPassword: String?,
        autofillIds: List<AutofillId>,
        autofillHints: List<String>,
    ): LinkedHashMap<AutofillId, String> {
        val normalizedHints = autofillHints.map { it.trim().lowercase() }
        val hasPasswordTarget = normalizedHints.any(AutofillPasswordRolePolicy::isStoredPasswordHint)
        if (hasPasswordTarget && decryptedPassword.isNullOrBlank()) {
            Log.w(TAG, "Authentication callback canceled: password decryption unavailable")
            return linkedMapOf()
        }

        val fillEmailWithAccount = AccountFillPolicy.shouldFillEmailWithAccount(applicationContext)
        val hasUsernameHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.USERNAME.name.lowercase() ||
                it.contains("username")
        }
        val hasPhoneHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.PHONE_NUMBER.name.lowercase() ||
                it.contains("phone") ||
                it.contains("mobile") ||
                it.contains("tel")
        }
        val hasEmailHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS.name.lowercase() ||
                it.contains("email")
        }
        val hasAccountHint = hasUsernameHint || hasPhoneHint
        val allowAccountInEmailField =
            fillEmailWithAccount || accountValue.contains("@") || (!hasAccountHint && hasEmailHint)

        val filledValues = linkedMapOf<AutofillId, String>()
        autofillIds.forEachIndexed { index, autofillId ->
            val normalizedHint = autofillHints.getOrNull(index)?.trim()?.lowercase().orEmpty()
            val value = when {
                AutofillPasswordRolePolicy.isNewPasswordHint(normalizedHint) -> null
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name.lowercase() ||
                    normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name.lowercase() ||
                    normalizedHint.contains("password") ||
                    normalizedHint.contains("pass") -> decryptedPassword
                else -> AutofillAccountValues.resolve(normalizedHint, accountValue, allowAccountInEmailField)
            }
            if (!value.isNullOrBlank()) {
                filledValues[autofillId] = value
            }
        }

        if (filledValues.isNotEmpty()) {
            return filledValues
        }

        if (autofillIds.size == 1 && autofillHints.singleOrNull().isNullOrBlank() && accountValue.isNotBlank()) {
            filledValues[autofillIds.first()] = accountValue
        }
        return filledValues
    }

    private suspend fun rememberLastFilledCredential(
        passwordId: Long,
        primaryIdentifier: String?,
        aliases: List<String>,
    ) {
        val normalizedIdentifiers = buildList {
            primaryIdentifier
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            aliases
                .asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }.distinct()
        if (normalizedIdentifiers.isEmpty()) return

        val preferences = AutofillPreferences(applicationContext)
        preferences.completeAutofillInteraction(normalizedIdentifiers.first(), passwordId)
    }

    private suspend fun rememberLearnedFieldSignature(fieldSignatureKey: String?) {
        val signatureKey = fieldSignatureKey?.trim()?.lowercase().orEmpty()
        if (signatureKey.isBlank()) return
        AutofillPreferences(applicationContext).markFieldSignatureLearned(signatureKey)
    }

    private fun cancelAndFinish(reason: String) {
        AutofillLogger.w("CALLBACK", "Cancel autofill callback: $reason")
        resultPublished = true
        setResult(Activity.RESULT_CANCELED)
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        resultPublished = true
        callbackArgsToken?.let { pendingArgsByToken.remove(it) }
        finish()
        overridePendingTransition(0, 0)
    }
}
