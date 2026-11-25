package takagi.ru.monica.ui.model

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** Display-only classification. Never retains an exception, key, or secret. */
enum class PasswordReadFailure {
    UNKNOWN, AUTH_REQUIRED, KEY_INVALIDATED, KEY_UNAVAILABLE, AUTHENTICATION_FAILED;

    companion object {
        fun from(error: Throwable): PasswordReadFailure {
            val causes = ArrayList<Throwable>(8)
            var current: Throwable? = error
            while (current != null && causes.size < 8 && causes.none { it === current }) {
                causes += current
                current = current.cause
            }
            return when {
                causes.any { it is UserNotAuthenticatedException } -> AUTH_REQUIRED
                causes.any { it is KeyPermanentlyInvalidatedException } -> KEY_INVALIDATED
                causes.any { it is AEADBadTagException } -> AUTHENTICATION_FAILED
                causes.any { it is UnrecoverableKeyException } -> KEY_UNAVAILABLE
                else -> UNKNOWN
            }
        }
    }
}
