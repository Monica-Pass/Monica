package takagi.ru.monica.autofill_ng

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class AutofillScopeMatchingTest {
    private fun row(id: Long) = PasswordEntry(id = id, title = "Synthetic", username = "account", password = "value", website = "")
    private val local = row(1)
    private val legacyBw = row(2).copy(bitwardenVaultId = 21)
    private val bw = row(3).copy(bitwardenVaultId = 21, bitwardenCipherId = "synthetic-cipher")
    private val otherBw = row(4).copy(bitwardenVaultId = 22, bitwardenCipherId = "other-cipher")
    private val keepass = row(5).copy(keepassDatabaseId = 31)
    private val mdbx = row(-6).copy(mdbxDatabaseId = 41)
    private val rows = listOf(local, legacyBw, bw, otherBw, keepass, mdbx)
    private fun ids(scope: AutofillScope) = rows.filter { it.matchesAutofillScope(scope) }.map { it.id }

    @Test fun exactSourceNeverWidensIntoOtherVaults() {
        assertEquals(listOf(1L, 2L), ids(AutofillScope.Local))
        assertEquals(listOf(2L, 3L), ids(AutofillScope.Bitwarden(21)))
        assertEquals(listOf(2L, 3L, 4L), ids(AutofillScope.Bitwarden()))
        assertEquals(listOf(5L), ids(AutofillScope.KeePass(31)))
        assertEquals(listOf(-6L), ids(AutofillScope.Mdbx(41)))
        assertEquals(emptyList<Long>(), ids(AutofillScope.Mdbx(999)))
        assertEquals(emptyList<Long>(), ids(AutofillScope.Mdbx(-1)))
        assertEquals(rows.map { it.id }, ids(AutofillScope.All))
    }

    @Test fun legacySourcePolicyStillFiltersDefaultsAndAddsMdbx() {
        val result = AutofillSuggestionPolicy.applyDefaultSourceFilter(rows,
            AutofillPreferences.AutofillDefaultSourceFilter.MDBX, null, null, 41)
        assertEquals(listOf(mdbx), result)
        assertEquals(listOf(local, legacyBw), AutofillSuggestionPolicy.applyDefaultSourceFilter(rows,
            AutofillPreferences.AutofillDefaultSourceFilter.LOCAL, null, null))
    }
}
