package takagi.ru.monica.navigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RetiredRouteNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var controller: NavHostController
    private var updated by mutableStateOf(false)
    private var savedState: Bundle? = null

    private fun show() {
        compose.setContent {
            key(updated) {
                val nav = rememberNavController()
                controller = nav
                remember(nav) { if (updated) nav.restoreState(savedState); true }
                NavHost(nav, startDestination = "retired-test-root") {
                    composable("retired-test-root") { Text("Retained root") }
                    composable(Screen.SyncBackup.route) { Text("Retained backup settings") }
                    if (updated) addRetiredFeatureDestinations(nav)
                    else composable("dedup_engine") { Text("Synthetic legacy destination") }
                }
            }
        }
    }

    @Test fun savedLegacyDestinationRestoresToBackupAndBackReturnsToOriginalParent() {
        show()
        compose.runOnIdle {
            controller.navigate(Screen.SyncBackup.route)
            controller.navigate("dedup_engine")
        }
        compose.onNodeWithText("Synthetic legacy destination").assertIsDisplayed()
        compose.runOnIdle { savedState = controller.saveState(); assertNotNull(savedState); updated = true }
        compose.waitUntil(5_000) { controller.currentDestination?.route == Screen.SyncBackup.route }
        compose.onNodeWithText("Retained backup settings").assertIsDisplayed()
        compose.onNodeWithText("Synthetic legacy destination").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("retired-test-root", controller.previousBackStackEntry?.destination?.route)
            assertTrue(controller.popBackStack())
        }
        compose.onNodeWithText("Retained root").assertIsDisplayed()
    }

    @Test fun obsoleteNavigationRequestRedirectsWithoutMountingTheLegacyPage() {
        updated = true
        show()
        compose.runOnIdle { controller.navigate("dedup_engine") }
        compose.waitUntil(5_000) { controller.currentDestination?.route == Screen.SyncBackup.route }
        compose.onNodeWithText("Retained backup settings").assertIsDisplayed()
        compose.onNodeWithText("Synthetic legacy destination").assertDoesNotExist()
        compose.runOnIdle { assertEquals("retired-test-root", controller.previousBackStackEntry?.destination?.route) }
    }

    @Test fun oldSerializedScreenEnumsSurviveAndroidStateParcel() {
        // Java enum serialization from the exact pre-removal binary names/constants.
        val fixtures = listOf(
            "rO0ABX5yACZ0YWthZ2kucnUubW9uaWNhLnVpLnNjcmVlbnMuRGVkdXBTaGVldAAAAAAAAAAAEgAAeHIADmphdmEubGFuZy5FbnVtAAAAAAAAAAASAAB4cHQAB1BSRVZJRVc=" to ("takagi.ru.monica.ui.screens.DedupSheet" to "PREVIEW"),
            "rO0ABX5yAC50YWthZ2kucnUubW9uaWNhLnVpLnNjcmVlbnMuRGVkdXBQcmV2aWV3RmlsdGVyAAAAAAAAAAASAAB4cgAOamF2YS5sYW5nLkVudW0AAAAAAAAAABIAAHhwdAAIQ09ORkxJQ1Q=" to ("takagi.ru.monica.ui.screens.DedupPreviewFilter" to "CONFLICT"),
        )
        fixtures.forEach { (encoded, expected) ->
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val value = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readObject() }
            assertEquals(expected.first, value.javaClass.name)
            assertEquals(expected.second, (value as Enum<*>).name)
            val state = Bundle().apply { putSerializable("retired-value", value as java.io.Serializable) }
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.writeBundle(state)
                parcel.setDataPosition(0)
                val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
                @Suppress("DEPRECATION")
                val restoredValue = restored.getSerializable("retired-value")
                assertEquals(value, restoredValue)
            } finally { parcel.recycle() }
        }
    }
}
