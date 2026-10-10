package takagi.ru.monica.steam

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.ui.theme.MonicaTheme

class SteamFolderPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nestedFolderSelectionSurvivesRecompositionAndCopyReturnsItsIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected: UnifiedMoveCategoryTarget? = null
        var action: UnifiedMoveAction? = null
        var loads = 0
        compose.setContent { MonicaTheme { UnifiedMoveToCategoryBottomSheet(
            visible = true, onDismiss = {}, categories = emptyList(), keepassDatabases = emptyList(),
            mdbxDatabases = listOf(LocalMdbxDatabase(id=1, name="Fixture MDBX", filePath="fixture")), bitwardenVaults = emptyList(),
            initialSource = UnifiedMoveInitialSource.MdbxDatabase(1), allowCopy = true,
            getBitwardenFolders = { flowOf(emptyList()) }, getKeePassGroups = { flowOf(emptyList()) },
            getMdbxFolders = { flow { loads++; emit(listOf(
                MdbxStoredFolderEntry("games", null, "Games", "Games", 0),
                MdbxStoredFolderEntry("personal", "games", "Personal", "Games/Personal", 0))) } },
            onTargetSelected = { target, operation -> selected = target; action = operation }
        ) } }
        compose.onNodeWithText("Personal").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_action_copy").performClick()
        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, loads) }
        // The expressive sheet has its own window; capture the composed display, not the host root.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir,"steam-folder-picker.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        compose.onNodeWithTag("transfer_confirm").performClick()
        compose.runOnIdle {
            assertEquals(UnifiedMoveCategoryTarget.MdbxFolderTarget(1,"personal"), selected)
            assertEquals(UnifiedMoveAction.COPY, action)
        }
    }
}
