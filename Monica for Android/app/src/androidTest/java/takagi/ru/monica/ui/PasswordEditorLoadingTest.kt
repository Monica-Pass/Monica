package takagi.ru.monica.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real editor and disk writes, with controlled latency at the existing DAO boundary. */
class PasswordEditorLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialEditCannotCreateDuplicateAndLoadedEditKeepsIdentity() = fixture(blockSiblings = true) { h ->
        h.waitForTitle()
        h.replacePassword()
        h.attemptBlockedSave()
        h.assertUnchanged()
        h.readGate.complete(Unit)
        h.waitForSave()
        h.saveEditAndReopen()
    }

    @Test fun saveWaitsForLateFieldsAndPreservesTheirValues() = fixture(blockFields = true) { h ->
        h.waitForLateFields()
        h.replacePassword()
        h.attemptBlockedSave()
        h.assertUnchanged()
        h.fieldGate.complete(Unit)
        h.waitForSave()
        h.saveEditAndReopen()
    }

    @Test fun failedLateReadCannotSavePartialDraft() = fixture(blockFields = true, failFields = true) { h ->
        h.waitForLateFields()
        h.fieldGate.complete(Unit)
        compose.waitUntil(15000) { h.failedReads.get() > 0 }
        compose.waitForIdle()
        h.replacePassword()
        h.attemptBlockedSave()
        h.assertUnchanged()
    }

    @Test fun missingEditCannotBecomeNewItem() = fixture(missing = true) { h ->
        compose.waitUntil(15000) { h.idReads.get() > 0 && compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_title").performTextReplacement("Synthetic missing source")
        h.replacePassword()
        h.attemptBlockedSave()
        h.assertUnchanged()
    }

    @Test fun restoredFormWaitsForFreshSourceBeforeSaving() = fixture { h ->
        h.waitForSave()
        h.readGate = CompletableDeferred()
        h.restoration.emulateSavedInstanceStateRestore()
        h.waitForTitle()
        h.replacePassword()
        h.attemptBlockedSave()
        h.assertUnchanged()
        h.readGate.complete(Unit)
        h.waitForSave()
        h.saveEditAndReopen()
    }

    @Test fun cancellingWhileLoadingLeavesOriginalUntouched() = fixture(blockSiblings = true) { h ->
        h.waitForTitle()
        compose.runOnIdle { h.visible.value = false }
        h.readGate.complete(Unit)
        compose.waitForIdle()
        assertEquals(0, h.returned.get())
        h.assertUnchanged()
    }

    @Test fun lockDuringLoadDisposesEditorAndCannotReviveDraft() = fixture(blockSiblings = true) { h ->
        h.waitForTitle()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isEmpty() }
        h.readGate.complete(Unit)
        compose.runOnIdle { SessionManager.markUnlocked() }
        compose.waitForIdle()
        compose.onNodeWithTag("password_editor_save").assertDoesNotExist()
        h.assertUnchanged()
    }

    @Test fun explicitNewEntryStillCreatesOneNewId() = fixture(create = true) { h ->
        compose.waitUntil(15000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_title").performTextReplacement("Synthetic new entry")
        h.replacePassword()
        h.waitForSave()
        h.save()
        val rows = h.dao.getAllPasswordEntriesSync()
        assertEquals(2, rows.size)
        assertEquals(h.original, rows.single { it.id == h.original.id })
        assertEquals("synthetic-new-password", h.security.decryptData(rows.single { it.id != h.original.id }.password))
    }

    @Test fun explicitCopyStillCreatesNewIdAndPreservesSource() = fixture(copy = true) { h ->
        h.waitForSave()
        h.replacePassword()
        h.save()
        val rows = h.dao.getAllPasswordEntriesSync()
        assertEquals(2, rows.size)
        assertEquals(h.original, rows.single { it.id == h.original.id })
        assertEquals(h.original.title, rows.single { it.id != h.original.id }.title)
        assertEquals("synthetic-new-password", h.security.decryptData(rows.single { it.id != h.original.id }.password))
    }

    private fun fixture(blockSiblings: Boolean = false, blockFields: Boolean = false,
                        failFields: Boolean = false, missing: Boolean = false,
                        create: Boolean = false, copy: Boolean = false,
                        action: suspend (Harness) -> Unit) = runBlocking {
        val h = Harness(blockSiblings, blockFields, failFields, missing, create, copy)
        try { h.open(); action(h) } finally { h.close() }
    }

    private inner class Harness(val blockSiblings: Boolean, val blockFields: Boolean,
                                val failFields: Boolean, val missing: Boolean,
                                val create: Boolean, val copy: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "password-editor-loading-${UUID.randomUUID()}.db"
        val room = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
        val dao = room.passwordEntryDao()
        lateinit var security: SecurityManager
        lateinit var original: PasswordEntry
        lateinit var originalFields: List<CustomField>
        var readGate = CompletableDeferred<Unit>().also { if (!blockSiblings) it.complete(Unit) }
        val fieldGate = CompletableDeferred<Unit>()
        val lateFields = AtomicInteger()
        val failedReads = AtomicInteger()
        val idReads = AtomicInteger()
        val syncFieldReads = AtomicInteger()
        val returned = AtomicInteger()
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        val models = mutableListOf<ViewModel>()
        val wasUnlocked = SessionManager.isUnlocked.value
        var mounted = false

        suspend fun open() {
            assertTrue(withContext(Dispatchers.IO) {
                takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context)
            } is takagi.ru.monica.security.SecureStartupResult.Ready)
            security = SecurityManager(context)
            SessionManager.markUnlocked()
            val id = dao.insertPasswordEntry(PasswordEntry(title = "Synthetic edit source", username = "test-user",
                password = security.encryptData("synthetic-old-password"), website = "", notes = "Preserve this note"))
            original = requireNotNull(dao.getPasswordEntryById(id))
            room.customFieldDao().replaceFieldsForEntry(id, listOf(
                CustomField(entryId = id, title = "Same field", value = "first", sortOrder = 0),
                CustomField(entryId = id, title = "Same field", value = "second", isProtected = true, sortOrder = 1)))
            originalFields = room.customFieldDao().getFieldsByEntryIdSync(id)
            val delayedDao = object : PasswordEntryDao by dao {
                override fun getActiveEntries(): Flow<List<PasswordEntry>> = flow {
                    readGate.await()
                    emitAll(dao.getActiveEntries())
                }
                override suspend fun getPasswordEntryById(id: Long): PasswordEntry? {
                    idReads.incrementAndGet()
                    return dao.getPasswordEntryById(id)
                }
            }
            val fields = room.customFieldDao()
            val delayedFields = object : CustomFieldDao by fields {
                override suspend fun getFieldsByEntryIdSync(entryId: Long): List<CustomField> {
                    if (syncFieldReads.incrementAndGet() == 2 && blockFields) {
                        lateFields.incrementAndGet()
                        fieldGate.await()
                        if (failFields) { failedReads.incrementAndGet(); error("Synthetic late read failure") }
                    }
                    return fields.getFieldsByEntryIdSync(entryId)
                }
            }
            val itemRepository = SecureItemRepository(room.secureItemDao())
            val model = withContext(Dispatchers.Main) {
                PasswordViewModel(PasswordRepository(delayedDao, categoryDao = room.categoryDao()), security,
                    secureItemRepository = itemRepository, customFieldRepository = CustomFieldRepository(delayedFields),
                    context = context, localKeePassDatabaseDao = room.localKeePassDatabaseDao(),
                    strings = AppLocaleStringResolver(context)).also { model ->
                    models += model
                    val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.Local
                }
            }
            val bank = BankCardViewModel(itemRepository, context, room.localKeePassDatabaseDao(), security,
                strings = AppLocaleStringResolver(context)).also(models::add)
            val note = NoteViewModel(itemRepository, context = context, localKeePassDatabaseDao = room.localKeePassDatabaseDao(),
                securityManager = security, strings = AppLocaleStringResolver(context)).also(models::add)
            val keepass = LocalKeePassViewModel(context.applicationContext as android.app.Application,
                room.localKeePassDatabaseDao(), security).also(models::add)
            restoration.setContent { MonicaTheme {
                if (visible.value) AddEditPasswordScreen(model, bankCardViewModel = bank, noteViewModel = note,
                    localKeePassViewModel = keepass,
                    passwordId = when { create -> null; copy -> -id; missing -> id + 100; else -> id },
                    onSaveCompleted = {}, onNavigateBack = { returned.incrementAndGet(); visible.value = false })
            } }
            mounted = true
        }

        fun waitForTitle() = compose.waitUntil(15000) {
            compose.onAllNodes(hasTestTag("password_editor_title") and hasText(original.title)).fetchSemanticsNodes().isNotEmpty()
        }
        fun waitForLateFields() = compose.waitUntil(15000) { lateFields.get() > 0 }
        fun waitForSave() = compose.waitUntil(15000) {
            compose.onAllNodes(hasTestTag("password_editor_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        fun replacePassword() { compose.onNodeWithTag("password_editor_value_0").performTextReplacement("synthetic-new-password") }
        fun attemptBlockedSave() {
            compose.onNodeWithTag("password_editor_save").assertIsNotEnabled().performClick()
            compose.waitForIdle()
            assertEquals(0, returned.get())
        }
        suspend fun assertUnchanged() {
            assertEquals(listOf(original), dao.getAllPasswordEntriesSync())
            assertEquals(originalFields, room.customFieldDao().getFieldsByEntryIdSync(original.id))
        }
        fun save() {
            compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
            compose.waitUntil(15000) { returned.get() == 1 }
        }
        suspend fun saveEditAndReopen() {
            replacePassword()
            save()
            // Reopen a separate Room handle to verify the persisted identity and contents.
            val reopened = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            try {
                val rows = reopened.passwordEntryDao().getAllPasswordEntriesSync()
                assertEquals(1, rows.size)
                assertEquals(original.id, rows.single().id)
                assertEquals(original.title, rows.single().title)
                assertEquals(original.notes, rows.single().notes)
                assertEquals("synthetic-new-password", security.decryptData(rows.single().password))
                val savedFields = reopened.customFieldDao().getFieldsByEntryIdSync(original.id)
                assertEquals(originalFields.map { Triple(it.title, it.value, it.isProtected) },
                    savedFields.map { Triple(it.title, it.value, it.isProtected) })
            } finally { reopened.close() }
        }
        fun close() {
            if (mounted) compose.runOnIdle { visible.value = false }
            models.forEach { it.viewModelScope.cancel() }
            readGate.complete(Unit)
            fieldGate.complete(Unit)
            room.close()
            context.deleteDatabase(name)
            if (!wasUnlocked) SessionManager.markLocked()
        }
    }
}
