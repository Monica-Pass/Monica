package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.BackupEncryptionFormat
import takagi.ru.monica.suggestions.CommonInfoTestActivity

class BackupEncryptionFormatUiTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CommonInfoTestActivity.content = content }
        compose.waitForIdle()
    }
    @After fun clear() { show {} }

    @Test fun defaultChoiceWarnsBeforeOptInAndBothChoicesWork() {
        var selected by mutableStateOf(BackupEncryptionFormat.COMPATIBLE_V1)
        show { MaterialTheme { Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            BackupEncryptionFormatSelector(selected, { selected = it })
        } } } }
        compose.onNodeWithTag("backup_format_COMPATIBLE_V1").assertIsSelected()
        compose.onNodeWithTag("backup_format_compatibility").performScrollTo().assertIsDisplayed()
        capture("compatible")
        compose.onNodeWithTag("backup_format_ENHANCED_V2").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(BackupEncryptionFormat.ENHANCED_V2, selected) }
        capture("enhanced")
        compose.onNodeWithTag("backup_format_COMPATIBLE_V1").performClick().assertIsSelected()
    }

    @Test fun narrowLargeDarkRtlKeepsBothOptionsAndWarningReachable() {
        var selected by mutableStateOf(BackupEncryptionFormat.COMPATIBLE_V1)
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme()) { Surface {
                    Column(Modifier.width(320.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                        BackupEncryptionFormatSelector(selected, { selected = it })
                    }
                } }
            }
        }
        compose.onNodeWithTag("backup_format_ENHANCED_V2").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("backup_format_compatibility").performScrollTo().assertIsDisplayed()
        capture("dark-rtl-large")
        compose.onNodeWithTag("backup_format_COMPATIBLE_V1").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun cloudFormatChoiceDoesNotChangePasswordOrEncryptionToggle() {
        var enabled by mutableStateOf(true)
        var password by mutableStateOf("Synthetic backup secret")
        var selected by mutableStateOf(BackupEncryptionFormat.COMPATIBLE_V1)
        show { MaterialTheme { Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            CloudBackupEncryptionSettings(enabled, password, false, { enabled = it }, { password = it }, {},
                selected, { selected = it })
        } } } }
        compose.onNodeWithTag("backup_format_ENHANCED_V2").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(BackupEncryptionFormat.ENHANCED_V2, selected)
            assertEquals("Synthetic backup secret", password)
            assertEquals(true, enabled)
        }
        compose.onNodeWithTag("cloud_backup_encryption").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("cloud_backup_encryption").performClick().assertIsOn()
        compose.onNodeWithTag("backup_format_ENHANCED_V2").performScrollTo().assertIsSelected()
    }

    private fun capture(name: String) {
        if (name !in setOf("enhanced", "dark-rtl-large")) return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val dir = File(instrumentation.targetContext.filesDir, "local-password-kdf-327/ui")
                .apply { check(isDirectory || mkdirs()) }
            File(dir, "backup-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
