package takagi.ru.monica.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import takagi.ru.monica.security.SecurityManager

private val Context.passwordHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "password_history")

/**
 * 密码生成历史管理器
 * 使用 DataStore 存储密码生成历史记录
 */
class PasswordHistoryManager(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    // PasswordViewModel creates this manager during the initial password screen.
    // Android Keystore is only needed once history is actually decoded/encoded,
    // so keep SecurityManager out of the cold-start constructor path.
    private val securityManager: SecurityManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecurityManager(appContext)
    }

    companion object {
        private val HISTORY_KEY = stringPreferencesKey("password_generation_history")
        private const val MAX_HISTORY_SIZE = 50
    }

    val historyFlow: Flow<List<PasswordGenerationHistory>> = appContext.passwordHistoryDataStore.data
        .onStart { migrateLegacyHistoryIfNeeded() }
        .map { preferences ->
            decodeHistoryPayload(preferences[HISTORY_KEY])
        }

    suspend fun addHistory(
        password: String,
        packageName: String = "",
        domain: String = "",
        username: String = "",
        type: String = "AUTOFILL"
    ) {
        appContext.passwordHistoryDataStore.edit { preferences ->
            val currentHistory = decodeHistoryPayload(preferences[HISTORY_KEY])
            val newRecord = PasswordGenerationHistory(
                password = password,
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                domain = domain,
                username = username,
                type = type
            )
            val updatedHistory = (listOf(newRecord) + currentHistory).take(MAX_HISTORY_SIZE)
            preferences[HISTORY_KEY] = encodeHistoryPayload(updatedHistory)
        }
    }

    suspend fun clearHistory() {
        appContext.passwordHistoryDataStore.edit { preferences ->
            preferences.remove(HISTORY_KEY)
        }
    }

    suspend fun exportHistoryJson(): String {
        // An unreadable history must not be exported as a successful empty history.
        // UI decoding remains tolerant; backups need an explicit failure to preserve old copies.
        val raw = appContext.passwordHistoryDataStore.data.first()[HISTORY_KEY]
        if (raw.isNullOrBlank()) return "[]"
        val plain = if (securityManager.looksLikeMonicaCiphertext(raw)) securityManager.decryptData(raw) else raw
        return json.encodeToString(json.decodeFromString<List<PasswordGenerationHistory>>(plain))
    }

    suspend fun importHistory(history: List<PasswordGenerationHistory>) {
        appContext.passwordHistoryDataStore.edit { preferences ->
            preferences[HISTORY_KEY] = encodeHistoryPayload(history.take(MAX_HISTORY_SIZE))
        }
    }

    suspend fun deleteHistory(timestamp: Long) {
        appContext.passwordHistoryDataStore.edit { preferences ->
            val currentHistory = decodeHistoryPayload(preferences[HISTORY_KEY])
            val updatedHistory = currentHistory.filter { it.timestamp != timestamp }
            preferences[HISTORY_KEY] = encodeHistoryPayload(updatedHistory)
        }
    }

    private suspend fun migrateLegacyHistoryIfNeeded() {
        appContext.passwordHistoryDataStore.edit { preferences ->
            val raw = preferences[HISTORY_KEY] ?: return@edit
            if (raw.isBlank() || raw.startsWith("MDK|")) {
                return@edit
            }
            val decoded = runCatching {
                val plain = if (securityManager.looksLikeMonicaCiphertext(raw)) securityManager.decryptData(raw) else raw
                json.decodeFromString<List<PasswordGenerationHistory>>(plain)
            }.getOrNull() ?: return@edit
            val encrypted = encodeHistoryPayload(decoded)
            check(json.decodeFromString<List<PasswordGenerationHistory>>(securityManager.decryptData(encrypted)) == decoded)
            preferences[HISTORY_KEY] = encrypted
        }
    }

    private fun decodeHistoryPayload(raw: String?): List<PasswordGenerationHistory> {
        if (raw.isNullOrBlank()) return emptyList()
        val historyJson = runCatching {
            if (securityManager.looksLikeMonicaCiphertext(raw)) {
                securityManager.decryptData(raw)
            } else {
                raw
            }
        }.getOrDefault("[]")
        return runCatching {
            json.decodeFromString<List<PasswordGenerationHistory>>(historyJson)
        }.getOrDefault(emptyList())
    }

    private fun encodeHistoryPayload(history: List<PasswordGenerationHistory>): String {
        return securityManager.encryptDataLegacyCompat(json.encodeToString(history))
    }
}
