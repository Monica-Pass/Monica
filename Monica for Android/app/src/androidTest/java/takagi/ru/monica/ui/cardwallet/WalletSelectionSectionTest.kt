package takagi.ru.monica.ui.cardwallet

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.ui.components.BankCardCard

@RunWith(AndroidJUnit4::class)
class WalletSelectionSectionTest {
    @get:Rule val compose = createComposeRule()

    private val selected = mutableStateOf(setOf(2L, 5L))
    private val managementRequests = AtomicInteger(0)
    private val longTitle = "工资与日常支出专用账户，包含旅行备用资金"

    private fun showGroups(filtered: Boolean = false, largeFont: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
            fontScale = if (largeFont) 1.3f else 1f
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val titles = mapOf(1L to "日常消费卡", 2L to if (largeFont) longTitle else "工资卡",
            3L to "出行卡", 4L to "备用卡", 5L to "生活卡")
        val cards = listOf(5L, 1L, 3L, 2L, 4L).map { id ->
            WalletListItem(
                id, WalletListItemType.BANK_CARD,
                SecureItem(id = id, itemType = ItemType.BANK_CARD, title = titles.getValue(id), itemData = "{}"),
                bankCardData = BankCardData(
                    cardNumber = "411111111111${id.toString().padStart(4, '0')}",
                    cardholderName = "MONICA DEMO", bankName = "示例银行",
                    expiryMonth = "09", expiryYear = "2030"
                )
            )
        }.filter { !filtered || it.id == 2L || it.id == 5L }
        val entries = projectWalletStacks(cards, listOf(
            WalletStack("travel", listOf(2, 1), coverId = 2),
            WalletStack("daily", listOf(3, 4), coverId = 3)
        ), selectionMode = true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("wallet_selection_list"),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(entries, key = { it.key }) { entry ->
                                when (entry) {
                                    is WalletStackListEntry.SelectionHeader -> WalletSelectionSectionHeader(
                                        entry = entry,
                                        selectedIds = selected.value,
                                        onToggleSelection = { selected.value = toggleWalletSelectionGroup(selected.value, entry) },
                                        onManageStack = { managementRequests.incrementAndGet() },
                                        onToggleExpanded = {}
                                    )
                                    is WalletStackListEntry.Single -> WalletSelectionCardFrame(entry) {
                                        BankCardCard(
                                            item = entry.card.item,
                                            cardData = entry.card.bankCardData,
                                            isSelectionMode = true,
                                            isSelected = entry.card.id in selected.value,
                                            onClick = {
                                                selected.value = if (entry.card.id in selected.value) {
                                                    selected.value - entry.card.id
                                                } else selected.value + entry.card.id
                                            },
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                    is WalletStackListEntry.Stack -> error("Selection must expand stacks")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertGroupState(state: ToggleableState) {
        compose.onNodeWithTag("wallet_selection_header_travel")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("wallet-selection-tests"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun groupCheckboxCompletesAndClearsAPartialSelection() {
        showGroups()
        assertGroupState(ToggleableState.Indeterminate)
        compose.onNodeWithText("已选 1 / 2 张").assertIsDisplayed()
        capture("grouped-selection")
        compose.onNodeWithTag("wallet_selection_header_travel").performClick()
        assertGroupState(ToggleableState.On)
        compose.onNodeWithText("已选 2 / 2 张").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(1L, 2L, 5L), selected.value) }
        compose.onNodeWithTag("wallet_selection_header_travel").performClick()
        assertGroupState(ToggleableState.Off)
        compose.runOnIdle { assertEquals(setOf(5L), selected.value) }
    }

    @Test fun manageButtonDoesNotToggleTheGroup() {
        showGroups()
        compose.onNodeWithTag("wallet_selection_manage_travel").performClick()
        assertGroupState(ToggleableState.Indeterminate)
        compose.runOnIdle {
            assertEquals(1, managementRequests.get())
            assertEquals(setOf(2L, 5L), selected.value)
        }
    }

    @Test fun filteredGroupClearlyScopesSelectionToVisibleCards() {
        selected.value = setOf(5L)
        showGroups(filtered = true)
        compose.onNodeWithText("当前显示：已选 0 / 1 张").assertIsDisplayed()
        compose.onNodeWithText("日常消费卡").assertDoesNotExist()
        compose.onNodeWithTag("wallet_selection_header_travel").performClick()
        compose.onNodeWithText("当前显示：已选 1 / 1 张").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(2L, 5L), selected.value) }
    }

    @Test fun longTitlesKeepCheckboxAndManagementControlsClearAtLargeFont() {
        showGroups(largeFont = true)
        val title = compose.onNodeWithText("卡叠 · $longTitle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val checkbox = compose.onNodeWithTag("wallet_selection_checkbox_travel", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val manage = compose.onNodeWithTag("wallet_selection_manage_travel").fetchSemanticsNode().boundsInRoot
        val collapse = compose.onNodeWithTag("wallet_selection_expand_travel").fetchSemanticsNode().boundsInRoot
        assertTrue("Checkbox overlaps the group title", checkbox.right < title.left)
        assertTrue("Collapse button overlaps the group title", title.right < collapse.left)
        assertTrue("Management button overlaps the collapse button", collapse.right <= manage.left)
        capture("grouped-selection-large-font")
        compose.onNodeWithTag("wallet_selection_header_travel").performClick()
        assertGroupState(ToggleableState.On)
    }
}
