package takagi.ru.monica.autofill_ng

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore

/** Old PendingIntents can still name a Room mirror after its native file became authoritative. */
internal object AutofillRoomCredentialReader {
    sealed interface Result {
        data class Available(val entry: PasswordEntry) : Result
        data object NativeMirror : Result
        data object Unavailable : Result
    }

    suspend fun read(database: PasswordDatabase, id: Long): Result = withContext(Dispatchers.IO) {
        if (KeePassMonicaPasswordStore.isUiId(id)) return@withContext Result.Unavailable
        database.withTransaction {
            val entry = database.passwordEntryDao().getPasswordEntryById(id)
                ?: return@withTransaction Result.Unavailable
            if (entry.keepassDatabaseId?.let { database.localKeePassDatabaseDao().getDatabaseById(it) } != null) {
                return@withTransaction Result.NativeMirror
            }
            if (entry.isDeleted || entry.isArchived || entry.isKeyCredential()) Result.Unavailable
            else Result.Available(entry)
        }
    }
}
