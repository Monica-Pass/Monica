package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.notes.domain.NoteCiphertextRepair
import takagi.ru.monica.notes.domain.NoteContentCodec
import java.io.File
import java.util.Date
import java.util.UUID

/** Real master-password vault in a private context; the installed user's vault is never opened. */
class NoteCiphertextRepairInstrumentedTest {
    @Test fun sealedNotesReturnToPlainItemDataWithoutTouchingOtherRows() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "note-repair-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.path }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        SecurityManager.clearRuntimeUnlockCache()
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val security = SecurityManager(context)
            assertTrue(security.setMasterPassword("Synthetic note repair 321!"))
            val dao = db.secureItemDao()
            val editedAt = Date(1_700_000_000_000)
            val (noteData, noteCache) = NoteContentCodec.encode("synthetic recovery code", tags = listOf("fixture"))
            // The nearby-sync receive path stored notes through the compatibility encryptor.
            val sealed = security.encryptDataLegacyCompat(noteData)
            assertTrue(sealed.startsWith("MDK|"))
            val sealedId = dao.insertItem(SecureItem(itemType = ItemType.NOTE, title = "Sealed",
                itemData = sealed, notes = noteCache, updatedAt = editedAt))
            val (resaved, resavedCache) = NoteContentCodec.encode(security.encryptData(noteData))
            val resavedId = dao.insertItem(SecureItem(itemType = ItemType.NOTE, title = "Resaved",
                itemData = resaved, notes = resavedCache, updatedAt = editedAt))
            val mention = NoteContentCodec.encode("format: MDK|example").first
            val mentionId = dao.insertItem(SecureItem(itemType = ItemType.NOTE, title = "Mention", itemData = mention))
            val foreign = "MDK|" + Base64.encodeToString(ByteArray(48) { it.toByte() }, Base64.NO_WRAP)
            val foreignId = dao.insertItem(SecureItem(itemType = ItemType.NOTE, title = "Foreign", itemData = foreign))
            val totp = security.encryptData("{\"secret\":\"JBSWY3DPEHPK3PXP\"}")
            val totpId = dao.insertItem(SecureItem(itemType = ItemType.TOTP, title = "OTP", itemData = totp))

            assertEquals(2, NoteCiphertextRepair.run(dao, security))
            assertEquals(0, NoteCiphertextRepair.run(dao, security))

            val repaired = dao.getItemById(sealedId)!!
            assertEquals(noteData, repaired.itemData)
            assertEquals(noteCache, repaired.notes)
            assertEquals(editedAt, repaired.updatedAt)
            val recovered = dao.getItemById(resavedId)!!
            assertEquals(noteData, recovered.itemData)
            assertEquals("synthetic recovery code", recovered.notes)
            assertEquals(listOf("fixture"), NoteContentCodec.decodeFromItem(recovered).tags)
            assertEquals(editedAt, recovered.updatedAt)
            assertEquals(mention, dao.getItemById(mentionId)!!.itemData)
            assertEquals(foreign, dao.getItemById(foreignId)!!.itemData)
            assertEquals(totp, dao.getItemById(totpId)!!.itemData)
        } finally {
            db.close()
            SecurityManager.clearRuntimeUnlockCache()
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("note-repair-"))
            root.deleteRecursively()
        }
    }
}
