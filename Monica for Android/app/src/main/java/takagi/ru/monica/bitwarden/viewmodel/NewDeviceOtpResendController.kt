package takagi.ru.monica.bitwarden.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import takagi.ru.monica.bitwarden.service.NewDeviceOtpResendException
import takagi.ru.monica.bitwarden.service.NewDeviceOtpResendFailure

enum class NewDeviceOtpResendOutcome { NONE, SENT, FAILED, UNAVAILABLE, RATE_LIMITED }

data class NewDeviceOtpResendState(
    val sending: Boolean = false,
    val remainingSeconds: Int = 0,
    val outcome: NewDeviceOtpResendOutcome = NewDeviceOtpResendOutcome.NONE
) {
    val canResend: Boolean get() = !sending && remainingSeconds == 0 && outcome != NewDeviceOtpResendOutcome.UNAVAILABLE
}

/** Confined to the ViewModel's main dispatcher. Holds no email, password, key or server. */
internal class NewDeviceOtpResendController(
    private val scope: CoroutineScope,
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val tick: suspend () -> Unit = { delay(1_000) }
) {
    private val _state = MutableStateFlow(NewDeviceOtpResendState())
    val state = _state.asStateFlow()
    private var session: Any? = null
    private var revision = 0L
    private var deadlineMillis = 0L
    private var requestJob: Job? = null
    private var countdownJob: Job? = null

    fun bind(session: Any?) {
        revision++
        requestJob?.cancel()
        countdownJob?.cancel()
        requestJob = null
        countdownJob = null
        this.session = session
        _state.value = NewDeviceOtpResendState(remainingSeconds = if (session == null) 0 else remaining())
        if (session != null) startCountdown(revision)
    }

    fun request(session: Any, stillValid: () -> Boolean = { true }, send: suspend () -> Result<Unit>): Boolean {
        if (this.session !== session || !stillValid() || !_state.value.canResend || remaining() > 0) return false
        val requestRevision = revision
        deadlineMillis = maxOf(deadlineMillis, elapsedMillis() + 30_000)
        _state.value = NewDeviceOtpResendState(sending = true, remainingSeconds = remaining())
        startCountdown(requestRevision)
        requestJob = scope.launch {
            if (!stillValid()) {
                if (revision == requestRevision) bind(null)
                return@launch
            }
            val result = try {
                send()
            } catch (cancelled: CancellationException) {
                if (revision == requestRevision) {
                    _state.value = _state.value.copy(sending = false)
                }
                throw cancelled
            } catch (_: Exception) {
                Result.failure(NewDeviceOtpResendException(NewDeviceOtpResendFailure.FAILED))
            }
            if (revision != requestRevision || this@NewDeviceOtpResendController.session !== session) return@launch
            if (!stillValid()) {
                bind(null)
                return@launch
            }
            val failure = result.exceptionOrNull() as? NewDeviceOtpResendException
            failure?.retryAfterSeconds?.let {
                deadlineMillis = maxOf(deadlineMillis, elapsedMillis() + it.toLong().coerceAtLeast(0) * 1000)
            }
            val outcome = if (result.isSuccess) NewDeviceOtpResendOutcome.SENT else when (failure?.failure) {
                NewDeviceOtpResendFailure.UNAVAILABLE -> NewDeviceOtpResendOutcome.UNAVAILABLE
                NewDeviceOtpResendFailure.RATE_LIMITED -> NewDeviceOtpResendOutcome.RATE_LIMITED
                else -> NewDeviceOtpResendOutcome.FAILED
            }
            _state.value = NewDeviceOtpResendState(remainingSeconds = remaining(), outcome = outcome)
            startCountdown(requestRevision)
        }
        return true
    }

    private fun remaining(): Int = ((deadlineMillis - elapsedMillis()).coerceAtLeast(0).let {
        it / 1000 + if (it % 1000 > 0) 1 else 0
    }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun startCountdown(expectedRevision: Long) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (revision == expectedRevision) {
                val seconds = remaining()
                _state.value = _state.value.copy(remainingSeconds = seconds)
                if (seconds == 0) break
                tick()
            }
        }
    }
}
