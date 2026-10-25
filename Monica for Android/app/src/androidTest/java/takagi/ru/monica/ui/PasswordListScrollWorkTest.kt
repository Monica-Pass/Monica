package takagi.ru.monica.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Exercises the production scroll effects with an isolated, empty in-memory vault. */
class PasswordListScrollWorkTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var list: LazyListState
    private val compositions = AtomicInteger()
    private val visibility = mutableListOf<Boolean>()
    private var topRequest by mutableIntStateOf(0)
    private var showList by mutableStateOf(true)
    private val keys = List(300) { "entry_$it" }

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        model = PasswordViewModel(
            PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao()),
            SecurityManager(context), strings = AppLocaleStringResolver(context)
        )
        compose.setContent {
            if (showList) {
                val fastRequest by model.fastScrollRequestKey.collectAsState()
                // A fast-scroll request samples the progress. Normal scrolling must not
                // subscribe the whole list body to every change of that same progress.
                list = rememberPasswordListLazyListState(
                    model, keys, topRequest, fastRequest, model.fastScrollProgress.value,
                    true, { visibility.add(it) }
                )
                SideEffect { compositions.incrementAndGet() }
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(keys.size, key = { keys[it] }) { index ->
                        Text("Synthetic row $index", Modifier.height(120.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        compose.runOnIdle { showList = false }
        compose.waitForIdle()
        model.viewModelScope.cancel()
        database.close()
    }

    private fun scroll(index: Int, offset: Int = 0) {
        runBlocking { withContext(Dispatchers.Main) { list.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }

    @Test fun pixelScrollingDoesNotRecomposeThePage() {
        val before = compositions.get()
        repeat(24) { scroll(0, (it + 1) * 2) }
        val recompositions = compositions.get() - before
        android.util.Log.i("PasswordScrollWork", "pixelSteps=24 pageRecompositions=$recompositions")
        assertEquals("Scroll effects must not invalidate the containing page", 0, recompositions)
        compose.runOnIdle {
            assertEquals(48, model.passwordListScrollOffset.value)
            assertFalse(visibility.last())
        }
    }

    @Test fun visibilityFastScrollReturnToTopAndDetailRestoreKeepTheirBehavior() {
        scroll(80, 17)
        compose.runOnIdle {
            assertTrue(visibility.last())
            assertEquals(80, model.passwordListScrollIndex.value)
            assertEquals(17, model.passwordListScrollOffset.value)
            showList = false
        }
        compose.waitForIdle()
        compose.runOnIdle { showList = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(80, list.firstVisibleItemIndex)
            assertEquals(17, list.firstVisibleItemScrollOffset)
            model.requestFastScroll(.5f)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(150, list.firstVisibleItemIndex); topRequest++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertFalse(visibility.last())
        }
    }
}
