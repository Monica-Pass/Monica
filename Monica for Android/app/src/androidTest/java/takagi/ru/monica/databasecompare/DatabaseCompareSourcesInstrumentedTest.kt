package takagi.ru.monica.databasecompare

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DatabaseCompareSourcesInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(block: suspend (File, DatabaseCompareSources) -> Unit) = runBlocking {
        val root = File(context.cacheDir, "compare-source-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root, DatabaseCompareSources(context)) } finally { root.deleteRecursively() }
    }

    @Test fun snapshotsAreIndependentAndRecheckOriginalBytes() = fixture { root, sources ->
        val file = File(root, "original.kdbx").apply { writeBytes(ByteArray(70_000) { it.toByte() }) }
        val original = file.readBytes()
        val password = "synthetic-password".toCharArray()
        val selection = DatabaseCompareSelection(DatabaseCompareSource.Document(CompareFormat.KDBX, Uri.fromFile(file), "Original"), password)
        val prepared = sources.prepare(selection, File(root, "capture")) {}
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, prepared.input.file.readBytes())
        assertTrue(password.all { it == '\u0000' })
        prepared.verifyUnchanged()
        file.writeBytes(original.copyOf().apply { this[50_000] = 99 })
        var failed = false
        try { prepared.verifyUnchanged() } catch (_: IllegalStateException) { failed = true }
        assertTrue("Must reject a source edit after review", failed)
        assertArrayEquals(original, prepared.input.file.readBytes())
        prepared.clearCredentials()
        assertTrue(prepared.input.password.all { it == '\u0000' })
    }

    @Test fun sourceAndKeyFileCredentialsRemainIndependent() = fixture { root, sources ->
        val file = File(root, "vault.kdbx").apply { writeText("encrypted-synthetic-placeholder") }
        val key = File(root, "vault.keyx").apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
        val prepared = sources.prepare(
            DatabaseCompareSelection(DatabaseCompareSource.Document(CompareFormat.KDBX, Uri.fromFile(file), "Vault"),
                "synthetic".toCharArray(), Uri.fromFile(key)), File(root, "capture")
        ) {}
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), prepared.input.keyFile)
        prepared.verifyUnchanged()
        key.writeBytes(byteArrayOf(4, 3, 2, 9))
        var failed = false
        try { prepared.verifyUnchanged() } catch (_: IllegalStateException) { failed = true }
        assertTrue("Changed key files must invalidate the prepared comparison", failed)
        prepared.clearCredentials()
        assertTrue(checkNotNull(prepared.input.keyFile).all { it == 0.toByte() })
        assertEquals("encrypted-synthetic-placeholder", file.readText())
    }

    @Test fun changedDocumentTreeAliasHasSameIdentity() {
        val direct = Uri.parse("content://example.documents/document/folder%2Fvault.kdbx")
        val tree = Uri.parse("content://example.documents/tree/folder/document/folder%2Fvault.kdbx")
        assertEquals(comparisonUriIdentity(direct), comparisonUriIdentity(tree))
        assertNotEquals(comparisonUriIdentity(direct), comparisonUriIdentity(Uri.parse("content://other.documents/document/folder%2Fvault.kdbx")))
    }

    @Test fun cancelledCapturePreservesInputAndRemovesOwnedOutput() = fixture { root, sources ->
        val file = File(root, "source.kdbx").apply { writeBytes(ByteArray(100_000) { 7 }) }
        val before = file.readBytes()
        val password = "synthetic".toCharArray()
        var checks = 0
        var failed = false
        try {
            sources.prepare(DatabaseCompareSelection(DatabaseCompareSource.Document(CompareFormat.KDBX, Uri.fromFile(file), "Source"), password),
                File(root, "capture")) { check(++checks < 7) { "cancelled" } }
        } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(root, "capture/input.kdbx").exists())
        assertTrue(password.all { it == '\u0000' })
    }
}
