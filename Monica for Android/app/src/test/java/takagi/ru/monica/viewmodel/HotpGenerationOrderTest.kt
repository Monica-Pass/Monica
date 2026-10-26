package takagi.ru.monica.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.StringResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class HotpGenerationOrderTest {
    private lateinit var database: PasswordDatabase
    private lateinit var repository: SecureItemRepository
    private val store = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = SecureItemRepository(database.secureItemDao())
    }

    @After fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    private fun item(id: Long, type: ItemType = ItemType.TOTP, time: Long = (4 - id) * 1000,
        rank: Int = 0, favorite: Boolean = false) = SecureItem(
        id = id, itemType = type, title = "Fixture $id", sortOrder = rank, isFavorite = favorite,
        createdAt = Date(time), updatedAt = Date(time),
        itemData = Json.encodeToString(TotpData(secret = "JBSWY3DPEHPK3PXP",
            otpType = if (id == 3L) OtpType.HOTP else OtpType.TOTP))
    )

    private suspend fun seed(rows: List<SecureItem> = (1L..3L).map { item(it) }) {
        rows.forEach { database.secureItemDao().insertItem(it) }
    }

    private fun viewModel() = TotpViewModel(repository, PasswordRepository(database.passwordEntryDao()),
        strings = StringResolver { _, _ -> "Fixture" }).also { store.put("totp", it) }

    @Test fun generatingRepeatedlyKeepsDefaultPositionAndAdvancesModificationTime() = runBlocking {
        seed()
        val vm = viewModel()
        val original = withTimeout(20_000) { vm.totpItems.first { it.size == 3 } }
        assertEquals(listOf(1L, 2L, 3L), original.map { it.id })
        val previousTime = original.last().updatedAt.time
        repeat(3) { index ->
            vm.incrementHotpCounter(3)
            val current = withTimeout(20_000) {
                vm.totpItems.first { rows -> rows.any { it.id == 3L &&
                    Json.decodeFromString<TotpData>(it.itemData).counter == (index + 1).toLong() } }
            }
            assertTrue("Counter changes retain a fresh modification time", current.single { it.id == 3L }.updatedAt.time > previousTime)
            assertEquals("Generating only changes the counter, not the saved sort rank", 0, current.single { it.id == 3L }.sortOrder)
            assertEquals("Generating must not move HOTP to the front", original.map { it.id }, current.map { it.id })
        }
    }

    @Test fun savedDragRanksRemainPrimaryWhenCounterChanges() = runBlocking {
        seed(listOf(item(1, rank = 2), item(2, rank = 0), item(3, rank = 1)))
        val vm = viewModel()
        val before = withTimeout(20_000) { vm.totpItems.first { it.size == 3 } }
        vm.incrementHotpCounter(3)
        val after = withTimeout(20_000) { vm.totpItems.first { rows -> rows.any { it.id == 3L &&
            Json.decodeFromString<TotpData>(it.itemData).counter == 1L } } }
        assertEquals(listOf(2L, 3L, 1L), after.map { it.id })
        assertEquals(before.map { it.sortOrder }, after.map { it.sortOrder })
    }

    @Test fun favoritePriorityRemainsAboveOrdinaryRows() = runBlocking {
        seed(listOf(item(1), item(2, favorite = true), item(3)))
        val vm = viewModel()
        val before = withTimeout(20_000) { vm.totpItems.first { it.size == 3 } }
        vm.incrementHotpCounter(3)
        val after = withTimeout(20_000) { vm.totpItems.first { rows -> rows.any { it.id == 3L &&
            Json.decodeFromString<TotpData>(it.itemData).counter == 1L } } }
        assertEquals(listOf(2L, 1L, 3L), before.map { it.id })
        assertEquals(before.map { it.id }, after.map { it.id })
    }

    @Test fun identicalCreationTimesUseStableIdsAndBothDaoStreamsAgree() = runBlocking {
        seed((1L..3L).map { item(it, time = 1000) })
        val before = database.secureItemDao().getItemsByType(ItemType.TOTP).first().map { it.id }
        repository.updateItem(requireNotNull(repository.getItemById(before.last())).copy(updatedAt = Date(5000)))
        val after = database.secureItemDao().getItemsByType(ItemType.TOTP).first().map { it.id }
        assertEquals("An edit cannot change a tied authenticator order", before, after)
        assertEquals(after, database.secureItemDao().getActiveItemsByType(ItemType.TOTP).first().map { it.id })
    }

    @Test fun otherItemTypesRetainMostRecentlyModifiedOrdering() = runBlocking {
        seed((1L..3L).map { item(it, type = ItemType.NOTE) })
        assertEquals(listOf(1L, 2L, 3L), database.secureItemDao().getItemsByType(ItemType.NOTE).first().map { it.id })
        repository.updateItem(requireNotNull(repository.getItemById(3)).copy(updatedAt = Date(5000)))
        assertEquals(listOf(3L, 1L, 2L), database.secureItemDao().getItemsByType(ItemType.NOTE).first().map { it.id })
    }

    @Test fun searchAndCategoryFilterRetainCounterPosition() = runBlocking {
        seed(listOf(item(1).copy(categoryId = 9), item(2).copy(categoryId = 10), item(3).copy(categoryId = 9)))
        val vm = viewModel()
        vm.setCategoryFilter(TotpCategoryFilter.Custom(9))
        vm.updateSearchQuery("Fixture")
        val before = withTimeout(20_000) { vm.totpItems.first { it.size == 2 } }
        assertEquals(listOf(1L, 3L), before.map { it.id })
        vm.incrementHotpCounter(3)
        val after = withTimeout(20_000) { vm.totpItems.first { rows -> rows.any { it.id == 3L &&
            Json.decodeFromString<TotpData>(it.itemData).counter == 1L } } }
        assertEquals(before.map { it.id }, after.map { it.id })
    }
}
