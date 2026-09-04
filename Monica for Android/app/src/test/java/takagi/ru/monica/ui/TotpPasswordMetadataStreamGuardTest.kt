package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpPasswordMetadataStreamGuardTest {

    @Test
    fun authenticatorListUsesMetadataOnlyPasswordLookup() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(
            "Authenticator list should not collect the decrypting allPasswords stream for title-only lookup.",
            source.contains("passwordViewModel.allPasswordsForUi.collectAsState")
        )
    }

    @Test
    fun authenticatorViewModelSharesTheMergedSourceAcrossConsumers() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(
            "The merged TOTP source should be shared so the list, keyboard and detail panes do not rescan Room independently.",
            source.contains("private val allTotpItemsSource: SharedFlow<List<SecureItem>>") &&
                source.contains(".shareIn(")
        )
        assertTrue(
            "Filtered TOTP items should consume the shared allTotpItems state rather than the cold source.",
            source.contains("allTotpItems,")
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
