package takagi.ru.monica.bitwarden

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordDatabase

/** Synthetic schema-83 transport columns only; never opens or resets the user's Room database. */
@RunWith(AndroidJUnit4::class)
class BitwardenTransportMigrationInstrumentedTest {
    @Test
    fun migration83To84AddsFiveColumnsAndPreservesLegacyUrlsTlsIdentityAndIndices() = withLegacyDatabase { db ->
        val oldColumns = columns(db)
        val rowsBefore = rows(db, oldColumns.keys.toList())
        val indicesBefore = indices(db)
        assertEquals(83, PasswordDatabase.MIGRATION_83_84.startVersion)
        assertEquals(84, PasswordDatabase.MIGRATION_83_84.endVersion)

        PasswordDatabase.MIGRATION_83_84.migrate(db)

        val after = columns(db)
        assertEquals(NEW_COLUMNS, after.keys - oldColumns.keys)
        assertEquals(oldColumns.size + 5, after.size)
        assertEquals(oldColumns, after.filterKeys { it in oldColumns })
        assertEquals(rowsBefore, rows(db, oldColumns.keys.toList()))
        assertEquals(indicesBefore, indices(db))
        assertEquals(Column("TEXT", true, "''"), after["transport_revision"])
        for (name in NEW_COLUMNS - "transport_revision") assertEquals(Column("TEXT", false, null), after[name])
        db.query("SELECT environment_preset, web_vault_url, notifications_url, transport_revision, transport_secret_ref FROM bitwarden_vaults").use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                count++
                for (index in listOf(0, 1, 2, 4)) assertTrue(cursor.isNull(index))
                assertEquals("", cursor.getString(3))
            }
            assertEquals(3, count)
        }
    }

    @Test
    fun migratedRowsSupportExplicitConfigurationWithoutChangingOtherLegacyRowsOrNewRowDefaults() = withLegacyDatabase { db ->
        val legacyColumns = columns(db).keys.toList()
        val before = rows(db, legacyColumns)
        PasswordDatabase.MIGRATION_83_84.migrate(db)
        db.execSQL(
            "UPDATE bitwarden_vaults SET environment_preset = ?, web_vault_url = ?, notifications_url = ?, transport_revision = ?, transport_secret_ref = ? WHERE id = ?",
            arrayOf<Any?>("CUSTOM", "https://web.example.invalid/Team/", "https://notify.example.invalid/Notify/", REVISION, REFERENCE, 41L),
        )
        assertEquals(before, rows(db, legacyColumns))
        db.query("SELECT id, environment_preset, web_vault_url, notifications_url, transport_revision, transport_secret_ref FROM bitwarden_vaults ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(41L, cursor.getLong(0))
            assertEquals("CUSTOM", cursor.getString(1))
            assertEquals("https://web.example.invalid/Team/", cursor.getString(2))
            assertEquals("https://notify.example.invalid/Notify/", cursor.getString(3))
            assertEquals(REVISION, cursor.getString(4))
            assertEquals(REFERENCE, cursor.getString(5))
            while (cursor.moveToNext()) {
                for (index in listOf(1, 2, 3, 5)) assertTrue(cursor.isNull(index))
                assertEquals("", cursor.getString(4))
            }
        }
        db.execSQL(
            "INSERT INTO bitwarden_vaults (id, email, canonical_email, account_key, server_url, api_url, identity_url) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(99L, "new@example.invalid", "new@example.invalid", "new-key", "https://new.example.invalid/", "https://new.example.invalid/api/", "https://new.example.invalid/identity/"),
        )
        db.query("SELECT environment_preset, web_vault_url, notifications_url, transport_revision, transport_secret_ref FROM bitwarden_vaults WHERE id = 99").use { cursor ->
            assertTrue(cursor.moveToFirst())
            for (index in listOf(0, 1, 2, 4)) assertTrue(cursor.isNull(index))
            assertEquals("", cursor.getString(3))
        }
    }

    private fun withLegacyDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(
                object : SupportSQLiteOpenHelper.Callback(83) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                """
                CREATE TABLE bitwarden_vaults (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    email TEXT NOT NULL,
                    canonical_email TEXT NOT NULL DEFAULT '',
                    user_id TEXT,
                    account_key TEXT NOT NULL DEFAULT '',
                    server_url TEXT NOT NULL,
                    api_url TEXT NOT NULL,
                    identity_url TEXT NOT NULL,
                    events_url TEXT,
                    tls_certificate_alias TEXT,
                    tls_ca_certificate_pem TEXT,
                    tls_mtls_enabled INTEGER NOT NULL DEFAULT 0,
                    tls_client_cert_pkcs12 TEXT,
                    tls_encrypted_client_cert_password TEXT,
                    encrypted_access_token TEXT,
                    encrypted_refresh_token TEXT,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    is_locked INTEGER NOT NULL DEFAULT 1,
                    is_connected INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX index_bitwarden_vaults_account_key ON bitwarden_vaults(account_key)")
            db.execSQL("CREATE INDEX index_bitwarden_vaults_canonical_email ON bitwarden_vaults(canonical_email)")
            db.execSQL("CREATE INDEX index_bitwarden_vaults_is_default ON bitwarden_vaults(is_default)")
            val fixtures = listOf(
                arrayOf<Any?>(41L, "one@example.invalid", "one@example.invalid", "synthetic-user", "id-form-key",
                    "http://192.168.1.8:8080/Vault", "https://api.example.invalid/Explicit/V2", "https://identity.example.invalid/Auth",
                    "https://events.example.invalid/Unchanged", "synthetic-alias", "synthetic-ca", 1, "synthetic-p12", "  ", "synthetic-access", "synthetic-refresh", 1, 0, 1),
                arrayOf<Any?>(42L, "two@example.invalid", "two@example.invalid", null, "email-form-key",
                    "https://vault.bitwarden.eu", "https://api.bitwarden.eu", "https://identity.bitwarden.eu", null,
                    null, null, 0, null, null, null, null, 0, 1, 0),
                arrayOf<Any?>(43L, "three@example.invalid", "three@example.invalid", null, "ipv6-key",
                    "http://[fd00::7]:8080/KeepCase/", "http://[fd00::7]:8080/KeepCase/api/", "http://[fd00::7]:8080/KeepCase/identity/", null,
                    null, null, 0, null, "", null, null, 0, 1, 0),
            )
            for (fixture in fixtures) db.execSQL(
                "INSERT INTO bitwarden_vaults VALUES (${fixture.joinToString(",") { "?" }})", fixture,
            )
            block(db)
        } finally {
            helper.close()
        }
    }

    private data class Column(val type: String, val notNull: Boolean, val defaultValue: String?)

    private fun columns(db: SupportSQLiteDatabase): Map<String, Column> = linkedMapOf<String, Column>().apply {
        db.query("PRAGMA table_info(bitwarden_vaults)").use { cursor ->
            while (cursor.moveToNext()) {
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                put(cursor.getString(cursor.getColumnIndexOrThrow("name")), Column(
                    cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex),
                ))
            }
        }
    }

    private fun rows(db: SupportSQLiteDatabase, columns: List<String>): List<List<String?>> = buildList {
        db.query("SELECT ${columns.joinToString(",")} FROM bitwarden_vaults ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) add(columns.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    private fun indices(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query("PRAGMA index_list(bitwarden_vaults)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private companion object {
        const val REVISION = "00000000-0000-4000-8000-000000000101"
        const val REFERENCE = "bw_transport_secret_v1_00000000-0000-4000-8000-000000000102"
        val NEW_COLUMNS = setOf("environment_preset", "web_vault_url", "notifications_url", "transport_revision", "transport_secret_ref")
    }
}
