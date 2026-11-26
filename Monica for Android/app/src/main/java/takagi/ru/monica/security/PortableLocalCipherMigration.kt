package takagi.ru.monica.security

import android.content.ContentValues
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.json.JSONArray

/** Converts only authenticated legacy ciphertext, without changing edit dates or sync ownership. */
internal object PortableLocalCipherMigration {
    private val mutex = Mutex()
    internal val vaultColumns = mapOf(
        "password_entries" to listOf("password", "authenticatorKey", "creditCardNumber", "creditCardCVV", "ssh_key_data"),
        "secure_items" to listOf("itemData"),
        "password_history_entries" to listOf("password"),
        "custom_fields" to listOf("value"),
        "attachments" to listOf("wrapped_cek"),
        "operation_logs" to listOf("changesJson"),
        "local_keepass_databases" to listOf("encrypted_password"),
        "local_mdbx_databases" to listOf("encrypted_password"),
        "keepass_remote_sources" to listOf("username_encrypted", "password_encrypted"),
        "mdbx_remote_sources" to listOf("username_encrypted", "password_encrypted"),
        "bitwarden_sync_raw_entry_records" to listOf("payload_cipher_text")
    )
    internal val steamColumns = mapOf("steam_accounts" to listOf(
        "steam_id", "accountName", "displayName", "deviceId", "sharedSecret", "identitySecret",
        "revocationCode", "tokenGid", "accessToken", "refreshToken", "steamLoginSecure", "rawSteamGuardJson"
    ))

    suspend fun run(database: RoomDatabase, security: SecurityManager, steam: Boolean = false): Int = mutex.withLock {
        if (!security.isVaultRuntimeUnlocked()) return@withLock 0
        val changed = migrate(database.openHelper.writableDatabase, security, if (steam) steamColumns else vaultColumns)
        if (changed > 0) database.invalidationTracker.refreshVersionsAsync()
        changed
    }

    internal suspend fun migrate(db: SupportSQLiteDatabase, security: SecurityManager, tables: Map<String, List<String>>): Int {
        var changed = 0
        for ((table, requestedColumns) in tables) {
            // Identifiers come exclusively from the above schema allowlist, never from item content.
            require(table.matches(Regex("[a-z_]+")))
            val available = db.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            for (column in requestedColumns.filter { it in available }) {
                require(column.matches(Regex("[a-zA-Z_]+")))
                val inspectJson = table == "secure_items" && column == "itemData"
                val inspectDeviceKey = table == "local_mdbx_databases" && column == "encrypted_password"
                // SQLite trim and Kotlin whitespace detection have different character sets.
                // Page nonempty values, then use the same format classifier as the reader.
                val predicate = "`$column` != ''"
                var after = Long.MIN_VALUE
                while (security.isVaultRuntimeUnlocked()) {
                    currentCoroutineContext().ensureActive()
                    val rows = db.query("SELECT rowid, `$column` FROM `$table` WHERE rowid > ? AND " +
                        "$predicate ORDER BY rowid LIMIT 64",
                        arrayOf<Any>(after)).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
                    }
                    if (rows.isEmpty()) break
                    for ((id, oldValue) in rows) {
                        currentCoroutineContext().ensureActive()
                        if (!security.isVaultRuntimeUnlocked()) return changed
                        val replacement = runCatching {
                            // DEVICE_KEY stores a tagged encrypted payload, not a password.
                            // Preserve the tag: removing it changes the database's unlock method.
                            if (inspectDeviceKey && takagi.ru.monica.repository.MdbxVaultCrypto.isEncodedDeviceKey(oldValue)) {
                                val prefix = "mdbx2-device-key:v1:"
                                val cipher = oldValue.removePrefix(prefix)
                                val portable = portableCipher(cipher, security) ?: return@runCatching null
                                return@runCatching prefix + portable
                            }
                            val deviceCipher = LocalCipherEnvelope.isDeviceBound(oldValue)
                            if (!deviceCipher && !inspectJson) return@runCatching null
                            val plain = security.decryptData(oldValue)
                            val nested = if (inspectJson) migrateNestedJson(plain, security) else null
                            if (!deviceCipher && nested == null) return@runCatching null
                            val portablePlain = nested ?: plain
                            val encrypted = security.encryptData(portablePlain)
                            check(encrypted.startsWith("MDK|") && security.decryptData(encrypted) == portablePlain)
                            encrypted
                        }.getOrNull()
                        if (replacement != null && security.isVaultRuntimeUnlocked()) {
                            changed += db.update(table, 0, ContentValues().apply { put(column, replacement) },
                                "rowid = ? AND `$column` = ?", arrayOf<Any>(id, oldValue))
                        }
                        // Bad ciphertext stays byte-for-byte unchanged; it cannot block later rows.
                        after = id
                    }
                    yield()
                }
            }
        }
        return changed
    }

    internal fun portableCipher(value: String, security: SecurityManager): String? {
        if (!LocalCipherEnvelope.isDeviceBound(value)) return null
        val plain = security.decryptData(value)
        val replacement = security.encryptData(plain)
        check(replacement.startsWith("MDK|") && security.decryptData(replacement) == plain)
        return replacement
    }

    // Historical OTP imports may encrypt the secret inside JSON, including
    // inside an already MDK-encrypted container. Preserve unknown members.
    private fun migrateNestedJson(plain: String, security: SecurityManager): String? {
        if (!plain.trimStart().startsWith("{")) return null
        val json = runCatching { JSONObject(plain) }.getOrNull() ?: return null
        var changed = false
        fun rewrite(value: Any?, depth: Int): Any? {
            require(depth <= 32)
            return when (value) {
                is JSONObject -> value.apply { keys().asSequence().toList().forEach { put(it, rewrite(get(it), depth + 1)) } }
                is JSONArray -> value.apply { for (i in 0 until length()) put(i, rewrite(get(i), depth + 1)) }
                is String -> portableCipher(value, security)?.let { replacement ->
                    changed = true
                    replacement
                } ?: value
                else -> value
            }
        }
        rewrite(json, 0)
        return if (changed) json.toString() else null
    }
}
