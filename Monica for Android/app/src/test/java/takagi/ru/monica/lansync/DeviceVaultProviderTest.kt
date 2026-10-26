package takagi.ru.monica.lansync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.BeforeClass
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.security.SecurePreferencesStore
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Uses real Room and the shared Rust JNI replica, with a deterministic OS-key substitute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceVaultProviderTest {
    companion object {
        @JvmStatic @BeforeClass fun loadNative() {
            val name = System.mapLibraryName("monica_rust_jni")
            val path = listOf(File("../rust-jni/target/release/$name"),File("rust-jni/target/release/$name")).firstOrNull { it.isFile }
            org.junit.Assume.assumeTrue("Build the host JNI to run the Room/native integration fixture",path != null)
            requireNotNull(path)
            require(path.isFile) { "Build the host JNI for the provider integration test" }
            System.setProperty("monica.lan.native.path",path.absolutePath)
        }
    }
    private val databases = mutableListOf<PasswordDatabase>()
    @Before fun dispatchers() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun close() { databases.forEach { it.close() }; SessionManager.markLocked(); Dispatchers.resetMain() }

    private fun vault(synchronousInvalidation: Boolean = false): Pair<PasswordDatabase, DevicePasswordSyncProvider> {
        val context = RuntimeEnvironment.getApplication()
        val builder = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries()
        if (synchronousInvalidation) builder.setQueryExecutor { it.run() }
        val database = builder.build()
        databases += database
        val security = SecurityManager(context)
        val preferences = DeviceSyncPreferenceStore(context,security,"provider-${UUID.randomUUID()}").also { it.save(DeviceSyncPreferences()) }
        return database to DevicePasswordSyncProvider(context,security,preferences,database,
            File(context.filesDir,"fixture-${UUID.randomUUID()}.replica"))
    }

    @Test fun receiving_deletions_waits_for_consent_and_keep_accepts_only_live_changes() = runBlocking { withTimeout(90000) {
        SessionManager.markUnlocked()
        val (a, source) = vault(true); val (b, keep) = vault(true); val (c, include) = vault(true); val (d, forward) = vault(true)
        fun directory() = File(RuntimeEnvironment.getApplication().filesDir, "deletion-consent-${UUID.randomUUID()}")
        fun peer(out: DevicePasswordExport) = JSONObject().put("pairing", JSONObject().put("content_key", out.contentKey))
        try {
            val removed = a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Delete after consent",website="",username="",password="synthetic"))
            val updated = a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Update normally",website="",username="",password="old"))
            val trashed = a.secureItemDao().insertItem(SecureItem(itemType=ItemType.NOTE,title="Trash after consent",itemData="{\"content\":\"kept\"}"))
            val initial = source.prepare(directory())
            keep.apply(initial.directory, peer(initial)); include.apply(initial.directory, peer(initial))
            a.passwordEntryDao().deletePasswordEntryById(removed)
            a.passwordEntryDao().updatePasswordEntry(a.passwordEntryDao().getPasswordEntryById(updated)!!.copy(password="new"))
            a.secureItemDao().updateItem(a.secureItemDao().getItemById(trashed)!!.copy(isDeleted=true,deletedAt=java.util.Date()))
            a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="New item",website="",username="",password="new synthetic"))
            val outgoing = source.prepare(directory())
            val awaiting = kotlinx.coroutines.CompletableDeferred<Int>()
            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val receive = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
                keep.apply(outgoing.directory, peer(outgoing)) { count -> awaiting.complete(count); answer.await() }
            }
            assertEquals(2, awaiting.await())
            assertEquals(2, b.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertEquals("old", b.passwordEntryDao().getDeviceSyncLocalPasswords().single { it.title=="Update normally" }.password)
            assertFalse(b.secureItemDao().getDeviceSyncLocalItems().single().isDeleted)
            answer.complete(false); receive.await()
            assertEquals(3, b.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertEquals("new", b.passwordEntryDao().getDeviceSyncLocalPasswords().single { it.title=="Update normally" }.password)
            assertFalse(b.secureItemDao().getDeviceSyncLocalItems().single().isDeleted)
            var prompts = 0
            include.apply(outgoing.directory, peer(outgoing)) { count -> prompts++; assertEquals(2,count); true }
            assertEquals(1,prompts)
            assertEquals(2,c.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertTrue(c.secureItemDao().getDeviceSyncLocalItems().single().isDeleted)
            keep.pause() // Retention must survive reopening and forwarding to another device.
            keep.apply(outgoing.directory, peer(outgoing)) { error("A skipped deletion version must not prompt again") }
            val forwarded = keep.prepare(directory())
            forward.apply(forwarded.directory, peer(forwarded)) { error("Kept content must not forward deletions") }
            assertEquals(3,d.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertFalse(d.secureItemDao().getDeviceSyncLocalItems().single().isDeleted)
            include.apply(outgoing.directory, peer(outgoing)) { error("Identical applied deletions must not prompt again") }
            assertEquals(2,c.passwordEntryDao().getDeviceSyncLocalPasswords().size)
        } finally { source.pause(); keep.pause(); include.pause(); forward.pause() }
    } }

    @Test fun cancelling_receive_review_does_not_merge_or_delete_and_can_be_retried() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked(); val (a, source) = vault(true); val (b, target) = vault(true)
        fun directory() = File(RuntimeEnvironment.getApplication().filesDir,"cancel-review-${UUID.randomUUID()}")
        fun peer(out: DevicePasswordExport) = JSONObject().put("pairing",JSONObject().put("content_key",out.contentKey))
        try {
            val id=a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Retained until confirmation",website="",username="",password="synthetic"))
            val initial=source.prepare(directory()); target.apply(initial.directory,peer(initial))
            a.passwordEntryDao().deletePasswordEntryById(id)
            val deleted=source.prepare(directory()); val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
            val work=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
                target.apply(deleted.directory,peer(deleted)) { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            }
            entered.await(); work.cancelAndJoin()
            assertEquals(1,b.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            target.pause()
            var reviews=0
            target.apply(deleted.directory,peer(deleted)) { count -> reviews++; assertEquals(1,count); true }
            assertEquals(1,reviews); assertTrue(b.passwordEntryDao().getDeviceSyncLocalPasswords().isEmpty())
        } finally { source.pause(); target.pause() }
    } }

    @Test fun metadata_attachment_bytes_survive_room_adoption_and_forwarding() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (sourceDb, source) = vault(); val (targetDb, target) = vault(); val (_, third) = vault()
        val context = RuntimeEnvironment.getApplication()
        val security = SecurityManager(context)
        val payloads = listOf(ByteArray(280_017) { (it % 251).toByte() }, ByteArray(180_003) { (it % 239).toByte() })
        val descriptors = payloads.mapIndexed { index, bytes -> JSONObject()
            .put("id", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("attachment_id", "portable-metadata-$index").put("file_name", "same-name.bin")
            .put("media_type", "application/octet-stream").put("size", bytes.size) }
        try {
            val metadata = DeviceRoomVaultMetadata(sourceDb, security) { JSONObject() }
            metadata.applyRootMetadata(UUID.randomUUID().toString(), null, null, false, JSONObject()
                .put("entry_type", "vault-metadata").put("title", "Root").put("folder", "")
                .put("payload", JSONObject().put("tags", org.json.JSONArray()))
                .put("assets", org.json.JSONArray().put(descriptors[0])))
            val category = sourceDb.categoryDao().insert(Category(name = "Folder assets"))
            metadata.save("folder:$category", JSONObject().put("assets", org.json.JSONArray().put(descriptors[1])))
            val outgoing = source.prepare(File(context.filesDir, "metadata-source-${UUID.randomUUID()}"))
            // Emulate a browser sender whose authenticated archive includes the actual files.
            val transfer = DeviceAssetTransfer(outgoing.directory, outgoing.contentKey)
            descriptors.forEachIndexed { index, descriptor -> transfer.append(descriptor.getString("id"), payloads[index]) }
            target.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            assertEquals(1, targetDb.categoryDao().getDeviceSyncCategories().size)
            val forwarded = target.prepare(File(context.filesDir, "metadata-forward-${UUID.randomUUID()}"))
            val forwardedAssets = DeviceAssetTransfer(forwarded.directory, forwarded.contentKey)
            descriptors.forEachIndexed { index, descriptor ->
                assertTrue("Missing metadata attachment bytes $index", forwardedAssets.contains(descriptor.getString("id")))
                assertArrayEquals(payloads[index], forwardedAssets.read(descriptor))
            }
            third.apply(forwarded.directory, JSONObject().put("pairing", JSONObject().put("content_key", forwarded.contentKey)))
            val last = third.prepare(File(context.filesDir, "metadata-third-${UUID.randomUUID()}"))
            descriptors.forEachIndexed { index, descriptor -> assertArrayEquals(payloads[index], DeviceAssetTransfer(last.directory,last.contentKey).read(descriptor)) }
        } finally { source.pause(); target.pause(); third.pause() }
    } }

    @Test fun opaque_extension_paths_do_not_become_replica_references() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault(); val (received, receiver) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val security = SecurityManager(context)
            val id = database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Opaque extensions",
                website = "example.test", username = "fixture", password = "synthetic-password"))
            val extension = JSONObject().put("path", org.json.JSONArray().put(JSONObject()
                .put("id", "service/account:42").put("parent", "folder/user")))
                .put("bound_password_entry_id", "external:42")
            DeviceRoomVaultMetadata(database, security) { JSONObject() }.save("local:$id",
                JSONObject().put("extensions", JSONObject().put("custom_data", extension)))
            val outgoing = provider.prepare(File(context.filesDir, "opaque-${UUID.randomUUID()}"))
            receiver.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            val entry = received.passwordEntryDao().getDeviceSyncLocalPasswords().single()
            val saved = DeviceRoomVaultMetadata(received, security) { JSONObject() }.supplement("local:${entry.id}", JSONObject())
            val retained = saved.getJSONObject("extensions").getJSONObject("custom_data")
            assertEquals("external:42", retained.getString("bound_password_entry_id"))
            assertEquals("service/account:42", retained.getJSONArray("path").getJSONObject(0).getString("id"))
            assertEquals("folder/user", retained.getJSONArray("path").getJSONObject(0).getString("parent"))
            assertEquals("synthetic-password", security.decryptDataIfMonicaCiphertext(entry.password))
        } finally { provider.pause(); receiver.pause() }
    } }

    @Test fun local_passkey_with_stale_mdbx_folder_does_not_abort_preparation() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault()
        val (receivedDatabase, receiver) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val folderId = database.categoryDao().insert(Category(name = "Foreign native folder", mdbxDatabaseId = 42L))
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val entry = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Legacy local folder",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture", categoryId = folderId,
                publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            database.passkeyDao().insert(entry)
            val outgoing = provider.prepare(File(context.filesDir, "stale-folder-${UUID.randomUUID()}"))
            receiver.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            assertNull(receivedDatabase.passkeyDao().getAllPasskeysSync().single().categoryId)
            assertEquals(folderId, database.passkeyDao().getAllPasskeysSync().single().categoryId)
        } finally { provider.pause(); receiver.pause() }
    } }

    @Test fun locally_created_spki_passkey_can_prepare() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val entry = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Local registration",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture",
                publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            assertTrue(takagi.ru.monica.passkey.PasskeyPrivateKeyStore.isProtectedReference(entry.privateKeyAlias))
            database.passkeyDao().insert(entry)
            val outgoing = provider.prepare(File(context.filesDir, "spki-${UUID.randomUUID()}"))
            assertTrue(outgoing.contentKey.isNotEmpty())
            assertTrue(outgoing.passkeyIssues.isEmpty())
            assertEquals(entry.publicKey, database.passkeyDao().getAllPasskeysSync().single().publicKey)
        } finally { provider.pause() }
    } }

    @Test fun legacy_passkey_without_cached_public_key_can_prepare_and_sign_on_receiver() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault()
        val (receivedDatabase, receiver) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val entry = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Legacy registration",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture", publicKey = "",
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            database.passkeyDao().insert(entry)
            val outgoing = provider.prepare(File(context.filesDir, "legacy-${UUID.randomUUID()}"))
            receiver.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            val received = receivedDatabase.passkeyDao().getAllPasskeysSync().single()
            assertTrue(received.publicKey.isNotBlank())
            assertEquals("", database.passkeyDao().getAllPasskeysSync().single().publicKey)
            val privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(takagi.ru.monica.passkey.PasskeyPrivateKeyStore.resolve(context, received.privateKeyAlias))))
            val challenge = "synthetic legacy passkey".toByteArray()
            val signature = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(challenge) }.sign()
            assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signature))
        } finally { provider.pause(); receiver.pause() }
    } }

    @Test fun mismatched_local_passkey_reports_record_and_reason() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val generator = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }
            val original = generator.generateKeyPair()
            val other = generator.generateKeyPair()
            database.passkeyDao().insert(PasskeyEntry(credentialId = UUID.randomUUID().toString(),
                rpId = "private.example.test", rpName = "Private account title", userId = "Zml4dHVyZQ==",
                userName = "private-user", userDisplayName = "Private user", publicKey = Base64.getEncoder().encodeToString(other.public.encoded),
                privateKeyAlias = Base64.getEncoder().encodeToString(original.private.encoded)))
            val id = database.passkeyDao().getAllPasskeysSync().single().id
            val failure = try {
                provider.prepare(File(context.filesDir, "mismatch-${UUID.randomUUID()}"))
                fail("Mismatched keys must remain rejected")
                error("unreachable")
            } catch (failure: LanSyncException) { failure }
            assertEquals("invalid_passkey_material", failure.code)
            assertEquals("passkey_room_${id}_public_key_mismatch", failure.operation)
            assertFalse(failure.message.orEmpty().contains("private.example.test"))
            assertFalse(failure.message.orEmpty().contains("private-user"))
        } finally { provider.pause() }
    } }

    @Test fun copied_protected_reference_reports_missing_key_without_replacing_it() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val entry = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Copied reference",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture",
                publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            database.passkeyDao().insert(entry)
            val id = database.passkeyDao().getAllPasskeysSync().single().id
            SecurityManager(context).removeProtectedString(entry.privateKeyAlias.substringAfter(':'))
            val failure = try {
                provider.prepare(File(context.filesDir, "copied-${UUID.randomUUID()}"))
                fail("A missing protected key must not be claimed as synchronized")
                error("unreachable")
            } catch (failure: LanSyncException) { failure }
            assertEquals("passkey_key_unavailable", failure.code)
            assertEquals("passkey_room_${id}_protected_key_missing", failure.operation)
            assertEquals(entry.privateKeyAlias, database.passkeyDao().getAllPasskeysSync().single().privateKeyAlias)
            SecurityManager(context).putProtectedString(entry.privateKeyAlias.substringAfter(':'), "synthetic-invalid-key")
            val corrupt = try {
                provider.prepare(File(context.filesDir, "corrupt-${UUID.randomUUID()}"))
                fail("Invalid protected material must not become a successful reference")
                error("unreachable")
            } catch (failure: LanSyncException) { failure }
            assertEquals("invalid_passkey_material", corrupt.code)
            assertEquals("passkey_room_${id}_protected_key_invalid", corrupt.operation)
            assertEquals(entry.privateKeyAlias, database.passkeyDao().getAllPasskeysSync().single().privateKeyAlias)
        } finally { provider.pause() }
    } }

    @Test fun reference_update_in_cose_keeps_the_local_spki_private_key() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (a, first) = vault(); val (b, second) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val original = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Original SPKI key",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture",
                publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            a.passkeyDao().insert(original)
            val outgoing = first.prepare(File(context.filesDir, "reference-first-${UUID.randomUUID()}"))
            second.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            val credential = b.passkeyDao().getAllPasskeysSync().single()
            val cose = requireNotNull(takagi.ru.monica.credentialexchange.CxfPasskeyMaterial.decode(pair.private.encoded, -7)).cosePublicKey
            b.passkeyDao().update(credential.copy(publicKey = cose, privateKeyAlias = "", notes = "Reference metadata update", isBackedUp = false))
            val back = second.prepare(File(context.filesDir, "reference-back-${UUID.randomUUID()}"))
            first.apply(back.directory, JSONObject().put("pairing", JSONObject().put("content_key", back.contentKey)))
            val updated = a.passkeyDao().getAllPasskeysSync().single()
            assertEquals("Reference metadata update", updated.notes)
            assertEquals(original.privateKeyAlias, updated.privateKeyAlias)
            assertEquals(original.syncStatus, updated.syncStatus)
            assertEquals(Base64.getEncoder().encodeToString(pair.private.encoded),
                takagi.ru.monica.passkey.PasskeyPrivateKeyStore.resolve(context, updated.privateKeyAlias))
        } finally { first.pause(); second.pause() }
    } }

    @Test fun external_passkey_copy_does_not_conflict_and_keeps_its_key_after_remote_deletion() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (a, first) = vault(synchronousInvalidation = true); val (b, second) = vault(synchronousInvalidation = true)
        try {
            val context = RuntimeEnvironment.getApplication()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val privateKey = Base64.getEncoder().encodeToString(pair.private.encoded)
            val original = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Shared credential",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture",
                publicKey = Base64.getEncoder().encodeToString(pair.public.encoded), privateKeyAlias = privateKey))
            a.passkeyDao().insert(original)
            b.passkeyDao().insert(original.copy(keepassDatabaseId = 42, notes = "External copy"))
            suspend fun sync() {
                val outgoing = first.prepare(File(context.filesDir, "copy-sync-${UUID.randomUUID()}"))
                second.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            }
            sync()
            assertEquals(2, b.passkeyDao().getAllPasskeysSync().size)
            val source = a.passkeyDao().getAllPasskeysSync().single()
            a.passkeyDao().update(source.copy(notes = "Updated local credential"))
            sync()
            assertEquals("Updated local credential", b.passkeyDao().getAllPasskeysSync().single { it.keepassDatabaseId == null }.notes)
            assertEquals("External copy", b.passkeyDao().getAllPasskeysSync().single { it.keepassDatabaseId == 42L }.notes)
            a.passkeyDao().deleteByRecordId(source.id)
            sync()
            val remaining = b.passkeyDao().getAllPasskeysSync().single()
            assertEquals(42L, requireNotNull(remaining.keepassDatabaseId))
            assertEquals(privateKey, takagi.ru.monica.passkey.PasskeyPrivateKeyStore.resolve(context, remaining.privateKeyAlias))
        } finally { first.pause(); second.pause() }
    } }

    @Test fun coordinator_blocks_first_send_for_restricted_room_keys_but_receive_only_skips_local_export() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (database, provider) = vault(synchronousInvalidation = true)
        val (received, receiver) = vault()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = ReflectionHelpers.callConstructor(DeviceSyncCoordinator::class.java,
            ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(coordinator, "dataProvider", lazyOf(provider))
        val settings = DeviceSyncPreferences(peers = mutableListOf(SavedSyncPeer("peer", "Fixture", "pair", "unused")))
        ReflectionHelpers.setField(coordinator, "preferences", settings)
        coordinator.setAppVisible(true)
        ReflectionHelpers.setField(coordinator, "presence", object : DeviceSyncPresence {
            override val online = kotlinx.coroutines.flow.MutableStateFlow(setOf("peer"))
            override val available = true
            override fun start(deviceId: String, peers: Set<String>) = Unit
            override fun stop() = Unit
        })
        suspend fun invoke(name: String): Any? = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = DeviceSyncCoordinator::class.java.getDeclaredMethod(name, kotlin.coroutines.Continuation::class.java)
            method.isAccessible = true
            method.invoke(coordinator, continuation)
        }
        try {
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val entry = takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
                credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Local registration",
                userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture", signCount = 7,
                publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
                privateKeyAlias = Base64.getEncoder().encodeToString(pair.private.encoded)))
            database.passkeyDao().insert(entry)
            database.passkeyDao().insert(entry.copy(credentialId = "external", keepassDatabaseId = 9, backupEligible = false))
            database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Continue fixture",website="",username="",password="synthetic"))
            invoke("maintainConnections")
            val consent = ReflectionHelpers.getField<DevicePasskeySyncConsent>(coordinator, "passkeyConsent")
            val review = requireNotNull(consent.pending)
            assertEquals(1, review.issues.size) // External vault copies stay outside nearby sync.
            assertEquals(takagi.ru.monica.passkey.PasskeyPortability.COUNTER_HISTORY, review.issues.single().portability)
            assertTrue(ReflectionHelpers.getField<Map<*, *>>(coordinator, "transfers").isEmpty())
            assertEquals("passkey_review", ReflectionHelpers.getField<String>(coordinator, "phase"))
            consent.confirm(review)
            val continued = invoke("currentSnapshot") as DevicePasswordExport
            receiver.apply(continued.directory,JSONObject().put("pairing",JSONObject().put("content_key",continued.contentKey)))
            assertTrue(received.passkeyDao().getAllPasskeysSync().isEmpty())
            assertEquals(1,received.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            settings.mode = DeviceSyncMode.RECEIVE_ONLY
            val receiving = invoke("currentSnapshot") as DevicePasswordExport
            assertTrue(receiving.passkeyIssues.isEmpty())
            assertEquals("", receiving.contentKey)
            assertEquals(7L, database.passkeyDao().getAllPasskeysSync().first { it.keepassDatabaseId == null }.signCount)
        } finally {
            provider.pause(); receiver.pause()
            val root = ReflectionHelpers.getField<File>(coordinator, "root")
            assertEquals(File(context.cacheDir, "device-sync").canonicalFile, root.canonicalFile.parentFile)
            root.deleteRecursively()
        }
    } }

    @Test fun standalone_items_history_links_and_trash_survive_real_native_exchange() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (a, first) = vault(); val (b, second) = vault()
        try {
            val category = a.categoryDao().insert(Category(name="Synthetic folder"))
            val data = """{"secret":"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ","otpType":"HOTP","counter":17,"pin":"synthetic","steamIdentitySecret":"synthetic-recovery"}"""
            val note = a.secureItemDao().insertItem(SecureItem(itemType=ItemType.NOTE,title="Fixture note",itemData="{\"content\":\"synthetic\"}",categoryId=category))
            val password = a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Fixture login",website="example.test",username="fixture",password="synthetic-password",categoryId=category,boundNoteId=note))
            a.passwordHistoryDao().insert(PasswordHistoryEntry(entryId=password,password="synthetic-old"))
            a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Trash login",website="",username="",password="synthetic-trash",categoryId=category,isDeleted=true))
            val context = RuntimeEnvironment.getApplication()
            val material = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            // Registration stores X.509 SPKI, whereas imported credentials can store COSE.
            a.passkeyDao().insert(PasskeyEntry(credentialId=UUID.randomUUID().toString(),rpId="example.test",rpName="Fixture passkey",
                userId=Base64.getEncoder().encodeToString("fixture".toByteArray()),userName="fixture",userDisplayName="Fixture",publicKey=Base64.getEncoder().encodeToString(material.public.encoded),
                privateKeyAlias=Base64.getEncoder().encodeToString(material.private.encoded),boundPasswordId=password,categoryId=category,
                backupEligible=false,backupState=false))
            val binary = ByteArray(700017).also { java.security.SecureRandom().nextBytes(it) }
            val storage = takagi.ru.monica.attachments.storage.AttachmentStorage(context)
            val keyVault = takagi.ru.monica.attachments.storage.AttachmentKeyVault(SecurityManager(context))
            val blob = storage.writeEncrypted(binary.inputStream())
            for(index in 0..1) a.attachmentDao().insert(takagi.ru.monica.attachments.model.Attachment(parentPasswordId=password,source="LOCAL",
                fileName="duplicate.bin",mimeType="application/octet-stream",sizeBytes=blob.sizeBytes,sha256Hex=blob.sha256Hex,
                wrappedCek=keyVault.wrap(blob.cek),localPath=blob.relativePath,downloadState="DOWNLOADED",createdAt=1L,updatedAt=1L))
            blob.cek.fill(0)
            val iconBytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5n8AAAAASUVORK5CYII=")
            File(takagi.ru.monica.ui.icons.PasswordCustomIconStore.getIconDir(context),"fixture-icon.png").writeBytes(iconBytes)
            a.passwordEntryDao().updatePasswordEntry(a.passwordEntryDao().getPasswordEntryById(password)!!.copy(
                notes=blob.relativePath,customIconType="UPLOADED",customIconValue="fixture-icon.png",passkeyBindings="[{\"credentialId\":\"synthetic-badge\"}]"))
            for(type in listOf(ItemType.TOTP,ItemType.BANK_CARD,ItemType.DOCUMENT,ItemType.BILLING_ADDRESS,ItemType.PAYMENT_ACCOUNT))
                a.secureItemDao().insertItem(SecureItem(itemType=type,title=type.name,itemData=data,categoryId=category))
            val directory = File(RuntimeEnvironment.getApplication().filesDir,"out-${UUID.randomUUID()}")
            val outgoing = first.prepare(directory)
            val peer = JSONObject().put("pairing",JSONObject().put("content_key",outgoing.contentKey))
            val progress = mutableListOf<DeviceSyncProgress>(); second.onProgress = { progress += it }
            assertTrue(second.apply(directory,peer) > 0)
            val received = b.passwordEntryDao().getDeviceSyncLocalPasswords().single { !it.isDeleted }
            assertEquals("synthetic-trash",b.passwordEntryDao().getDeviceSyncLocalPasswords().single { it.isDeleted }.password)
            assertEquals("synthetic-password",received.password)
            assertEquals(blob.relativePath,received.notes)
            assertEquals("[{\"credentialId\":\"synthetic-badge\"}]",received.passkeyBindings)
            assertArrayEquals(iconBytes,takagi.ru.monica.ui.icons.PasswordCustomIconStore.resolveIconFile(context,received.customIconValue)!!.readBytes())
            assertNotNull(received.boundNoteId)
            assertEquals("Fixture note",b.secureItemDao().getItemById(received.boundNoteId!!)!!.title)
            assertEquals("synthetic-old",b.passwordHistoryDao().getHistoryByEntryIdSync(received.id).single().password)
            val items = b.secureItemDao().getDeviceSyncLocalItems()
            assertEquals(6,items.size)
            assertEquals(17,JSONObject(items.single { it.itemType==ItemType.TOTP }.itemData).getInt("counter"))
            val credential = b.passkeyDao().getAllPasskeysSync().single()
            assertEquals(false, credential.backupEligible)
            assertEquals(false, credential.backupState)
            assertEquals(received.id,credential.boundPasswordId)
            assertEquals(received.categoryId,credential.categoryId)
            assertTrue(takagi.ru.monica.passkey.PasskeyPrivateKeyStore.isProtectedReference(credential.privateKeyAlias))
            val imported = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(
                takagi.ru.monica.passkey.PasskeyPrivateKeyStore.resolve(context,credential.privateKeyAlias))))
            val challenge = "synthetic-challenge".toByteArray()
            val signature = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(imported); update(challenge) }.sign()
            assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply { initVerify(material.public); update(challenge) }.verify(signature))
            assertEquals(2,b.attachmentDao().getAllByParent(received.id).size)
            val attachment = b.attachmentDao().getAllByParent(received.id).first()
            val cek = keyVault.unwrap(attachment.wrappedCek!!)
            try { assertArrayEquals(binary,storage.openDecryptedStream(attachment.localPath!!,cek).use { it.readBytes() }) } finally { cek.fill(0) }
            assertTrue(progress.any { it.stage=="applying" && it.completed > 0 })
            val revision = second.revision()
            assertEquals(revision,second.revision())
            b.passkeyDao().update(credential.copy(notes="Updated on receiver",boundPasswordId=null,categoryId=null))
            b.passwordEntryDao().updatePasswordEntry(received.copy(boundNoteId=null))
            val back = second.prepare(File(context.filesDir,"back-${UUID.randomUUID()}"))
            first.apply(back.directory,JSONObject().put("pairing",JSONObject().put("content_key",back.contentKey)))
            assertEquals("Updated on receiver",a.passkeyDao().getAllPasskeysSync().single().notes)
            assertNull(a.passkeyDao().getAllPasskeysSync().single().boundPasswordId)
            assertNull(a.passwordEntryDao().getPasswordEntryById(password)!!.boundNoteId)
            assertEquals(2,a.attachmentDao().getAllByParent(password).size)
        } finally { first.pause(); second.pause() }
    } }

    @Test fun nonzero_passkeys_are_omitted_without_deleting_existing_copies_even_after_reopening_replica() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked()
        val (a, first) = vault(synchronousInvalidation=true); val (b, second) = vault(); val (c, third) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val original = PasskeyEntry(credentialId=UUID.randomUUID().toString(), rpId="example.test", rpName="Counter fixture",
                userId="Zml4dHVyZQ==", userName="fixture", userDisplayName="Fixture", notes="Before use", backupEligible=true, backupState=false,
                publicKey=Base64.getEncoder().encodeToString(pair.public.encoded), privateKeyAlias=Base64.getEncoder().encodeToString(pair.private.encoded))
            a.passkeyDao().insert(original)
            val id = a.passkeyDao().getAllPasskeysSync().single().id
            suspend fun send(target: DevicePasswordSyncProvider) {
                val out = first.prepare(File(context.filesDir,"counter-${UUID.randomUUID()}"))
                target.apply(out.directory, JSONObject().put("pairing",JSONObject().put("content_key",out.contentKey)))
            }
            send(second)
            assertEquals(1, b.passkeyDao().getAllPasskeysSync().size)
            a.passkeyDao().update(a.passkeyDao().getPasskeyByRecordId(id)!!.copy(signCount=7, notes="After use"))
            a.passkeyDao().insert(original.copy(credentialId=UUID.randomUUID().toString(), signCount=11, backupEligible=false))
            a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Still synced",website="",username="",password="synthetic"))
            val issues = first.currentPasskeyIssues()
            assertEquals(2, issues.size); assertTrue(issues.all { it.skipForCounter })
            send(second); send(third)
            first.pause() // Reopen the encrypted replica and export its cached records again.
            send(second); send(third)
            val existing = b.passkeyDao().getAllPasskeysSync().single()
            assertEquals("Before use", existing.notes); assertEquals(0L, existing.signCount)
            assertTrue(c.passkeyDao().getAllPasskeysSync().isEmpty())
            assertEquals(1, b.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertEquals(1, c.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertEquals(setOf(7L,11L), a.passkeyDao().getAllPasskeysSync().map { it.signCount }.toSet())
            assertEquals(original.privateKeyAlias, a.passkeyDao().getPasskeyByRecordId(id)!!.privateKeyAlias)
        } finally { first.pause(); second.pause(); third.pause() }
    } }

    @Test fun mixed_bulk_sync_preserves_new_trashed_secure_items_and_retries_without_duplicates() = runBlocking { withTimeout(120000) {
        SessionManager.markUnlocked(); val (a, first) = vault(); val (b, second) = vault()
        try {
            repeat(610) { index ->
                a.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Bulk $index", website="example.test", username="fixture", password="synthetic-$index"))
            }
            val types = listOf(ItemType.NOTE, ItemType.TOTP, ItemType.BANK_CARD, ItemType.DOCUMENT, ItemType.BILLING_ADDRESS, ItemType.PAYMENT_ACCOUNT)
            types.forEach { type -> a.secureItemDao().insertItem(SecureItem(itemType=type, title="Trash $type", itemData="{\"content\":\"synthetic trash\"}", isDeleted=true)) }
            val outgoing = first.prepare(File(RuntimeEnvironment.getApplication().filesDir,"trash-bulk-${UUID.randomUUID()}"))
            val peer = JSONObject().put("pairing", JSONObject().put("content_key",outgoing.contentKey))
            assertEquals(616, second.apply(outgoing.directory, peer))
            assertEquals(610, b.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            val trash = b.secureItemDao().getDeviceSyncLocalItems()
            assertEquals(types.toSet(), trash.map { it.itemType }.toSet())
            assertTrue(trash.all { it.isDeleted && JSONObject(it.itemData).getString("content") == "synthetic trash" })
            second.apply(outgoing.directory, peer)
            assertEquals(610, b.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertEquals(6, b.secureItemDao().getDeviceSyncLocalItems().size)
        } finally { first.pause(); second.pause() }
    } }

    @Test fun progress_keeps_the_complete_total_across_multiple_native_batches() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked(); val (a,first) = vault(); val (_,second) = vault()
        try {
            for(index in 0 until 270) a.secureItemDao().insertItem(SecureItem(itemType=ItemType.NOTE,title="Batch $index",itemData="{\"content\":\"synthetic $index\"}"))
            val outgoing = first.prepare(File(RuntimeEnvironment.getApplication().filesDir,"batch-${UUID.randomUUID()}"))
            val progress = mutableListOf<DeviceSyncProgress>(); second.onProgress = { if(it.stage == "applying") progress += it }
            assertEquals(270,second.apply(outgoing.directory,JSONObject().put("pairing",JSONObject().put("content_key",outgoing.contentKey))))
            assertTrue(progress.all { it.total == 270L })
            assertTrue(progress.any { it.completed == 256L && it.completed < it.total })
            assertEquals(270L,progress.last().completed)
        } finally { first.pause(); second.pause() }
    } }

    @Test fun received_notes_keep_plain_item_data_while_wallet_items_stay_sealed() = runBlocking { withTimeout(60000) {
        SessionManager.markUnlocked(); SecurityShadow.sealLegacyCompat = true
        val (a, first) = vault(); val (b, second) = vault()
        try {
            val context = RuntimeEnvironment.getApplication()
            a.secureItemDao().insertItem(SecureItem(itemType=ItemType.NOTE,title="Plain note",itemData="{\"content\":\"synthetic note\",\"tags\":[],\"isMarkdown\":false}"))
            a.secureItemDao().insertItem(SecureItem(itemType=ItemType.BANK_CARD,title="Sealed card",
                itemData=SecurityManager(context).encryptDataLegacyCompat("{\"cardNumber\":\"4111111111111111\"}")))
            val outgoing = first.prepare(File(context.filesDir,"plain-notes-${UUID.randomUUID()}"))
            assertTrue(second.apply(outgoing.directory,JSONObject().put("pairing",JSONObject().put("content_key",outgoing.contentKey))) > 0)
            val items = b.secureItemDao().getDeviceSyncLocalItems()
            // Note readers decode itemData directly, so a sealed note would show its ciphertext.
            assertEquals("synthetic note",JSONObject(items.single { it.itemType==ItemType.NOTE }.itemData).getString("content"))
            val card = items.single { it.itemType==ItemType.BANK_CARD }.itemData
            assertTrue(card.startsWith("fixture|"))
            assertEquals("4111111111111111",JSONObject(SecurityManager(context).decryptData(card)).getString("cardNumber"))
        } finally { SecurityShadow.sealLegacyCompat = false; first.pause(); second.pause() }
    } }

    @Implements(value=SecurePreferencesStore::class,isInAndroidSdk=false)
    class SecureStoreShadow {
        companion object {
            @JvmStatic @Implementation fun open(context: Context,name: String,alias: String): SecurePreferencesStore.Opened =
                SecurePreferencesStore.Opened(ReflectionHelpers.callConstructor(MasterKey::class.java,
                    ClassParameter.from(String::class.java,"fixture-master"), ClassParameter.from(Any::class.java,null)),
                    context.getSharedPreferences("fixture-$name",Context.MODE_PRIVATE))
        }
    }
    @Implements(value=SecurityManager::class,isInAndroidSdk=false)
    class SecurityShadow {
        companion object {
            private val protected = mutableMapOf<String,String>()
            // Opt-in model of a master-password vault, where the compatibility path also seals.
            var sealLegacyCompat = false
            @JvmStatic @Resetter fun reset() { protected.clear(); sealLegacyCompat = false }
        }
        @Implementation fun encryptData(value: String): String = seal(value)
        @Implementation fun encryptDataLegacyCompat(value: String): String = if(sealLegacyCompat) seal(value) else value
        @Implementation fun decryptData(value: String): String = if(value.startsWith("fixture|")) open(value) else value
        @Implementation fun decryptDataIfMonicaCiphertext(value: String): String = decryptData(value)
        @Implementation fun putProtectedString(key: String,value: String?) { if(value==null) protected.remove(key) else protected[key]=seal(value) }
        @Implementation fun getProtectedString(key: String): String? = protected[key]?.let(::open)
        @Implementation fun removeProtectedString(key: String) { protected.remove(key) }
        private fun seal(value: String): String {
            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(ByteArray(32){9},"AES"),GCMParameterSpec(128,nonce))
            return "fixture|"+Base64.getEncoder().encodeToString(nonce+cipher.doFinal(value.toByteArray()))
        }
        private fun open(value: String): String {
            val bytes=Base64.getDecoder().decode(value.removePrefix("fixture|")); val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(ByteArray(32){9},"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)))
        }
    }
}

// Existing fixtures explicitly grant deletion consent; production callers must supply a decision.
internal suspend fun DevicePasswordSyncProvider.apply(directory: File, peer: JSONObject): Int = apply(directory, peer) { true }
