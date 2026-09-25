package takagi.ru.monica.data

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordCursorSnapshotInstrumentedTest {
    @Test
    fun activeListKeepsOneSnapshotWhenRowsChangeBetweenCursorWindows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cursor-snapshot-${UUID.randomUUID()}.db"
        val afterFirstRow = AtomicReference<(() -> Unit)?>(null)
        val room = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
            .openHelperFactory(ObservedCursorFactory(afterFirstRow))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        val writer = Executors.newSingleThreadExecutor()
        var writing: Future<*>? = null
        try {
            val entries = List(800) { index -> PasswordEntry(
                title = "Cursor fixture $index", website = "", username = "fixture",
                password = "fixture-password", notes = "x".repeat(16 * 1024),
            ) }
            val ids = room.passwordEntryDao().insertPasswordEntries(entries).toSet()
            val writerStarted = CountDownLatch(1)
            afterFirstRow.set {
                writing = writer.submit {
                    writerStarted.countDown()
                    room.openHelper.writableDatabase.execSQL("DELETE FROM password_entries")
                }
                check(writerStarted.await(5, TimeUnit.SECONDS))
                // With a read transaction the writer waits; without it the next
                // CursorWindow is filled from a different version of the table.
                try { writing!!.get(1, TimeUnit.SECONDS) } catch (_: TimeoutException) { }
            }
            val snapshot = withTimeout(20_000) { room.passwordEntryDao().getActiveEntries().first() }
            assertEquals("Every window must belong to the same snapshot", ids, snapshot.map { it.id }.toSet())
            assertEquals(ids.size, snapshot.size)
            writing!!.get(10, TimeUnit.SECONDS)
            assertTrue(withTimeout(10_000) { room.passwordEntryDao().getActiveEntries().first() }.isEmpty())
        } finally {
            afterFirstRow.set(null)
            writer.shutdownNow()
            writer.awaitTermination(10, TimeUnit.SECONDS)
            room.close()
            context.deleteDatabase(name)
        }
    }

    private class ObservedCursorFactory(
        private val afterFirstRow: AtomicReference<(() -> Unit)?>,
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = observe(helper.writableDatabase)
                override val readableDatabase get() = observe(helper.readableDatabase)
            }
        }

        private fun observe(database: SupportSQLiteDatabase): SupportSQLiteDatabase =
            object : SupportSQLiteDatabase by database {
                override fun query(query: SupportSQLiteQuery): Cursor =
                    observeCursor(query, database.query(query))
                override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                    observeCursor(query, database.query(query, cancellationSignal))
            }

        private fun observeCursor(query: SupportSQLiteQuery, cursor: Cursor): Cursor {
            if (!query.sql.startsWith("SELECT * FROM password_entries")) return cursor
            return object : CursorWrapper(cursor) {
                override fun moveToNext(): Boolean = super.moveToNext().also { moved ->
                    if (moved) afterFirstRow.getAndSet(null)?.invoke()
                }
            }
        }
    }
}
