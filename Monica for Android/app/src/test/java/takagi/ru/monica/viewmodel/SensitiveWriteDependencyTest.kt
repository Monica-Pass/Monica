package takagi.ru.monica.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SensitiveValueEncryptor
import takagi.ru.monica.utils.StringResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class SensitiveWriteDependencyTest {
    private lateinit var db: PasswordDatabase
    private lateinit var items: SecureItemRepository
    private val store = ViewModelStore()
    private val strings = StringResolver { _, _ -> "Synthetic test message" }
    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
        items = SecureItemRepository(db.secureItemDao())
    }
    @After fun close() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun assertNoRows() {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM secure_items").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
    @Test fun missingCardEncryptionDependencyRejectsBeforeInsert() = runBlocking {
        val model = BankCardViewModel(items, strings = strings).also { store.put("card", it) }
        val error = runCatching { model.addCard("Synthetic card", BankCardData("4111111111111111", "Synthetic", "01", "2030")).await() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertNoRows()
    }
    @Test fun missingDocumentEncryptionDependencyRejectsBeforeInsert() = runBlocking {
        val model = DocumentViewModel(items, strings = strings).also { store.put("document", it) }
        val error = runCatching { model.addDocument("Synthetic document", DocumentData(DocumentType.PASSPORT, "SYNTHETIC-327", "Synthetic")).await() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertNoRows()
    }
    @Test fun missingTotpEncryptionDependencyReportsFailureWithoutInsert() = runBlocking {
        val model = TotpViewModel(items, PasswordRepository(db.passwordEntryDao()), strings = strings).also { store.put("totp", it) }
        val completed = CompletableDeferred<Boolean>()
        model.saveTotpAcrossTargets(null, "Synthetic OTP", "", TotpData(secret = "JBSWY3DPEHPK3PXP"),
            targets = listOf(StorageTarget.MonicaLocal(null)), onComplete = { completed.complete(it) })
        assertFalse(withTimeout(5000) { completed.await() }); assertNoRows()
    }
    @Test fun explicitEncryptionFailureCannotFallBackToPlaintext() = runBlocking {
        val model = BankCardViewModel(items, strings = strings,
            sensitiveValueEncryptor = SensitiveValueEncryptor { throw IllegalStateException("Synthetic encryption failure") })
            .also { store.put("failed-card", it) }
        assertNotNull(runCatching { model.addCard("Synthetic card", BankCardData("4111111111111111", "Synthetic", "01", "2030")).await() }.exceptionOrNull())
        assertNoRows()
    }
}
