package takagi.ru.monica.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class Mdbx2CreationInstrumentedTest {
    @Test
    fun defaultEngineCreatesRoutedMdbx2Vault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val name = "MDBX2 creation ${UUID.randomUUID()}"
        val password = "mdbx2-creation-password"
        val viewModel = MdbxViewModel(
            application = application,
            databaseDao = databaseDao,
            remoteSourceDao = room.mdbxRemoteSourceDao(),
            passwordEntryDao = room.passwordEntryDao(),
            secureItemDao = room.secureItemDao(),
            passkeyDao = room.passkeyDao(),
            attachmentDao = room.attachmentDao(),
            customFieldDao = room.customFieldDao(),
            securityManager = securityManager
        )
        var databaseId = 0L
        var passwordEntryId = 0L
        var vaultFile: File? = null
        try {
            viewModel.createLocalVault(
                name = name,
                masterPassword = password,
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
                keyFile = null,
                tigaMode = MdbxTigaMode.SKY,
                description = "default creation test"
            )
            val operation = withTimeout(20_000) {
                viewModel.operationState.first {
                    it is MdbxViewModel.OperationState.Success ||
                        it is MdbxViewModel.OperationState.Error
                }
            }
            assertTrue(operation.toString(), operation is MdbxViewModel.OperationState.Success)

            val database = databaseDao.getAllDatabasesSnapshot().single { it.name == name }
            databaseId = database.id
            vaultFile = File(database.workingCopyPath!!)
            assertEquals(MdbxEngineType.RUST_MDBX2, database.engineTypeEnum)
            assertEquals(MdbxStorageLocation.INTERNAL, database.storageLocationEnum)
            assertEquals(MdbxSourceType.LOCAL_INTERNAL, database.sourceTypeEnum)
            assertEquals(MdbxSyncStatus.LOCAL_ONLY.name, database.lastSyncStatus)
            assertEquals("argon2id-mdbx2", database.kdfProfile)
            assertTrue(vaultFile.isFile)

            val passwordRepository = PasswordRepository(
                passwordEntryDao = room.passwordEntryDao(),
                mdbxRepository = MdbxRepositoryFactory.create(context, room, securityManager)
            )
            passwordEntryId = passwordRepository.insertPasswordEntry(
                PasswordEntry(
                    title = "MDBX2 searchable creation marker",
                    website = "https://example.com",
                    username = "alice",
                    password = "secret",
                    mdbxDatabaseId = databaseId
                )
            )
            val inserted = room.passwordEntryDao().getPasswordEntryById(passwordEntryId)!!
            assertEquals("password:$passwordEntryId", inserted.replicaGroupId)
            assertEquals(
                passwordEntryId,
                passwordRepository.searchPasswordEntries("searchable creation marker")
                    .first()
                    .single { it.mdbxDatabaseId == databaseId }
                    .id
            )

            val reopenedPasswordRepository = PasswordRepository(
                passwordEntryDao = room.passwordEntryDao(),
                mdbxRepository = MdbxRepositoryFactory.create(context, room, securityManager)
            )
            val updated = inserted.copy(
                title = "MDBX2 searchable restart marker",
                username = "bob"
            )
            reopenedPasswordRepository.updatePasswordEntry(updated)
            assertEquals(
                passwordEntryId,
                reopenedPasswordRepository.searchPasswordEntries("restart marker")
                    .first()
                    .single { it.mdbxDatabaseId == databaseId }
                    .id
            )
            val reopenedVaultEntries = MdbxRepositoryFactory.create(context, room, securityManager)
                .readStoredEntries(databaseId)
            assertEquals("MDBX2 searchable restart marker", reopenedVaultEntries.single { !it.deleted }.title)
            assertEquals("bob", JSONObject(reopenedVaultEntries.single { !it.deleted }.payloadJson).getString("username"))

            reopenedPasswordRepository.deletePasswordEntry(updated)
            assertTrue(
                reopenedPasswordRepository.searchPasswordEntries("restart marker")
                    .first()
                    .none { it.id == passwordEntryId }
            )
            assertTrue(
                MdbxRepositoryFactory.create(context, room, securityManager)
                    .readStoredEntries(databaseId)
                    .single { it.entryId == "password:$passwordEntryId" }
                    .deleted
            )
            passwordEntryId = 0L
        } finally {
            if (passwordEntryId > 0L) room.passwordEntryDao().deletePasswordEntryById(passwordEntryId)
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            vaultFile?.delete()
        }
    }

    @Test
    fun explicitLegacyEngineIsRejectedWithoutCreatingADatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val name = "MDBX1 compatibility ${UUID.randomUUID()}"
        val viewModel = MdbxViewModel(
            application = application,
            databaseDao = databaseDao,
            remoteSourceDao = room.mdbxRemoteSourceDao(),
            passwordEntryDao = room.passwordEntryDao(),
            secureItemDao = room.secureItemDao(),
            passkeyDao = room.passkeyDao(),
            attachmentDao = room.attachmentDao(),
            customFieldDao = room.customFieldDao(),
            securityManager = securityManager
        )
        val existingIds = databaseDao.getAllDatabasesSnapshot().map { it.id }.toSet()
        try {
            viewModel.createLocalVault(
                name = name,
                masterPassword = "mdbx1-compatibility-password",
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
                keyFile = null,
                tigaMode = MdbxTigaMode.MULTI,
                description = "legacy compatibility test",
                engineType = MdbxEngineType.KOTLIN_MDBX1
            )
            val operation = withTimeout(20_000) {
                viewModel.operationState.first {
                    it is MdbxViewModel.OperationState.Success ||
                        it is MdbxViewModel.OperationState.Error
                }
            }
            assertTrue(operation.toString(), operation is MdbxViewModel.OperationState.Error)
            assertEquals(existingIds, databaseDao.getAllDatabasesSnapshot().map { it.id }.toSet())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
