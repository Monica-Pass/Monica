package takagi.ru.monica.bitwarden.service

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class NewDeviceOtpResendFailure { FAILED, UNAVAILABLE, RATE_LIMITED }

/** Only fixed failure categories and a bounded delay may cross into presentation state. */
class NewDeviceOtpResendException(
    val failure: NewDeviceOtpResendFailure,
    val retryAfterSeconds: Int? = null
) : Exception("New-device verification email request failed: ${failure.name}")

internal fun newDeviceOtpRetryAfterSeconds(value: String?, nowMillis: Long = System.currentTimeMillis()): Int? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toLongOrNull()?.let { return it.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
    return try {
        val untilMillis = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        ((untilMillis - nowMillis).coerceAtLeast(0) / 1000 +
            if ((untilMillis - nowMillis).coerceAtLeast(0) % 1000 > 0) 1 else 0)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } catch (_: Exception) {
        null
    }
}
