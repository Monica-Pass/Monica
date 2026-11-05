package takagi.ru.monica.bitwarden.api

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.StringResolver

/** Portable metadata and password-encrypted material only: no device-local reference, ID or revision. */
@Serializable
internal class BitwardenTransportBackup(
    val version: Int = 1,
    val preset: String,
    val webUrl: String? = null,
    val notificationsUrl: String,
    val encryptedPayload: String,
) {
    override fun toString() = "BitwardenTransportBackup(redacted)"
}

internal enum class BitwardenTransportBackupFailure {
    ENCRYPTION_REQUIRED, UNSUPPORTED_VERSION, INVALID_PAYLOAD, ACCOUNT_MISMATCH, CONFIGURATION_MISMATCH,
}

internal class BitwardenTransportBackupException(val reason: BitwardenTransportBackupFailure) :
    IllegalStateException("Bitwarden transport backup unavailable: ${reason.name}")

/** The repository owns authorization/publication. This codec performs no storage or network IO. */
internal object BitwardenTransportBackupCodec {
    private const val MAX_PLAINTEXT_CHARS = 3 * 1024 * 1024
    private const val MAX_ENCRYPTED_CHARS = 6 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun seal(vault: BitwardenVault, environment: BitwardenEnvironment, password: String?, strings: StringResolver): BitwardenTransportBackup {
        requirePassword(password)
        val email = canonicalEmail(vault.email)
        if (email.isEmpty()) fail(BitwardenTransportBackupFailure.ACCOUNT_MISMATCH)
        val base = BitwardenEnvironmentResolver.validateEndpoint(vault.serverUrl, BitwardenEnvironmentField.BASE).value
        if (base != environment.base) fail(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH)
        val payload = Payload(
            version = 1, canonicalEmail = email, userId = normalizedUserId(vault.userId),
            configurationFingerprint = environment.fingerprint,
            headers = environment.headers.map { Header(it.name, it.value) }, tls = Tls.from(environment.tls),
        )
        val plaintext = json.encodeToString(payload)
        if (plaintext.length > MAX_PLAINTEXT_CHARS) fail(BitwardenTransportBackupFailure.INVALID_PAYLOAD)
        val encrypted = checked { EncryptionHelper.encryptString(plaintext, requireNotNull(password), strings) }
        if (encrypted.length > MAX_ENCRYPTED_CHARS) fail(BitwardenTransportBackupFailure.INVALID_PAYLOAD)
        return BitwardenTransportBackup(
            preset = environment.preset.name,
            webUrl = environment.web.toString().takeIf { environment.webExplicit },
            notificationsUrl = environment.notifications.toString(), encryptedPayload = encrypted,
        )
    }

    fun open(
        backup: BitwardenTransportBackup,
        serverUrl: String,
        apiUrl: String,
        identityUrl: String,
        email: String,
        userId: String?,
        password: String?,
        strings: StringResolver,
    ): BitwardenEnvironment {
        requirePassword(password)
        if (backup.version != 1) fail(BitwardenTransportBackupFailure.UNSUPPORTED_VERSION)
        if (backup.encryptedPayload.isEmpty() || backup.encryptedPayload.length > MAX_ENCRYPTED_CHARS) {
            fail(BitwardenTransportBackupFailure.INVALID_PAYLOAD)
        }
        // New transport backups never use the historical fixed-password fallback.
        val plaintext = checked { EncryptionHelper.decryptString(backup.encryptedPayload, requireNotNull(password), strings) }
        if (plaintext.length > MAX_PLAINTEXT_CHARS) fail(BitwardenTransportBackupFailure.INVALID_PAYLOAD)
        val payload = checked { json.decodeFromString<Payload>(plaintext) }
        if (payload.version != 1) fail(BitwardenTransportBackupFailure.UNSUPPORTED_VERSION)
        if (payload.canonicalEmail.isEmpty() || payload.canonicalEmail != canonicalEmail(email) || payload.userId != normalizedUserId(userId)) {
            fail(BitwardenTransportBackupFailure.ACCOUNT_MISMATCH)
        }
        val preset = BitwardenEnvironmentPreset.entries.firstOrNull { it.name == backup.preset }
            ?: fail(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH)
        val environment = BitwardenEnvironmentResolver.resolve(
            BitwardenEnvironmentDraft(
                preset, serverUrl, backup.webUrl, apiUrl, identityUrl, backup.notificationsUrl,
                payload.headers.map { BitwardenHeaderDraft(it.name, it.value) }, payload.tls.toConfig(),
            ), UUID.randomUUID().toString(),
        ).value ?: fail(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH)
        // Full fingerprint binds every endpoint, Web provenance, ordered header and exact TLS value.
        // Check Base independently because an official preset deliberately ignores its draft Base.
        val base = BitwardenEnvironmentResolver.validateEndpoint(serverUrl, BitwardenEnvironmentField.BASE).value
        if (base != environment.base || environment.fingerprint != payload.configurationFingerprint) {
            fail(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH)
        }
        return environment
    }

    /** A missing custom Base is never authority to send restored credentials to the US service. */
    fun legacyEnvironment(serverUrl: String, apiUrl: String, identityUrl: String): BitwardenEnvironment =
        BitwardenEnvironmentResolver.resolveLegacy(
            BitwardenLegacyEnvironmentSnapshot(
                serverUrl = serverUrl,
                apiUrl = apiUrl.takeIf { it.isNotBlank() }, identityUrl = identityUrl.takeIf { it.isNotBlank() },
            ), UUID.randomUUID().toString(),
        ).value ?: fail(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH)

    private fun requirePassword(password: String?) {
        if (password.isNullOrEmpty()) fail(BitwardenTransportBackupFailure.ENCRYPTION_REQUIRED)
    }

    private fun canonicalEmail(value: String) = BitwardenVaultIdentity.canonicalizeEmail(value)
    private fun normalizedUserId(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }

    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fail(BitwardenTransportBackupFailure.INVALID_PAYLOAD) }

    private fun fail(reason: BitwardenTransportBackupFailure): Nothing = throw BitwardenTransportBackupException(reason)

    @Serializable
    private class Payload(
        val version: Int,
        val canonicalEmail: String,
        val userId: String?,
        val configurationFingerprint: String,
        val headers: List<Header>,
        val tls: Tls,
    )

    @Serializable
    private class Header(val name: String, val value: String)

    @Serializable
    private class Tls(
        // A display alias is metadata only; usable mTLS requires the encrypted imported PKCS#12.
        val certificateAlias: String?,
        val caCertificatePem: String?,
        val mtlsEnabled: Boolean,
        val clientCertPkcs12Base64: String?,
        val clientCertPassword: String?,
    ) {
        fun toConfig() = BitwardenTlsConfig(certificateAlias, caCertificatePem, mtlsEnabled, clientCertPkcs12Base64, clientCertPassword)
        companion object {
            fun from(tls: BitwardenTlsConfig) = Tls(tls.certificateAlias, tls.caCertificatePem, tls.mtlsEnabled,
                tls.clientCertPkcs12Base64, tls.clientCertPassword)
        }
    }
}
