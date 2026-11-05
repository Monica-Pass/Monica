package takagi.ru.monica.lansync

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.Mdbx2NativeRuntime
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/** Executes Android's native export path against the actual host vault engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
class DeviceNativePasskeyPreparationTest {
    companion object {
        @JvmStatic @BeforeClass fun hostEngine() {
            val platform = System.getProperty("os.name").lowercase().let { name ->
                when { name.contains("windows") -> "win"; name.contains("mac") -> "osx"; else -> "linux" }
            }
            val architecture = if (System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64")) "arm64" else "x64"
            val relative = "Monica-by-Avalonia/monica by avalonia/src/Monica.Platform/Mdbx/runtimes/$platform-$architecture/${System.mapLibraryName("mdbx_ffi")}"
            val configured = System.getenv("MONICA_MDBX_HOST_LIBRARY")?.takeIf { it.isNotBlank() }?.let(::File)
            val library = (listOfNotNull(configured) + listOf(File("../../$relative"), File("../../../$relative"))).firstOrNull { it.isFile }
            org.junit.Assume.assumeTrue("A matching host vault engine is needed for native export integration", library != null)
            requireNotNull(library)
            val matchesBinding = runCatching { com.sun.jna.NativeLibrary.getInstance(library.absolutePath)
                .getFunction("uniffi_mdbx_ffi_fn_method_mdbxvault_metadata_cache_stats") }.isSuccess
            org.junit.Assume.assumeTrue("The host engine must match the Android binding; set MONICA_MDBX_HOST_LIBRARY", matchesBinding)
            System.setProperty("uniffi.component.mdbx_ffi.libraryOverride", library.absolutePath)
            // JNA loads the real host engine; Android's class-loader preload is device-specific.
            ReflectionHelpers.setStaticField(Mdbx2NativeRuntime::class.java, "loaded", true)
        }
    }

    @Test fun native_legacy_cache_is_derived_and_real_mismatch_is_identified() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries().build()
        SessionManager.markUnlocked()
        try {
            val directory = File(context.filesDir, "native-passkey-${UUID.randomUUID()}")
            assertTrue(directory.mkdirs())
            val password = "synthetic-native-passkey-password"
            val repository = Mdbx2Repository(context, database.localMdbxDatabaseDao(), SecurityManager(context))
            val path = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
            val project = UUID.randomUUID().toString()
            val entry = UUID.randomUUID().toString()
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = generator.generateKeyPair()
            val payload = JSONObject().put("kind", "passkey").put("credential_id", "synthetic-native-credential")
                .put("rp_id", "example.test").put("user_id", "Zml4dHVyZQ==")
                .put("public_key", "").put("private_key_alias", Base64.getEncoder().encodeToString(pair.private.encoded))
            uniffi.mdbx_ffi.openVault(path.absolutePath, password, "fixture-native").use { vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "fixture-create", listOf(
                    MdbxWriteCommand.CreateProject(project, "Fixture folder"),
                    MdbxWriteCommand.CreateEntry(entry, project, "passkey", "Legacy native key", payload.toString())))
            }
            val id = database.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Fixture native vault",
                filePath = path.absolutePath, storageLocation = MdbxStorageLocation.INTERNAL.name,
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name, engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = MdbxTigaMode.SKY.name, encryptedPassword = password, isOfflineAvailable = true))
            val exported = repository.deviceSyncPasswords(id).single { it.data.optString("entry_type") == "passkey" }.data
            DevicePasskeyShape.verify(exported)
            assertEquals(-7, exported.getJSONObject("payload").getInt("public_key_algorithm"))
            assertTrue(exported.getJSONObject("payload").getString("public_key").isNotBlank())
            val snapshot = repository.deviceSyncPasswords(id).single { it.data.optString("entry_type") == "passkey" }
            val reference = JSONObject(snapshot.data.toString())
            reference.getJSONObject("payload").put("private_key_alias", "").put("key_status", "reference")
                .put("notes", "Native reference update")
            assertNotNull(repository.applyDeviceSyncPassword(id, entry, entry, snapshot.guard, false, reference))
            uniffi.mdbx_ffi.openVault(path.absolutePath, password, "fixture-native").use { vault ->
                val legacy = JSONObject(vault.getObject(project, entry)!!.payloadJson)
                assertEquals(Base64.getEncoder().encodeToString(pair.private.encoded), legacy.getString("private_key_alias"))
                assertEquals("reference", legacy.getString("key_status"))
                assertEquals("Native reference update", legacy.getString("notes"))
                assertTrue(DevicePasskeyShape.matchesStoredPublicKey(context, PasskeyEntry(
                    credentialId = "synthetic-native-credential", rpId = "example.test", rpName = "Legacy native key",
                    userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture", publicKey = "",
                    privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)), legacy))
                payload.put("public_key_algorithm", -7)
                    .put("public_key", Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded))
                vault.executeWriteOperation(UUID.randomUUID().toString(), "fixture-update", listOf(
                    MdbxWriteCommand.UpdateEntry(entry, project, "passkey", "Mismatched native key", payload.toString())))
            }
            takagi.ru.monica.repository.Mdbx2NativeReadSessions.invalidate(id)
            val failure = try {
                repository.deviceSyncPasswords(id)
                fail("Mismatched native keys must remain rejected")
                error("unreachable")
            } catch (failure: LanSyncException) { failure }
            assertEquals("invalid_passkey_material", failure.code)
            assertTrue(failure.operation.startsWith("passkey_native_${id}_"))
            assertTrue(failure.operation.endsWith("public_key_mismatch"))
            assertEquals("Mismatched native key", failure.recordName)
            assertFalse(failure.message.orEmpty().contains("example.test"))
        } finally {
            takagi.ru.monica.repository.Mdbx2NativeReadSessions.clear()
            database.close(); SessionManager.markLocked()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun projection_keeps_known_algorithms() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries().build()
        val previous = ReflectionHelpers.getStaticField<PasswordDatabase?>(PasswordDatabase::class.java, "INSTANCE")
        val models = androidx.lifecycle.ViewModelStore()
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        ReflectionHelpers.setStaticField(PasswordDatabase::class.java, "INSTANCE", database)
        SessionManager.markUnlocked()
        try {
            val repository = Mdbx2Repository(context, database.localMdbxDatabaseDao(), SecurityManager(context))
            val password = "synthetic-projection-password"
            val path = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
            val id = database.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Projection fixture",
                filePath = path.absolutePath, storageLocation = MdbxStorageLocation.INTERNAL.name,
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name, engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = MdbxTigaMode.SKY.name, encryptedPassword = password, isOfflineAvailable = true))
            val project = UUID.randomUUID().toString()
            val expected = mutableMapOf<String, Int>()
            uniffi.mdbx_ffi.openVault(path.absolutePath, password, "fixture-projection").use { vault ->
                val commands = mutableListOf<MdbxWriteCommand>(MdbxWriteCommand.CreateProject(project, "Projection folder"))
                for (algorithm in listOf(-257, -37, -8)) {
                    val pair = KeyPairGenerator.getInstance(if (algorithm == -8) "Ed25519" else "RSA")
                        .apply { if (algorithm != -8) initialize(2048) }.generateKeyPair()
                    val credential = UUID.randomUUID().toString()
                    expected[credential] = algorithm
                    database.passkeyDao().insert(takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context,
                        PasskeyEntry(credentialId = credential, rpId = "example.test", rpName = "Projection key",
                            userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture",
                            publicKeyAlgorithm = algorithm, publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
                            privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded), backupEligible = false, backupState = false)))
                    val payload = JSONObject().put("kind", "passkey").put("credential_id", credential)
                        .put("rp_id", "example.test").put("user_id", "Zml4dHVyZQ==")
                        .put("public_key", requireNotNull(takagi.ru.monica.credentialexchange.CxfPasskeyMaterial.decode(pair.private.encoded, algorithm)).cosePublicKey)
                        .put("private_key_alias", Base64.getEncoder().encodeToString(pair.private.encoded))
                    commands += MdbxWriteCommand.CreateEntry(UUID.randomUUID().toString(), project, "passkey", "Projection key", payload.toString())
                }
                vault.executeWriteOperation(UUID.randomUUID().toString(), "fixture-projection", commands)
            }
            val model = takagi.ru.monica.viewmodel.MdbxViewModel(context, database.localMdbxDatabaseDao(),
                database.mdbxRemoteSourceDao(), database.passwordEntryDao(), database.secureItemDao(), database.passkeyDao(),
                database.attachmentDao(), database.customFieldDao(), SecurityManager(context), backgroundOnly = true)
            models.put("fixture", model)
            model.refreshDeviceSyncProjection(id)
            val projected = database.passkeyDao().getAllPasskeysSync()
            assertEquals(3, projected.size)
            for (item in projected) {
                assertEquals(expected[item.credentialId], item.publicKeyAlgorithm)
                assertEquals(false, item.backupEligible)
                assertEquals(false, item.backupState)
            }
        } finally {
            models.clear()
            ReflectionHelpers.setStaticField(PasswordDatabase::class.java, "INSTANCE", previous)
            takagi.ru.monica.repository.Mdbx2NativeReadSessions.clear()
            database.close(); SessionManager.markLocked(); kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
}
