package takagi.ru.monica.bitwarden.export

import android.net.Uri
import androidx.activity.ComponentActivity
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import takagi.ru.monica.ui.theme.MonicaTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.transfer.TransferProgressReporter
import takagi.ru.monica.ui.screens.BitwardenJsonImportDialog
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BitwardenJsonTransferInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realRoomImportStoresFiveTypesFoldersHistoryUriRulesAndProtectedPasskey() = withFixture { f -> runBlocking {
        val preview = f.transfer.previewImport(f.uri, null)
        assertEquals(5, preview.items); assertEquals(2, preview.folders); assertTrue(preview.issues.isEmpty())
        assertEquals(5, f.transfer.importLocal(f.uri, null, ImportDestination.Local).imported)
        val entries = f.passwords()
        assertEquals(2, entries.size); assertEquals(3, f.secureItems().size)
        val note = f.secureItems().single { it.itemType == takagi.ru.monica.data.ItemType.NOTE }
        val decodedNote = takagi.ru.monica.notes.domain.NoteContentCodec.decodeFromItem(note)
        assertEquals(note.notes, decodedNote.content)
        assertEquals(2, decodedNote.customFields.size)
        assertEquals(takagi.ru.monica.data.model.SecureCustomFieldType.HIDDEN, decodedNote.customFields[1].type)
        val login = entries.single { it.title.endsWith("1") }
        assertEquals("2.this-is-a-plain-password", f.security.decryptData(login.password))
        assertEquals(1793754123456L, login.createdAt.time)
        val history = f.db.passwordHistoryDao().getHistoryByEntryIdSync(login.id)
        assertEquals("older synthetic", f.security.decryptData(history.single().password))
        val rules = f.db.customFieldDao().getFieldsByEntryIdSync(login.id).single { it.title == "monica.autofill.url_rules" }
        assertTrue(rules.value.contains("192.0.2.1:8080/login")); assertTrue(rules.value.contains("EXACT"))
        val key = f.db.passkeyDao().getAllPasskeysSync().single { it.rpId == f.rpId }
        assertEquals(login.id, key.boundPasswordId)
        assertTrue(PasskeyPrivateKeyStore.isProtectedReference(key.privateKeyAlias))
        assertTrue(PasskeyPrivateKeyStore.hasUsablePrivateKey(f.context, key.privateKeyAlias))
        assertEquals(2, f.db.categoryDao().getAllCategories().first().count { it.name.startsWith(f.prefix) })
    } }

    @Test fun actualLocalExportWritesEncryptedJsonAndRoundTripsAllFiveTypes() = withFixture { f -> runBlocking {
        val original = BitwardenJsonCodec.decode(f.file.readBytes())
        assertEquals(5, f.transfer.importLocal(f.uri, null, ImportDestination.Local).imported)
        val output = File(f.context.cacheDir, "${f.prefix}-encrypted-export.json")
        try {
            val permit = BitwardenJsonExportPermit.grant(f.context, ImportDestination.Local, SessionManager.lockGeneration.value)
            val result = f.transfer.export(ImportDestination.Local, Uri.fromFile(output), "Synthetic export file password 325!", permit)
            assertTrue(result.items >= 5); assertTrue(result.folders >= 2)
            val bytes = output.readBytes()
            assertTrue(BitwardenJsonCodec.requiresPassword(bytes))
            assertFalse(bytes.decodeToString().contains("2.this-is-a-plain-password"))
            val roundTrip = BitwardenJsonCodec.decode(bytes, "Synthetic export file password 325!")
            val items = roundTrip.items.map { it.jsonObject }.filter { it.string("name")?.startsWith(f.prefix) == true }
            assertEquals(setOf(1, 2, 3, 4, 5), items.map { it.integer("type") }.toSet())
            for (type in 1..5) {
                val expected = original.items.map { it.jsonObject }.single { it.integer("type") == type }
                val actual = items.single { it.integer("type") == type }
                assertEquals(expected.string("name"), actual.string("name"))
                assertEquals(expected.string("notes"), actual.string("notes"))
                assertEquals(expected["fields"], actual["fields"])
                assertEquals(expected["passwordHistory"], actual["passwordHistory"])
                val section = BitwardenJsonCodec.sections.getValue(type)
                if (type == 1) {
                    val expectedLogin = expected.getValue(section).jsonObject
                    val actualLogin = actual.getValue(section).jsonObject
                    assertEquals(JsonObject(expectedLogin - "fido2Credentials"), JsonObject(actualLogin - "fido2Credentials"))
                    val expectedKey = expectedLogin.array("fido2Credentials").single().jsonObject
                    val actualKey = actualLogin.array("fido2Credentials").single().jsonObject
                    assertEquals(takagi.ru.monica.passkey.PasskeyCredentialIdCodec.normalize(expectedKey.string("credentialId")),
                        takagi.ru.monica.passkey.PasskeyCredentialIdCodec.normalize(actualKey.string("credentialId")))
                    assertEquals(JsonObject(expectedKey - "credentialId"), JsonObject(actualKey - "credentialId"))
                } else assertEquals(expected[section], actual[section])
                val folder = roundTrip.folders.map { it.jsonObject }.single { it.string("id") == actual.string("folderId") }
                assertTrue(folder.string("name")!!.startsWith(f.prefix))
            }
            assertEquals(2, roundTrip.folders.count { it.jsonObject.string("name")?.startsWith(f.prefix) == true })
            bytes.fill(0)
        } finally { output.delete() }
    } }

    @Test fun lockWhileActuallyWritingExportTruncatesThePartialDocument() = withFixture { f -> runBlocking {
        val original = BitwardenJsonCodec.decode(f.file.readBytes())
        f.file.writeText(original.copy(items = JsonArray(original.items.mapIndexed { index, item ->
            if (index == 0) JsonObject(item.jsonObject + ("notes" to JsonPrimitive("Synthetic large note ".repeat(8000)))) else item
        })).json().toString())
        assertEquals(5, f.transfer.importLocal(f.uri, null, ImportDestination.Local).imported)
        val output = File(f.context.cacheDir, "${f.prefix}-revoked-export.json")
        var partialWrite = false
        try {
            val permit = BitwardenJsonExportPermit.grant(f.context, ImportDestination.Local, SessionManager.lockGeneration.value)
            val failure = runCatching { f.transfer.export(ImportDestination.Local, Uri.fromFile(output), null, permit,
                TransferProgressReporter { progress ->
                    if (progress.phase == takagi.ru.monica.transfer.TransferPhase.SAVING && !partialWrite) {
                        assertTrue(output.length() > 0)
                        assertTrue(progress.completed < requireNotNull(progress.total))
                        partialWrite = true
                        SessionManager.markLocked()
                    }
                }) }.exceptionOrNull()
            assertTrue("The test must reach a real partial file write", partialWrite)
            assertNotNull(failure)
            assertTrue(output.exists()); assertEquals(0L, output.length())
            f.unlockLocal()
            assertFalse(permit.consume(f.context, ImportDestination.Local))
            assertEquals(2, f.passwords().size)
        } finally { output.delete() }
    } }

    @Test fun changedFileAfterSuccessfulPreviewIsRecheckedBeforeAnyWrite() = withFixture { f -> runBlocking {
        assertTrue(f.transfer.previewImport(f.uri, null).issues.isEmpty())
        val original = BitwardenJsonCodec.decode(f.file.readBytes())
        val altered = original.copy(items = JsonArray(original.items.mapIndexed { index, item ->
            if (index == 4) JsonObject(item.jsonObject + ("futureSecret" to JsonPrimitive("SYNTHETIC_NEVER_SHOW"))) else item
        }))
        f.file.writeText(altered.json().toString())
        val failure = runCatching { f.transfer.importLocal(f.uri, null, ImportDestination.Local) }.exceptionOrNull()
        assertTrue(failure is BitwardenJsonRejected)
        assertTrue(f.passwords().isEmpty()); assertTrue(f.secureItems().isEmpty())
        assertFalse(failure.toString().contains("SYNTHETIC_NEVER_SHOW"))
    } }

    @Test fun lockDuringWriteRollsBackTheWholeImportAndRevokesPreview() = withFixture { f -> runBlocking {
        val result = runCatching { f.transfer.importLocal(f.uri, null, ImportDestination.Local, TransferProgressReporter {
            if (it.completed == 1L) SessionManager.markLocked()
        }) }
        assertTrue(result.isFailure)
        f.unlockLocal()
        assertTrue(f.passwords().isEmpty()); assertTrue(f.secureItems().isEmpty())
        assertFalse(f.db.passkeyDao().getAllPasskeysSync().any { it.rpId == f.rpId })
        assertFalse(f.db.categoryDao().getAllCategories().first().any { it.name.startsWith(f.prefix) })
    } }

    @Test fun actualReviewDialogImportsOnlyAfterExplicitConfirm() = withFixture { f ->
        var imported by mutableStateOf(0)
        var visible by mutableStateOf(true)
        compose.setContent { JsonTheme { if (visible) BitwardenJsonImportDialog(f.uri,
            onPreview = { uri, password -> runCatching { f.transfer.previewImport(uri, password) } },
            onImport = { uri, password -> runCatching { f.transfer.importLocal(uri, password, ImportDestination.Local).imported } },
            onDismiss = { visible = false }, onImported = { imported = it; visible = false }) } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("bw_json_confirm_import").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        assertTrue(runBlocking { f.passwords().isEmpty() })
        screenshotIfRequested("review-ready")
        compose.onNodeWithTag("bw_json_confirm_import").performClick()
        compose.waitUntil(15000) { imported == 5 }
        assertEquals(2, runBlocking { f.passwords().size })
    }

    @Test fun actualReviewIssueTitlesDisappearWhenLocked() = withFixture { f ->
        val original = BitwardenJsonCodec.decode(f.file.readBytes())
        f.file.writeText(original.copy(items = JsonArray(original.items.mapIndexed { index, item ->
            if (index == 0) JsonObject(item.jsonObject + ("reprompt" to JsonPrimitive(1))) else item
        })).json().toString())
        var visible by mutableStateOf(true)
        compose.setContent { JsonTheme { if (visible) BitwardenJsonImportDialog(f.uri,
            onPreview = { uri, password -> runCatching { f.transfer.previewImport(uri, password) } },
            onImport = { _, _ -> error("Blocked import must not be called") },
            onDismiss = { visible = false }, onImported = { error("Blocked import must not complete") }) } }
        compose.waitUntil(15000) { compose.onAllNodesWithText(f.prefix + " type 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bw_json_confirm_import").assertIsNotEnabled()
        compose.onNodeWithText(f.prefix + " type 1").performScrollTo().assertIsDisplayed()
        screenshotIfRequested("review-issues")
        compose.runOnIdle { SessionManager.markLocked() }
        compose.waitUntil(10000) { !visible }
        compose.onNodeWithText(f.prefix + " type 1").assertDoesNotExist()
    }

    @Test fun busyPreviewCanBeCancelledAndLateResultCannotRestorePrivateTitles() = withFixture { f ->
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        var visible by mutableStateOf(true)
        compose.setContent { JsonTheme { if (visible) BitwardenJsonImportDialog(f.uri,
            onPreview = { _, _ ->
                started.set(true)
                try { awaitCancellation() } catch (_: CancellationException) {
                    cancelled.set(true)
                    Result.success(BitwardenJsonPreview(1, 0, false, listOf(BitwardenJsonIssue(0,
                        "LATE_SYNTHETIC_TITLE", "items[0]", BitwardenJsonIssue.Reason.INVALID))))
                }
            },
            onImport = { _, _ -> error("Cancelled preview must never import") },
            onDismiss = { visible = false }, onImported = { error("Cancelled preview must never finish") }) } }
        compose.waitUntil(10000) { started.get() }
        compose.onNodeWithTag("bw_json_cancel_import").assertIsEnabled().performClick()
        compose.waitUntil(10000) { cancelled.get() && !visible }
        compose.onNodeWithText("LATE_SYNTHETIC_TITLE").assertDoesNotExist()
        assertTrue(runBlocking { f.passwords().isEmpty() })
    }

    @Test fun leavingForegroundCancelsActualImportAndRollsBackWrittenRows() = withFixture { f ->
        val firstWrite = CountDownLatch(1)
        val resumeWrite = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var visible by mutableStateOf(true)
        var imported by mutableStateOf(0)
        compose.setContent { JsonTheme { if (visible) BitwardenJsonImportDialog(f.uri,
            onPreview = { uri, password -> runCatching { f.transfer.previewImport(uri, password) } },
            onImport = { uri, password ->
                try { runCatching { f.transfer.importLocal(uri, password, ImportDestination.Local, TransferProgressReporter {
                    if (it.completed == 1L) { firstWrite.countDown(); check(resumeWrite.await(15, TimeUnit.SECONDS)) }
                }).imported } } finally { finished.set(true) }
            }, onDismiss = { visible = false }, onImported = { imported = it }) } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("bw_json_confirm_import").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag("bw_json_confirm_import").performClick()
        assertTrue(firstWrite.await(15, TimeUnit.SECONDS))
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            resumeWrite.countDown()
            compose.waitUntil(15000) { finished.get() }
        } finally {
            resumeWrite.countDown()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        assertFalse(visible); assertEquals(0, imported)
        assertTrue(runBlocking { f.passwords().isEmpty() && f.secureItems().isEmpty() })
        assertFalse(runBlocking { f.db.categoryDao().getAllCategories().first().any { it.name.startsWith(f.prefix) } })
        assertFalse(runBlocking { f.db.passkeyDao().getAllPasskeysSync().any { it.rpId == f.rpId } })
    }

    @Test fun actualExportIdentityCancelAndBackgroundClearPasswordBeforeNextAttempt() = withFixture { f ->
        var exports = 0
        takagi.ru.monica.transfer.DatabaseExportJobs.dismiss()
        compose.setContent { JsonTheme { takagi.ru.monica.ui.screens.ExportDataScreen(
            onNavigateBack = {}, onExportZip = { _, _, _, _, _ -> error("Unexpected ZIP export") },
            onExportKdbx = { _, _, _, _ -> error("Unexpected KDBX export") },
            onLoadSteamMaFileCandidates = { Result.success(emptyList()) },
            onExportSteamMaFile = { _, _, _, _ -> error("Unexpected Steam export") },
            onExportBitwardenJson = { _, _, _, _, _ -> exports++; Result.success("synthetic") }) } }
        compose.onNodeWithText(f.context.getString(takagi.ru.monica.R.string.transfer_zip_title)).performClick()
        compose.onNodeWithText(f.context.getString(takagi.ru.monica.R.string.bw_json_encrypted_title)).performClick()
        screenshotIfRequested("export-options")
        compose.onNodeWithTag("database_export_start").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        screenshotIfRequested("export-identity")
        compose.onNode(hasSetTextAction()).performTextInput("SyntheticNotTheMasterPassword")
        compose.onNodeWithText(f.context.getString(takagi.ru.monica.R.string.cancel)).performClick()
        compose.onNodeWithTag("database_export_start").performClick()
        compose.onNode(hasSetTextAction()).assert(hasText("", substring = false))
        compose.onNode(hasSetTextAction()).performTextInput("SecondSyntheticAttempt")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("database_export_start").performClick()
        compose.onNode(hasSetTextAction()).assert(hasText("", substring = false))
        assertEquals(0, exports)
        compose.onNodeWithText(f.context.getString(takagi.ru.monica.R.string.cancel)).performClick()
    }

    @Test fun exportPermitIsOneShotAndCannotSurviveLockAndUnlock() = withFixture { f -> runBlocking {
        val once = BitwardenJsonExportPermit.grant(f.context, ImportDestination.Local, SessionManager.lockGeneration.value)
        assertTrue(once.consume(f.context, ImportDestination.Local)); assertFalse(once.consume(f.context, ImportDestination.Local))
        val old = BitwardenJsonExportPermit.grant(f.context, ImportDestination.Local, SessionManager.lockGeneration.value)
        SessionManager.markLocked(); f.unlockLocal()
        assertFalse(old.consume(f.context, ImportDestination.Local))
    } }

    private val testLargeFont get() = InstrumentationRegistry.getArguments().getString("jsonLargeFont") == "true"
    private val testDark get() = InstrumentationRegistry.getArguments().getString("jsonDark") == "true"

    @Composable private fun JsonTheme(content: @Composable () -> Unit) {
        val actualDensity = LocalDensity.current
        val configuration = Configuration(LocalConfiguration.current).apply {
            if (testLargeFont) fontScale = 1.5f
        }
        CompositionLocalProvider(LocalConfiguration provides configuration,
            LocalDensity provides Density(actualDensity.density, configuration.fontScale)) {
            MonicaTheme(darkTheme = testDark, content = content)
        }
    }

    /** Only synthetic test data; native screenshots are opt-in and stay outside version control. */
    private fun screenshotIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("jsonScreenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(instrumentation.targetContext.filesDir, "bitwarden-json-captures")
            check(folder.isDirectory || folder.mkdirs())
            val suffix = "${if (testDark) "dark" else "light"}-${if (testLargeFont) "large" else "normal"}"
            File(folder, "$name-$suffix.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }; f.close() }
    }
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val transfer = BitwardenJsonTransfer(context)
        val prefix = "JSON-${UUID.randomUUID()}"
        val rpId = "${UUID.randomUUID()}.example.invalid"
        val file = File(context.cacheDir, "$prefix.json")
        val uri: Uri = Uri.fromFile(file)
        private val previousUnlocked = SessionManager.isUnlocked.value
        private val testMasterPassword = InstrumentationRegistry.getArguments().getString("testMasterPassword")
            ?: "Synthetic Bitwarden settings fixture 325!"
        init {
            if (!security.isMasterPasswordSet()) check(security.setMasterPassword(testMasterPassword))
            check(security.unlockVaultWithPassword(testMasterPassword)) {
                "Existing test vault password differs from testMasterPassword; refusing to reset it"
            }
            unlockLocal()
            val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("bitwarden-json/five-types.json").use { it.readBytes() }
            val document = BitwardenJsonCodec.decode(fixture)
            val folders = JsonArray(document.folders.map { JsonObject(it.jsonObject + ("name" to JsonPrimitive(prefix + " " + it.jsonObject.string("name")))) })
            val items = JsonArray(document.items.mapIndexed { index, item ->
                val data = item.jsonObject.toMutableMap(); data["name"] = JsonPrimitive("$prefix type ${index + 1}")
                if (index == 0) {
                    val login = data.getValue("login").jsonObject
                    data["login"] = JsonObject(login + ("fido2Credentials" to JsonArray(login.array("fido2Credentials").map { key -> JsonObject(key.jsonObject + ("rpId" to JsonPrimitive(rpId))) })))
                }; JsonObject(data)
            })
            file.writeText(BitwardenJsonDocument(folders, items).json().toString())
        }
        fun unlockLocal() {
            if (!security.isVaultRuntimeUnlocked()) check(security.unlockVaultWithPassword(testMasterPassword)) {
                "Cannot unlock the existing test vault with testMasterPassword; refusing to reset it"
            }
            SessionManager.markUnlocked()
        }
        suspend fun passwords() = (db.passwordEntryDao().getAllPasswordEntriesSync() + db.passwordEntryDao().getDeletedEntriesSync()).filter { it.title.startsWith(prefix) }.distinctBy { it.id }
        suspend fun secureItems() = (db.secureItemDao().getAllItemsIncludingArchived().first() + db.secureItemDao().getDeletedItemsSync()).filter { it.title.startsWith(prefix) }.distinctBy { it.id }
        fun close() = runBlocking {
            unlockLocal()
            db.passkeyDao().getAllPasskeysSync().filter { it.rpId == rpId }.forEach {
                db.passkeyDao().delete(it); PasskeyPrivateKeyStore.removeIfProtectedReference(context, it.privateKeyAlias)
            }
            passwords().forEach { db.passwordEntryDao().delete(it) }
            secureItems().forEach { db.secureItemDao().deleteItem(it) }
            db.categoryDao().getAllCategories().first().filter { it.name.startsWith(prefix) }.forEach { db.categoryDao().delete(it) }
            file.delete()
            if (!previousUnlocked) SessionManager.markLocked()
        }
    }
}
