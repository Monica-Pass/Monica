package takagi.ru.monica.ui.model

import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordReadFailureTest {
    @Test fun authenticationFailureNeverClaimsMissingKey() {
        assertEquals(PasswordReadFailure.AUTHENTICATION_FAILED,
            PasswordReadFailure.from(IllegalStateException("private payload", AEADBadTagException("private key"))))
    }
    @Test fun unavailableKeyIsDistinctFromAuthenticationFailure() {
        assertEquals(PasswordReadFailure.KEY_UNAVAILABLE,
            PasswordReadFailure.from(UnrecoverableKeyException("private alias")))
    }
    @Test fun arbitraryMessagesCannotClaimACause() {
        for (message in listOf("OMK changed", "AEADBadTagException", "MDK not available", "BAD_DECRYPT")) {
            assertEquals(PasswordReadFailure.UNKNOWN, PasswordReadFailure.from(Exception(message)))
        }
    }
    @Test fun causeCyclesAreBounded() {
        val first = Exception("one")
        val second = Exception("two", first)
        first.initCause(second)
        assertEquals(PasswordReadFailure.UNKNOWN, PasswordReadFailure.from(first))
    }
}
