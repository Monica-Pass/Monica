package takagi.ru.monica.autofill_ng

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.isLocalOnlyItem

internal fun PasswordEntry.matchesAutofillScope(scope: AutofillScope): Boolean = when (scope) {
    AutofillScope.All -> true
    AutofillScope.Local -> isLocalOnlyEntry()
    is AutofillScope.KeePass -> keepassDatabaseId != null &&
        (scope.databaseId == null || keepassDatabaseId == scope.databaseId)
    is AutofillScope.Bitwarden -> bitwardenVaultId != null &&
        (scope.vaultId == null || bitwardenVaultId == scope.vaultId)
    is AutofillScope.Mdbx -> mdbxDatabaseId != null &&
        (scope.databaseId == null || mdbxDatabaseId == scope.databaseId)
}

/** Preserve the wallet adapter's legacy local-ownership interpretation before decoding. */
internal fun SecureItem.matchesWalletAutofillScope(scope: AutofillScope): Boolean = when (scope) {
    AutofillScope.All -> true
    AutofillScope.Local -> isLocalOnlyItem()
    is AutofillScope.KeePass -> keepassDatabaseId != null &&
        (scope.databaseId == null || keepassDatabaseId == scope.databaseId)
    is AutofillScope.Bitwarden -> bitwardenVaultId != null &&
        (scope.vaultId == null || bitwardenVaultId == scope.vaultId)
    is AutofillScope.Mdbx -> mdbxDatabaseId != null &&
        (scope.databaseId == null || mdbxDatabaseId == scope.databaseId)
}
