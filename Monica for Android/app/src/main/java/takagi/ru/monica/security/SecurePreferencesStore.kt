package takagi.ru.monica.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SecureStoragePhase {
    UNKNOWN, STARTUP_CHECK, PREFLIGHT, MASTER_KEY, KEYSET_OPEN, VALUE_READ, RECOVERY_VALIDATION
}

/** Opening failure is never an instruction to delete keys, clear preferences or use plaintext. */
internal class SecureStorageUnavailableException(
    val store: String,
    val reason: String,
    cause: Exception? = null,
    val phase: SecureStoragePhase = SecureStoragePhase.UNKNOWN
) : Exception("Secure storage unavailable: $reason", cause)

internal object SecurePreferencesStore {
    const val MONICA = "monica_secure_prefs"
    const val BITWARDEN = "bitwarden_secure_prefs"
    private const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"

    data class Opened(val masterKey: MasterKey, val preferences: SharedPreferences)

    @Synchronized
    fun preflight(context: Context, name: String, alias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS) {
        try {
            val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val contents = raw.all
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            if (contents.isEmpty() && (file.exists() || File(file.path + ".bak").exists())) {
                // Android may expose an empty map after XML loading fails. That is not
                // evidence of a new installation, and must not overwrite the old file.
                throw SecureStorageUnavailableException(name, "EMPTY_OR_UNREADABLE_STORE", phase = SecureStoragePhase.PREFLIGHT)
            }
            if (contents.isNotEmpty()) {
                // Tink otherwise generates a missing keyset, making an already damaged store worse.
                if (raw.getString(KEY_KEYSET, null).isNullOrBlank() || raw.getString(VALUE_KEYSET, null).isNullOrBlank()) {
                    throw SecureStorageUnavailableException(name, "INCOMPLETE_KEYSET", phase = SecureStoragePhase.PREFLIGHT)
                }
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!keyStore.containsAlias(alias)) {
                    throw SecureStorageUnavailableException(name, "MISSING_MASTER_KEY", phase = SecureStoragePhase.PREFLIGHT)
                }
            }
        } catch (error: SecureStorageUnavailableException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SecureStorageUnavailableException(name, "UNREADABLE_SECURE_STORAGE", error, SecureStoragePhase.PREFLIGHT)
        }
    }

    fun open(context: Context, name: String, alias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS): Opened {
        if (name == MONICA && alias == MasterKey.DEFAULT_MASTER_KEY_ALIAS) {
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.ACTIVE_STORE)
            val recovery = LocalVaultRecovery(context)
            val active = recovery.activeStore()
            val opened = openRaw(context, active?.first ?: name, active?.second ?: alias)
            try {
                RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.RECOVERY_VALIDATION)
                recovery.validate(opened.preferences)
            }
            catch (error: SecureStorageUnavailableException) { throw error }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                throw SecureStorageUnavailableException(MONICA, "RECOVERY_VALIDATION_FAILED", error, SecureStoragePhase.RECOVERY_VALIDATION)
            }
            return opened.copy(preferences = recovery.wrap(opened.preferences))
        }
        return openRaw(context, name, alias)
    }

    @Synchronized
    internal fun openRaw(context: Context, name: String, alias: String): Opened {
        var phase = SecureStoragePhase.PREFLIGHT
        try {
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.PREFLIGHT)
            preflight(context, name, alias)
            phase = SecureStoragePhase.MASTER_KEY
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.MASTER_KEY)
            val masterKey = MasterKey.Builder(context, alias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            phase = SecureStoragePhase.KEYSET_OPEN
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.KEYSET_OPEN)
            val prefs = EncryptedSharedPreferences.create(context, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            // Keyset authentication alone does not detect an unreadable individual preference.
            // Validate before any caller can interpret missing credentials as a fresh installation.
            phase = SecureStoragePhase.VALUE_READ
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.VALUE_READ)
            prefs.all
            return Opened(masterKey, prefs)
        } catch (error: SecureStorageUnavailableException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val authenticationFailed = generateSequence<Throwable>(error) { it.cause }.take(8)
                .any { it is javax.crypto.AEADBadTagException }
            throw SecureStorageUnavailableException(name,
                if (authenticationFailed) "AUTHENTICATION_FAILED" else "UNREADABLE_SECURE_STORAGE", error, phase)
        }
    }

    fun validateExisting(context: Context, name: String) {
        preflight(context, name)
        if (context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty()) open(context, name)
    }
}

