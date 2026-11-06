package takagi.ru.monica.repository

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.credentialexchange.*
import java.util.UUID

class BitwardenFolderTransferInstrumentedTest {
    @Before fun unlockSyntheticLocalVaultForHistorySync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val security = takagi.ru.monica.security.SecurityManager(instrumentation.targetContext)
        val password = InstrumentationRegistry.getArguments().getString("testMasterPassword")
            ?: "Synthetic Bitwarden settings fixture 325!"
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(password))
        check(security.unlockVaultWithPassword(password)) {
            "Existing test vault password differs; refusing to reset its data"
        }
        check(security.isVaultRuntimeUnlocked()) { "Synthetic fixture MDK was not loaded" }
        instrumentation.runOnMainSync { takagi.ru.monica.security.SessionManager.markUnlocked() }
        val probe = security.encryptData("synthetic-history-session")
        check(probe.startsWith("MDK|") && security.decryptData(probe) == "synthetic-history-session") {
            "Synthetic history fixture requires authenticated MDK encryption"
        }
    }

    /** Explicit recovery only: never sweep pre-existing fixture databases on the shared AVD. */
    @Before fun recoverExplicitlyOwnedFailedFixtures() = runBlocking {
        val prefixes = InstrumentationRegistry.getArguments().getString("folderFixtureCleanupPrefixes")
            ?.split(',')?.filter { it.isNotBlank() } ?: return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
        val bw = BitwardenRepository.getInstance(context)
        for (prefix in prefixes) {
            require(prefix.matches(Regex("credential-transfer-[0-9a-f-]{36}")))
            val vault = bw.getAllVaults().singleOrNull { it.displayName == "$prefix-Bitwarden" } ?: continue
            check(vault.accountKey.startsWith("$prefix-"))
            check(android.net.Uri.parse(vault.apiUrl).host in setOf("localhost", "127.0.0.1"))
            check((db.passwordEntryDao().getAllPasswordEntriesSync() + db.passwordEntryDao().getDeletedEntriesSync()).none { it.bitwardenVaultId == vault.id })
            db.categoryDao().getAllCategories().first().filter { it.bitwardenVaultId == vault.id }.forEach { db.categoryDao().delete(it) }
            db.bitwardenFolderDao().deleteByVault(vault.id)
            db.bitwardenVaultDao().deleteById(vault.id)
            val directory = java.io.File(context.filesDir, prefix).canonicalFile
            check(directory.parentFile == context.filesDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test fun unsyncedSourceCannotCreateAStaleServerCopy() = runBlocking {
        val f = TransferFixture()
        try {
            val vault = f.bitwarden()
            val bw = BitwardenRepository.getInstance(f.context)
            val work = bw.createFolder(vault.databaseId, "Work").getOrThrow()
            val id = f.db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                title = f.prefix, website = "", username = "new edit", password = f.security.encryptData("new secret"),
                bitwardenVaultId = vault.databaseId, bitwardenFolderId = work.bitwardenFolderId, bitwardenLocalModified = true))
            val service = ExternalFolderRepository(f.context)
            val target = ExternalFolderTarget.Bitwarden(vault.databaseId, work.bitwardenFolderId, "Work")
            assertTrue(runCatching { service.transfer(target, DatabaseManagerLocation(vault), true) }.isFailure)
            assertEquals(1, f.remote.folders.size)
            assertTrue(f.remote.created.isEmpty())
            assertEquals("new edit", f.db.passwordEntryDao().getPasswordEntryById(id)!!.username)
        } finally { f.close() }
    }

    @Test fun sameVaultCopyPreservesCiphertextAndMoveKeepsMembership() = runBlocking {
        val f = TransferFixture()
        try {
            val vault = f.bitwarden()
            val bw = BitwardenRepository.getInstance(f.context)
            val service = ExternalFolderRepository(f.context)
            val work = bw.createFolder(vault.databaseId, "Work").getOrThrow()
            val child = bw.createFolder(vault.databaseId, "Work/Child").getOrThrow()
            val parent = bw.createFolder(vault.databaseId, "Personal").getOrThrow()
            val secret = BitwardenCrypto.encryptString("synthetic password", f.vaultKey)
            val source = JSONObject().put("id", UUID.randomUUID().toString()).put("folderId", child.bitwardenFolderId)
                .put("type", 1).put("name", BitwardenCrypto.encryptString("Synthetic", f.vaultKey))
                .put("login", JSONObject().put("username", BitwardenCrypto.encryptString("user", f.vaultKey)).put("password", secret))
                .put("futureEncryptedField", JSONObject().put("blob", "opaque-encrypted-value"))
                .put("revisionDate", "2026-10-21T00:00:00.000Z")
            synchronized(f.remote.created) { f.remote.created += source }
            val target = ExternalFolderTarget.Bitwarden(vault.databaseId, work.bitwardenFolderId, "Work")
            service.transfer(target, DatabaseManagerLocation(vault), true)
            val refreshedVault = f.db.bitwardenVaultDao().getVaultById(vault.databaseId)!!
            assertEquals("synthetic-transfer-${refreshedVault.userId}", refreshedVault.revisionDate)
            val copied = synchronized(f.remote.created) { f.remote.created.single { it.getString("id") != source.getString("id") } }
            assertEquals(secret, copied.getJSONObject("login").getString("password"))
            assertEquals("opaque-encrypted-value", copied.getJSONObject("futureEncryptedField").getString("blob"))
            assertNotEquals(child.bitwardenFolderId, copied.getString("folderId"))
            val originalDocuments = f.remote.created.map { it.toString() }
            service.transfer(target, DatabaseManagerLocation(vault, parent.bitwardenFolderId), false)
            assertEquals(originalDocuments, f.remote.created.map { it.toString() })
            val folders = bw.getFolders(vault.databaseId)
            assertEquals("Personal/Work", folders.single { it.bitwardenFolderId == work.bitwardenFolderId }.name)
            assertEquals("Personal/Work/Child", folders.single { it.bitwardenFolderId == child.bitwardenFolderId }.name)
        } finally { f.close() }
    }

    @Test fun failedFolderMoveRollsBackAndUnsupportedCopyCreatesNothing() = runBlocking {
        val f = TransferFixture()
        try {
            val vault = f.bitwarden()
            val bw = BitwardenRepository.getInstance(f.context)
            val service = ExternalFolderRepository(f.context)
            val work = bw.createFolder(vault.databaseId, "Work").getOrThrow()
            val child = bw.createFolder(vault.databaseId, "Work/Child").getOrThrow()
            val parent = bw.createFolder(vault.databaseId, "Personal").getOrThrow()
            val target = ExternalFolderTarget.Bitwarden(vault.databaseId, work.bitwardenFolderId, "Work")
            f.remote.rejectFolderRenameId = child.bitwardenFolderId
            assertTrue(runCatching { service.transfer(target, DatabaseManagerLocation(vault, parent.bitwardenFolderId), false) }.isFailure)
            assertEquals("Work", bw.getFolders(vault.databaseId).single { it.bitwardenFolderId == work.bitwardenFolderId }.name)
            assertEquals("Work/Child", bw.getFolders(vault.databaseId).single { it.bitwardenFolderId == child.bitwardenFolderId }.name)
            f.remote.rejectFolderRenameId = null
            val cipher = JSONObject().put("id", UUID.randomUUID().toString()).put("folderId", work.bitwardenFolderId)
                .put("type", 1).put("name", BitwardenCrypto.encryptString("Synthetic", f.vaultKey))
                .put("attachments", JSONArray().put(JSONObject().put("id", "synthetic-attachment")))
            synchronized(f.remote.created) { f.remote.created += cipher }
            assertTrue(runCatching { service.transfer(target, DatabaseManagerLocation(vault), true) }.isFailure)
            assertEquals(3, f.remote.folders.size)
            assertEquals(1, f.remote.created.size)
            assertEquals(work.bitwardenFolderId, f.remote.created.single().getString("folderId"))
        } finally { f.close() }
    }
}
