package takagi.ru.monica.autofill_ng

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class AutofillPickerSearchTest {
    private fun entry(id: Long = 1, packages: String = "", website: String = "", appName: String = "") =
        PasswordEntry(id = id, title = "Synthetic unrelated", username = "synthetic", password = "synthetic",
            appPackageName = packages, website = website, appName = appName)

    @Test fun labelsCoverEveryBindingAndAndroidUrisWithOneLookupPerPackage(): Unit = runBlocking {
        val entries = listOf(entry(packages = "com.other.app|androidapp://com.x8bit.bitwarden"),
            entry(2, website = "https://example.test, androidapp://com.x8bit.bitwarden"))
        val calls = mutableListOf<String>()
        val labels = AutofillPickerSearch.loadAppLabels(entries) { calls += it; if (it == "com.x8bit.bitwarden") "Bitwarden" else "Other" }
        assertEquals(listOf("com.other.app", "com.x8bit.bitwarden"), calls)
        entries.forEach { assertTrue(AutofillPickerSearch.matches(it, "  bItWaRdEn  ", labels[it.id].orEmpty())) }
        assertEquals("", entries[1].appName)
    }

    @Test fun unavailableAppsKeepStoredNamesAndPackagesSearchable(): Unit = runBlocking {
        val value = entry(packages = "com.uninstalled.app", appName = "Stored Label")
        val labels = AutofillPickerSearch.loadAppLabels(listOf(value)) { null }
        assertTrue(AutofillPickerSearch.matches(value, " stored label ", labels[1].orEmpty()))
        assertTrue(AutofillPickerSearch.matches(value, "com.uninstalled"))
        assertFalse(AutofillPickerSearch.matches(value, "Unrelated application"))
    }

    @Test fun websitesDoNotResolveHostnamesAsInstalledApps(): Unit = runBlocking {
        val values = listOf(entry(website = "https://accounts.example.test/path; example.test; android-app://com.example.app/path"))
        val calls = mutableListOf<String>()
        AutofillPickerSearch.loadAppLabels(values) { calls += it; "Example" }
        assertEquals(listOf("com.example.app"), calls)
    }

    @Test fun queryWhitespaceHasSameMeaningForStoredFieldsAndResolvedLabels() {
        val value = entry(appName = "Saved App")
        assertTrue(AutofillPickerSearch.matches(value, "  Saved App  "))
        assertTrue(AutofillPickerSearch.matches(value, "  Installed App  ", listOf("Installed App")))
        assertTrue(AutofillPickerSearch.matches(value, "   "))
    }
}
