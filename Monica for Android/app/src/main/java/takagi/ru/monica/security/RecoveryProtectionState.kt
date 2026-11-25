package takagi.ru.monica.security

/** Structural availability is not proof that a password can decrypt the recovery material. */
internal enum class RecoveryMaterialState {
    NOT_CHECKED, AVAILABLE, MISSING, REENROLLMENT_REQUIRED, REVOKED,
    UNSUPPORTED_VERSION, INVALID, UNREADABLE
}

internal data class RecoveryProtectionStatus(
    val material: RecoveryMaterialState = RecoveryMaterialState.NOT_CHECKED,
    val verifiedAtMillis: Long? = null,
    val verificationFailed: Boolean = false,
)

internal enum class RecoveryProtectionCheck { VERIFIED, INVALID_PASSWORD, UNAVAILABLE }
