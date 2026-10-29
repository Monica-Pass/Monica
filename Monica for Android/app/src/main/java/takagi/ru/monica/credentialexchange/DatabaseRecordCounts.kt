package takagi.ru.monica.credentialexchange

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.Mdbx2FailureKind
import takagi.ru.monica.repository.Mdbx2OperationException
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.utils.KeePassKdbxService

internal sealed interface DatabaseRecordCount {
    data class Ready(val count: Long) : DatabaseRecordCount
    data object Locked : DatabaseRecordCount
    data object Unavailable : DatabaseRecordCount
}

/** Read-only source totals; never create an export snapshot or a Room projection to count. */
internal class DatabaseRecordCounts(context: Context, private val db: PasswordDatabase = PasswordDatabase.getDatabase(context)) {
    private val context = context.applicationContext
    private val security by lazy { SecurityManager(this.context) }
    private val keepass by lazy { KeePassKdbxService(this.context, db.localKeePassDatabaseDao(), security) }
    private val bitwarden by lazy { BitwardenRepository.getInstance(this.context) }
    private val mdbx by lazy { MdbxRepositoryFactory.create(this.context, db, security) }

    fun isLocked(source: ImportDestination): Boolean = !SessionManager.isUnlocked.value || when (source.kind) {
        ImportDestinationKind.KEEPASS -> !keepass.isDatabaseUnlocked(source.databaseId)
        ImportDestinationKind.BITWARDEN -> !bitwarden.isVaultUnlocked(source.databaseId)
        else -> false
    }

    suspend fun load(source: ImportDestination): DatabaseRecordCount = withContext(Dispatchers.IO) {
        if (isLocked(source)) return@withContext DatabaseRecordCount.Locked
        try {
            val count = when (source.kind) {
                ImportDestinationKind.LOCAL -> countRoomRecords(source) +
                    SteamDatabase.getDatabase(context).steamAccountDao().count().toLong()
                ImportDestinationKind.BITWARDEN -> countRoomRecords(source)
                ImportDestinationKind.KEEPASS -> keepass.openNativeBrowser(source.databaseId).getOrThrow()
                    .entries.count { !it.isInRecycleBin }.toLong()
                ImportDestinationKind.MDBX -> mdbx.countActiveEntries(source.databaseId)
            }
            if (isLocked(source)) DatabaseRecordCount.Locked else DatabaseRecordCount.Ready(count)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isLocked(source) || error is Mdbx2OperationException && error.kind == Mdbx2FailureKind.CREDENTIAL_UNAVAILABLE)
                DatabaseRecordCount.Locked else DatabaseRecordCount.Unavailable
        }
    }

    internal fun countRoomRecords(source: ImportDestination): Long {
        val local = source.kind == ImportDestinationKind.LOCAL
        fun owner(keepassColumn: String) = if (local)
            "$keepassColumn IS NULL AND mdbx_database_id IS NULL AND bitwarden_vault_id IS NULL"
        else "$keepassColumn IS NULL AND mdbx_database_id IS NULL AND bitwarden_vault_id = ?"
        fun identity(prefix: String) = if (local) "'$prefix:' || id"
            else "COALESCE(NULLIF(bitwarden_cipher_id, ''), '$prefix:' || id)"
        // A Bitwarden login and its Passkey projection are one underlying cipher.
        val sql = """
            SELECT COUNT(*) FROM (
                SELECT ${identity("password")} AS identity FROM password_entries
                    WHERE ${owner("keepassDatabaseId")} AND isDeleted = 0
                UNION
                SELECT ${identity("item")} AS identity FROM secure_items
                    WHERE ${owner("keepass_database_id")} AND isDeleted = 0
                UNION
                SELECT ${identity("passkey")} AS identity FROM passkeys
                    WHERE ${owner("keepass_database_id")}
            )
        """.trimIndent()
        val args: Array<Any?> = if (local) emptyArray() else Array(3) { source.databaseId }
        return db.query(SimpleSQLiteQuery(sql, args)).use { it.moveToFirst(); it.getLong(0) }
    }
}
