package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.*

/** Host-created synthetic data, real Android password writer, independently checked by Rust. */
@RunWith(AndroidJUnit4::class)
class MdbxCliPasswordAlignmentTest {
    @Test fun passwordAdapterPreservesCliIdentityAndAcceptsTheCliReturn() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host synthetic password fixture required", args.containsKey("monicaPasswordInputDir"))
        fun privatePath(value: String): File = File(value).canonicalFile.also { file ->
            check(file.path.startsWith(context.filesDir.canonicalPath + File.separator))
        }
        val input = privatePath(args.getString("monicaPasswordInputDir")!!)
        val output = args.getString("monicaPasswordOutputDir")?.let(::privatePath)
        check(output == null || !output.exists())
        val manifest = JSONObject(File(input, "manifest.json").readText())
        val password = manifest.getString("password")
        check(password == "Synthetic password alignment 20261005!")
        val verify = args.getString("monicaPasswordPhase") == "verify-cli"
        val work = File(context.filesDir, "mdbx2/password-alignment-${UUID.randomUUID()}.mdbx")
        work.parentFile!!.mkdirs()
        File(input, if (verify) "cli-return.mdbx" else "fixture.mdbx").copyTo(work)
        Mdbx2NativeRuntime.ensureLoaded()
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security,
            passwordEntryDao = room.passwordEntryDao(), secureItemDao = room.secureItemDao(),
            customFieldDao = room.customFieldDao())
        val inserted = mutableListOf<Long>()
        var databaseId = 0L
        try {
            databaseId = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Synthetic CLI password alignment", filePath = work.absolutePath, workingCopyPath = work.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                storageLocation = MdbxStorageLocation.INTERNAL.name, encryptedPassword = security.encryptData(password)))
            val initial = repository.readStoredEntries(databaseId).filterNot { it.deleted }
            assertEquals(if (verify) 3 else 2, initial.size)
            if (verify) {
                val returned = initial.single { it.entryId == manifest.getString("android_logical") }
                val value = JSONObject(returned.payloadJson)
                assertEquals("synthetic-android-created-password", value.getString("password_plain"))
                assertEquals("cli-edited-android-user", value.getString("username"))
                assertEquals("CLI 原样回写\n下一行", value.getString("notes"))
            } else {
                val expected = manifest.getJSONArray("objects").getJSONObject(0)
                val stored = initial.single { it.entryId == expected.getString("logical_id") }
                val value = JSONObject(stored.payloadJson)
                assertEquals(expected.getJSONObject("payload").toString(), value.toString())
                val prototype = PasswordEntry(title = stored.title, website = value.getString("website"),
                    username = value.getString("username"), password = security.encryptData(value.getString("password_plain")),
                    notes = value.getString("notes"), replicaGroupId = stored.entryId, mdbxDatabaseId = databaseId,
                    mdbxFolderId = expected.getString("category"), passwordGroupId = value.getString("password_group_id"),
                    appPackageName = value.getString("app_package_name"), appName = value.getString("app_name"),
                    authenticatorKey = security.encryptData(value.getString("authenticator_key")),
                    passkeyBindings = value.getString("passkey_bindings"))
                val rowId = room.passwordEntryDao().insertPasswordEntry(prototype)
                inserted += rowId
                val custom = value.getJSONArray("custom_fields")
                room.customFieldDao().insertAll((0 until custom.length()).map { index ->
                    val field = custom.getJSONObject(index)
                    CustomField(entryId = rowId, title = field.getString("title"), value = field.getString("value"),
                        isProtected = field.getBoolean("is_protected"), sortOrder = field.getInt("sort_order"))
                })
                val edited = prototype.copy(id = rowId, username = "android-edited-user", notes = "Android 第一行\n第二行")
                repository.upsertPassword(edited)
                repository.upsertPassword(edited)
                assertEquals(2, repository.readStoredEntries(databaseId).count { !it.deleted })
                val android = PasswordEntry(title = "Android-created fixture", website = "https://example.invalid", username = "android-user",
                    password = security.encryptData("synthetic-android-created-password"), mdbxDatabaseId = databaseId,
                    replicaGroupId = manifest.getString("android_logical"))
                val androidRow = room.passwordEntryDao().insertPasswordEntry(android)
                inserted += androidRow
                repository.upsertPassword(android.copy(id = androidRow))
                assertEquals(3, repository.readStoredEntries(databaseId).count { !it.deleted })
            }
            Mdbx2NativeReadSessions.clear()
            openVault(work.absolutePath, password, "password-alignment-check").use { native ->
                assertEquals(manifest.getString("vault_id"), native.info().vaultId)
                val expected = manifest.getJSONArray("objects")
                for (index in 0 until expected.length()) {
                    val item = expected.getJSONObject(index)
                    val record = native.revealObject(item.getString("id")).`object`!!
                    assertEquals("login", record.objectTypeId)
                    assertEquals(item.getString("category"), record.collectionId)
                    assertEquals(item.getString("logical_id"), JSONObject(record.payloadJson).getString("monica_entry_id"))
                    assertEquals(item.getJSONObject("payload").getString("password_plain"), JSONObject(record.payloadJson).getString("password_plain"))
                }
                assertEquals("login", native.revealObject(manifest.getString("android_id")).`object`!!.objectTypeId)
            }
            if (output != null) {
                check(output.mkdirs())
                createPortableBackup(work.absolutePath, File(output, "android-return.mdbx").absolutePath)
            }
        } finally {
            Mdbx2NativeReadSessions.clear()
            for (id in inserted) room.passwordEntryDao().deletePasswordEntryById(id)
            if (databaseId != 0L) room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(work)
        }
    }
}
