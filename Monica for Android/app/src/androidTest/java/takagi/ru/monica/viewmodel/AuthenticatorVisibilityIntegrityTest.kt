package takagi.ru.monica.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Exercise the real Room -> repository -> shared list flow using synthetic, isolated rows. */
@RunWith(AndroidJUnit4::class)
class AuthenticatorVisibilityIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unreadablePasswordOtpStaysVisibleWithoutBecomingSelectableOrChangingStorage() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val model = TotpViewModel(items, passwords, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            val storedId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "Unreadable stored",
                itemData = "C2|synthetic-unreadable-stored"))
            val passwordId = passwords.insertPasswordEntry(PasswordEntry(title = "Unreadable password",
                username = "alice", website = "example.invalid", password = "synthetic",
                authenticatorKey = "C2|synthetic-unreadable-password", notes = "Keep my recovery notes"))
            val validId = passwords.insertPasswordEntry(PasswordEntry(title = "Valid password", username = "bob",
                website = "", password = "", authenticatorKey = "JBSWY3DPEHPK3PXP"))
            val original = passwords.getPasswordEntryById(passwordId)
            val state = withTimeout(10_000) { model.parsedTotpState.first {
                it.isReady && it.items.any { row -> row.item.id == storedId } &&
                    it.items.any { row -> row.item.id == -validId }
            } }
            assertEquals("Unreadable OTP must remain visible alongside readable OTP", setOf(storedId, -passwordId, -validId),
                state.items.map { it.item.id }.toSet())
            val unreadable = state.items.single { it.item.id == -passwordId }
            assertEquals("", unreadable.totpData.secret)
            assertEquals(passwordId, unreadable.totpData.boundPasswordId)
            assertNull(model.parseTotpDataForDisplay(unreadable.item))
            assertEquals(original?.authenticatorKey, unreadable.item.itemData)
            val selectable = withTimeout(10_000) { model.allParsedTotpItems.first { it.any { row -> row.item.id == -validId } } }
            assertEquals(listOf(-validId), selectable.map { it.item.id })
            assertEquals(original, passwords.getPasswordEntryById(passwordId))
            assertEquals("C2|synthetic-unreadable-stored", items.getItemById(storedId)?.itemData)

            // A subsequently restored/readable source must replace the placeholder without restarting the VM.
            passwords.updateAuthenticatorKey(passwordId, "GEZDGNBVGY3TQOJQ")
            val recovered = withTimeout(10_000) { model.parsedTotpState.first { it.items.any { row ->
                row.item.id == -passwordId && row.totpData.secret == "GEZDGNBVGY3TQOJQ"
            } } }
            assertEquals(3, recovered.items.size)
            assertEquals(original?.notes, passwords.getPasswordEntryById(passwordId)?.notes)
        } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }

    @Test fun differentMotpPinsOnTheSameBindingRemainSeparateButExactMirrorsCollapse() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val model = TotpViewModel(items, passwords, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            val passwordId = passwords.insertPasswordEntry(PasswordEntry(title = "mOTP account",
                website = "", username = "", password = ""))
            val first = TotpData(secret = "1234567890abcdef", otpType = OtpType.MOTP, period = 10, pin = "1234", boundPasswordId = passwordId)
            val second = first.copy(pin = "5678")
            assertNotEquals(TotpGenerator.generateOtp(first, currentSeconds = 1_700_000_000),
                TotpGenerator.generateOtp(second, currentSeconds = 1_700_000_000))
            val firstId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "PIN one", itemData = Json.encodeToString(first)))
            val secondId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "PIN two", itemData = Json.encodeToString(second)))
            val mirrorId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "Exact mirror", itemData = Json.encodeToString(first)))
            // The first collector starts after writes, so it receives the complete query snapshot.
            val state = withTimeout(10_000) { model.parsedTotpState.first { it.isReady } }
            assertEquals(2, state.items.size)
            assertTrue(state.items.any { it.item.id == secondId })
            assertEquals(1, state.items.count { it.item.id == firstId || it.item.id == mirrorId })
            assertEquals(3, db.secureItemDao().getItemsByType(ItemType.TOTP).first().size)
        } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }

    @Test fun passwordAndStoredMotpWithDifferentPinsAreNotMirrors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val model = TotpViewModel(items, passwords, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            val data = TotpData(secret = "1234567890abcdef", otpType = OtpType.MOTP, period = 10, pin = "1234")
            val passwordId = passwords.insertPasswordEntry(PasswordEntry(title = "mOTP password", website = "", username = "",
                password = "", authenticatorKey = TotpDataResolver.toBitwardenPayload("mOTP password", data)))
            val storedId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "Different PIN",
                itemData = Json.encodeToString(data.copy(pin = "5678", boundPasswordId = passwordId))))
            val state = withTimeout(10_000) { model.parsedTotpState.first { it.isReady } }
            assertEquals(setOf(storedId, -passwordId), state.items.map { it.item.id }.toSet())
            // Once both parameters really match, only the stored mirror should be displayed.
            val mirrorId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "Matching PIN",
                itemData = Json.encodeToString(data.copy(boundPasswordId = passwordId))))
            withTimeout(10_000) { model.parsedTotpState.first { it.items.map { row -> row.item.id }.toSet() == setOf(storedId, mirrorId) } }
            assertEquals(TotpDataResolver.toBitwardenPayload("mOTP password", data), passwords.getPasswordEntryById(passwordId)?.authenticatorKey)
        } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }

    @Test fun unlockingReloadsTheSameCiphertextWithoutWritingThePasswordRow() = runBlocking {
        val prefix = "otp-unlock-${java.util.UUID.randomUUID()}-"
        val preferenceNames = mutableSetOf<String>()
        val fixtureContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(context.applicationInfo).apply {
                dataDir = java.io.File(context.cacheDir, prefix).absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                preferenceNames += prefix + name
                return context.getSharedPreferences(prefix + name, mode)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val wasUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
        val security = takagi.ru.monica.security.SecurityManager(fixtureContext)
        var model: TotpViewModel? = null
        try {
            security.setMasterPassword("Synthetic OTP fixture password")
            val encrypted = security.encryptData("JBSWY3DPEHPK3PXP")
            assertTrue(encrypted.startsWith("MDK|"))
            // Simulate an unavailable device wrapper in isolated preferences; retain the password wrapper.
            takagi.ru.monica.security.SecurePreferencesStore.open(fixtureContext,
                takagi.ru.monica.security.SecurePreferencesStore.MONICA).preferences
                .edit().remove("mdk_keystore_blob").commit()
            takagi.ru.monica.security.SessionManager.markLocked()
            assertTrue(runCatching { security.decryptDataIfMonicaCiphertext(encrypted) }.isFailure)
            val passwords = PasswordRepository(db.passwordEntryDao())
            val passwordId = passwords.insertPasswordEntry(PasswordEntry(title = "Locked OTP", username = "alice", website = "",
                password = "", authenticatorKey = encrypted, notes = "Recovery notes retained"))
            val original = passwords.getPasswordEntryById(passwordId)
            val activeModel = TotpViewModel(SecureItemRepository(db.secureItemDao()), passwords,
                securityManager = security, strings = AppLocaleStringResolver(context))
            model = activeModel
            val unreadable = withTimeout(10_000) { activeModel.parsedTotpState.first { it.isReady } }
            assertEquals(listOf(-passwordId), unreadable.items.map { it.item.id })
            assertEquals("", unreadable.items.single().totpData.secret)
            assertTrue(security.unlockVaultWithPassword("Synthetic OTP fixture password"))
            takagi.ru.monica.security.SessionManager.markUnlocked()
            withTimeout(10_000) { activeModel.parsedTotpState.first { it.items.singleOrNull()?.totpData?.secret == "JBSWY3DPEHPK3PXP" } }
            val picker = withTimeout(10_000) { activeModel.allParsedTotpItems.first { it.isNotEmpty() } }
            assertEquals(-passwordId, picker.single().item.id)
            assertEquals(original, passwords.getPasswordEntryById(passwordId))
        } finally {
            model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            db.close()
            takagi.ru.monica.security.SecurityManager.clearRuntimeUnlockCache()
            if (wasUnlocked) takagi.ru.monica.security.SessionManager.markUnlocked()
            else takagi.ru.monica.security.SessionManager.markLocked()
            preferenceNames.forEach(context::deleteSharedPreferences)
        }
    }

    @Test fun deletingOneMotpDoesNotClearThePasswordKeyWithADifferentPin() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val model = TotpViewModel(items, passwords, strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        try {
            val data = TotpData(secret = "1234567890abcdef", otpType = OtpType.MOTP, period = 10, pin = "1234")
            val key = TotpDataResolver.toBitwardenPayload("Password OTP", data)
            val passwordId = passwords.insertPasswordEntry(PasswordEntry(title = "Password OTP", website = "", username = "",
                password = "", authenticatorKey = key))
            val storedId = items.insertItem(SecureItem(itemType = ItemType.TOTP, title = "Different PIN",
                itemData = Json.encodeToString(data.copy(pin = "5678", boundPasswordId = passwordId))))
            val before = passwords.getPasswordEntryById(passwordId)
            model.deleteTotpItem(requireNotNull(items.getItemById(storedId)))
            withTimeout(10_000) { db.secureItemDao().getItemsByType(ItemType.TOTP).first { it.isEmpty() } }
            assertEquals(true, items.getItemById(storedId)?.isDeleted)
            assertEquals("Deleting one PIN must not clear a different authenticator", before, passwords.getPasswordEntryById(passwordId))
        } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }
}
