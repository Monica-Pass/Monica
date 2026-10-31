package takagi.ru.monica.security

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import takagi.ru.monica.BuildConfig

/** Bounded, local diagnostics. Never pass exception messages or vault values to this logger. */
internal object RuntimeDiagnostics {
    const val TAG = "MonicaDiag"
    private val processStartedAt = System.currentTimeMillis()
    private val session = "${Process.myPid()}-${SystemClock.elapsedRealtime()}"
    private val installed = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val current = ThreadLocal<StartupDiagnosticTrace>()
    private val lastDataFailure = AtomicLongArray(DataFormat.entries.size)

    enum class Stage {
        WAIT_TURN, QUEUED, STARTUP_CHECK, ACTIVE_STORE, PREFLIGHT, MASTER_KEY,
        KEYSET_OPEN, VALUE_READ, RECOVERY_VALIDATION, RECOVERY_AVAILABLE, RETRY_DELAY
    }
    enum class Outcome { READY, BLOCKED, TIMEOUT, CANCELLED }
    enum class DataFormat { MDK, V2, C2 }

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        output(Log.INFO, "event=app_start version=${BuildConfig.FULL_VERSION_NAME} code=${BuildConfig.VERSION_CODE}" +
            " git=${BuildConfig.GIT_SHA} build=${BuildConfig.BUILD_TIME} api=${Build.VERSION.SDK_INT}")
        // The platform handler still owns crash reporting/termination; diagnostics never consume it.
        val delegate = Thread.getDefaultUncaughtExceptionHandler() ?: return
        runCatching { Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(delegate) { thread, error ->
            output(Log.ERROR, "event=fatal thread_id=${thread.id} main=${thread === android.os.Looper.getMainLooper().thread}", persist = false)
            SafeExceptionDiagnostic.lines(error).forEach { output(Log.ERROR, "event=fatal_detail $it", persist = false) }
        }) }
    }

    fun applicationReady() = output(Log.INFO, "event=application_ready")
    fun mainActivityCreated() = output(Log.INFO, "event=main_activity_created")

    /** Call off the main thread, independently of secure-storage readiness. No descriptions/traces. */
    fun reportPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .filter { it.timestamp < processStartedAt }
                .forEach { exit ->
                    output(Log.INFO, "event=previous_exit reason=${exitReason(exit.reason)} pid=${exit.pid}" +
                        " timestamp=${exit.timestamp} status=${exit.status} importance=${exit.importance}" +
                        " pss_kb=${exit.pss} rss_kb=${exit.rss}")
                }
        }.onFailure { error ->
            output(Log.WARN, "event=previous_exit_unavailable type=${SafeExceptionDiagnostic.type(error)}")
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> "OTHER_$reason"
    }

    fun beginStartup(): StartupDiagnosticTrace = StartupDiagnosticTrace(sequence.incrementAndGet()).also {
        it.event("secure_begin")
    }

    fun stage(stage: Stage) { current.get()?.stage(stage) }

    fun startupFailure(failure: SecureStorageUnavailableException, attempt: Int) {
        current.get()?.failure(failure, attempt)
    }

    fun dataFailure(format: DataFormat, error: Exception) { runCatching {
        // A damaged row may be read repeatedly during composition. Bound log traffic by format.
        val now = SystemClock.elapsedRealtime()
        val previous = lastDataFailure.get(format.ordinal)
        if (previous != 0L && now - previous < 5_000L) return
        if (!lastDataFailure.compareAndSet(format.ordinal, previous, now)) return
        output(Log.WARN, "event=data_read_failure format=${format.name}")
        SafeExceptionDiagnostic.lines(error).forEach { output(Log.WARN, "event=data_read_detail format=${format.name} $it") }
    } }

    private fun output(priority: Int, fields: String, persist: Boolean = true) {
        // println is deliberately outside the release rules stripping Log.d/v/i. Do not use i().
        runCatching { Log.println(priority, TAG, "session=$session $fields") }
        if (persist) runCatching { SecurityDiagLogger.append("$TAG session=$session $fields") }
    }

    class StartupDiagnosticTrace internal constructor(val id: Long) {
        private val beganAt = SystemClock.elapsedRealtime()
        @Volatile var lastStage = Stage.WAIT_TURN
            private set

        fun stage(stage: Stage) {
            lastStage = stage
            event("secure_stage")
        }
        fun event(event: String, extra: String = "", priority: Int = Log.INFO) {
            output(priority, "event=$event request=$id stage=${lastStage.name}" +
                " elapsed_ms=${SystemClock.elapsedRealtime() - beganAt}" + if (extra.isEmpty()) "" else " $extra")
        }
        fun outcome(outcome: Outcome) = event("secure_result", "outcome=${outcome.name}",
            if (outcome == Outcome.READY) Log.INFO else Log.WARN)

        fun failure(failure: SecureStorageUnavailableException, attempt: Int) { runCatching {
            val store = when (failure.store) {
                SecurePreferencesStore.MONICA -> "MONICA"
                SecurePreferencesStore.BITWARDEN -> "BITWARDEN"
                else -> "OTHER" // Recovery selectors and test stores may contain random/path data.
            }
            event("secure_failure", "attempt=$attempt store=$store code=${SafeExceptionDiagnostic.reason(failure.reason)}" +
                " phase=${failure.phase.name}", Log.WARN)
            SafeExceptionDiagnostic.lines(failure).forEach { event("secure_failure_detail", it, Log.WARN) }
        } }

        fun <T> run(operation: () -> T): T {
            val previous = current.get()
            current.set(this)
            try { return operation() }
            finally { if (previous == null) current.remove() else current.set(previous) }
        }
    }
}

/** Never call Throwable.toString(), getMessage(), printStackTrace() or log a raw Throwable here. */
internal object SafeExceptionDiagnostic {
    private val reasons = setOf("INVALID_RECOVERY_SELECTOR", "RECOVERY_COMMIT_PENDING", "AUTHENTICATION_FAILED",
        "EMPTY_OR_UNREADABLE_STORE", "INCOMPLETE_KEYSET", "MISSING_MASTER_KEY", "RECOVERY_VALIDATION_FAILED",
        "SECURE_OPERATION_IN_PROGRESS", "SECURE_OPERATION_TIMEOUT", "UNREADABLE_SECURE_STORAGE")
    fun reason(value: String): String = value.takeIf { it in reasons } ?: "UNKNOWN"
    fun type(error: Throwable): String = error.javaClass.name

    fun lines(error: Throwable): List<String> {
        val output = ArrayList<String>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        var index = 0
        while (cause != null && index < 8 && visited.add(cause)) {
            val item = cause
            var description = "cause=$index type=${type(item)}"
            if (Build.VERSION.SDK_INT >= 33 && item is android.security.KeyStoreException) {
                description += " keystore_code=${item.numericErrorCode} transient=${item.isTransientFailure}"
            }
            output.add(description)
            // Filename can be a path. Class/method/line are sufficient for the exact build mapping.
            item.stackTrace.take(8).forEach { frame ->
                output.add("cause=$index frame=${frame.className}.${frame.methodName}:${frame.lineNumber}")
            }
            item.suppressed.take(2).forEach { output.add("cause=$index suppressed_type=${type(it)}") }
            cause = item.cause
            index++
        }
        if (cause != null) output.add("causes_truncated=true")
        return output
    }
}

internal class DiagnosticCrashHandler(
    private val delegate: Thread.UncaughtExceptionHandler,
    private val record: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { runCatching { record(thread, error) } }
        finally { delegate.uncaughtException(thread, error) }
    }
}
