package takagi.ru.monica.databasecompare

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.security.SessionManager

class DatabaseCompareControllerInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val password = "synthetic-input-password"
    private val resultPassword = "synthetic-result-password"
    private val entryId = UUID.fromString("7232f4ed-a286-4207-aada-010101010101")

    private class Fixture(val root: File, val context: Context) {
        val outputs = mutableListOf<Uri>()
        val controllers = mutableListOf<DatabaseCompareController>()
        fun controller() = DatabaseCompareController(context).also { controllers += it }
        fun output(): Uri {
            val authority = "${InstrumentationRegistry.getInstrumentation().context.packageName}.kdbx-delete"
            val uri = Uri.parse("content://$authority/delete-${UUID.randomUUID()}.kdbx")
            context.contentResolver.openOutputStream(uri, "wt")!!.close()
            outputs += uri
            return uri
        }
        fun command(uri: Uri, method: String, extras: Bundle? = null) {
            context.contentResolver.call(uri, method, uri.toString(), extras)
        }
        fun bytes(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        suspend fun awaitBlocked(uri: Uri) = withTimeout(10_000) {
            while (true) {
                val blocked = context.contentResolver.query(uri, arrayOf("read_blocked"), null, null, null)!!.use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (blocked) break
                delay(20)
            }
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val root = File(context.cacheDir, "compare-controller-test-${UUID.randomUUID()}").apply { mkdirs() }
        val f = Fixture(root, context)
        try { block(f) } finally {
            f.controllers.forEach { it.close() }
            f.outputs.forEach { uri ->
                runCatching { f.command(uri, "release-read") }
                runCatching { f.command(uri, "release-write") }
                context.contentResolver.delete(uri, null, null)
            }
            root.deleteRecursively()
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    private fun pair(root: File): Pair<File, File> {
        val original = KeePassDatabase.Ver4x.create("Root", Meta(), Credentials.from(EncryptedValue.fromString(password)))
        // Fast synthetic KDF only in a dedicated test fixture; production parameters are untouched.
        val base = original.copy(header = original.header.copy(kdfParameters = KdfParameters.Aes(10u, original.header.masterSeed)))
        fun save(name: String, secret: String): File {
            val entry = Entry(entryId, fields = EntryFields.of("Title" to EntryValue.Plain("Synthetic account"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(secret))))
            val db = base.modifyContent { copy(group = group.copy(entries = listOf(entry))) }
            return File(root, name).also { file -> file.outputStream().use { db.encode(it, contentParser = KdbxComparisonXml()) } }
        }
        return save("left.kdbx", "before") to save("right.kdbx", "after")
    }

    private fun selection(file: File, supplied: String = password) = DatabaseCompareSelection(
        DatabaseCompareSource.Document(CompareFormat.KDBX, Uri.fromFile(file), file.name), supplied.toCharArray())
    private fun choices(plan: ComparePlan): Map<String, CompareChoice> = plan.rows.associate {
        it.id to if (it.id == "entry:$entryId") CompareChoice.RIGHT else it.defaultChoice ?: CompareChoice.LEFT
    }
    private suspend fun ready(f: Fixture, controller: DatabaseCompareController): Pair<File, File> {
        val files = pair(f.root)
        val plan = controller.compare(selection(files.first), selection(files.second))
        assertTrue(plan.blockers.isEmpty())
        controller.buildResult(choices(plan), resultPassword.toCharArray())
        controller.suspendForPicker()
        assertTrue(controller.awaitingPicker)
        return files
    }

    @Test fun completeEncryptedExportReopensAndPreservesBothInputs() = fixture { f ->
        val files = pair(f.root)
        val before = files.first.readBytes() to files.second.readBytes()
        val controller = f.controller()
        val left = selection(files.first); val right = selection(files.second)
        val plan = controller.compare(left, right)
        assertTrue(left.password.all { it == '\u0000' }); assertTrue(right.password.all { it == '\u0000' })
        val outputPassword = resultPassword.toCharArray()
        controller.buildResult(choices(plan), outputPassword)
        assertTrue(outputPassword.all { it == '\u0000' })
        controller.suspendForPicker()
        val destination = f.output()
        controller.exportDocument(destination)
        val reopened = f.bytes(destination).inputStream().use {
            KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(resultPassword)), contentParser = KdbxComparisonXml())
        }
        assertEquals("after", reopened.content.group.entries.single().fields.password!!.content)
        assertTrue(reopened.content.group.entries.single().history.any { it.fields.password?.content == "before" })
        assertArrayEquals(before.first, files.first.readBytes()); assertArrayEquals(before.second, files.second.readBytes())
        assertFalse(controller.awaitingPicker)
        assertTrue(runCatching { controller.buildResult(choices(plan), resultPassword.toCharArray()) }.isFailure)
    }

    @Test fun wrongPasswordAndStaleSourceNeverPublishResults() = fixture { f ->
        val files = pair(f.root)
        val before = files.first.readBytes() to files.second.readBytes()
        val controller = f.controller()
        assertTrue(runCatching { controller.compare(selection(files.first, "wrong"), selection(files.second)) }.isFailure)
        assertArrayEquals(before.first, files.first.readBytes()); assertArrayEquals(before.second, files.second.readBytes())
        val plan = controller.compare(selection(files.first), selection(files.second))
        files.second.appendBytes(byteArrayOf(7))
        val outputPassword = resultPassword.toCharArray()
        assertTrue(runCatching { controller.buildResult(choices(plan), outputPassword) }.isFailure)
        assertTrue(outputPassword.all { it == '\u0000' })
        assertArrayEquals(before.first, files.first.readBytes())
        assertArrayEquals(before.second + byteArrayOf(7), files.second.readBytes())
    }

    @Test fun lockingAfterReviewRevokesGenerationEvenAfterUnlock() = fixture { f ->
        val files = pair(f.root)
        val controller = f.controller()
        val plan = controller.compare(selection(files.first), selection(files.second))
        val before = files.first.readBytes() to files.second.readBytes()
        SessionManager.markLocked(); SessionManager.markUnlocked()
        val outputPassword = resultPassword.toCharArray()
        assertTrue(runCatching { controller.buildResult(choices(plan), outputPassword) }.isFailure)
        assertTrue(outputPassword.all { it == '\u0000' })
        assertArrayEquals(before.first, files.first.readBytes()); assertArrayEquals(before.second, files.second.readBytes())
    }

    @Test fun nonemptyDestinationIsNeverTruncated() = fixture { f ->
        val controller = f.controller(); ready(f, controller)
        val destination = f.output()
        val original = "unrelated user content".toByteArray()
        context.contentResolver.openOutputStream(destination, "wt")!!.use { it.write(original) }
        assertTrue(runCatching { controller.exportDocument(destination) }.isFailure)
        assertArrayEquals(original, f.bytes(destination))
    }

    @Test fun lockDuringReadbackClearsOnlyOwnEncryptedOutput() = fixture { f ->
        val controller = f.controller(); val files = ready(f, controller)
        val before = files.first.readBytes() to files.second.readBytes()
        val destination = f.output()
        f.command(destination, "block-read", Bundle().apply { putInt("read", 2) })
        val export = kotlinx.coroutines.coroutineScope {
            val pending = async(Dispatchers.IO) { runCatching { controller.exportDocument(destination) } }
            try {
                f.awaitBlocked(destination)
                SessionManager.markLocked()
            } finally { f.command(destination, "release-read") }
            pending.await()
        }
        assertTrue(export.isFailure)
        assertEquals(0, f.bytes(destination).size)
        assertArrayEquals(before.first, files.first.readBytes()); assertArrayEquals(before.second, files.second.readBytes())
    }

    @Test fun independentDestinationWriterSurvivesFailedReadback() = fixture { f ->
        val controller = f.controller(); ready(f, controller)
        val destination = f.output(); val replacement = "another writer's result".toByteArray()
        f.command(destination, "arm-other-writer", Bundle().apply { putByteArray("bytes", replacement) })
        assertTrue(runCatching { controller.exportDocument(destination) }.isFailure)
        assertArrayEquals(replacement, f.bytes(destination))
    }

    @Test fun cancelledSourceReadCannotPublishAndClearsPrivateCapture() = fixture { f ->
        val files = pair(f.root); val controller = f.controller()
        val uri = f.output(); val original = files.first.readBytes()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(original) }
        val root = File(context.cacheDir, "database-compare")
        val existing = root.listFiles().orEmpty().map { it.name }.toSet()
        f.command(uri, "block-read", Bundle().apply { putInt("read", 2) })
        val first = DatabaseCompareSelection(DatabaseCompareSource.Document(CompareFormat.KDBX, uri, "Synthetic source"), password.toCharArray())
        val second = selection(files.second)
        val compared = kotlinx.coroutines.coroutineScope {
            val pending = async(Dispatchers.IO) { runCatching { controller.compare(first, second) } }
            try { f.awaitBlocked(uri); controller.invalidate() }
            finally { f.command(uri, "release-read") }
            pending.await()
        }
        assertTrue(compared.isFailure)
        assertTrue(first.password.all { it == '\u0000' }); assertTrue(second.password.all { it == '\u0000' })
        assertArrayEquals(original, f.bytes(uri))
        assertTrue(root.listFiles().orEmpty().all { it.name in existing })
    }
}
