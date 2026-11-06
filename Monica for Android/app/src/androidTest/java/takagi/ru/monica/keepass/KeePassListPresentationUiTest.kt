package takagi.ru.monica.keepass

import android.app.Application
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumbBanner
import takagi.ru.monica.ui.password.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

class KeePassListPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var selected by mutableLongStateOf(0)
    private var transition: KeePassListTransition? = null
    private var read: KeePassListRead? = null
    private var clickCount = 0
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun show(model: PasswordViewModel, local: LocalKeePassViewModel, id: Long) {
        selected = id
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner,
            LocalDensity provides Density(LocalDensity.current.density, 1.5f)) { MonicaTheme {
            val state = rememberKeePassListRead(selected, model, local)
            val preview = rememberKeePassListTransition(state, "Vault $selected", state.snapshot != null)
            SideEffect { read = state; transition = preview }
            Column {
                PasswordQuickFolderBreadcrumbBanner(emptyList(), CategoryFilter.KeePassDatabase(selected), {},
                    keePassFileActions = KeePassFileActions(KeePassFileState.READY, true, true, {}, {}, {}).withListTransition(preview))
                val renderedId = selected
                KeePassRetainedList(state, preview, object : KeePassListContent {
                    @Composable override fun Render() {
                        val canInteract = LocalKeePassListInteraction.current
                        Text("Ready $renderedId", Modifier.testTag("live-list").clickable { if (canInteract()) clickCount++ })
                    }
                })
            }
        } } }
    }

    private suspend fun fixture(empty: Boolean = false, block: suspend (PasswordViewModel, LocalKeePassViewModel, Long, android.net.Uri) -> Unit) {
        val security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword(MASTER))
        KeePassDeleteInvestigationTest().scenario(securityOverride = security, databaseTransform = {
            if (empty) it.modifyParentGroup { copy(entries = emptyList(), groups = emptyList()) } else it
        }) { model, _, id, _, uri ->
            val local = withContext(Dispatchers.Main) { LocalKeePassViewModel(context.applicationContext as Application,
                PasswordDatabase.getDatabase(context).localKeePassDatabaseDao(), security) }
            try { block(model, local, id, uri) } finally {
                withContext(Dispatchers.Main) { local.viewModelScope.cancel() }
            }
        }
    }

    @Test fun lockRemovesPreviewAndAuthenticationReopensOriginalWithoutWrites() = runBlocking {
        fixture { model, local, id, uri ->
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            show(model, local, id)
            compose.waitUntil(30_000) { read?.snapshot != null && transition == null }
            compose.runOnIdle { local.lockKeePassDatabase(id) }
            compose.onNodeWithTag("live-list").assertDoesNotExist()
            compose.onNodeWithTag("keepass-list-unlock").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("incorrect synthetic password")
            compose.onAllNodesWithText(context.getString(takagi.ru.monica.R.string.kp_list_unlock)).filter(hasClickAction()).onLast().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText(context.getString(takagi.ru.monica.R.string.kp_list_auth_failed)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("live-list").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextReplacement(MASTER)
            compose.onAllNodesWithText(context.getString(takagi.ru.monica.R.string.kp_list_unlock)).filter(hasClickAction()).onLast().performClick()
            compose.waitUntil(30_000) { read?.snapshot != null && transition == null }
            compose.onNodeWithTag("live-list").performClick()
            assertEquals(1, clickCount)
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun delayedSelectionKeepsPreviousListAndOnlyLatestSelectionBecomesLive() = runBlocking {
        fixture { model, local, a, _ ->
            KeePassDeleteInvestigationTest().scenario { _, _, b, _, _ ->
                show(model, local, a)
                compose.waitUntil(30_000) { transition == null && read?.snapshot != null }
                val store = model.monicaKeePassStore!!
                val mutex = store.javaClass.getDeclaredField("mutex").apply { isAccessible = true }.get(store) as kotlinx.coroutines.sync.Mutex
                mutex.lock()
                try {
                    compose.runOnIdle { selected = b }
                    compose.waitUntil(5_000) { transition?.previousSource?.databaseId == a }
                    compose.onNodeWithTag("live-list").assertDoesNotExist()
                    assertEquals(a, transition?.previousSource?.databaseId)
                    capture("previous")
                    compose.runOnIdle { selected = a }
                    compose.waitUntil(5_000) { read?.databaseId == a && transition != null }
                    assertEquals(a, transition?.previousSource?.databaseId)
                    capture("target")
                    compose.runOnIdle { selected = b }
                } finally { mutex.unlock() }
                compose.waitUntil(30_000) { read?.databaseId == b && read?.snapshot != null && transition == null }
                compose.onNodeWithText("Ready $a").assertDoesNotExist()
                compose.onNodeWithText("Ready $b").assertHasClickAction()
            }
        }
    }

    @Test fun backgroundClearsPreviewAndDialogBeforeReturning() = runBlocking {
        fixture { model, local, id, _ ->
            show(model, local, id)
            compose.waitUntil(30_000) { transition == null && read?.snapshot != null }
            compose.runOnIdle { local.lockKeePassDatabase(id) }
            compose.onNodeWithTag("keepass-list-unlock").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("transient input")
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            assertNull(transition?.previousSource)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithTag("live-list").assertDoesNotExist()
            compose.onNodeWithTag("keepass-list-unlock").assertExists()
            capture("locked")
        }
    }

    @Test fun failedReadCanRetryAndKeepsTheOriginalBytes() = runBlocking {
        fixture { model, local, id, uri ->
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            context.contentResolver.delete(uri, null, null)
            show(model, local, id)
            compose.waitUntil(30_000) { transition?.failed == true }
            compose.onNodeWithTag("live-list").assertDoesNotExist()
            capture("failed")
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(original) }
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.kp_file_reload)).performClick()
            compose.waitUntil(30_000) { read?.snapshot != null && transition == null }
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun lockingWhileReadIsPendingRejectsItsLateResult() = runBlocking {
        fixture { model, local, id, _ ->
            val store = model.monicaKeePassStore!!
            val mutex = store.javaClass.getDeclaredField("mutex").apply { isAccessible = true }.get(store) as kotlinx.coroutines.sync.Mutex
            mutex.lock()
            try {
                show(model, local, id)
                compose.waitUntil(5_000) { transition != null }
                compose.runOnIdle { local.lockKeePassDatabase(id) }
            } finally { mutex.unlock() }
            compose.waitUntil(5_000) { transition?.locked == true }
            compose.onNodeWithTag("live-list").assertDoesNotExist()
            assertNull(read?.snapshot)
            assertNull(transition?.previousSource)
        }
    }

    @Test fun emptyNativeDatabaseFinishesLoadingWithoutAnyPasswordRows() = runBlocking {
        fixture(empty = true) { model, local, id, uri ->
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            show(model, local, id)
            compose.waitUntil(30_000) { read?.snapshot != null && transition == null }
            assertTrue(read!!.snapshot!!.rows.isEmpty())
            compose.onNodeWithTag("live-list").assertHasClickAction()
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun evictedDecodedDatabaseReopensWithoutBeingMislabelledLocked() = runBlocking {
        fixture { model, local, id, uri ->
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            model.monicaKeePassStore!!.refresh(id)
            suspend fun openOthers(index: Int) {
                KeePassDeleteInvestigationTest().scenario { _, service, otherId, _, _ ->
                    service.openNativeBrowser(otherId).getOrThrow()
                    if (index < 2) openOthers(index + 1) else {
                        takagi.ru.monica.utils.KeePassKdbxService.clearActiveDatabase()
                        assertFalse("Exercise actual decoded-cache eviction", service.isDatabaseUnlocked(id))
                        assertFalse(model.monicaKeePassStore!!.isListAuthenticationRequired(id))
                        show(model, local, id)
                        compose.waitUntil(30_000) { read?.snapshot != null && transition == null }
                        compose.onNodeWithTag("keepass-list-unlock").assertDoesNotExist()
                        compose.onNodeWithTag("live-list").assertHasClickAction()
                    }
                }
            }
            openOthers(0)
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun canceledQueuedPasswordCannotUnlockReopenedDialog() = runBlocking {
        fixture { model, local, id, uri ->
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            show(model, local, id)
            compose.waitUntil(30_000) { transition == null && read?.snapshot != null }
            compose.runOnIdle { local.lockKeePassDatabase(id) }
            val reader = takagi.ru.monica.security.BoundedSecureDataRead
            val permit = reader.javaClass.getDeclaredField("permit").apply { isAccessible = true }
                .get(reader) as kotlinx.coroutines.sync.Semaphore
            permit.acquire()
            var held = true
            try {
                compose.onNodeWithTag("keepass-list-unlock").performClick()
                compose.onNode(hasSetTextAction()).performTextInput(MASTER)
                fun confirm() = compose.onAllNodesWithText(context.getString(takagi.ru.monica.R.string.kp_list_unlock))
                    .filter(hasClickAction()).onLast()
                confirm().performClick()
                confirm().assertIsNotEnabled()
                compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.cancel)).performClick()
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                compose.onNodeWithTag("keepass-list-unlock").performClick()
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNode(hasSetTextAction()).performTextInput("New unconfirmed draft")
                permit.release(); held = false
                withTimeout(10_000) { permit.acquire() }
                permit.release()
                compose.waitForIdle()
                assertTrue(model.monicaKeePassStore!!.isListAuthenticationRequired(id))
                compose.onNodeWithTag("live-list").assertDoesNotExist()
                confirm().assertIsEnabled()
                compose.onNode(hasSetTextAction()).performTextReplacement(MASTER)
                confirm().performClick()
                compose.waitUntil(30_000) { transition == null && read?.snapshot != null }
                assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { if (held) permit.release() }
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureKeePassList") != "true") return
        java.io.File(context.filesDir, "keepass-list-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object { const val MASTER = "Synthetic KeePass switching master 325!" }
}
