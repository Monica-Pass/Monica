package takagi.ru.monica.security

import org.junit.Assert.*
import org.junit.Test

/** Runs on either build, deliberately failing on the frozen old implementation. */
class OldRecoveryBypassInstrumentedTest {
    @Test fun cachedOrCompatMdkIsNotResetAuthorization() {
        for (cached in listOf(true, false)) RecoveryTestFixture().use { f ->
            if (!cached) SecurityManager.clearRuntimeUnlockCache()
            val before = f.prefs.all.toMap()
            assertFalse("An existing MDK must not authorize replacing the master password",
                f.manager.setMasterPassword(f.replacement))
            assertEquals(before, f.prefs.all)
            f.assertRecord(f.password)
        }
    }
}
