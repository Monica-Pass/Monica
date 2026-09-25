package takagi.ru.monica.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class Mdbx1RetirementInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun retiredVaultIsKeptForUpgradeButExcludedFromNormalListsAndCredentials() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val dao = room.localMdbxDatabaseDao()
            val legacy = dao.insertDatabase(LocalMdbxDatabase(name = "Retired fixture", filePath = "retired.mdbx", isDefault = true))
            val modern = dao.insertDatabase(LocalMdbxDatabase(name = "Modern fixture", filePath = "modern.mdbx", engineType = MdbxEngineType.RUST_MDBX2.name))
            val passwordIds = listOf(null, legacy, modern).map { id ->
                room.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                    title = "Fixture $id", website = "fixture.test", username = "fixture", password = "fixture", mdbxDatabaseId = id))
            }
            listOf(null, legacy, modern).forEach { id ->
                room.secureItemDao().insertItem(SecureItem(
                    title = "Fixture $id", itemType = ItemType.NOTE, itemData = "fixture", mdbxDatabaseId = id))
                room.passkeyDao().insert(PasskeyEntry(
                    credentialId = "fixture-$id", rpId = "fixture.test", rpName = "Fixture", userId = "fixture",
                    userName = "fixture", userDisplayName = "Fixture", publicKey = "fixture", privateKeyAlias = "fixture",
                    mdbxDatabaseId = id))
            }
            assertEquals(setOf(legacy, modern), dao.getAllDatabasesSnapshot().map { it.id }.toSet())
            assertEquals(listOf(modern), dao.getAvailableDatabases().first().map { it.id })
            assertNull(dao.getDefaultDatabase())
            assertEquals(setOf(passwordIds[0], passwordIds[2]), room.passwordEntryDao().getActiveEntries().first().map { it.id }.toSet())
            assertEquals(2, room.secureItemDao().getAllItems().first().size)
            assertEquals(2, room.passkeyDao().getDiscoverablePasskeysByRpId("fixture.test").size)
            assertNull(room.passkeyDao().getPasskeyById("fixture-$legacy"))
            // Cached content is still available to deliberate recovery/upgrade code.
            assertEquals(1, room.passwordEntryDao().getByMdbxDatabaseIdSync(legacy).size)
            assertEquals(1, room.secureItemDao().getByMdbxDatabaseIdSync(legacy).size)
            assertEquals(1, room.passkeyDao().getByMdbxDatabaseId(legacy).size)
        } finally {
            room.close()
        }
    }

    @Test
    fun retiredPasswordsDoNotPreventNewAutofillSavesAsDuplicateMatches() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val legacy = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Retired", filePath = "retired.mdbx"))
            val passwords = room.passwordEntryDao()
            passwords.insertPasswordEntry(PasswordEntry(title = "retired", website = "retired.example", username = "legacy-only",
                password = "fixture", appPackageName = "retired.fixture", mdbxDatabaseId = legacy))
            assertNull(passwords.findDuplicateEntry("retired", "legacy-only", "retired.example"))
            assertTrue(passwords.findActiveDuplicateCandidatesByKey("retired", "legacy-only", "retired.example").isEmpty())
            assertNull(passwords.findByPackageAndUsername("retired.fixture", "legacy-only"))
            assertNull(passwords.findByDomainAndUsername("retired.example", "legacy-only"))
            assertNull(passwords.findExactMatch("retired.fixture", "legacy-only", "fixture"))
            assertEquals(0, passwords.countByTitleUsernameWebsite("retired", "legacy-only", "retired.example"))
            room.secureItemDao().insertItem(SecureItem(title = "retired-note", itemType = ItemType.NOTE,
                itemData = "fixture", mdbxDatabaseId = legacy))
            assertNull(room.secureItemDao().findDuplicateItem(ItemType.NOTE, "retired-note"))
            assertEquals(1, passwords.getByMdbxDatabaseIdSync(legacy).size)
        } finally {
            room.close()
        }
    }

    @Test
    fun staleLegacyWriteRollsBackTheRoomRowAndOrdinaryReadsAreRejected() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val legacy = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Retired", filePath = "must-not-open.mdbx"))
            val router = MdbxRepositoryFactory.create(context, room, SecurityManager(context))
            val repository = PasswordRepository(room.passwordEntryDao(), mdbxRepository = router)
            val error = runCatching {
                repository.insertPasswordEntry(PasswordEntry(title = "Must not persist", website = "", username = "", password = "", mdbxDatabaseId = legacy))
            }.exceptionOrNull()
            assertTrue(error.toString(), error is MdbxLegacyUnavailableException)
            assertTrue(router.requiresStrictMutationConsistency(legacy))
            assertTrue(room.passwordEntryDao().getByMdbxDatabaseIdSync(legacy).isEmpty())
            assertTrue(runCatching { router.readStoredEntries(legacy) }.exceptionOrNull() is MdbxLegacyUnavailableException)
        } finally {
            room.close()
        }
    }

    @Test
    fun allCreationEntryPointsRejectLegacyBeforeCreatingOrConnecting() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val viewModel = MdbxViewModel(context.applicationContext as Application,
            room.localMdbxDatabaseDao(), room.mdbxRemoteSourceDao(), room.passwordEntryDao(), room.secureItemDao(),
            room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), SecurityManager(context))
        try {
            viewModel.createLocalVault("Retired", "fixture", MdbxUnlockMethod.MASTER_PASSWORD, null, MdbxTigaMode.SKY, null,
                engineType = MdbxEngineType.KOTLIN_MDBX1)
            assertTrue(viewModel.operationState.value is MdbxViewModel.OperationState.Error)
            viewModel.clearOperationState()
            viewModel.createWebDavVault("Retired", "fixture", MdbxUnlockMethod.MASTER_PASSWORD, null, MdbxTigaMode.SKY,
                "https://must-not-connect.invalid", "fixture", "fixture", null, null, MdbxEngineType.KOTLIN_MDBX1)
            assertTrue(viewModel.operationState.value is MdbxViewModel.OperationState.Error)
            viewModel.clearOperationState()
            viewModel.createOneDriveVault("Retired", "fixture", MdbxUnlockMethod.MASTER_PASSWORD, null, MdbxTigaMode.SKY,
                "must-not-connect", "fixture", null, null, MdbxEngineType.KOTLIN_MDBX1)
            assertTrue(viewModel.operationState.value is MdbxViewModel.OperationState.Error)
            assertTrue(room.localMdbxDatabaseDao().getAllDatabasesSnapshot().isEmpty())
        } finally {
            viewModel.viewModelScope.cancel()
            room.close()
        }
    }
}
