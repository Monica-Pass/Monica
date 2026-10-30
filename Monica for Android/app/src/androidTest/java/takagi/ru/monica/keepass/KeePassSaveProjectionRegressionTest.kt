package takagi.ru.monica.keepass

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KeePassSaveProjectionRegressionTest {
    @Test fun editingOneEntryKeepsUnchangedPresentationObjects() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, _ ->
            val store = KeePassMonicaPasswordStore(service)
            val before = store.refresh(id)
            val edited = before.rows.first()
            val untouched = before.rows.last()
            val result = store.save(id, listOf(edited.copy(title = "Incremental save")), emptyMap()).single()
            val after = store.refresh(id)
            assertEquals("Incremental save", result.title)
            assertEquals(300, after.rows.size)
            assertSame("Unchanged entries must keep their presentation after a single-entry save", untouched,
                after.rows.single { it.id == untouched.id })
            assertSame(before.customFields[untouched.id], after.customFields[untouched.id])
            assertNotEquals(before.browser.sourceRevision, after.browser.sourceRevision)
            val beforeProjection = requireNotNull(before.passwordProjection)
            val afterProjection = requireNotNull(after.passwordProjection)
            assertSame(beforeProjection.passwords.single { it.entryUuid == untouched.keepassEntryUuid },
                afterProjection.passwords.single { it.entryUuid == untouched.keepassEntryUuid })
            assertNotSame(beforeProjection.passwords.single { it.entryUuid == edited.keepassEntryUuid },
                afterProjection.passwords.single { it.entryUuid == edited.keepassEntryUuid })
        }
    }

    @Test fun externalReloadRebuildsInputsEvenWhenValuesMatch() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, _ ->
            val store = KeePassMonicaPasswordStore(service)
            val before = store.refresh(id)
            takagi.ru.monica.utils.KeePassKdbxService.invalidateProcessCache(id)
            val after = store.refresh(id)
            assertEquals(before.rows, after.rows)
            assertNotSame(before.passwordProjection!!.passwords.first(), after.passwordProjection!!.passwords.first())
        }
    }
}