internal sealed interface SecureStartupResult {
    data class Ready(val manager: SecurityManager) : SecureStartupResult
    data class Blocked(
        val failure: SecureStorageUnavailableException,
        val attempts: Int = 1,
        val recoveryAvailable: Boolean = false
    ) : SecureStartupResult
}

internal object SecureStorageStartup {
    // Only post-launch maintenance uses this process gate. A failed open must not start it.
    private val readiness = MutableStateFlow(false)
    private val retryMutex = Mutex()
    private val workLock = Any()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "monica-secure-startup").apply { isDaemon = true }
    }
    private var running: StartupWork? = null
    @Volatile private var attempted = false
    val readyForMaintenance: Boolean get() = readiness.value
    internal val operationInProgress: Boolean get() = synchronized(workLock) { running != null }

    private class StartupWork(context: Context) {
        val guard = StartupStorageGuard(context)
        val result = CompletableDeferred<SecureStartupResult>() // No parent: native calls cannot be cancelled.
        var finished = false // Protected by workLock.
        var released = false // Caller has accepted or abandoned its result.
    }

    /** Bound the wait, not the native call. Keep its single slot occupied until it really exits. */
    suspend fun prepareWithRetry(context: Context, timeoutMillis: Long = 15_000): SecureStartupResult {
        require(timeoutMillis > 0)
        val trace = RuntimeDiagnostics.beginStartup()
        var work: StartupWork? = null
        var accepted = false
        try {
            val result = withTimeoutOrNull(timeoutMillis) {
                retryMutex.withLock {
                    ensureActive()
                    val started = synchronized(workLock) {
                        if (running != null) return@withLock blocked("SECURE_OPERATION_IN_PROGRESS")
                        attempted = true
                        readiness.value = false
                        StartupWork(context.applicationContext).also { running = it; work = it }
                    }
                    trace.stage(RuntimeDiagnostics.Stage.QUEUED)
                    executor.execute {
                        trace.run {
                            try {
                                trace.stage(RuntimeDiagnostics.Stage.STARTUP_CHECK)
                                started.result.complete(checkWithRetry(started.guard))
                            } catch (cancelled: CancellationException) {
                                started.result.completeExceptionally(cancelled)
                            } catch (error: Exception) {
                                val failure = SecureStorageUnavailableException(SecurePreferencesStore.MONICA,
                                    "UNREADABLE_SECURE_STORAGE", error, SecureStoragePhase.STARTUP_CHECK)
                                trace.failure(failure, 1)
                                started.result.complete(SecureStartupResult.Blocked(failure))
                            } finally {
                                val callerReleased = synchronized(workLock) {
                                    started.finished = true
                                    if (started.released && running === started) running = null
                                    started.released
                                }
                                trace.event("secure_worker_exit", "caller_released=$callerReleased")
                            }
                        }
                    }
                    val checked = started.result.await()
                    ensureActive()
                    checked
                }
            } ?: run {
                trace.outcome(RuntimeDiagnostics.Outcome.TIMEOUT)
                return blocked("SECURE_OPERATION_TIMEOUT")
            }
            currentCoroutineContext().ensureActive()
            // Publish only after the timeout scope has accepted the result. A timeout racing a
            // completed worker must not return Blocked with maintenance already enabled.
            if (work != null) synchronized(workLock) {
                readiness.value = result is SecureStartupResult.Ready
                accepted = true
            }
            if (result is SecureStartupResult.Blocked && work != null) SessionManager.markLocked()
            if (result is SecureStartupResult.Blocked) {
                trace.event("secure_result_code", "code=${SafeExceptionDiagnostic.reason(result.failure.reason)}", android.util.Log.WARN)
            }
            trace.outcome(if (result is SecureStartupResult.Ready) RuntimeDiagnostics.Outcome.READY else RuntimeDiagnostics.Outcome.BLOCKED)
            return result
        } catch (cancelled: CancellationException) {
            trace.outcome(RuntimeDiagnostics.Outcome.CANCELLED)
            throw cancelled
        } finally {
            synchronized(workLock) {
                work?.let {
                    it.released = true
                    if (!accepted) {
                        it.guard.abandon()
                        readiness.value = false
                        SessionManager.markLocked()
                    }
                    if (it.finished && running === it) running = null
                }
            }
        }
    }

    private fun blocked(reason: String) = SecureStartupResult.Blocked(SecureStorageUnavailableException(
        SecurePreferencesStore.MONICA, reason, phase = SecureStoragePhase.STARTUP_CHECK))

    private fun checkWithRetry(guard: StartupStorageGuard): SecureStartupResult {
        var attempt = 1
        var result = checkStorage(guard.context)
        while (result is SecureStartupResult.Blocked) {
            guard.checkActive()
            SecurityDiagLogger.append("W/SecureStorageStartup ${diagnostic(result.failure, attempt)}")
            RuntimeDiagnostics.startupFailure(result.failure, attempt)
            // Authentication errors and missing key material require explicit user action.
            if (result.failure.reason != "UNREADABLE_SECURE_STORAGE" || attempt == 3) {
                RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.RECOVERY_AVAILABLE)
                val recoveryAvailable = LocalVaultRecovery(guard.context).available()
                guard.checkActive()
                return result.copy(attempts = attempt, recoveryAvailable = recoveryAvailable)
            }
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.RETRY_DELAY)
            Thread.sleep(if (attempt == 1) 200L else 600L)
            guard.checkActive()
            attempt++
            result = checkStorage(guard.context)
        }
        guard.checkActive()
        if (attempt > 1) SecurityDiagLogger.append("I/SecureStorageStartup readable_after_attempts=$attempt")
        return result
    }

    suspend fun awaitReadyForMaintenance(context: Context) {
        // Application may be started by a background worker without MainActivity.
        // Healthy storage must still permit its normal maintenance in that case.
        // After a blocked/cancelled attempt, only an explicit startup retry may reopen the gate.
        if (!readyForMaintenance && !attempted) prepareWithRetry(context)
        readiness.first { it }
    }

    @Synchronized
    fun prepare(context: Context): SecureStartupResult {
        // Synchronous boundary retained for isolated device tests. Production uses the bounded worker.
        attempted = true
        readiness.value = false
        val result = checkStorage(context)
        readiness.value = result is SecureStartupResult.Ready
        if (result is SecureStartupResult.Blocked) SessionManager.markLocked()
        return result
    }

    private fun checkStorage(context: Context): SecureStartupResult {
        return try {
            SecurityDiagLogger.initialize(context.applicationContext)
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.ACTIVE_STORE)
            // Do not create the shared legacy alias over an existing Bitwarden store.
            // An unreadable Bitwarden settings store must not block a healthy local vault.
            if (LocalVaultRecovery(context).activeStore() == null &&
                context.getSharedPreferences(SecurePreferencesStore.MONICA, Context.MODE_PRIVATE).all.isEmpty()) {
                RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.PREFLIGHT)
                SecurePreferencesStore.preflight(context, SecurePreferencesStore.BITWARDEN)
            }
            val manager = SecurityManager(context)
            SecureStartupResult.Ready(manager)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The preliminary raw-store read can fail before SecurityManager is constructed.
            val failure = error as? SecureStorageUnavailableException ?: SecureStorageUnavailableException(
                SecurePreferencesStore.MONICA, "UNREADABLE_SECURE_STORAGE", error, SecureStoragePhase.STARTUP_CHECK)
            SecureStartupResult.Blocked(failure)
        }
    }

    fun diagnostic(failure: SecureStorageUnavailableException, attempts: Int = 1): String = buildString {
        appendLine("Monica ${takagi.ru.monica.BuildConfig.FULL_VERSION_NAME}")
        appendLine("Build: ${takagi.ru.monica.BuildConfig.GIT_SHA}")
        appendLine("Logcat tag: ${RuntimeDiagnostics.TAG}")
        appendLine("Android API ${android.os.Build.VERSION.SDK_INT}")
        appendLine("Secure storage: ${failure.store}")
        appendLine("Code: ${failure.reason}")
        appendLine("Phase: ${failure.phase.name}")
        appendLine("Attempts: $attempts")
        // Types and numeric system codes only: never include exception messages, paths or values.
        val causes = generateSequence(failure.cause) { it.cause }.take(8).toList()
        appendLine("Causes: ${causes.joinToString(" -> ") { it.javaClass.simpleName }.ifEmpty { "none" }}")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            causes.filterIsInstance<android.security.KeyStoreException>().firstOrNull()?.let {
                appendLine("Keystore code: ${it.numericErrorCode}; transient: ${it.isTransientFailure}")
            }
        }
        append("No automatic reset performed.")
    }
}
