package takagi.ru.monica.attachments.executor

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.bitwarden.api.AttachmentUploadResponse
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.data.bitwarden.BitwardenVault

class BitwardenAttachmentRecoveryPolicyTest {
    @Test fun missingAndUnknownUploadTypesNeverBecomeTransfers() {
        assertEquals(0, requireKnownAttachmentUploadType(0))
        assertEquals(1, requireKnownAttachmentUploadType(1))
        listOf(null, -1, 2, Int.MAX_VALUE).forEach {
            assertSame(AttachmentError.InvalidRemoteData, runCatching { requireKnownAttachmentUploadType(it) }.exceptionOrNull())
        }
        val missing = Json.decodeFromString<AttachmentUploadResponse>("""{"attachmentId":"exact-id"}""")
        assertSame(AttachmentError.InvalidRemoteData,
            runCatching { requireKnownAttachmentUploadType(missing.fileUploadType) }.exceptionOrNull())
    }

    @Test fun absentReceiptIdNeverFallsBackToAnExistingAttachment() {
        val response = AttachmentUploadResponse(fileUploadType = 0,
            cipherResponse = CipherApiResponse(id = "cipher", attachments = listOf(CipherAttachmentApiData(id = "zz-existing"))))
        assertSame(AttachmentError.InvalidRemoteData,
            runCatching { requireExactAttachmentAllocationId(response) }.exceptionOrNull())
        assertEquals("exact-id", requireExactAttachmentAllocationId(response.copy(attachmentId = "exact-id")))
    }

    @Test fun identicalNameAndSizeWithoutTheRandomWrappedKeyCannotIdentifyAnAllocation() {
        val record = record()
        val exact = CipherAttachmentApiData(id = "remote", key = record.requestKey,
            fileName = record.requestFileName, size = record.encryptedSize.toString())
        assertTrue(BitwardenAttachmentRecoveryStore.matchesRequest(record, exact))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesRequest(record, exact.copy(key = "somebody-elses-key")))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesRequest(record, exact.copy(fileName = "another-encrypted-name")))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesRequest(record, exact.copy(size = "97")))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesRequest(record, exact.copy(id = "")))
    }

    @Test fun uncertainOrAdoptedUploadCannotTransitionToRemoteDeletionOrASecondAllocation() {
        // Keep every irreversible/uncertain state out of the allocation and deletion transitions.
        listOf(BitwardenAttachmentRecoveryStore.Phase.UPLOADING, BitwardenAttachmentRecoveryStore.Phase.UPLOADED,
            BitwardenAttachmentRecoveryStore.Phase.ADOPTED).forEach { from ->
            assertFalse(BitwardenAttachmentRecoveryStore.validTransition(from, BitwardenAttachmentRecoveryStore.Phase.ALLOCATING))
            assertFalse(BitwardenAttachmentRecoveryStore.validTransition(from, BitwardenAttachmentRecoveryStore.Phase.DELETING))
        }
    }

    @Test fun bindingDistinguishesEqualNumericOwnersAndServerLocalIds() {
        val binding = record().binding
        listOf(binding.copy(ownerKind = "SECURE_ITEM"), binding.copy(ownerId = 2), binding.copy(vaultId = 2),
            binding.copy(userId = "other-user"), binding.copy(userId = null), binding.copy(cipherId = "other-cipher"),
            binding.copy(serverUrl = "https://other.test"), binding.copy(apiUrl = "https://other.test/api"),
            binding.copy(identityUrl = "https://other.test/identity"), binding.copy(transportRevision = "reconfigured"),
            binding.copy(transportSecretRef = "replaced")).forEach {
            assertFalse(BitwardenAttachmentRecoveryStore.matchesBinding(binding, it))
        }
        assertFalse(binding.toString().contains(binding.accountKey))
    }

    @Test fun projectionAliasPromotionPreservesRecoveryScopeButNotBlankAliasesOrDifferentUsers() {
        val binding = record().binding
        val promoted = binding.copy(accountKey = "server-user-projection")
        assertTrue(BitwardenAttachmentRecoveryStore.matchesBinding(binding, promoted))
        assertTrue(BitwardenAttachmentRecoveryStore.matchesBinding(promoted, binding))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesBinding(binding, promoted.copy(accountKey = " ")))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesBinding(binding.copy(accountKey = ""), promoted))
        assertTrue(runCatching { BitwardenAttachmentRecoveryStore.validateBinding(binding.copy(accountKey = "")) }.isFailure)
        val vault = BitwardenVault(id = binding.vaultId, email = "synthetic@example.invalid", accountKey = promoted.accountKey,
            userId = binding.userId, serverUrl = binding.serverUrl, apiUrl = binding.apiUrl, identityUrl = binding.identityUrl,
            transportRevision = binding.transportRevision, transportSecretRef = binding.transportSecretRef)
        assertTrue(BitwardenAttachmentRecoveryStore.matchesVault(binding, vault))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesVault(binding, vault.copy(userId = "other-user")))
        assertFalse(BitwardenAttachmentRecoveryStore.matchesVault(binding, vault.copy(accountKey = "")))
    }

    private fun record() = BitwardenAttachmentRecoveryStore.Record(
        operationId = "00000000-0000-0000-0000-000000000001",
        binding = BitwardenAttachmentRecoveryStore.Binding(1, "synthetic-account", "user", "https://server.test",
            "https://server.test/api", "https://server.test/identity", "revision", "secret-ref", "cipher", "PASSWORD", 1),
        localAttachmentId = 7, fileName = "private-name", mimeType = "application/octet-stream",
        plainSize = 10, plainSha256 = "a".repeat(64), encryptedSize = 96, encryptedSha256 = "b".repeat(64),
        requestKey = "random-wrapped-key", requestFileName = "random-encrypted-name")
}
