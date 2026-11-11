package takagi.ru.monica.rustcore

import takagi.ru.monica.utils.AppLocaleStringResolver

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.viewmodel.TotpViewModel

@RunWith(AndroidJUnit4::class)
class ListFirstFrameInstrumentedTest {
    @Test
    fun nativeSortMatchesKotlinAndMeasuresTheWholeJniRoundTrip() {
        val random = Random(117)
        for (size in listOf(256, 1000, 10000, 50000)) {
            val items = List(size) { index ->
                SecureItem(id = index.toLong(), itemType = ItemType.BANK_CARD, title = "",
                    itemData = "must-not-cross-jni", isFavorite = random.nextBoolean(),
                    sortOrder = random.nextInt(-100, 100), updatedAt = Date(random.nextLong()))
            }
            for (tieById in listOf(false, true)) {
                assertEquals(RustListSortCore.kotlinSort(items, tieById) { it },
                    requireNotNull(RustListSortCore.nativeSort(items, tieById) { it }))
            }
            repeat(5) {
                RustListSortCore.nativeSort(items, true) { it }
                RustListSortCore.kotlinSort(items, true) { it }
            }
            val nativeTimes = mutableListOf<Long>()
            val kotlinTimes = mutableListOf<Long>()
            repeat(11) {
                nativeTimes += measureNanoTime { RustListSortCore.nativeSort(items, true) { it } }
                kotlinTimes += measureNanoTime { RustListSortCore.kotlinSort(items, true) { it } }
            }
            report("sort rows=$size nativeMedianMs=${nativeTimes.sorted()[5] / 1e6} kotlinMedianMs=${kotlinTimes.sorted()[5] / 1e6}")
        }
    }

    @Test
    fun authenticatorQueriesOnlyCandidatesAndEmitsReadyAfterRealEmptyOrPopulatedData() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = database.passwordEntryDao()
        val viewModel = TotpViewModel(SecureItemRepository(database.secureItemDao()), PasswordRepository(passwords), strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            assertFalse(viewModel.parsedTotpState.value.isReady)
            val empty = withTimeout(10000) { viewModel.parsedTotpState.first { it.isReady } }
            assertTrue(empty.items.isEmpty())
            val base = PasswordEntry(title = "account", website = "", username = "user", password = "encrypted")
            passwords.insertPasswordEntries(List(2000) { base.copy(title = "plain-$it") })
            passwords.insertPasswordEntries(listOf(
                base.copy(title = "valid", authenticatorKey = "JBSWY3DPEHPK3PXP"),
                base.copy(title = "archived", authenticatorKey = "JBSWY3DPEHPK3PXP", isArchived = true),
                base.copy(title = "deleted", authenticatorKey = "JBSWY3DPEHPK3PXP", isDeleted = true)
            ))
            assertEquals(listOf("valid"), passwords.getActiveAuthenticatorEntries().first().map { it.title })
            val populated = withTimeout(10000) { viewModel.parsedTotpState.first { it.items.size == 1 } }
            assertEquals("valid", populated.items.single().item.title)
            assertEquals("JBSWY3DPEHPK3PXP", populated.items.single().totpData.secret)
            val coldViewModel = TotpViewModel(SecureItemRepository(database.secureItemDao()), PasswordRepository(passwords), strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
            try {
                assertFalse(coldViewModel.parsedTotpState.value.isReady)
                val firstReady = withTimeout(10000) { coldViewModel.parsedTotpState.first { it.isReady } }
                assertEquals(1, firstReady.items.size)
            } finally {
                coldViewModel.viewModelScope.cancel()
            }
            val oldMs = measureNanoTime { passwords.getActiveEntries().first() } / 1e6
            val newMs = measureNanoTime { passwords.getActiveAuthenticatorEntries().first() } / 1e6
            report("otp query rows=2001 candidates=1 allRowsMs=$oldMs candidatesMs=$newMs")
            val boundId = passwords.getActiveAuthenticatorEntries().first().single().id
            passwords.updateAuthenticatorKey(boundId, "JBSWY3DPEHPK3PXQ")
            withTimeout(10000) {
                viewModel.parsedTotpState.first { it.items.singleOrNull()?.totpData?.secret == "JBSWY3DPEHPK3PXQ" }
            }
            passwords.updateAuthenticatorKey(boundId, "")
            withTimeout(10000) { viewModel.parsedTotpState.first { it.isReady && it.items.isEmpty() } }
        } finally {
            viewModel.viewModelScope.cancel()
            database.close()
        }
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("stream", "\nPERF $message\n")
        })
    }
}
