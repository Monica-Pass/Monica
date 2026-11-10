package takagi.ru.monica.security

import android.database.Cursor
import android.os.Process
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

/** Host-ordered, additive-only fixture. Never run seed/recovery/key-loss methods with this class. */
class ToolchainRoundTripCorpusTest {
    @Test fun appendIndependentLocalRecordOnUpgradedApk() = runBlocking {
        authorize()
        val f = LegacyVaultCorpus()
        try {
            val evidence = File(f.root, "toolchain-roundtrip-326-v2")
            check(!evidence.exists()) { "Never overwrite round-trip evidence or repeat the writer" }
            f.authorizeFixture()
            assertTrue("The original password must unlock", f.security.unlockVaultWithPassword(f.password))
            assertTrue("Original corpus must match before adding a row", f.marker.readText() == f.snapshot().toString())
            f.verifyPayloads()
            check(f.db.passwordEntryDao().getAllPasswordEntriesSync().none { it.title == TITLE })
            val originalMarkerDigest = digest(f.marker.readBytes())
            val originalRaw = rawSnapshot(f, null).toString()
            check(evidence.mkdir())
            File(evidence, "original-raw-rows.json").writeText(originalRaw)
            val row = PasswordEntry(
                title = TITLE, website = WEBSITE, username = USERNAME,
                password = f.security.encryptData(PASSWORD),
                authenticatorKey = f.security.encryptDataLegacyCompat(OTP),
                notes = NOTES, createdAt = Date(1_794_182_400_123L),
                updatedAt = Date(1_794_182_400_123L), sortOrder = 500,
            )
            assertTrue(row.password.startsWith("MDK|"))
            assertTrue(row.authenticatorKey.startsWith("MDK|"))
            val id = f.db.withTransaction { f.db.passwordEntryDao().insert(row) }
            check(id > 0)
            val actual = checkNotNull(f.db.passwordEntryDao().getPasswordEntryById(id))
            assertTrue("Inserted row must retain every supplied column", row.copy(id = id) == actual)
            assertOldRowsUnchanged(f, id, originalMarkerDigest, originalRaw)
            // The new record has no attachment. The unchanged existing verifier still checks
            // every original attachment, the real software Passkey signature and native MDBX.
            f.verifyPayloads()
            assertOldRowsUnchanged(f, id, originalMarkerDigest, originalRaw)
            val expected = JSONObject()
                .put("id", id)
                .put("writerPid", Process.myPid())
                .put("writerUid", Process.myUid())
                .put("writerVersion", f.context.packageManager.getPackageInfo(f.context.packageName, 0).versionName)
                .put("originalMarkerSha256", originalMarkerDigest)
                .put("newRawRow", rawRow(f, id).toString())
            check(File(evidence, "expected.json").createNewFile())
            File(evidence, "expected.json").writeText(expected.toString())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("toolchainRoundTripPhase", "new_record_written")
                putString("toolchainRoundTripPid", Process.myPid().toString())
                putString("toolchainRoundTripRowId", id.toString())
            })
        } finally {
            SecurityManager.clearRuntimeUnlockCache()
            SessionManager.markLocked()
        }
    }

    @Test fun readAdditionalRecordAndOriginalCorpusInDifferentProcess() = runBlocking {
        authorize()
        val f = LegacyVaultCorpus()
        val evidence = File(f.root, "toolchain-roundtrip-326-v2")
        val expected = JSONObject(File(evidence, "expected.json").readText())
        check(Process.myUid() == expected.getInt("writerUid")) { "Do not copy fixture evidence to another user or package" }
        val previousPid = checkNotNull(InstrumentationRegistry.getArguments().getString("toolchainPreviousPid"))
            .toInt()
        check(previousPid > 0 && Process.myPid() != previousPid && Process.myPid() != expected.getInt("writerPid")) { "Host must force-stop and start a different process" }
        try {
            SecurityManager.clearRuntimeUnlockCache()
            f.authorizeFixture()
            assertTrue("The original password must unlock the existing vault", f.security.unlockVaultWithPassword(f.password))
            val id = expected.getLong("id")
            val row = checkNotNull(f.db.passwordEntryDao().getPasswordEntryById(id))
            assertEquals(TITLE, row.title)
            assertEquals(USERNAME, row.username)
            assertEquals(WEBSITE, row.website)
            assertEquals(NOTES, row.notes)
            assertNull(row.keepassDatabaseId)
            assertNull(row.mdbxDatabaseId)
            assertNull(row.bitwardenVaultId)
            assertNull(row.passwordGroupId)
            assertTrue(row.password.startsWith("MDK|"))
            assertTrue(row.authenticatorKey.startsWith("MDK|"))
            assertTrue("New MDK password must match the synthetic fixture", PASSWORD == f.security.decryptData(row.password))
            assertTrue("New MDK OTP must match the synthetic fixture", OTP == f.security.decryptData(row.authenticatorKey))
            assertTrue("New ciphertext and all columns must survive", expected.getString("newRawRow") == rawRow(f, id).toString())
            val raw = File(evidence, "original-raw-rows.json").readText()
            assertOldRowsUnchanged(f, id, expected.getString("originalMarkerSha256"), raw)
            f.verifyPayloads()
            assertOldRowsUnchanged(f, id, expected.getString("originalMarkerSha256"), raw)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("toolchainRoundTripPhase", "cold_read_verified")
                putString("toolchainRoundTripPid", Process.myPid().toString())
                putString("toolchainRoundTripVersion", f.context.packageManager.getPackageInfo(f.context.packageName, 0).versionName)
                putString("toolchainRoundTripRowId", id.toString())
            })
        } finally {
            SecurityManager.clearRuntimeUnlockCache()
            SessionManager.markLocked()
        }
    }

    private fun authorize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(Process.myUid() / 100_000 == 12)
        check(InstrumentationRegistry.getArguments().getString("toolchainRoundTripUser") == "yes")
        check(InstrumentationRegistry.getArguments().getString("isolatedRecoveryUser") == "yes")
        val expectedApk = checkNotNull(InstrumentationRegistry.getArguments().getString("toolchainTargetApkSha256"))
        check(expectedApk.matches(Regex("[0-9a-f]{64}")))
        val actualApkDigest = MessageDigest.getInstance("SHA-256")
        File(instrumentation.targetContext.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                actualApkDigest.update(buffer, 0, count)
            }
        }
        check(expectedApk == actualApkDigest.digest().joinToString("") { "%02x".format(it) }) { "Unexpected installed target APK" }
    }

    private fun assertOldRowsUnchanged(f: LegacyVaultCorpus, addedId: Long, markerDigest: String, raw: String) {
        assertEquals("Original marker must never be rewritten", markerDigest, digest(f.marker.readBytes()))
        val snapshot = f.snapshot()
        val all = snapshot.getJSONArray("password_entries")
        val retained = JSONArray()
        var removed = 0
        for (index in 0 until all.length()) {
            val row = all.getJSONObject(index)
            if (row.getLong("id") == addedId) removed++ else retained.put(row)
        }
        assertEquals("Exactly the one additive row may be excluded", 1, removed)
        snapshot.put("password_entries", retained)
        assertTrue("Every original plaintext column must survive", f.marker.readText() == snapshot.toString())
        assertTrue("Every original raw ciphertext and column must survive", raw == rawSnapshot(f, addedId).toString())
    }

    private fun rawRow(f: LegacyVaultCorpus, id: Long): JSONObject {
        val rows = rawSnapshot(f, null).getJSONArray("password_entries")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getLong("id") == id }
    }

    private fun rawSnapshot(f: LegacyVaultCorpus, excludedId: Long?): JSONObject {
        val result = JSONObject()
        for (table in listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments", "local_mdbx_databases")) {
            val rows = JSONArray()
            f.db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
                while (c.moveToNext()) {
                    if (table == "password_entries" && excludedId != null && c.getLong(c.getColumnIndexOrThrow("id")) == excludedId) continue
                    val row = JSONObject()
                    for (name in c.columnNames.sorted()) {
                        val i = c.getColumnIndexOrThrow(name)
                        val value: Any = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(c.getBlob(i))
                            else -> c.getString(i)
                        }
                        row.put(name, value)
                    }
                    rows.put(row)
                }
            }
            result.put(table, rows)
        }
        return result
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val TITLE = "Toolchain round-trip 326"
        const val USERNAME = "synthetic-roundtrip@example.invalid"
        const val WEBSITE = "https://example.invalid/toolchain-roundtrip"
        const val PASSWORD = "  Synthetic-new-326;\"\n中文🔑  "
        const val OTP = "otpauth://totp/Synthetic:roundtrip?secret=JBSWY3DPEHPK3PXP&issuer=Synthetic"
        const val NOTES = "Added by the toolchain acceptance fixture; original records are unchanged."
    }
}
