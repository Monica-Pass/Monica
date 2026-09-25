package takagi.ru.monica.ime

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.rustcore.RustPasswordListCore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator

class ImeVaultLoadInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun batchSortKeysPreserveEachUnicodeTitleAndItsBoundaries() {
        val examples = listOf("中文标题", "Café", "ΔΣ", "Москва", "東京の口座", "서울", "مرحبا", "नमस्ते",
            "银行A", "A银行", "123账户", "银行\n账户", "中文\u0000标题", "\u0301Café", "", "   ", "🔑 工作邮箱")
        val titles = List(160) { examples[it % examples.size] + if (it % 3 == 0) " $it" else "" }
        assertEquals(titles.map(::normalizedImeSortKey), normalizedImeSortKeys(titles))
    }

    @Test fun projectionKeepsFillFieldsWithoutReadingLargeEditorPayloadsOrKeyCredentials() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val entry = password("Wide record").copy(notes = "n".repeat(3 * 1024 * 1024),
                appName = "Fixture app", authenticatorKey = "fixture-otp", bitwardenVaultId = 42)
            val ids = room.passwordEntryDao().insertPasswordEntries(listOf(entry,
                entry.copy(loginType = "API_KEY"), entry.copy(loginType = "GPG_KEY"),
                entry.copy(isDeleted = true), entry.copy(isArchived = true)))
            val rows = room.passwordEntryDao().getImePasswordRows()
            assertEquals(listOf(ids.first()), rows.map { it.id })
            assertEquals(entry.username, rows.single().username)
            assertEquals(entry.password, rows.single().password)
            assertEquals(entry.authenticatorKey, rows.single().authenticatorKey)
            assertEquals(42L, rows.single().bitwardenVaultId)
        } finally { room.close() }
    }

    @Test fun nativeBatchAndFallbackAgreeOnUnicodeAndCrossFieldSearch() {
        val entries = List(600) { i -> MonicaImePasswordEntry(i.toLong(),
            if (i % 25 == 0) "Bitwarden ΔΣ 中文 $i" else "Mail $i", "Alice$i@example.com",
            "https://example.com", "com.example.app", "fixture", false, "Bitwarden · all rows",
            appName = "Fixture App") }
        val rows = entries.map { entry -> RustPasswordListCore.SearchRow(entry.title.lowercase(Locale.ROOT),
            entry.username.lowercase(Locale.ROOT), entry.website.lowercase(Locale.ROOT),
            entry.appName.lowercase(Locale.ROOT), entry.packageName.lowercase(Locale.ROOT)) }
        val native = RustPasswordListCore.prepareSearch(rows)
        assertNotNull("The x86_64 build must exercise the production Rust library",
            RustPasswordListCore.filterIndices(native, "bitwarden"))
        val index = ImePasswordIndex(entries)
        listOf("bitwarden", "ALICE25 bitwarden", "中文", "ΔΣ", "example fixture", "missing", " ").forEach { query ->
            assertEquals(query, entries.filter { imePasswordEntryMatchesQuery(it, query) }.map { it.id }.toSet(),
                index.query(query).map { it.id }.toSet())
        }
    }

    @Test fun passwordOtpUsesTheLatestKeyWithoutLoadingWideNotes() = runBlocking {
        val harness = Harness()
        try {
            val original = password("OTP fixture").copy(authenticatorKey = "JBSWY3DPEHPK3PXP", notes = "n".repeat(3 * 1024 * 1024))
            val id = harness.room.passwordEntryDao().insertPasswordEntry(original)
            harness.refresh()
            val cachedEntry = harness.state.value.entries.single()
            assertTrue(cachedEntry.hasTotp)
            assertEquals("", cachedEntry.totpCode)
            val newSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
            harness.room.passwordEntryDao().updatePasswordEntry(original.copy(id = id, authenticatorKey = newSecret))
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            val data = checkNotNull(TotpDataResolver.fromAuthenticatorKey(newSecret, "", ""))
            val before = TotpGenerator.generateOtp(data)
            withContext(Dispatchers.Main) { harness.fillOtp(cachedEntry) }
            waitUntil { editor.text.isNotEmpty() }
            val after = TotpGenerator.generateOtp(data)
            assertTrue(editor.text.toString() in setOf(before, after))
        } finally { harness.close() }
    }

    @Test fun otpReadFailureShowsAnErrorWithoutCommittingText() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("OTP fixture").copy(authenticatorKey = "JBSWY3DPEHPK3PXP"))
            harness.refresh()
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            gate.set { error("Synthetic database read failure") }
            withContext(Dispatchers.Main) { harness.fillOtp(harness.state.value.entries.single()) }
            waitUntil { harness.state.value.errorMessage != null }
            assertEquals("", editor.text.toString())
            assertTrue(harness.state.value.unlocked)
        } finally { harness.close() }
    }

    @Test fun otpReadCannotFillAnEditorThatChangedDuringTheRead() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("OTP fixture").copy(authenticatorKey = "JBSWY3DPEHPK3PXP"))
            harness.refresh()
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { harness.fillOtp(harness.state.value.entries.single()) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { harness.set("inputGeneration", harness.number("inputGeneration") + 1) }
            release.countDown()
            val scope = harness.field("serviceScope") as CoroutineScope
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals("", editor.text.toString())
        } finally { release.countDown(); harness.close() }
    }

    @Test fun serviceReusesTheSnapshotAndInvalidatesItAfterARealDatabaseWrite() = runBlocking {
        val harness = Harness()
        try {
            val ids = harness.room.passwordEntryDao().insertPasswordEntries(listOf(password("Bitwarden"), password("Mail")))
            withContext(Dispatchers.Main) { harness.call("observeDatabaseSources") }
            waitUntil { harness.number("vaultGeneration") > 0 }
            harness.refresh()
            val first = harness.field("vaultSourceCache")
            assertNotNull(first)
            withContext(Dispatchers.Main) { harness.state.value = harness.state.value.copy(query = "mail") }
            harness.refresh()
            assertSame(first, harness.field("vaultSourceCache"))
            assertEquals(listOf("Mail"), harness.state.value.entries.map { it.title })
            harness.room.passwordEntryDao().insertPasswordEntry(password("Mail two"))
            waitUntil { harness.field("vaultSourceCache") == null }
            harness.refresh()
            assertEquals(listOf("Mail", "Mail two"), harness.state.value.entries.map { it.title })
            val mail = harness.room.passwordEntryDao().getPasswordEntryById(ids[1])!!
            harness.room.passwordEntryDao().updatePasswordEntry(mail.copy(title = "Renamed"))
            waitUntil { harness.field("vaultSourceCache") == null }
            harness.refresh()
            assertEquals(listOf("Mail two"), harness.state.value.entries.map { it.title })
        } finally { harness.close() }
    }

    @Test fun simultaneousWindowAndEditorCallbacksShareTheSameLoad() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("Fixture"))
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            val request = MonicaInputMethodService::class.java.declaredMethods.single { it.name == "requestRefreshVaultEntries" }
                .apply { isAccessible = true }
            withContext(Dispatchers.Main) { request.invoke(harness.service, false, 0L) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            val job = harness.field("refreshJob")
            withContext(Dispatchers.Main) { request.invoke(harness.service, false, 0L) }
            assertSame("Repeated lifecycle callbacks must not cancel and start the same disk read again", job, harness.field("refreshJob"))
            release.countDown()
            waitUntil { harness.state.value.entries.isNotEmpty() }
        } finally { release.countDown(); harness.close() }
    }

    @Test fun oldReadCannotPublishAfterTheSearchQueryChanges() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntries(listOf(password("Bitwarden"), password("Mail")))
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            val old = async { harness.refresh() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { harness.state.value = harness.state.value.copy(query = "mail") }
            release.countDown()
            old.await()
            assertEquals("mail", harness.state.value.query)
            assertTrue(harness.state.value.entries.isEmpty())
            assertNull(harness.field("vaultSourceCache"))
            harness.refresh()
            assertEquals(listOf("Mail"), harness.state.value.entries.map { it.title })
        } finally { release.countDown(); harness.close() }
    }

    @Test fun lockingDuringAReadClearsTheKeyboardAndCannotResurrectItsCache() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("Private fixture"))
            withContext(Dispatchers.Main) { harness.call("observeVaultLock") }
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            val old = async { harness.refresh() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            SessionManager.markLocked()
            waitUntil { !harness.state.value.unlocked }
            release.countDown()
            old.await()
            assertFalse(harness.state.value.unlocked)
            assertTrue(harness.state.value.entries.isEmpty())
            assertNull(harness.field("vaultSourceCache"))
        } finally { release.countDown(); harness.close() }
    }

    private fun password(title: String) = PasswordEntry(title = title, username = "fixture-user", password = "fixture", website = "")
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(10)
    }

    private inner class Harness(gate: AtomicReference<(() -> Unit)?> = AtomicReference(null)) {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java)
            .openHelperFactory(CursorGateFactory(gate)).build()
        val service = ConnectedService()
        private val wasUnlocked = SessionManager.isUnlocked.value
        @Suppress("UNCHECKED_CAST")
        val state get() = field("uiState") as MutableStateFlow<MonicaImeUiState>
        init {
            SessionManager.attachAppContext(context)
            SessionManager.markUnlocked()
            MonicaInputMethodService::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            set("database", room); set("securityManager", SecurityManager(context))
            set("settingsManager", SettingsManager(context)); set("autofillPreferences", AutofillPreferences(context))
            state.value = MonicaImeUiState(unlocked = true, activePanel = MonicaImePanel.PASSWORDS, isAutofillPanelVisible = true)
        }
        fun field(name: String): Any? = MonicaInputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun number(name: String) = field(name) as Long
        fun set(name: String, value: Any) { MonicaInputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
        fun bindEditor(): EditText {
            val editor = EditText(context)
            service.connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            set("inputViewVisible", true)
            return editor
        }
        fun fillOtp(entry: MonicaImePasswordEntry) {
            MonicaInputMethodService::class.java.getDeclaredMethod("insertCurrentPasswordTotp", MonicaImePasswordEntry::class.java)
                .apply { isAccessible = true }.invoke(service, entry)
        }
        fun call(name: String) { MonicaInputMethodService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service) }
        suspend fun refresh() = withContext(Dispatchers.Main) {
            val method = MonicaInputMethodService::class.java.declaredMethods.single { it.name == "refreshVaultEntries" }.apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Any?> { method.invoke(service, false, it) }
        }
        suspend fun close() {
            withContext(Dispatchers.Main) { (field("serviceScope") as CoroutineScope).cancel() }
            room.close()
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    private class ConnectedService : MonicaInputMethodService() {
        var connection: InputConnection? = null
        override fun getCurrentInputConnection(): InputConnection? = connection
    }

    private class CursorGateFactory(private val gate: AtomicReference<(() -> Unit)?>) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
        private fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase = object : SupportSQLiteDatabase by database {
            override fun query(query: SupportSQLiteQuery): Cursor = wrap(query, database.query(query))
            override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                wrap(query, database.query(query, cancellationSignal))
        }
        private fun wrap(query: SupportSQLiteQuery, cursor: Cursor): Cursor {
            if (!query.sql.trimStart().startsWith("SELECT id, title, username")) return cursor
            return object : CursorWrapper(cursor) {
                override fun moveToFirst(): Boolean = super.moveToFirst().also { if (it) gate.getAndSet(null)?.invoke() }
                override fun moveToNext(): Boolean = super.moveToNext().also { if (it) gate.getAndSet(null)?.invoke() }
            }
        }
    }
}
