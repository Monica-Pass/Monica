package takagi.ru.monica.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.Language

class StartupLanguageCacheTest {

    @Test
    fun cachedLanguageParsingFailsSafelyToSystemLanguage() {
        assertEquals(Language.CHINESE, parseStartupLanguage("CHINESE"))
        assertEquals(Language.ITALIAN, parseStartupLanguage("ITALIAN"))
        assertEquals(Language.PORTUGUESE_PORTUGAL, parseStartupLanguage("PORTUGUESE_PORTUGAL"))
        assertEquals(Language.PORTUGUESE_BRAZIL, parseStartupLanguage("PORTUGUESE_BRAZIL"))
        assertEquals(Language.CLASSICAL_CHINESE, parseStartupLanguage("CLASSICAL_CHINESE"))
        assertEquals(Language.CHINESE, parseStartupLanguage("NYA"))
        assertEquals(Language.CHINESE, parseStartupLanguage("SNOW_LEOPARD"))
        assertEquals(Language.SYSTEM, parseStartupLanguage("unsupported"))
        assertEquals(Language.SYSTEM, parseStartupLanguage(null))
    }

    @Test
    fun activityStartupUsesCacheInsteadOfWaitingForDataStore() {
        val activitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/base/BaseMonicaActivity.kt"
        ).readText()
        val attachBody = activitySource.substringAfter("override fun attachBaseContext(")
            .substringBefore("override fun onCreate(")
        val settingsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/SettingsManager.kt"
        ).readText()
        val updateLanguageBody = settingsSource.substringAfter("suspend fun updateLanguage(")
            .substringBefore("suspend fun updateBitwardenUploadAll(")

        assertTrue(attachBody.contains("StartupLanguageCache.read("))
        assertFalse(attachBody.contains("runBlocking"))
        assertFalse(attachBody.contains("settingsFlow.first()"))
        assertTrue(updateLanguageBody.contains("StartupLanguageCache.write("))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
