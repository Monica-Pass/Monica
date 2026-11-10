package takagi.ru.monica.keepass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Disk-backed local comparison using the same real editor and completion boundary. */
class PasswordLocalSaveTimingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newLocalEditorCancelThenSaveAndDiskReopenPreservesSentinel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(android.os.Process.myUid() / 100000 == 11)
        check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val nonce = UUID.randomUUID().toString()
        val name = "toolchain-local-create-$nonce.db"
        val captures = java.io.File(context.filesDir, "toolchain-local-editor-ui-$nonce")
        val wasUnlocked = SessionManager.isUnlocked.value
        val models = mutableListOf<PasswordViewModel>()
        var room: PasswordDatabase? = null
        val editorModel = mutableStateOf<PasswordViewModel?>(null)
        val visible = mutableStateOf(false)
        val editId = mutableStateOf<Long?>(null)
        val route = mutableStateOf(0)
        val savedId = AtomicLong()
        val returns = java.util.concurrent.atomic.AtomicInteger()
        var contentMounted = false
        var primaryFailure: Throwable? = null
        try {
            assertTrue(withContext(Dispatchers.IO) {
                takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context)
            } is takagi.ru.monica.security.SecureStartupResult.Ready)
            val security = SecurityManager(context)
            SessionManager.markUnlocked()
            fun openRoom(): PasswordDatabase = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            suspend fun attachModel(database: PasswordDatabase) = withContext(Dispatchers.Main) {
                val model = PasswordViewModel(PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao()),
                    security, secureItemRepository = SecureItemRepository(database.secureItemDao()),
                    customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(database.customFieldDao()),
                    context = context, localKeePassDatabaseDao = database.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(context))
                models += model
                val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.Local
                editorModel.value = model
            }
            fun rows(database: PasswordDatabase): String = buildString {
                val tables = database.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                for (table in tables) {
                    append(table)
                    database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                        while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) {
                            val value = if (cursor.isNull(column)) null else if (cursor.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB)
                                java.util.Base64.getEncoder().encodeToString(cursor.getBlob(column)) else cursor.getString(column)
                            append('|').append(cursor.getType(column)).append(':').append(value?.length ?: -1).append(':').append(value)
                        }
                    }
                }
            }
            fun capture(step: String) {
                compose.waitForIdle(); instrumentation.waitForIdleSync()
                check(captures.isDirectory || captures.mkdir())
                val image = java.io.File(captures, "$step.png")
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                val marker = java.io.File(captures, "$step.json")
                marker.writeText(org.json.JSONObject().put("step", step).put("nonce", nonce).put("uid", android.os.Process.myUid())
                    .put("package", context.packageName).put("image", image.name).put("pause", false).put("state", "ready").toString(2))
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_UI_CAPTURE ${marker.absolutePath}\n") })
            }
            fun editor() = compose.onNode(hasTestTag("password_classic_editor") or hasTestTag("password_content_editor"))
            fun field(tag: String): SemanticsNodeInteraction {
                editor().performScrollToNode(hasTestTag(tag))
                return compose.onNodeWithTag(tag)
            }
            fun fill(title: String, secret: String) {
                field("password_editor_title").performTextReplacement(title)
                val username = hasSetTextAction() and hasText(context.getString(takagi.ru.monica.R.string.username))
                editor().performScrollToNode(username)
                compose.onNode(username).performTextReplacement("synthetic-local-user")
                field("password_editor_value_0").performTextReplacement(secret)
                androidx.test.espresso.Espresso.closeSoftKeyboard()
            }
            room = openRoom()
            val database = checkNotNull(room)
            val sentinelId = database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Synthetic sentinel",
                username = "sentinel", password = security.encryptData("Synthetic sentinel password"), website = "https://example.invalid"))
            val sentinel = checkNotNull(database.passwordEntryDao().getPasswordEntryById(sentinelId))
            attachModel(database)
            compose.setContent { MonicaTheme {
                if (visible.value) androidx.compose.runtime.key(route.value) {
                    androidx.compose.runtime.CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides security) {
                        AddEditPasswordScreen(checkNotNull(editorModel.value), passwordId = editId.value, initialStorageExplicit = true,
                            onSaveCompleted = { savedId.set(checkNotNull(it)) },
                            onNavigateBack = { returns.incrementAndGet(); visible.value = false })
                    }
                }
            } }
            contentMounted = true
            val beforeCancel = withContext(Dispatchers.IO) { rows(database) }
            compose.runOnIdle { visible.value = true }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isNotEmpty() }
            fill("Synthetic cancelled draft", "Synthetic cancelled password")
            capture("local-create-draft")
            compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.back)).performClick()
            compose.waitUntil(15000) { returns.get() == 1 }
            compose.waitForIdle()
            assertEquals(0L, savedId.get())
            assertTrue("Cancel must preserve every isolated database row", beforeCancel == withContext(Dispatchers.IO) { rows(database) })
            compose.runOnIdle { route.value++; visible.value = true }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isNotEmpty() }
            val title = "Synthetic saved local item"
            val secret = "Synthetic saved local password"
            fill(title, secret)
            capture("local-create-ready-to-save")
            compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
            compose.waitUntil(30000) { returns.get() == 2 && savedId.get() > 0 }
            val id = savedId.get()
            val saved = checkNotNull(database.passwordEntryDao().getPasswordEntryById(id))
            assertTrue("Separate local record ID", id != sentinelId)
            assertEquals(title, saved.title)
            assertTrue("Password stored encrypted and readable (values suppressed)", saved.password != secret && security.decryptData(saved.password) == secret)
            assertTrue(saved.keepassDatabaseId == null && saved.bitwardenVaultId == null && saved.mdbxDatabaseId == null)
            assertTrue("Sentinel raw row unchanged", sentinel == database.passwordEntryDao().getPasswordEntryById(sentinelId))
            assertEquals(2, database.passwordEntryDao().getAllPasswordEntriesSync().size)
            compose.waitForIdle()
            withContext(Dispatchers.Main) { editorModel.value = null; models.last().viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            withContext(Dispatchers.IO) { database.close() }
            room = openRoom()
            val reopened = checkNotNull(room)
            assertTrue("Disk reopen preserves saved ciphertext and ID", saved == reopened.passwordEntryDao().getPasswordEntryById(id))
            assertTrue("Disk reopen preserves sentinel ciphertext", sentinel == reopened.passwordEntryDao().getPasswordEntryById(sentinelId))
            attachModel(reopened)
            compose.runOnIdle { editId.value = id; route.value++; visible.value = true }
            compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_editor_title") and hasText(title)).fetchSemanticsNodes().isNotEmpty() }
            val passwordField = field("password_editor_value_0").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.InputText].text
            assertTrue("Real editor reopened original password (value suppressed)", passwordField == secret)
            capture("local-create-disk-reopened")
            compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.back)).performClick()
            compose.waitUntil(15000) { returns.get() == 3 }
            assertTrue("Reopen and cancel cannot rewrite either raw row",
                saved == reopened.passwordEntryDao().getPasswordEntryById(id) && sentinel == reopened.passwordEntryDao().getPasswordEntryById(sentinelId))
            assertEquals(2, reopened.passwordEntryDao().getAllPasswordEntriesSync().size)
        } catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            val errors = mutableListOf<Throwable>()
            if (contentMounted) runCatching { compose.runOnIdle { visible.value = false } }.exceptionOrNull()?.let(errors::add)
            withContext(NonCancellable + Dispatchers.Main) { models.forEach { model ->
                runCatching { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }.exceptionOrNull()?.let(errors::add)
            } }
            runCatching { withContext(NonCancellable + Dispatchers.IO) { room?.close() } }.exceptionOrNull()?.let(errors::add)
            runCatching { withContext(NonCancellable + Dispatchers.IO) {
                check(context.deleteDatabase(name) || !context.getDatabasePath(name).exists())
            } }.exceptionOrNull()?.let(errors::add)
            runCatching { if (!wasUnlocked) SessionManager.markLocked() }.exceptionOrNull()?.let(errors::add)
            if (errors.isNotEmpty()) {
                val failure = primaryFailure ?: errors.first()
                errors.filter { it !== failure }.forEach(failure::addSuppressed)
                if (primaryFailure == null) throw failure
            }
            // Unique screenshots remain for the host collector; only the owned database is removed.
        }
    }

    @Test fun standardEditorPersistsLocalPasswordBeforeReturning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(withContext(Dispatchers.IO) {
            takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context)
        } is takagi.ru.monica.security.SecureStartupResult.Ready)
        val name = "local-save-timing-${UUID.randomUUID()}.db"
        val room = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
        val security = SecurityManager(context)
        val wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val dao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
        val models = withContext(Dispatchers.Main) {
            val repository = SecureItemRepository(room.secureItemDao())
            val model = PasswordViewModel(PasswordRepository(room.passwordEntryDao()), security,
                context = context, localKeePassDatabaseDao = dao, strings = AppLocaleStringResolver(context))
            val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.Local
            listOf(model,
                BankCardViewModel(repository, context, dao, security, strings = AppLocaleStringResolver(context)),
                NoteViewModel(repository, context = context, localKeePassDatabaseDao = dao, securityManager = security, strings = AppLocaleStringResolver(context)),
                LocalKeePassViewModel(context.applicationContext as android.app.Application, dao, security))
        }
        val model = models[0] as PasswordViewModel
        val visible = mutableStateOf(true)
        try {
            val ids = room.passwordEntryDao().insertPasswordEntries((0 until 300).map { i ->
                PasswordEntry(title = "Synthetic $i", username = "user-$i", password = security.encryptData("secret-$i"), website = "")
            })
            val started = AtomicLong()
            val returned = AtomicLong()
            compose.setContent { MonicaTheme {
                if (visible.value) AddEditPasswordScreen(model,
                    bankCardViewModel = models[1] as BankCardViewModel,
                    noteViewModel = models[2] as NoteViewModel,
                    localKeePassViewModel = models[3] as LocalKeePassViewModel,
                    passwordId = ids.first(), onSaveCompleted = {}, onNavigateBack = {
                        returned.compareAndSet(0L, android.os.SystemClock.elapsedRealtime())
                        visible.value = false
                    })
            } }
            compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_editor_title") and hasText("Synthetic 0")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("password_editor_title").performTextReplacement("Local disk saved")
            val save = requireNotNull(compose.onNodeWithTag("password_editor_save").assertIsEnabled()
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnUiThread { started.set(android.os.SystemClock.elapsedRealtime()); assertTrue(save()) }
            compose.waitUntil(30000) { returned.get() > 0 }
            assertEquals("Local disk saved", room.passwordEntryDao().getPasswordEntryById(ids.first())!!.title)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "KDBX_SAVE_PERF stage=local_disk_editor_save_handler_until_return ms=${returned.get() - started.get()}\n")
            })
        } finally {
            compose.runOnIdle { visible.value = false }
            models.forEach { it.viewModelScope.cancel() }
            room.close()
            context.deleteDatabase(name)
            if (!wasUnlocked) SessionManager.markLocked()
        }
    }
}
