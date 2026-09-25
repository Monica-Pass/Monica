package takagi.ru.monica.ime

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.system.measureNanoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager

/** Exercises the actual service loader and sort helper with a synthetic, wide Chinese vault. */
@RunWith(AndroidJUnit4::class)
class MonicaImePerformanceTest {
    @Test fun coldLoadAndRepeatedSearchOnLargeChineseVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val service = MonicaInputMethodService()
        fun inject(name: String, value: Any) {
            service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        try {
            service.javaClass.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            inject("database", database)
            inject("securityManager", SecurityManager(context))
            val names = listOf("工作邮箱", "个人账户", "开发平台", "密码管理", "百度网盘", "高德地图", "云服务", "购物网站")
            val records = List(3000) { index -> PasswordEntry(
                title = names[index % names.size] + " " + (3000 - index),
                website = "https://example$index.com", username = "fixture-$index", password = "fixture",
                notes = "n".repeat(8192),
            ) }
            database.passwordEntryDao().insertPasswordEntries(records)
            val loadMethod = service.javaClass.declaredMethods.single { it.name == "loadImeVaultSources" }
                .apply { isAccessible = true }
            val loadTimes = mutableListOf<Long>()
            val sortTimes = mutableListOf<Long>()
            val firstQueryTimes = mutableListOf<Long>()
            val cachedQueryMicros = mutableListOf<Long>()
            withContext(Dispatchers.IO) {
                repeat(3) {
                    lateinit var source: Any
                    loadTimes += measureNanoTime {
                        source = suspendCoroutineUninterceptedOrReturn { continuation ->
                            loadMethod.invoke(service, "Monica", "KeePass", "MDBX", "Bitwarden", "All", continuation)
                        }
                    } / 1_000_000
                    @Suppress("UNCHECKED_CAST")
                    val results = source.javaClass.getDeclaredField("passwordResults")
                        .apply { isAccessible = true }.get(source) as List<Any>
                    val entries = results.map { row ->
                        row.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(row)
                            as MonicaImePasswordEntry
                    }
                    assertEquals(3000, entries.size)
                    val index = source.javaClass.getDeclaredField("passwordIndex")
                        .apply { isAccessible = true }.get(source) as ImePasswordIndex
                    firstQueryTimes += measureNanoTime {
                        assertEquals(375, index.query("工作邮箱").size)
                    } / 1_000_000
                    repeat(20) {
                        cachedQueryMicros += measureNanoTime {
                            assertEquals(375, index.query("工作邮箱 fixture").size)
                        } / 1_000
                    }
                    sortTimes += measureNanoTime {
                        assertEquals(3000, sortImePasswordEntries(entries,
                            MonicaImePasswordSortMode.ALPHABETICAL, "").size)
                    } / 1_000_000
                }
            }
            val result = "rows=3000 notesBytes=24576000 loadMs=$loadTimes sortMs=$sortTimes " +
                "firstQueryMs=$firstQueryTimes cachedQueryMicros=$cachedQueryMicros"
            Log.i("MonicaImePerformance", result)
            println(result)
        } finally {
            database.close()
        }
    }
}
