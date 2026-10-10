package takagi.ru.monica.steam

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.steam.ui.SteamStorageSourceMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection as Selection
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.decodeKeePassPathForDisplay

class SteamFolderFilterMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedMenuDrillsIntoLocalAndExternalFoldersWithoutReloadingOnSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected by mutableStateOf<Selection>(Selection.Local)
        var expanded by mutableStateOf(true)
        var mdbxLoads = 0
        var keepassLoads = 0
        val getMdbxFolders = { _: Long -> flow {
            mdbxLoads++
            emit(listOf(MdbxStoredFolderEntry("games",null,"MDBX Games","MDBX Games",0),
                MdbxStoredFolderEntry("personal","games","Personal","MDBX Games/Personal",0)))
        } }
        val getKeePassGroups = { _: Long -> flow {
            keepassLoads++
            emit(listOf(KeePassGroupInfo("KP Games","KP Games","p"),
                KeePassGroupInfo("Child","KP Games/Child","c",1)))
        } }
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopEnd) { Box(Modifier.size(48.dp)) {
                SteamStorageSourceMenu(expanded,{ expanded=false },selected,
                    listOf(Category(1,"Local Games"),Category(2,"Local Games/Personal")),
                    listOf(LocalMdbxDatabase(id=3,name="Fixture MDBX",filePath="fixture")),
                    listOf(LocalKeePassDatabase(id=4,name="Fixture KeePass",filePath="fixture")),
                    listOf(BitwardenVault(id=5,email="fixture@example.invalid")),
                    getMdbxFolders,getKeePassGroups,
                    { flowOf(listOf(BitwardenFolder(vaultId=5,bitwardenFolderId="remote",name="BW Games",revisionDate="fixture"))) },
                    { selected=it })
            } }
        } }
        fun clickText(text: String) {
            compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text).performScrollTo().performClick()
        }
        clickText("Local Games")
        clickText("Personal")
        compose.runOnIdle { assertEquals(Selection.Custom(2),selected); assertTrue(expanded) }
        compose.runOnIdle { expanded=false }
        compose.runOnIdle { expanded=true }
        compose.runOnIdle { assertEquals(Selection.Custom(2),selected) }
        compose.onNodeWithTag("database_expand_toggle").performClick()
        compose.onNodeWithTag("database_filter_mdbx:3").performScrollTo().performClick()
        clickText("MDBX Games")
        clickText("Personal")
        compose.runOnIdle { assertEquals(Selection.MdbxFolderFilter(3,"personal"),selected); assertEquals(1,mdbxLoads) }
        clickText(context.getString(R.string.back))
        compose.runOnIdle { assertEquals(Selection.MdbxFolderFilter(3,"games"),selected) }
        clickText(context.getString(R.string.back))
        compose.runOnIdle { assertEquals(Selection.MdbxDatabaseFilter(3),selected); assertEquals(1,mdbxLoads) }
        compose.onNodeWithTag("database_filter_keepass:4").performScrollTo().performClick()
        clickText("KP Games")
        clickText(decodeKeePassPathForDisplay("KP Games/Child"))
        compose.runOnIdle { assertEquals(Selection.KeePassGroupFilter(4,"KP Games/Child","c"),selected); assertEquals(1,keepassLoads) }
        compose.onNodeWithTag("database_filter_bitwarden:5").performScrollTo().performClick()
        clickText("BW Games")
        compose.runOnIdle { assertEquals(Selection.BitwardenFolderFilter(5,"remote"),selected) }
        compose.onNodeWithText("BW Games").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_folders)).assertExists()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir,"steam-folder-filter.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        screenshot.recycle()
    }
}
