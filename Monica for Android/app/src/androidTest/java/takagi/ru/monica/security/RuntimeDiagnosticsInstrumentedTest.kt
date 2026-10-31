package takagi.ru.monica.security

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsInstrumentedTest {
    private class MessageTrap : Exception() {
        override val message: String get() = error("Exception messages must never be read")
    }

    @Test fun exceptionDetailsKeepTypesAndCodePositionsWithoutReadingMessagesOrFilenames() {
        val root = MessageTrap().apply {
            initCause(java.security.ProviderException("synthetic-private-password", javax.crypto.AEADBadTagException("synthetic-key-bytes")))
            stackTrace = arrayOf(StackTraceElement("takagi.ru.monica.security.Fixture", "open", "/synthetic/private/database.kdbx", 123))
            addSuppressed(IllegalArgumentException("synthetic-private-pin"))
        }
        val rendered = SafeExceptionDiagnostic.lines(root).joinToString("\n")
        assertTrue(rendered.contains("ProviderException"))
        assertTrue(rendered.contains("AEADBadTagException"))
        assertTrue(rendered.contains("Fixture.open:123"))
        assertTrue(rendered.contains("suppressed_type=java.lang.IllegalArgumentException"))
        assertFalse(rendered.contains("synthetic-"))
        assertFalse(rendered.contains("database.kdbx"))
    }

    @Test fun cyclicAndVeryDeepCausesRemainBounded() {
        val a = Exception("synthetic-private-a")
        val b = Exception("synthetic-private-b")
        a.initCause(b); b.initCause(a)
        assertTrue(SafeExceptionDiagnostic.lines(a).last() == "causes_truncated=true")
        var deep: Throwable = Exception("synthetic-private-bottom")
        repeat(30) { deep = Exception("synthetic-private-$it", deep) }
        val lines = SafeExceptionDiagnostic.lines(deep)
        assertTrue(lines.size <= 89)
        assertEquals(8, lines.count { it.contains(" type=") })
        assertFalse(lines.joinToString().contains("synthetic-"))
    }

    @Test fun crashDiagnosticFailureStillDelegatesTheOriginalThrowableExactlyOnce() {
        val error = MessageTrap()
        val thread = Thread.currentThread()
        var calls = 0
        val handler = DiagnosticCrashHandler(Thread.UncaughtExceptionHandler { actualThread, actualError ->
            calls++
            assertSame(thread, actualThread)
            assertSame(error, actualError)
        }) { _, _ -> throw IllegalStateException("synthetic diagnostic failure") }
        handler.uncaughtException(thread, error)
        assertEquals(1, calls)
    }

    @Test fun structuredStagesAndFailuresReachRealLogcatWithoutStorePathsOrSecretReasons() {
        val trace = RuntimeDiagnostics.beginStartup()
        trace.run {
            RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.KEYSET_OPEN)
            RuntimeDiagnostics.startupFailure(SecureStorageUnavailableException(
                "/synthetic/private/store", "synthetic-private-reason", MessageTrap(), SecureStoragePhase.KEYSET_OPEN), 2)
        }
        RuntimeDiagnostics.stage(RuntimeDiagnostics.Stage.VALUE_READ)
        trace.outcome(RuntimeDiagnostics.Outcome.BLOCKED)
        val logs = readLogcat()
        val matching = logs.lines().filter { it.contains("request=${trace.id} ") }.joinToString("\n")
        assertTrue(matching.contains("event=secure_begin"))
        assertTrue(matching.contains("stage=KEYSET_OPEN"))
        assertTrue(matching.contains("attempt=2 store=OTHER code=UNKNOWN phase=KEYSET_OPEN"))
        assertTrue(matching.contains("outcome=BLOCKED"))
        assertTrue(matching.contains("elapsed_ms="))
        assertFalse(matching.contains("stage=VALUE_READ"))
        assertFalse(matching.contains("synthetic-private"))
        assertFalse(matching.contains("/synthetic"))
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    fun keyMintMacFailureKeepsItsPublicNumericCodeWithoutTheProviderMessage() {
        val alias = "diag-code-fixture-${java.util.UUID.randomUUID()}"
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
            val key = generator.generateKey()
            val encrypt = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
            val corrupt = encrypt.doFinal(byteArrayOf(1, 2, 3))
            corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
            val decrypt = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, encrypt.iv))
            }
            val error = runCatching { decrypt.doFinal(corrupt) }.exceptionOrNull()
            assertTrue(error is javax.crypto.AEADBadTagException)
            val text = SafeExceptionDiagnostic.lines(checkNotNull(error)).joinToString("\n")
            assertTrue(text.contains("type=android.security.KeyStoreException"))
            assertTrue(text.contains("keystore_code=10"))
            assertFalse(text.contains(alias))
        } finally {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    companion object {
        fun readLogcat(): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("logcat -d -t 500 --pid=${Process.myPid()} -s MonicaDiag:*")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    }
}
