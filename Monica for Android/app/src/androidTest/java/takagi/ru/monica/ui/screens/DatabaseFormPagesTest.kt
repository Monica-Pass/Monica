package takagi.ru.monica.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.cryptography.EncryptedValue
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.security.*
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

@RunWith(AndroidJUnit4::class)
class DatabaseFormPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun fill(id: Int, value: String) = compose.onNode(hasSetTextAction() and hasText(label(id)))
        .performScrollTo().performTextReplacement(value)
    private fun submit() = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("keepass_create_form_actions")))

    @Test fun createPageKeepsOriginalOptionsAndDoesNotSubmitInvalidPassword() {
        var submissions = 0
        compose.setContent { MaterialTheme {
            CreateKeePassDatabaseScreen({}, { _, done -> done(false) }) { _, _, _, _, _, _, _ -> submissions++ }
        } }
        fill(R.string.database_name, "Synthetic form")
        fill(R.string.database_password, "SyntheticPassword331!")
        fill(R.string.confirm_password, "DifferentSyntheticPassword331!")
        submit().assertIsNotEnabled()
        fill(R.string.confirm_password, "SyntheticPassword331!")
        submit().assertIsEnabled()
        compose.onNodeWithText(label(R.string.local_keepass_advanced_crypto_options)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.local_keepass_kdf_algorithm)).performScrollTo().assertIsDisplayed()
        fill(R.string.local_keepass_transform_rounds, "0")
        submit().assertIsNotEnabled()
        assertEquals(0, submissions)
    }

    @Test fun backAndLockRemoveDraftWithoutRetainingPasswords() {
        Fixture().use { f ->
            SessionManager.markUnlocked()
            compose.setContent { MaterialTheme { LocalKeePassScreen(f.model, {}, {}) } }
            compose.onNodeWithText(label(R.string.mdbx_ui_local_databases)).performClick()
            compose.onNodeWithTag("keepass_create_database").performClick()
            compose.onNodeWithTag("keepass_create_page").assertIsDisplayed()
            fill(R.string.database_password, "TransientSyntheticPassword")
            compose.onNodeWithContentDescription(label(R.string.go_back)).performClick()
            compose.onNodeWithTag("keepass_create_page").assertDoesNotExist()
            compose.onNodeWithTag("keepass_create_database").performClick()
            compose.onNode(hasSetTextAction() and hasText(label(R.string.database_password))).assertTextContains("")
            fill(R.string.database_password, "TransientSyntheticPassword")
            compose.runOnIdle { SessionManager.markLocked() }
            compose.onNodeWithTag("keepass_create_page").assertDoesNotExist()
            compose.onAllNodesWithText("TransientSyntheticPassword", substring = true).assertCountEquals(0)
        }
    }

    @Test fun wrongExternalPasswordStaysOnFormThenCorrectPasswordOpensOriginalFile() {
        Fixture().use { f ->
            val row = f.create("form_retry", "Native description")
            val file = File(context.filesDir, row.filePath)
            val original = file.readBytes()
            var busy by mutableStateOf(false)
            var error by mutableStateOf<String?>(null)
            var complete by mutableStateOf(false)
            compose.setContent { MaterialTheme {
                if (!complete) OpenKeePassDatabaseScreen(Uri.fromFile(file), {}, busy, error) { name, secret, key, copy ->
                    busy = true
                    f.model.importExternalDatabase(name, Uri.fromFile(file), secret, key, keepKeyFileCopy = copy) { ok ->
                        busy = false
                        if (ok) complete = true else error = (f.model.operationState.value as LocalKeePassViewModel.OperationState.Error).message
                    }
                }
            } }
            fill(R.string.database_password, "WrongSyntheticPassword")
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("keepass_open_form_actions"))).performClick()
            compose.waitUntil(20_000) { error != null }
            compose.onNodeWithTag("keepass_open_page").assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText(label(R.string.database_password))).assertTextContains("WrongSyntheticPassword")
            assertArrayEquals(original, file.readBytes())
            fill(R.string.database_password, PASSWORD)
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("keepass_open_form_actions"))).performClick()
            compose.waitUntil(20_000) { complete }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun distinctNamedDatabasesKeepOriginalBytesAndNativeMetadata() {
        Fixture().use { f ->
            val first = f.create("form_first_${System.nanoTime()}", "Room display description")
            val bytes = File(context.filesDir, first.filePath).readBytes()
            val second = f.create("form_second_${System.nanoTime()}", "Room display description")
            assertNotEquals(first.filePath, second.filePath)
            assertArrayEquals(bytes, File(context.filesDir, first.filePath).readBytes())
            listOf(first, second).forEach { row ->
                val decoded = File(context.filesDir, row.filePath).inputStream().use {
                    KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(PASSWORD)))
                }
                assertEquals(row.name, decoded.content.meta.name)
                assertEquals("", decoded.content.meta.description)
            }
        }
    }

    @Test fun keyFileOnlyCreationRequiresOriginalKeyAndKeepsNativeDefaults() {
        Fixture().use { f ->
            val key = File(context.cacheDir, "form_key_${System.nanoTime()}.key").apply { writeBytes(ByteArray(32) { (it + 1).toByte() }) }
            try {
                var accepted = false
                runBlocking { f.model.createDatabase("form_key", "", KeePassStorageLocation.INTERNAL,
                    keyFileUri = Uri.fromFile(key), creationOptions = OPTIONS, description = "Key-only native description") { accepted = it }.join() }
                assertTrue(accepted)
                val row = runBlocking { f.dao.getAllDatabases().first().single() }
                f.files += File(context.filesDir, row.filePath)
                val result = f.files.last().inputStream().use { KeePassDatabase.decode(it, Credentials.from(keyData = key.readBytes())) }
                assertEquals("", result.content.meta.description)
                assertTrue(runCatching { f.files.last().inputStream().use { KeePassDatabase.decode(it, Credentials.from(keyData = ByteArray(32))) } }.isFailure)
            } finally { key.delete() }
        }
    }

    @Test fun keepassLongPressReordersWithoutOpeningAndSurvivesRoomReopen() = dragDatabaseGrid(false)
    @Test fun mdbxLongPressReordersWithoutOpeningAndSurvivesRoomReopen() = dragDatabaseGrid(true)

    private fun dragDatabaseGrid(mdbx: Boolean) {
        val dbName = "form_order_${System.nanoTime()}"
        var room = Room.databaseBuilder(context, PasswordDatabase::class.java, dbName).build()
        var opened = 0
        var show by mutableStateOf(true)
        val names = listOf("Alpha", "Beta", "Gamma", "Delta")
        try {
            runBlocking {
                names.forEachIndexed { index, name ->
                    if (mdbx) room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(id = index + 1L, name = name, filePath = "synthetic-$name.mdbx", sortOrder = index))
                    else room.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = index + 1L, name = name, filePath = "synthetic-$name.kdbx", sortOrder = index))
                }
            }
            compose.setContent { MaterialTheme {
                if (show) {
                    if (mdbx) {
                        val rows by room.localMdbxDatabaseDao().getAllDatabases().collectAsState(emptyList())
                        MdbxSourceManagementPage(MdbxManagerSource.LOCAL, rows, emptyMap(), emptyMap(), {}, {}, { opened++ },
                            onReorder = { room.localMdbxDatabaseDao().reorderDisplay(it) })
                    } else {
                        val rows by room.localKeePassDatabaseDao().getAllDatabases().collectAsState(emptyList())
                        KeePassSourceManagementPage(KeePassManagementSource.LOCAL, rows, emptyMap(), {}, {}, { opened++ }, {},
                            onReorder = { room.localKeePassDatabaseDao().reorderDisplay(it) })
                    }
                }
            } }
            val prefix = if (mdbx) "mdbx" else "keepass"
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("${prefix}_database_2").fetchSemanticsNodes().isNotEmpty() }
            val first = compose.onNodeWithTag("${prefix}_database_1").fetchSemanticsNode().boundsInRoot.center
            val second = compose.onNodeWithTag("${prefix}_database_2").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput {
                down(first); advanceEventTime(700); moveTo(first + Offset(1f, 0f)); moveTo(second, 400); advanceEventTime(100); up()
            }
            compose.waitUntil(5_000) { runBlocking {
                if (mdbx) room.localMdbxDatabaseDao().getAllDatabasesSnapshot().first().id == 2L
                else room.localKeePassDatabaseDao().getAllDatabases().first().first().id == 2L
            } }
            assertEquals(0, opened)
            compose.runOnIdle { show = false }
            compose.waitForIdle()
            room.close()
            room = Room.databaseBuilder(context, PasswordDatabase::class.java, dbName).build()
            val persisted = runBlocking { if (mdbx) room.localMdbxDatabaseDao().getAllDatabasesSnapshot().map { it.id }
                else room.localKeePassDatabaseDao().getAllDatabases().first().map { it.id } }
            assertEquals(listOf(2L,1L,3L,4L), persisted)
        } finally {
            compose.runOnIdle { show = false }; compose.waitForIdle(); room.close(); context.deleteDatabase(dbName)
        }
    }

    private inner class Fixture : AutoCloseable {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val dao = room.localKeePassDatabaseDao()
        val model: LocalKeePassViewModel
        val files = mutableListOf<File>()
        init {
            runBlocking { check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready) }
            model = LocalKeePassViewModel(context.applicationContext as Application, dao, SecurityManager(context))
        }
        fun create(name: String, description: String): LocalKeePassDatabase {
            var accepted = false
            runBlocking { model.createDatabase(name, PASSWORD, KeePassStorageLocation.INTERNAL,
                creationOptions = OPTIONS, description = description) { accepted = it }.join() }
            check(accepted) { "Synthetic creation failed: ${model.operationState.value}" }
            return runBlocking { dao.getAllDatabases().first().maxBy { it.id } }.also { files += File(context.filesDir, it.filePath) }
        }
        override fun close() {
            runBlocking { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            room.close()
            files.forEach { check(it.name.startsWith("form_")); it.delete() }
        }
    }
    companion object {
        const val PASSWORD = "SyntheticForm331Password!"
        val OPTIONS = KeePassDatabaseCreationOptions(kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF, transformRounds = 100)
    }
}
