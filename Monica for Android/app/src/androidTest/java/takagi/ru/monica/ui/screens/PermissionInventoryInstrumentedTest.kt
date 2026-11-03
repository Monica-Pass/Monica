package takagi.ru.monica.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import takagi.ru.monica.util.PermissionTestSystemUi
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.PermissionRepository
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.PermissionViewModel

class PermissionInventoryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var system:PermissionTestSystemUi
    @Before fun exposeSystemWindows(){system=PermissionTestSystemUi()}
    @After fun restoreSystemWindowFlags(){system.close()}
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("DEPRECATION")
    @Test fun installedInventoryCoversDependenciesAndServicesWithoutBroadReading() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_DISABLED_COMPONENTS)
        val list = PermissionRepository(context).getAllPermissions(true)
        val declared = pkg.requestedPermissions.orEmpty().toSet()
        assertEquals(declared, list.filter { it.requested }.map { it.androidPermission }.toSet())
        assertEquals(list.size, list.map { it.id }.toSet().size)
        for (removed in listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.BIND_AUTOFILL_SERVICE)) assertFalse(removed in declared)
        assertTrue(Manifest.permission.NFC in declared)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in declared)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in declared)
        assertTrue(Manifest.permission.WAKE_LOCK in declared)
        assertTrue(list.any { it.id == "SHIZUKU" })
        assertTrue(list.any { it.id == "IME" && !it.requested })
        assertTrue(pkg.services.orEmpty().any { it.permission == Manifest.permission.BIND_AUTOFILL_SERVICE })
        list.flatMap { it.runtimePermissions }.forEach { assertTrue(it in declared) }
        list.filter { it.category != PermissionCategory.RUNTIME }.forEach { assertTrue(it.runtimePermissions.isEmpty()) }
        if (Build.VERSION.SDK_INT < 34) assertEquals(PermissionStatus.UNAVAILABLE, list.first { it.id == "CREDENTIALS" }.status)
    }

    private fun content(locale: Locale, scale: Float, dark: Boolean) {
        compose.runOnUiThread { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true) }
        val cfg = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(cfg)
        val list = PermissionRepository(localized).getAllPermissions(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides cfg, LocalDensity provides Density(density, scale)) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("permission_test_frame")) {
                        PermissionManagementContent(list.groupBy { it.category }, PermissionStats(0,0,list.size,0,list.count { it.requested }),false,{}, {}, {}, {})
                    }
                }
            }
        }
        for (id in listOf("CAMERA", "OVERLAY", "NFC", "INTERNET")) {
            compose.onNodeWithTag("permission_row_$id").performScrollTo().assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("permission_test_frame").captureToImage().asAndroidBitmap()
            File(context.filesDir,"permission-inventory-${locale.toLanguageTag()}-$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true)
        repeat(nodes.fetchSemanticsNodes().size) { i ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                val message="Clipped: ${layout.layoutInput.text} (${layout.size})"
                assertEquals(message,layout.layoutInput.text.length,layout.getLineEnd(layout.lineCount-1))
                assertFalse(message,(0 until layout.lineCount).any{layout.isLineEllipsized(it)})
                assertTrue(message,(0 until layout.lineCount).maxOf{layout.getLineRight(it)-layout.getLineLeft(it)}<=layout.size.width+1f)
                assertTrue(message,layout.multiParagraph.height<=layout.size.height+1f)
            }
        }
    }
    @Test fun nativePermissionGroupsFitNarrowEnglishAtDoubleFontSize() = content(Locale.ENGLISH,2f,true)
    @Test fun nativePermissionGroupsFitBrazilianPortuguese() = content(Locale.forLanguageTag("pt-BR"),1f,false)

    @Test fun openingDetailsCannotRequestRuntimePermissionAndAutomaticEntriesHaveNoGrantAction() {
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
        val model = PermissionViewModel(context.applicationContext as Application)
        compose.setContent { MonicaTheme { PermissionManagementScreen({}, model) } }
        compose.onNodeWithTag("permission_row_CAMERA").performScrollTo().performClick()
        compose.onNodeWithTag("permission_details").assertIsDisplayed()
        compose.onNodeWithTag("permission_details_action").assertIsDisplayed()
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithTag("permission_row_INTERNET").performScrollTo().performClick()
        compose.onNodeWithTag("permission_details").assertIsDisplayed()
        compose.onNodeWithTag("permission_details_action").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithTag("permission_refresh").performScrollTo().assertIsDisplayed()
    }

    @Test fun explicitCameraActionShowsSystemRequestAndDenialKeepsThePageUsable() {
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
        val model=PermissionViewModel(context.applicationContext as Application)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.setContent { MonicaTheme { PermissionManagementScreen({},model) } }
        compose.onNodeWithTag("permission_row_CAMERA").performScrollTo().performClick()
        compose.onNodeWithTag("permission_details_action").performClick()
        system.click("permission_deny_button")
        compose.waitUntil(10_000){compose.onAllNodesWithText(context.getString(R.string.permission_request_denied_title)).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
        automation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir,"permission-camera-denial.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("permission_refresh").performScrollTo().assertIsEnabled()
    }

    @Test fun preciseLocationRequestAcceptsSystemApproximateChoiceWithoutDenialDialog() {
        assertTrue("Requires Android 12+ accuracy choice",Build.VERSION.SDK_INT>=31)
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        val model=PermissionViewModel(context.applicationContext as Application)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.setContent { MonicaTheme { PermissionManagementScreen({},model) } }
        compose.onNodeWithTag("permission_row_ACCESS_FINE_LOCATION").performScrollTo().performClick()
        compose.onNodeWithTag("permission_details_action").performClick()
        system.click("Approximate")
        system.click("permission_allow_foreground_only_button")
        compose.waitUntil(10_000){system.hasPackage(context.packageName)}
        compose.waitForIdle()
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        compose.onNodeWithText(context.getString(R.string.permission_request_denied_title)).assertDoesNotExist()
        compose.onNodeWithTag("permission_refresh").performScrollTo().assertIsEnabled()
    }
}
