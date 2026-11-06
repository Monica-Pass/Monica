package takagi.ru.monica.data.dedup

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver

internal fun createSingleVaultDedupService(context: Context, database: PasswordDatabase, security: SecurityManager): SingleVaultDedupService {
    val app = context.applicationContext
    return SingleVaultDedupService(database) {
        buildList {
            add(SingleVaultDedupLocalAdapter(app, database, security, AppLocaleStringResolver(context).get(R.string.database_source_local)))
            database.localKeePassDatabaseDao().getAllDatabasesSync().forEach { row ->
                add(SingleVaultDedupKeePassAdapter(app, database, security, row.id, row.name))
            }
            database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot().forEach { row ->
                add(SingleVaultDedupMdbxAdapter(app, database, security, row.id, row.name))
            }
            val bitwarden = BitwardenRepository.getInstance(app)
            database.bitwardenVaultDao().getAllVaults().forEach { row ->
                add(SingleVaultDedupBitwardenAdapter(bitwarden, row.id, row.displayName?.takeIf { it.isNotBlank() } ?: row.email))
            }
        }
    }
}
