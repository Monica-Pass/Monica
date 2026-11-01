package takagi.ru.monica.keepass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.PasswordHistorySection
import takagi.ru.monica.ui.theme.MonicaTheme
import java.text.DateFormat

class KeePassHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualHistorySectionShowsNativeVersionCountAndModificationTime() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = KeePassHistoryStabilityTest()::fixture) { _, service, id, _, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = KeePassMonicaPasswordStore(service)
            val history = store.history(store.refresh(id).rows.single().id).first()
            val native = mutableStateOf(true)
            val visibility = mutableStateMapOf<Long, Boolean>()
            compose.setContent { MonicaTheme {
                PasswordHistorySection(history, visibility, context, {}, {}, nativeKeePass = native.value)
            } }
            compose.onNodeWithText(context.getString(R.string.keepass_native_history_subtitle, 2)).assertIsDisplayed()
            compose.onAllNodesWithText(context.getString(R.string.password_history_description)).assertCountEquals(0)
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            history.forEach { compose.onNodeWithText(context.getString(R.string.modified_field, format.format(it.lastUsedAt))).assertIsDisplayed() }
            compose.onNodeWithText(context.getString(R.string.password_history_latest)).assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("captureHistory") == "true") {
                java.io.File(context.filesDir, "kdbx-history-324.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.runOnIdle { native.value = false }
            compose.onNodeWithText(context.getString(R.string.password_history_description)).assertIsDisplayed()
            history.forEach { compose.onNodeWithText(context.getString(R.string.password_history_last_used, format.format(it.lastUsedAt))).assertIsDisplayed() }
        }
    }
}
