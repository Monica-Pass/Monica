package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.EntryContentFields
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.ui.LocalReduceAnimations
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope

/** Gestures against production surfaces; synthetic values only, no database/preferences mutation. */
class DirectReorderDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val blocks = (0..11).map {
        PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY)
            .edited("Synthetic key $it", mapOf("key" to "synthetic-$it"))
    }
    private val fields = blocks.fold(listOf(CustomFieldDraft(title = "future", value = "keep me", isProtected = true))) { fields, block ->
        PasswordContentBlocks.put(fields, block)
    }
    private val stored = PasswordContentBlocks.read(fields)
    private var order by mutableStateOf(listOf("NOTES", stored[0].token, "PAYMENT", stored[1].token, "ADDRESS"))
    private var opened by mutableStateOf<String?>(null)

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "direct-drag-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun editor(dark: Boolean = false, reduce: Boolean = false, height: Int = 650) {
        compose.setContent {
            CompositionLocalProvider(LocalReduceAnimations provides reduce) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface {
                    val list = rememberLazyListState()
                    val dragState = remember { DirectDragState() }
                    val reorder = rememberReorderableLazyListState(list, scroller = rememberDirectReorderScroller(list)) { from, to ->
                        val a = order.indexOfFirst { entryContentItemKey(it) == from.key }
                        val b = order.indexOfFirst { entryContentItemKey(it) == to.key }
                        if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
                    }
                    LazyColumn(Modifier.width(360.dp).height(height.dp).padding(12.dp).testTag("editor"),
                        state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item("credentials") { Text("Account · fixed", Modifier.height(50.dp)) }
                        item("otp") { Text("Authenticator · fixed", Modifier.height(55.dp)) }
                        order.forEachIndexed { i, token ->
                            reorderableContentItem(entryContentItemKey(token), reorder, true, dragState, order.map(::entryContentItemKey)) {
                                val actions = EntryContentActions(
                                    moveUp = if (i > 0) ({ order = order.toMutableList().apply { add(i - 1, removeAt(i)) } }) else null,
                                    moveDown = if (i < order.lastIndex) ({ order = order.toMutableList().apply { add(i + 1, removeAt(i)) } }) else null,
                                    remove = { order = order - token }, groupIndex = i, groupCount = order.size)
                                val block = stored.firstOrNull { it.token == token }
                                if (block != null) PasswordContentBlockCard(block, actions) { opened = token }
                                else CompositionLocalProvider(LocalEntryContentActions provides { actions }) {
                                    EntryContentPanel(PasswordContentSection.valueOf(token),
                                        if (token == "NOTES") "Synthetic multiline note\nAll original values remain unchanged" else "Synthetic $token",
                                        open = false, onOpenChange = { opened = token }) { }
                                }
                            }
                        }
                        item("add") { Text("Add content · fixed", Modifier.height(48.dp).testTag("add")) }
                    }
                } }
            }
        }
    }
    private fun node(token: String) = compose.onNodeWithTag(if (token.startsWith("BLOCK:")) "block_card_$token" else "content_panel_$token")

    @Test fun separatedEdgesRoundTogetherAndReconnectAfterDrop() {
        var draggedIndex by mutableStateOf<Int?>(null)
        val shapes = mutableMapOf<Int, androidx.compose.foundation.shape.RoundedCornerShape>()
        compose.setContent { MaterialTheme {
            Column { repeat(5) { i ->
                val shape = directReorderShape(i, 5, draggedIndex)
                shapes[i] = shape
                Surface(shape = shape, modifier = Modifier.size(200.dp, 60.dp)) { Text("Row $i") }
            } }
        } }
        fun radii(i: Int): Pair<Float, Float> {
            val rect = (shapes.getValue(i).createOutline(androidx.compose.ui.geometry.Size(400f, 160f),
                androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.Density(1f)) as androidx.compose.ui.graphics.Outline.Rounded).roundRect
            return rect.topLeftCornerRadius.x to rect.bottomLeftCornerRadius.x
        }
        compose.runOnIdle { assertEquals(4f to 4f, radii(2)); draggedIndex = 2 }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertEquals(24f to 4f, radii(0)); assertEquals(4f to 24f, radii(1))
            assertEquals(24f to 24f, radii(2)); assertEquals(24f to 4f, radii(3)); assertEquals(4f to 24f, radii(4))
            draggedIndex = 1
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(24f to 24f, radii(0)); assertEquals(24f to 24f, radii(1))
            assertEquals(24f to 4f, radii(2)); assertEquals(4f to 4f, radii(3))
            draggedIndex = null
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(4f to 4f, radii(1)); assertEquals(4f to 4f, radii(2)) }
    }

    @Test fun mixedCardsFollowPointerBeforeCrossingThenSettleAndKeepValues() {
        editor()
        val first = node("NOTES")
        val before = first.fetchSemanticsNode().boundsInRoot
        first.performTouchInput { down(centerLeft + Offset(28f, 0f)); advanceEventTime(650); moveBy(Offset(0f, 22f), 160) }
        compose.mainClock.advanceTimeBy(240)
        val during = first.fetchSemanticsNode().boundsInRoot
        assertTrue("Lifted card must follow the finger before crossing another item", during.top > before.top + 12f)
        compose.runOnIdle { assertEquals("NOTES", order.first()) }
        capture("password-lifted")
        val distance = node("PAYMENT").fetchSemanticsNode().boundsInRoot.center.y - during.center.y
        first.performTouchInput { moveBy(Offset(0f, distance), 450) }
        compose.mainClock.advanceTimeBy(300)
        first.performTouchInput { up() }
        compose.waitUntil(5000) { order.indexOf("NOTES") >= 2 }
        compose.waitForIdle()
        capture("password-settled")
        compose.runOnIdle {
            val saved = EntryContentFields.withOrder(fields, order)
            assertEquals(order, EntryContentFields.order(saved))
            assertEquals(fields, saved.filterNot { it.title == EntryContentFields.ORDER })
            assertEquals(stored.map { it.block!!.raw }, PasswordContentBlocks.read(saved).map { it.block!!.raw })
        }
        first.performClick()
        compose.runOnIdle { assertEquals("NOTES", opened) }
    }

    @Test fun repeatedBlockCanMoveAboveNoteAndFixedFieldsRemainOutsideOrder() {
        editor(dark = true, reduce = true)
        val token = stored[1].token
        val moving = node(token)
        val distance = node("NOTES").fetchSemanticsNode().boundsInRoot.center.y - moving.fetchSemanticsNode().boundsInRoot.center.y
        moving.performTouchInput { down(centerLeft + Offset(35f, 0f)); advanceEventTime(650); moveBy(Offset(0f, distance), 500) }
        compose.mainClock.advanceTimeBy(300)
        moving.performTouchInput { up() }
        compose.waitUntil(5000) { order.first() == token }
        compose.onNodeWithText("Account · fixed").assertIsDisplayed()
        compose.onNodeWithText("Authenticator · fixed").assertIsDisplayed()
        capture("password-dark-reduced")
        moving.performClick()
        compose.runOnIdle { assertEquals(token, opened); assertEquals(5, order.toSet().size) }
    }

    @Test fun customizationWholeRowDragKeepsSwitchAndAccessibleActions() {
        var items by mutableStateOf(listOf("Account", "Website", "Notes"))
        var selected by mutableStateOf(items)
        compose.setContent { MaterialTheme { Surface { Column(Modifier.width(360.dp).padding(12.dp)) {
            CustomizationOrderGroup(takagi.ru.monica.ui.screens.Setting.ADD_BUTTON_ACTIONS_TITLE, items, selected, { it }, { Icons.Default.Tune }, { items = it },
                { item, enabled -> selected = if (enabled) selected + item else selected - item })
        } } } }
        compose.onNodeWithTag("customization_drag_Account").assertDoesNotExist()
        compose.onNodeWithTag("customization_toggle_Website").performClick()
        compose.runOnIdle { assertEquals(listOf("Account", "Notes"), selected); assertEquals("Account", items.first()) }
        val moving = compose.onNodeWithTag("customization_order_Account")
        val destination = compose.onNodeWithTag("customization_order_Notes").fetchSemanticsNode().boundsInRoot.center.y
        val distance = destination - moving.fetchSemanticsNode().boundsInRoot.center.y
        moving.performTouchInput { down(centerLeft + Offset(45f,0f)); advanceEventTime(650); moveBy(Offset(0f,distance),400) }
        compose.mainClock.advanceTimeBy(240)
        capture("customization-lifted")
        moving.performTouchInput { up() }
        compose.waitUntil(5000) { items.last() == "Account" }
        compose.runOnIdle { assertEquals(listOf("Account", "Notes"), selected) }
        val actions = moving.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertEquals(1, actions.size); assertTrue(actions.single().action()) }
        compose.runOnIdle { assertEquals(listOf("Website", "Account", "Notes"), items) }
        capture("customization-settled")
    }

    @Test fun edgeAutoScrollMovesBeyondViewportAndCancelLeavesAValidPermutation() {
        order = listOf("NOTES") + stored.map { it.token }
        val original = order.toSet()
        editor(height = 420)
        val moving = node("NOTES")
        val bottom = compose.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot.bottom
        val distance = bottom - 12f - moving.fetchSemanticsNode().boundsInRoot.center.y
        moving.performTouchInput { down(centerLeft + Offset(35f,0f)); advanceEventTime(650); moveBy(Offset(0f, 1f), 16) }
        compose.waitForIdle()
        moving.performTouchInput { moveBy(Offset(0f, distance - 1f), 500) }
        compose.mainClock.advanceTimeBy(32)
        moving.performTouchInput { moveBy(Offset(0f, 1f), 16) }
        // Auto-scroll awaits Android layout after each swap; allow those real layout passes
        // instead of advancing only the Compose animation clock in one batch.
        compose.waitUntil(8_000) { order.indexOf("NOTES") > 3 }
        capture("edge-autoscroll")
        // OS interruption must release the drag and preserve all content, never duplicate/remove it.
        moving.performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Drag near edge should continue scrolling", order.indexOf("NOTES") > 3)
            assertEquals(original, order.toSet()); assertEquals(original.size, order.size)
        }
    }

    @Test fun realPasswordEditorSavesReorderedContentAndReopensWithoutChangingValues() {
        val fixture = takagi.ru.monica.credentialexchange.TransferFixture()
        val model = takagi.ru.monica.viewmodel.PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(fixture.db.customFieldDao()),
            context = fixture.context, localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(),
            strings = takagi.ru.monica.utils.AppLocaleStringResolver(fixture.context))
        val source = takagi.ru.monica.data.PasswordEntry(title = "${fixture.prefix}-drag", username = "synthetic", password = "synthetic secret",
            website = "https://example.invalid", notes = "Synthetic note\nKeep this exact content", creditCardNumber = "4242424242424242",
            creditCardHolder = "SYNTHETIC", addressLine = "Synthetic address", country = "US")
        val extras = EntryContentFields.withOrder(fields, listOf("NOTES", "PAYMENT", stored[0].token, "ADDRESS") + stored.drop(1).map { it.token })
        var saved by mutableStateOf<Long?>(null)
        var phase by mutableIntStateOf(0)
        try {
            val id = runBlocking {
                val done = kotlinx.coroutines.CompletableDeferred<Long?>()
                model.savePasswordsAcrossTargets(emptyList(), source, listOf(source.password),
                    listOf(takagi.ru.monica.data.model.StorageTarget.MonicaLocal(null)), extras, onComplete = { done.complete(it) })
                requireNotNull(kotlinx.coroutines.withTimeout(30_000) { done.await() })
            }
            val before = runBlocking { model.getPasswordEntryById(id)!! }
            compose.setContent {
                CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides fixture.security) {
                    MaterialTheme { key(phase) {
                        takagi.ru.monica.ui.screens.AddEditPasswordScreen(model, passwordId = id,
                            onSaveCompleted = { saved = it }, onNavigateBack = {})
                    } }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("content_panel_NOTES"))
            val moving = node("NOTES")
            val distance = node("PAYMENT").fetchSemanticsNode().boundsInRoot.center.y - moving.fetchSemanticsNode().boundsInRoot.center.y
            moving.performTouchInput { down(centerLeft + Offset(45f, 0f)); advanceEventTime(650); moveBy(Offset(0f, distance), 500) }
            compose.mainClock.advanceTimeBy(300)
            moving.performTouchInput { up() }
            compose.waitForIdle()
            capture("real-password-editor")
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(20_000) { saved != null }
            val after = runBlocking { model.getPasswordEntryById(id)!! }
            val savedFields = runBlocking { model.getCustomFieldsByEntryIdSync(id) }.map { CustomFieldDraft(it.id, it.title, it.value, it.isProtected) }
            val savedOrder = EntryContentFields.order(savedFields)
            assertTrue(savedOrder.indexOf("PAYMENT") < savedOrder.indexOf("NOTES"))
            assertEquals(before.password, after.password); assertEquals(before.notes, after.notes)
            assertEquals(before.creditCardNumber, after.creditCardNumber); assertEquals(before.addressLine, after.addressLine)
            fields.forEach { expected ->
                val actual = savedFields.single { it.title == expected.title }
                assertEquals(expected.value, actual.value); assertEquals(expected.isProtected, actual.isProtected)
            }
            compose.runOnIdle { phase++ }
            compose.waitForIdle()
            compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("content_panel_PAYMENT"))
            assertTrue(node("PAYMENT").fetchSemanticsNode().boundsInRoot.top < node("NOTES").fetchSemanticsNode().boundsInRoot.top)
            capture("real-password-reopened")
        } finally {
            model.viewModelScope.cancel()
            runBlocking { fixture.close() }
        }
    }
}
