package takagi.ru.monica.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.CategoryDao
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Real ViewModel and repository; only the Room notification source is controllable. */
class PasswordListLoadingWorkTest {
    private lateinit var database: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private val categorySubscriptions = AtomicInteger()
    private val snapshots = MutableSharedFlow<List<PasswordEntry>>(replay = 1)

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val categories = object : CategoryDao by database.categoryDao() {
            override fun getAllCategories(): Flow<List<Category>> =
                database.categoryDao().getAllCategories().onStart { categorySubscriptions.incrementAndGet() }
        }
        val passwords = object : PasswordEntryDao by database.passwordEntryDao() {
            override fun getActiveEntries(): Flow<List<PasswordEntry>> = snapshots
            override fun getArchivedEntries(): Flow<List<PasswordEntry>> = snapshots
        }
        model = PasswordViewModel(
            PasswordRepository(passwords, categoryDao = categories), SecurityManager(context),
            strings = AppLocaleStringResolver(context)
        )
    }

    @After fun cleanup() { model.viewModelScope.cancel(); database.close() }

    @Test fun categoryReadinessAndContentsUseOneRoomSubscription() = runBlocking {
        val subscriber = launch { model.categories.collect() }
        try {
            withTimeout(5000) { model.categoriesReady.first { it } }
            android.util.Log.i("PasswordLoadingWork", "categorySubscriptions=${categorySubscriptions.get()}")
            assertEquals(1, categorySubscriptions.get())
        } finally { subscriber.cancel() }
    }

    @Test fun listMetadataTracksEditsOrderAndRemovalWithoutChangingStoredFields() = runBlocking {
        val source = List(1000) { index ->
            PasswordEntry(id = index + 1L, title = "Synthetic $index", username = "user-$index",
                password = "synthetic-ciphertext-$index", website = "example.invalid", notes = "note-$index")
        }
        val subscriber = launch { model.allPasswordsForUi.collect() }
        val archiveSubscriber = launch { model.archivedPasswordsForUi.collect() }
        suspend fun expect(entries: List<PasswordEntry>) {
            snapshots.emit(entries)
            val expected = entries.map { it.copy(password = "") }
            val actual = withTimeout(10000) { model.allPasswordsForUi.first { it == expected } }
            assertEquals(expected, actual)
            assertEquals(expected, withTimeout(10000) { model.archivedPasswordsForUi.first { it == expected } })
            if (entries.isNotEmpty()) assertNotSame(entries.first(), actual.first())
        }
        try {
            expect(source)
            expect(source.map { it.copy() })
            expect(source.mapIndexed { index, entry ->
                if (index == 500) entry.copy(title = "Edited", notes = "edited note", password = "new-ciphertext") else entry
            })
            expect(source.reversed())
            expect(source.drop(1))
            expect(emptyList())
            assertEquals("synthetic-ciphertext-500", source[500].password)
            assertEquals("note-500", source[500].notes)
        } finally { subscriber.cancel(); archiveSubscriber.cancel() }
    }
}
