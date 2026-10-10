package takagi.ru.monica.passkey

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry

class PasskeyPortabilityTest {
    private val row = PasskeyEntry(credentialId = "id", rpId = "example.test", rpName = "Example", userId = "user",
        userName = "User", userDisplayName = "User", publicKey = "public", privateKeyAlias = "key")

    @Test fun onlyUsableNonexportableKeysAreLocalOnly() {
        assertEquals(PasskeyPortability.DEVICE_ONLY, PasskeyPortability.classify(row, true, false))
        assertEquals(PasskeyPortability.KEY_UNAVAILABLE, PasskeyPortability.classify(row, false, false))
        assertEquals(PasskeyPortability.PORTABLE, PasskeyPortability.classify(row, true, true))
    }
    @Test fun backupMetadataAndCounterHistoryAreRestrictionsRatherThanDeviceBinding() {
        assertEquals(PasskeyPortability.BACKUP_RESTRICTED, PasskeyPortability.classify(row.copy(backupEligible = false), true, true))
        assertEquals(PasskeyPortability.INVALID_FLAGS, PasskeyPortability.classify(row.copy(backupEligible = false, backupState = true), true, true))
        assertEquals(PasskeyPortability.COUNTER_HISTORY, PasskeyPortability.classify(row.copy(signCount = 41), true, true))
        assertEquals(PasskeyPortability.ALGORITHM_RESTRICTED, PasskeyPortability.classify(row.copy(publicKeyAlgorithm = -257), true, true))
    }
}
