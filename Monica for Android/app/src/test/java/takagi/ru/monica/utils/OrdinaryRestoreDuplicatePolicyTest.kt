package takagi.ru.monica.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.util.DataExportImportManager.ExportItem
import takagi.ru.monica.util.DataExportImportManager.ImportedCustomField
import java.util.Date

class OrdinaryRestoreDuplicatePolicyTest {
    private val payload = """{"content":"Synthetic note","customFields":[{"label":"secret","value":"synthetic","type":"HIDDEN"}]}"""
    private val incoming = ExportItem(41, "NOTE", "Synthetic note", payload, "notes", false, "", 1000, 2000)
    private val saved = SecureItem(id = 72, itemType = ItemType.NOTE, title = incoming.title,
        notes = incoming.notes, itemData = "encrypted representation", createdAt = Date(1000), updatedAt = Date(2000))

    private fun matches(source: ExportItem = incoming, candidate: SecureItem = saved,
        sourcePlain: String = source.itemData, candidatePlain: String = payload) =
        OrdinaryRestoreDuplicatePolicy.isEquivalentStandalone(source, candidate, sourcePlain, candidatePlain)

    @Test fun assignedIdAndEncryptionEnvelopeDoNotPreventAnExactRetry() {
        assertTrue(matches())
        assertTrue(matches(candidate = saved.copy(id = 912, itemData = "new envelope")))
    }

    @Test fun everyStoredProvenanceAndLifecycleDifferencePreventsEquality() {
        val changed = listOf(saved.copy(title = "different"), saved.copy(notes = "different"),
            saved.copy(isFavorite = true), saved.copy(sortOrder = 1), saved.copy(createdAt = Date(999)),
            saved.copy(updatedAt = Date(2001)), saved.copy(imagePaths = "[]"), saved.copy(categoryId = 8),
            saved.copy(keepassDatabaseId = 1), saved.copy(keepassGroupPath = "group"),
            saved.copy(keepassEntryUuid = "uuid"), saved.copy(keepassGroupUuid = "uuid"),
            saved.copy(mdbxDatabaseId = 1), saved.copy(mdbxFolderId = "folder"),
            saved.copy(isDeleted = true), saved.copy(deletedAt = Date(3000)),
            saved.copy(isArchived = true), saved.copy(archivedAt = Date(3000)),
            saved.copy(bitwardenArchiveIntent = false), saved.copy(replicaGroupId = "group"),
            saved.copy(bitwardenVaultId = 1), saved.copy(bitwardenCipherId = "cipher"),
            saved.copy(bitwardenFolderId = "folder"), saved.copy(bitwardenRevisionDate = "revision"),
            saved.copy(bitwardenLocalModified = true), saved.copy(syncStatus = "PENDING"))
        changed.forEach { assertFalse("Stored metadata must be preserved: $it", matches(candidate = it)) }
    }

    @Test fun unknownRootAndNestedFieldsCannotProveAStandaloneProject() {
        val unknown = listOf("""{"content":"Synthetic note","futureRef":3}""",
            """{"content":"Synthetic note","customFields":[{"label":"x","value":"x","futureRef":3}]}""")
        unknown.forEach { assertFalse(matches(source = incoming.copy(itemData = it), candidatePlain = it)) }
    }

    @Test fun importedExternalOrSupplementalStateCannotBeDiscardedAsARetry() {
        val changed = listOf(incoming.copy(keepassDatabaseId = 1), incoming.copy(keepassGroupPath = "group"),
            incoming.copy(bitwardenVaultId = 1), incoming.copy(bitwardenFolderId = "folder"),
            incoming.copy(importedCustomFields = listOf(ImportedCustomField("x", "x", true))),
            incoming.copy(importedAuthenticatorKey = "synthetic"), incoming.copy(isFromAppExport = true))
        changed.forEach { assertFalse(matches(source = it)) }
    }

    @Test fun identicalCardFaceReferenceStillRequiresItsIndependentAttachmentProject() {
        val card = """{"cardNumber":"4111111111111111","cardholderName":"Synthetic","expiryMonth":"01","expiryYear":"2030","cardFace":{"imageAttachmentName":"missing.png"}}"""
        assertFalse(matches(source = incoming.copy(itemType = "BANK_CARD", itemData = card),
            candidate = saved.copy(itemType = ItemType.BANK_CARD), candidatePlain = card))
    }

    @Test fun formattingProtectedValueAndDuplicateFieldDifferencesRemainIndependent() {
        val different = listOf(payload.replace("HIDDEN", "TEXT"), payload.replace("synthetic", "changed"),
            payload.replace("{", "{ "),
            """{"content":"Synthetic note","customFields":[{"label":"secret","value":"synthetic","type":"HIDDEN"},{"label":"secret","value":"synthetic","type":"HIDDEN"}]}""")
        different.forEach { assertFalse(matches(source = incoming.copy(itemData = it))) }
    }
}
