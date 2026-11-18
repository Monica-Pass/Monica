package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File
import java.security.MessageDigest

/** Run seed, upgrade and cold-read in separate instrumented processes of one isolated package. */
class LocalCredentialCreateColdStartTest {
    private fun requirePhase(phase: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localCreatePhase") == phase)
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val proofFile get() = File(context.filesDir, "local-create-cold-329.json")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun fixture(block: suspend (PasswordDatabase, PasswordViewModel, SecurityManager) -> Unit) {
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        val security = SecurityManager(context)
        val wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val db = Room.databaseBuilder(context, PasswordDatabase::class.java, "synthetic-local-create-329.db").build()
        val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
        try { block(db, model, security) } finally {
            model.viewModelScope.cancel(); db.close()
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }
    private suspend fun save(model: PasswordViewModel, title: String, passwords: List<String>): Long {
        val done = CompletableDeferred<Long?>()
        model.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = title, username = "synthetic-user", password = "", website = "https://example.invalid"),
            passwords, listOf(StorageTarget.MonicaLocal(null)), listOf(CustomFieldDraft(title = "Unknown retained field", value = "kept", isProtected = true)),
            onComplete = { done.complete(it) })
        return requireNotNull(withTimeout(30_000) { done.await() })
    }
    private suspend fun verifyOld(db: PasswordDatabase, security: SecurityManager, proof: JSONObject) {
        val rows = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title == "Synthetic pre-fix project" }.sortedBy { it.id }
        assertEquals(2, rows.size)
        assertEquals(listOf(proof.getLong("firstId"), proof.getLong("secondId")), rows.map { it.id })
        assertEquals(proof.getString("ciphertextHash"), digest(rows.joinToString("\n") { it.password }.toByteArray()))
        assertEquals(listOf("synthetic-old-1", "synthetic-old-2"), rows.map { security.decryptData(it.password) })
        assertEquals("kept", db.customFieldDao().getFieldsByEntryIdSync(rows.first().id).single().value)
        assertTrue(db.customFieldDao().getFieldsByEntryIdSync(rows.first().id).single().isProtected)
    }
    @Test fun seedBeforeFix() = runBlocking { requirePhase("seed"); fixture { db, model, _ ->
        assertFalse("Only a fresh isolated fixture may be seeded", proofFile.exists())
        assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
        save(model, "Synthetic pre-fix project", listOf("synthetic-old-1", "synthetic-old-2"))
        val rows = db.passwordEntryDao().getAllPasswordEntriesSync().sortedBy { it.id }
        assertEquals(2, rows.size)
        proofFile.writeText(JSONObject().put("firstId", rows[0].id).put("secondId", rows[1].id)
            .put("ciphertextHash", digest(rows.joinToString("\n") { it.password }.toByteArray()))
            .put("seedPid", android.os.Process.myPid()).toString(2))
    } }
    @Test fun readPreFixDataAndWriteAfterUpgrade() = runBlocking { requirePhase("upgrade"); fixture { db, model, security ->
        val proof = JSONObject(proofFile.readText())
        assertNotEquals(proof.getInt("seedPid"), android.os.Process.myPid())
        verifyOld(db, security, proof)
        assertEquals(2, db.passwordEntryDao().getAllPasswordEntriesSync().size)
        val id = save(model, "Synthetic post-fix project", listOf("synthetic-new"))
        verifyOld(db, security, proof)
        assertEquals(3, db.passwordEntryDao().getAllPasswordEntriesSync().size)
        proofFile.writeText(proof.put("newId", id).put("upgradePid", android.os.Process.myPid()).toString(2))
    } }
    @Test fun coldReadPreservesOldAndNewRecords() = runBlocking { requirePhase("cold"); fixture { db, _, security ->
        val proof = JSONObject(proofFile.readText())
        assertNotEquals(proof.getInt("upgradePid"), android.os.Process.myPid())
        verifyOld(db, security, proof)
        val rows = db.passwordEntryDao().getAllPasswordEntriesSync()
        assertEquals(3, rows.size)
        val new = rows.single { it.id == proof.getLong("newId") }
        assertEquals("synthetic-new", security.decryptData(new.password))
        assertEquals("kept", db.customFieldDao().getFieldsByEntryIdSync(new.id).single().value)
        proofFile.writeText(proof.put("coldPid", android.os.Process.myPid()).put("rows", rows.size).put("oldCiphertextUnchanged", true).toString(2))
    } }
}
