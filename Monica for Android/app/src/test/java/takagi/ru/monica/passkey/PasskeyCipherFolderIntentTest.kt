package takagi.ru.monica.passkey

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasskeyEntry
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PasskeyCipherFolderIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun row() = PasskeyEntry(id = 5, credentialId = "credential", rpId = "example.test", rpName = "Example",
        userId = "user", userName = "User", userDisplayName = "User", publicKey = "public", privateKeyAlias = "key",
        bitwardenVaultId = 3, bitwardenCipherId = UUID.randomUUID().toString())

    @Test fun failedLocalWriteRestoresPreviousMoveAndDoesNotApplyToDifferentRowState(): Unit = runBlocking {
        val before = row(); val moved = before.copy(bitwardenFolderId = "folder")
        PasskeyCipherFolderIntent.recordMove(context, before, moved) { }
        val original = PasskeyCipherFolderIntent.pending(context, moved)
        assertNotNull(original)
        assertNull(PasskeyCipherFolderIntent.pending(context, before))
        try {
            PasskeyCipherFolderIntent.recordMove(context, moved, moved.copy(bitwardenFolderId = null)) { error("synthetic Room failure") }
            fail("Expected Room failure")
        } catch (_: IllegalStateException) { }
        assertEquals(original, PasskeyCipherFolderIntent.pending(context, moved))
        PasskeyCipherFolderIntent.complete(context, moved, original)
        assertNull(PasskeyCipherFolderIntent.pending(context, moved))
    }

    @Test fun completingOldUploadCannotEraseNewerExplicitMove(): Unit = runBlocking {
        val before = row().copy(bitwardenFolderId = "first"); val root = before.copy(bitwardenFolderId = null)
        PasskeyCipherFolderIntent.recordMove(context, before, root) { }
        val old = PasskeyCipherFolderIntent.pending(context, root)!!
        assertNull(old.folderId)
        PasskeyCipherFolderIntent.recordMove(context, before, root) { }
        val newer = PasskeyCipherFolderIntent.pending(context, root)!!
        assertNotEquals(old.token, newer.token)
        PasskeyCipherFolderIntent.complete(context, root, old)
        assertEquals(newer, PasskeyCipherFolderIntent.pending(context, root))
        PasskeyCipherFolderIntent.complete(context, root, newer)
    }
}
