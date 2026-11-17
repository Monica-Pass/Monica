package takagi.ru.monica.ui.vaultv2

import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.EntryValue
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.keepass.KeePassDeleteInvestigationTest
import takagi.ru.monica.ui.screens.KeePassKdbxViewModel
import java.util.UUID

/** Actual encrypted-file import, including source identities that names cannot distinguish. */
class KdbxImportIdentityInstrumentedTest {
    @Test fun sameNamedGroupsAndSameLoginFieldsKeepBothSourceIdentitiesOnReimport() = runBlocking {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { file -> file.modifyParentGroup {
            val same = entries.take(2).map { entry -> entry.copy(fields = entry.fields +
                ("Title" to EntryValue.Plain("Same synthetic login")) + ("UserName" to EntryValue.Plain("same"))) }
            copy(entries = emptyList(), groups = listOf(Group(a, "合成同名", entries = listOf(same[0])),
                Group(b, "合成同名", entries = listOf(same[1])), Group(UUID.randomUUID(), "Empty")))
        } }) { _, service, id, _, uri ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = PasswordDatabase.getDatabase(context)
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            try {
                val importer = KeePassKdbxViewModel()
                assertEquals(2, importer.importFromLocalKdbx(context, uri, "Synthetic KDBX deletion test").getOrThrow())
                val first = db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                assertEquals(2, first.size)
                assertEquals(setOf(a.toString(), b.toString()), first.map { it.keepassGroupUuid }.toSet())
                assertEquals(2, first.map { it.keepassEntryUuid }.toSet().size)
                val native = service.readPasswordEntries(id).getOrThrow()
                assertEquals(native.map { it.entryUuid }.toSet(), first.map { it.keepassEntryUuid }.toSet())
                assertEquals(0, importer.importFromLocalKdbx(context, uri, "Synthetic KDBX deletion test").getOrThrow())
                val second = db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
                assertEquals(first.map { it.keepassGroupUuid }.toSet(), second.map { it.keepassGroupUuid }.toSet())
                assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).forEach {
                db.customFieldDao().deleteByEntryId(it.id); db.passwordEntryDao().deletePasswordEntry(it)
            } }
        }
    }

    @Test fun sameNamedTotpGroupsKeepBothIdentitiesAndRejectWrongPassword() = runBlocking {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { file -> file.modifyParentGroup {
            val entries = entries.take(2).map { entry -> entry.copy(fields = entry.fields +
                ("Title" to EntryValue.Plain("Synthetic OTP")) + ("Password" to EntryValue.Plain("")) + ("UserName" to EntryValue.Plain("")) +
                ("otp" to EntryValue.Plain("otpauth://totp/Synthetic?secret=JBSWY3DPEHPK3PXP&issuer=Synthetic"))) }
            copy(entries = emptyList(), groups = listOf(Group(a, "OTP+%20", entries = listOf(entries[0])),
                Group(b, "OTP+%20", entries = listOf(entries[1]))))
        } }) { _, _, id, _, uri ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = PasswordDatabase.getDatabase(context)
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            try {
                val importer = KeePassKdbxViewModel()
                assertTrue(importer.importFromLocalKdbx(context, uri, "Wrong synthetic password").isFailure)
                assertTrue(db.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                assertEquals(2, importer.importFromLocalKdbx(context, uri, "Synthetic KDBX deletion test").getOrThrow())
                val first = db.secureItemDao().getByKeePassDatabaseIdSync(id)
                assertEquals(2, first.size)
                assertEquals(setOf(a.toString(), b.toString()), first.map { it.keepassGroupUuid }.toSet())
                assertEquals(2, first.map { it.keepassEntryUuid }.toSet().size)
                assertEquals(0, importer.importFromLocalKdbx(context, uri, "Synthetic KDBX deletion test").getOrThrow())
                assertEquals(first.map { it.id }.toSet(), db.secureItemDao().getByKeePassDatabaseIdSync(id).map { it.id }.toSet())
                assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { db.secureItemDao().getByKeePassDatabaseIdSync(id).forEach { db.secureItemDao().deleteItem(it) } }
        }
    }
}
