package takagi.ru.monica.keepass

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.sync.*
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumb
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumbBanner
import takagi.ru.monica.ui.password.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.ui.vaultv2.VaultV2QuickStatusBar
import takagi.ru.monica.viewmodel.CategoryFilter

class AllVaultLoadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val filter = CategoryFilter.All
    private val breadcrumbs = listOf(PasswordQuickFolderBreadcrumb("all-fixture", "All fixture", filter, true))

    @Test fun actualQueuedDatabasesDriveStatusAndCompletionRestoresPath() = runBlocking<Unit> {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val targets = listOf(SyncTarget.KeePassDatabase(985321), SyncTarget.BitwardenVault(985322))
        val gates = listOf(first, second)
        val active = mutableStateOf(true)
        val preparing = mutableStateOf(false)
        SessionManager.markUnlocked()
        try {
            targets.forEachIndexed { i, target ->
                val request = SyncRequest("all-list-${System.nanoTime()}-$i", target, SyncTrigger.MANUAL,
                    System.currentTimeMillis(), dedupeKey = target.stableKey, networkPolicy = SyncNetworkPolicy.FORBIDDEN)
                assertTrue(SyncTaskRunner.request(request) { gates[i].await() } is SyncEnqueueResult.Accepted)
            }
            compose.setContent { MonicaTheme {
                val loading = rememberAllVaultLoadingState(active.value, preparing.value)
                PasswordQuickFolderBreadcrumbBanner(breadcrumbs, filter, {}, allVaultLoadingState = loading)
            } }
            val two = context.getString(R.string.kp_list_loading_databases, 2)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(two).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("All fixture").assertCountEquals(0)
            compose.runOnIdle { active.value = false }
            compose.onNodeWithText("All fixture").assertExists()
            compose.runOnIdle { active.value = true }
            compose.onNodeWithText(two).assertExists()
            first.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.kp_list_loading)).fetchSemanticsNodes().isNotEmpty() }
            second.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("All fixture").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { preparing.value = true }
            compose.onNodeWithTag("all-vault-loading-status", useUnmergedTree = true).assertExists()
            compose.runOnIdle { SessionManager.markLocked() }
            compose.onAllNodesWithTag("all-vault-loading-status", useUnmergedTree = true).assertCountEquals(0)
        } finally {
            first.complete(Unit); second.complete(Unit)
            SessionManager.markLocked()
        }
    }

    @Test fun vaultBarShowsShortLargeTextStatusAndRestoresBreadcrumb() {
        val loading = mutableStateOf<AllVaultLoadingState?>(AllVaultLoadingState(2))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            MonicaTheme { Column {
                VaultV2QuickStatusBar(false, "All fixture", "A", breadcrumbs, filter, null, null, null, {}, null,
                    allVaultLoadingState = loading.value)
                Text("Existing list", Modifier.testTag("all-following-list"))
            } }
        } }
        compose.onNodeWithText(context.getString(R.string.kp_list_loading_databases, 2)).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(results)); assertFalse(results.single().hasVisualOverflow)
        }
        if (InstrumentationRegistry.getArguments().getString("captureKeePassList") == "true") {
            java.io.File(context.filesDir, "keepass-list-all.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { loading.value = null }
        compose.onNodeWithText("All fixture").assertExists()
        compose.onAllNodesWithTag("all-vault-loading-status", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("all-following-list").assertExists()
    }

    @Test fun lockedAndFailedBarsMatchLoadingHeightAndWholeBarRemainsClickable() {
        val mode = mutableIntStateOf(0)
        val fontScale = mutableFloatStateOf(1f)
        var unlocks = 0
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                MonicaTheme {
                    KeePassFileStatus(KeePassFileActions(KeePassFileState.READY, true, true, {}, {}, {})
                        .withListTransition(KeePassListTransition("Vault", null, mode.intValue == 1,
                            mode.intValue == 2, true, { retries++ }, { unlocks++ })))
                }
            }
        }
        for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { fontScale.floatValue = scale; mode.intValue = 0 }
            fun status() = compose.onNodeWithTag("keepass-list-status", useUnmergedTree = true)
            val loadingHeight = status().fetchSemanticsNode().size.height
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            for (value in listOf(1, 2)) {
                compose.runOnIdle { mode.intValue = value }
                assertEquals("State $value must not inflate the status at font scale $scale",
                    loadingHeight, status().fetchSemanticsNode().size.height)
                val action = compose.onNodeWithTag(if (value == 1) "keepass-list-unlock" else "keepass-list-retry")
                action.assertHasClickAction()
                // Tap the leading edge, well away from the trailing action label.
                action.performTouchInput { click(androidx.compose.ui.geometry.Offset(5f, center.y)) }
                if (InstrumentationRegistry.getArguments().getString("captureKeePassList") == "true") {
                    val label = if (value == 1) "locked" else "failed"
                    java.io.File(context.filesDir, "keepass-bar-$label-$scale.png").outputStream().use {
                        status().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
        assertEquals(2, unlocks)
        assertEquals(2, retries)
    }
}
