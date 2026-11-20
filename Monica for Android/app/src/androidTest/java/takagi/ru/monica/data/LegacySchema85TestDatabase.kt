package takagi.ru.monica.data

import androidx.room.*
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.keepass.KeePassPendingChange

/** Frozen pre-timeline schema-85 entity set; never add new entities here. */
@Database(
    entities = [
        PasswordEntry::class,
        SecureItem::class,
        Category::class,
        OperationLog::class,
        TimelineVersionSnapshot::class,
        LocalKeePassDatabase::class,
        KeepassRemoteSource::class,
        KeepassRemoteSyncState::class,
        KeepassGroupSyncConfig::class,
        CustomField::class,  // 自定义字段表
        PasswordPageAggregateStackEntry::class, // 密码页聚合堆叠元数据
        PasswordArchiveSyncMeta::class, // 归档同步元信息
        PasswordHistoryEntry::class, // 历史密码表
        PasskeyEntry::class,  // Passkey 通行密钥表
        // Bitwarden 集成表
        BitwardenVault::class,
        BitwardenFolder::class,
        BitwardenSend::class,
        BitwardenConflictBackup::class,
        BitwardenPendingOperation::class,
        BitwardenSyncRawEntryRecord::class,
        BitwardenCipherSource::class,
        BitwardenPasswordHistoryState::class,
        // 附件（可挂在 PasswordEntry 或 SecureItem 上，跨来源统一元数据）
        Attachment::class,
        // MDBX 数据库格式
        LocalMdbxDatabase::class,
        MdbxRemoteSource::class,
        MdbxSyncStateEntity::class,
        // KeePass entry-level pending changes
        KeePassPendingChange::class,
        LegacyDedupRecoveryEntity::class
    ],
    version = 85,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LegacySchema85TestDatabase : RoomDatabase() {
    abstract fun entries(): PasswordEntryDao
}
