package takagi.ru.monica.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureItemRepairSingleFlightGuardTest {

    @Test
    fun legacyKeePassRepairIsSingleFlightPerRepository() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(
            "Concurrent ViewModel initialisation must not scan every secure item multiple times.",
            source.contains("legacyRepairMutex") &&
                source.contains("legacyRepairCompleted") &&
                source.contains("withLock")
        )
    }

    @Test
    fun secureItemViewModelsDoNotRunRepairOnTheMainDispatcher() {
        val viewModelPaths = listOf(
            "app/src/main/java/takagi/ru/monica/viewmodel/BankCardViewModel.kt",
            "app/src/main/java/takagi/ru/monica/viewmodel/DocumentViewModel.kt",
            "app/src/main/java/takagi/ru/monica/viewmodel/NoteViewModel.kt",
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt",
        )

        viewModelPaths.forEach { path ->
            val source = projectFile(path).readText().replace("\r\n", "\n")
            assertTrue(
                "$path must dispatch the legacy repair away from Main.",
                source.contains("viewModelScope.launch(Dispatchers.Default)")
            )
        }
    }

    private fun projectFile(path: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, path)
    }
}
