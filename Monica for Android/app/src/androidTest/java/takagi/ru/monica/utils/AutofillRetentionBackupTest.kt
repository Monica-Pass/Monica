package takagi.ru.monica.utils

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.BackupPreferences
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AutofillRetentionBackupTest {
    @Test fun oldBackupDefaultsOffAndNewBackupRoundTripsExplicitChoice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context)
        val original = settings.exportPageAdjustmentSettings()
        val helper = WebDavHelper(context)
        val legacy = File.createTempFile("autofill-options-", ".zip", context.cacheDir)
        try {
            for (enabled in listOf(true, false)) {
                settings.updateAutofillKeepUnlocked(true)
                ZipOutputStream(legacy.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("monica_config/page_adjustment_settings.json"))
                    zip.write("""{"useDraggableBottomNav":$enabled,"bottomNavSettingsVersion":1,"autoHideBottomNavWhenSingleTab":true,"passwordListQuickFoldersEnabled":true}""".toByteArray())
                    zip.closeEntry()
                }
                helper.restoreFromBackupFile(legacy, restoreMonicaConfig = true).getOrThrow()
                val restored = settings.exportPageAdjustmentSettings()
                assertFalse(restored.autofillKeepUnlocked)
                assertTrue(restored.autoHideBottomNavWhenSingleTab)
                assertTrue(restored.passwordListQuickFoldersEnabled)
            }
            settings.updateAutofillKeepUnlocked(true)
            kotlinx.coroutines.delay(200)
            val preferences = BackupPreferences(includePasswords = true, includeNotes = false,
                includeAuthenticators = false, includeDocuments = false, includeBankCards = false,
                includePasskeys = false, includeGeneratorHistory = false, includeImages = false,
                includeTrash = false, includeTrashAndHistory = false,
                includeWebDavConfig = false, includeLocalKeePass = false)
            val (archive, report) = helper.createBackupZip(emptyList(), emptyList(), preferences).getOrThrow()
            try {
                assertTrue(report.success)
                ZipFile(archive).use { zip ->
                    val entry = requireNotNull(zip.getEntry("monica_config/page_adjustment_settings.json"))
                    val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    assertFalse(json.contains("useDraggableBottomNav"))
                    assertFalse(json.contains("passkeyHyperOsBiometricBypassEnabled"))
                    assertTrue(json.contains("autoHideBottomNavWhenSingleTab"))
                    assertTrue(org.json.JSONObject(json).getBoolean("autofillKeepUnlocked"))
                }
                settings.updateAutofillKeepUnlocked(false)
                helper.restoreFromBackupFile(archive, restoreMonicaConfig = true).getOrThrow()
                assertTrue(settings.exportPageAdjustmentSettings().autofillKeepUnlocked)
            } finally { archive.delete() }
        } finally {
            settings.importPageAdjustmentSettings(original)
            legacy.delete()
        }
    }
}
