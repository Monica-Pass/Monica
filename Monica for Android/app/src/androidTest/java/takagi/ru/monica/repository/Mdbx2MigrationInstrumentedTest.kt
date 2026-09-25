package takagi.ru.monica.repository

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class Mdbx2MigrationInstrumentedTest {
    @Test
    fun viewModelMigratesLocalMdbx1WithoutChangingSource() = migrateFixture(MdbxSourceType.LOCAL_INTERNAL)

    @Test
    fun viewModelMigratesRemoteCopyWithoutUploadingOrChangingSource() = migrateFixture(MdbxSourceType.REMOTE_WEBDAV)

    private fun migrateFixture(sourceType: MdbxSourceType) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val viewModel = MdbxViewModel(
            application,
            databaseDao,
            room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(),
            room.secureItemDao(),
            room.passkeyDao(),
            room.attachmentDao(),
            room.customFieldDao(),
            securityManager
        )
        val sourceName = "MDBX1 migration source ${UUID.randomUUID()}"
        val targetName = "MDBX2 migration target ${UUID.randomUUID()}"
        val sourcePassword = "source-migration-password"
        val targetPassword = "target-migration-password"
        var sourceDatabaseId = 0L
        var targetDatabaseId = 0L
        var sourceFile: File? = null
        var targetFile: File? = null
        var localAttachmentPath: String? = null
        try {
            val repository = legacyStore(context, room, securityManager)
            val sourceDatabase = createLegacyFixture(repository, room, securityManager, sourceName, sourcePassword)
            sourceDatabaseId = sourceDatabase.id
            sourceFile = File(sourceDatabase.workingCopyPath!!)
            val folder = repository.createFolder(sourceDatabaseId, "Work", null)
            val nestedFolder = repository.createFolder(sourceDatabaseId, "Servers", folder.folderId)
            val passwordId = room.passwordEntryDao().insertPasswordEntry(
                PasswordEntry(
                    title = "Migration login",
                    website = "https://migration.test",
                    username = "migration-user",
                    password = "migration-secret",
                    notes = "portable note",
                    mdbxDatabaseId = sourceDatabaseId,
                    mdbxFolderId = nestedFolder.folderId
                )
            )
            val passwordEntry = room.passwordEntryDao().getPasswordEntryById(passwordId)!!
            repository.upsertPassword(passwordEntry)

            val secureItemId = room.secureItemDao().insertItem(
                SecureItem(
                    itemType = ItemType.TOTP,
                    title = "Migration OTP",
                    itemData = """{"secret":"JBSWY3DPEHPK3PXP","issuer":"Migration"}""",
                    mdbxDatabaseId = sourceDatabaseId,
                    mdbxFolderId = folder.folderId
                )
            )
            val secureItem = room.secureItemDao().getItemById(secureItemId)!!
            repository.upsertSecureItem(secureItem)

            val credentialId = "migration-${UUID.randomUUID()}"
            room.passkeyDao().insert(
                PasskeyEntry(
                    credentialId = credentialId,
                    rpId = "migration.test",
                    rpName = "Migration Passkey",
                    userId = "bWlncmF0aW9u",
                    userName = "migration-user",
                    userDisplayName = "Migration User",
                    publicKey = "migration-public-key",
                    privateKeyAlias = "",
                    mdbxDatabaseId = sourceDatabaseId,
                    mdbxFolderId = folder.folderId
                )
            )
            val passkey = room.passkeyDao().getByMdbxDatabaseId(sourceDatabaseId).single { it.credentialId == credentialId }
            repository.upsertPasskey(passkey)

            repository.upsertSteamMaFileEntry(
                databaseId = sourceDatabaseId,
                entryId = "steam-mafile:migration-test",
                title = "Migration Steam",
                maFileJson = """{"steamid":"76561190000000000","account_name":"migration_test"}"""
            )

            val attachmentStorage = AttachmentStorage(context)
            val encrypted = attachmentStorage.writeEncrypted("migration attachment bytes".byteInputStream())
            localAttachmentPath = encrypted.relativePath
            val wrappedCek = try {
                AttachmentKeyVault(securityManager).wrap(encrypted.cek)
            } finally {
                encrypted.cek.fill(0)
            }
            val attachment = Attachment(
                parentPasswordId = passwordId,
                source = AttachmentSource.LOCAL.name,
                fileName = "migration.txt",
                mimeType = "text/plain",
                sizeBytes = encrypted.sizeBytes,
                sha256Hex = encrypted.sha256Hex,
                wrappedCek = wrappedCek,
                localPath = encrypted.relativePath,
                downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            room.attachmentDao().insert(attachment)
            repository.upsertAttachment(sourceDatabaseId, "password:$passwordId", attachment)

            databaseDao.updateDatabase(sourceDatabase.copy(sourceType = sourceType.name))
            val sourceHashBefore = sha256(sourceFile)
            viewModel.prepareMdbx2Migration(sourceDatabaseId)
            val ready = withTimeout(120_000) {
                viewModel.migrationState.first {
                    it is MdbxViewModel.MdbxMigrationState.Ready ||
                        it is MdbxViewModel.MdbxMigrationState.Error
                }
            }
            assertTrue(ready.toString(), ready is MdbxViewModel.MdbxMigrationState.Ready)
            ready as MdbxViewModel.MdbxMigrationState.Ready
            assertTrue(ready.preview.isEligible)
            assertEquals(sourceType == MdbxSourceType.REMOTE_WEBDAV,
                ready.preview.warnings.any { it.kind == MdbxMigrationWarningKind.REMOTE_LOCAL_COPY_ONLY })
            assertEquals(2, ready.preview.folderCount)
            assertEquals(4, ready.preview.activeEntryCount)
            assertEquals(1, ready.preview.attachmentCount)

            viewModel.startMdbx2Migration(sourceDatabaseId, targetName, targetPassword)
            val migrated = withTimeout(180_000) {
                viewModel.migrationState.first {
                    it is MdbxViewModel.MdbxMigrationState.Success ||
                        it is MdbxViewModel.MdbxMigrationState.Error
                }
            }
            assertTrue(migrated.toString(), migrated is MdbxViewModel.MdbxMigrationState.Success)
            migrated as MdbxViewModel.MdbxMigrationState.Success
            targetDatabaseId = migrated.targetDatabaseId
            assertEquals(2, migrated.verification.folderCount)
            assertEquals(4, migrated.verification.entryCount)
            assertEquals(1, migrated.verification.attachmentCount)
            assertTrue(sourceHashBefore.contentEquals(sha256(sourceFile)))

            val targetDatabase = databaseDao.getDatabaseById(targetDatabaseId)!!
            targetFile = File(targetDatabase.workingCopyPath!!)
            assertEquals(MdbxEngineType.RUST_MDBX2, targetDatabase.engineTypeEnum)
            assertTrue(targetFile.isFile)
            assertTrue(File("${targetFile.absolutePath}.blobs").isDirectory)

            val reopenedRepository = MdbxRepositoryFactory.create(context, room, securityManager)
            val migratedFolders = reopenedRepository.listFolders(targetDatabaseId)
            val migratedParent = migratedFolders.single { it.name == "Work" }
            val migratedChild = migratedFolders.single { it.name == "Servers" }
            assertEquals(migratedParent.folderId, migratedChild.parentFolderId)
            val targetEntries = reopenedRepository.readStoredEntries(targetDatabaseId)
            assertEquals(
                setOf("login", "totp", "passkey", "steam-mafile"),
                targetEntries.filterNot { it.deleted }.map { it.entryType }.toSet()
            )
            assertEquals(1, reopenedRepository.readStoredAttachments(targetDatabaseId).size)
            assertEquals(1, room.passwordEntryDao().getByMdbxDatabaseIdSync(targetDatabaseId).size)
            assertEquals(1, room.secureItemDao().getByMdbxDatabaseIdSync(targetDatabaseId).size)
            assertEquals(1, room.passkeyDao().getByMdbxDatabaseId(targetDatabaseId).size)
            assertEquals(4, repository.readStoredEntries(sourceDatabaseId).count { !it.deleted })
            assertNotNull(databaseDao.getDatabaseById(sourceDatabaseId))
        } finally {
            listOf(targetDatabaseId, sourceDatabaseId).filter { it > 0L }.forEach { databaseId ->
                room.attachmentDao().selectLocalPathsByMdbxDatabaseId(databaseId).forEach {
                    AttachmentStorage(context).delete(it)
                }
                room.passwordEntryDao().deleteAllByMdbxDatabaseId(databaseId)
                room.secureItemDao().deleteAllByMdbxDatabaseId(databaseId)
                room.passkeyDao().deleteAllByMdbxDatabaseId(databaseId)
                databaseDao.deleteDatabaseById(databaseId)
            }
            localAttachmentPath?.let { AttachmentStorage(context).delete(it) }
            targetFile?.let { Mdbx2Repository(context, databaseDao, securityManager).deleteOwnedVaultFile(it) }
            sourceFile?.delete()
            assertFalse(targetFile?.exists() == true)
        }
    }

    @Test
    fun attachmentVerificationFailureRemovesTargetArtifacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val viewModel = MdbxViewModel(
            application,
            databaseDao,
            room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(),
            room.secureItemDao(),
            room.passkeyDao(),
            room.attachmentDao(),
            room.customFieldDao(),
            securityManager
        )
        val sourceName = "MDBX1 failed migration ${UUID.randomUUID()}"
        val sourcePassword = "legacy-migration-failure-fixture"
        val targetName = "MDBX2 must be cleaned ${UUID.randomUUID()}"
        var sourceDatabaseId = 0L
        var retryTargetDatabaseId = 0L
        var sourceFile: File? = null
        var retryTargetFile: File? = null
        var localAttachmentPath: String? = null
        var attachmentRoomId = 0L
        try {
            val repository = legacyStore(context, room, securityManager)
            val sourceDatabase = createLegacyFixture(repository, room, securityManager, sourceName, sourcePassword)
            sourceDatabaseId = sourceDatabase.id
            sourceFile = File(sourceDatabase.workingCopyPath!!)
            val passwordId = room.passwordEntryDao().insertPasswordEntry(
                PasswordEntry(
                    title = "Failed migration login",
                    website = "",
                    username = "failure-user",
                    password = "failure-secret",
                    mdbxDatabaseId = sourceDatabaseId
                )
            )
            val passwordEntry = room.passwordEntryDao().getPasswordEntryById(passwordId)!!
            repository.upsertPassword(passwordEntry)

            val attachmentStorage = AttachmentStorage(context)
            val encrypted = attachmentStorage.writeEncrypted("hash mismatch".byteInputStream())
            localAttachmentPath = encrypted.relativePath
            val wrappedCek = try {
                AttachmentKeyVault(securityManager).wrap(encrypted.cek)
            } finally {
                encrypted.cek.fill(0)
            }
            val attachment = Attachment(
                parentPasswordId = passwordId,
                source = AttachmentSource.LOCAL.name,
                fileName = "invalid-hash.txt",
                mimeType = "text/plain",
                sizeBytes = encrypted.sizeBytes,
                sha256Hex = "00".repeat(32),
                wrappedCek = wrappedCek,
                localPath = encrypted.relativePath,
                downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            attachmentRoomId = room.attachmentDao().insert(attachment)
            repository.upsertAttachment(sourceDatabaseId, "password:$passwordId", attachment)

            val sourceHashBefore = sha256(sourceFile)
            val artifactsBefore = mdbx2Artifacts(context.filesDir)
            viewModel.prepareMdbx2Migration(sourceDatabaseId)
            val ready = withTimeout(120_000) {
                viewModel.migrationState.first {
                    it is MdbxViewModel.MdbxMigrationState.Ready ||
                        it is MdbxViewModel.MdbxMigrationState.Error
                }
            }
            assertTrue(ready.toString(), ready is MdbxViewModel.MdbxMigrationState.Ready)

            viewModel.startMdbx2Migration(sourceDatabaseId, targetName, "failed-migration-target")
            val failed = withTimeout(180_000) {
                viewModel.migrationState.first {
                    it is MdbxViewModel.MdbxMigrationState.Success ||
                        it is MdbxViewModel.MdbxMigrationState.Error
                }
            }
            assertTrue(failed.toString(), failed is MdbxViewModel.MdbxMigrationState.Error)
            assertTrue(
                failed.toString(),
                (failed as MdbxViewModel.MdbxMigrationState.Error).message.contains("content hash")
            )
            assertTrue(databaseDao.getAllDatabasesSnapshot().none { it.name == targetName })
            assertEquals(artifactsBefore, mdbx2Artifacts(context.filesDir))
            assertTrue(sourceHashBefore.contentEquals(sha256(sourceFile)))
            assertNotNull(databaseDao.getDatabaseById(sourceDatabaseId))

            repository.deleteAttachment(sourceDatabaseId, "password:$passwordId", attachment)
            room.attachmentDao().deleteById(attachmentRoomId)
            attachmentRoomId = 0L
            val retrySourceHash = sha256(sourceFile)
            viewModel.prepareMdbx2Migration(sourceDatabaseId)
            val retryReady = withTimeout(120_000) {
                viewModel.migrationState.first {
                    it is MdbxViewModel.MdbxMigrationState.Ready ||
                        it is MdbxViewModel.MdbxMigrationState.Error
                }
            }
            assertTrue(retryReady.toString(), retryReady is MdbxViewModel.MdbxMigrationState.Ready)
            viewModel.startMdbx2Migration(sourceDatabaseId, targetName, "failed-migration-target")
            val retrySuccess = withTimeout(180_000) {
                viewModel.migrationState.first {
                    it is MdbxViewModel.MdbxMigrationState.Success ||
                        it is MdbxViewModel.MdbxMigrationState.Error
                }
            }
            assertTrue(retrySuccess.toString(), retrySuccess is MdbxViewModel.MdbxMigrationState.Success)
            retrySuccess as MdbxViewModel.MdbxMigrationState.Success
            retryTargetDatabaseId = retrySuccess.targetDatabaseId
            retryTargetFile = File(databaseDao.getDatabaseById(retryTargetDatabaseId)!!.workingCopyPath!!)
            assertEquals(1, retrySuccess.verification.entryCount)
            assertEquals(0, retrySuccess.verification.attachmentCount)
            assertTrue(retrySourceHash.contentEquals(sha256(sourceFile)))
            assertEquals(1, databaseDao.getAllDatabasesSnapshot().count { it.name == targetName })
        } finally {
            listOf(retryTargetDatabaseId, sourceDatabaseId).filter { it > 0L }.forEach { databaseId ->
                room.attachmentDao().selectLocalPathsByMdbxDatabaseId(databaseId).forEach {
                    AttachmentStorage(context).delete(it)
                }
                room.passwordEntryDao().deleteAllByMdbxDatabaseId(databaseId)
                room.secureItemDao().deleteAllByMdbxDatabaseId(databaseId)
                room.passkeyDao().deleteAllByMdbxDatabaseId(databaseId)
                databaseDao.deleteDatabaseById(databaseId)
            }
            if (attachmentRoomId > 0L) room.attachmentDao().deleteById(attachmentRoomId)
            localAttachmentPath?.let { AttachmentStorage(context).delete(it) }
            retryTargetFile?.let { Mdbx2Repository(context, databaseDao, securityManager).deleteOwnedVaultFile(it) }
            sourceFile?.delete()
        }
    }

    // Legacy data is a pre-existing fixture. App creation and ordinary routing
    // must remain disabled; only the migration reader accesses this old store.
    private fun legacyStore(context: Context, room: PasswordDatabase, security: SecurityManager) =
        MdbxVaultStore(context, room.localMdbxDatabaseDao(), security, room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(), room.secureItemDao(), room.customFieldDao())

    private suspend fun createLegacyFixture(
        store: MdbxVaultStore, room: PasswordDatabase, security: SecurityManager, name: String, password: String
    ): LocalMdbxDatabase {
        val file = store.createInitializedVaultFile(name, MdbxTigaMode.SKY.name,
            MdbxUnlockMethod.MASTER_PASSWORD, MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD, password))
        val record = LocalMdbxDatabase(name = name, filePath = file.absolutePath, workingCopyPath = file.absolutePath,
            storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            tigaMode = MdbxTigaMode.SKY.name, encryptedPassword = security.encryptData(password))
        return record.copy(id = room.localMdbxDatabaseDao().insertDatabase(record))
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun mdbx2Artifacts(filesDir: File): Set<String> {
        val directory = File(filesDir, "mdbx2")
        if (!directory.exists()) return emptySet()
        return directory.walkTopDown()
            .filter { it != directory }
            .map { it.relativeTo(directory).path }
            .toSet()
    }
}
