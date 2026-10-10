package takagi.ru.monica.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import takagi.ru.monica.attachments.data.AttachmentDao
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.data.bitwarden.*
import takagi.ru.monica.keepass.KeePassPendingChange
import takagi.ru.monica.keepass.KeePassPendingChangeDao

/**
 * Room database for storing password entries and secure items
 */
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
        // 附件（可挂在 PasswordEntry 或 SecureItem 上，跨来源统一元数据）
        Attachment::class,
        // MDBX 数据库格式
        LocalMdbxDatabase::class,
        MdbxRemoteSource::class,
        MdbxSyncStateEntity::class,
        // KeePass entry-level pending changes
        KeePassPendingChange::class
    ],
    version = 80,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PasswordDatabase : RoomDatabase() {
    
    abstract fun passwordEntryDao(): PasswordEntryDao
    abstract fun secureItemDao(): SecureItemDao
    abstract fun categoryDao(): CategoryDao
    abstract fun operationLogDao(): OperationLogDao
    abstract fun timelineVersionSnapshotDao(): TimelineVersionSnapshotDao
    abstract fun localKeePassDatabaseDao(): LocalKeePassDatabaseDao
    abstract fun keepassRemoteSourceDao(): KeepassRemoteSourceDao
    abstract fun keepassRemoteSyncStateDao(): KeepassRemoteSyncStateDao
    abstract fun keepassGroupSyncConfigDao(): KeepassGroupSyncConfigDao
    abstract fun localMdbxDatabaseDao(): LocalMdbxDatabaseDao
    abstract fun mdbxRemoteSourceDao(): MdbxRemoteSourceDao
    abstract fun mdbxSyncStateDao(): MdbxSyncStateDao
    abstract fun customFieldDao(): CustomFieldDao  // 自定义字段 DAO
    abstract fun passwordPageAggregateStackDao(): PasswordPageAggregateStackDao
    abstract fun passwordArchiveSyncMetaDao(): PasswordArchiveSyncMetaDao
    abstract fun passwordHistoryDao(): PasswordHistoryDao
    abstract fun passkeyDao(): PasskeyDao  // Passkey DAO
    
    // Bitwarden DAOs
    abstract fun bitwardenVaultDao(): BitwardenVaultDao
    abstract fun bitwardenFolderDao(): BitwardenFolderDao
    abstract fun bitwardenSendDao(): BitwardenSendDao
    abstract fun bitwardenConflictBackupDao(): BitwardenConflictBackupDao
    abstract fun bitwardenPendingOperationDao(): BitwardenPendingOperationDao
    abstract fun bitwardenSyncRawEntryRecordDao(): BitwardenSyncRawEntryRecordDao

    // KeePass 增量变更队列
    abstract fun keepassPendingChangeDao(): KeePassPendingChangeDao

    // Attachment DAO（跨来源统一附件元数据）
    abstract fun attachmentDao(): AttachmentDao
    
    companion object {
        @Volatile
        private var INSTANCE: PasswordDatabase? = null
        
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 创建secure_items表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS secure_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        itemType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        itemData TEXT NOT NULL,
                        imagePaths TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }
        
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为secure_items表添加sortOrder字段
                database.execSQL("ALTER TABLE secure_items ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为password_entries表添加sortOrder字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为password_entries表添加isGroupCover字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN isGroupCover INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create ledger categories table
                database.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS ledger_categories (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            type TEXT,
                            iconKey TEXT NOT NULL,
                            colorHex TEXT NOT NULL,
                            sortOrder INTEGER NOT NULL DEFAULT 0,
                            parentId INTEGER,
                            FOREIGN KEY(parentId) REFERENCES ledger_categories(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED
                        )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_categories_parentId ON ledger_categories(parentId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_categories_type ON ledger_categories(type)")

                // Create ledger tags table
                database.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS ledger_tags (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            colorHex TEXT NOT NULL
                        )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_tags_name ON ledger_tags(name)")

                // Create ledger entries table
                database.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS ledger_entries (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            amountInCents INTEGER NOT NULL DEFAULT 0,
                            currencyCode TEXT NOT NULL,
                            type TEXT NOT NULL,
                            categoryId INTEGER,
                            linkedItemId INTEGER,
                            occurredAt INTEGER NOT NULL,
                            note TEXT NOT NULL DEFAULT '',
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(categoryId) REFERENCES ledger_categories(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
                            FOREIGN KEY(linkedItemId) REFERENCES secure_items(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED
                        )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_categoryId ON ledger_entries(categoryId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_linkedItemId ON ledger_entries(linkedItemId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_occurredAt ON ledger_entries(occurredAt)")

                // Create ledger entry-tag cross reference table
                database.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS ledger_entry_tag_cross_ref (
                            entryId INTEGER NOT NULL,
                            tagId INTEGER NOT NULL,
                            PRIMARY KEY(entryId, tagId),
                            FOREIGN KEY(entryId) REFERENCES ledger_entries(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
                            FOREIGN KEY(tagId) REFERENCES ledger_tags(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
                        )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entry_tag_cross_ref_tagId ON ledger_entry_tag_cross_ref(tagId)")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为ledger_entries表添加paymentMethod字段
                database.execSQL("ALTER TABLE ledger_entries ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 创建assets表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS assets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        assetType TEXT NOT NULL,
                        balanceInCents INTEGER NOT NULL DEFAULT 0,
                        currencyCode TEXT NOT NULL DEFAULT 'CNY',
                        iconKey TEXT NOT NULL DEFAULT 'wallet',
                        colorHex TEXT NOT NULL DEFAULT '#4CAF50',
                        linkedBankCardId INTEGER,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_assets_assetType ON assets(assetType)")
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为linkedBankCardId添加唯一索引
                // 1. 先删除重复的银行卡资产(保留最早创建的)
                database.execSQL("""
                    DELETE FROM assets 
                    WHERE id NOT IN (
                        SELECT MIN(id) 
                        FROM assets 
                        WHERE linkedBankCardId IS NOT NULL 
                        GROUP BY linkedBankCardId
                    ) AND linkedBankCardId IS NOT NULL
                """.trimIndent())
                
                // 2. 创建唯一索引
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_assets_linkedBankCardId ON assets(linkedBankCardId)")
            }
        }

        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为password_entries表添加应用包名和应用名称字段（用于自动填充）
                database.execSQL("ALTER TABLE password_entries ADD COLUMN appPackageName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN appName TEXT NOT NULL DEFAULT ''")
            }
        }

        // Phase 7: Migration 10 → 11 - 扩展数据模型
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 添加个人信息字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN email TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN phone TEXT NOT NULL DEFAULT ''")
                
                // 添加地址信息字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN addressLine TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN city TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN state TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN zipCode TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN country TEXT NOT NULL DEFAULT ''")
                
                // 添加支付信息字段 (加密存储)
                database.execSQL("ALTER TABLE password_entries ADD COLUMN creditCardNumber TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN creditCardHolder TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN creditCardExpiry TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN creditCardCVV TEXT NOT NULL DEFAULT ''")
            }
        }

        // Migration 11 → 12 - 删除记账功能相关表
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 删除所有记账相关的表
                database.execSQL("DROP TABLE IF EXISTS ledger_entries")
                database.execSQL("DROP TABLE IF EXISTS ledger_categories")
                database.execSQL("DROP TABLE IF EXISTS ledger_tags")
                database.execSQL("DROP TABLE IF EXISTS ledger_entry_tag_cross_ref")
                database.execSQL("DROP TABLE IF EXISTS assets")
            }
        }
        // Migration 12 → 13 - 预留版本 (Passkey 功能开发)
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 此版本暂无数据库结构变更
            }
        }
        
        // Migration 13 → 14 - 删除 Passkey 功能
        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 删除 passkeys 表 (如果存在)
                database.execSQL("DROP TABLE IF EXISTS passkeys")
            }
        }
        
        // Migration 14 → 15 - 扩展OTP支持 (HOTP/Steam/Yandex/mOTP)
        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 由于TotpData使用JSON存储在itemData字段中,
                // 新增的otpType、counter、pin字段通过Kotlin序列化的默认值机制自动处理
                // otpType默认为TOTP,确保向后兼容
                // 不需要修改数据库结构
                // 现有TOTP记录在反序列化时自动获得默认值: otpType=TOTP, counter=0, pin=""
            }
        }

        // Migration 15 → 16 - 空迁移(版本号占位)
        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 此版本暂无数据库结构变更
            }
        }

        // Migration 16 → 17 - 添加分类功能
        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create categories table
                database.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL DEFAULT 0)")
                
                // Add categoryId to password_entries
                database.execSQL("ALTER TABLE `password_entries` ADD COLUMN `categoryId` INTEGER DEFAULT NULL")
            }
        }

        // Migration 17 → 18 - 添加authenticatorKey字段
        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add authenticatorKey to password_entries
                database.execSQL("ALTER TABLE `password_entries` ADD COLUMN `authenticatorKey` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Migration 18 → 19 - 添加操作日志表
        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS operation_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        itemType TEXT NOT NULL,
                        itemId INTEGER NOT NULL,
                        itemTitle TEXT NOT NULL,
                        operationType TEXT NOT NULL,
                        changesJson TEXT NOT NULL DEFAULT '',
                        deviceId TEXT NOT NULL DEFAULT '',
                        deviceName TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_logs_timestamp ON operation_logs(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_logs_itemType ON operation_logs(itemType)")
            }
        }

        // Migration 19 → 20 - 添加 isReverted 字段
        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE operation_logs ADD COLUMN isReverted INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 20 → 21 - 添加回收站功能（软删除字段）
        private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 为 password_entries 表添加软删除字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE password_entries ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                
                // 为 secure_items 表添加软删除字段
                database.execSQL("ALTER TABLE secure_items ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE secure_items ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                
                // 创建索引以优化查询
                database.execSQL("CREATE INDEX IF NOT EXISTS index_password_entries_isDeleted ON password_entries(isDeleted)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_secure_items_isDeleted ON secure_items(isDeleted)")
            }
        }

        // Migration 21 → 22 - 添加第三方登录(SSO)字段
        private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 添加登录类型字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN loginType TEXT NOT NULL DEFAULT 'PASSWORD'")
                // 添加SSO提供商字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN ssoProvider TEXT NOT NULL DEFAULT ''")
                // 添加关联账号条目ID字段
                database.execSQL("ALTER TABLE password_entries ADD COLUMN ssoRefEntryId INTEGER DEFAULT NULL")
            }
        }
        
        // Migration 22 → 23 - 添加本地 KeePass 数据库管理表
        private val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_keepass_databases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        keyFileUri TEXT,
                        storage_location TEXT NOT NULL,
                        encrypted_password TEXT,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        last_accessed_at INTEGER NOT NULL,
                        last_synced_at INTEGER,
                        is_default INTEGER NOT NULL,
                        entry_count INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_keepass_databases_storage_location ON local_keepass_databases(storage_location)")
            }
        }
        
        // Migration 23 → 24 - 为密码条目添加 KeePass 数据库归属字段
        private val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE password_entries ADD COLUMN keepassDatabaseId INTEGER DEFAULT NULL")
            }
        }
        
        // Migration 24 → 25 - 修复 local_keepass_databases 表结构（保留数据）
        private val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 检查表是否存在并需要修复
                try {
                    // 1. 重命名旧表
                    database.execSQL("ALTER TABLE local_keepass_databases RENAME TO local_keepass_databases_backup")
                    
                    // 2. 创建正确结构的新表（统一不包含 keyFileUri，留给 25->26 添加）
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS local_keepass_databases (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            filePath TEXT NOT NULL,
                            storage_location TEXT NOT NULL,
                            encrypted_password TEXT,
                            description TEXT,
                            created_at INTEGER NOT NULL,
                            last_accessed_at INTEGER NOT NULL,
                            last_synced_at INTEGER,
                            is_default INTEGER NOT NULL,
                            entry_count INTEGER NOT NULL,
                            sort_order INTEGER NOT NULL
                        )
                    """.trimIndent())
                    
                    // 3. 复制数据
                    database.execSQL("""
                        INSERT INTO local_keepass_databases 
                        SELECT id, name, filePath, storage_location, encrypted_password, description,
                               created_at, last_accessed_at, last_synced_at, is_default, entry_count, sort_order
                        FROM local_keepass_databases_backup
                    """.trimIndent())
                    
                    // 4. 删除备份表
                    database.execSQL("DROP TABLE IF EXISTS local_keepass_databases_backup")
                    
                    // 5. 重建索引
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_local_keepass_databases_storage_location ON local_keepass_databases(storage_location)")
                } catch (e: Exception) {
                    // 如果出错（例如旧表不存在），确保新建一个干净的表
                    database.execSQL("DROP TABLE IF EXISTS local_keepass_databases_backup")
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS local_keepass_databases (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            filePath TEXT NOT NULL,
                            storage_location TEXT NOT NULL,
                            encrypted_password TEXT,
                            description TEXT,
                            created_at INTEGER NOT NULL,
                            last_accessed_at INTEGER NOT NULL,
                            last_synced_at INTEGER,
                            is_default INTEGER NOT NULL,
                            entry_count INTEGER NOT NULL,
                            sort_order INTEGER NOT NULL
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_local_keepass_databases_storage_location ON local_keepass_databases(storage_location)")
                }
            }
        }
        
        // Migration 25 → 26 - 为本地 KeePass 数据库添加密钥文件字段
        // 修复版：增加容错检查，防止重复添加字段导致崩溃
        private val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val cursor = database.query("PRAGMA table_info(local_keepass_databases)")
                var hasColumn = false
                try {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex != -1 && cursor.getString(nameIndex) == "keyFileUri") {
                            hasColumn = true
                            break
                        }
                    }
                } finally {
                    cursor.close()
                }

                if (!hasColumn) {
                    try {
                        database.execSQL("ALTER TABLE local_keepass_databases ADD COLUMN keyFileUri TEXT")
                    } catch (e: Exception) {
                        // 忽略错误，例如字段已存在（虽然我们检查了，但为了双重保险）
                        android.util.Log.e("PasswordDatabase", "Failed to add column keyFileUri: ${e.message}")
                    }
                }
            }
        }
        
        // Migration 26 → 27 - 添加自定义字段表 (custom_fields)
        // 支持每个密码条目拥有无限个自定义键值对
        private val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 迁移前检查：表是否已存在
                val cursor = database.query("SELECT name FROM sqlite_master WHERE type='table' AND name='custom_fields'")
                val tableExists = cursor.count > 0
                cursor.close()
                
                if (tableExists) {
                    android.util.Log.w("PasswordDatabase", "custom_fields table already exists, skipping creation")
                    return
                }
                
                try {
                    // 创建自定义字段表
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS custom_fields (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            entry_id INTEGER NOT NULL,
                            title TEXT NOT NULL,
                            value TEXT NOT NULL,
                            is_protected INTEGER NOT NULL DEFAULT 0,
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(entry_id) REFERENCES password_entries(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    
                    // 创建索引以提升查询性能
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_custom_fields_entry_id ON custom_fields(entry_id)")
                    
                    android.util.Log.i("PasswordDatabase", "Successfully created custom_fields table")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Failed to create custom_fields table: ${e.message}")
                    // 不抛出异常，让迁移继续，避免应用崩溃
                    // Room 会在后续操作中处理不一致性
                }
            }
        }
        
        // Migration 27 → 28 - 添加 Passkey 通行密钥表
        // 支持 FIDO2/WebAuthn 标准的 Passkey 存储
        private val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 迁移前检查：表是否已存在
                val cursor = database.query("SELECT name FROM sqlite_master WHERE type='table' AND name='passkeys'")
                val tableExists = cursor.count > 0
                cursor.close()
                
                if (tableExists) {
                    android.util.Log.w("PasswordDatabase", "passkeys table already exists, skipping creation")
                    return
                }
                
                try {
                    // 创建 Passkey 表
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS passkeys (
                            credential_id TEXT PRIMARY KEY NOT NULL,
                            rp_id TEXT NOT NULL,
                            rp_name TEXT NOT NULL,
                            user_id TEXT NOT NULL,
                            user_name TEXT NOT NULL,
                            user_display_name TEXT NOT NULL,
                            public_key_algorithm INTEGER NOT NULL DEFAULT -7,
                            public_key TEXT NOT NULL,
                            private_key_alias TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            last_used_at INTEGER NOT NULL,
                            use_count INTEGER NOT NULL DEFAULT 0,
                            icon_url TEXT,
                            is_discoverable INTEGER NOT NULL DEFAULT 1,
                            is_user_verification_required INTEGER NOT NULL DEFAULT 1,
                            transports TEXT NOT NULL DEFAULT 'internal',
                            aaguid TEXT NOT NULL DEFAULT '',
                            sign_count INTEGER NOT NULL DEFAULT 0,
                            is_backed_up INTEGER NOT NULL DEFAULT 0,
                            notes TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())
                    
                    // 创建索引以提升查询性能
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_rp_id ON passkeys(rp_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_user_name ON passkeys(user_name)")
                    
                    android.util.Log.i("PasswordDatabase", "Successfully created passkeys table")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Failed to create passkeys table: ${e.message}")
                }
            }
        }
        
        // Migration 28 → 29 - 为 secure_items 添加 categoryId 字段（验证器分类功能）
        private val MIGRATION_28_29 = object : androidx.room.migration.Migration(28, 29) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 检查字段是否已存在
                val cursor = database.query("PRAGMA table_info(secure_items)")
                var hasColumn = false
                try {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex != -1 && cursor.getString(nameIndex) == "categoryId") {
                            hasColumn = true
                            break
                        }
                    }
                } finally {
                    cursor.close()
                }
                
                if (!hasColumn) {
                    try {
                        database.execSQL("ALTER TABLE secure_items ADD COLUMN categoryId INTEGER DEFAULT NULL")
                        android.util.Log.i("PasswordDatabase", "Successfully added categoryId column to secure_items")
                    } catch (e: Exception) {
                        android.util.Log.e("PasswordDatabase", "Failed to add categoryId column: ${e.message}")
                    }
                }
            }
        }
        
        // Migration 29 → 30 - Bitwarden 集成
        // 添加 Bitwarden 相关的表和字段
        private val MIGRATION_29_30 = object : androidx.room.migration.Migration(29, 30) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                android.util.Log.i("PasswordDatabase", "Starting Migration 29→30: Bitwarden Integration")
                
                try {
                    // 1. 创建 bitwarden_vaults 表
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS bitwarden_vaults (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            email TEXT NOT NULL,
                            user_id TEXT,
                            display_name TEXT,
                            server_url TEXT NOT NULL DEFAULT 'https://vault.bitwarden.com',
                            identity_url TEXT NOT NULL DEFAULT 'https://identity.bitwarden.com',
                            api_url TEXT NOT NULL DEFAULT 'https://api.bitwarden.com',
                            events_url TEXT,
                            encrypted_access_token TEXT,
                            encrypted_refresh_token TEXT,
                            access_token_expires_at INTEGER,
                            encrypted_master_key TEXT,
                            encrypted_enc_key TEXT,
                            encrypted_mac_key TEXT,
                            kdf_type INTEGER NOT NULL DEFAULT 0,
                            kdf_iterations INTEGER NOT NULL DEFAULT 600000,
                            kdf_memory INTEGER,
                            kdf_parallelism INTEGER,
                            last_sync_at INTEGER,
                            last_full_sync_at INTEGER,
                            revision_date TEXT,
                            is_default INTEGER NOT NULL DEFAULT 0,
                            is_locked INTEGER NOT NULL DEFAULT 1,
                            is_connected INTEGER NOT NULL DEFAULT 0,
                            sync_enabled INTEGER NOT NULL DEFAULT 1,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                    """.trimIndent())
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bitwarden_vaults_email ON bitwarden_vaults(email)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_vaults_isDefault ON bitwarden_vaults(is_default)")
                    
                    // 2. 创建 bitwarden_folders 表
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS bitwarden_folders (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            vault_id INTEGER NOT NULL,
                            bitwarden_folder_id TEXT NOT NULL,
                            name TEXT NOT NULL,
                            encrypted_name TEXT,
                            revision_date TEXT NOT NULL,
                            last_synced_at INTEGER NOT NULL,
                            is_local_modified INTEGER NOT NULL DEFAULT 0,
                            local_monica_category_id INTEGER,
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(vault_id) REFERENCES bitwarden_vaults(id) ON DELETE NO ACTION ON UPDATE NO ACTION
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_folders_vault_id ON bitwarden_folders(vault_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bitwarden_folders_bitwarden_folder_id ON bitwarden_folders(bitwarden_folder_id)")
                    
                    // 3. 创建 bitwarden_conflict_backups 表
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS bitwarden_conflict_backups (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            vault_id INTEGER NOT NULL,
                            entry_id INTEGER,
                            bitwarden_cipher_id TEXT,
                            conflict_type TEXT NOT NULL,
                            local_data_json TEXT NOT NULL,
                            server_data_json TEXT,
                            local_revision_date TEXT,
                            server_revision_date TEXT,
                            entry_title TEXT NOT NULL,
                            description TEXT,
                            is_resolved INTEGER NOT NULL DEFAULT 0,
                            resolution TEXT,
                            resolved_at INTEGER,
                            created_at INTEGER NOT NULL,
                            FOREIGN KEY(vault_id) REFERENCES bitwarden_vaults(id) ON DELETE NO ACTION ON UPDATE NO ACTION
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_conflict_backups_vault_id ON bitwarden_conflict_backups(vault_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_conflict_backups_entry_id ON bitwarden_conflict_backups(entry_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_conflict_backups_conflict_type ON bitwarden_conflict_backups(conflict_type)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_conflict_backups_created_at ON bitwarden_conflict_backups(created_at)")
                    
                    // 4. 创建 bitwarden_pending_operations 表
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS bitwarden_pending_operations (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            vault_id INTEGER NOT NULL,
                            entry_id INTEGER,
                            bitwarden_cipher_id TEXT,
                            operation_type TEXT NOT NULL,
                            target_type TEXT NOT NULL,
                            payload_json TEXT NOT NULL,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            retry_count INTEGER NOT NULL DEFAULT 0,
                            max_retries INTEGER NOT NULL DEFAULT 3,
                            last_error TEXT,
                            last_attempt_at INTEGER,
                            created_at INTEGER NOT NULL,
                            completed_at INTEGER,
                            FOREIGN KEY(vault_id) REFERENCES bitwarden_vaults(id) ON DELETE NO ACTION ON UPDATE NO ACTION
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_pending_operations_vault_id ON bitwarden_pending_operations(vault_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_pending_operations_status ON bitwarden_pending_operations(status)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_pending_operations_created_at ON bitwarden_pending_operations(created_at)")
                    
                    // 5. 为 password_entries 添加 Bitwarden 字段
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN bitwarden_vault_id INTEGER DEFAULT NULL")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN bitwarden_cipher_id TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN bitwarden_folder_id TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN bitwarden_revision_date TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN bitwarden_cipher_type INTEGER NOT NULL DEFAULT 1")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN bitwarden_local_modified INTEGER NOT NULL DEFAULT 0")
                    
                    android.util.Log.i("PasswordDatabase", "Migration 29→30 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 29→30 failed: ${e.message}")
                    // 不抛出异常，让应用继续运行
                    // Room 会在后续操作中处理不一致性
                }
            }
        }
        
        /**
         * Migration 30 -> 31: 扩展 Bitwarden 同步支持多数据类型
         * 
         * 添加内容:
         * 1. 为 bitwarden_pending_operations 添加 item_type 字段
         * 2. 为 secure_items 添加 Bitwarden 关联字段
         * 3. 为 passkeys 添加 Bitwarden 关联字段
         * 4. 为 categories 添加 Bitwarden 文件夹关联字段
         */
        private val MIGRATION_30_31 = object : androidx.room.migration.Migration(30, 31) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 30→31: Bitwarden multi-type sync support")
                    
                    // 1. 为 bitwarden_pending_operations 添加 item_type 字段
                    database.execSQL(
                        "ALTER TABLE bitwarden_pending_operations ADD COLUMN item_type TEXT NOT NULL DEFAULT 'PASSWORD'"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_bitwarden_pending_operations_item_type ON bitwarden_pending_operations(item_type)"
                    )
                    
                    // 2. 为 secure_items 添加 Bitwarden 关联字段
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN bitwarden_vault_id INTEGER DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN bitwarden_cipher_id TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN bitwarden_folder_id TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN bitwarden_revision_date TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN bitwarden_local_modified INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'NONE'"
                    )
                    
                    // 3. 为 passkeys 添加 Bitwarden 关联字段
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN bitwarden_vault_id INTEGER DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN bitwarden_cipher_id TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'NONE'"
                    )
                    
                    // 4. 为 categories 添加 Bitwarden 文件夹关联字段
                    database.execSQL(
                        "ALTER TABLE categories ADD COLUMN bitwarden_vault_id INTEGER DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE categories ADD COLUMN bitwarden_folder_id TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE categories ADD COLUMN sync_item_types TEXT DEFAULT NULL"
                    )
                    
                    android.util.Log.i("PasswordDatabase", "Migration 30→31 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 30→31 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 31 -> 32: 为 passkeys 添加绑定密码字段
         */
        private val MIGRATION_31_32 = object : androidx.room.migration.Migration(31, 32) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 31→32: passkey bound password")
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN bound_password_id INTEGER DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 31→32 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 31→32 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 32 -> 33: 为 password_entries 添加通行密钥绑定字段
         */
        private val MIGRATION_32_33 = object : androidx.room.migration.Migration(32, 33) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 32→33: password passkey bindings")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN passkey_bindings TEXT NOT NULL DEFAULT ''"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 32→33 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 32→33 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 33 -> 34: 添加 KeePass 组同步映射表
         */
        private val MIGRATION_33_34 = object : androidx.room.migration.Migration(33, 34) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 33→34: keepass group sync configs")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS keepass_group_sync_configs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            keepassDatabaseId INTEGER NOT NULL,
                            groupPath TEXT NOT NULL,
                            groupUuid TEXT,
                            bitwarden_vault_id INTEGER DEFAULT NULL,
                            bitwarden_folder_id TEXT DEFAULT NULL,
                            sync_item_types TEXT DEFAULT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_keepass_group_sync_configs_keepassDatabaseId_groupPath ON keepass_group_sync_configs(keepassDatabaseId, groupPath)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 33→34 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 33→34 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 34 -> 35: 添加 Bitwarden Send 本地缓存表
         */
        private val MIGRATION_34_35 = object : androidx.room.migration.Migration(34, 35) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 34→35: bitwarden sends cache")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS bitwarden_sends (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            vault_id INTEGER NOT NULL,
                            bitwarden_send_id TEXT NOT NULL,
                            access_id TEXT NOT NULL,
                            key_base64 TEXT,
                            type INTEGER NOT NULL DEFAULT 0,
                            name TEXT NOT NULL,
                            notes TEXT NOT NULL DEFAULT '',
                            text_content TEXT,
                            is_text_hidden INTEGER NOT NULL DEFAULT 0,
                            file_name TEXT,
                            file_size TEXT,
                            access_count INTEGER NOT NULL DEFAULT 0,
                            max_access_count INTEGER,
                            has_password INTEGER NOT NULL DEFAULT 0,
                            disabled INTEGER NOT NULL DEFAULT 0,
                            hide_email INTEGER NOT NULL DEFAULT 0,
                            revision_date TEXT NOT NULL DEFAULT '',
                            expiration_date TEXT,
                            deletion_date TEXT,
                            share_url TEXT NOT NULL DEFAULT '',
                            last_synced_at INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            FOREIGN KEY(vault_id) REFERENCES bitwarden_vaults(id) ON DELETE NO ACTION ON UPDATE NO ACTION
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_sends_vault_id ON bitwarden_sends(vault_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_sends_bitwarden_send_id ON bitwarden_sends(bitwarden_send_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bitwarden_sends_vault_id_bitwarden_send_id ON bitwarden_sends(vault_id, bitwarden_send_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bitwarden_sends_updated_at ON bitwarden_sends(updated_at)")
                    android.util.Log.i("PasswordDatabase", "Migration 34→35 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 34→35 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 35 -> 36: 为 passkeys 添加 category_id 字段，接入统一文件夹体系
         */
        private val MIGRATION_35_36 = object : androidx.room.migration.Migration(35, 36) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 35→36: passkeys category_id")
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN category_id INTEGER DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 35→36 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 35→36 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 36 -> 37:
         * 1. 为 secure_items 添加 keepass_database_id（统一目标存储）
         * 2. 为 passkeys 添加 keepass_database_id（通行密钥目标存储）
         */
        private val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 36→37: keepass_database_id for secure_items/passkeys")
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN keepass_database_id INTEGER DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN keepass_database_id INTEGER DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 36→37 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 36→37 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 37 -> 38:
         * 1. 为 password_entries 添加 keepassGroupPath（支持 KeePass 分组精确过滤）
         * 2. 为 secure_items 添加 keepass_group_path（支持笔记/验证器分组精确过滤）
         */
        private val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 37→38: keepass group path")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN keepassGroupPath TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN keepass_group_path TEXT DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 37→38 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 37→38 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 38 -> 39:
         * 为 password_entries 添加自定义图标字段
         */
        private val MIGRATION_38_39 = object : androidx.room.migration.Migration(38, 39) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 38→39: custom password icons")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN customIconType TEXT NOT NULL DEFAULT 'NONE'"
                    )
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN customIconValue TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN customIconUpdatedAt INTEGER NOT NULL DEFAULT 0"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 38→39 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 38→39 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 39 -> 40:
         * 为 passkeys 添加 passkey_mode 字段。
         * 旧数据全部标记为 LEGACY，后续新建条目显式写入 BW_COMPAT。
         */
        private val MIGRATION_39_40 = object : androidx.room.migration.Migration(39, 40) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 39→40: passkey mode")
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN passkey_mode TEXT NOT NULL DEFAULT 'LEGACY'"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 39→40 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 39→40 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 40 -> 41:
         * 为 password_entries 添加归档字段，并创建索引。
         */
        private val MIGRATION_40_41 = object : androidx.room.migration.Migration(40, 41) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 40→41: password archive fields")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN archivedAt INTEGER DEFAULT NULL"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_entries_isArchived ON password_entries(isArchived)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 40→41 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 40→41 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 41 -> 42:
         * 为 passkeys 增加精确存储定位字段（KeePass 分组路径 / Bitwarden 文件夹）。
         */
        private val MIGRATION_41_42 = object : androidx.room.migration.Migration(41, 42) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 41→42: passkey folder/group fields")
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN keepass_group_path TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE passkeys ADD COLUMN bitwarden_folder_id TEXT DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 41→42 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 41→42 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 42 -> 43:
         * 仅清理已下线的 KeePass WebDAV 残余数据，不改变现有功能行为。
         */
        private val MIGRATION_42_43 = object : androidx.room.migration.Migration(42, 43) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 42→43: cleanup legacy KeePass WebDAV data")
                    val legacyWebDavIdSubQuery = "SELECT id FROM local_keepass_databases WHERE filePath LIKE 'webdav://%'"

                    // 清理依赖于 legacy KeePass WebDAV 库的归属引用。
                    database.execSQL(
                        "UPDATE password_entries SET keepassDatabaseId = NULL, keepassGroupPath = NULL WHERE keepassDatabaseId IN ($legacyWebDavIdSubQuery)"
                    )
                    database.execSQL(
                        "UPDATE secure_items SET keepass_database_id = NULL, keepass_group_path = NULL WHERE keepass_database_id IN ($legacyWebDavIdSubQuery)"
                    )
                    database.execSQL(
                        "UPDATE passkeys SET keepass_database_id = NULL, keepass_group_path = NULL WHERE keepass_database_id IN ($legacyWebDavIdSubQuery)"
                    )
                    database.execSQL(
                        "DELETE FROM keepass_group_sync_configs WHERE keepassDatabaseId IN ($legacyWebDavIdSubQuery)"
                    )
                    database.execSQL(
                        "DELETE FROM local_keepass_databases WHERE filePath LIKE 'webdav://%'"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 42→43 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 42→43 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 43 -> 44:
         * 1. 清理 Bitwarden 多库场景下遗留的重复映射数据（按 vault+cipher 收敛）。
         * 2. 为 password_entries / secure_items 添加 vault+cipher 唯一索引，防止重复插入。
         * 3. 为 passkeys 添加 vault+cipher 查询索引（非唯一，兼容一个 cipher 多凭据）。
         */
        private val MIGRATION_43_44 = object : androidx.room.migration.Migration(43, 44) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 43→44: bitwarden vault+cipher uniqueness hardening")

                    database.execSQL(
                        """
                        DELETE FROM password_entries
                        WHERE bitwarden_vault_id IS NOT NULL
                          AND bitwarden_cipher_id IS NOT NULL
                          AND id NOT IN (
                              SELECT MAX(id)
                              FROM password_entries
                              WHERE bitwarden_vault_id IS NOT NULL
                                AND bitwarden_cipher_id IS NOT NULL
                              GROUP BY bitwarden_vault_id, bitwarden_cipher_id
                          )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        DELETE FROM secure_items
                        WHERE bitwarden_vault_id IS NOT NULL
                          AND bitwarden_cipher_id IS NOT NULL
                          AND id NOT IN (
                              SELECT MAX(id)
                              FROM secure_items
                              WHERE bitwarden_vault_id IS NOT NULL
                                AND bitwarden_cipher_id IS NOT NULL
                              GROUP BY bitwarden_vault_id, bitwarden_cipher_id
                          )
                        """.trimIndent()
                    )

                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_password_entries_bitwarden_vault_cipher_unique ON password_entries(bitwarden_vault_id, bitwarden_cipher_id)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_secure_items_bitwarden_vault_cipher_unique ON secure_items(bitwarden_vault_id, bitwarden_cipher_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_passkeys_bitwarden_vault_cipher ON passkeys(bitwarden_vault_id, bitwarden_cipher_id)"
                    )

                    android.util.Log.i("PasswordDatabase", "Migration 43→44 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 43→44 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 44 -> 45:
         * 增加密码归档同步元信息表（统一归档视图 + 分数据源适配）。
         */
        private val MIGRATION_44_45 = object : androidx.room.migration.Migration(44, 45) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 44→45: password archive sync metadata")

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS password_archive_sync_meta (
                            entry_id INTEGER NOT NULL,
                            provider_type TEXT NOT NULL DEFAULT 'LOCAL',
                            origin_keepass_database_id INTEGER DEFAULT NULL,
                            origin_keepass_group_path TEXT DEFAULT NULL,
                            origin_bitwarden_folder_id TEXT DEFAULT NULL,
                            sync_status TEXT NOT NULL DEFAULT 'SYNCED',
                            last_error TEXT DEFAULT NULL,
                            updated_at INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(entry_id),
                            FOREIGN KEY(entry_id) REFERENCES password_entries(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_archive_sync_meta_provider_type ON password_archive_sync_meta(provider_type)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_archive_sync_meta_sync_status ON password_archive_sync_meta(sync_status)"
                    )

                    android.util.Log.i("PasswordDatabase", "Migration 44→45 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 44→45 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 45 -> 46:
         * 为 local_keepass_databases 增加 KDBX 配置字段（版本/算法/KDF 参数）。
         */
        private val MIGRATION_45_46 = object : androidx.room.migration.Migration(45, 46) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 45→46: keepass kdbx config columns")
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN kdbx_major_version INTEGER NOT NULL DEFAULT 4"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN cipher_algorithm TEXT NOT NULL DEFAULT 'AES'"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN kdf_algorithm TEXT NOT NULL DEFAULT 'ARGON2D'"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN kdf_transform_rounds INTEGER NOT NULL DEFAULT 8"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN kdf_memory_bytes INTEGER NOT NULL DEFAULT 33554432"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN kdf_parallelism INTEGER NOT NULL DEFAULT 2"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 45→46 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 45→46 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 46 -> 47:
         * 为 KeePass 映射增加原生条目 UUID / 分组 UUID 字段。
         */
        private val MIGRATION_46_47 = object : androidx.room.migration.Migration(46, 47) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 46→47: keepass native uuid columns")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN keepass_entry_uuid TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE password_entries ADD COLUMN keepass_group_uuid TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE secure_items ADD COLUMN keepass_entry_uuid TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE secure_items ADD COLUMN keepass_group_uuid TEXT DEFAULT NULL")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_entries_keepass_entry_uuid ON password_entries(keepass_entry_uuid)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_secure_items_keepass_entry_uuid ON secure_items(keepass_entry_uuid)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 46→47 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 46→47 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 47 -> 48:
         * 为 Bitwarden vault 增加自签名证书与 mTLS 配置字段。
         */
        private val MIGRATION_47_48 = object : androidx.room.migration.Migration(47, 48) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 47→48: bitwarden tls columns")
                    database.execSQL("ALTER TABLE bitwarden_vaults ADD COLUMN tls_certificate_alias TEXT")
                    database.execSQL("ALTER TABLE bitwarden_vaults ADD COLUMN tls_ca_certificate_pem TEXT")
                    database.execSQL("ALTER TABLE bitwarden_vaults ADD COLUMN tls_mtls_enabled INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE bitwarden_vaults ADD COLUMN tls_client_cert_pkcs12 TEXT")
                    database.execSQL("ALTER TABLE bitwarden_vaults ADD COLUMN tls_encrypted_client_cert_password TEXT")
                    android.util.Log.i("PasswordDatabase", "Migration 47→48 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 47→48 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 48 -> 49:
         * 增加历史密码表，用于密码详情页展示历史密码。
         */
        private val MIGRATION_48_49 = object : androidx.room.migration.Migration(48, 49) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 48→49: password history table")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS password_history_entries (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            entry_id INTEGER NOT NULL,
                            password TEXT NOT NULL,
                            last_used_at INTEGER NOT NULL,
                            FOREIGN KEY(entry_id) REFERENCES password_entries(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_history_entries_entry_id ON password_history_entries(entry_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_history_entries_entry_id_last_used_at ON password_history_entries(entry_id, last_used_at)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 48→49 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 48→49 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 49 -> 50:
         * 为密码条目增加结构化 SSH 密钥字段。
         */
        private val MIGRATION_49_50 = object : androidx.room.migration.Migration(49, 50) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 49→50: ssh key data column")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN ssh_key_data TEXT NOT NULL DEFAULT ''"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 49→50 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 49→50 failed: ${e.message}")
                }
            }
        }

        /**
         * Migration 50 -> 51:
         * 为密码页聚合视图增加跨类型手动堆叠元数据表。
         */
        private val MIGRATION_50_51 = object : androidx.room.migration.Migration(50, 51) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 50→51: aggregate stack metadata table")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS password_page_aggregate_stack_entries (
                            item_key TEXT NOT NULL PRIMARY KEY,
                            stack_group_id TEXT NOT NULL,
                            stack_order INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_page_aggregate_stack_entries_group ON password_page_aggregate_stack_entries(stack_group_id)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 50→51 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 50→51 failed: ${e.message}")
                }
            }
        }

        private val MIGRATION_51_52 = object : androidx.room.migration.Migration(51, 52) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 51→52: password replica group id")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN replica_group_id TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_password_entries_replica_group_id ON password_entries(replica_group_id)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 51→52 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 51→52 failed: ${e.message}")
                }
            }
        }

        private val MIGRATION_52_53 = object : androidx.room.migration.Migration(52, 53) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 52→53: secure item replica group id")
                    database.execSQL(
                        "ALTER TABLE secure_items ADD COLUMN replica_group_id TEXT DEFAULT NULL"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_secure_items_replica_group_id ON secure_items(replica_group_id)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 52→53 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 52→53 failed: ${e.message}")
                }
            }
        }

        private val MIGRATION_53_54 = object : androidx.room.migration.Migration(53, 54) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 53→54: password bound note id")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN boundNoteId INTEGER DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 53→54 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 53→54 failed: ${e.message}")
                }
            }
        }

        private val MIGRATION_54_55 = object : androidx.room.migration.Migration(54, 55) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 54→55: bitwarden raw entry records")
                    // Recreate to guarantee exact Room schema (column default + index names).
                    database.execSQL("DROP TABLE IF EXISTS bitwarden_sync_raw_entry_records")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS bitwarden_sync_raw_entry_records (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            vault_id INTEGER NOT NULL,
                            bitwarden_cipher_id TEXT NOT NULL,
                            operation TEXT NOT NULL,
                            endpoint TEXT NOT NULL,
                            payload_cipher_text TEXT NOT NULL,
                            payload_digest TEXT NOT NULL,
                            payload_source TEXT NOT NULL,
                            response_code INTEGER,
                            success INTEGER NOT NULL DEFAULT 1,
                            captured_at INTEGER NOT NULL,
                            FOREIGN KEY(vault_id) REFERENCES bitwarden_vaults(id) ON DELETE NO ACTION ON UPDATE NO ACTION
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_bitwarden_sync_raw_entry_records_vault_id_bitwarden_cipher_id_captured_at ON bitwarden_sync_raw_entry_records(vault_id, bitwarden_cipher_id, captured_at)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_bitwarden_sync_raw_entry_records_captured_at ON bitwarden_sync_raw_entry_records(captured_at)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 54→55 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 54→55 failed: ${e.message}")
                }
            }
        }

        private val MIGRATION_55_56 = object : androidx.room.migration.Migration(55, 56) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 55→56: passkey internal record id")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS passkeys_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            credential_id TEXT NOT NULL,
                            rp_id TEXT NOT NULL,
                            rp_name TEXT NOT NULL,
                            user_id TEXT NOT NULL,
                            user_name TEXT NOT NULL,
                            user_display_name TEXT NOT NULL,
                            public_key_algorithm INTEGER NOT NULL DEFAULT -7,
                            public_key TEXT NOT NULL,
                            private_key_alias TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            last_used_at INTEGER NOT NULL,
                            use_count INTEGER NOT NULL DEFAULT 0,
                            icon_url TEXT,
                            is_discoverable INTEGER NOT NULL DEFAULT 1,
                            is_user_verification_required INTEGER NOT NULL DEFAULT 1,
                            transports TEXT NOT NULL DEFAULT 'internal',
                            aaguid TEXT NOT NULL DEFAULT '',
                            sign_count INTEGER NOT NULL DEFAULT 0,
                            is_backed_up INTEGER NOT NULL DEFAULT 0,
                            notes TEXT NOT NULL DEFAULT '',
                            bound_password_id INTEGER DEFAULT NULL,
                            category_id INTEGER DEFAULT NULL,
                            keepass_database_id INTEGER DEFAULT NULL,
                            keepass_group_path TEXT DEFAULT NULL,
                            bitwarden_vault_id INTEGER DEFAULT NULL,
                            bitwarden_folder_id TEXT DEFAULT NULL,
                            bitwarden_cipher_id TEXT DEFAULT NULL,
                            sync_status TEXT NOT NULL DEFAULT 'NONE',
                            passkey_mode TEXT NOT NULL DEFAULT 'LEGACY'
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO passkeys_new (
                            credential_id,
                            rp_id,
                            rp_name,
                            user_id,
                            user_name,
                            user_display_name,
                            public_key_algorithm,
                            public_key,
                            private_key_alias,
                            created_at,
                            last_used_at,
                            use_count,
                            icon_url,
                            is_discoverable,
                            is_user_verification_required,
                            transports,
                            aaguid,
                            sign_count,
                            is_backed_up,
                            notes,
                            bound_password_id,
                            category_id,
                            keepass_database_id,
                            keepass_group_path,
                            bitwarden_vault_id,
                            bitwarden_folder_id,
                            bitwarden_cipher_id,
                            sync_status,
                            passkey_mode
                        )
                        SELECT
                            credential_id,
                            rp_id,
                            rp_name,
                            user_id,
                            user_name,
                            user_display_name,
                            public_key_algorithm,
                            public_key,
                            private_key_alias,
                            created_at,
                            last_used_at,
                            use_count,
                            icon_url,
                            is_discoverable,
                            is_user_verification_required,
                            transports,
                            aaguid,
                            sign_count,
                            is_backed_up,
                            notes,
                            bound_password_id,
                            category_id,
                            keepass_database_id,
                            keepass_group_path,
                            bitwarden_vault_id,
                            bitwarden_folder_id,
                            bitwarden_cipher_id,
                            sync_status,
                            passkey_mode
                        FROM passkeys
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE passkeys")
                    database.execSQL("ALTER TABLE passkeys_new RENAME TO passkeys")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_credential_id ON passkeys(credential_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_rp_id ON passkeys(rp_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_user_name ON passkeys(user_name)")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_passkeys_bitwarden_vault_cipher ON passkeys(bitwarden_vault_id, bitwarden_cipher_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_passkeys_bitwarden_scope_credential ON passkeys(bitwarden_vault_id, bitwarden_cipher_id, credential_id)"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 55→56 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 55→56 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_56_57 = object : androidx.room.migration.Migration(56, 57) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting Migration 56→57: KeePass remote source foundation")

                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL_INTERNAL'"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN source_id INTEGER"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN open_mode TEXT NOT NULL DEFAULT 'DIRECT'"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN working_copy_path TEXT"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN cache_copy_path TEXT"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN is_offline_available INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN last_sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY'"
                    )
                    database.execSQL(
                        "ALTER TABLE local_keepass_databases ADD COLUMN last_sync_error TEXT"
                    )
                    database.execSQL(
                        "UPDATE local_keepass_databases SET source_type = 'LOCAL_INTERNAL', is_offline_available = 1 WHERE storage_location = 'INTERNAL'"
                    )
                    database.execSQL(
                        "UPDATE local_keepass_databases SET source_type = 'LOCAL_DOCUMENT_URI', is_offline_available = 0 WHERE storage_location = 'EXTERNAL'"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_local_keepass_databases_source_type ON local_keepass_databases(source_type)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_local_keepass_databases_source_id ON local_keepass_databases(source_id)"
                    )

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS keepass_remote_sources (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_type TEXT NOT NULL,
                            display_name TEXT NOT NULL,
                            remote_path TEXT NOT NULL,
                            remote_parent_path TEXT,
                            base_url TEXT,
                            account_id TEXT,
                            drive_id TEXT,
                            item_id TEXT,
                            username_encrypted TEXT,
                            password_encrypted TEXT,
                            token_ref TEXT,
                            allow_metered_network INTEGER NOT NULL DEFAULT 0,
                            auto_sync_enabled INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_keepass_remote_sources_provider_type ON keepass_remote_sources(provider_type)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_keepass_remote_sources_display_name ON keepass_remote_sources(display_name)"
                    )

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS keepass_remote_sync_states (
                            database_id INTEGER NOT NULL,
                            remote_version_token TEXT,
                            remote_etag TEXT,
                            remote_last_modified INTEGER,
                            base_hash TEXT,
                            working_hash TEXT,
                            has_local_changes INTEGER NOT NULL DEFAULT 0,
                            has_remote_changes INTEGER NOT NULL DEFAULT 0,
                            sync_phase TEXT NOT NULL DEFAULT 'IDLE',
                            last_success_at INTEGER,
                            last_failure_at INTEGER,
                            failure_code TEXT,
                            failure_message TEXT,
                            retry_count INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(database_id),
                            FOREIGN KEY(database_id) REFERENCES local_keepass_databases(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_keepass_remote_sync_states_sync_phase ON keepass_remote_sync_states(sync_phase)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_keepass_remote_sync_states_last_failure_at ON keepass_remote_sync_states(last_failure_at)"
                    )

                    android.util.Log.i("PasswordDatabase", "Migration 56→57 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 56→57 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_57_58 = object : androidx.room.migration.Migration(57, 58) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 57→58: bitwarden vault identity stabilization")

                    database.execSQL(
                        "ALTER TABLE bitwarden_vaults ADD COLUMN canonical_email TEXT NOT NULL DEFAULT ''"
                    )
                    database.execSQL(
                        "ALTER TABLE bitwarden_vaults ADD COLUMN account_key TEXT NOT NULL DEFAULT ''"
                    )

                    database.execSQL(
                        """
                        UPDATE bitwarden_vaults
                        SET canonical_email = LOWER(TRIM(email))
                        """
                        .trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE bitwarden_vaults
                        SET account_key = LOWER(RTRIM(server_url, '/')) || '|' ||
                            CASE
                                WHEN user_id IS NOT NULL AND TRIM(user_id) <> '' THEN LOWER(TRIM(user_id))
                                ELSE LOWER(TRIM(email))
                            END
                        """
                        .trimIndent()
                    )

                    database.execSQL("DROP INDEX IF EXISTS index_bitwarden_vaults_email")
                    database.execSQL("DROP INDEX IF EXISTS index_bitwarden_vaults_isDefault")
                    database.execSQL("DROP INDEX IF EXISTS index_bitwarden_vaults_is_default")
                    database.execSQL("DROP INDEX IF EXISTS index_bitwarden_vaults_canonical_email")
                    database.execSQL("DROP INDEX IF EXISTS index_bitwarden_vaults_account_key")

                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_bitwarden_vaults_account_key ON bitwarden_vaults(account_key)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_bitwarden_vaults_canonical_email ON bitwarden_vaults(canonical_email)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_bitwarden_vaults_is_default ON bitwarden_vaults(is_default)"
                    )

                    android.util.Log.i("PasswordDatabase", "Migration 57→58 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 57→58 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_58_59 = object : androidx.room.migration.Migration(58, 59) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 58→59: KeePass passkey duplicate guard")

                    database.execSQL(
                        """
                        DELETE FROM passkeys
                        WHERE id IN (
                            SELECT duplicate.id
                            FROM passkeys duplicate
                            JOIN passkeys keeper
                              ON duplicate.keepass_database_id = keeper.keepass_database_id
                             AND duplicate.passkey_mode = keeper.passkey_mode
                             AND duplicate.credential_id = keeper.credential_id
                             AND duplicate.id < keeper.id
                            WHERE duplicate.keepass_database_id IS NOT NULL
                              AND duplicate.passkey_mode = 'KEEPASS_COMPAT'
                        )
                        """
                        .trimIndent()
                    )
                    database.execSQL("DROP INDEX IF EXISTS index_passkeys_keepass_scope_credential")
                    database.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS index_passkeys_keepass_scope_credential
                        ON passkeys(keepass_database_id, passkey_mode, credential_id)
                        """
                        .trimIndent()
                    )

                    android.util.Log.i("PasswordDatabase", "Migration 58→59 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 58→59 failed: ${e.message}")
                    throw e
                }
            }
        }

        // Migration 59 → 60 - 附件表（仅挂在 password_entries 上）
        // 对应 .kiro/specs/monica-android-attachments Requirement 1 / 9.1
        private val MIGRATION_59_60 = object : androidx.room.migration.Migration(59, 60) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 59→60: attachments table")

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS attachments (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            parent_password_id INTEGER NOT NULL,
                            source TEXT NOT NULL,
                            file_name TEXT NOT NULL,
                            mime_type TEXT NOT NULL,
                            size_bytes INTEGER NOT NULL,
                            sha256_hex TEXT,
                            wrapped_cek TEXT,
                            local_path TEXT,
                            bitwarden_attachment_id TEXT,
                            bitwarden_url TEXT,
                            bitwarden_file_key_enc TEXT,
                            keepass_binary_ref TEXT,
                            download_state TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            is_deleted INTEGER NOT NULL DEFAULT 0,
                            deleted_at INTEGER,
                            FOREIGN KEY(parent_password_id) REFERENCES password_entries(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_parent ON attachments(parent_password_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_source ON attachments(source)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_bw_id ON attachments(bitwarden_attachment_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_kp_ref ON attachments(keepass_binary_ref)")

                    android.util.Log.i("PasswordDatabase", "Migration 59→60 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 59→60 failed: ${e.message}")
                    throw e
                }
            }
        }

        // Migration 60 → 61 - WIFI 条目扩展元数据
        // 复用 password_entries：loginType 新增取值 "WIFI"，额外字段 wifi_metadata 存 JSON。
        private val MIGRATION_60_61 = object : androidx.room.migration.Migration(60, 61) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 60→61: wifi_metadata column")
                    database.execSQL(
                        "ALTER TABLE password_entries ADD COLUMN wifi_metadata TEXT NOT NULL DEFAULT ''"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 60→61 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 60→61 failed: ${e.message}")
                    throw e
                }
            }
        }

        // Migration 61 → 62 - 为 bitwarden_sends 增加 is_dirty 标志
        //
        // 修复：在 Vaultwarden / 官方 sync API 写后读不一致的窗口期，本地刚创建 / 修改的
        // Send 不会立刻出现在服务器返回的 sync 列表里。原 deleteNotIn 路径会把这些行立即
        // 清除，造成"新建后短时间内再次同步就消失"的问题。
        //
        // 修复策略：写路径置 is_dirty = 1，sync 路径在服务器侧确认到来后清 0；同时
        // 删除清理改用 deleteNotInProtectingDirty，对 dirty 行 + 本次 sync 后才落地的
        // 行（双重保护）跳过删除。
        private val MIGRATION_61_62 = object : androidx.room.migration.Migration(61, 62) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 61→62: bitwarden_sends.is_dirty")
                    database.execSQL(
                        "ALTER TABLE bitwarden_sends ADD COLUMN is_dirty INTEGER NOT NULL DEFAULT 0"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 61→62 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 61→62 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_62_63 = object : androidx.room.migration.Migration(62, 63) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 62→63: MDBX database tables")
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS local_mdbx_databases (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            file_path TEXT NOT NULL,
                            storage_location TEXT NOT NULL,
                            source_type TEXT NOT NULL,
                            source_id INTEGER,
                            tiga_mode TEXT NOT NULL DEFAULT 'MULTI',
                            encrypted_password TEXT,
                            unlock_method TEXT NOT NULL DEFAULT 'password',
                            kdf_profile TEXT NOT NULL DEFAULT 'argon2id',
                            key_file_name TEXT,
                            key_file_uri TEXT,
                            key_file_fingerprint TEXT,
                            description TEXT,
                            created_at INTEGER NOT NULL,
                            last_accessed_at INTEGER NOT NULL,
                            last_synced_at INTEGER,
                            is_default INTEGER NOT NULL,
                            project_count INTEGER NOT NULL DEFAULT 0,
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            working_copy_path TEXT,
                            cache_copy_path TEXT,
                            is_offline_available INTEGER NOT NULL DEFAULT 0,
                            last_sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                            last_sync_error TEXT
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_local_mdbx_databases_storage_location ON local_mdbx_databases(storage_location)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_local_mdbx_databases_source_type ON local_mdbx_databases(source_type)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_local_mdbx_databases_source_id ON local_mdbx_databases(source_id)")

                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS mdbx_remote_sources (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            display_name TEXT NOT NULL,
                            remote_path TEXT NOT NULL,
                            remote_parent_path TEXT,
                            base_url TEXT,
                            username_encrypted TEXT,
                            password_encrypted TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_mdbx_remote_sources_display_name ON mdbx_remote_sources(display_name)")

                    android.util.Log.i("PasswordDatabase", "Migration 62→63 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 62→63 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_63_64 = object : androidx.room.migration.Migration(63, 64) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 63→64: MDBX item ownership")
                    addColumnIfMissing(database, "password_entries", "mdbx_database_id", "INTEGER DEFAULT NULL")
                    addColumnIfMissing(database, "secure_items", "mdbx_database_id", "INTEGER DEFAULT NULL")
                    addColumnIfMissing(database, "passkeys", "mdbx_database_id", "INTEGER DEFAULT NULL")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_password_entries_mdbx_database_id ON password_entries(mdbx_database_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_secure_items_mdbx_database_id ON secure_items(mdbx_database_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_mdbx_database_id ON passkeys(mdbx_database_id)")
                    android.util.Log.i("PasswordDatabase", "Migration 63→64 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 63→64 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_64_65 = object : androidx.room.migration.Migration(64, 65) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 64→65: MDBX unlock metadata")
                    addColumnIfMissing(
                        database,
                        "local_mdbx_databases",
                        "unlock_method",
                        "TEXT NOT NULL DEFAULT 'password'"
                    )
                    addColumnIfMissing(
                        database,
                        "local_mdbx_databases",
                        "kdf_profile",
                        "TEXT NOT NULL DEFAULT 'argon2id'"
                    )
                    addColumnIfMissing(
                        database,
                        "local_mdbx_databases",
                        "key_file_name",
                        "TEXT DEFAULT NULL"
                    )
                    addColumnIfMissing(
                        database,
                        "local_mdbx_databases",
                        "key_file_fingerprint",
                        "TEXT DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 64→65 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 64→65 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_65_66 = object : androidx.room.migration.Migration(65, 66) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 65→66: MDBX key file URI")
                    addColumnIfMissing(
                        database,
                        "local_mdbx_databases",
                        "key_file_uri",
                        "TEXT DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 65→66 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 65→66 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_66_67 = object : androidx.room.migration.Migration(66, 67) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 66→67: MDBX category linkage")
                    addColumnIfMissing(
                        database,
                        "categories",
                        "mdbx_database_id",
                        "INTEGER DEFAULT NULL"
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 66→67 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 66→67 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_67_68 = object : androidx.room.migration.Migration(67, 68) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 67→68: MDBX folder ownership")
                    addColumnIfMissing(database, "password_entries", "mdbx_folder_id", "TEXT DEFAULT NULL")
                    addColumnIfMissing(database, "secure_items", "mdbx_folder_id", "TEXT DEFAULT NULL")
                    addColumnIfMissing(database, "passkeys", "mdbx_folder_id", "TEXT DEFAULT NULL")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_password_entries_mdbx_database_folder ON password_entries(mdbx_database_id, mdbx_folder_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_secure_items_mdbx_database_folder ON secure_items(mdbx_database_id, mdbx_folder_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_passkeys_mdbx_database_folder ON passkeys(mdbx_database_id, mdbx_folder_id)")
                    android.util.Log.i("PasswordDatabase", "Migration 67→68 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 67→68 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_68_69 = object : androidx.room.migration.Migration(68, 69) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 68→69: redact sensitive operation logs")
                    database.execSQL(
                        """
                        UPDATE operation_logs
                        SET
                            changesJson = '',
                            itemTitle = itemType || '#' || itemId
                        WHERE itemType IN (
                            'PASSWORD',
                            'TOTP',
                            'PASSKEY',
                            'BANK_CARD',
                            'DOCUMENT',
                            'BILLING_ADDRESS',
                            'PAYMENT_ACCOUNT',
                            'NOTE'
                        )
                        """.trimIndent()
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 68→69 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 68→69 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_69_70 = object : androidx.room.migration.Migration(69, 70) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 69→70: KeePass pending changes")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS keepass_pending_changes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            database_id INTEGER NOT NULL,
                            change_id TEXT NOT NULL,
                            entry_uuid TEXT,
                            operation TEXT NOT NULL,
                            target TEXT NOT NULL,
                            base_fingerprint TEXT,
                            base_group_path TEXT,
                            base_group_uuid TEXT,
                            payload_json TEXT NOT NULL,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            retry_count INTEGER NOT NULL DEFAULT 0,
                            max_retries INTEGER NOT NULL DEFAULT 3,
                            next_attempt_at INTEGER,
                            last_attempt_at INTEGER,
                            last_error TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            completed_at INTEGER,
                            FOREIGN KEY(database_id) REFERENCES local_keepass_databases(id) ON DELETE CASCADE ON UPDATE NO ACTION
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_database_id ON keepass_pending_changes(database_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_keepass_pending_changes_change_id ON keepass_pending_changes(change_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_status ON keepass_pending_changes(status)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_operation ON keepass_pending_changes(operation)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_entry_uuid ON keepass_pending_changes(entry_uuid)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_database_id_status_next_attempt_at ON keepass_pending_changes(database_id, status, next_attempt_at)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_database_id_entry_uuid_status ON keepass_pending_changes(database_id, entry_uuid, status)")
                    android.util.Log.i("PasswordDatabase", "Migration 69→70 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 69→70 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_70_71 = object : androidx.room.migration.Migration(70, 71) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 70→71: KeePass pending base snapshots")
                    addColumnIfMissing(database, "keepass_pending_changes", "base_remote_version_token", "TEXT")
                    addColumnIfMissing(database, "keepass_pending_changes", "base_remote_etag", "TEXT")
                    addColumnIfMissing(database, "keepass_pending_changes", "base_remote_last_modified", "INTEGER")
                    addColumnIfMissing(database, "keepass_pending_changes", "base_hash", "TEXT")
                    addColumnIfMissing(database, "keepass_pending_changes", "working_hash_at_change", "TEXT")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_base_remote_etag ON keepass_pending_changes(base_remote_etag)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_keepass_pending_changes_base_hash ON keepass_pending_changes(base_hash)")
                    android.util.Log.i("PasswordDatabase", "Migration 70→71 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 70→71 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_71_72 = object : androidx.room.migration.Migration(71, 72) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    android.util.Log.i("PasswordDatabase", "Starting migration 71→72: KeePass sync state updated timestamp")
                    addColumnIfMissing(
                        database,
                        "local_keepass_databases",
                        "last_sync_state_updated_at",
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        """
                        UPDATE local_keepass_databases
                        SET last_sync_state_updated_at = COALESCE(last_synced_at, last_accessed_at, created_at, 0)
                        WHERE last_sync_state_updated_at = 0
                        """.trimIndent()
                    )
                    android.util.Log.i("PasswordDatabase", "Migration 71→72 completed successfully")
                } catch (e: Exception) {
                    android.util.Log.e("PasswordDatabase", "Migration 71→72 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_72_73 = object : androidx.room.migration.Migration(72, 73) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS timeline_version_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operation_log_id INTEGER NOT NULL,
                        item_type TEXT NOT NULL,
                        item_id INTEGER NOT NULL,
                        operation_type TEXT NOT NULL,
                        encrypted_changes_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_timeline_version_snapshots_operation_log_id ON timeline_version_snapshots(operation_log_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_timeline_version_snapshots_created_at ON timeline_version_snapshots(created_at)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_timeline_version_snapshots_item_type_item_id ON timeline_version_snapshots(item_type, item_id)"
                )
            }
        }

        internal val MIGRATION_73_74 = object : androidx.room.migration.Migration(73, 74) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                addColumnIfMissing(
                    database = database,
                    tableName = "local_mdbx_databases",
                    columnName = "engine_type",
                    definition = "TEXT NOT NULL DEFAULT 'KOTLIN_MDBX1'"
                )
            }
        }

        internal val MIGRATION_74_75 = object : androidx.room.migration.Migration(74, 75) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mdbx_sync_states` (
                        `database_id` INTEGER NOT NULL,
                        `state_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`database_id`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_75_76 = object : androidx.room.migration.Migration(75, 76) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                addColumnIfMissing(
                    database = database,
                    tableName = "local_mdbx_databases",
                    columnName = "external_tree_uri",
                    definition = "TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Migration 76 → 77: allow attachments to belong to either a password or a secure item.
         *
         * SQLite cannot add a foreign key with ALTER TABLE, so rebuild the table while copying every
         * existing row and keeping `parent_secure_item_id` null for legacy password attachments.
         */
        internal val MIGRATION_76_77 = object : androidx.room.migration.Migration(76, 77) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachments_v77 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        parent_password_id INTEGER,
                        parent_secure_item_id INTEGER,
                        source TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        sha256_hex TEXT,
                        wrapped_cek TEXT,
                        local_path TEXT,
                        bitwarden_attachment_id TEXT,
                        bitwarden_url TEXT,
                        bitwarden_file_key_enc TEXT,
                        keepass_binary_ref TEXT,
                        download_state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        is_deleted INTEGER NOT NULL DEFAULT 0,
                        deleted_at INTEGER,
                        CHECK (
                            (parent_password_id IS NOT NULL AND parent_secure_item_id IS NULL) OR
                            (parent_password_id IS NULL AND parent_secure_item_id IS NOT NULL)
                        ),
                        FOREIGN KEY(parent_password_id) REFERENCES password_entries(id) ON DELETE CASCADE,
                        FOREIGN KEY(parent_secure_item_id) REFERENCES secure_items(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO attachments_v77 (
                        id, parent_password_id, parent_secure_item_id, source, file_name, mime_type,
                        size_bytes, sha256_hex, wrapped_cek, local_path, bitwarden_attachment_id,
                        bitwarden_url, bitwarden_file_key_enc, keepass_binary_ref, download_state,
                        created_at, updated_at, is_deleted, deleted_at
                    )
                    SELECT
                        id, parent_password_id, NULL, source, file_name, mime_type,
                        size_bytes, sha256_hex, wrapped_cek, local_path, bitwarden_attachment_id,
                        bitwarden_url, bitwarden_file_key_enc, keepass_binary_ref, download_state,
                        created_at, updated_at, is_deleted, deleted_at
                    FROM attachments
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE attachments")
                database.execSQL("ALTER TABLE attachments_v77 RENAME TO attachments")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_parent ON attachments(parent_password_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_secure_item_parent ON attachments(parent_secure_item_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_source ON attachments(source)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_bw_id ON attachments(bitwarden_attachment_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_kp_ref ON attachments(keepass_binary_ref)"
                )
            }
        }

        /** Migration 77 → 78: optional encrypted Monica-owned KeePass key-file copies. */
        internal val MIGRATION_77_78 = object : androidx.room.migration.Migration(77, 78) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                addColumnIfMissing(database, "local_keepass_databases", "key_file_internal_path", "TEXT")
                addColumnIfMissing(database, "local_keepass_databases", "key_file_name", "TEXT")
                addColumnIfMissing(database, "local_keepass_databases", "key_file_fingerprint", "TEXT")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_keepass_databases_key_file_fingerprint " +
                        "ON local_keepass_databases(key_file_fingerprint)"
                )
            }
        }

        internal val MIGRATION_78_79 = object : androidx.room.migration.Migration(78, 79) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                addColumnIfMissing(database, "password_entries", "password_group_id", "TEXT DEFAULT NULL")
            }
        }

        internal val MIGRATION_79_80 = object : androidx.room.migration.Migration(79, 80) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Do not derive signing flags from is_backed_up or rewrite keys/counters.
                addColumnIfMissing(database, "passkeys", "backup_eligible", "INTEGER DEFAULT NULL")
                addColumnIfMissing(database, "passkeys", "backup_state", "INTEGER DEFAULT NULL")
            }
        }

        private fun addColumnIfMissing(
            database: androidx.sqlite.db.SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            definition: String
        ) {
            val exists = database.query("PRAGMA table_info($tableName)").use { cursor ->
                var found = false
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) {
                database.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
            }
        }

        fun getDatabase(context: Context): PasswordDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PasswordDatabase::class.java,
                    "password_database"
                )
                    .addMigrations(
                        MIGRATION_1_2, 
                        MIGRATION_2_3, 
                        MIGRATION_3_4, 
                        MIGRATION_4_5, 
                        MIGRATION_5_6, 
                        MIGRATION_6_7, 
                        MIGRATION_7_8, 
                        MIGRATION_8_9, 
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,  // 删除记账功能
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,  // 扩展OTP支持
                        MIGRATION_15_16,  // 版本占位
                        MIGRATION_16_17,  // 添加分类功能
                        MIGRATION_17_18,  // 添加authenticatorKey字段
                        MIGRATION_18_19,  // 添加操作日志表
                        MIGRATION_19_20,  // 添加 isReverted 字段
                        MIGRATION_20_21,  // 添加回收站功能（软删除字段）
                        MIGRATION_21_22,  // 添加第三方登录(SSO)字段
                        MIGRATION_22_23,  // 添加本地 KeePass 数据库管理表
                        MIGRATION_23_24,  // 为密码条目添加 KeePass 数据库归属字段
                        MIGRATION_24_25,  // 修复 local_keepass_databases 表结构
                        MIGRATION_25_26,  // 添加 KeePass 密钥文件字段
                        MIGRATION_26_27,  // 添加自定义字段表
                        MIGRATION_27_28,  // 添加 Passkey 通行密钥表
                        MIGRATION_28_29,  // 为 secure_items 添加 categoryId 字段
                        MIGRATION_29_30,  // Bitwarden 集成
                        MIGRATION_30_31,  // Bitwarden 多类型同步支持
                        MIGRATION_31_32,  // Passkey 绑定密码
                        MIGRATION_32_33,  // Password 绑定通行密钥元数据
                        MIGRATION_33_34,  // KeePass 组同步配置
                        MIGRATION_34_35,  // Bitwarden Send 本地缓存
                        MIGRATION_35_36,  // Passkey 分类字段（统一文件夹）
                        MIGRATION_36_37,  // secure_items/passkeys KeePass 归属字段
                        MIGRATION_37_38,  // keepass 分组路径字段
                        MIGRATION_38_39,  // 自定义密码图标字段
                        MIGRATION_39_40,  // Passkey 模式字段
                        MIGRATION_40_41,  // 密码归档字段
                        MIGRATION_41_42,  // Passkey 文件夹/分组字段
                        MIGRATION_42_43,  // 清理 legacy KeePass WebDAV 残余
                        MIGRATION_43_44,  // Bitwarden 多库去重与唯一约束
                        MIGRATION_44_45,  // 密码归档同步元信息
                        MIGRATION_45_46,  // KeePass KDBX 配置字段
                        MIGRATION_46_47,  // KeePass 原生 UUID 字段
                        MIGRATION_47_48,  // Bitwarden TLS/证书字段
                        MIGRATION_48_49,  // 历史密码表
                        MIGRATION_49_50,  // SSH 密钥结构化字段
                        MIGRATION_50_51,  // 密码页聚合堆叠元数据
                        MIGRATION_51_52,  // 密码多目标副本组标识
                        MIGRATION_52_53,  // 安全项多目标副本组标识
                        MIGRATION_53_54,  // 密码绑定笔记ID
                        MIGRATION_54_55,  // Bitwarden 条目原始同步快照
                        MIGRATION_55_56,  // Passkey 内部记录 ID
                        MIGRATION_56_57,  // KeePass 远端来源与同步骨架
                        MIGRATION_57_58,  // Bitwarden Vault 身份稳定化
                        MIGRATION_58_59,  // KeePass Passkey 去重与唯一约束
                        MIGRATION_59_60,   // 附件表 (attachments)
                        MIGRATION_60_61,   // WIFI 条目扩展元数据 (wifi_metadata)
                        MIGRATION_61_62,   // bitwarden_sends.is_dirty 防止本地新建被清理
                        MIGRATION_62_63,   // MDBX 数据库格式 (local_mdbx_databases + mdbx_remote_sources)
                        MIGRATION_63_64,   // MDBX 条目归属字段 (passwords/secure_items/passkeys)
                        MIGRATION_64_65,   // MDBX 解锁方式元数据 (password/key file/device key)
                        MIGRATION_65_66,   // MDBX key file URI for real unlock flows
                        MIGRATION_66_67,   // MDBX category linkage
                        MIGRATION_67_68,   // MDBX folder ownership
                        MIGRATION_68_69,   // Redact sensitive operation log history
                        MIGRATION_69_70,   // KeePass entry-level pending changes
                        MIGRATION_70_71,   // KeePass pending base snapshots
                        MIGRATION_71_72,   // KeePass sync state updated timestamp
                        MIGRATION_72_73,   // Encrypted timeline version snapshots
                        MIGRATION_73_74,   // Per-database MDBX engine selection
                        MIGRATION_74_75,   // MDBX2 durable remote sync cursors
                        MIGRATION_75_76,   // MDBX2 external SAF tree metadata
                        MIGRATION_76_77,   // Attachments for PasswordEntry and SecureItem
                        MIGRATION_77_78,   // Monica-owned KeePass key-file copies
                        MIGRATION_78_79,   // Explicit multi-password project identity
                        MIGRATION_79_80    // Preserve explicit WebAuthn backup flags
                    )
                    // 启用多进程失效通知：IME 跑在 :ime 独立进程，主进程需要
                    // 感知 IME 进程对数据库的修改（例如最近填充时间戳等）。
                    .enableMultiInstanceInvalidation()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}



