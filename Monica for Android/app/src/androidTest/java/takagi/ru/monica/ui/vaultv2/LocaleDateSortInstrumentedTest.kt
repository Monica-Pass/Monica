package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import takagi.ru.monica.data.*
import takagi.ru.monica.localization.LocaleTestActivity
import takagi.ru.monica.ui.components.ExpressiveLazyListScrollbar
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager

/** Real sort sheet, persisted selection, production sorter/section projection and drag scrollbar. */
@RunWith(Parameterized::class)
class LocaleDateSortInstrumentedTest(private val language: Language) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun languages() = listOf(arrayOf(Language.ARABIC), arrayOf(Language.FRENCH), arrayOf(Language.ENGLISH))
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = SettingsManager(context)
    private val compose = createAndroidComposeRule<LocaleTestActivity>()
    private lateinit var old: AppSettings
    private lateinit var locale: Locale
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object: ExternalResource() {
        override fun before() = runBlocking {
            val user = InstrumentationRegistry.getArguments().getString("localeTestUser")!!.toInt()
            require(user > 0 && android.os.Process.myUid() / 100000 == user)
            old = manager.settingsFlow.first(); locale = Locale.getDefault(); manager.updateLanguage(language)
        }
        override fun after() { runBlocking { manager.updateLanguage(old.language); manager.updateVaultListSort(old.vaultListSort) }; Locale.setDefault(locale) }
    }).around(compose)

    @Test fun allDateModesSelectPersistRenderAndDragWithoutLocaleDependentKeys() {
        var sort by mutableStateOf(VaultListSort.TITLE_ASC)
        var sheet by mutableStateOf(false)
        val base = java.time.Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val input = buildVaultV2PasswordItems((1L..60).map { id -> PasswordEntry(id = id, title = "حساب $id", username = "sample", password = "", website = "", createdAt = Date(base + id * 86_400_000L), updatedAt = Date(base + (61-id) * 86_400_000L)) })
        compose.setContent {
            assertEquals(if (language == Language.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr, LocalLayoutDirection.current)
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MonicaTheme(darkTheme = true) {
                    val rows = sortVaultV2Items(input, sort)
                    val sections = buildVaultV2SortedSections(rows, sort)
                    Column(Modifier.fillMaxSize()) {
                        Button({ sheet = true }, Modifier.testTag("open_sort")) { Text("Sort") }
                        val state = rememberLazyListState()
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("date_list")) {
                            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                                items(rows.size, key = { rows[it].key }) { i -> Text(rows[i].key, Modifier.fillMaxWidth().height(72.dp).testTag("sorted_row_$i")) }
                            }
                            ExpressiveLazyListScrollbar(state, Modifier.align(Alignment.CenterEnd).testTag("date_drag"), labelForIndex = { i ->
                                val row = rows.getOrNull(i); sections.firstOrNull { row in it.second }?.first
                            })
                        }
                    }
                    if (sheet) VaultV2SortSheet(sort, { chosen ->
                        runBlocking { manager.updateVaultListSort(chosen) }; sort = chosen; sheet = false
                    }, { sheet = false })
                }
            }
        }
        for (mode in VaultListSort.entries.filterNot { it.isAlphabetical }) {
            compose.onNodeWithTag("open_sort").performClick()
            compose.onNodeWithTag("vault_sort_${mode.name}").performScrollTo().assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals(mode, runBlocking { SettingsManager(context).settingsFlow.first() }.vaultListSort)
            val sorted = sortVaultV2Items(input, mode)
            val times = sorted.map { it.sortTimestamp(mode)!! }
            assertEquals(if (mode.descending) times.sortedDescending() else times.sorted(), times)
            val sections = buildVaultV2SortedSections(sorted, mode)
            assertEquals(sorted.map { it.key }, sections.flatMap { it.second }.map { it.key })
            assertTrue(sections.all { it.first.matches(Regex("[0-9]{4}/[0-9]{2}/[0-9]{2}")) })
            val drag = compose.onNodeWithTag("date_drag")
            val list = compose.onNodeWithTag("date_list").fetchSemanticsNode().boundsInRoot
            assertEquals(language == Language.ARABIC, drag.fetchSemanticsNode().boundsInRoot.center.x < list.center.x)
            drag.performTouchInput { down(center.copy(y = 15f)); moveTo(center, delayMillis = 300) }
            compose.waitForIdle()
            val label = compose.onNodeWithTag("vault_scrollbar_label").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Date label remains on screen: $language / $mode / $label", label.left >= list.left && label.right <= list.right)
            File(context.filesDir, "arabic-sort-${language.name}-${mode.name}.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            drag.performTouchInput { up() }
        }
        compose.onNodeWithTag("open_sort").performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("vault_sort_sheet").assertDoesNotExist()
        assertEquals(VaultListSort.UPDATED_ASC, sort)
    }
}
