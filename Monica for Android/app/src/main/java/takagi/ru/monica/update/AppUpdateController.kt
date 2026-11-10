package takagi.ru.monica.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import takagi.ru.monica.utils.UpdateCheckResult
import takagi.ru.monica.utils.UpdateDownloadProgress

data class AppUpdateState(
    val loaded: Boolean = false,
    val preferences: UpdatePreferencesState = UpdatePreferencesState(),
    val checking: Boolean = false,
    val result: UpdateCheckResult? = null,
    val error: String? = null,
    val downloading: Boolean = false,
    val progress: UpdateDownloadProgress? = null,
    val dialogVisible: Boolean = false,
)

/** One foreground owner for automatic checks, manual checks and installation. */
class AppUpdateController(
    private val scope: CoroutineScope,
    private val readPreferences: suspend () -> UpdatePreferencesState,
    private val writeEnabled: suspend (Boolean) -> Unit,
    private val writeAttempt: suspend (Long) -> Unit,
    private val writeSkipped: suspend (String) -> Unit,
    private val fetch: suspend () -> Result<UpdateCheckResult>,
    private val download: suspend (UpdateCheckResult, suspend (UpdateDownloadProgress) -> Unit) -> Result<File>,
    private val install: (File) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = mutableState
    private var eligible = false
    private var foreground = false
    private var allowAutomatic = false
    private var generation = 0L
    private var operation: Job? = null
    private var automaticOperation = false
    private var preferenceGeneration = 0L
    private var persistedAutoCheckEnabled = false

    init {
        scope.launch {
            val prefs = try {
                readPreferences()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed preferences read must not silently turn networking on.
                UpdatePreferencesState(autoCheckEnabled = false)
            }
            persistedAutoCheckEnabled = prefs.autoCheckEnabled
            mutableState.value = mutableState.value.copy(loaded = true, preferences = prefs)
            maybeCheckAutomatically()
        }
    }

    fun setEnvironment(eligible: Boolean, foreground: Boolean, allowAutomatic: Boolean) {
        this.eligible = eligible
        this.foreground = foreground
        this.allowAutomatic = allowAutomatic
        if (!eligible || !foreground || (!allowAutomatic && automaticOperation)) dismiss()
        else maybeCheckAutomatically()
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        if (!state.value.loaded) return
        val preferenceToken = ++preferenceGeneration
        mutableState.value = state.value.copy(preferences = state.value.preferences.copy(autoCheckEnabled = enabled))
        if (!enabled && automaticOperation) dismiss()
        scope.launch {
            try {
                writeEnabled(enabled)
                persistedAutoCheckEnabled = enabled
                if (preferenceToken == preferenceGeneration && enabled && state.value.preferences.autoCheckEnabled) maybeCheckAutomatically()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (preferenceToken == preferenceGeneration) {
                    if (automaticOperation) dismiss()
                    mutableState.value = state.value.copy(
                        preferences = state.value.preferences.copy(autoCheckEnabled = persistedAutoCheckEnabled),
                        error = error.message ?: error.javaClass.simpleName,
                        dialogVisible = eligible && foreground,
                    )
                }
            }
        }
    }

    fun checkManually() = check(manual = true)

    private fun maybeCheckAutomatically() {
        if (allowAutomatic && !state.value.dialogVisible) check(manual = false)
    }

    private fun check(manual: Boolean) {
        if (!eligible || !foreground || !state.value.loaded || operation?.isActive == true) return
        val attempt = now()
        if (!AppUpdatePolicy.shouldCheck(state.value.preferences, attempt, manual)) return
        val token = ++generation
        automaticOperation = !manual
        mutableState.value = state.value.copy(
            checking = true, result = null, error = null, dialogVisible = false,
            preferences = state.value.preferences.copy(lastAttemptAtMillis = attempt),
        )
        operation = scope.launch {
            try {
                writeAttempt(attempt)
                val result = fetch().getOrThrow()
                ensureActive()
                if (!isCurrent(token)) return@launch
                mutableState.value = state.value.copy(
                    checking = false, result = result,
                    dialogVisible = AppUpdatePolicy.shouldPresent(result, state.value.preferences, manual),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(token)) mutableState.value = state.value.copy(
                    checking = false, error = error.message ?: error.javaClass.simpleName, dialogVisible = manual,
                )
            } finally {
                if (generation == token) {
                    mutableState.value = state.value.copy(checking = false)
                    automaticOperation = false
                }
            }
        }
    }

    fun skipVersion() {
        val result = state.value.result ?: return
        if (!result.isUpdateAvailable) return
        val previous = state.value.preferences.skippedReleaseIdentity
        mutableState.value = state.value.copy(
            preferences = state.value.preferences.copy(skippedReleaseIdentity = result.releaseIdentity),
        )
        dismiss()
        val token = generation
        scope.launch {
            try {
                writeSkipped(result.releaseIdentity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (state.value.preferences.skippedReleaseIdentity == result.releaseIdentity) {
                    mutableState.value = state.value.copy(preferences = state.value.preferences.copy(skippedReleaseIdentity = previous))
                }
                if (isCurrent(token)) mutableState.value = state.value.copy(result = result,
                    error = error.message ?: error.javaClass.simpleName, dialogVisible = true)
            }
        }
    }

    fun startDownload() {
        val result = state.value.result ?: return
        if (!eligible || !foreground || !result.isUpdateAvailable || result.isFdroid ||
            result.apkDownloadUrl.isNullOrBlank() || operation?.isActive == true) return
        val token = ++generation
        mutableState.value = state.value.copy(downloading = true, progress = null, error = null)
        operation = scope.launch {
            var readyFile: File? = null
            try {
                readyFile = download(result) { progress ->
                    ensureActive()
                    if (isCurrent(token)) mutableState.value = state.value.copy(progress = progress)
                }.getOrThrow()
                ensureActive()
                if (!isCurrent(token)) return@launch
                install(readyFile)
                readyFile = null // The system installer owns the URI until it finishes reading.
                mutableState.value = state.value.copy(downloading = false, dialogVisible = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(token)) mutableState.value = state.value.copy(error = error.message ?: error.javaClass.simpleName)
            } finally {
                readyFile?.delete()
                if (generation == token) mutableState.value = state.value.copy(downloading = false)
            }
        }
    }

    fun dismiss() {
        ++generation
        operation?.cancel()
        operation = null
        automaticOperation = false
        mutableState.value = state.value.copy(
            checking = false, downloading = false, result = null, error = null,
            progress = null, dialogVisible = false,
        )
    }

    private fun isCurrent(token: Long) = generation == token && eligible && foreground
}
