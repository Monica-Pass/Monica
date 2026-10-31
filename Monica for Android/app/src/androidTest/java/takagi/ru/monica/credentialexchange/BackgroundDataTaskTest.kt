package takagi.ru.monica.credentialexchange

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.transfer.*
import takagi.ru.monica.ui.screens.ImportDataScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.FileOperationHelper

@RunWith(AndroidJUnit4::class)
class BackgroundDataTaskTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(fixture: TransferFixture, name: String) {
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(fixture.context.filesDir, "background-data-validation").apply { mkdirs() }
        File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun importSurvivesBackAndHomeAndReentryWithoutDuplicateWrites() = runBlocking {
        val fixture = TransferFixture()
        val visible = mutableStateOf(true)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        lateinit var activity: Activity
        val input = File(fixture.root, "background-import.zip")
        ZipOutputStream(input.outputStream()).use {
            it.putNextEntry(ZipEntry("passwords/password.json"))
            it.write(JSONObject().put("id", 1).put("title", "${fixture.prefix}-background")
                .put("username", "synthetic").put("password", "test password")
                .put("website", "https://example.invalid").toString().toByteArray())
            it.closeEntry()
        }
        DatabaseExportJobs.dismiss()
        try {
            compose.setContent {
                activity = LocalContext.current as Activity
                MonicaTheme {
                    if (visible.value) ImportDataScreen(initialApplicationSource = false,
                        onNavigateBack = { visible.value = false }, onImport = { Result.success(0) },
                        onImportAegis = { Result.success(0) }, onImportEncryptedAegis = { _, _ -> Result.success(0) },
                        onImportSteamMaFile = { Result.success(0) }, onImportZip = { uri, password, restoreConfig ->
                            calls.incrementAndGet()
                            DatabaseExportJobs.reportImportProgress(TransferProgress(TransferPhase.WRITING, 1, 4))
                            started.complete(Unit)
                            gate.await()
                            fixture.model.importZipBackup(uri, password, ImportDestination.Local)
                        })
                    else Text("Background work test")
                }
            }
            fun text(id: Int) = activity.getString(id)
            compose.runOnIdle { FileOperationHelper.handleImportResult(FileOperationHelper.REQUEST_CODE_IMPORT,
                Activity.RESULT_OK, Intent().setData(Uri.fromFile(input))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.start_import)).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.start_import)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.webdav_restore_action)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.webdav_restore_action)).performClick()
            compose.waitUntil(10_000) { started.isCompleted }
            compose.onNodeWithTag("transfer-progress").assertIsDisplayed()
            screenshot(fixture, "import-progress.png")
            compose.onNodeWithContentDescription(text(R.string.go_back)).performClick()
            compose.waitUntil { !visible.value }
            assertEquals(ExportJobStatus.RUNNING, DatabaseExportJobs.state.value?.status)
            assertFalse(DatabaseExportJobs.startTask(fixture.context, "LOCAL:0", DataTaskKind.IMPORT) { Result.success("duplicate") })
            // A real Home transition while the foreground service holds the import.
            fixture.context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val notifications = fixture.context.getSystemService(NotificationManager::class.java)
            withTimeout(5_000) { while (notifications.activeNotifications.none { it.id == 4318 && it.isOngoing }) delay(50) }
            gate.complete(Unit)
            val result = withTimeout(60_000) { DatabaseExportJobs.state.first { it?.status != ExportJobStatus.RUNNING }!! }
            assertEquals(result.message, ExportJobStatus.SUCCEEDED, result.status)
            assertEquals(1, calls.get())
            assertEquals(1, fixture.importedPasswords(ImportDestination.Local).count { it.title.endsWith("-background") })
            activity.startActivity(Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            compose.runOnUiThread { visible.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.transfer_task_done)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.transfer_task_done)).assertIsDisplayed()
            screenshot(fixture, "import-result.png")
            assertEquals(1, calls.get())
        } finally {
            gate.complete(Unit)
            withTimeout(60_000) { DatabaseExportJobs.state.first { it?.status != ExportJobStatus.RUNNING } }
            compose.runOnUiThread { visible.value = false }
            DatabaseExportJobs.dismiss()
            fixture.close()
        }
    }

    @Test fun serviceFailureDoesNotDeleteImportSourceOrExistingRecords() = runBlocking {
        val fixture = TransferFixture()
        compose.setContent { MonicaTheme { Text("Background service failure test") } }
        fixture.importer.importExchange(fixture.decoded(), ImportDestination.Local)
        val before = fixture.importedPasswords(ImportDestination.Local)
        val file = File(fixture.root, "untouched-import.csv").apply { writeText("user-owned input") }
        DatabaseExportJobs.dismiss()
        try {
            val result = DatabaseExportJobs.await<Int>(fixture.context, "LOCAL:0", DataTaskKind.IMPORT, { "$it" }) {
                Result.failure(java.io.IOException("Synthetic parsing failure"))
            }
            assertTrue(result.isFailure)
            assertEquals("user-owned input", file.readText())
            assertEquals(before, fixture.importedPasswords(ImportDestination.Local))
            assertEquals(ExportJobStatus.FAILED, DatabaseExportJobs.state.value?.status)
        } finally { DatabaseExportJobs.dismiss(); fixture.close() }
    }

    @Test fun webDavUploadContinuesAfterItsObserverIsCancelled() = runBlocking {
        val fixture = TransferFixture()
        compose.setContent { MonicaTheme { Text("Background upload test") } }
        val server = okhttp3.mockwebserver.MockWebServer()
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val archive = File(fixture.root, "synthetic-upload.zip").apply { writeBytes(bytes) }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                received.countDown()
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                return okhttp3.mockwebserver.MockResponse().setResponseCode(201)
            }
        }
        server.start()
        DatabaseExportJobs.dismiss()
        try {
            val observer = launch {
                DatabaseExportJobs.await(fixture.context, "LOCAL:0", DataTaskKind.WEBDAV_BACKUP, { _: Unit -> "Uploaded" }) { progress ->
                    takagi.ru.monica.webdav.WebDavBackupTransport(okhttp3.OkHttpClient())
                        .upload(server.url("/synthetic.zip").toString(), archive, progress)
                    Result.success(Unit)
                }
            }
            assertTrue(withContext(Dispatchers.IO) { received.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            observer.cancelAndJoin()
            assertEquals(ExportJobStatus.RUNNING, DatabaseExportJobs.state.value?.status)
            assertEquals(TransferPhase.UPLOADING, DatabaseExportJobs.state.value?.progress?.phase)
            release.countDown()
            val state = withTimeout(10_000) { DatabaseExportJobs.state.first { it?.status != ExportJobStatus.RUNNING }!! }
            assertEquals(state.message, ExportJobStatus.SUCCEEDED, state.status)
            assertEquals(1, server.requestCount)
            assertArrayEquals(bytes, server.takeRequest().body.readByteArray())
        } finally {
            release.countDown()
            withTimeout(30_000) { DatabaseExportJobs.state.first { it?.status != ExportJobStatus.RUNNING } }
            server.shutdown(); DatabaseExportJobs.dismiss(); fixture.close()
        }
    }
}
