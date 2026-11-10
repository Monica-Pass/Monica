package takagi.ru.monica.ui.screens

import android.database.Cursor
import android.os.Process
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup

/** One archived, interrupted synthetic run only. This does not establish synchronization success.
 * No native vault opening, credential replacement, remote operation, or original-state restoration.
 * Any unexpected registration, projection, file, or digest fails before cleanup starts.
 */
@RunWith(AndroidJUnit4::class)
class ToolchainMdbxSyncFixtureRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dataRoot get() = File(context.applicationInfo.dataDir)

    @Test fun removeOnlyArchivedInterruptedRunE6116ff6() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        check(Process.myUid() == 1_310_060 && Process.myUid() / 100_000 == 13)
        check(context.packageName == PACKAGE && args.getString("toolchainDatabaseUiUser") == "13")
        check(args.getString("toolchainDatabaseUi") == "yes")
        check(args.getString("toolchainRecoverMdbxRun") == RUN)
        check(args.getString("toolchainTargetApkSha256") == sha(File(context.applicationInfo.sourceDir)))
        val ownerFile = File(context.filesDir, "toolchain-db-ui-owner.json")
        check(sha(ownerFile) == "11e3c258db654a7c9b2a4ee6ac7a230af905ee45bb118cb3362df0a22c638211")
        val owner = JSONObject(ownerFile.readText())
        check(owner.getString("task") == "20261109-android-toolchain" && owner.getInt("uid") == Process.myUid())
        check(owner.getString("name") == "Monica_Toolchain326_Ui_20261110" && owner.getString("package") == PACKAGE)
        check(owner.getBoolean("initialized") && !owner.getBoolean("initAllowed"))
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val rows = dao.getAllDatabasesSnapshot()
        check(rows.map { it.id }.toSet() == IDS) { "Unexpected MDBX registrations" }
        rows.forEach { row ->
            val filename = checkNotNull(VAULT_FILES[row.id])
            val path = File(context.filesDir, "mdbx2/$filename").absolutePath
            check(row.name == PREFIX + if (row.id == 18L) "_sibling" else "_target")
            check(row.workingCopyPath == path && row.cacheCopyPath == path)
            check(row.filePath == if (row.id == 18L) path else REMOTE_PATH)
            check(row.sourceId == if (row.id == 18L) null else 1L)
            check(row.sourceType == if (row.id == 18L) "LOCAL_INTERNAL" else "REMOTE_WEBDAV")
            check(row.storageLocation == if (row.id == 18L) "INTERNAL" else "REMOTE_WEBDAV")
            check(row.engineType == "RUST_MDBX2" && row.tigaMode == "SKY")
            check(row.unlockMethod == "password" && row.kdfProfile == "argon2id-mdbx2")
        }
        val source = checkNotNull(room.mdbxRemoteSourceDao().getSourceById(1L))
        check(source.displayName == "${PREFIX}_target" && source.remotePath == REMOTE_PATH)
        check(source.remoteParentPath == "monica-toolchain-ui-sync/$RUN" && source.baseUrl == "http://127.0.0.1:18869/")
        check(rows.filter { it.sourceId == 1L }.map { it.id }.toSet() == setOf(16L, 17L))
        // The archived run has no projected entries/categories. Reject any later data before deleting.
        listOf("password_entries", "secure_items", "passkeys", "categories").forEach { table ->
            room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE mdbx_database_id IN (16,17,18)").use {
                check(it.moveToFirst() && it.getLong(0) == 0L) { "Unexpected owned projection in $table" }
            }
        }
        val active = context.getSharedPreferences("mdbx_active_vault", 0)
        check(active.all[ACTIVE_KEY] == 16L)
        val native = context.getSharedPreferences("mdbx2_vault_sessions", 0)
        check(native.getString("device_id", null) == "ui-a-$RUN")
        val files = EXPECTED_FILES.mapValues { (path, expected) ->
            checkedFile(path).also { check(it.isFile && it.length() == expected.first && sha(it) == expected.second) { "Changed archived file: $path" } }
        }
        val ownedCache = checkedDirectory("cache/$PREFIX")
        check(treeHashes(ownedCache).keys == setOf("segment-0.mdbxsync", "receiver/sync-state-17.json"))
        VAULT_FILES.values.forEach { filename ->
            val directory = checkedDirectory("files/mdbx2")
            check(directory.listFiles().orEmpty().none { it.name.startsWith(filename) && it.name != filename }) { "Unexpected native sidecar" }
        }
        val sharedSync = checkedDirectory("files/mdbx2-sync")
        check(!File(sharedSync, "sync-state-17.json").exists() && !File(sharedSync, "sync-state-18.json").exists())
        listOf("pending", "incoming", "blob-transfer").forEach { child ->
            File(sharedSync, child).listFiles().orEmpty().forEach { file ->
                check(IDS.none { id -> listOf("segment-$id-", "segment-receive-$id-", "blob-upload-$id-", "blob-download-$id-", "blob-verify-$id-")
                    .any(file.name::startsWith) }) { "Unexpected owned sync cache" }
            }
        }
        val originalRows = protectedRows(room)
        val originalPreferences = preferences()
        val expectedPreferences = originalPreferences.toMutableMap().apply {
            put("mdbx_active_vault", checkNotNull(get("mdbx_active_vault")).filterKeys { it != ACTIVE_KEY })
        }
        val originalSettings = treeHashes(File(context.filesDir, "datastore"))
        val originalEvidence = treeHashes(File(context.filesDir, "toolchain-db-ui"))
        val originalRecovery = treeHashes(File(context.filesDir, "local-vault-recovery"))
        val report = checkedFile("cache/toolchain-mdbx-interrupted-cleanup-$RUN.json")
        check(!report.exists()) { "Recovery receipt already exists; do not silently rerun partial cleanup" }
        val receipt = JSONObject().put("kind", "interrupted-fixture-cleanup").put("run", RUN)
            .put("synchronizationPassed", false).put("restoredPreRunState", false)
            .put("retainedSyntheticDeviceId", "ui-a-$RUN").put("removedIds", JSONArray())
            .put("removedFiles", JSONArray()).put("remoteAndScreenshotsRetained", true).put("phase", "preflight-complete")
        report.writeText(receipt.toString(2))
        try {
            room.withTransaction {
                check(dao.getAllDatabasesSnapshot() == rows)
                check(room.mdbxRemoteSourceDao().getSourceById(1L) == source)
                IDS.forEach { id ->
                    room.passwordEntryDao().deleteAllByMdbxDatabaseId(id)
                    room.secureItemDao().deleteAllByMdbxDatabaseId(id)
                    room.passkeyDao().deleteAllByMdbxDatabaseId(id)
                    room.categoryDao().getCategoriesByMdbxDatabase(id).first().forEach { room.categoryDao().delete(it) }
                    room.mdbxSyncStateDao().delete(id)
                    dao.deleteDatabaseById(id)
                }
                room.mdbxRemoteSourceDao().deleteSourceById(1L)
                check(protectedRows(room) == originalRows) { "Unrelated Room rows changed; rollback" }
            }
            receipt.put("removedIds", JSONArray(IDS.toList())).put("phase", "registrations-removed")
            report.writeText(receipt.toString(2))
            check(active.all[ACTIVE_KEY] == 16L)
            check(active.edit().remove(ACTIVE_KEY).commit())
            files.forEach { (path, file) ->
                val expected = checkNotNull(EXPECTED_FILES[path])
                check(file == checkedFile(path) && file.isFile && file.length() == expected.first && sha(file) == expected.second)
                check(file.delete()) { "Cannot delete owned archived file: $path" }
                receipt.getJSONArray("removedFiles").put(path)
                report.writeText(receipt.toString(2))
            }
            val receiver = checkedDirectory("cache/$PREFIX/receiver")
            check(receiver.listFiles().orEmpty().isEmpty() && receiver.delete())
            check(ownedCache.listFiles().orEmpty().isEmpty() && ownedCache.delete())
            check(dao.getAllDatabasesSnapshot().isEmpty())
            check(room.mdbxRemoteSourceDao().getSourceById(1L) == null)
            IDS.forEach { check(room.mdbxSyncStateDao().get(it) == null) }
            check(protectedRows(room) == originalRows)
            check(preferences() == expectedPreferences) { "Unrelated preferences changed" }
            check(treeHashes(File(context.filesDir, "datastore")) == originalSettings)
            check(treeHashes(File(context.filesDir, "toolchain-db-ui")) == originalEvidence)
            check(treeHashes(File(context.filesDir, "local-vault-recovery")) == originalRecovery)
            check(sha(ownerFile) == "11e3c258db654a7c9b2a4ee6ac7a230af905ee45bb118cb3362df0a22c638211")
            receipt.put("phase", "interrupted-fixture-cleanup-complete").put("unrelatedRowsAndPreferencesPreserved", true)
            report.writeText(receipt.toString(2))
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "TOOLCHAIN_INTERRUPTED_FIXTURE_CLEANUP ${report.absolutePath}; not a synchronization pass\n")
            })
        } catch (failure: Throwable) {
            receipt.put("phase", "cleanup-incomplete").put("failureType", failure.javaClass.name)
            runCatching { report.writeText(receipt.toString(2)) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun checkedFile(relative: String): File {
        val root = dataRoot.canonicalFile
        val file = File(root, relative)
        check(!File(relative).isAbsolute && ".." !in relative.split('/'))
        check(file.canonicalFile == file.absoluteFile && file.path.startsWith(root.path + File.separator))
        return file
    }
    private fun checkedDirectory(relative: String): File = checkedFile(relative).also { check(it.isDirectory) }
    private fun treeHashes(root: File): Map<String, String> {
        if (!root.exists()) return emptyMap()
        check(root.isDirectory && root.canonicalFile == root.absoluteFile)
        val result = sortedMapOf<String, String>()
        fun visit(directory: File) {
            directory.listFiles().orEmpty().forEach { file ->
                check(file.canonicalFile == file.absoluteFile && file.path.startsWith(root.path + File.separator))
                if (file.isDirectory) visit(file) else {
                    check(file.isFile)
                    result[file.relativeTo(root).invariantSeparatorsPath] = sha(file)
                }
            }
        }
        visit(root)
        return result
    }
    private fun preferences(): Map<String, Map<String, *>> = checkedDirectory("shared_prefs").listFiles().orEmpty()
        .filter { it.name.endsWith(".xml") }.associate { file ->
            val name = file.name.removeSuffix(".xml")
            name to context.getSharedPreferences(name, 0).all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        }
    private fun protectedRows(room: PasswordDatabase): Map<String, String> {
        val db = room.openHelper.readableDatabase
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            val filter = when (table) {
                "local_mdbx_databases" -> " WHERE id NOT IN (16,17,18)"
                "mdbx_remote_sources" -> " WHERE id != 1"
                "mdbx_sync_states" -> " WHERE database_id NOT IN (16,17,18)"
                else -> ""
            }
            val rows = JSONArray()
            db.query("SELECT * FROM `$table`$filter ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONArray()
                    cursor.columnNames.sorted().forEach { column ->
                        val i = cursor.getColumnIndexOrThrow(column)
                        row.put(JSONArray().put(column).put(cursor.getType(i)).put(when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(i))
                            else -> cursor.getString(i)
                        }))
                    }
                    rows.put(row)
                }
            }
            digest(rows.toString().toByteArray())
        }
    }
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        const val PACKAGE = "takagi.ru.monica.bwsync326"
        const val RUN = "e6116ff6-c476-4ff1-81c4-e6ea4889bf4a"
        const val PREFIX = "toolchain_mdbx_sync_e6116ff6_c476_4ff1_81c4_e6ea4889bf4a"
        const val REMOTE_PATH = "monica-toolchain-ui-sync/$RUN/${PREFIX}_target.mdbx"
        const val ACTIVE_KEY = "last_active_mdbx_database_id"
        val IDS = setOf(16L, 17L, 18L)
        val VAULT_FILES = mapOf(16L to "2d4fee5d-25a5-4ead-b24d-3743fff91eb4.mdbx",
            17L to "$PREFIX-replica.mdbx", 18L to "b38c5fc4-2719-443f-9eb2-79ecb34cfa67.mdbx")
        val EXPECTED_FILES = linkedMapOf(
            "files/mdbx2/2d4fee5d-25a5-4ead-b24d-3743fff91eb4.mdbx" to (536576L to "b8b315a8f2a64c5d24dfe7143403460f03db77c7c044327f846e2e5fc590ea22"),
            "files/mdbx2/toolchain_mdbx_sync_e6116ff6_c476_4ff1_81c4_e6ea4889bf4a-replica.mdbx" to (528384L to "0a6e8dc051c6fd68f225703afad7fe1b791bd8faf2168ede2e4bc93ed108c7dd"),
            "files/mdbx2/b38c5fc4-2719-443f-9eb2-79ecb34cfa67.mdbx" to (536576L to "468e664eb4b4a21613414a299e73f7db39c0f90b08b49ef06cb2ff85cbd91234"),
            "files/mdbx2-sync/sync-state-16.json" to (358L to "8fa28f67620c55e2bc4ae4c6ae9f55e3fc3c5930b106511da668e39a17660bfe"),
            "cache/$PREFIX/segment-0.mdbxsync" to (8724L to "f8cd38abbdb853ddb74cc5e029dcdc596bf3ab1991af04a6d7ab9cf725701de0"),
            "cache/$PREFIX/receiver/sync-state-17.json" to (358L to "413db390206f43b4026ec4f46b621db2a3fdcb69d9d9c898bf763bf11ff749fb")
        )
    }
}
