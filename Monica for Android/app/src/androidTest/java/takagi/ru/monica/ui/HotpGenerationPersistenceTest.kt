package takagi.ru.monica.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.viewmodel.TotpViewModel

/** Production counter mutation and Room emissions feed the same native cards used by the grid. */
class HotpGenerationPersistenceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: PasswordDatabase
    private lateinit var repository: SecureItemRepository
    private val store = ViewModelStore()
    private var currentViewModel by mutableStateOf<TotpViewModel?>(null)
    private var copied = ""

    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        repository = SecureItemRepository(database.secureItemDao())
        (1L..4L).forEach { id ->
            val time = Date((5 - id) * 1000)
            database.secureItemDao().insertItem(SecureItem(id = id, itemType = ItemType.TOTP,
                title = "OTP $id", createdAt = time, updatedAt = time,
                itemData = Json.encodeToString(TotpData(secret = "JBSWY3DPEHPK3PXP", digits = 8,
                    otpType = when(id) { 3L -> OtpType.HOTP; 4L -> OtpType.STEAM; else -> OtpType.TOTP }))))
        }
    }

    @After fun cleanUp() {
        compose.runOnIdle { currentViewModel = null; store.clear() }
        compose.waitForIdle()
        database.close()
    }

    private fun newViewModel() = TotpViewModel(repository, PasswordRepository(database.passwordEntryDao()),
        strings = StringResolver { _, _ -> "Fixture" })

    private fun show() {
        compose.runOnUiThread {
            currentViewModel = newViewModel().also { store.put("totp", it) }
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                MonicaTheme {
                    currentViewModel?.let { vm ->
                        val items by vm.totpItems.collectAsState()
                        Surface(Modifier.width(360.dp)) {
                            LazyVerticalGrid(columns = GridCells.Fixed(2),
                                modifier = Modifier.wrapContentHeight(), contentPadding = PaddingValues(12.dp)) {
                                items(items, key = { it.id }) { item ->
                                    TotpCodeCard(item = item,
                                        parsedTotpData = Json.decodeFromString<TotpData>(item.itemData),
                                        compactTile = true, uniformAuthenticatorLayout = true,
                                        sharedTickSeconds = 1_700_000_010L,
                                        modifier = Modifier.testTag("persisted_${item.id}"),
                                        onCopyCode = { copied = it },
                                        onGenerateNext = vm::incrementHotpCounter,
                                        appSettings = AppSettings(iconCardsEnabled = false, validatorSmoothProgress = false))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("persisted_3").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun stored() = runBlocking { database.secureItemDao().getItemsByType(ItemType.TOTP).first() }

    private fun generateAndVerify(counter: Long, expectedOrder: List<Long>) {
        val previousBounds = compose.onNodeWithTag("persisted_3").fetchSemanticsNode().boundsInRoot
        val previousText = compose.onNodeWithTag("persisted_3").fetchSemanticsNode().config[SemanticsProperties.Text]
        compose.onNodeWithText(context.getString(R.string.generate_next)).performTouchInput { click() }
        compose.waitUntil(20_000) {
            Json.decodeFromString<TotpData>(stored().single { it.id == 3L }.itemData).counter == counter
        }
        compose.waitUntil(20_000) {
            compose.onNodeWithTag("persisted_3").fetchSemanticsNode().config[SemanticsProperties.Text] != previousText
        }
        compose.waitForIdle()
        assertEquals("Database keeps the saved display order", expectedOrder, stored().map { it.id })
        val currentBounds = compose.onNodeWithTag("persisted_3").fetchSemanticsNode().boundsInRoot
        assertEquals("Generation preserves the grid row", previousBounds.top, currentBounds.top, 1f)
        assertEquals("Generation preserves the grid column", previousBounds.left, currentBounds.left, 1f)
        compose.onNodeWithTag("persisted_3").assertHeightIsEqualTo(142.dp)
        assertTrue("Changed payloads retain a fresh timestamp", stored().single { it.id == 3L }.updatedAt.time > 2000)
        assertEquals("Generating does not copy", "", copied)
    }

    @Test fun repeatedGenerateNextKeepsDefaultPositionAfterReopening() {
        show()
        val order = listOf(1L, 2L, 3L, 4L)
        assertEquals(order, stored().map { it.id })
        repeat(3) { generateAndVerify(it + 1L, order) }
        compose.runOnIdle { currentViewModel = newViewModel().also { store.put("totp", it) } }
        compose.waitUntil(20_000) { currentViewModel!!.totpItems.value.map { it.id } == order }
        compose.waitForIdle()
        generateAndVerify(4, order)
        compose.onNodeWithTag("persisted_3").performTouchInput { click() }
        assertTrue(copied.matches(Regex("[0-9]{8}")))
        assertEquals(4L, Json.decodeFromString<TotpData>(stored().single { it.id == 3L }.itemData).counter)
    }

    @Test fun generationRetainsPersistedManualOrder() {
        show()
        val order = listOf(4L, 3L, 1L, 2L)
        compose.runOnIdle { currentViewModel!!.updateSortOrders(order.mapIndexed { index, id -> id to index }) }
        compose.waitUntil(20_000) { currentViewModel!!.totpItems.value.map { it.id } == order }
        compose.waitForIdle()
        generateAndVerify(1, order)
        assertEquals(listOf(0, 1, 2, 3), stored().map { it.sortOrder })
    }
}
