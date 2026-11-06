package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.KeePassGroupInfo

/** Uses the production sheet with synthetic data; never opens a user's database. */
class MoveFolderScopeTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<Pair<UnifiedMoveCategoryTarget, UnifiedMoveAction>>()

    private fun setSheetContent(content: @Composable () -> Unit) {
        compose.setContent(content)
        awaitSheet()
    }

    private fun awaitSheet() {
        // The modal owns a separate window. Absence assertions are meaningful only
        // after that window has registered its actual semantics tree.
        compose.waitUntil {
            compose.onAllNodesWithTag("transfer_confirm")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "move-folder-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Composable private fun Sheet(
        categories: List<Category> = emptyList(),
        initial: UnifiedMoveInitialSource = UnifiedMoveInitialSource.MonicaLocal,
        keepass: List<LocalKeePassDatabase> = emptyList(),
        mdbx: List<LocalMdbxDatabase> = emptyList(),
        bitwarden: List<BitwardenVault> = emptyList(),
        groups: (Long) -> Flow<List<KeePassGroupInfo>> = { flowOf(emptyList()) },
        folders: (Long) -> Flow<List<MdbxStoredFolderEntry>> = { flowOf(emptyList()) },
        bwFolders: (Long) -> Flow<List<BitwardenFolder>> = { flowOf(emptyList()) },
        archive: Boolean = false,
        local: Boolean = true,
        before: ((UnifiedMoveCategoryTarget, UnifiedMoveAction, () -> Unit) -> Unit)? = null,
        dismiss: () -> Unit = {},
        selected: ((UnifiedMoveCategoryTarget, UnifiedMoveAction) -> Unit)? = null,
    ) {
        UnifiedMoveToCategoryBottomSheet(
            visible = true, onDismiss = dismiss, categories = categories, initialSource = initial,
            keepassDatabases = keepass, mdbxDatabases = mdbx, bitwardenVaults = bitwarden,
            getKeePassGroups = groups, getMdbxFolders = folders, getBitwardenFolders = bwFolders,
            allowCopy = true, allowArchiveTarget = archive, allowLocalTarget = local, onBeforeTargetSelected = before,
            onTargetSelected = selected ?: { target, action -> calls += target to action },
        )
    }

    @Test fun localMoveAndCopyExcludeMdbxMirrorsButKeepEmptyAndLinkedFolders() {
        val categories = listOf(
            Category(1, "Empty local"),
            Category(2, "Linked local", bitwardenVaultId = 8, bitwardenFolderId = "linked"),
            Category(3, "Shared name"), Category(4, "Shared name"),
            Category(5, "Ghost active", mdbxDatabaseId = 20),
            Category(6, "Ghost unavailable", mdbxDatabaseId = 99),
            Category(7, "Shared name", mdbxDatabaseId = 20),
        )
        setSheetContent { MonicaTheme { Sheet(categories) } }
        compose.onNodeWithText("Ghost active").assertDoesNotExist()
        compose.onNodeWithText("Ghost unavailable").assertDoesNotExist()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Shared name"))
        compose.onAllNodesWithText("Shared name").assertCountEquals(2)
        compose.onAllNodesWithText("Shared name")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Shared name")[1].assertIsDisplayed()
        capture("local-duplicates")
        list.performScrollToNode(hasText("Empty local"))
        compose.onNodeWithText("Empty local").assertIsDisplayed().performClick()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.onNodeWithTag("transfer_action_copy").performClick()
        list.performScrollToNode(hasText("Linked local"))
        compose.onNodeWithText("Linked local").assertIsDisplayed().performClick()
        capture("local-copy")
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf(
                UnifiedMoveCategoryTarget.MonicaCategory(1) to UnifiedMoveAction.MOVE,
                UnifiedMoveCategoryTarget.MonicaCategory(2) to UnifiedMoveAction.COPY,
            ), calls)
            assertEquals(7, categories.size)
            assertEquals(3, categories.count { it.mdbxDatabaseId != null })
        }
    }

    @Test fun reclassifiedOrRemovedLocalSelectionCannotConfirmOrResumePreflight() {
        val categories = mutableStateOf(listOf(Category(1, "Chosen local")))
        var proceed: (() -> Unit)? = null
        setSheetContent { MonicaTheme { Sheet(categories.value, before = { _, _, next -> proceed = next }) } }
        compose.onNodeWithText("Chosen local").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.runOnIdle {
            assertNotNull(proceed)
            categories.value = listOf(categories.value.single().copy(mdbxDatabaseId = 99))
        }
        compose.onNodeWithTag("transfer_confirm").assertIsNotEnabled()
        compose.onNodeWithText("Chosen local").assertDoesNotExist()
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
        compose.runOnIdle { categories.value = listOf(Category(1, "Chosen local")) }
        compose.onNodeWithTag("transfer_confirm").assertIsNotEnabled()
        compose.onNodeWithText("Chosen local").performScrollTo().performClick()
        compose.runOnIdle { categories.value = emptyList() }
        compose.onNodeWithTag("transfer_confirm").assertIsNotEnabled()
        capture("removed-target")
    }

    @Test fun changingSourceRevokesPendingConfirmationEvenAfterReselectingSameFolder() {
        var proceed: (() -> Unit)? = null
        setSheetContent { MonicaTheme { Sheet(
            listOf(Category(1, "Chosen local")),
            mdbx = listOf(LocalMdbxDatabase(2, "Other database", "synthetic")),
            before = { _, _, next -> proceed = next },
        ) } }
        compose.onNodeWithText("Chosen local").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.onNodeWithText("Other database").performScrollTo().performClick()
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Monica").performScrollTo().performClick()
        compose.onNodeWithText("Chosen local").performScrollTo().performClick()
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
    }

    @Test fun pendingConfirmationIsRevokedByActionChangesAndSheetDisposal() {
        val visible = mutableStateOf(true)
        var proceed: (() -> Unit)? = null
        setSheetContent { MonicaTheme { if (visible.value) Sheet(
            listOf(Category(1, "Chosen local")), before = { _, _, next -> proceed = next },
        ) } }
        compose.onNodeWithText("Chosen local").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.onNodeWithTag("transfer_action_copy").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.move)).performClick()
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
        compose.runOnIdle { visible.value = true }
        awaitSheet()
        compose.onNodeWithTag("transfer_confirm").assertIsNotEnabled()
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
    }

    @Test fun noAllowedSourceDoesNotFallBackToLocalFolders() {
        setSheetContent { MonicaTheme { Sheet(listOf(Category(1, "Local hidden")), local = false) } }
        compose.onNodeWithText("Local hidden").assertDoesNotExist()
        compose.onNodeWithTag("transfer_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun validDelayedConfirmationKeepsOriginalSourceCallbackAndRunsOnce() {
        val sourceSelection = mutableStateOf(1)
        val executedSources = mutableListOf<Int>()
        var proceed: (() -> Unit)? = null
        setSheetContent {
            val confirmedSource = sourceSelection.value
            MonicaTheme { Sheet(listOf(Category(1, "Chosen local")),
                before = { _, _, next -> proceed = next },
                selected = { target, action -> executedSources += confirmedSource; calls += target to action },
            ) }
        }
        compose.onNodeWithText("Chosen local").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_action_copy").performClick()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.runOnIdle { sourceSelection.value = 2 }
        compose.runOnIdle {
            proceed!!.invoke()
            proceed!!.invoke()
            assertEquals(listOf(1), executedSources)
            assertEquals(listOf(UnifiedMoveCategoryTarget.MonicaCategory(1) to UnifiedMoveAction.COPY), calls)
        }
    }

    @Test fun pendingKeePassConfirmationRechecksConflictAndCannotBeReplayed() {
        val database = mutableStateOf(LocalKeePassDatabase(
            id = 1, name = "Remote fixture", filePath = "synthetic",
            sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV,
            workingCopyPath = "synthetic", lastSyncStatus = KeePassSyncStatus.IN_SYNC,
        ))
        var proceed: (() -> Unit)? = null
        setSheetContent { MonicaTheme { Sheet(
            initial = UnifiedMoveInitialSource.KeePassDatabase(1), keepass = listOf(database.value),
            before = { _, _, next -> proceed = next },
        ) } }
        compose.onNodeWithText("Remote fixture").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.runOnIdle { database.value = database.value.copy(lastSyncStatus = KeePassSyncStatus.CONFLICT) }
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.keepass_operation_unavailable_title)).assertIsDisplayed()
        capture("keepass-conflict")
        compose.runOnIdle { database.value = database.value.copy(lastSyncStatus = KeePassSyncStatus.IN_SYNC) }
        compose.runOnIdle { proceed!!.invoke(); assertTrue(calls.isEmpty()) }
    }

    @Test fun missingNativeFolderRevokesSelection() {
        val folders = MutableStateFlow(listOf(MdbxStoredFolderEntry("chosen", null, "Chosen native", "Chosen native", 0)))
        setSheetContent { MonicaTheme { Sheet(
            initial = UnifiedMoveInitialSource.MdbxDatabase(1),
            mdbx = listOf(LocalMdbxDatabase(1, "Native database", "synthetic")), folders = { folders },
        ) } }
        compose.onNodeWithText("Chosen native").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_confirm").assertIsEnabled()
        compose.runOnIdle { folders.value = emptyList() }
        compose.onNodeWithTag("transfer_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun keepassSwitchDoesNotAssignPreviousGroupToDelayedDatabase() = delayedSwitch("keepass")
    @Test fun mdbxSwitchDoesNotAssignPreviousFolderToDelayedDatabase() = delayedSwitch("mdbx")
    @Test fun bitwardenSwitchDoesNotAssignPreviousFolderToDelayedVault() = delayedSwitch("bitwarden")

    private fun delayedSwitch(kind: String) {
        val gate = CompletableDeferred<Unit>()
        fun <T> delayed(id: Long, value: T): Flow<List<T>> = flow {
            if (id == 2L) gate.await()
            emit(listOf(value))
        }
        fun label(id: Long) = if (id == 1L) "Only source A" else "Only source B"
        setSheetContent { MonicaTheme { Sheet(
            initial = when (kind) {
                "keepass" -> UnifiedMoveInitialSource.KeePassDatabase(1)
                "mdbx" -> UnifiedMoveInitialSource.MdbxDatabase(1)
                else -> UnifiedMoveInitialSource.BitwardenVault(1)
            },
            keepass = if (kind == "keepass") listOf(1L, 2L).map { LocalKeePassDatabase(it, "Database $it", "synthetic") } else emptyList(),
            mdbx = if (kind == "mdbx") listOf(1L, 2L).map { LocalMdbxDatabase(it, "Database $it", "synthetic") } else emptyList(),
            bitwarden = if (kind == "bitwarden") listOf(1L, 2L).map { BitwardenVault(id = it, email = "fixture$it@example.invalid", displayName = "Database $it") } else emptyList(),
            groups = { id -> delayed(id, KeePassGroupInfo(label(id), label(id), "group-$id")) },
            folders = { id -> delayed(id, MdbxStoredFolderEntry("folder-$id", null, label(id), label(id), 0)) },
            bwFolders = { id -> delayed(id, BitwardenFolder(vaultId = id, bitwardenFolderId = "folder-$id", name = label(id), revisionDate = "synthetic")) },
        ) } }
        try {
            compose.onNodeWithText("Only source A").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Database 2").performScrollTo().performClick()
            compose.waitForIdle()
            capture("$kind-pending")
            compose.onNodeWithText("Only source A").assertDoesNotExist()
            compose.onNodeWithText("Only source B").assertDoesNotExist()
            compose.runOnIdle { gate.complete(Unit) }
            compose.onNodeWithText("Only source B").performScrollTo().performClick()
            compose.onNodeWithTag("transfer_action_copy").performClick()
            capture("$kind-ready")
            compose.onNodeWithTag("transfer_confirm").performClick()
            compose.runOnIdle {
                val target = when (kind) {
                    "keepass" -> UnifiedMoveCategoryTarget.KeePassGroupTarget(2, "Only source B", "group-2")
                    "mdbx" -> UnifiedMoveCategoryTarget.MdbxFolderTarget(2, "folder-2")
                    else -> UnifiedMoveCategoryTarget.BitwardenFolderTarget(2, "folder-2")
                }
                assertEquals(listOf(target to UnifiedMoveAction.COPY), calls)
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun largeTextDarkModeKeepsRootArchiveAndCancelUsable() {
        val visible = mutableStateOf(true)
        setSheetContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MonicaTheme(darkTheme = true) { if (visible.value) Sheet(archive = true, dismiss = { visible.value = false }) }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.category_none)).performScrollTo().performClick()
        compose.onNodeWithTag("transfer_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.archive_page_title)).performScrollTo().performClick()
        capture("dark-large-archive")
        compose.onNodeWithTag("transfer_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(UnifiedMoveCategoryTarget.Uncategorized to UnifiedMoveAction.MOVE,
                UnifiedMoveCategoryTarget.MonicaCategory(UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID) to UnifiedMoveAction.MOVE), calls)
        }
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(2, calls.size) }
    }
}
