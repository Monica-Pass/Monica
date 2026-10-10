package takagi.ru.monica.passkey

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.repository.PasskeyRepository

class PasskeyCopyStorageTest {
    @Test fun copiedKeyRemainsUsableWhenEitherRecordIsRemoved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repository = PasskeyRepository(db.passkeyDao(), context = context)
        try {
            val raw = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private.encoded)
            val entry = PasskeyEntry(credentialId = "Y29weS10ZXN0", rpId = "example.invalid", rpName = "Example",
                userId = "dXNlcg", userName = "Fixture", userDisplayName = "Fixture", publicKey = "",
                privateKeyAlias = raw, passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT)
            val id = repository.copyPasskey(entry)
            val source = repository.getAllPasskeysSync().single()
            val copyId = repository.copyPasskey(source.copy(id = 0, categoryId = 42))
            assertNotEquals(id, copyId)
            assertEquals(2, repository.getAllPasskeysSync().size)
            repository.deletePasskey(source)
            val copy = repository.getAllPasskeysSync().single()
            assertEquals(42L, copy.categoryId)
            assertEquals(PasskeyPortability.PORTABLE, PasskeyPortability.inspect(context, copy))
            repository.deletePasskey(copy)
        } finally {
            repository.getAllPasskeysSync().forEach { repository.deletePasskey(it) }
            db.close()
        }
    }
}
