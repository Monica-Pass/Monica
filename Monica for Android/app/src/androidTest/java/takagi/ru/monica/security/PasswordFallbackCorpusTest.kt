package takagi.ru.monica.security

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.Security
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host orders phases with process death; fixture source/build provenance is recorded by the host. */
class PasswordFallbackCorpusTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "Synthetic-upgrade-corpus-316!"
    private fun authorize() {
        check(InstrumentationRegistry.getArguments().getString("isolatedRecoveryUser") == "yes")
        check(android.os.Process.myUid() / 100_000 ==
            InstrumentationRegistry.getArguments().getString("expectedRecoveryUser")?.toInt())
        check(context.packageName.startsWith("takagi.ru.monica.resilience"))
    }
    private inline fun <T> withoutProvider(action: () -> T): T {
        val provider = checkNotNull(Security.getProvider("AndroidKeyStore"))
        val position = Security.getProviders().indexOf(provider) + 1
        try {
            Security.removeProvider(provider.name)
            assertThrows(java.security.KeyStoreException::class.java) { KeyStore.getInstance("AndroidKeyStore") }
            return action()
        } finally { assertEquals(position, Security.insertProviderAt(provider, position)) }
    }

    @Test fun recoverCompleteCorpusWithoutProviderAndKeepOriginals() = runBlocking {
        authorize()
        val corpus = LegacyVaultCorpus()
        corpus.verify()
        check(File(corpus.root, "migration-passed").isFile)
        val config = File(context.applicationInfo.dataDir, "shared_prefs/monica_secure_prefs.xml")
        val recoveryFile = File(context.filesDir, "local-vault-recovery/recovery.json")
        val originalConfig = config.readBytes()
        val originalRecovery = recoveryFile.readBytes()
        SecurityManager.clearRuntimeUnlockCache(); PreparedSecureStore.invalidate(); SessionManager.markLocked()
        withoutProvider {
            val recovery = LocalVaultRecovery(context)
            assertFalse(recovery.recoverWithoutKeystore("incorrect-password"))
            assertTrue(recovery.recoverWithoutKeystore(password))
            assertTrue(recovery.usesPasswordStore())
            LegacyVaultCorpus().verify()
            // Re-authentication retires the old in-memory configuration even when
            // the durable selector is unchanged. Attachments must still reopen.
            recovery.forgetPasswordSession()
            assertTrue(recovery.recoverWithoutKeystore(password))
            LegacyVaultCorpus().verify()
            assertArrayEquals(originalConfig, config.readBytes())
            assertArrayEquals(originalRecovery, recoveryFile.readBytes())
        }
        File(corpus.root, "password-fallback-passed").writeText("All retained corpus assertions passed without AndroidKeyStore")
    }

    @Test fun coldPasswordFallbackReadsOriginalCorpusAndSavesNewData() = runBlocking {
        authorize()
        assertTrue(File(context.filesDir, "recovery-upgrade-corpus/password-fallback-passed").isFile)
        val recovery = LocalVaultRecovery(context)
        assertTrue(recovery.usesPasswordStore())
        assertTrue(SecureStorageStartup.prepare(context) is SecureStartupResult.Blocked)
        withoutProvider {
            assertTrue(recovery.recoverWithoutKeystore(password))
            val corpus = LegacyVaultCorpus()
            corpus.verify()
            val encoded = corpus.security.encryptData("Synthetic cold write after recovery")
            File(corpus.root, "new-password.cipher").writeText(encoded)
            assertEquals("Synthetic cold write after recovery", corpus.security.decryptData(encoded))
        }
    }

    @Test fun coldNewWriteAndEncryptedBackupRestoreWithoutProvider() = runBlocking {
        authorize()
        withoutProvider {
            assertTrue(LocalVaultRecovery(context).recoverWithoutKeystore(password))
            val corpus = LegacyVaultCorpus()
            corpus.verify()
            assertEquals("Synthetic cold write after recovery", corpus.security.decryptData(File(corpus.root, "new-password.cipher").readText()))
            LocalVaultUpgradeTest().fullLocalBackupRestoreAfterRecovery()
        }
    }

    @Test fun coldRestoredCorpusWithoutProvider() = runBlocking {
        authorize()
        withoutProvider {
            assertTrue(LocalVaultRecovery(context).recoverWithoutKeystore(password))
            LocalVaultUpgradeTest().verifyRestoredPayloadsAfterProcessRestart()
        }
    }
}
