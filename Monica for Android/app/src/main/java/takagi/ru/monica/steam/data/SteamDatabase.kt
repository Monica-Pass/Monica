package takagi.ru.monica.steam.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import takagi.ru.monica.security.SecurityManager

@Database(
    entities = [SteamAccountEntity::class],
    version = 4,
    exportSchema = false
)
abstract class SteamDatabase : RoomDatabase() {
    abstract fun steamAccountDao(): SteamAccountDao

    companion object {
        @Volatile
        private var INSTANCE: SteamDatabase? = null

        fun getDatabase(context: Context): SteamDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SteamDatabase::class.java,
                    "steam_database"
                )
                    .addMigrations(migration1To2(context.applicationContext))
                    .addMigrations(migration2To3(), migration3To4())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        internal fun migration3To4(): Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE steam_accounts ADD COLUMN categoryId INTEGER DEFAULT NULL")
                db.execSQL("DROP INDEX IF EXISTS index_steam_accounts_steam_id")
                db.execSQL("CREATE INDEX index_steam_accounts_steam_id ON steam_accounts(steam_id)")
            }
        }

        private fun migration2To3(): Migration {
            return object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE steam_accounts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                    db.query("SELECT id FROM steam_accounts ORDER BY selected DESC, updatedAt DESC").use { cursor ->
                        var index = 0
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                            val values = ContentValues().apply {
                                put("sortOrder", index++)
                            }
                            db.update(
                                "steam_accounts",
                                SQLiteDatabase.CONFLICT_NONE,
                                values,
                                "id = ?",
                                arrayOf(id.toString())
                            )
                        }
                    }
                }
            }
        }

        private fun migration1To2(context: Context): Migration {
            return object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    encryptExistingSteamRows(db, SecurityManager(context))
                }
            }
        }

        private fun encryptExistingSteamRows(
            db: SupportSQLiteDatabase,
            securityManager: SecurityManager
        ) {
            val columns = listOf(
                "steam_id",
                "accountName",
                "displayName",
                "deviceId",
                "sharedSecret",
                "identitySecret",
                "revocationCode",
                "tokenGid",
                "accessToken",
                "refreshToken",
                "steamLoginSecure",
                "rawSteamGuardJson"
            )
            db.query(
                "SELECT id, ${columns.joinToString(", ")} FROM steam_accounts"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                    val values = ContentValues()
                    columns.forEach { column ->
                        values.putEncrypted(column, cursor, securityManager)
                    }
                    db.update(
                        "steam_accounts",
                        SQLiteDatabase.CONFLICT_NONE,
                        values,
                        "id = ?",
                        arrayOf(id.toString())
                    )
                }
            }
        }

        private fun ContentValues.putEncrypted(
            column: String,
            cursor: Cursor,
            securityManager: SecurityManager
        ) {
            val index = cursor.getColumnIndexOrThrow(column)
            if (cursor.isNull(index)) {
                putNull(column)
                return
            }

            val value = cursor.getString(index)
            val encrypted = if (securityManager.looksLikeMonicaCiphertext(value)) {
                value
            } else {
                securityManager.encryptDataLegacyCompat(value)
            }
            put(column, encrypted)
        }
    }
}
