package takagi.ru.monica.steam

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import takagi.ru.monica.steam.data.*
import takagi.ru.monica.security.SecurityManager

class SteamFolderStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun account() = SteamAccount(0, "76561198000000000", "fixture", "Fixture", "android:fixture",
        "c2VjcmV0", null, null, null, null, null, null, "{}", false, 0, 1, 1)

    @Test fun localCopiesAreIndependentAndFolderSurvivesEditsAndSourceDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SteamDatabase::class.java).build()
        try {
            val repository = SteamAccountRepository(db.steamAccountDao(), SecurityManager(context))
            val original = repository.insertCopy(account(), null)
            val copy = repository.insertCopy(requireNotNull(repository.getAccount(original)), 17)
            assertNotEquals(original, copy)
            assertEquals(2, repository.getAccounts().size)
            repository.moveToCategory(original, 21)
            assertEquals(21L, repository.getAccount(original)?.categoryId)
            repository.replaceAccount(requireNotNull(repository.getAccount(copy)).copy(displayName = "Edited"))
            repository.updateSessionTokens(copy, "fixture-token", null, null)
            assertEquals(17L, repository.getAccount(copy)?.categoryId)
            repository.delete(original)
            assertEquals("Edited", repository.getAccount(copy)?.displayName)
            assertEquals("fixture-token", repository.getAccount(copy)?.accessToken)
            assertEquals(1, repository.getAccounts().size)
        } finally { db.close() }
    }

    @Test fun migrationPreservesOldRowsAndAllowsMultipleRecordsForOneSteamId() {
        val name = "steam-migration-${UUID.randomUUID()}"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE steam_accounts(id INTEGER PRIMARY KEY, steam_id TEXT NOT NULL)")
                    db.execSQL("CREATE UNIQUE INDEX index_steam_accounts_steam_id ON steam_accounts(steam_id)")
                    db.execSQL("INSERT INTO steam_accounts VALUES (1, 'encrypted-fixture')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            SteamDatabase.migration3To4().migrate(db)
            db.query("SELECT categoryId FROM steam_accounts WHERE id=1").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
            db.execSQL("INSERT INTO steam_accounts(id, steam_id, categoryId) VALUES (2, 'encrypted-fixture', 9)")
            db.query("SELECT COUNT(*) FROM steam_accounts").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
