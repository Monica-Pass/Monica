package takagi.ru.monica.keepass

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumb
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumbBanner
import takagi.ru.monica.ui.password.KeePassFileActions
import takagi.ru.monica.ui.password.KeePassFileMenuItems
import takagi.ru.monica.ui.password.rememberKeePassFileActions
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.ui.vaultv2.VaultV2QuickStatusBar
import takagi.ru.monica.viewmodel.CategoryFilter

class KeePassPathStatusUiTest {
    @get:Rule val compose = createComposeRule()
    private val filter = CategoryFilter.KeePassDatabase(42)
    private val breadcrumbs = listOf(PasswordQuickFolderBreadcrumb("fixture", "Synthetic path", filter, true))

    @Test fun statusReplacesPathAndLockRemovesBothImmediately() {
        val state = mutableStateOf(KeePassFileState.READY)
        val available = mutableStateOf(true)
        compose.setContent { MonicaTheme { Column {
            PasswordQuickFolderBreadcrumbBanner(breadcrumbs, filter, {}, keePassFileActions =
                KeePassFileActions(state.value, true, available.value, {}, {}, {}))
            Text("Synthetic list", Modifier.testTag("following-list"))
        } } }
        compose.onNodeWithText("Synthetic path").assertExists()
        compose.onAllNodesWithTag("keepass-file-status").assertCountEquals(0)
        compose.runOnIdle { state.value = KeePassFileState.BUSY }
        compose.onNodeWithTag("keepass-file-status").assertExists()
        compose.onAllNodesWithText("Synthetic path").assertCountEquals(0)
        compose.runOnIdle { state.value = KeePassFileState.RELOADED }
        compose.onNodeWithTag("keepass-file-status").assertExists()
        compose.runOnIdle { state.value = KeePassFileState.READY }
        compose.onNodeWithText("Synthetic path").assertExists()
        compose.onAllNodesWithTag("keepass-file-status").assertCountEquals(0)
        compose.runOnIdle { available.value = false }
        compose.onAllNodesWithText("Synthetic path").assertCountEquals(0)
        compose.onAllNodesWithTag("keepass-file-status").assertCountEquals(0)
    }

    @Test fun realMergeMenuNeedsNoSourcePickerAndSuccessRestoresPath() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, _, _ ->
            val store = requireNotNull(model.monicaKeePassStore)
            store.refresh(id)
            compose.setContent { MonicaTheme { Column {
                val actions = rememberKeePassFileActions(id, model) {}
                PasswordQuickFolderBreadcrumbBanner(breadcrumbs, filter, {}, keePassFileActions = actions)
                actions?.let { KeePassFileMenuItems(it) {} }
            } } }
            compose.onNodeWithTag("keepass-file-merge").performClick()
            compose.waitUntil(30000) { store.fileStates.value[id]?.state == KeePassFileState.MERGED }
            compose.onAllNodesWithTag("keepass-merge-source").assertCountEquals(0)
            compose.onAllNodesWithTag("keepass-merge-password").assertCountEquals(0)
            compose.onNodeWithTag("keepass-file-status").assertExists()
            compose.waitUntil(6000) { store.fileStates.value[id]?.state == KeePassFileState.READY }
            compose.onNodeWithText("Synthetic path").assertExists()
            compose.onAllNodesWithTag("keepass-file-status").assertCountEquals(0)
        }
    }

    @Test fun expiredNoticeCannotDismissANewerOperationResult() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, _ ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            store.reload(id)
            val first = requireNotNull(store.fileStates.value[id])
            store.reload(id)
            val second = requireNotNull(store.fileStates.value[id])
            store.dismissFileNotice(id, first)
            assertSame(second, store.fileStates.value[id])
            store.dismissFileNotice(id, second)
            assertEquals(KeePassFileState.READY, store.fileStates.value[id]?.state)
        }
    }

    @Test fun vaultStatusSharesPathSpaceAndLargeTextHeightMovesListSmoothly() {
        val state = mutableStateOf(KeePassFileState.READY)
        compose.setContent { MonicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Column {
                    VaultV2QuickStatusBar(false, "Synthetic path", "S", breadcrumbs, filter,
                        null, null, null, {}, KeePassFileActions(state.value, true, true, {}, {}, {}))
                    Text("Synthetic list", Modifier.testTag("following-list"))
                }
            }
        } }
        fun listY() = compose.onNodeWithTag("following-list").fetchSemanticsNode().boundsInRoot.top
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("captureKeePassPath") != "true") return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.filesDir, "keepass-path-$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText("Synthetic path").assertExists()
        compose.onAllNodesWithTag("keepass-file-status").assertCountEquals(0)
        val pathY = listY()
        compose.onNodeWithText("Synthetic path").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(results))
            assertFalse("Large breadcrumb text must not be vertically clipped", results.single().didOverflowHeight)
        }
        capture("path")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = KeePassFileState.WRITE_FAILED }
        compose.mainClock.advanceTimeBy(32)
        val startY = listY()
        compose.mainClock.advanceTimeBy(96)
        val middleY = listY()
        compose.mainClock.advanceTimeBy(500)
        val errorY = listY()
        assertTrue("Error actions should expand the path container", errorY > pathY)
        assertTrue("List must move through an intermediate height", middleY > startY && middleY < errorY)
        compose.onAllNodesWithText("Synthetic path").assertCountEquals(0)
        compose.onNodeWithTag("keepass-file-status").assertExists()
        capture("error-large-text")
        compose.runOnIdle { state.value = KeePassFileState.READY }
        compose.mainClock.advanceTimeBy(128)
        val shrinkingY = listY()
        compose.mainClock.advanceTimeBy(500)
        assertTrue(shrinkingY > pathY && shrinkingY < errorY)
        assertEquals(pathY, listY(), 1f)
        compose.mainClock.autoAdvance = true
    }
}
