package takagi.ru.monica.bitwarden.service

internal enum class BitwardenAccountManagementState { READY, REAUTHENTICATE, DELETED_LOCAL_COPY, UNREADABLE }
internal enum class BitwardenAccountManagementResult { HINT_SAVED, REAUTHENTICATE, DELETED_LOCAL_COPY, UNCERTAIN, NOT_SENT }

internal class BitwardenAccountManagementInfo(
    val vaultId: Long,
    val email: String,
    val server: String,
    val kdf: BitwardenAccountMutationKdf,
    val hintPresent: Boolean,
    val hint: String?,
    val state: BitwardenAccountManagementState,
) {
    override fun toString() = "BitwardenAccountManagementInfo(state=$state, redacted)"
}
