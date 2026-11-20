package takagi.ru.monica.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.security.SessionManager
import java.util.UUID

/** Uses the frozen schema emitted by the real 86 build, not the current create-table code. */
class Schema85CompatibilityTest {
    private inline fun <T : androidx.room.RoomDatabase, R> T.use(block: (T) -> R): R = try { block(this) } finally { close() }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val oldIdentity = "f1cc89b0bd71d086b7a81d230b3fe1b7"
    private val identity86 = "8168bf211998eaffafc08075db85265d"

    private fun fixture(name: String, version: Int = 86) {
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
            val ddl = instrumentation.context.assets.open("compat/schema86.sql").bufferedReader().use { it.readText() }
            ddl.lineSequence().filterNot { it.startsWith("--") }.joinToString("\n").split(';')
                .map(String::trim).filter(String::isNotEmpty).forEach(sql::execSQL)
            val tables = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name", null).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }
            // Every legacy table gets a row, including unknown encrypted fields and provider metadata.
            for (table in tables) {
                val row = ContentValues()
                sql.rawQuery("PRAGMA table_info(`$table`)", null).use { columns ->
                    while (columns.moveToNext()) {
                        val column = columns.getString(1)
                        val type = columns.getString(2)
                        if (columns.getInt(3) == 0 && columns.getInt(5) == 0) row.putNull(column)
                        else if (type == "INTEGER") row.put(column, 1L)
                        else row.put(column, "synthetic-preserved-$column")
                    }
                }
                if (table == "timeline_events") {
                    row.put("id", "synthetic-event")
                    row.put("sealedDetails", "opaque-existing-encrypted-material")
                }
                if (table == "timeline_targets") row.put("eventId", "synthetic-event")
                sql.insertOrThrow(table, null, row)
            }
            sql.version = version
        }
    }

    private fun rows(sql: SQLiteDatabase): Map<String, List<List<String?>>> {
        val names = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name != 'room_master_table' ORDER BY name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return names.associateWith { table ->
            sql.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(cursor.getBlob(column), android.util.Base64.NO_WRAP)
                        else -> "${cursor.getType(column)}:${cursor.getString(column)}"
                    }
                }) }
            }
        }
    }

    private fun raw(name: String) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
    private fun current(name: String, migration: Boolean = true) = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
        .apply { if (migration) addMigrations(PasswordDatabase.MIGRATION_86_85) }.build()
    private fun identity(db: androidx.sqlite.db.SupportSQLiteDatabase) = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) }

    @Test fun migration86To85PreservesEveryTableAndLegacy85CanWriteThenReopen() = runBlocking {
        val name = "schema85-${UUID.randomUUID()}"
        try {
            fixture(name)
            val before = raw(name).use(::rows)
            assertTrue(before.size >= 30)
            current(name).use { db ->
                assertEquals(85, db.openHelper.writableDatabase.version)
                assertEquals(oldIdentity, identity(db.openHelper.writableDatabase))
            }
            assertEquals(before, raw(name).use(::rows))
            val id = Room.databaseBuilder(context, LegacySchema85TestDatabase::class.java, name).build().use { old ->
                assertEquals(oldIdentity, identity(old.openHelper.writableDatabase))
                old.entries().insertPasswordEntry(PasswordEntry(title="old85-write", username="account", password="opaque-ciphertext", website="https://example.invalid"))
            }
            current(name).use { db ->
                assertEquals("opaque-ciphertext", db.passwordEntryDao().getPasswordEntryById(id)!!.password)
                val row = db.passwordEntryDao().getPasswordEntryById(id)!!
                db.passwordEntryDao().updatePasswordEntry(row.copy(title="new85-write"))
            }
            Room.databaseBuilder(context, LegacySchema85TestDatabase::class.java, name).build().use { old ->
                assertEquals("new85-write", old.entries().getPasswordEntryById(id)!!.title)
            }
            val after = raw(name).use(::rows)
            for ((table, values) in before) if (table !in setOf("password_entries", "sqlite_sequence")) assertEquals(table, values, after[table])
        } finally { context.deleteDatabase(name) }
    }

    @Test fun withoutReverseMigration86FailsAndKeepsOriginalDatabase() {
        val name = "schema85-control-${UUID.randomUUID()}"
        try {
            fixture(name)
            val before = raw(name).use(::rows)
            current(name, migration=false).use { db -> assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure) }
            raw(name).use { sql -> assertEquals(86, sql.version); assertEquals(before, rows(sql)) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun malformed86RollsBackVersionAndIdentityWithoutDeletingData() {
        val name = "schema85-malformed-${UUID.randomUUID()}"
        try {
            fixture(name)
            raw(name).use { it.execSQL("ALTER TABLE password_entries RENAME COLUMN title TO unexpected_title") }
            val before = raw(name).use(::rows)
            current(name).use { db -> assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure) }
            raw(name).use { sql ->
                assertEquals(86, sql.version)
                assertEquals(before, rows(sql))
                sql.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42",null).use { it.moveToFirst(); assertEquals(identity86,it.getString(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun futureSchemaIsRejectedWithoutDestructiveFallback() {
        val name = "schema85-future-${UUID.randomUUID()}"
        try {
            fixture(name,87)
            val before = raw(name).use(::rows)
            current(name).use { db -> assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure) }
            raw(name).use { assertEquals(87,it.version); assertEquals(before,rows(it)) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun new85DatabaseHasLegacyIdentityAndDoesNotCreateJournalTables() = runBlocking {
        val name = "schema85-fresh-${UUID.randomUUID()}"
        try {
            current(name).use { db ->
                assertEquals(85,db.openHelper.writableDatabase.version)
                assertEquals(oldIdentity,identity(db.openHelper.writableDatabase))
                db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="fresh", username="account", password="cipher", website=""))
                assertEquals(0,db.operationLogDao().getLogCount())
                db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE name IN ('timeline_events','timeline_targets')").use { assertEquals(0,it.count) }
            }
            Room.databaseBuilder(context,LegacySchema85TestDatabase::class.java,name).build().use { old ->
                assertEquals("fresh",old.entries().getAllPasswordEntriesSync().single().title)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedCancelledAndLockedGroupedWritesRemainAtomicWithoutJournal() = runBlocking {
        val wasUnlocked=SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        Room.inMemoryDatabaseBuilder(context,PasswordDatabase::class.java).build().use { db ->
            try {
                suspend fun insert() = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="rollback",username="",password="cipher",website=""))
                assertTrue(runCatching { db.passwordEntryDao().commitLocalPasswordSave { insert(); error("synthetic") } }.isFailure)
                assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
                val job=launch { db.passwordEntryDao().commitLocalPasswordSave { insert(); throw CancellationException("synthetic") } }
                job.join()
                assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
                assertTrue(runCatching { db.passwordEntryDao().commitLocalPasswordSave { insert(); SessionManager.markLocked() } }.isFailure)
                assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
            } finally { if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked() }
        }
    }

    @Test fun permanentDeletePurgesOnlyTheDeletedKindsLegacyMaterial() = runBlocking {
        val name="schema85-delete-${UUID.randomUUID()}"
        try {
            fixture(name)
            current(name).use { db ->
                val sql=db.openHelper.writableDatabase
                val password=db.passwordEntryDao().insertPasswordEntry(PasswordEntry(id=101,title="password",username="",password="opaque",website=""))
                val note=db.secureItemDao().insertItem(SecureItem(id=101,itemType=ItemType.NOTE,title="note",itemData="opaque"))
                for (kind in listOf("PASSWORD","SECURE")) {
                    sql.execSQL("INSERT INTO timeline_events VALUES (?,1,'UPDATE','LOCAL','local',?,9999999999999,1,NULL,1)", arrayOf(kind,"encrypted-$kind"))
                    sql.execSQL("INSERT INTO timeline_targets VALUES (?,?,101,'local')",arrayOf(kind,kind))
                }
                fun material(kind:String)=sql.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT sealedDetails FROM timeline_events WHERE id=?",arrayOf(kind))).use { it.moveToFirst(); if(it.isNull(0)) null else it.getString(0) }
                for ((index, kind) in listOf("NOTE", "PASSWORD", "PASSKEY").withIndex()) {
                    sql.execSQL("INSERT INTO timeline_version_snapshots (operation_log_id,item_type,item_id,operation_type,encrypted_changes_json,created_at) VALUES (?,?,101,'UPDATE','opaque',1)",arrayOf<Any>(index+101,kind))
                }
                fun snapshots()=sql.query("SELECT item_type FROM timeline_version_snapshots WHERE item_id=101 ORDER BY item_type").use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(0)) } }
                db.secureItemDao().softDelete(note,1)
                assertEquals("encrypted-SECURE",material("SECURE"))
                db.secureItemDao().permanentlyDeleteAll()
                assertNull(db.secureItemDao().getItemById(note))
                assertNotNull(db.passwordEntryDao().getPasswordEntryById(password))
                assertNull(material("SECURE"))
                assertEquals(listOf("PASSKEY","PASSWORD"),snapshots())
                assertEquals("encrypted-PASSWORD",material("PASSWORD"))
                db.passwordEntryDao().deletePasswordEntryById(password)
                assertNull(material("PASSWORD"))
                assertEquals(listOf("PASSKEY"),snapshots())
                assertEquals(3,sql.query("SELECT COUNT(*) FROM timeline_events").use { it.moveToFirst();it.getInt(0) })
            }
        } finally { context.deleteDatabase(name) }
    }
}
