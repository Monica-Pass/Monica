package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectorPerformanceGuardTest {

    @Test
    fun appMetadataLoadingDoesNotDecodeEveryIconBeforeFirstFrame() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/AppSelector.kt"
        ).readText().replace("\r\n", "\n")
        val loader = source.substringAfter("suspend fun loadInstalledApps")
            .substringBefore("/**\n * 判断是否是需要隐藏")

        assertFalse(
            "The app picker must resolve icons lazily in visible list rows, not while building the full metadata list.",
            loader.contains("activityInfo.loadIcon(packageManager)")
        )
        assertTrue(
            "The app picker should cache the expensive PackageManager metadata query for subsequent openings.",
            source.contains("INSTALLED_APPS_CACHE_TTL_MS") &&
                source.contains("installedAppsCacheTimestamp")
        )
        assertTrue(
            "Icons must remain lazy so the first frame can render metadata immediately.",
            source.contains("AppInfo(packageName, appName, icon = null)") &&
                source.contains("fun AppIcon(")
        )
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!
        }
        return File(dir, path)
    }
}
