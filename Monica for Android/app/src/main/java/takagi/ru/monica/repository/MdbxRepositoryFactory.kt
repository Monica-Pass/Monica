package takagi.ru.monica.repository

import android.content.Context
import java.util.UUID
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.security.SecurityManager

object MdbxRepositoryFactory {
    fun create(
        context: Context,
        database: PasswordDatabase,
        securityManager: SecurityManager
    ): MdbxRepository {
        val appContext = context.applicationContext
        val databaseDao = database.localMdbxDatabaseDao()
        val legacy = MdbxVaultStore(
            context = appContext,
            databaseDao = databaseDao,
            securityManager = securityManager,
            remoteSourceDao = database.mdbxRemoteSourceDao(),
            passwordEntryDao = database.passwordEntryDao(),
            secureItemDao = database.secureItemDao(),
            customFieldDao = database.customFieldDao()
        )
        val rust = Mdbx2Repository(
            context = appContext,
            databaseDao = databaseDao,
            securityManager = securityManager,
            passwordEntryDao = database.passwordEntryDao(),
            secureItemDao = database.secureItemDao(),
            customFieldDao = database.customFieldDao()
        )
        return MdbxRepositoryRouter(databaseDao, legacy, rust)
    }
}

fun mdbxPasswordObjectId(entry: PasswordEntry): String =
    entry.replicaGroupId
        ?.takeIf { (it.startsWith("password:") && it.length > "password:".length) ||
            runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        ?: "password:${entry.id}"

fun mdbxSecureItemObjectId(item: SecureItem): String {
    val prefix = when (item.itemType) {
        ItemType.NOTE -> "note"
        ItemType.TOTP -> "totp"
        ItemType.BANK_CARD -> "card"
        ItemType.DOCUMENT -> "document-ref"
        ItemType.BILLING_ADDRESS -> "billing-address"
        ItemType.PAYMENT_ACCOUNT -> "payment-account"
        ItemType.PASSWORD -> "password"
    }
    return item.replicaGroupId
        ?.takeIf { it.startsWith("$prefix:") && it.length > prefix.length + 1 }
        ?: "$prefix:${item.id}"
}

internal fun mdbx2PhysicalEntryId(vaultId: String, logicalEntryId: String): String =
    if (runCatching { UUID.fromString(logicalEntryId).toString() == logicalEntryId }.getOrDefault(false)) logicalEntryId else UUID.nameUUIDFromBytes(
        "monica-entry:$vaultId:$logicalEntryId".toByteArray(Charsets.UTF_8)
    ).toString()

internal fun mdbx2PhysicalAttachmentId(vaultId: String, logicalAttachmentId: String): String =
    UUID.nameUUIDFromBytes(
        "monica-attachment:$vaultId:$logicalAttachmentId".toByteArray(Charsets.UTF_8)
    ).toString()
