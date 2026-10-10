package takagi.ru.monica.passkey

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasskeyViewModel

class PasskeyFilterPersistenceTest {
    @Test fun restoresDatabaseAndFolderIdentitiesWithoutRewritingThem() = verifyPersistence(selectImmediately = false)
    @Test fun immediateUserSelectionWinsOverInitialRestoreIncludingAll() = verifyPersistence(selectImmediately = true)

    private fun verifyPersistence(selectImmediately: Boolean): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context)
        val filterScope = SettingsManager.CategoryFilterScope.PASSKEY
        val previous = settings.categoryFilterStateFlow(filterScope).first()
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val savedFilters = listOf(
            SavedCategoryFilterState("local"),
            SavedCategoryFilterState("bitwarden_vault", primaryId = 9L),
            SavedCategoryFilterState("bitwarden_folder", primaryId = 9L, text = "accounts"),
            SavedCategoryFilterState("keepass_database", primaryId = 8L),
            SavedCategoryFilterState("keepass_group", primaryId = 8L, text = "Work/Email", groupUuid = "group-uuid"),
            SavedCategoryFilterState("mdbx_database", primaryId = 7L),
            SavedCategoryFilterState("mdbx_folder", primaryId = 7L, text = "folder-uuid")
        )
        try {
            savedFilters.forEachIndexed { index, saved ->
                settings.updateCategoryFilterState(filterScope, saved)
                val expected = if (selectImmediately) SavedCategoryFilterState(if (index % 2 == 0) "all" else "local") else saved
                val model = withContext(Dispatchers.Main) {
                    PasskeyViewModel(PasskeyRepository(database.passkeyDao()), context = context,
                        strings = AppLocaleStringResolver(context)).also {
                        if (selectImmediately) it.setCategoryFilter(expected)
                    }
                }
                var stage = "ready"
                try {
                    withTimeout(10_000) {
                        model.isCategoryFilterReady.first { it }
                        stage = "view model"
                        model.categoryFilter.first { it == expected }
                        stage = "persisted settings"
                        // Check the persisted snapshot. Rapidly replacing seven ViewModels can leave a
                        // long-lived DataStore collector waiting despite a fresh read already matching.
                        while (settings.categoryFilterStateFlow(filterScope).first() != expected) delay(25)
                    }
                    withContext(Dispatchers.Main) { assertEquals(expected, model.categoryFilter.value) }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("Filter $index timed out at $stage: expected=$expected, model=${model.categoryFilter.value}, stored=${settings.categoryFilterStateFlow(filterScope).first()}", error)
                } finally {
                    model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            database.close()
            settings.updateCategoryFilterState(filterScope, previous)
        }
    }
}
