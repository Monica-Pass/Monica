package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.CardWalletScreen
import takagi.ru.monica.ui.screens.CardWalletTab
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.*
import java.io.File

/** Exercises the real screen and its production row hierarchy, not a replacement list. */
class WalletReorderScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { SettingsManager(context) }
    private val scope = SettingsManager.CategoryFilterScope.CARD_WALLET
    private lateinit var previousFilter: SavedCategoryFilterState
    private lateinit var database: PasswordDatabase
    private val store = ViewModelStore()
    private var stackId: String? = null
    private var additionalStackId: String? = null
    private var visible by mutableStateOf(true)
    private var selecting = false
    private var selectedColor = 0
    private var unselectedColor = 0

    @Before fun prepare() = runBlocking {
        previousFilter = settings.categoryFilterStateFlow(scope).first()
        settings.updateCategoryFilterState(scope, SavedCategoryFilterState())
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        // IDs are reserved to this in-memory fixture; no user's vault rows are touched.
        listOf(910001L, 910002L, 910003L, 910004L).forEachIndexed { index, id ->
            database.secureItemDao().insertItem(SecureItem(
                id=id, itemType=ItemType.BANK_CARD, title="Reorder demo $id", sortOrder=index,
                itemData=Json.encodeToString(BankCardData(cardNumber="4111111111111111", cardholderName="DEMO", bankName="Demo bank", expiryMonth="09", expiryYear="2030"))
            ))
        }
        stackId = WalletStackRepository.get(context).create(listOf(910003L, 910004L))
    }

    @After fun cleanUp() {
        compose.runOnIdle { visible=false; store.clear() }
        compose.waitForIdle()
        runBlocking {
            stackId?.let { WalletStackRepository.get(context).dissolve(it) }
            additionalStackId?.let { WalletStackRepository.get(context).dissolve(it) }
            settings.updateCategoryFilterState(scope, previousFilter)
        }
        database.close()
    }

    private fun showScreen(selectFirstCard: Boolean = true) {
        val repository=SecureItemRepository(database.secureItemDao())
        val strings=AppLocaleStringResolver(context)
        val security=SecurityManager(context)
        val bank=BankCardViewModel(repository, strings=strings)
        val documents=DocumentViewModel(repository, strings=strings)
        val addresses=BillingAddressViewModel(repository)
        val passwords=PasswordViewModel(PasswordRepository(database.passwordEntryDao()), security, strings=strings)
        store.put("bank", bank); store.put("documents", documents)
        store.put("addresses", addresses); store.put("passwords", passwords)
        compose.setContent {
            MaterialTheme {
                selectedColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
                unselectedColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                if (visible) CardWalletScreen(
                    bank, documents, addresses, passwords,
                    onCardClick={}, onDocumentClick={}, onBillingAddressClick={},
                    currentTab=CardWalletTab.ALL, onTabSelected={},
                    onSelectionModeChange={ active, _, _, _, _, _ -> selecting=active },
                    onBankCardSelectionModeChange={ _, _, _, _, _, _, _ -> }
                )
            }
        }
        if (!selectFirstCard) {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("wallet_stack_$additionalStackId").fetchSemanticsNodes().isNotEmpty() }
            return
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("wallet_card_910001").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wallet_card_910001").performTouchInput { longClick() }
        compose.waitUntil(10_000) { selecting }
        compose.onNodeWithTag("wallet_card_910001").performScrollTo()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "wallet-reorder-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun collapsedStacksReorderWithoutChangingSelectionMembershipOrCoverAndSurviveReopening() {
        val repository = WalletStackRepository.get(context)
        additionalStackId = runBlocking { repository.create(listOf(910001L, 910002L)) }
        val firstId = additionalStackId!!
        val secondId = stackId!!
        runBlocking { repository.setCover(firstId, 910002L) }
        val savedStacks = runBlocking { repository.stacks.first() }
        fun toggleStack(id: String) {
            // Headers may be outside the composition after anchoring to a member card.
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("wallet_selection_header_$id"))
            compose.onNodeWithTag("wallet_selection_expand_$id").performClick()
        }
        showScreen(selectFirstCard = false)
        compose.onNodeWithTag("wallet_stack_$firstId").performTouchInput { longClick() }
        compose.waitUntil(10_000) { selecting }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("wallet_card_910002").fetchSemanticsNodes().isNotEmpty() }
        val firstHeader = compose.onNodeWithTag("wallet_selection_header_$firstId")
        val secondHeader = compose.onNodeWithTag("wallet_selection_header_$secondId")
        toggleStack(firstId)
        compose.onNodeWithTag("wallet_card_910001").assertDoesNotExist()
        compose.onNodeWithTag("wallet_card_910002").assertDoesNotExist()
        firstHeader.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.On))
        toggleStack(secondId)
        compose.onNodeWithTag("wallet_card_910003").assertDoesNotExist()
        capture("stacks-collapsed")

        fun drag(from: SemanticsNodeInteraction, to: SemanticsNodeInteraction) {
            from.performScrollTo()
            to.performScrollTo()
            compose.waitForIdle()
            val distance = to.fetchSemanticsNode().boundsInRoot.center.y - from.fetchSemanticsNode().boundsInRoot.center.y
            from.performTouchInput {
                // The title area avoids the checkbox and trailing buttons.
                down(Offset(center.x * 0.8f, center.y)); advanceEventTime(700)
                moveBy(Offset(0f, distance * 0.6f), 180)
            }
            compose.waitForIdle()
            from.performTouchInput { moveBy(Offset(0f, distance * 0.5f), 180); up() }
            compose.waitForIdle()
        }
        fun awaitOrder(ids: List<Long>) {
            compose.waitUntil(10_000) {
                runBlocking { database.secureItemDao().getItemsByType(ItemType.BANK_CARD).first() }
                    .sortedBy { it.sortOrder }.map { it.id } == ids
            }
        }
        drag(firstHeader, secondHeader)
        awaitOrder(listOf(910003L, 910004L, 910001L, 910002L))
        assertTrue(secondHeader.fetchSemanticsNode().boundsInRoot.top < firstHeader.fetchSemanticsNode().boundsInRoot.top)
        capture("stacks-reordered")
        firstHeader.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.On))
        assertEquals(savedStacks, runBlocking { repository.stacks.first() })

        // Expanding and collapsing a partial selection must retain the exact check state.
        toggleStack(firstId)
        compose.onNodeWithTag("wallet_card_910001").performScrollTo().performClick()
        toggleStack(firstId)
        firstHeader.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Indeterminate))
        toggleStack(firstId)
        firstHeader.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Indeterminate))
        capture("stacks-partial-expanded")

        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("wallet_stack_$secondId").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onNodeWithTag("wallet_stack_$secondId").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_stack_$firstId").fetchSemanticsNode().boundsInRoot.top)
        // The reverse move exercises persistence again from a freshly opened screen.
        compose.onNodeWithTag("wallet_stack_$secondId").performTouchInput { longClick() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("wallet_card_910003").fetchSemanticsNodes().isNotEmpty() }
        toggleStack(secondId)
        toggleStack(firstId)
        drag(firstHeader, secondHeader)
        awaitOrder(listOf(910001L, 910002L, 910003L, 910004L))
        assertEquals(savedStacks, runBlocking { repository.stacks.first() })
    }

    @Test fun independentCardsReorderInTheActualWalletAndSurviveReopening() {
        showScreen()
        val first=compose.onNodeWithTag("wallet_card_910001")
        val second=compose.onNodeWithTag("wallet_card_910002")
        second.performScrollTo()
        compose.waitForIdle()
        val distance=second.fetchSemanticsNode().boundsInRoot.center.y-first.fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("Cards must start in original order", distance>0)
        capture("before")
        first.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, distance*0.55f), 160)
        }
        compose.waitForIdle()
        capture("overlap")
        val a=first.fetchSemanticsNode().boundsInRoot
        val b=second.fetchSemanticsNode().boundsInRoot
        val top=maxOf(a.top,b.top)+8f
        val bottom=minOf(a.bottom,b.bottom)-16f
        assertTrue("Fixture must exercise overlapping rows", bottom>top)
        val pixels=compose.onRoot().captureToImage().asAndroidBitmap()
        var selectedPixels=0
        var samples=0
        for (y in top.toInt() until bottom.toInt() step 3) {
            for (x in (a.left+a.width*0.35f).toInt() until (a.left+a.width*0.65f).toInt() step 3) {
                samples++
                // A live press ripple tints the dragged surface. Distinguish the two
                // surfaces by color distance rather than requiring an exact untinted pixel.
                val pixel=pixels.getPixel(x,y)
                fun distance(color: Int): Int = listOf(0,8,16).sumOf { shift ->
                    val delta=((pixel shr shift) and 255)-((color shr shift) and 255)
                    delta*delta
                }
                val selectedDistance=distance(selectedColor)
                if (selectedDistance<3600 && selectedDistance<distance(unselectedColor)) selectedPixels++
            }
        }
        assertTrue("Dragged selected card must paint above its unselected neighbour", selectedPixels>samples*0.5f)
        first.performTouchInput { moveBy(Offset(0f,distance*0.55f),160); up() }
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            val ids=runBlocking { database.secureItemDao().getItemsByType(ItemType.BANK_CARD).first() }
                .sortedBy { it.sortOrder }.map { it.id }
            ids.indexOf(910002L)<ids.indexOf(910001L)
        }
        assertTrue(second.fetchSemanticsNode().boundsInRoot.top < first.fetchSemanticsNode().boundsInRoot.top)
        capture("released")
        runBlocking {
            assertEquals(listOf(910003L,910004L), WalletStackRepository.get(context).stacks.first().first { it.id==stackId }.memberIds)
        }
        compose.runOnIdle { visible=false }
        compose.waitForIdle()
        compose.runOnIdle { visible=true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("wallet_card_910002").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wallet_card_910001").performScrollTo()
        assertTrue(second.fetchSemanticsNode().boundsInRoot.top < first.fetchSemanticsNode().boundsInRoot.top)
        // Reverse the gesture after reopening the real page, then verify persistence again.
        first.performTouchInput { longClick() }
        compose.waitUntil(10_000) { selecting }
        first.performScrollTo()
        compose.waitForIdle()
        val upward=second.fetchSemanticsNode().boundsInRoot.center.y-first.fetchSemanticsNode().boundsInRoot.center.y
        assertTrue(upward<0)
        first.performTouchInput {
            down(center); advanceEventTime(700)
            moveBy(Offset(0f,upward*0.6f),160)
        }
        compose.waitForIdle()
        first.performTouchInput { moveBy(Offset(0f,upward*0.5f),160); up() }
        compose.waitUntil(10_000) {
            val ids=runBlocking { database.secureItemDao().getItemsByType(ItemType.BANK_CARD).first() }
                .sortedBy { it.sortOrder }.map { it.id }
            ids.indexOf(910001L)<ids.indexOf(910002L)
        }
        compose.waitForIdle()
        assertTrue(first.fetchSemanticsNode().boundsInRoot.top < second.fetchSemanticsNode().boundsInRoot.top)
        capture("reverse")
    }
}
