package takagi.ru.monica.bitwarden.service

import java.time.Instant
import takagi.ru.monica.bitwarden.api.CipherApiResponse

/** The version last read locally is the edit baseline, never a fresh GET's version. */
internal object BitwardenCipherRevisionGuard {
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return false
        if (expected == actual) return true
        return runCatching { Instant.parse(expected) == Instant.parse(actual) }.getOrDefault(false)
    }

    fun requireUnchanged(expected: String?, source: CipherApiResponse) {
        check(matches(expected, source.revisionDate)) {
            "Remote cipher changed since the local edit; review the conflict before uploading"
        }
    }
}
