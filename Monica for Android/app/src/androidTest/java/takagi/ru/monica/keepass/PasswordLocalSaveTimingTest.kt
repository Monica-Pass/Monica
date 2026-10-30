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

    @Test fun standardEditorPersistsLocalPasswordBeforeReturning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
