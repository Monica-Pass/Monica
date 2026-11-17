package takagi.ru.monica.ui.note

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Field
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.bitwarden.BitwardenVaultDao
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager

/**
 * Real MainActivity, NoteListScreen and Room queries. Only DAO return values are decorated to
 * count NoteListScreen's subscriptions; ViewModel/repository subscriptions are not counted.
 * Requires an isolated perf328 app and -e noteFlowSynthetic true -e noteFlowPassword <fixture password>.
 */
@RunWith(AndroidJUnit4::class)
class NoteMetadataSubscriptionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val englishContext by lazy {
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
    }
    private val password get() = requireNotNull(
        InstrumentationRegistry.getArguments().getString("noteFlowPassword")
    ) { "Supply the isolated app's synthetic master password" }
    private val marker = "Flow ${UUID.randomUUID()}"
    private lateinit var database: PasswordDatabase
    private lateinit var settings: SettingsManager
    private lateinit var oldSettings: AppSettings
    private lateinit var oldFilter: SavedCategoryFilterState
    private var scenario: ActivityScenario<MainActivity>? = null
    private var category: Category? = null
    private var note: SecureItem? = null
    private val replacedDaos = mutableListOf<Pair<Field, Any>>()
    private val counts = linkedMapOf<String, Counts>()

    private class Counts {
        val factories = AtomicInteger()
        val starts = AtomicInteger()
        val active = AtomicInteger()
        override fun toString() = "factory=${factories.get()},start=${starts.get()},active=${active.get()}"
    }

    private fun <T> track(name: String, source: Flow<T>): Flow<T> {
        val fromScreen = Thread.currentThread().stackTrace.any {
            it.className == "takagi.ru.monica.ui.screens.NoteListScreenKt" &&
                it.methodName == "NoteListScreen"
        }
        if (!fromScreen) return source
        val counter = counts.getValue(name)
        counter.factories.incrementAndGet()
        return source.onStart {
            counter.starts.incrementAndGet()
            counter.active.incrementAndGet()
        }.onCompletion { counter.active.decrementAndGet() }
    }

    private fun replaceDao(name: String, value: Any) {
        val field = database.javaClass.getDeclaredField(name).apply { isAccessible = true }
        replacedDaos += field to requireNotNull(field.get(database))
        field.set(database, value)
    }

    @Before fun prepare() = runBlocking {
        check(context.packageName.contains("perf328")) { "Refuse to use a normal application data store" }
        check(InstrumentationRegistry.getArguments().getString("noteFlowSynthetic") == "true")
        val ready = SecureStorageStartup.prepareForEntry(context)
        check(ready is SecureStartupResult.Ready) { "The isolated secure store must be usable" }
        if (!ready.manager.isMasterPasswordSet()) {
            check(ready.manager.setMasterPassword(password))
        } else {
            check(ready.manager.unlockVaultWithPassword(password)) { "Synthetic fixture password mismatch" }
        }
        database = PasswordDatabase.getDatabase(context)
        settings = SettingsManager(context)
        oldSettings = settings.settingsFlow.first()
        oldFilter = settings.categoryFilterStateFlow(SettingsManager.CategoryFilterScope.NOTE).first()
        settings.updateLanguage(Language.ENGLISH)
        settings.updateQuickSetupCompleted(true)
        settings.updateBiometricEnabled(false)
        // Only the explicitly opted-in synthetic perf328 package is used. Allow a real
        // diagnostic screenshot of the lock cover instead of FLAG_SECURE's black image.
        settings.updateScreenshotProtectionEnabled(false)
        settings.updateAutoLockMinutes(5)
        settings.updateQuickSetupNavigation(
            listOf(BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.NOTES),
            listOf(BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.NOTES),
        )
        val categoryId = database.categoryDao().insert(Category(name = "$marker category"))
        category = Category(id = categoryId, name = "$marker category")
        val initial = SecureItem(
            itemType = ItemType.NOTE, title = "$marker note", categoryId = categoryId,
            itemData = Json.encodeToString(NoteData(content = "Synthetic subscription test content")),
        )
        note = initial.copy(id = database.secureItemDao().insertItem(initial))
        settings.updateCategoryFilterState(
            SettingsManager.CategoryFilterScope.NOTE,
            SavedCategoryFilterState(type = "custom", primaryId = categoryId),
        )
        listOf("category", "keepass", "mdbx", "bitwarden").forEach { counts[it] = Counts() }
        val categories = database.categoryDao()
        replaceDao("_categoryDao", object : CategoryDao by categories {
            override fun getAllCategories(): Flow<List<Category>> = track("category", categories.getAllCategories())
        })
        val keepass = database.localKeePassDatabaseDao()
        replaceDao("_localKeePassDatabaseDao", object : LocalKeePassDatabaseDao by keepass {
            override fun getAllDatabases(): Flow<List<LocalKeePassDatabase>> = track("keepass", keepass.getAllDatabases())
        })
        val mdbx = database.localMdbxDatabaseDao()
        replaceDao("_localMdbxDatabaseDao", object : LocalMdbxDatabaseDao by mdbx {
            override fun getAvailableDatabases(): Flow<List<LocalMdbxDatabase>> = track("mdbx", mdbx.getAvailableDatabases())
        })
        val bitwarden = database.bitwardenVaultDao()
        replaceDao("_bitwardenVaultDao", object : BitwardenVaultDao by bitwarden {
            override fun getAllVaultsFlow(): Flow<List<BitwardenVault>> = track("bitwarden", bitwarden.getAllVaultsFlow())
        })
        SessionManager.markLocked()
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        scenario?.close()
        if (::database.isInitialized) {
            replacedDaos.asReversed().forEach { (field, original) -> field.set(database, original) }
            runBlocking {
                note?.let { database.secureItemDao().deleteItemById(it.id) }
                category?.let { database.categoryDao().delete(it) }
            }
        }
        if (::oldSettings.isInitialized) runBlocking {
            settings.updateLanguage(oldSettings.language)
            settings.updateQuickSetupCompleted(oldSettings.quickSetupCompleted)
            settings.updateBiometricEnabled(oldSettings.biometricEnabled)
            settings.updateScreenshotProtectionEnabled(oldSettings.screenshotProtectionEnabled)
            settings.updateAutoLockMinutes(oldSettings.autoLockMinutes)
            settings.updateBottomNavOrder(oldSettings.bottomNavOrder)
            BottomNavContentTab.entries.forEach {
                settings.updateBottomNavVisibility(it, oldSettings.bottomNavVisibility.isVisible(it))
            }
            settings.updateAutoHideBottomNavWhenSingleTab(oldSettings.autoHideBottomNavWhenSingleTab)
            settings.updateCategoryFilterState(SettingsManager.CategoryFilterScope.NOTE, oldFilter)
        }
        if (::database.isInitialized) SessionManager.markLocked()
    }

    private fun tab(label: Int) = compose.onNode(
        hasText(englishContext.getString(label)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    )

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun openNotes() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(password)
        compose.onAllNodes(hasSetTextAction())[0].performImeAction()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(englishContext.getString(R.string.nav_notes_short)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes().isNotEmpty()
        }
        tab(R.string.nav_notes_short).performClick()
        awaitText(requireNotNull(note).title)
        awaitText(requireNotNull(category).name)
        compose.waitUntil(5_000) { counts.values.all { it.active.get() > 0 } }
    }

    @Test fun searchRecompositionKeepsOneSubscriptionPerMetadataQuery() {
        openNotes()
        compose.onNodeWithContentDescription(englishContext.getString(R.string.search)).performClick()
        repeat(12) { index ->
            compose.onNode(hasSetTextAction()).performTextReplacement(if (index % 2 == 0) "Flow" else marker)
            awaitText(requireNotNull(note).title)
        }
        println("note_metadata_search_12=$counts")
        counts.forEach { (name, counter) ->
            assertEquals("$name metadata Flow must be reused across search recompositions", 1, counter.factories.get())
            assertEquals("$name query must not restart for each keystroke", 1, counter.starts.get())
            assertEquals("$name must still receive Room invalidations", 1, counter.active.get())
        }
    }

    @Test fun roomEditsRefreshVisibleMetadataAndReenteringDoesNotLeakCollectors() {
        openNotes()
        val renamed = requireNotNull(category).copy(name = "$marker renamed")
        runBlocking { database.categoryDao().update(renamed) }
        category = renamed
        awaitText(renamed.name)
        val changed = requireNotNull(note).copy(title = "$marker changed")
        runBlocking { database.secureItemDao().updateItem(changed) }
        note = changed
        awaitText(changed.title)
        tab(R.string.nav_authenticator_short).performClick()
        compose.waitUntil(5_000) { counts.values.all { it.active.get() == 0 } }
        val hiddenChange = changed.copy(title = "$marker changed while hidden")
        runBlocking { database.secureItemDao().updateItem(hiddenChange) }
        note = hiddenChange
        tab(R.string.nav_notes_short).performClick()
        awaitText(hiddenChange.title)
        awaitText(renamed.name)
        println("note_metadata_reentry=$counts")
        counts.forEach { (name, counter) ->
            assertEquals("$name must have one query for each of the two screen entries", 2, counter.starts.get())
            assertEquals("$name must not leak a collector after tab navigation", 1, counter.active.get())
        }
    }

    @Test fun backgroundLockKeepsTheCachedNoteHiddenOnReturn() {
        openNotes()
        val title = requireNotNull(note).title
        val evidence = File(context.filesDir, "flow328-privacy-frames/${UUID.randomUUID()}")
        check(evidence.mkdirs())
        requireNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { SessionManager.markLocked() }
        assertTrue(!SessionManager.isUnlocked.value)
        compose.mainClock.autoAdvance = false
        try {
            requireNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
            repeat(12) { frameIndex ->
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                awaitNativeFrameCommit()
                val observed = recordLockedFrame(evidence, frameIndex, title)
                // MainActivity keeps the outgoing NavHost tree under an opaque transition cover.
                // Assert its actual public surface on every committed frame; do not mistake the
                // retained internal test semantics for Android accessibility or rendered pixels.
                assertEquals("Frame $frameIndex must belong to the target app", context.packageName, observed.activePackage)
                assertTrue("Frame $frameIndex must be visible in the resumed target window", observed.windowFocused && observed.resumed)
                assertTrue("Frame $frameIndex screenshot must not be obscured by FLAG_SECURE", !observed.secureFlag)
                assertEquals("Frame $frameIndex exposed the synthetic title/body through system accessibility", 0, observed.noteMatches)
                assertTrue("Frame $frameIndex must expose a loading indicator or the authentication editor", observed.progressNodes > 0 || observed.passwordEditors > 0)
                assertTrue("Frame $frameIndex must remain locked", !SessionManager.isUnlocked.value)
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.waitUntil(5_000) { counts.values.all { it.active.get() == 0 } }
        println("note_metadata_locked_return_frames=12 evidence=${evidence.absolutePath} counts=$counts")
    }

    private fun awaitNativeFrameCommit() {
        val committed = CountDownLatch(1)
        requireNotNull(scenario).onActivity { activity ->
            val decor = activity.window.decorView
            check(decor.isHardwareAccelerated) { "Frame-commit evidence requires a hardware-accelerated window" }
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.postInvalidateOnAnimation()
        }
        assertTrue("The target must commit a real rendered frame before observation", committed.await(5, TimeUnit.SECONDS))
    }

    private data class LockedFrame(
        val activePackage: String?,
        val windowFocused: Boolean,
        val resumed: Boolean,
        val secureFlag: Boolean,
        val noteMatches: Int,
        val progressNodes: Int,
        val passwordEditors: Int,
    )

    private fun recordLockedFrame(directory: File, frameIndex: Int, title: String): LockedFrame {
        val timestamp = SystemClock.elapsedRealtime()
        val virtualTime = compose.mainClock.currentTime
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Frame $frameIndex has no native screenshot" }
        val image = File(directory, "frame-$frameIndex.png")
        try {
            image.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { screenshot.recycle() }
        val internalTree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 100)
        File(directory, "frame-$frameIndex-compose.txt").writeText(internalTree)
        val systemTree = StringBuilder()
        var targetNoteMatches = 0
        var progressNodes = 0
        var passwordEditors = 0
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val text = if (node.isPassword) "<password-redacted>" else node.text?.toString().orEmpty()
            val exposed = "$text ${node.contentDescription?.toString().orEmpty()}"
            if (exposed.contains(title) || exposed.contains("Synthetic subscription test content")) targetNoteMatches++
            if (node.isVisibleToUser && !bounds.isEmpty) {
                if (node.className == "android.widget.ProgressBar") progressNodes++
                if (node.isEditable && node.isPassword) passwordEditors++
            }
            systemTree.append("  ".repeat(depth)).append(node.className)
                .append(" visible=").append(node.isVisibleToUser)
                .append(" clickable=").append(node.isClickable)
                .append(" bounds=").append(bounds)
                .append(" text=").append(text)
                .append(" description=").append(node.contentDescription).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        check(instrumentation.uiAutomation.clearCache()) { "Refresh the actual Android accessibility tree before each observation" }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val activePackage = root?.packageName?.toString()
        if (activePackage == context.packageName && root != null) describe(root, 0)
        else systemTree.append("Active root is not the target: ").append(activePackage)
        File(directory, "frame-$frameIndex-accessibility.txt").writeText(systemTree.toString())
        var windowState = "Activity unavailable"
        var windowFocused = false
        var resumed = false
        var secureFlag = true
        scenario?.onActivity { activity ->
            windowFocused = activity.hasWindowFocus()
            resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED
            secureFlag = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            windowState = "focus=$windowFocused lifecycle=${activity.lifecycle.currentState}" +
                " decorVisibility=${activity.window.decorView.visibility}" +
                " secureFlag=$secureFlag"
        }
        val summary = "frameIndex=$frameIndex elapsedRealtime=$timestamp virtualTime=$virtualTime" +
            " virtualTimeAfter=${compose.mainClock.currentTime} unlocked=${SessionManager.isUnlocked.value}" +
            " lockGeneration=${SessionManager.lockGeneration.value} targetPackage=${context.packageName}" +
            " activePackage=$activePackage targetNoteMatches=$targetNoteMatches" +
            " systemProgressNodes=$progressNodes systemPasswordEditors=$passwordEditors screenshot=${image.absolutePath}" +
            " $windowState counts=$counts\n" +
            "internalNotePresent=${internalTree.contains(title)}; outgoing internal semantics may remain under the cover until authentication navigation settles.\n"
        File(directory, "frame-$frameIndex-summary.txt").writeText(summary)
        println("note_metadata_locked_frame=$summary")
        assertEquals("Observation must not skip ahead through virtual Compose frames", virtualTime, compose.mainClock.currentTime)
        return LockedFrame(activePackage, windowFocused, resumed, secureFlag, targetNoteMatches, progressNodes, passwordEditors)
    }
}
