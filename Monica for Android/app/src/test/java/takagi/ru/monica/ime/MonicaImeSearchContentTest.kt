package takagi.ru.monica.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MonicaImeSearchContentTest {
    @Test fun providerNameMatchesEntryContentRatherThanEveryEntryInThatVault() {
        val entries = listOf(
            entry(1, "Bitwarden account"),
            entry(2, "Mail account"),
            entry(3, "Work", website = "https://vault.bitwarden.com"),
            entry(4, "Development", username = "bitwarden-user"),
        )
        assertEquals(listOf(1L, 3L, 4L), entries.filter {
            imePasswordEntryMatchesQuery(it, "BITWARDEN")
        }.map { it.id })
    }

    @Test fun databaseNamesAndAccountLabelsAreOnlyUsedByTheSourceSelector() {
        assertFalse(imePasswordEntryMatchesQuery(entry(1, "Mail"), "Personal"))
        assertFalse(imePasswordEntryMatchesQuery(entry(1, "Mail"), "mail Bitwarden"))
    }

    private fun entry(id: Long, title: String, website: String = "", username: String = "user") =
        MonicaImePasswordEntry(id, title, username, website, "com.example.app", "encrypted",
            false, "Bitwarden · Personal", bitwardenVaultId = 1)
}
