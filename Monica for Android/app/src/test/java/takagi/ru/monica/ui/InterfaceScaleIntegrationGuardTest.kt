package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceScaleIntegrationGuardTest {
    @Test
    fun appSettingsPersistAndSanitizeInterfaceScale() {
        val appSettings = projectFile(
            "app/src/main/java/takagi/ru/monica/data/AppSettings.kt"
        ).readText()
        val settingsManager = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/SettingsManager.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/SettingsViewModel.kt"
        ).readText()

        assertTrue(appSettings.contains("interfaceScalePercent: Int = InterfaceScale.DEFAULT_PERCENT"))
        assertTrue(settingsManager.contains("intPreferencesKey(\"interface_scale_percent\")"))
        assertTrue(settingsManager.contains("InterfaceScale.normalizePercent("))
        assertTrue(settingsManager.contains("suspend fun updateInterfaceScalePercent("))
        assertTrue(viewModel.contains("fun updateInterfaceScalePercent("))
    }

    @Test
    fun mainComposeRootAppliesScaleWithoutOverridingSystemFontScale() {
        val provider = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/scale/InterfaceScaleProvider.kt"
        ).readText()
        val mainActivity = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()

        assertTrue(provider.contains("LocalDensity provides appDensity"))
        assertTrue(provider.contains("InterfaceScale.calculateDensity("))
        assertTrue(provider.contains("fontScale = baseDensity.fontScale"))
        assertTrue(mainActivity.contains("scalePercent = settings.interfaceScalePercent"))
        assertTrue(mainActivity.contains("animationsEnabled = !settings.reduceAnimations"))
        assertTrue(
            mainActivity.indexOf("ProvideMonicaInterfaceScale(") <
                mainActivity.indexOf("MonicaTheme(", startIndex = mainActivity.indexOf("fun MonicaApp("))
        )
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
