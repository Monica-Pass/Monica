package takagi.ru.monica.repository

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class Mdbx2HardeningInstrumentedTest {
    @Test
    fun openFailuresAreStableAndSanitized() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val correctPassword = "correct-${UUID.randomUUID()}"
        val wrongPassword = "wrong-${UUID.randomUUID()}"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, correctPassword)
        val missingFile = File(vaultFile.parentFile, "missing-${UUID.randomUUID()}.mdbx")
        val corruptFile = File(vaultFile.parentFile, "corrupt-${UUID.randomUUID()}.mdbx")
            .apply { writeBytes(ByteArray(512) { index -> (index * 31).toByte() }) }
        val databaseIds = mutableListOf<Long>()
        try {
            val wrongPasswordId = insertDatabase(
                room = room,
                file = vaultFile,
                encryptedPassword = securityManager.encryptData(wrongPassword)
            ).also(databaseIds::add)
            val wrongPasswordFailure = expectMdbx2Failure {
                repository.readStoredEntries(wrongPasswordId)
            }
            assertEquals(Mdbx2FailureKind.INVALID_CREDENTIAL, wrongPasswordFailure.kind)
            assertSanitized(wrongPasswordFailure, vaultFile.absolutePath, correctPassword, wrongPassword)

            val missingFileId = insertDatabase(
                room = room,
                file = missingFile,
                encryptedPassword = securityManager.encryptData(correctPassword)
            ).also(databaseIds::add)
            val missingFailure = expectMdbx2Failure {
                repository.readStoredEntries(missingFileId)
            }
            assertEquals(Mdbx2FailureKind.FILE_MISSING, missingFailure.kind)
            assertSanitized(missingFailure, missingFile.absolutePath, correctPassword)

            val corruptFileId = insertDatabase(
                room = room,
                file = corruptFile,
                encryptedPassword = securityManager.encryptData(correctPassword)
            ).also(databaseIds::add)
            val corruptFailure = expectMdbx2Failure {
                repository.readStoredEntries(corruptFileId)
            }
            assertEquals(Mdbx2FailureKind.CORRUPT_VAULT, corruptFailure.kind)
            assertSanitized(corruptFailure, corruptFile.absolutePath, correctPassword)
        } finally {
            databaseIds.forEach { databaseDao.deleteDatabaseById(it) }
            repository.deleteOwnedVaultFile(vaultFile)
            repository.deleteOwnedVaultFile(corruptFile)
        }
    }

    @Test
    fun deleteRemovesRoomMirrorsAttachmentBlobAndOwnedVaultFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "delete-${UUID.randomUUID()}"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val databaseId = insertDatabase(
            room = room,
            file = vaultFile,
            encryptedPassword = securityManager.encryptData(password)
        )
        val passwordEntryId = room.passwordEntryDao().insertPasswordEntry(
            PasswordEntry(
                title = "MDBX2 deletion marker",
                website = "https://deletion.test",
                username = "deletion-test",
                password = "test-only",
                mdbxDatabaseId = databaseId
            )
        )
        val attachmentStorage = AttachmentStorage(context)
        val encrypted = attachmentStorage.writeEncrypted("delete attachment".byteInputStream())
        val wrappedCek = try {
            AttachmentKeyVault(securityManager).wrap(encrypted.cek)
        } finally {
            encrypted.cek.fill(0)
        }
        val attachmentId = room.attachmentDao().insert(
            Attachment(
                parentPasswordId = passwordEntryId,
                source = AttachmentSource.LOCAL.name,
                fileName = "delete.txt",
                mimeType = "text/plain",
                sizeBytes = encrypted.sizeBytes,
                sha256Hex = encrypted.sha256Hex,
                wrappedCek = wrappedCek,
                localPath = encrypted.relativePath,
                downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        val viewModel = createViewModel(application, room, securityManager)
        try {
            viewModel.clearOperationState()
            viewModel.syncVault(databaseId)
            val sync = awaitTerminalOperation(viewModel)
            assertTrue(sync.toString(), sync is MdbxViewModel.OperationState.Success)

            viewModel.clearOperationState()
            viewModel.deleteVault(databaseId)
            val deletion = awaitTerminalOperation(viewModel)
            assertTrue(deletion.toString(), deletion is MdbxViewModel.OperationState.Success)
            assertNull(databaseDao.getDatabaseById(databaseId))
            assertNull(room.passwordEntryDao().getPasswordEntryById(passwordEntryId))
            assertNull(room.attachmentDao().getById(attachmentId))
            assertFalse(attachmentStorage.exists(encrypted.relativePath))
            assertFalse(vaultFile.exists())
        } finally {
            room.attachmentDao().deleteById(attachmentId)
            room.passwordEntryDao().deletePasswordEntryById(passwordEntryId)
            databaseDao.deleteDatabaseById(databaseId)
            attachmentStorage.delete(encrypted.relativePath)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }

    @Test
    fun ownedFileDeletionRefusesFilesOutsideMdbx2Directory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val repository = Mdbx2Repository(
            context,
            room.localMdbxDatabaseDao(),
            SecurityManager(context)
        )
        val outsideFile = File(context.filesDir, "outside-${UUID.randomUUID()}.mdbx")
            .apply { writeText("must remain") }
        try {
            assertFalse(repository.deleteOwnedVaultFile(outsideFile))
            assertTrue(outsideFile.isFile)
        } finally {
            outsideFile.delete()
        }
    }

    @Test
    fun ownedFileDeletionRemovesSqliteAndBlobStoreSidecars() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val repository = Mdbx2Repository(
            context,
            room.localMdbxDatabaseDao(),
            SecurityManager(context)
        )
        val vaultFile = repository.createInitializedVaultFile(
            MdbxTigaMode.SKY,
            "sidecar-cleanup-${UUID.randomUUID()}"
        )
        val wal = File("${vaultFile.absolutePath}-wal").apply { writeText("stale wal") }
        val shm = File("${vaultFile.absolutePath}-shm").apply { writeText("stale shm") }
        val blobSidecars = listOf("blobs", "leases", "transfers").map { suffix ->
            File("${vaultFile.absolutePath}.$suffix").apply {
                mkdirs()
                File(this, "stale").writeText("stale $suffix")
            }
        }
        try {
            assertTrue(vaultFile.isFile)
            assertTrue(wal.isFile)
            assertTrue(shm.isFile)
            assertTrue(blobSidecars.all(File::isDirectory))
            assertTrue(repository.deleteOwnedVaultFile(vaultFile))
            assertFalse(vaultFile.exists())
            assertFalse(wal.exists())
            assertFalse(shm.exists())
            assertTrue(blobSidecars.none(File::exists))
        } finally {
            vaultFile.delete()
            wal.delete()
            shm.delete()
            blobSidecars.forEach(File::deleteRecursively)
        }
    }

    @Test
    fun missingVaultRollsBackRoomMutationsAndAttachmentBlob() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val securityManager = SecurityManager(context)
        val mdbxRepository = MdbxRepositoryFactory.create(context, room, securityManager)
        val rawRepository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), securityManager)
        val vaultPassword = "rollback-${UUID.randomUUID()}"
        val vaultFile = rawRepository.createInitializedVaultFile(MdbxTigaMode.SKY, vaultPassword)
        val parkedFile = File(vaultFile.parentFile, "${vaultFile.name}.parked")
        val databaseId = insertDatabase(
            room = room,
            file = vaultFile,
            encryptedPassword = securityManager.encryptData(vaultPassword)
        )
        val passwordRepository = PasswordRepository(
            passwordEntryDao = room.passwordEntryDao(),
            mdbxRepository = mdbxRepository
        )
        val secureItemRepository = SecureItemRepository(
            secureItemDao = room.secureItemDao(),
            mdbxRepository = mdbxRepository
        )
        val passkeyRepository = PasskeyRepository(
            passkeyDao = room.passkeyDao(),
            mdbxRepository = mdbxRepository,
            context = context
        )
        val passwordId = passwordRepository.insertPasswordEntry(
            PasswordEntry(
                title = "MDBX2 rollback original",
                website = "https://rollback.test",
                username = "rollback-user",
                password = "rollback-secret",
                mdbxDatabaseId = databaseId
            )
        )
        val sourceFile = File(context.cacheDir, "mdbx2-attachment-${UUID.randomUUID()}.txt")
            .apply { writeText("attachment rollback") }
        try {
            assertTrue(vaultFile.renameTo(parkedFile))
            val original = room.passwordEntryDao().getPasswordEntryById(passwordId)!!

            expectAnyFailure {
                passwordRepository.updatePasswordEntry(original.copy(title = "must not persist"))
            }
            assertEquals(
                "MDBX2 rollback original",
                room.passwordEntryDao().getPasswordEntryById(passwordId)?.title
            )

            expectAnyFailure { passwordRepository.deletePasswordEntry(original) }
            assertEquals(passwordId, room.passwordEntryDao().getPasswordEntryById(passwordId)?.id)

            val secureMarker = "secure-${UUID.randomUUID()}"
            expectAnyFailure {
                secureItemRepository.insertItem(
                    SecureItem(
                        itemType = ItemType.NOTE,
                        title = secureMarker,
                        itemData = "test-only",
                        mdbxDatabaseId = databaseId
                    )
                )
            }
            assertTrue(
                room.secureItemDao().getActiveItemsByTypeSync(ItemType.NOTE)
                    .none { it.title == secureMarker }
            )

            val credentialId = "credential-${UUID.randomUUID()}"
            expectAnyFailure {
                passkeyRepository.savePasskey(
                    PasskeyEntry(
                        credentialId = credentialId,
                        rpId = "rollback.test",
                        rpName = "Rollback",
                        userId = "dGVzdA",
                        userName = "rollback@test",
                        userDisplayName = "Rollback",
                        publicKey = "test-public-key",
                        privateKeyAlias = "test-private-key",
                        mdbxDatabaseId = databaseId
                    )
                )
            }
            assertNull(room.passkeyDao().getPasskeyById(credentialId))

            val storage = AttachmentStorage(context)
            val blobsBefore = storage.listAllBlobs().toSet()
            expectAnyFailure {
                AttachmentContainer.facade(context).addAttachment(
                    AttachmentFacade.UploadRequest(
                        owner = AttachmentOwner.password(passwordId),
                        source = AttachmentSource.LOCAL,
                        uri = Uri.fromFile(sourceFile),
                        isPlusActivated = true
                    )
                )
            }
            assertTrue(room.attachmentDao().getAllByParent(passwordId).isEmpty())
            assertEquals(blobsBefore, storage.listAllBlobs().toSet())
        } finally {
            if (parkedFile.exists()) parkedFile.renameTo(vaultFile)
            runCatching {
                room.passwordEntryDao().getPasswordEntryById(passwordId)?.let {
                    passwordRepository.deletePasswordEntry(it)
                }
            }
            room.passwordEntryDao().deletePasswordEntryById(passwordId)
            room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            sourceFile.delete()
            rawRepository.deleteOwnedVaultFile(vaultFile)
            parkedFile.delete()
        }
    }

    private suspend fun insertDatabase(
        room: PasswordDatabase,
        file: File,
        encryptedPassword: String
    ): Long = room.localMdbxDatabaseDao().insertDatabase(
        LocalMdbxDatabase(
            name = "MDBX2 hardening ${UUID.randomUUID()}",
            filePath = file.absolutePath,
            storageLocation = MdbxStorageLocation.INTERNAL.name,
            sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            tigaMode = MdbxTigaMode.SKY.name,
            encryptedPassword = encryptedPassword,
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
            kdfProfile = "argon2id-mdbx2",
            workingCopyPath = file.absolutePath,
            cacheCopyPath = file.absolutePath,
            isOfflineAvailable = true,
            lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
        )
    )

    private fun createViewModel(
        application: Application,
        room: PasswordDatabase,
        securityManager: SecurityManager
    ) = MdbxViewModel(
        application = application,
        databaseDao = room.localMdbxDatabaseDao(),
        remoteSourceDao = room.mdbxRemoteSourceDao(),
        passwordEntryDao = room.passwordEntryDao(),
        secureItemDao = room.secureItemDao(),
        passkeyDao = room.passkeyDao(),
        attachmentDao = room.attachmentDao(),
        customFieldDao = room.customFieldDao(),
        securityManager = securityManager
    )

    private suspend fun awaitTerminalOperation(viewModel: MdbxViewModel) = withTimeout(30_000) {
        viewModel.operationState.first { state ->
            state is MdbxViewModel.OperationState.Success || state is MdbxViewModel.OperationState.Error
        }
    }

    private suspend fun expectMdbx2Failure(block: suspend () -> Unit): Mdbx2OperationException {
        try {
            block()
            throw AssertionError("Expected MDBX2 operation to fail")
        } catch (error: Mdbx2OperationException) {
            return error
        }
    }

    private suspend fun expectAnyFailure(block: suspend () -> Unit): Throwable {
        return runCatching { block() }.exceptionOrNull()
            ?: throw AssertionError("Expected operation to fail")
    }

    private fun assertSanitized(error: Mdbx2OperationException, vararg secrets: String) {
        secrets.forEach { secret ->
            assertFalse(error.message.orEmpty(), error.message.orEmpty().contains(secret))
        }
        assertFalse(error.message.orEmpty().contains("native detail", ignoreCase = true))
    }
}
