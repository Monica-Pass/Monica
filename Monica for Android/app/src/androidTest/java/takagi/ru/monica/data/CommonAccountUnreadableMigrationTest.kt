package takagi.ru.monica.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.security.SecurityManager

class CommonAccountUnreadableMigrationTest {
    @Test fun unreadableLegacyAccountMustNotBeClearedOrMarkedMigrated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val method = Class.forName("takagi.ru.monica.data.CommonAccountPreferencesKt")
            .getDeclaredMethod("getCommonAccountDataStore", Context::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val store = method.invoke(null, context) as DataStore<Preferences>
        val original = store.data.first()
        val security = SecurityManager(context)
        val ciphertext = security.encryptDataLegacyCompat("synthetic-unreadable-account")
        val prefix = ciphertext.substringBefore('|') + "|"
        val bytes = Base64.decode(ciphertext.removePrefix(prefix), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val damaged = prefix + Base64.encodeToString(bytes, Base64.NO_WRAP)
        try {
            store.edit { it.clear(); it[stringPreferencesKey("default_username")] = damaged }
            val before = store.data.first().asMap()
            CommonAccountPreferences(context, security).migrateLegacyDefaultsToTemplatesIfNeeded(
                "account", "email", "phone", "Account", "Email", "Phone")
            assertEquals("Unreadable account data is not an empty account or a completed migration", before, store.data.first().asMap())
        } finally {
            store.updateData { original }
        }
    }
}
