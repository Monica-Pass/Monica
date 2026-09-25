package takagi.ru.monica.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.isUsable
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class MdbxVaultStoreInstrumentedCompatibilityTest {

    private lateinit var context: Context
    private lateinit var workDir: File
    private lateinit var databaseDao: InMemoryLocalMdbxDatabaseDao
    private lateinit var securityManager: SecurityManager
    private lateinit var store: MdbxVaultStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "mdbx-instrumented-${UUID.randomUUID()}")
        assertTrue(workDir.mkdirs())
        databaseDao = InMemoryLocalMdbxDatabaseDao()
        securityManager = SecurityManager(context)
        store = MdbxVaultStore(
            context = context,
            databaseDao = databaseDao,
            securityManager = securityManager
        )
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun legacyDraftVaultPreparationUpgradesAdditively() = runBlocking {
        val file = File(workDir, "legacy-draft.mdbx")
        createLegacyDraftVault(file)
        val credential = MdbxVaultCredential(
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
            password = "legacy-password"
        )

        store.validateExistingVaultFile(file)
        store.validateVaultCredentialFile(file, credential)

        store.prepareVaultForOfficialMdbx1(file, credential, MdbxTigaMode.SKY)

        store.validateExistingVaultFile(file)
        store.validateVaultCredentialFile(file, credential)
        openReadOnly(file).use { db ->
            assertEquals("MDBX-1-DRAFT", queryString(db, "SELECT format_version FROM vault_meta LIMIT 1"))
            assertEquals("MDBX-1.0", queryString(db, "SELECT release_label FROM vault_meta LIMIT 1"))
            assertEquals(
                "android-official-1.0,sky-portable,tiga-selectable,legacy-test-compatible",
                queryString(db, "SELECT capability_flags FROM vault_meta LIMIT 1")
            )
            assertEquals("sky", queryString(db, "SELECT default_tiga_mode FROM vault_meta LIMIT 1"))
            assertEquals("password", queryString(db, "SELECT unlock_methods FROM vault_meta LIMIT 1"))
            assertNotNull(queryBlob(db, "SELECT credential_salt FROM vault_meta LIMIT 1"))
            assertNotNull(queryBlob(db, "SELECT credential_verifier FROM vault_meta LIMIT 1"))
            assertNotNull(queryString(db, "SELECT credential_kdf_profile FROM vault_meta LIMIT 1"))
            assertNotNull(queryBlob(db, "SELECT wrapped_epoch_key_ct FROM key_epochs WHERE status = 'active' LIMIT 1"))
            assertEquals(1L, queryLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'project_tags'"))
        }
    }

    @Test
    fun newSkyVaultStartsAsOfficialMdbxOneWithoutKeyFileRequirement() = runBlocking {
        val credential = MdbxVaultCredential(
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
            password = "new-password"
        )

        val file = store.createInitializedVaultFile(
            displayName = "New Sky Vault",
            tigaMode = MdbxTigaMode.SKY.name,
            credential = credential
        )

        store.validateExistingVaultFile(file)
        store.validateVaultCredentialFile(file, credential)
        openReadOnly(file).use { db ->
            assertEquals("MDBX-1", queryString(db, "SELECT format_version FROM vault_meta LIMIT 1"))
            assertEquals("MDBX-1.0", queryString(db, "SELECT release_label FROM vault_meta LIMIT 1"))
            assertEquals(
                "android-official-1.0,sky-portable,tiga-selectable,legacy-test-compatible",
                queryString(db, "SELECT capability_flags FROM vault_meta LIMIT 1")
            )
            assertEquals("sky", queryString(db, "SELECT default_tiga_mode FROM vault_meta LIMIT 1"))
            assertEquals("password", queryString(db, "SELECT unlock_methods FROM vault_meta LIMIT 1"))
            assertNull(queryString(db, "SELECT key_file_name FROM vault_meta LIMIT 1"))
            assertNull(queryString(db, "SELECT key_file_fingerprint FROM vault_meta LIMIT 1"))
            assertTrue(
                queryString(db, "SELECT credential_kdf_profile FROM vault_meta LIMIT 1")
                    ?.startsWith("pbkdf2-sha256:") == true
            )
        }
    }

    @Test
    fun officialMdbxOneFacadeSupportsCoreAndroidOperations() = runBlocking {
        val databaseId = 42L
        val vaultPassword = "official-password"
        val credential = MdbxVaultCredential(
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
            password = vaultPassword
        )
        val file = store.createInitializedVaultFile(
            displayName = "Official Android Vault",
            tigaMode = MdbxTigaMode.SKY.name,
            credential = credential
        )
        databaseDao.insertDatabase(
            LocalMdbxDatabase(
                id = databaseId,
                name = "Official Android Vault",
                filePath = file.absolutePath,
                storageLocation = "INTERNAL",
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = securityManager.encryptDataLegacyCompat(vaultPassword),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
            )
        )

        val folder = store.createFolder(databaseId, "Android Folder", "root")
        val password = PasswordEntry(
            id = 1001L,
            title = "Monica Account",
            website = "https://monica.local",
            username = "sky",
            password = securityManager.encryptDataLegacyCompat("secret"),
            notes = "mdbx 1.0 smoke",
            mdbxDatabaseId = databaseId,
            mdbxFolderId = folder.folderId
        )
        val totp = SecureItem(
            id = 2001L,
            itemType = ItemType.TOTP,
            title = "Monica TOTP",
            notes = "folder smoke",
            itemData = JSONObject().put("secret", "JBSWY3DPEHPK3PXP").toString(),
            mdbxDatabaseId = databaseId,
            mdbxFolderId = folder.folderId
        )
        val passkey = PasskeyEntry(
            id = 3001L,
            credentialId = "cred-${UUID.randomUUID()}",
            rpId = "monica.local",
            rpName = "Monica",
            userId = "user-1",
            userName = "sky",
            userDisplayName = "Sky",
            publicKey = "public-key",
            privateKeyAlias = "alias",
            mdbxDatabaseId = databaseId,
            mdbxFolderId = folder.folderId
        )

        store.upsertPassword(password)
        store.upsertSecureItem(totp)
        store.upsertPasskey(passkey)

        val passwordObjectId = store.passwordObjectIdForAttachment(password)
        val attachment = Attachment(
            id = 4001L,
            parentPasswordId = password.id,
            source = AttachmentSource.LOCAL.name,
            fileName = "evidence.txt",
            mimeType = "text/plain",
            sizeBytes = 7L,
            sha256Hex = sha256Hex("evidence".toByteArray()),
            downloadState = AttachmentDownloadState.PENDING.name,
            createdAt = Date(0).time,
            updatedAt = Date(0).time
        )
        store.upsertExternalAttachmentRef(
            databaseId = databaseId,
            parentEntryId = passwordObjectId,
            attachment = attachment,
            externalUri = "content://monica/mdbx/evidence.txt"
        )
        store.setProjectTags(databaseId, passwordObjectId, listOf("Sky", " Android ", "sky"))

        val entries = store.readStoredEntries(databaseId)
        assertTrue(entries.any { it.entryId == passwordObjectId && it.entryType == "login" })
        assertTrue(entries.any { it.entryType == "totp" && it.payloadJson.contains(folder.folderId) })
        assertTrue(entries.any { it.entryType == "passkey" && it.payloadJson.contains(folder.folderId) })
        assertEquals(listOf("Android", "Sky"), store.listProjectTags(databaseId, passwordObjectId))
        val searchResults = store.searchProjects(databaseId, "monica", requiredTags = listOf("sky"))
        assertTrue(searchResults.any { it.projectId == passwordObjectId && it.parentFolderId == folder.folderId })
        assertEquals(listOf("Android", "Sky"), searchResults.first { it.projectId == passwordObjectId }.tags)

        val bundle = store.exportSyncBundle(databaseId)
        assertTrue(bundle.commitCount > 0)
        assertTrue(bundle.payloadJson.contains("project_tags"))
        val importResult = store.importSyncBundle(databaseId, bundle)
        assertTrue(importResult.appliedObjectCount >= 0)

        val snapshot = store.createSnapshot(databaseId, "Android smoke", fullSnapshot = true)
        assertTrue(snapshot.integrityOk)
        val preview = store.getSnapshotStructurePreview(databaseId, snapshot.snapshotId)
        assertTrue(preview.snapshotItemCount >= 3)
        assertTrue(store.listSnapshots(databaseId).any { it.snapshotId == snapshot.snapshotId })

        val diagnostics = store.getVaultDiagnostics(databaseId)
        assertTrue(diagnostics.isReadable)
        assertEquals("MDBX-1", diagnostics.formatVersion)
        assertEquals("MDBX-1.0", diagnostics.releaseLabel)
        assertEquals("sky", diagnostics.defaultTigaMode)
        assertEquals(0, diagnostics.healthIssueCount)
        assertTrue(diagnostics.entryCount >= 3)
        assertEquals(1, diagnostics.externalAttachmentCount)
        assertEquals(0, store.listConflicts(databaseId).size)

        store.deleteAttachment(databaseId, passwordObjectId, attachment)
        assertEquals(0, store.getVaultDiagnostics(databaseId).attachmentCount)
        assertFalse(store.readStoredEntries(databaseId).first { it.entryId == passwordObjectId }.deleted)
    }

    @Test
    fun syncBundleConflictAndBadBundleRollbackUseRealSqlite() = runBlocking {
        val localDatabaseId = 501L
        val incomingDatabaseId = 502L
        val vaultPassword = "conflict-password"
        val file = createRegisteredOfficialVault(localDatabaseId, "Conflict Local", vaultPassword)
        val baseEntry = PasswordEntry(
            id = 5001L,
            title = "Conflict Base",
            website = "https://conflict.local",
            username = "base",
            password = securityManager.encryptDataLegacyCompat("base-secret"),
            notes = "base",
            mdbxDatabaseId = localDatabaseId
        )
        store.upsertPassword(baseEntry)
        checkpointFile(file)

        val incomingFile = File(workDir, "incoming-branch.mdbx")
        file.copyTo(incomingFile, overwrite = true)
        databaseDao.insertDatabase(
            LocalMdbxDatabase(
                id = incomingDatabaseId,
                name = "Conflict Incoming",
                filePath = incomingFile.absolutePath,
                storageLocation = "INTERNAL",
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = securityManager.encryptDataLegacyCompat(vaultPassword),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
            )
        )

        store.upsertPassword(
            baseEntry.copy(
                title = "Local Winner",
                notes = "local edit"
            )
        )
        store.upsertPassword(
            baseEntry.copy(
                title = "Incoming Challenger",
                notes = "incoming edit",
                mdbxDatabaseId = incomingDatabaseId
            )
        )

        val incomingBundle = store.exportSyncBundle(incomingDatabaseId)
        val localBeforeBadBundle = store.readStoredEntries(localDatabaseId)
            .first { it.entryId == store.passwordObjectIdForAttachment(baseEntry) }
        try {
            store.importSyncBundle(localDatabaseId, incomingBundle.copy(payloadHash = "bad-hash"))
            fail("Bad MDBX sync bundle hash should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("hash mismatch"))
        }
        val localAfterBadBundle = store.readStoredEntries(localDatabaseId)
            .first { it.entryId == localBeforeBadBundle.entryId }
        assertEquals(localBeforeBadBundle.title, localAfterBadBundle.title)
        assertEquals(0, store.listConflicts(localDatabaseId).size)

        val applyResult = store.importSyncBundle(localDatabaseId, incomingBundle)
        assertEquals(1, applyResult.conflictCount)
        val conflict = store.listConflicts(localDatabaseId).single()
        assertEquals(localBeforeBadBundle.entryId, conflict.objectId)
        assertTrue(conflict.localTitle.orEmpty().contains("Local Winner"))
        assertTrue(conflict.incomingTitle.orEmpty().contains("Incoming Challenger"))

        store.resolveConflict(localDatabaseId, conflict.conflictId, MdbxConflictResolution.LOCAL_WINS)
        assertEquals(0, store.listConflicts(localDatabaseId).size)
        val resolved = store.readStoredEntries(localDatabaseId).first { it.entryId == conflict.objectId }
        assertEquals("Local Winner", resolved.title)
    }

    private fun createLegacyDraftVault(file: File) {
        openWritable(file).use { db ->
            db.execSQL(
                """
                CREATE TABLE vault_meta (
                    vault_id TEXT PRIMARY KEY NOT NULL,
                    format_name TEXT NOT NULL DEFAULT 'Monica Database eXtended',
                    format_version TEXT NOT NULL DEFAULT 'MDBX-1-DRAFT',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    default_tiga_mode TEXT NOT NULL DEFAULT 'sky',
                    unlock_methods TEXT NOT NULL DEFAULT 'password',
                    active_key_epoch_id TEXT NOT NULL DEFAULT '',
                    compat_flags TEXT NOT NULL DEFAULT '',
                    critical_extensions TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO vault_meta (
                    vault_id, format_name, format_version, created_at, updated_at,
                    default_tiga_mode, unlock_methods, active_key_epoch_id
                ) VALUES (?, 'Monica Database eXtended', 'MDBX-1-DRAFT', ?, ?, 'sky', 'password', '')
                """.trimIndent(),
                arrayOf(UUID.randomUUID().toString(), "2026-06-02T00:00:00Z", "2026-06-02T00:00:00Z")
            )
            db.execSQL(
                """
                CREATE TABLE folders (
                    folder_id TEXT PRIMARY KEY NOT NULL,
                    parent_folder_id TEXT,
                    name_ct BLOB NOT NULL,
                    path_key TEXT NOT NULL,
                    object_clock TEXT NOT NULL,
                    head_commit_id TEXT NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    created_by_device_id TEXT NOT NULL,
                    updated_by_device_id TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO folders (
                    folder_id, parent_folder_id, name_ct, path_key, object_clock,
                    head_commit_id, deleted, created_at, updated_at, created_by_device_id, updated_by_device_id
                ) VALUES ('root', NULL, ?, '/', '1', ?, 0, ?, ?, 'legacy-device', 'legacy-device')
                """.trimIndent(),
                arrayOf("Root".toByteArray(), UUID.randomUUID().toString(), "2026-06-02T00:00:00Z", "2026-06-02T00:00:00Z")
            )
            db.execSQL(
                """
                CREATE TABLE projects (
                    project_id TEXT PRIMARY KEY NOT NULL,
                    title_ct BLOB NOT NULL,
                    summary_ct BLOB,
                    group_id TEXT,
                    icon_ref TEXT,
                    favorite INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    tiga_mode_override TEXT,
                    object_clock TEXT NOT NULL,
                    head_commit_id TEXT NOT NULL,
                    attachment_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    created_by_device_id TEXT NOT NULL,
                    updated_by_device_id TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE entries (
                    entry_id TEXT PRIMARY KEY NOT NULL,
                    project_id TEXT NOT NULL,
                    entry_type TEXT NOT NULL,
                    title_ct BLOB,
                    payload_ct BLOB NOT NULL,
                    payload_schema_version INTEGER NOT NULL DEFAULT 1,
                    tiga_mode_override TEXT,
                    object_clock TEXT NOT NULL,
                    head_commit_id TEXT NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    created_by_device_id TEXT NOT NULL,
                    updated_by_device_id TEXT NOT NULL,
                    FOREIGN KEY (project_id) REFERENCES projects(project_id)
                )
                """.trimIndent()
            )
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { it.moveToFirst() }
        }
    }

    private suspend fun createRegisteredOfficialVault(
        databaseId: Long,
        name: String,
        password: String
    ): File {
        val credential = MdbxVaultCredential(
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
            password = password
        )
        val file = store.createInitializedVaultFile(
            displayName = name,
            tigaMode = MdbxTigaMode.SKY.name,
            credential = credential
        )
        databaseDao.insertDatabase(
            LocalMdbxDatabase(
                id = databaseId,
                name = name,
                filePath = file.absolutePath,
                storageLocation = "INTERNAL",
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = securityManager.encryptDataLegacyCompat(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
            )
        )
        return file
    }

    private fun checkpointFile(file: File) {
        openWritable(file).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { it.moveToFirst() }
        }
    }

    private fun openWritable(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or
                SQLiteDatabase.CREATE_IF_NECESSARY or
                SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        ).apply {
            setForeignKeyConstraintsEnabled(true)
        }

    private fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )

    private fun queryString(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, emptyArray()).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun queryBlob(db: SQLiteDatabase, sql: String): ByteArray? =
        db.rawQuery(sql, emptyArray()).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else null
        }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class InMemoryLocalMdbxDatabaseDao : LocalMdbxDatabaseDao {
        private val databases = linkedMapOf<Long, LocalMdbxDatabase>()
        private val flow = MutableStateFlow<List<LocalMdbxDatabase>>(emptyList())
        private var nextId = 1L

        override fun getAllDatabases(): Flow<List<LocalMdbxDatabase>> = flow
        override suspend fun getAllDatabasesSnapshot(): List<LocalMdbxDatabase> = databases.values.toList()
        override fun getAvailableDatabases(): Flow<List<LocalMdbxDatabase>> =
            flow.map { rows -> rows.filter { it.isUsable } }
        override suspend fun getAvailableDatabasesSnapshot(): List<LocalMdbxDatabase> =
            databases.values.filter { it.isUsable }
        override suspend fun getDatabaseById(id: Long): LocalMdbxDatabase? = databases[id]
        override suspend fun getDefaultDatabase(): LocalMdbxDatabase? = databases.values.firstOrNull { it.isDefault }
        override fun getDatabasesByLocation(location: String): Flow<List<LocalMdbxDatabase>> =
            MutableStateFlow(databases.values.filter { it.storageLocation == location })

        override fun getDatabasesBySourceType(sourceType: String): Flow<List<LocalMdbxDatabase>> =
            MutableStateFlow(databases.values.filter { it.sourceType == sourceType })

        override suspend fun insertDatabase(database: LocalMdbxDatabase): Long {
            val id = database.id.takeIf { it > 0L } ?: nextId++
            databases[id] = database.copy(id = id)
            publish()
            return id
        }

        override suspend fun updateDatabase(database: LocalMdbxDatabase) {
            databases[database.id] = database
            publish()
        }

        override suspend fun deleteDatabase(database: LocalMdbxDatabase) {
            databases.remove(database.id)
            publish()
        }

        override suspend fun deleteDatabaseById(id: Long) {
            databases.remove(id)
            publish()
        }

        override suspend fun clearDefaultDatabase() {
            databases.replaceAll { _, value -> value.copy(isDefault = false) }
            publish()
        }

        override suspend fun setDefaultDatabase(id: Long) {
            databases.replaceAll { key, value -> value.copy(isDefault = key == id) }
            publish()
        }

        override suspend fun updateLastAccessedTime(id: Long, time: Long) {
            mutate(id) { it.copy(lastAccessedAt = time) }
        }

        override suspend fun updateProjectCount(id: Long, count: Int) {
            mutate(id) { it.copy(projectCount = count) }
        }

        override suspend fun updateSourceBinding(databaseId: Long, sourceId: Long?) {
            mutate(databaseId) { it.copy(sourceId = sourceId) }
        }

        override suspend fun updateLocalCopies(databaseId: Long, workingPath: String?, cachePath: String?) {
            mutate(databaseId) { it.copy(workingCopyPath = workingPath, cacheCopyPath = cachePath) }
        }

        override suspend fun updateSyncStatus(databaseId: Long, status: String, error: String?) {
            mutate(databaseId) { it.copy(lastSyncStatus = status, lastSyncError = error) }
        }

        override suspend fun updateSyncSuccess(databaseId: Long, status: String, time: Long) {
            mutate(databaseId) {
                it.copy(
                    lastSyncedAt = time,
                    lastSyncStatus = status,
                    lastSyncError = null
                )
            }
        }

        override suspend fun updateRemoteSyncSuccess(databaseId: Long, status: String, time: Long) {
            mutate(databaseId) {
                it.copy(lastSyncedAt = time, lastSyncStatus = status, lastSyncError = null, isOfflineAvailable = true)
            }
        }

        private fun mutate(id: Long, block: (LocalMdbxDatabase) -> LocalMdbxDatabase) {
            databases[id]?.let { databases[id] = block(it) }
            publish()
        }

        private fun publish() {
            flow.value = databases.values.toList()
        }
    }
}
