package takagi.ru.monica.ui.screens

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.credentialexchange.ImportDestination

class BitwardenJsonExportRequestTest {
    @Test fun cancellationRejectsLateAuthenticationAndDoesNotAuthorizeReplacement() {
        val old = BitwardenJsonExportRequest(ImportDestination.Local, ExportOption.BITWARDEN_JSON, 1)
        val lateBiometric = { old.authenticated() }
        old.revoke()
        val replacement = BitwardenJsonExportRequest(ImportDestination.Local, ExportOption.BITWARDEN_JSON, 1)
        assertFalse(lateBiometric())
        assertFalse(old.chooseDocument())
        assertFalse(replacement.chooseDocument())
        assertTrue(replacement.authenticated())
        assertFalse(replacement.authenticated())
    }

    @Test fun documentReturnIsBoundToOneAuthenticatedAttemptAndCannotReplay() {
        val request = BitwardenJsonExportRequest(ImportDestination.Local, ExportOption.BITWARDEN_JSON_ENCRYPTED, 7)
        assertFalse(request.chooseDocument())
        assertTrue(request.authenticated())
        assertTrue(request.chooseDocument())
        assertTrue(request.choosingDocument)
        assertFalse(request.chooseDocument())
        assertTrue(request.returnDocument())
        assertFalse(request.returnDocument())
        assertFalse(request.active)
        assertFalse(request.authenticated())
    }
}
