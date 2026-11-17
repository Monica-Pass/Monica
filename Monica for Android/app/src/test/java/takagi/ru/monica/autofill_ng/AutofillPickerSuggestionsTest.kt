package takagi.ru.monica.autofill_ng

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.addOrReplaceLinkedAppBinding

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutofillPickerSuggestionsTest {
    private val preferences get() = AutofillPreferences(RuntimeEnvironment.getApplication())
    private val target = "com.example.native"
    private val native = AutofillPickerActivityV2.Args(applicationId = target)
    private val web = AutofillPickerActivityV2.Args(applicationId = "com.android.chrome", webDomain = "accounts.example.test")
    private val entries = listOf(
        entry(1, "https://accounts.example.test/login"),
        entry(2, "", "com.other.native|androidapp://$target"),
        entry(3, "https://accounts.example.test.evil.test", "com.android.chrome"),
    )

    @Before fun resetSettings(): Unit = runBlocking {
        preferences.setBitwardenStrictModeEnabled(true)
        preferences.setBitwardenSubdomainMatchEnabled(true)
        preferences.setDomainMatchStrategy(DomainMatchStrategy.BASE_DOMAIN)
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.ALL)
    }

    @Test fun manualPickerUsesBoundPackageAndWebsite(): Unit = runBlocking {
        assertEquals(listOf(2L), suggestions(entries, native))
        assertEquals(listOf(1L), suggestions(entries, web))
    }

    @Test fun domainlessBrowserAndContextlessPickerDoNotGuessAnAccount(): Unit = runBlocking {
        assertEquals(emptyList<Long>(), suggestions(entries, web.copy(webDomain = null)))
        assertEquals(emptyList<Long>(), suggestions(entries, AutofillPickerActivityV2.Args()))
    }

    @Test fun explicitFrameworkResultsPreserveOrderAndEmptyScope(): Unit = runBlocking {
        assertEquals(listOf(2L, 1L), suggestions(entries, native.copy(suggestedPasswordIds = longArrayOf(2, 1, 2, 999))))
        assertEquals(emptyList<Long>(), suggestions(entries, native.copy(suggestedPasswordIds = longArrayOf())))
    }

    @Test fun nextOpenSeesBindingAddedAndRemoved(): Unit = runBlocking {
        val original = entry(4, "")
        assertEquals(emptyList<Long>(), suggestions(listOf(original), native))
        val binding = addOrReplaceLinkedAppBinding("com.old.app", "Old", target, "Target")
        assertEquals(listOf(4L), suggestions(listOf(original.copy(appPackageName = binding.first, appName = binding.second)), native))
        assertEquals(listOf(4L), suggestions(listOf(original.copy(website = "https://old.test, https://accounts.example.test/signin")), web))
        assertEquals(emptyList<Long>(), suggestions(listOf(original), web))
    }

    @Test fun disabledMatchingAndDefaultSourceAreRespected(): Unit = runBlocking {
        preferences.setDomainMatchStrategy(DomainMatchStrategy.NEVER)
        assertEquals(emptyList<Long>(), suggestions(entries, native))
        preferences.setDomainMatchStrategy(DomainMatchStrategy.BASE_DOMAIN)
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.LOCAL)
        assertEquals(emptyList<Long>(), suggestions(listOf(entries[1].copy(keepassDatabaseId = 10)), native))
    }

    @Test fun exactDomainSettingDoesNotFallBackToSiblingWebsite(): Unit = runBlocking {
        val sibling = listOf(entry(5, "https://other.example.test"))
        assertEquals(listOf(5L), suggestions(sibling, web))
        preferences.setDomainMatchStrategy(DomainMatchStrategy.EXACT_MATCH)
        assertEquals(emptyList<Long>(), suggestions(sibling, web))
    }

    @Test fun activeSelectionWinsWhilePersistedDefaultIsStillOld(): Unit = runBlocking {
        preferences.setV2DefaultScope(AutofillScope.Local)
        val values = listOf(entries[1].copy(id = 20, bitwardenVaultId = 81, bitwardenCipherId = "a"),
            entries[1].copy(id = 21, bitwardenVaultId = 82, bitwardenCipherId = "b"))
        assertEquals(listOf(21L), AutofillPickerSuggestions.load(values, native, preferences, null,
            activeScope = AutofillScope.Bitwarden(82)).map { it.id })
        assertEquals(AutofillScope.Local, preferences.v2DefaultScope.first())
    }

    @Test fun suppliedIdsStayOrderedAndCannotEscapeActiveMdbxScope(): Unit = runBlocking {
        val values = listOf(entries[0].copy(id = -20, mdbxDatabaseId = 81),
            entries[0].copy(id = -21, mdbxDatabaseId = 82))
        assertEquals(listOf(-21L), AutofillPickerSuggestions.load(values,
            web.copy(suggestedPasswordIds = longArrayOf(-20, -21, -21)), preferences, null,
            activeScope = AutofillScope.Mdbx(82)).map { it.id })
        assertTrue(AutofillPickerSuggestions.load(values, web.copy(suggestedPasswordIds = longArrayOf()),
            preferences, null, activeScope = AutofillScope.Mdbx()).isEmpty())
    }

    private suspend fun suggestions(values: List<PasswordEntry>, args: AutofillPickerActivityV2.Args) =
        AutofillPickerSuggestions.load(values, args, preferences, null).map { it.id }

    private fun entry(id: Long, website: String, app: String = "") =
        PasswordEntry(id = id, title = "Synthetic $id", website = website, appPackageName = app,
            username = "synthetic", password = "synthetic")
}
