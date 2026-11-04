package takagi.ru.monica.bitwarden.service

import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest

internal object BitwardenCipherOwnership {
    fun requireWritable(cipher: CipherApiResponse, expectedId: String) {
        require(cipher.id == expectedId && cipher.deletedDate == null) { "Cipher source changed or was deleted" }
        require(cipher.edit != false && (cipher.organizationId.isNullOrBlank() || cipher.edit == true)) {
            "Team item is read-only or edit permission is unavailable"
        }
        require(cipher.organizationId.isNullOrBlank() || cipher.viewPassword != false) {
            "Team item restricts password visibility"
        }
    }

    /** Updates never turn shared data into personal data or drop its per-item key/collections. */
    fun preserve(request: CipherUpdateRequest, source: CipherApiResponse): CipherUpdateRequest {
        requireWritable(source, source.id)
        require(request.type == source.type) { "Cipher type changed" }
        return request.copy(
            organizationId = source.organizationId,
            collectionIds = source.collectionIds,
            key = source.key,
            lastKnownRevisionDate = source.revisionDate.takeIf(String::isNotBlank),
            reprompt = source.reprompt,
            archivedDate = source.archivedDate,
            // Ordinary edits must not clear history maintained by other clients.
            passwordHistory = source.passwordHistory,
            login = request.login?.copy(
                fido2Credentials = source.login?.fido2Credentials,
                passwordRevisionDate = source.login?.passwordRevisionDate
            )
        )
    }
}
