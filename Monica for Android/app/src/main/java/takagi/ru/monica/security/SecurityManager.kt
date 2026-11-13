package takagi.ru.monica.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import takagi.ru.monica.data.PredefinedSecurityQuestions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SecurityQuestionsBackupSnapshot(
    val question1Id: Int,
    val question1Text: String?,
    val answer1Hash: String,
    val question2Id: Int,
    val question2Text: String?,
    val answer2Hash: String
)

/**
 * Security manager for encryption and master password handling
 */
class SecurityManager private constructor(
    private val context: Context,
    private val secureStore: SecurePreferencesStore.Opened,
) {
    constructor(context: Context) : this(context, PreparedSecureStore.open(context))

    internal fun acceptPreparedStore() = PreparedSecureStore.accept(context, secureStore)

    internal fun isPreparedStoreCurrent(): Boolean = runCatching {
        PreparedSecureStore.unwrap(sharedPreferences)
        true
    }.getOrDefault(false)

    enum class VaultAccessState {
        ACCESSIBLE,
        REQUIRES_PASSWORD_REENTRY,
        LOCKED
    }

    private enum class WrapperMode {
        AUTH,
        COMPAT
    }

    private data class WrappedMdkBlob(
        val mode: WrapperMode,
        val payload: String
    )

    private val logTag = "SecurityManager"

    @Volatile
    private var mdkAuthUnavailableUntilMillis: Long = 0L
    @Volatile
    private var hasLoggedMdkAuthExpiredWarning = false
    @Volatile
    private var hasLoggedMdkFallbackEncryption = false
    @Volatile
    private var mdkWrapperUnavailable = false

    private val mdkAuthCooldownMillis = 30_000L

    init {
        SecurityDiagLogger.initialize(context.applicationContext)
    }

    private fun logRoutineDebug(message: String) {
        SecurityDiagLogger.append("D/$logTag $message")
    }
    
    private val masterKey = secureStore.masterKey

    // Secure Data Key Alias and Prefix
    private val KEY_ALIAS_DATA = "monica_data_key_v2"
    private val KEY_ALIAS_DATA_COMPAT = "monica_data_key_v2_compat"
    private val DATA_PREFIX_V2 = "V2|"
    private val DATA_PREFIX_COMPAT = "C2|"
    private val WRAPPER_PREFIX_AUTH = "AU|"
    private val WRAPPER_PREFIX_COMPAT = "CP|"
    
    private val sharedPreferences = secureStore.preferences
    private val localRecovery = LocalVaultRecovery(context)
    private val recoveryLimiter = RecoveryAttemptLimiter(context)
    private val recoveryAuthorization = RecoveryAuthorization(android.os.SystemClock::elapsedRealtime, System::currentTimeMillis)

    private fun recoveryBinding(): Map<String, Any?> {
        check(isPreparedStoreCurrent()) { "Recovery store was retired" }
        val values = sharedPreferences.all
        val keys = LocalPasswordCredentials.keys + listOf(MDK_READY_KEY, MDK_KEYSTORE_BLOB_KEY,
            LocalVaultRecovery.GENERATION, SECURITY_QUESTION_1_ID_KEY, SECURITY_QUESTION_1_ANSWER_KEY,
            SECURITY_QUESTION_1_TEXT_KEY, SECURITY_QUESTION_2_ID_KEY, SECURITY_QUESTION_2_ANSWER_KEY,
            SECURITY_QUESTION_2_TEXT_KEY)
        return keys.associateWith { values[it] } + ("store" to context.applicationInfo.dataDir)
    }

    internal fun recoveryAttemptStatus() = recoveryLimiter.status()
    internal fun cancelRecovery() = recoveryAuthorization.cancel()
    internal fun cancelRecovery(challenge: RecoveryChallenge) = recoveryAuthorization.cancel(challenge)
    fun cancelRecovery(permit: RecoveryPermit) = recoveryAuthorization.cancel(permit)
    fun isRecoveryPermitCurrent(permit: RecoveryPermit?): Boolean = runCatching {
        recoveryAuthorization.isCurrent(permit, recoveryBinding(), runtimeKeyGeneration.get(), passwordCredentialEpoch.get())
    }.getOrDefault(false)

    /** Answers create only a pending challenge. The platform credential callback issues the permit. */
    internal fun beginRecoveryWithAnswers(answer1: String, answer2: String, ensureActive: () -> Unit): RecoveryChallenge? {
        recoveryAuthorization.cancel()
        val generation = runtimeKeyGeneration.get()
        val epoch = passwordCredentialEpoch.get()
        return try {
            ensureActive()
            localRecovery.validate(sharedPreferences)
            check(isMasterPasswordSet())
            val binding = recoveryBinding()
            if (!recoveryLimiter.reserve()) return null
            val first = binding[SECURITY_QUESTION_1_ANSWER_KEY] as? String ?: return null
            val second = binding[SECURITY_QUESTION_2_ANSWER_KEY] as? String ?: return null
            val valid1 = SecurityAnswerHash.verify(answer1, first)
            val valid2 = SecurityAnswerHash.verify(answer2, second)
            if (!valid1 || !valid2) return null
            val updates = buildMap {
                if (SecurityAnswerHash.isLegacy(first)) put(SECURITY_QUESTION_1_ANSWER_KEY, SecurityAnswerHash.create(answer1))
                if (SecurityAnswerHash.isLegacy(second)) put(SECURITY_QUESTION_2_ANSWER_KEY, SecurityAnswerHash.create(answer2))
            }
            ensureActive()
            synchronized(runtimeKeyLock) {
                check(runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == epoch && recoveryBinding() == binding)
                recoveryAuthorization.begin(binding, generation, epoch, updates)
            }
        } catch (_: Exception) { null }
    }

    internal fun completeRecoveryDeviceCredential(challenge: RecoveryChallenge): RecoveryPermit? = runCatching {
        synchronized(runtimeKeyLock) {
            recoveryAuthorization.confirm(challenge, recoveryBinding(), runtimeKeyGeneration.get(), passwordCredentialEpoch.get())
        }
    }.getOrNull()

    fun recoverMasterPassword(
        password: String,
        permit: RecoveryPermit?,
        attempt: LocalPasswordChangeAttempt = LocalPasswordChangeAttempt(),
        ensureActive: () -> Unit = {},
    ): Boolean {
        var mdk: ByteArray? = null
        return try {
            require(MasterPasswordPolicy.meetsMinLength(password))
            attempt.ensureCurrent(); ensureActive()
            val proof = requireNotNull(permit)
            val binding = recoveryBinding()
            val updates = requireNotNull(recoveryAuthorization.updates(proof, binding, attempt.generation, attempt.credentialEpoch))
            localRecovery.validate(sharedPreferences)
            val original = sharedPreferences.all
            PasswordKdf.fromValues(original)
            // COMPAT may supply the original MDK only AFTER explicit recovery authorization.
            check(original[MDK_READY_KEY] == true && original[MDK_PASSWORD_BLOB_KEY] != null)
            mdk = readKeystoreWrappedMdk(original[MDK_KEYSTORE_BLOB_KEY] as? String ?: return false)
            check(mdk.size == 32)
            commitLocalPassword(password, mdk, original, attempt, ensureActive, updates) {
                check(recoveryAuthorization.consume(proof, recoveryBinding(), attempt.generation, attempt.credentialEpoch)) {
                    "Recovery authorization was revoked"
                }
            }
            runCatching { recoveryLimiter.clearAfterAuthenticatedRecovery() }
            true
        } catch (_: Exception) {
            false
        } finally {
            permit?.let(recoveryAuthorization::cancel)
            mdk?.fill(0)
        }
    }
    
    companion object {
        internal fun openFresh(context: Context) = SecurityManager(context, PreparedSecureStore.fresh(context))
        internal fun fromPrepared(context: Context, store: SecurePreferencesStore.Opened): SecurityManager {
            PreparedSecureStore.unwrap(store.preferences)
            return SecurityManager(context, store)
        }
        private const val MASTER_PASSWORD_HASH_KEY = "master_password_hash"
        private const val MASTER_PASSWORD_SALT_KEY = "master_password_salt"
        private const val BIOMETRIC_ENABLED_KEY = "biometric_enabled"
        private const val AUTO_LOCK_TIMEOUT_KEY = "auto_lock_timeout"
        private const val SECURITY_QUESTION_1_ID_KEY = "security_question_1_id"
        private const val SECURITY_QUESTION_1_ANSWER_KEY = "security_question_1_answer"
        private const val SECURITY_QUESTION_1_TEXT_KEY = "security_question_1_text"
        private const val SECURITY_QUESTION_2_ID_KEY = "security_question_2_id"
        private const val SECURITY_QUESTION_2_ANSWER_KEY = "security_question_2_answer"
        private const val SECURITY_QUESTION_2_TEXT_KEY = "security_question_2_text"
        private val SECURITY_ANSWER_HASH_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        private const val MDK_PASSWORD_BLOB_KEY = "mdk_password_blob"
        private const val MDK_PASSWORD_SALT_KEY = "mdk_password_salt"
        private const val MDK_KEYSTORE_BLOB_KEY = "mdk_keystore_blob"
        private const val MDK_READY_KEY = "mdk_ready"
        
        // V2 Bitwarden 凭据存储键
        private const val BITWARDEN_ACCESS_TOKEN_KEY = "bitwarden_access_token"
        private const val BITWARDEN_REFRESH_TOKEN_KEY = "bitwarden_refresh_token"
        private const val BITWARDEN_TOKEN_EXPIRY_KEY = "bitwarden_token_expiry"
        private const val BITWARDEN_USER_EMAIL_KEY = "bitwarden_user_email"
        private const val BITWARDEN_USER_ID_KEY = "bitwarden_user_id"
        private const val BITWARDEN_MASTER_KEY_HASH_KEY = "bitwarden_master_key_hash"
        private const val BITWARDEN_SYMMETRIC_KEY_KEY = "bitwarden_symmetric_key"
        private const val BITWARDEN_PRIVATE_KEY_KEY = "bitwarden_private_key"
        private const val BITWARDEN_SERVER_URL_KEY = "bitwarden_server_url"
        private const val BITWARDEN_CONNECTED_KEY = "bitwarden_connected"

        @Volatile
        private var processCachedMdk: ByteArray? = null
        private val runtimeKeyLock = Any()
        private val runtimeKeyGeneration = java.util.concurrent.atomic.AtomicLong()
        private val passwordCredentialEpoch = java.util.concurrent.atomic.AtomicLong()
        private val dataReadGenerationState = kotlinx.coroutines.flow.MutableStateFlow(0L)
        internal val dataReadGeneration: kotlinx.coroutines.flow.StateFlow<Long> = dataReadGenerationState
        @Volatile
        private var cachedCompatDataKey: SecretKey? = null

        private val compatDataKeyLock = Any()

        fun hasRuntimeUnlockCache(): Boolean = processCachedMdk?.isNotEmpty() == true

        fun clearRuntimeUnlockCache() {
            synchronized(runtimeKeyLock) {
                val generation = runtimeKeyGeneration.incrementAndGet()
                processCachedMdk = null
                cachedCompatDataKey = null
                dataReadGenerationState.value = generation
            }
            takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants.clear()
            takagi.ru.monica.repository.Mdbx2NativeReadSessions.clear()
        }
    }
    
    /** One password submission; cancelled work may finish IO but cannot unlock the process. */
    class PasswordVerificationAttempt internal constructor() {
        internal val generation = runtimeKeyGeneration.get()
        @Volatile internal var credentialEpoch = passwordCredentialEpoch.get()
            private set
        private var active = true
        private var candidate: ByteArray? = null
        private var manager: SecurityManager? = null
        private var credentials: Map<String, Any?>? = null

        internal fun isCurrent(): Boolean = synchronized(runtimeKeyLock) {
            active && runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == credentialEpoch
        }

        internal fun advanceCredentialEpoch(next: Long) {
            // Called only by this verified attempt, inside the final credential commit gate.
            credentialEpoch = next
        }

        internal fun stage(owner: SecurityManager, mdk: ByteArray, verifiedCredentials: Map<String, *>) {
            check(owner.credentialsMatch(verifiedCredentials)) { "Password credentials changed" }
            check(owner.isDataReadCurrent(generation)) { "Password verification was revoked" }
            synchronized(runtimeKeyLock) {
                check(active && runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == credentialEpoch) { "Password verification was revoked" }
                candidate?.fill(0)
                candidate = mdk.copyOf()
                manager = owner
                credentials = LocalPasswordCredentials.snapshot(verifiedCredentials)
            }
        }

        internal fun commit(): Boolean {
            val owner = synchronized(runtimeKeyLock) { manager } ?: return false
            if (!owner.isDataReadCurrent(generation) || credentials?.let(owner::credentialsMatch) != true) {
                cancel(); return false
            }
            return synchronized(runtimeKeyLock) {
                if (!active || runtimeKeyGeneration.get() != generation || passwordCredentialEpoch.get() != credentialEpoch || candidate == null) {
                    cancel()
                    return@synchronized false
                }
                processCachedMdk = candidate
                candidate = null
                manager = null
                credentials = null
                active = false
                true
            }
        }

        internal fun cancel() = synchronized(runtimeKeyLock) {
            active = false
            candidate?.fill(0)
            candidate = null
            manager = null
            credentials = null
        }
    }

    /** A single settings submission. Cancellation wins until the credential commit starts. */
    class LocalPasswordChangeAttempt {
        internal val generation = runtimeKeyGeneration.get()
        private val state = java.util.concurrent.atomic.AtomicInteger(0)
        internal var credentialEpoch = passwordCredentialEpoch.get()
            private set
        fun cancel() { state.compareAndSet(0, 2) }
        internal fun ensureCurrent() {
            check(state.get() == 0 && runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == credentialEpoch) { "Password change was revoked" }
        }
        internal fun beginCommit(ensureActive: () -> Unit) {
            ensureActive()
            synchronized(runtimeKeyLock) {
                check(runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == credentialEpoch && state.compareAndSet(0, 1)) {
                    "Password change was revoked"
                }
                credentialEpoch = passwordCredentialEpoch.incrementAndGet()
            }
        }
    }

    /** New standalone verifiers use the default profile; stored credentials always use their metadata. */
    fun hashMasterPassword(password: String, salt: ByteArray? = null): Pair<String, ByteArray> {
        val actualSalt = salt ?: generateRandom(16)
        val hash = PasswordKdf.derive(password, actualSalt, PasswordKdf.PBKDF2_600K)
        return try { PasswordKdf.hex(hash) to actualSalt } finally { hash.fill(0) }
    }

    private fun credentialsMatch(expected: Map<String, *>): Boolean = runCatching {
        LocalPasswordCredentials.matches(sharedPreferences.all, expected)
    }.getOrDefault(false)

    fun verifyMasterPassword(
        inputPassword: String,
        attempt: PasswordVerificationAttempt? = null,
        ensureActive: () -> Unit = {},
    ): Boolean {
        val generation = attempt?.generation ?: runtimeKeyGeneration.get()
        val credentialEpoch = attempt?.credentialEpoch ?: passwordCredentialEpoch.get()
        return try {
            ensureActive()
            if (attempt?.isCurrent() == false) return false
            localRecovery.validate(sharedPreferences)
            val original = sharedPreferences.all
            if (!LocalPasswordCredentials.verify(inputPassword, original)) return false
            ensureMdkInitializedWithPassword(inputPassword, original, generation, credentialEpoch, attempt, ensureActive)
            true
        } catch (_: Exception) {
            android.util.Log.w(logTag, "Password authentication unavailable or rejected")
            false
        }
    }

    fun unlockVaultWithPassword(
        inputPassword: String,
        attempt: PasswordVerificationAttempt? = null,
        ensureActive: () -> Unit = {},
    ): Boolean {
        if (attempt?.isCurrent() == false) return false
        if (!isMasterPasswordSet()) return true
        return verifyMasterPassword(inputPassword, attempt, ensureActive)
    }

    fun getLocalPasswordAlgorithm(): LocalPasswordAlgorithm = PasswordKdf.fromValues(sharedPreferences.all).algorithm

    private fun changeVerifiedPassword(
        currentPassword: String,
        newPassword: String,
        attempt: LocalPasswordChangeAttempt,
        ensureActive: () -> Unit,
    ): Boolean {
        var mdk: ByteArray? = null
        return try {
            attempt.ensureCurrent(); ensureActive()
            localRecovery.validate(sharedPreferences)
            val original = sharedPreferences.all
            if (!LocalPasswordCredentials.verify(currentPassword, original)) return false
            // Even a warm runtime cache cannot conceal damage to the password wrapper.
            mdk = readPasswordAuthenticatedMdk(currentPassword, original)
            commitLocalPassword(newPassword, mdk, original, attempt, ensureActive)
            true
        } catch (_: Exception) {
            android.util.Log.w(logTag, "Local password protection update was not confirmed")
            false
        } finally { mdk?.fill(0) }
    }

    fun unlockVaultWithBiometric(): Boolean {
        if (!isMasterPasswordSet()) {
            return true
        }

        return try {
            // A previous failed keystore read can set a short cooldown. Once the
            // user has just passed biometric auth, retry immediately instead of
            // reporting that the vault key is still unavailable.
            mdkAuthUnavailableUntilMillis = 0L
            ensureMdkKeystoreWrapper()
            val mdk = getMdkForCrypto()
            mdk != null && mdk.isNotEmpty()
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "Vault biometric unlock failed: ${e.message}")
            false
        }
    }

    fun isVaultRuntimeUnlocked(): Boolean {
        if (runCatching { PreparedSecureStore.unwrap(sharedPreferences) }.isFailure) {
            clearRuntimeUnlockCache()
            return false
        }
        return hasRuntimeUnlockCache()
    }

    /**
     * Main app authentication success hook.
     * Writes the shared app session and clears temporary keystore cooldown flags.
     */
    fun markVaultAuthenticated() {
        SessionManager.markUnlocked()
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
    }

    /**
     * Secondary entry-point authentication success hook.
     * Grants Autofill and IME their own unlock window without unlocking the main app.
     */
    fun markSecondaryVaultAuthenticated(autoLockMinutes: Int) {
        SecondarySessionManager.updateAutoLockTimeout(autoLockMinutes)
        SecondarySessionManager.markUnlocked()
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
    }

    /**
     * Makes the vault key available to secondary entry points without an identity prompt.
     * This is intentionally limited to the explicit developer bypass setting.
     */
    fun unlockVaultForDeveloperBypass(autoLockMinutes: Int): Boolean {
        if (!isMasterPasswordSet()) return true
        val mdk = runCatching {
            mdkAuthUnavailableUntilMillis = 0L
            getMdkForCrypto()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        val accessible = mdk != null
        if (accessible) {
            val currentWrapper = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null)
            if (currentWrapper?.startsWith(WRAPPER_PREFIX_COMPAT) != true) {
                persistCompatKeystoreWrappedMdk(mdk!!)
            }
            markVaultAuthenticated()
            markSecondaryVaultAuthenticated(autoLockMinutes)
        }
        return accessible
    }

    /**
     * Enables a device-local, non-authenticated wrapper for the MDK. Disabling removes that
     * wrapper; the next normal password unlock recreates the authenticated wrapper.
     */
    fun configureDeveloperVerificationBypass(enabled: Boolean): Boolean {
        if (!isMasterPasswordSet()) return true
        if (!enabled) {
            val storedWrapper = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null)
            if (storedWrapper?.startsWith(WRAPPER_PREFIX_COMPAT) == true) {
                sharedPreferences.edit().remove(MDK_KEYSTORE_BLOB_KEY).apply()
            }
            return true
        }

        val mdk = runCatching { getMdkForCrypto() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: processCachedMdk?.takeIf { it.isNotEmpty() }
            ?: return false
        return persistCompatKeystoreWrappedMdk(mdk)
    }

    fun forceVaultReauthentication(reason: String) {
        android.util.Log.w(logTag, "forceVaultReauthentication: $reason")
        SecurityDiagLogger.append("W/$logTag forceVaultReauthentication: $reason")
        SessionManager.markLocked()
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
    }

    fun shouldForceVaultReauthenticationAfterDecryptFailure(error: Throwable?): Boolean {
        if (error == null || !isMasterPasswordSet()) return false
        if (error is android.security.keystore.KeyPermanentlyInvalidatedException) return true
        if (error is UserNotAuthenticatedException) return true
        if (error.message == "MDK not available") return true
        return !isVaultRuntimeUnlocked() && requiresPasswordReentryForWrapperRebuild()
    }

    fun handleVaultDecryptFailure(error: Throwable?): Boolean {
        if (!shouldForceVaultReauthenticationAfterDecryptFailure(error)) {
            return false
        }
        forceVaultReauthentication(
            "decrypt failure requires reauth: ${error?.javaClass?.simpleName ?: "unknown"}"
        )
        return true
    }

    fun canRestoreMainAppSession(context: Context, autoLockMinutes: Int): Boolean {
        if (!isMasterPasswordSet()) {
            android.util.Log.d(logTag, "canRestoreMainAppSession: no master password set -> accessible")
            SecurityDiagLogger.append("D/$logTag canRestoreMainAppSession: no master password set -> accessible")
            return true
        }

        if (!hasActiveSharedSession(context, autoLockMinutes)) {
            logRoutineDebug("canRestoreMainAppSession: session inactive -> locked")
            return false
        }

        if (isVaultRuntimeUnlocked()) {
            logRoutineDebug("canRestoreMainAppSession: runtime MDK cache present")
            return true
        }

        if (isMdkReadable()) {
            android.util.Log.d(logTag, "canRestoreMainAppSession: MDK readable with active session")
            SecurityDiagLogger.append("D/$logTag canRestoreMainAppSession: MDK readable with active session")
            return true
        }

        mdkAuthUnavailableUntilMillis = 0L
        val retriedAccessible = isMdkReadable()
        if (retriedAccessible) {
            android.util.Log.d(logTag, "canRestoreMainAppSession: MDK readable on retry with active session")
            SecurityDiagLogger.append("D/$logTag canRestoreMainAppSession: MDK readable on retry with active session")
            return true
        }

        android.util.Log.w(logTag, "canRestoreMainAppSession: active session but MDK unavailable")
        SecurityDiagLogger.append("W/$logTag canRestoreMainAppSession: active session but MDK unavailable")
        return false
    }

    fun canAccessVaultNowStrict(context: Context, autoLockMinutes: Int): Boolean {
        return canAccessVaultNow(context, autoLockMinutes, allowSessionOnlyFallback = false)
    }

    /**
     * Vault accessibility check for secondary secure entry points.
     * Requires an active access window from either the main app session or the
     * isolated secondary session, plus usable runtime key material.
     */
    fun canAccessVaultNow(
        context: Context,
        autoLockMinutes: Int,
        allowSessionOnlyFallback: Boolean = true
    ): Boolean {
        if (!isMasterPasswordSet()) {
            android.util.Log.d(logTag, "canAccessVaultNow: no master password set -> accessible")
            SecurityDiagLogger.append("D/$logTag canAccessVaultNow: no master password set -> accessible")
            return true
        }
        val secondarySessionActive = hasActiveSecondarySession(context, autoLockMinutes)
        val sharedSessionActive = if (secondarySessionActive) {
            false
        } else {
            hasActiveSharedSession(context, autoLockMinutes)
        }
        if (!sharedSessionActive && !secondarySessionActive) {
            logRoutineDebug("canAccessVaultNow: session inactive -> locked")
            return false
        }

        if (isVaultRuntimeUnlocked()) {
            logRoutineDebug("canAccessVaultNow: runtime MDK cache present -> accessible")
            return true
        }

        if (isMdkReadable()) {
            android.util.Log.d(logTag, "canAccessVaultNow: MDK readable on first attempt")
            SecurityDiagLogger.append("D/$logTag canAccessVaultNow: MDK readable on first attempt")
            return true
        }

        // Session is still valid but MDK read previously failed (often due a stale auth cooldown).
        // Force one immediate retry before reporting locked state.
        mdkAuthUnavailableUntilMillis = 0L
        val retriedAccessible = isMdkReadable()

        if (retriedAccessible) {
            android.util.Log.d(logTag, "canAccessVaultNow: MDK readable on retry")
            SecurityDiagLogger.append("D/$logTag canAccessVaultNow: MDK readable on retry")
            return true
        }

        val hasKeystoreWrapper = sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)
        val authCooldownActive = System.currentTimeMillis() < mdkAuthUnavailableUntilMillis

        android.util.Log.w(
            logTag,
            "canAccessVaultNow: locked after retry; wrapperPresent=$hasKeystoreWrapper, sharedSessionActive=$sharedSessionActive, secondarySessionActive=$secondarySessionActive, allowSessionOnlyFallback=$allowSessionOnlyFallback, authCooldownActive=$authCooldownActive"
        )
        SecurityDiagLogger.append(
            "W/$logTag canAccessVaultNow: locked after retry wrapperPresent=$hasKeystoreWrapper sharedSessionActive=$sharedSessionActive secondarySessionActive=$secondarySessionActive allowSessionOnlyFallback=$allowSessionOnlyFallback authCooldownActive=$authCooldownActive"
        )

        return false
    }

    /**
     * Checks whether usable vault key material is immediately available after an
     * explicit authentication step, without consulting any app session window.
     */
    fun canAccessVaultMaterialNow(): Boolean {
        if (!isMasterPasswordSet()) {
            return true
        }
        if (isVaultRuntimeUnlocked()) {
            return true
        }
        if (isMdkReadable()) {
            return true
        }
        mdkAuthUnavailableUntilMillis = 0L
        return isMdkReadable()
    }

    fun getVaultAccessState(context: Context, autoLockMinutes: Int): VaultAccessState {
        if (canAccessVaultNowStrict(context, autoLockMinutes)) {
            return VaultAccessState.ACCESSIBLE
        }
        return if (requiresPasswordReentryForWrapperRebuild()) {
            VaultAccessState.REQUIRES_PASSWORD_REENTRY
        } else {
            VaultAccessState.LOCKED
        }
    }

    fun requiresPasswordReentryForWrapperRebuild(): Boolean {
        if (!isMasterPasswordSet()) return false
        if (isVaultRuntimeUnlocked()) return false
        val ready = sharedPreferences.getBoolean(MDK_READY_KEY, false)
        if (!ready) return false
        val hasPasswordBlob = sharedPreferences.contains(MDK_PASSWORD_BLOB_KEY)
        if (!hasPasswordBlob) return false
        val hasKeystoreBlob = sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)
        val keystoreAliasMissing = hasKeystoreBlob && !hasRequiredAliasForStoredWrapper()
        val requires = mdkWrapperUnavailable || !hasKeystoreBlob || keystoreAliasMissing
        if (requires) {
            android.util.Log.w(
                logTag,
                "requiresPasswordReentryForWrapperRebuild=true: ready=$ready, hasPasswordBlob=$hasPasswordBlob, hasKeystoreBlob=$hasKeystoreBlob, keystoreAliasMissing=$keystoreAliasMissing"
            )
            SecurityDiagLogger.append(
                "W/$logTag requiresPasswordReentryForWrapperRebuild=true ready=$ready hasPasswordBlob=$hasPasswordBlob hasKeystoreBlob=$hasKeystoreBlob keystoreAliasMissing=$keystoreAliasMissing"
            )
        }
        return requires
    }

    fun rebuildKeystoreWrapperFromRuntimeCacheIfNeeded(): Boolean {
        if (sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) {
            android.util.Log.d(logTag, "rebuildKeystoreWrapperFromRuntimeCacheIfNeeded: wrapper already exists")
            SecurityDiagLogger.append("D/$logTag rebuildWrapper: wrapper already exists")
            return true
        }
        val mdk = processCachedMdk
        if (mdk == null || mdk.isEmpty()) {
            android.util.Log.w(logTag, "rebuildKeystoreWrapperFromRuntimeCacheIfNeeded: runtime MDK cache missing")
            SecurityDiagLogger.append("W/$logTag rebuildWrapper: runtime MDK cache missing")
            return false
        }
        val persisted = persistKeystoreWrappedMdk(mdk)
        android.util.Log.d(logTag, "rebuildKeystoreWrapperFromRuntimeCacheIfNeeded: persisted=$persisted")
        SecurityDiagLogger.append("D/$logTag rebuildWrapper: persisted=$persisted")
        return persisted
    }

    private fun hasActiveSharedSession(context: Context, autoLockMinutes: Int): Boolean {
        SessionManager.updateAutoLockTimeout(autoLockMinutes)
        return SessionManager.canSkipVerification(context)
    }

    private fun hasActiveSecondarySession(context: Context, autoLockMinutes: Int): Boolean {
        SecondarySessionManager.updateAutoLockTimeout(autoLockMinutes)
        return SecondarySessionManager.canSkipVerification(context)
    }
    
    /**
     * Set the master password
     */
    fun setMasterPassword(
        password: String,
        attempt: LocalPasswordChangeAttempt = LocalPasswordChangeAttempt(),
        ensureActive: () -> Unit = {},
    ): Boolean {
        var mdk: ByteArray? = null
        return try {
            attempt.ensureCurrent(); ensureActive()
            check(!isMasterPasswordSet()) { "Master password already set" }
            localRecovery.validate(sharedPreferences)
            val original = sharedPreferences.all
            // Initial enrollment alone. Cached keys and wrappers cannot authorize replacement.
            check(!isMasterPasswordSet()) { "Master password already set" }
            require(MasterPasswordPolicy.meetsMinLength(password))
            PasswordKdf.fromValues(original)
            mdk = getOrCreateMdkBytes()
            commitLocalPassword(password, mdk, original, attempt, ensureActive)
            true
        } catch (_: Exception) {
            android.util.Log.w(logTag, "Master password update could not be committed")
            clearRuntimeUnlockCache()
            SessionManager.markLocked()
            false
        } finally { mdk?.fill(0) }
    }

    // Unknown storage is treated as password-protected. Never expose first-time setup or
    // a no-password bypass when a retired/damaged store cannot be read. Authentication
    // and credential writes independently validate the store and still fail closed.
    fun isMasterPasswordSet(): Boolean = runCatching {
        sharedPreferences.contains(MASTER_PASSWORD_HASH_KEY) ||
            sharedPreferences.contains(MASTER_PASSWORD_SALT_KEY) ||
            sharedPreferences.contains(MDK_PASSWORD_BLOB_KEY) ||
            sharedPreferences.contains(MDK_PASSWORD_SALT_KEY) ||
            sharedPreferences.contains(PasswordKdf.METADATA_KEY) ||
            sharedPreferences.getBoolean(MDK_READY_KEY, false)
    }.getOrDefault(true)

    fun resetMasterPassword(
        currentPassword: String,
        newPassword: String,
        attempt: LocalPasswordChangeAttempt = LocalPasswordChangeAttempt(),
        ensureActive: () -> Unit = {},
    ): Boolean = changeVerifiedPassword(currentPassword, newPassword, attempt, ensureActive)

    /**
     * Clear all security data (for complete reset scenarios)
     */
    fun clearSecurityData() {
        sharedPreferences.edit()
            .remove(MASTER_PASSWORD_HASH_KEY)
            .remove(MASTER_PASSWORD_SALT_KEY)
            .remove(PasswordKdf.METADATA_KEY)
            .remove(BIOMETRIC_ENABLED_KEY)
            .remove(AUTO_LOCK_TIMEOUT_KEY)
            .remove(SECURITY_QUESTION_1_ID_KEY)
            .remove(SECURITY_QUESTION_1_ANSWER_KEY)
            .remove(SECURITY_QUESTION_2_ID_KEY)
            .remove(SECURITY_QUESTION_2_ANSWER_KEY)
            .remove(MDK_PASSWORD_BLOB_KEY)
            .remove(MDK_PASSWORD_SALT_KEY)
            .remove(MDK_KEYSTORE_BLOB_KEY)
            .remove(MDK_READY_KEY)
            // V2 Bitwarden 凭据
            .remove(BITWARDEN_ACCESS_TOKEN_KEY)
            .remove(BITWARDEN_REFRESH_TOKEN_KEY)
            .remove(BITWARDEN_TOKEN_EXPIRY_KEY)
            .remove(BITWARDEN_USER_EMAIL_KEY)
            .remove(BITWARDEN_USER_ID_KEY)
            .remove(BITWARDEN_MASTER_KEY_HASH_KEY)
            .remove(BITWARDEN_SYMMETRIC_KEY_KEY)
            .remove(BITWARDEN_PRIVATE_KEY_KEY)
            .remove(BITWARDEN_SERVER_URL_KEY)
            .remove(BITWARDEN_CONNECTED_KEY)
            .apply()
        clearRuntimeUnlockCache()
    }
    
    /**
     * Biometric settings
     */
    fun setBiometricEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(BIOMETRIC_ENABLED_KEY, enabled).apply()
    }
    
    fun isBiometricEnabled(): Boolean {
        return sharedPreferences.getBoolean(BIOMETRIC_ENABLED_KEY, false)
    }
    
    /**
     * Auto-lock timeout settings (in minutes)
     */
    fun setAutoLockTimeout(minutes: Int) {
        sharedPreferences.edit().putInt(AUTO_LOCK_TIMEOUT_KEY, minutes).apply()
    }
    
    fun getAutoLockTimeout(): Int {
        return sharedPreferences.getInt(AUTO_LOCK_TIMEOUT_KEY, 5) // Default 5 minutes
    }
    
    /**
     * Security Questions Management
     */
    fun setSecurityQuestions(
        question1Id: Int,
        answer1: String,
        question2Id: Int,
        answer2: String,
        question1Text: String? = null,
        question2Text: String? = null,
        ensureActive: () -> Unit = {},
    ) {
        ensureActive()
        check(SessionManager.isUnlocked.value && hasRuntimeUnlockCache()) { "Security question setup requires authentication" }
        require(isValidSecurityQuestionId(question1Id) && isValidSecurityQuestionId(question2Id))
        val generation = runtimeKeyGeneration.get()
        val original = recoveryBinding()
        val hashedAnswer1 = SecurityAnswerHash.create(answer1)
        val hashedAnswer2 = SecurityAnswerHash.create(answer2)
        val updates = mapOf(
            SECURITY_QUESTION_1_ID_KEY to question1Id,
            SECURITY_QUESTION_1_ANSWER_KEY to hashedAnswer1,
            SECURITY_QUESTION_1_TEXT_KEY to question1Text.takeIf { PredefinedSecurityQuestions.isCustomQuestion(question1Id) },
            SECURITY_QUESTION_2_ID_KEY to question2Id,
            SECURITY_QUESTION_2_ANSWER_KEY to hashedAnswer2,
            SECURITY_QUESTION_2_TEXT_KEY to question2Text.takeIf { PredefinedSecurityQuestions.isCustomQuestion(question2Id) },
        )
        ensureActive()
        check(localRecovery.commitAuthenticatedSettings(sharedPreferences, updates) {
            synchronized(runtimeKeyLock) {
                ensureActive()
                check(runtimeKeyGeneration.get() == generation && SessionManager.isUnlocked.value && recoveryBinding() == original)
                cancelRecovery()
            }
        }) { "Security questions could not be saved" }
    }
    
    fun areSecurityQuestionsSet(): Boolean {
        return sharedPreferences.contains(SECURITY_QUESTION_1_ID_KEY) &&
                sharedPreferences.contains(SECURITY_QUESTION_2_ID_KEY)
    }
    
    fun getSecurityQuestion1Id(): Int {
        return sharedPreferences.getInt(SECURITY_QUESTION_1_ID_KEY, -1)
    }
    
    fun getSecurityQuestion2Id(): Int {
        return sharedPreferences.getInt(SECURITY_QUESTION_2_ID_KEY, -1)
    }

    fun getSecurityQuestion1Text(isZh: Boolean = false): String? {
        return resolveSecurityQuestionText(
            id = getSecurityQuestion1Id(),
            customText = sharedPreferences.getString(SECURITY_QUESTION_1_TEXT_KEY, null),
            isZh = isZh
        )
    }

    fun getSecurityQuestion2Text(isZh: Boolean = false): String? {
        return resolveSecurityQuestionText(
            id = getSecurityQuestion2Id(),
            customText = sharedPreferences.getString(SECURITY_QUESTION_2_TEXT_KEY, null),
            isZh = isZh
        )
    }
    
    fun verifySecurityAnswers(answer1: String, answer2: String): Boolean {
        if (!recoveryLimiter.reserve()) return false
        return runCatching {
            val first = sharedPreferences.getString(SECURITY_QUESTION_1_ANSWER_KEY, null) ?: return false
            val second = sharedPreferences.getString(SECURITY_QUESTION_2_ANSWER_KEY, null) ?: return false
            val valid1 = SecurityAnswerHash.verify(answer1, first)
            val valid2 = SecurityAnswerHash.verify(answer2, second)
            valid1 && valid2
        }.getOrDefault(false)
    }

    fun exportSecurityQuestionsForBackup(): SecurityQuestionsBackupSnapshot? {
        if (!areSecurityQuestionsSet()) return null
        val answer1Hash = sharedPreferences.getString(SECURITY_QUESTION_1_ANSWER_KEY, null)
            ?.takeIf(::isValidSecurityAnswerHash)
            ?: return null
        val answer2Hash = sharedPreferences.getString(SECURITY_QUESTION_2_ANSWER_KEY, null)
            ?.takeIf(::isValidSecurityAnswerHash)
            ?: return null
        val question1Id = getSecurityQuestion1Id()
        val question2Id = getSecurityQuestion2Id()
        if (!isValidSecurityQuestionId(question1Id) || !isValidSecurityQuestionId(question2Id)) {
            return null
        }
        return SecurityQuestionsBackupSnapshot(
            question1Id = question1Id,
            question1Text = sharedPreferences.getString(SECURITY_QUESTION_1_TEXT_KEY, null),
            answer1Hash = answer1Hash,
            question2Id = question2Id,
            question2Text = sharedPreferences.getString(SECURITY_QUESTION_2_TEXT_KEY, null),
            answer2Hash = answer2Hash
        )
    }

    fun restoreSecurityQuestionsFromBackup(snapshot: SecurityQuestionsBackupSnapshot): Boolean {
        val normalized = snapshot.copy(
            question1Text = snapshot.question1Text?.trim()?.takeIf { it.isNotEmpty() },
            answer1Hash = SecurityAnswerHash.canonical(snapshot.answer1Hash),
            question2Text = snapshot.question2Text?.trim()?.takeIf { it.isNotEmpty() },
            answer2Hash = SecurityAnswerHash.canonical(snapshot.answer2Hash)
        )
        if (
            !isValidSecurityQuestionId(normalized.question1Id) ||
            !isValidSecurityQuestionId(normalized.question2Id) ||
            !isValidSecurityAnswerHash(normalized.answer1Hash) ||
            !isValidSecurityAnswerHash(normalized.answer2Hash)
        ) {
            return false
        }
        if (
            PredefinedSecurityQuestions.isCustomQuestion(normalized.question1Id) &&
            normalized.question1Text == null
        ) {
            return false
        }
        if (
            PredefinedSecurityQuestions.isCustomQuestion(normalized.question2Id) &&
            normalized.question2Text == null
        ) {
            return false
        }
        cancelRecovery()
        sharedPreferences.edit()
            .putInt(SECURITY_QUESTION_1_ID_KEY, normalized.question1Id)
            .putString(SECURITY_QUESTION_1_ANSWER_KEY, normalized.answer1Hash)
            .putString(
                SECURITY_QUESTION_1_TEXT_KEY,
                normalized.question1Text.takeIf {
                    PredefinedSecurityQuestions.isCustomQuestion(normalized.question1Id)
                }
            )
            .putInt(SECURITY_QUESTION_2_ID_KEY, normalized.question2Id)
            .putString(SECURITY_QUESTION_2_ANSWER_KEY, normalized.answer2Hash)
            .putString(
                SECURITY_QUESTION_2_TEXT_KEY,
                normalized.question2Text.takeIf {
                    PredefinedSecurityQuestions.isCustomQuestion(normalized.question2Id)
                }
            )
            .apply()
        return true
    }

    private fun isValidSecurityQuestionId(questionId: Int): Boolean {
        return PredefinedSecurityQuestions.getQuestionById(questionId) != null
    }

    private fun isValidSecurityAnswerHash(value: String): Boolean = SecurityAnswerHash.isValid(value)

    fun clearSecurityQuestions() {
        cancelRecovery()
        sharedPreferences.edit()
            .remove(SECURITY_QUESTION_1_ID_KEY)
            .remove(SECURITY_QUESTION_1_ANSWER_KEY)
            .remove(SECURITY_QUESTION_1_TEXT_KEY)
            .remove(SECURITY_QUESTION_2_ID_KEY)
            .remove(SECURITY_QUESTION_2_ANSWER_KEY)
            .remove(SECURITY_QUESTION_2_TEXT_KEY)
            .apply()
    }

    private fun resolveSecurityQuestionText(
        id: Int,
        customText: String?,
        isZh: Boolean
    ): String? {
        return if (PredefinedSecurityQuestions.isCustomQuestion(id)) {
            customText
        } else if (PredefinedSecurityQuestions.getQuestionById(id) == null) {
            null
        } else {
            context.getString(PredefinedSecurityQuestions.textResIdFor(id))
        }
    }
    
    /**
     * Get or create a secure key from Android KeyStore
     * This key requires user authentication (biometric) to be used.
     */
    private fun getOrGenerateSecureKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        
        if (keyStore.containsAlias(KEY_ALIAS_DATA)) {
            val entry = keyStore.getEntry(KEY_ALIAS_DATA, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }
        
        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS_DATA,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationValidityDurationSeconds(300) // Allow use for 5 minutes after authentication
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(true) // Key becomes permanently invalid if new biometric is enrolled
        }
        
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun getOrGenerateCompatSecureKey(): SecretKey {
        cachedCompatDataKey?.let { return it }

        return synchronized(compatDataKeyLock) {
            cachedCompatDataKey?.let { return@synchronized it }

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS_DATA_COMPAT)) {
                val entry = keyStore.getEntry(KEY_ALIAS_DATA_COMPAT, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return@synchronized entry.secretKey.also { cachedCompatDataKey = it }
                }
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS_DATA_COMPAT,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey().also { cachedCompatDataKey = it }
        }
    }

    /** Decrypting existing data is never permission to generate a replacement key. */
    private fun getExistingDataKey(compat: Boolean): SecretKey {
        fun read(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw UnrecoverableKeyException("Existing data key unavailable")
        }
        if (!compat) return read(KEY_ALIAS_DATA)
        val generation = runtimeKeyGeneration.get()
        cachedCompatDataKey?.let { return it }
        return synchronized(compatDataKeyLock) {
            cachedCompatDataKey ?: read(KEY_ALIAS_DATA_COMPAT).also { key ->
                synchronized(runtimeKeyLock) {
                    check(runtimeKeyGeneration.get() == generation) { "Vault locked during key read" }
                    cachedCompatDataKey = key
                }
            }
        }
    }

    private fun hasSecureKeyAlias(alias: String = KEY_ALIAS_DATA): Boolean {
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.containsAlias(alias)
        }.getOrDefault(false)
    }

    private fun parseWrappedMdkBlob(rawBlob: String): WrappedMdkBlob {
        return when {
            rawBlob.startsWith(WRAPPER_PREFIX_COMPAT) -> WrappedMdkBlob(
                mode = WrapperMode.COMPAT,
                payload = rawBlob.removePrefix(WRAPPER_PREFIX_COMPAT)
            )
            rawBlob.startsWith(WRAPPER_PREFIX_AUTH) -> WrappedMdkBlob(
                mode = WrapperMode.AUTH,
                payload = rawBlob.removePrefix(WRAPPER_PREFIX_AUTH)
            )
            else -> WrappedMdkBlob(
                mode = WrapperMode.AUTH,
                payload = rawBlob
            )
        }
    }

    private fun hasRequiredAliasForStoredWrapper(): Boolean {
        val rawBlob = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null) ?: return true
        val wrapper = parseWrappedMdkBlob(rawBlob)
        return when (wrapper.mode) {
            WrapperMode.AUTH -> hasSecureKeyAlias(KEY_ALIAS_DATA)
            WrapperMode.COMPAT -> hasSecureKeyAlias(KEY_ALIAS_DATA_COMPAT)
        }
    }

    private fun isMdkReadable(): Boolean {
        return try {
            val mdk = getMdkForCrypto()
            mdk != null && mdk.isNotEmpty()
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun persistKeystoreWrappedMdk(mdk: ByteArray): Boolean {
        if (mdk.isEmpty()) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: skip empty MDK")
            return false
        }
        return try {
            val ksKey = getExistingDataKey(compat = false)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, ksKey)
            val iv = cipher.iv
            val enc = cipher.doFinal(mdk)
            val combined = iv + enc
            val blob = WRAPPER_PREFIX_AUTH + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
            sharedPreferences.edit()
                .putString(MDK_KEYSTORE_BLOB_KEY, blob)
                .apply()
            android.util.Log.d(logTag, "persistKeystoreWrappedMdk: success")
            SecurityDiagLogger.append("D/$logTag persistKeystoreWrappedMdk: success")
            true
        } catch (e: UserNotAuthenticatedException) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: user not authenticated")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk: user not authenticated")
            persistCompatKeystoreWrappedMdk(mdk)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: invalidated key and wrapper retained")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk: secure key invalidated")
            false
        } catch (e: UnrecoverableKeyException) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: unreadable key and wrapper retained")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk: secure key unrecoverable")
            false
        } catch (e: Exception) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk failed: ${e.javaClass.simpleName}")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun persistCompatKeystoreWrappedMdk(mdk: ByteArray, allowInitialKeyCreation: Boolean = false): Boolean {
        return try {
            val compatKey = if (allowInitialKeyCreation) getOrGenerateCompatSecureKey()
                else getExistingDataKey(compat = true)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, compatKey)
            val iv = cipher.iv
            val enc = cipher.doFinal(mdk)
            val combined = iv + enc
            val blob = WRAPPER_PREFIX_COMPAT + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
            sharedPreferences.edit()
                .putString(MDK_KEYSTORE_BLOB_KEY, blob)
                .apply()
            android.util.Log.w(logTag, "persistCompatKeystoreWrappedMdk: saved compatibility wrapper")
            SecurityDiagLogger.append("W/$logTag persistCompatKeystoreWrappedMdk: saved compatibility wrapper")
            true
        } catch (e: Exception) {
            android.util.Log.w(logTag, "persistCompatKeystoreWrappedMdk failed: ${e.javaClass.simpleName}")
            SecurityDiagLogger.append("W/$logTag persistCompatKeystoreWrappedMdk failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun generateRandom(bytes: Int): ByteArray {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b
    }

    private fun aesGcmEncrypt(key: SecretKeySpec, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(data)
        val combined = iv + enc
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    private fun aesGcmDecrypt(key: SecretKeySpec, combinedBase64: String): ByteArray {
        val combined = android.util.Base64.decode(combinedBase64, android.util.Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val enc = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(enc)
    }

    private fun readPasswordAuthenticatedMdk(password: String, original: Map<String, *>): ByteArray {
        val hasBlob = original.containsKey(MDK_PASSWORD_BLOB_KEY)
        val hasSalt = original.containsKey(MDK_PASSWORD_SALT_KEY)
        check(hasBlob == hasSalt) { "Incomplete password key material" }
        if (hasBlob) return LocalPasswordCredentials.unwrap(password, original)
        // A genuinely pre-MDK installation may still use the original device-encrypted fields.
        check(original[MDK_READY_KEY] != true && PasswordKdf.fromValues(original) == PasswordKdf.LEGACY) {
            "Existing vault key unavailable"
        }
        return getOrCreateMdkBytes().also { check(it.size == 32) }
    }

    private fun credentialUpdates(password: String, mdk: ByteArray): Map<String, Any> =
        LocalPasswordCredentials.create(password, mdk, PasswordKdf.PBKDF2_600K).toMutableMap().apply {
            put(MDK_READY_KEY, true)
            if (!sharedPreferences.contains("legacy_master_key_description_v1")) {
                put("legacy_master_key_description_v1", masterKey.toString())
            }
        }

    private fun commitLocalPassword(
        password: String,
        mdk: ByteArray,
        original: Map<String, *>,
        attempt: LocalPasswordChangeAttempt,
        ensureActive: () -> Unit,
        extraUpdates: Map<String, String> = emptyMap(),
        authorizeCommit: () -> Unit = {},
    ) {
        attempt.ensureCurrent(); ensureActive()
        check(mdk.size == 32)
        val values = credentialUpdates(password, mdk) + extraUpdates
        attempt.ensureCurrent(); ensureActive()
        localRecovery.enroll(sharedPreferences, password, values, expectedCredentials = original,
            beforeCommit = {
                synchronized(runtimeKeyLock) {
                    attempt.ensureCurrent(); ensureActive()
                    authorizeCommit()
                    attempt.beginCommit(ensureActive)
                }
            })
        val committed = original + values
        // The credential transaction may finish after cancellation; it must stay coherent,
        // but it is never permission to publish a runtime key into a subsequently locked session.
        ensureActive()
        publishPasswordMdk(mdk, committed, attempt.generation, attempt.credentialEpoch, null)
        if (!sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) {
            persistCompatKeystoreWrappedMdk(mdk, allowInitialKeyCreation = original[MASTER_PASSWORD_HASH_KEY] == null && original[MDK_READY_KEY] != true)
        }
    }

    private fun ensureMdkInitializedWithPassword(
        password: String,
        original: Map<String, *>,
        generation: Long,
        originalEpoch: Long,
        attempt: PasswordVerificationAttempt?,
        ensureActive: () -> Unit,
    ) {
        var epoch = originalEpoch
        val mdk = readPasswordAuthenticatedMdk(password, original)
        try {
            val oldProfile = PasswordKdf.fromValues(original)
            // Historical Argon2id is read-only compatibility, like the legacy 100k profile.
            val upgrading = oldProfile != PasswordKdf.PBKDF2_600K
            val values = if (upgrading) credentialUpdates(password, mdk)
                else emptyMap()
            fun ensureCurrent() {
                ensureActive()
                check(runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == epoch && attempt?.isCurrent() != false) {
                    "Vault locked during password authentication"
                }
            }
            ensureCurrent()
            var authenticatedCredentials: Map<String, *> = original + values
            try {
                localRecovery.enroll(sharedPreferences, password, values, expectedCredentials = original,
                    beforeCommit = {
                        synchronized(runtimeKeyLock) {
                            ensureCurrent()
                            if (upgrading) {
                                epoch = passwordCredentialEpoch.incrementAndGet()
                                attempt?.advanceCredentialEpoch(epoch)
                            }
                        }
                    })
            } catch (error: Exception) {
                // An optional upgrade that failed BEFORE canonical commit must not lock a
                // healthy old user out. A partially committed generation instead requires recovery.
                localRecovery.validate(sharedPreferences)
                if (!credentialsMatch(original) || original[MDK_PASSWORD_BLOB_KEY] == null) throw error
                authenticatedCredentials = original
                android.util.Log.w(logTag, "Password protection upgrade deferred; original credentials retained")
            }
            ensureCurrent()
            if (!sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) persistCompatKeystoreWrappedMdk(mdk)
            publishPasswordMdk(mdk, authenticatedCredentials, generation, epoch, attempt)
        } finally { mdk.fill(0) }
    }

    private fun publishPasswordMdk(
        mdk: ByteArray,
        credentials: Map<String, *>,
        generation: Long,
        credentialEpoch: Long,
        attempt: PasswordVerificationAttempt?,
    ) {
        check(isDataReadCurrent(generation) && credentialsMatch(credentials)) { "Password authentication was revoked" }
        mdkWrapperUnavailable = false
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
        if (attempt != null) attempt.stage(this, mdk, credentials)
        else synchronized(runtimeKeyLock) {
            check(runtimeKeyGeneration.get() == generation && passwordCredentialEpoch.get() == credentialEpoch) { "Vault locked during password authentication" }
            processCachedMdk = mdk.copyOf()
        }
    }

    private fun ensureMdkKeystoreWrapper() {
        if (sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) return
        val mdk = getOrCreateMdkBytes()
        if (mdk.isEmpty()) {
            android.util.Log.w("SecurityManager", "Cannot create MDK keystore wrapper without current MDK")
            return
        }
        persistKeystoreWrappedMdk(mdk)
    }

    private fun getOrCreateMdkBytes(): ByteArray {
        processCachedMdk?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached.copyOf()
            }
            processCachedMdk = null
            android.util.Log.w(logTag, "getOrCreateMdkBytes: ignoring empty runtime MDK cache")
            SecurityDiagLogger.append("W/$logTag getOrCreateMdkBytes: ignoring empty runtime MDK cache")
        }
        val passwordBlob = sharedPreferences.getString(MDK_PASSWORD_BLOB_KEY, null)
        val keystoreBlob = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null)
        if (passwordBlob == null && keystoreBlob == null) {
            check(!sharedPreferences.getBoolean(MDK_READY_KEY, false)) { "Existing vault key unavailable" }
            return generateRandom(32)
        }
        if (keystoreBlob == null) {
            android.util.Log.w("SecurityManager", "MDK keystore wrapper missing and no cached MDK is available")
            return ByteArray(0)
        }
        return try {
            readKeystoreWrappedMdk(keystoreBlob)
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "Get MDK by keystore failed: ${e.javaClass.simpleName}; wrapper retained")
            ByteArray(0)
        }
    }

    private fun readKeystoreWrappedMdk(blob: String): ByteArray {
        val wrapped = parseWrappedMdkBlob(blob)
        val key = getExistingDataKey(compat = wrapped.mode == WrapperMode.COMPAT)
        val combined = android.util.Base64.decode(wrapped.payload, android.util.Base64.NO_WRAP)
        require(combined.size == 60) { "Invalid existing MDK wrapper" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, combined, 0, 12))
        return cipher.doFinal(combined, 12, combined.size - 12).also { require(it.size == 32) }
    }

    private fun getMdkForCrypto(): ByteArray? {
        PreparedSecureStore.unwrap(sharedPreferences)
        val generation = runtimeKeyGeneration.get()
        processCachedMdk?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached
            }
            processCachedMdk = null
            android.util.Log.w(logTag, "getMdkForCrypto: ignoring empty runtime MDK cache")
            SecurityDiagLogger.append("W/$logTag getMdkForCrypto: ignoring empty runtime MDK cache")
        }
        val now = System.currentTimeMillis()
        if (now < mdkAuthUnavailableUntilMillis) {
            return null
        }
        val ready = sharedPreferences.getBoolean(MDK_READY_KEY, false)
        if (!ready) return null
        val rawBlob = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null) ?: return null
        val wrapped = parseWrappedMdkBlob(rawBlob)
        val usesCompatWrapper = wrapped.mode == WrapperMode.COMPAT
        return try {
            val ksKey = getExistingDataKey(compat = usesCompatWrapper)
            val combined = android.util.Base64.decode(wrapped.payload, android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val enc = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, ksKey, spec)
            val mdk = cipher.doFinal(enc)
            check(mdk.size == 32) { "Existing vault key unavailable" }
            PreparedSecureStore.unwrap(sharedPreferences)
            synchronized(runtimeKeyLock) {
                if (runtimeKeyGeneration.get() != generation) {
                    mdk.fill(0)
                    return null
                }
                processCachedMdk = mdk
            }
            mdkWrapperUnavailable = false
            mdkAuthUnavailableUntilMillis = 0L
            hasLoggedMdkAuthExpiredWarning = false
            hasLoggedMdkFallbackEncryption = false
            if (usesCompatWrapper) {
                android.util.Log.w(logTag, "getMdkForCrypto: using compatibility wrapper mode")
            }
            mdk
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException: 生物识别已更改，密钥永久失效
            // UserNotAuthenticatedException: 用户认证已过期，需要重新认证
            if (e is android.security.keystore.KeyPermanentlyInvalidatedException) {
                mdkWrapperUnavailable = true
                throw e  // 密钥永久失效，必须抛出让用户重新设置
            }
            if (e is UserNotAuthenticatedException) {
                if (!hasLoggedMdkAuthExpiredWarning) {
                    android.util.Log.w("SecurityManager", "User authentication expired, MDK not available")
                    hasLoggedMdkAuthExpiredWarning = true
                }
                // Cooldown to avoid hot-looping keystore access when auth is expired.
                mdkAuthUnavailableUntilMillis = System.currentTimeMillis() + mdkAuthCooldownMillis
                return null  // 认证过期，返回 null 让调用方降级处理
            }
            // Keep the original wrapper and password-recovery material. Rebuilding is allowed
            // only after authenticating the original MDK, never as a side effect of a read.
            mdkWrapperUnavailable = true
            mdkAuthUnavailableUntilMillis = System.currentTimeMillis() + mdkAuthCooldownMillis
            android.util.Log.w(logTag, "MDK wrapper unavailable: ${e.javaClass.simpleName}; wrapper retained")
            null
        }
    }

    private val DATA_PREFIX_MDK = "MDK|"

    /**
     * AES encryption for sensitive data (additional layer)
     * Automatically chooses between V2 (Secure KeyStore) and V1 (Legacy) based on biometric settings.
     * 
     * 安全策略：
     * - 优先使用 MDK 加密（最安全）
     * - 如果 MDK 不可用（认证过期），降级到 V1 加密
     * - 只有当密钥永久失效时才抛出异常
     */
    fun encryptData(data: String): String {
        // 尝试使用 MDK 加密
        val mdk = try {
            getMdkForCrypto()
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // 密钥永久失效（生物识别已更改），需要用户重新设置
            android.util.Log.e("SecurityManager", "Key permanently invalidated", e)
            throw e
        }
        
        if (mdk != null && mdk.isNotEmpty()) {
            val key = SecretKeySpec(mdk, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val enc = cipher.doFinal(data.toByteArray())
            val combined = iv + enc
            return DATA_PREFIX_MDK + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        }
        
        check(!isMasterPasswordSet()) { "Vault key required for recoverable encryption" }
        // Installations without a master password retain the legacy compatibility path.
        if (!hasLoggedMdkFallbackEncryption) {
            android.util.Log.d("SecurityManager", "MDK not available, using compat Keystore encryption")
            hasLoggedMdkFallbackEncryption = true
        }
        return encryptDataCompat(data)
    }

    /**
     * Compatibility helper: force compat payload for scenarios where
     * immediate readability is required under unstable MDK auth state.
     */
    fun encryptDataLegacyCompat(data: String): String {
        return if (isMasterPasswordSet()) encryptData(data) else encryptDataCompat(data)
    }

    internal fun migrateProtectedDeviceCiphertexts() {
        if (!isVaultRuntimeUnlocked()) return
        sharedPreferences.all.forEach { (name, value) ->
            if (value !is String || (!value.startsWith("C2|") && !value.startsWith("V2|"))) return@forEach
            if (!isVaultRuntimeUnlocked()) return
            val replacement = runCatching {
                val plain = decryptData(value)
                val encrypted = encryptData(plain)
                check(encrypted.startsWith("MDK|") && decryptData(encrypted) == plain)
                encrypted
            }.getOrNull() ?: return@forEach
            if (isVaultRuntimeUnlocked()) {
                localRecovery.compareAndSet(sharedPreferences, name, value, replacement)
            }
        }
    }

    /**
     * Timeline snapshots must never silently downgrade to the compatibility key
     * when the vault has a master password. They are readable only while the
     * main vault key is present in the authenticated runtime session.
     */
    fun encryptTimelineSnapshot(data: String): String {
        if (!isMasterPasswordSet()) return encryptDataCompat(data)
        check(isVaultRuntimeUnlocked()) { "Vault authentication required" }
        val encrypted = encryptData(data)
        check(encrypted.startsWith(DATA_PREFIX_MDK)) { "Timeline snapshot requires MDK encryption" }
        return encrypted
    }

    fun decryptTimelineSnapshot(encryptedData: String): String {
        if (isMasterPasswordSet()) {
            check(isVaultRuntimeUnlocked()) { "Vault authentication required" }
            check(
                encryptedData.startsWith(DATA_PREFIX_MDK) ||
                    encryptedData.startsWith(DATA_PREFIX_COMPAT)
            ) { "Invalid timeline snapshot encryption" }
        }
        return decryptData(encryptedData)
    }

    fun looksLikeMonicaCiphertext(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith(DATA_PREFIX_MDK) ||
            trimmed.startsWith(DATA_PREFIX_V2) ||
            trimmed.startsWith(DATA_PREFIX_COMPAT)
    }

    fun decryptDataIfMonicaCiphertext(value: String): String {
        return if (looksLikeMonicaCiphertext(value)) {
            decryptData(value)
        } else {
            value
        }
    }

    private fun encryptDataV2(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getOrGenerateSecureKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(data.toByteArray())
        
        // Combine IV and encrypted data
        val combined = iv + encryptedBytes
        return DATA_PREFIX_V2 + android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
    }

    private fun encryptDataCompat(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getOrGenerateCompatSecureKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val combined = iv + encryptedBytes
        return DATA_PREFIX_COMPAT + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }
    
    /** Validates an existing caller authorization; capturing a generation never authenticates. */
    internal fun isDataReadCurrent(generation: Long): Boolean = runCatching {
        PreparedSecureStore.unwrap(sharedPreferences)
        runtimeKeyGeneration.get() == generation
    }.getOrDefault(false)

    fun decryptData(encryptedData: String): String {
        PreparedSecureStore.unwrap(sharedPreferences)
        val generation = runtimeKeyGeneration.get()
        try {
            val result = decryptDataWithoutDiagnostic(encryptedData)
            PreparedSecureStore.unwrap(sharedPreferences)
            check(runtimeKeyGeneration.get() == generation) { "Vault changed during data read" }
            return result
        } catch (error: Exception) {
            val format = when {
                encryptedData.startsWith(DATA_PREFIX_MDK) -> RuntimeDiagnostics.DataFormat.MDK
                encryptedData.startsWith(DATA_PREFIX_V2) -> RuntimeDiagnostics.DataFormat.V2
                encryptedData.startsWith(DATA_PREFIX_COMPAT) -> RuntimeDiagnostics.DataFormat.C2
                else -> null
            }
            if (format != null) RuntimeDiagnostics.dataFailure(format, error)
            throw error
        }
    }

    private fun decryptDataWithoutDiagnostic(encryptedData: String): String {
        if (encryptedData.isEmpty()) {
            return ""
        }

        if (encryptedData.startsWith(DATA_PREFIX_MDK)) {
            val mdk = getMdkForCrypto()
            if (mdk == null || mdk.isEmpty()) throw Exception("MDK not available")
            val combined = android.util.Base64.decode(encryptedData.substring(DATA_PREFIX_MDK.length), android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val enc = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            val key = SecretKeySpec(mdk, "AES")
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val dec = cipher.doFinal(enc)
            return String(dec, kotlin.text.Charsets.UTF_8)
        }

        if (encryptedData.startsWith(DATA_PREFIX_V2)) {
            return decryptDataV2(encryptedData)
        }

        if (encryptedData.startsWith(DATA_PREFIX_COMPAT)) {
            return decryptDataCompat(encryptedData)
        }

        return decryptLegacyV1OrPlainText(encryptedData)
    }

    private fun decryptDataV2(encryptedData: String): String {
        val combined = android.util.Base64.decode(encryptedData.substring(DATA_PREFIX_V2.length), android.util.Base64.DEFAULT)
        
        // Extract IV and encrypted data
        val iv = combined.copyOfRange(0, 12) // GCM IV is 12 bytes
        val encrypted = combined.copyOfRange(12, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getExistingDataKey(compat = false)
        
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        
        val decryptedBytes = cipher.doFinal(encrypted)
        return String(decryptedBytes, kotlin.text.Charsets.UTF_8)
    }

    private fun decryptDataCompat(encryptedData: String): String {
        val combined = android.util.Base64.decode(
            encryptedData.substring(DATA_PREFIX_COMPAT.length),
            android.util.Base64.NO_WRAP
        )
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getExistingDataKey(compat = true)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(encrypted)
        return String(decryptedBytes, kotlin.text.Charsets.UTF_8)
    }

    private fun decryptLegacyV1OrPlainText(encryptedData: String): String {
        if (encryptedData.isEmpty()) return ""

        val combined = try {
            android.util.Base64.decode(encryptedData, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            // Not a Base64 payload, likely plain text.
            return encryptedData
        }

        // Legacy payload format is: 12-byte IV + ciphertext(>=0) + 16-byte GCM tag.
        // Empty plaintext is valid and yields exactly 28 bytes.
        if (combined.size < 28) {
            return encryptedData
        }

        return try {
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Historical unprefixed V1 payloads were derived from this predictable
            // value. Keep it read-only so old local data can be opened and migrated.
            val description = sharedPreferences.getString("legacy_master_key_description_v1", null) ?: masterKey.toString()
            val keyBytes = description.toByteArray().copyOf(32)
            val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(encrypted)
            String(decryptedBytes, kotlin.text.Charsets.UTF_8)
        } catch (_: Exception) {
            // Fallback to original data if decryption fails.
            encryptedData
        }
    }
    
    /**
     * Generate secure random password
     */
    fun generateSecurePassword(length: Int = 16, includeSymbols: Boolean = true): String {
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val numbers = "0123456789"
        val symbols = "!@#$%^&*()_+-=[]{}|;:,.<>?"
        
        val charset = lowercase + uppercase + numbers + if (includeSymbols) symbols else ""
        val random = SecureRandom()
        
        return (1..length)
        .map { charset[random.nextInt(charset.length)] }
        .joinToString("")
    }
    
    /**
     * Validate password strength
     */
    fun validatePasswordStrength(password: String): PasswordStrength {
        val length = password.length
        val hasLowercase = password.any { it.isLowerCase() }
        val hasUppercase = password.any { it.isUpperCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        
        val score = listOf(
            length >= 8,
            length >= 12,
            hasLowercase,
            hasUppercase,
            hasDigit,
            hasSymbol
        ).count { it }
        
        return when {
            score <= 2 -> PasswordStrength.WEAK
            score <= 4 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.STRONG
        }
    }
    
    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }
    
    // ==================== V2 Bitwarden 凭据管理 ====================
    
    /**
     * Bitwarden 凭据数据类
     * 用于存储登录后的认证信息
     */
    data class BitwardenCredential(
        val accessToken: String,
        val refreshToken: String,
        val tokenExpiry: Long,          // 过期时间戳（毫秒）
        val userEmail: String,
        val userId: String,
        val serverUrl: String = "https://vault.bitwarden.com"  // 默认官方服务器
    )
    
    /**
     * Bitwarden 加密密钥数据类
     * 用于存储解密 Vault 数据所需的密钥
     */
    data class BitwardenCryptoKeys(
        val masterKeyHash: String,      // 主密码哈希（用于验证）
        val symmetricKey: String,       // 对称加密密钥（加密存储）
        val privateKey: String?         // RSA 私钥（加密存储，可选）
    )
    
    /**
     * 保存 Bitwarden 登录凭据
     * 使用 EncryptedSharedPreferences 安全存储
     * 
     * @param credential Bitwarden 凭据
     */
    fun saveBitwardenCredential(credential: BitwardenCredential) {
        // Access Token 和 Refresh Token 使用额外的 AES-GCM 加密层
        val encryptedAccessToken = encryptData(credential.accessToken)
        val encryptedRefreshToken = encryptData(credential.refreshToken)
        
        sharedPreferences.edit()
            .putString(BITWARDEN_ACCESS_TOKEN_KEY, encryptedAccessToken)
            .putString(BITWARDEN_REFRESH_TOKEN_KEY, encryptedRefreshToken)
            .putLong(BITWARDEN_TOKEN_EXPIRY_KEY, credential.tokenExpiry)
            .putString(BITWARDEN_USER_EMAIL_KEY, credential.userEmail)
            .putString(BITWARDEN_USER_ID_KEY, credential.userId)
            .putString(BITWARDEN_SERVER_URL_KEY, credential.serverUrl)
            .putBoolean(BITWARDEN_CONNECTED_KEY, true)
            .apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden credential saved")
    }
    
    /**
     * 获取 Bitwarden 登录凭据
     * 
     * @return BitwardenCredential 或 null（未登录）
     */
    fun getBitwardenCredential(): BitwardenCredential? {
        if (!isBitwardenConnected()) {
            return null
        }
        
        return try {
            val encryptedAccessToken = sharedPreferences.getString(BITWARDEN_ACCESS_TOKEN_KEY, null)
            val encryptedRefreshToken = sharedPreferences.getString(BITWARDEN_REFRESH_TOKEN_KEY, null)
            
            if (encryptedAccessToken == null || encryptedRefreshToken == null) {
                return null
            }
            
            BitwardenCredential(
                accessToken = decryptData(encryptedAccessToken),
                refreshToken = decryptData(encryptedRefreshToken),
                tokenExpiry = sharedPreferences.getLong(BITWARDEN_TOKEN_EXPIRY_KEY, 0L),
                userEmail = sharedPreferences.getString(BITWARDEN_USER_EMAIL_KEY, "") ?: "",
                userId = sharedPreferences.getString(BITWARDEN_USER_ID_KEY, "") ?: "",
                serverUrl = sharedPreferences.getString(BITWARDEN_SERVER_URL_KEY, "https://vault.bitwarden.com") ?: "https://vault.bitwarden.com"
            )
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Failed to get Bitwarden credential", e)
            null
        }
    }
    
    /**
     * 保存 Bitwarden 加密密钥
     * 这些密钥用于解密从服务器获取的 Vault 数据
     * 
     * @param keys Bitwarden 加密密钥
     */
    fun saveBitwardenCryptoKeys(keys: BitwardenCryptoKeys) {
        // 所有密钥都使用 AES-GCM 加密存储
        val encryptedSymmetricKey = encryptData(keys.symmetricKey)
        val encryptedPrivateKey = keys.privateKey?.let { encryptData(it) }
        
        sharedPreferences.edit()
            .putString(BITWARDEN_MASTER_KEY_HASH_KEY, keys.masterKeyHash)
            .putString(BITWARDEN_SYMMETRIC_KEY_KEY, encryptedSymmetricKey)
            .apply {
                if (encryptedPrivateKey != null) {
                    putString(BITWARDEN_PRIVATE_KEY_KEY, encryptedPrivateKey)
                }
            }
            .apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden crypto keys saved")
    }
    
    /**
     * 获取 Bitwarden 加密密钥
     * 
     * @return BitwardenCryptoKeys 或 null
     */
    fun getBitwardenCryptoKeys(): BitwardenCryptoKeys? {
        return try {
            val masterKeyHash = sharedPreferences.getString(BITWARDEN_MASTER_KEY_HASH_KEY, null)
            val encryptedSymmetricKey = sharedPreferences.getString(BITWARDEN_SYMMETRIC_KEY_KEY, null)
            
            if (masterKeyHash == null || encryptedSymmetricKey == null) {
                return null
            }
            
            val encryptedPrivateKey = sharedPreferences.getString(BITWARDEN_PRIVATE_KEY_KEY, null)
            
            BitwardenCryptoKeys(
                masterKeyHash = masterKeyHash,
                symmetricKey = decryptData(encryptedSymmetricKey),
                privateKey = encryptedPrivateKey?.let { decryptData(it) }
            )
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Failed to get Bitwarden crypto keys", e)
            null
        }
    }
    
    /**
     * 检查是否已连接 Bitwarden
     * 
     * @return true 如果已保存凭据
     */
    fun isBitwardenConnected(): Boolean {
        return sharedPreferences.getBoolean(BITWARDEN_CONNECTED_KEY, false)
    }
    
    /**
     * 检查 Bitwarden Token 是否过期
     * 
     * @return true 如果已过期或未连接
     */
    fun isBitwardenTokenExpired(): Boolean {
        if (!isBitwardenConnected()) return true
        
        val expiry = sharedPreferences.getLong(BITWARDEN_TOKEN_EXPIRY_KEY, 0L)
        // 提前 5 分钟判断为过期，以便有时间刷新
        return System.currentTimeMillis() > (expiry - 5 * 60 * 1000)
    }
    
    /**
     * 更新 Bitwarden Access Token（Token 刷新后调用）
     * 
     * @param newAccessToken 新的 Access Token
     * @param newRefreshToken 新的 Refresh Token（可选）
     * @param newExpiry 新的过期时间
     */
    fun updateBitwardenTokens(
        newAccessToken: String,
        newRefreshToken: String? = null,
        newExpiry: Long
    ) {
        val encryptedAccessToken = encryptData(newAccessToken)
        
        sharedPreferences.edit().apply {
            putString(BITWARDEN_ACCESS_TOKEN_KEY, encryptedAccessToken)
            putLong(BITWARDEN_TOKEN_EXPIRY_KEY, newExpiry)
            
            if (newRefreshToken != null) {
                putString(BITWARDEN_REFRESH_TOKEN_KEY, encryptData(newRefreshToken))
            }
        }.apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden tokens updated, new expiry: $newExpiry")
    }
    
    /**
     * 获取 Bitwarden 用户邮箱
     * 
     * @return 用户邮箱或 null
     */
    fun getBitwardenUserEmail(): String? {
        return sharedPreferences.getString(BITWARDEN_USER_EMAIL_KEY, null)
    }
    
    /**
     * 获取 Bitwarden 服务器 URL
     * 
     * @return 服务器 URL
     */
    fun getBitwardenServerUrl(): String {
        return sharedPreferences.getString(BITWARDEN_SERVER_URL_KEY, "https://vault.bitwarden.com") 
            ?: "https://vault.bitwarden.com"
    }
    
    /**
     * 清除 Bitwarden 凭据（登出时调用）
     * 同时清除所有相关的加密密钥
     */
    fun clearBitwardenCredential() {
        sharedPreferences.edit()
            .remove(BITWARDEN_ACCESS_TOKEN_KEY)
            .remove(BITWARDEN_REFRESH_TOKEN_KEY)
            .remove(BITWARDEN_TOKEN_EXPIRY_KEY)
            .remove(BITWARDEN_USER_EMAIL_KEY)
            .remove(BITWARDEN_USER_ID_KEY)
            .remove(BITWARDEN_MASTER_KEY_HASH_KEY)
            .remove(BITWARDEN_SYMMETRIC_KEY_KEY)
            .remove(BITWARDEN_PRIVATE_KEY_KEY)
            .remove(BITWARDEN_SERVER_URL_KEY)
            .remove(BITWARDEN_CONNECTED_KEY)
            .apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden credential cleared")
    }

    fun putProtectedString(key: String, value: String?) {
        putProtectedStrings(mapOf(key to value))
    }

    internal fun putProtectedStrings(values: Map<String, String?>) {
        val committed = sharedPreferences.edit().apply {
            values.forEach { (key, value) ->
                if (value.isNullOrEmpty()) {
                    remove(key)
                } else {
                    putString(key, value)
                }
            }
        }.commit()
        check(committed) { "Protected value could not be saved" }
    }

    fun getProtectedString(key: String): String? {
        return sharedPreferences.getString(key, null)
    }

    fun removeProtectedString(key: String) {
        check(sharedPreferences.edit().remove(key).commit()) { "Protected value could not be removed" }
    }
    
    /**
     * 验证 Bitwarden 主密码哈希
     * 用于解锁时验证用户输入的主密码是否正确
     * 
     * @param masterPasswordHash 用户输入的主密码生成的哈希
     * @return true 如果匹配
     */
    fun verifyBitwardenMasterPasswordHash(masterPasswordHash: String): Boolean {
        val storedHash = sharedPreferences.getString(BITWARDEN_MASTER_KEY_HASH_KEY, null)
        return storedHash != null && storedHash == masterPasswordHash
    }
}
