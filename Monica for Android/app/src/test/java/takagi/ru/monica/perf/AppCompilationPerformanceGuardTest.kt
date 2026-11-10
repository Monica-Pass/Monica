package takagi.ru.monica.perf

import takagi.ru.monica.testing.readSourceText

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCompilationPerformanceGuardTest {

    @Test
    fun releaseUsesR8AndConsumesMonicaBaselineProfile() {
        val settings = projectFile("settings.gradle").readSourceText()
        val appBuild = projectFile("app/build.gradle").readSourceText()
        val baselineProfile = projectFile("app/src/main/baseline-prof.txt").readSourceText()
        val startupProfile = projectFile("app/src/main/startup-prof.txt").readSourceText()

        assertTrue(settings.contains("include ':baselineprofile'"))
        // The upgraded release retains R8 optimization; runtime contracts are verified separately.
        assertTrue(appBuild.contains("minifyEnabled true"))
        assertTrue(appBuild.contains("proguard-android-optimize.txt"))
        assertTrue(baselineProfile.contains("Ltakagi/ru/monica/MainActivity;"))
        assertTrue(baselineProfile.contains("Ltakagi/ru/monica/ui/SimpleMainScreenKt;"))
        assertTrue(startupProfile.contains("Ltakagi/ru/monica/MonicaApplication;"))
        assertTrue(startupProfile.contains("Ltakagi/ru/monica/MainActivity;"))
    }

    @Test
    fun generatorCoversStartupAndPrimaryAuthenticatedSurfaces() {
        val generator = projectFile(
            "baselineprofile/src/main/java/takagi/ru/monica/baselineprofile/BaselineProfileGenerator.kt"
        ).readSourceText()

        assertTrue(generator.contains("includeInStartupProfile = true"))
        assertTrue(generator.contains("startActivityAndWait()"))
        assertTrue(generator.contains("exercisePrimarySurface()"))
        assertTrue(generator.contains("openSettingsWhenAvailable()"))
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
