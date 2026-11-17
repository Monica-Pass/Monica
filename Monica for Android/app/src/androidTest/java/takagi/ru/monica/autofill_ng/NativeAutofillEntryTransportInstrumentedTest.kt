package takagi.ru.monica.autofill_ng

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry

/** Framework parcel boundaries and real generated DAOs; never opens a user's vault. */
@RunWith(AndroidJUnit4::class)
class NativeAutofillEntryTransportInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uuid = "c054e4ba-02fd-42f3-895e-b445792ac47e"

    @Test fun callbackKeePassAndMdbxSelectionsSurviveFrameworkParcel() {
        NativeAutofillStorage.entries.forEach { storage ->
            val expected = args(storage)
            val transported = callbackIntent(expected)
            val actual = requireNotNull(readCallback(transported))
            assertEquals(expected, actual)
            assertEquals(expected.nativeSelection(), actual.nativeSelection())
            assertEquals(storage, requireNotNull(actual.nativeSelection()).storage)
            val bundle = requireNotNull(transported.getBundleExtra("extra_args_bundle"))
            assertEquals(storage.name, bundle.getString("native_storage"))
            assertFalse(bundle.containsKey("password_value"))
            assertFalse(bundle.containsKey("username_value"))
        }
    }

    @Test fun absentCallbackStorageIsLegacyButUnknownOrNullStorageIsRejected() {
        val legacy = callbackIntent(args(NativeAutofillStorage.KEEPASS))
        val bundle = requireNotNull(legacy.getBundleExtra("extra_args_bundle"))
        bundle.remove("native_storage")
        assertEquals(NativeAutofillStorage.KEEPASS, requireNotNull(readCallback(legacy)?.nativeSelection()).storage)
        bundle.putString("native_storage", "MDBX_FUTURE")
        assertNull(readCallback(legacy))
        bundle.putString("native_storage", null)
        assertNull(readCallback(legacy))
        bundle.putInt("native_storage", 1)
        assertNull(readCallback(legacy))
    }

    @Test fun nativeCallbackIdentityCannotCrossStorageNamespaces() {
        assertNull(args(NativeAutofillStorage.MDBX).copy(passwordId = 0x4000000000000001L).nativeSelection())
        assertNull(args(NativeAutofillStorage.KEEPASS).copy(passwordId = -17L).nativeSelection())
        assertNull(args(NativeAutofillStorage.KEEPASS).copy(passwordId = 17L).nativeSelection())
        val missingKind = callbackIntent(args(NativeAutofillStorage.MDBX))
        requireNotNull(missingKind.getBundleExtra("extra_args_bundle")).remove("native_storage")
        assertNull(readCallback(missingKind)?.nativeSelection())
    }

    @Test fun deferredActionPreservesStorageAndRejectsUnknownDiscriminators() {
        try {
            NativeAutofillStorage.entries.forEach { storage ->
                val selection = requireNotNull(args(storage).nativeSelection())
                val action = NativeAutofillAction.create(selection, "org.example.form", "example.org")
                val transported = parcel(Intent().putExtra("action", action.toBundle()))
                val bundle = requireNotNull(transported.getBundleExtra("action"))
                bundle.classLoader = Bundle::class.java.classLoader
                @Suppress("DEPRECATION")
                bundle.keySet().forEach { bundle.get(it) }
                val restored = requireNotNull(NativeAutofillAction.fromBundle(bundle))
                assertEquals(selection, restored.selection)
                assertEquals(action.targetPackage, restored.targetPackage)
                assertEquals(action.webDomain, restored.webDomain)
                bundle.putString("storage", "mdbx")
                assertNull(NativeAutofillAction.fromBundle(bundle))
                bundle.putString("storage", null)
                assertNull(NativeAutofillAction.fromBundle(bundle))
                bundle.remove("storage")
                assertEquals(NativeAutofillStorage.KEEPASS,
                    requireNotNull(NativeAutofillAction.fromBundle(bundle)).selection.storage)
            }
        } finally { NativeAutofillAction.revokeAll() }
    }

    @Test fun roomReaderRejectsMdbxMirrorEvenWithoutARegistrationAndKeepsItsBytes() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val mirror = row(1L).copy(mdbxDatabaseId = 73L, replicaGroupId = "password:legacy")
            database.passwordEntryDao().insertPasswordEntry(mirror)
            assertEquals(AutofillRoomCredentialReader.Result.NativeMirror, AutofillRoomCredentialReader.read(database, mirror.id))
            assertEquals(mirror, database.passwordEntryDao().getPasswordEntryById(mirror.id))
            val conflictingOwner = mirror.copy(bitwardenVaultId = 92L, bitwardenCipherId = "cipher")
            database.passwordEntryDao().updatePasswordEntry(conflictingOwner)
            assertEquals(AutofillRoomCredentialReader.Result.NativeMirror, AutofillRoomCredentialReader.read(database, mirror.id))
            assertEquals(conflictingOwner, database.passwordEntryDao().getPasswordEntryById(mirror.id))
        } finally { database.close() }
    }

    @Test fun virtualRoomRowsAreRejectedWhileLocalAndOfflineBitwardenStayReadable() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val virtualRows = listOf(row(-17L), row(0x4000000000000001L))
            virtualRows.forEach { database.passwordEntryDao().insertPasswordEntry(it) }
            for (row in virtualRows) {
                assertEquals(AutofillRoomCredentialReader.Result.Unavailable, AutofillRoomCredentialReader.read(database, row.id))
                assertEquals(row, database.passwordEntryDao().getPasswordEntryById(row.id))
            }
            val available = listOf(row(1L), row(2L).copy(bitwardenVaultId = 92L, bitwardenCipherId = "cipher"))
            available.forEach { database.passwordEntryDao().insertPasswordEntry(it) }
            for (row in available) {
                assertEquals(AutofillRoomCredentialReader.Result.Available(row), AutofillRoomCredentialReader.read(database, row.id))
            }
            val deleted = available.last().copy(isDeleted = true)
            database.passwordEntryDao().updatePasswordEntry(deleted)
            assertEquals(AutofillRoomCredentialReader.Result.Unavailable, AutofillRoomCredentialReader.read(database, deleted.id))
        } finally { database.close() }
    }

    private fun args(storage: NativeAutofillStorage) = AutofillCipherCallbackActivity.Args(
        passwordId = if (storage == NativeAutofillStorage.MDBX) -17L else 0x4000000000000001L,
        applicationId = "org.example.form", webDomain = "example.org", fieldSignatureKey = "synthetic-signature",
        nativeDatabaseId = 73L, nativeEntryUuid = uuid, nativeRevision = "synthetic-revision",
        nativeLockVersion = 4L, nativeProcessNonce = "synthetic-process", nativeGeneration = 9L,
        nativeStorage = storage,
    )

    private fun row(id: Long) = PasswordEntry(id = id, title = "Synthetic entry", website = "example.org",
        username = "synthetic-account", password = "MDK|synthetic-encrypted-value")

    private fun callbackIntent(args: AutofillCipherCallbackActivity.Args): Intent {
        val original = AutofillCipherCallbackActivity.getIntent(context, args)
        // Exercise the framework Bundle fallback, not the process-local argument object.
        original.getStringExtra("extra_args_token")?.let { token ->
            val field = AutofillCipherCallbackActivity::class.java.getDeclaredField("pendingArgsByToken")
                .apply { isAccessible = true }
            (field.get(null) as MutableMap<*, *>).remove(token)
        }
        original.removeExtra("extra_args_token")
        val transported = parcel(original)
        transported.setExtrasClassLoader(Bundle::class.java.classLoader)
        val bundle = requireNotNull(transported.getBundleExtra("extra_args_bundle"))
        bundle.classLoader = Bundle::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.keySet().forEach { bundle.get(it) }
        return transported
    }

    private fun readCallback(intent: Intent): AutofillCipherCallbackActivity.Args? {
        val method = AutofillCipherCallbackActivity::class.java.getDeclaredMethod("resolveArgsFromIntent", Intent::class.java)
            .apply { isAccessible = true }
        var result: AutofillCipherCallbackActivity.Args? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = method.invoke(AutofillCipherCallbackActivity(), intent) as? AutofillCipherCallbackActivity.Args
        }
        return result
    }

    private fun parcel(intent: Intent): Intent {
        val value = Parcel.obtain()
        return try {
            intent.writeToParcel(value, 0)
            value.setDataPosition(0)
            Intent.CREATOR.createFromParcel(value)
        } finally { value.recycle() }
    }
}
