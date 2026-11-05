package takagi.ru.monica.bitwarden.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CancellationException

/** Immutable pointer to a protected preference. Account keys can change during a normal sync. */
internal class BitwardenTransportSecretBinding(
    val vaultId: Long,
    val serverUrl: String,
    val revision: String,
    val reference: String,
) {
    override fun toString() = "BitwardenTransportSecretBinding(redacted)"
}

internal class BitwardenTransportSecrets(
    headers: List<BitwardenHeaderDraft>,
    val tls: BitwardenTlsConfig,
    internal val configurationFingerprint: String,
) {
    val headers: List<BitwardenHeaderDraft> = java.util.Collections.unmodifiableList(headers.toList())
    override fun toString() = "BitwardenTransportSecrets(redacted)"
}

internal enum class BitwardenTransportSecretFailure {
    INVALID_BINDING, MISSING, UNAVAILABLE, CORRUPT, BINDING_MISMATCH, WRITE_NOT_VERIFIED, STILL_REFERENCED,
}

internal class BitwardenTransportSecretException(val reason: BitwardenTransportSecretFailure) :
    IllegalStateException("Bitwarden transport configuration unavailable: ${reason.name}")

/**
 * Callbacks must use SecurityManager's protected store, never the legacy Base64 helper.
 * Caller owns session authorization and serializes publication/retirement with Room changes.
 * A successful stage is not a published configuration and cannot unlock an account by itself.
 */
internal class BitwardenTransportSecretStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val remove: (String) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun stage(vaultId: Long, environment: BitwardenEnvironment): BitwardenTransportSecretBinding {
        val reference = PREFIX + newId()
        val binding = BitwardenTransportSecretBinding(
            vaultId, environment.base.toString(), environment.revision, reference,
        )
        validateBinding(binding)
        if (protectedCall { read(reference) } != null) fail(BitwardenTransportSecretFailure.INVALID_BINDING)
        val payload = Payload(
            version = 1,
            vaultId = binding.vaultId,
            serverUrl = binding.serverUrl,
            revision = binding.revision,
            reference = binding.reference,
            configurationFingerprint = environment.fingerprint,
            headers = environment.headers.map { Header(it.name, it.value) },
            tls = Tls.from(environment.tls),
        )
        val encoded = json.encodeToString(payload)
        if (encoded.length > MAX_PAYLOAD_CHARS) fail(BitwardenTransportSecretFailure.CORRUPT)
        protectedCall { write(reference, encoded) }
        // Do not delete on failure: a failed read is not proof that a concurrent publication did not occur.
        val stored = protectedCall { read(reference) } ?: fail(BitwardenTransportSecretFailure.WRITE_NOT_VERIFIED)
        if (stored != encoded) fail(BitwardenTransportSecretFailure.WRITE_NOT_VERIFIED)
        decode(binding, stored)
        return binding
    }

    fun load(binding: BitwardenTransportSecretBinding): BitwardenTransportSecrets {
        validateBinding(binding)
        val raw = protectedCall { read(binding.reference) } ?: fail(BitwardenTransportSecretFailure.MISSING)
        return decode(binding, raw)
    }

    /** Must be called while the repository publication gate excludes new references/leases. */
    fun deleteIfUnreferenced(binding: BitwardenTransportSecretBinding, isReferenced: (String) -> Boolean) {
        validateBinding(binding)
        if (isReferenced(binding.reference)) fail(BitwardenTransportSecretFailure.STILL_REFERENCED)
        // A swapped reference is not authority to remove somebody else's protected entry.
        val raw = protectedCall { read(binding.reference) } ?: return
        decode(binding, raw)
        protectedCall { remove(binding.reference) }
        if (protectedCall { read(binding.reference) } != null) fail(BitwardenTransportSecretFailure.WRITE_NOT_VERIFIED)
    }

    private fun validateBinding(binding: BitwardenTransportSecretBinding) {
        val validBase = BitwardenEnvironmentResolver.validateEndpoint(
            binding.serverUrl, BitwardenEnvironmentField.BASE,
        ).value?.toString()
        if (binding.vaultId <= 0 || !isUuid(binding.revision) ||
            !binding.reference.startsWith(PREFIX) || !isUuid(binding.reference.removePrefix(PREFIX)) ||
            validBase != binding.serverUrl
        ) fail(BitwardenTransportSecretFailure.INVALID_BINDING)
    }

    private fun decode(binding: BitwardenTransportSecretBinding, raw: String): BitwardenTransportSecrets {
        if (raw.length > MAX_PAYLOAD_CHARS) fail(BitwardenTransportSecretFailure.CORRUPT)
        val payload = try {
            json.decodeFromString<Payload>(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(BitwardenTransportSecretFailure.CORRUPT)
        }
        if (payload.version != 1) fail(BitwardenTransportSecretFailure.CORRUPT)
        if (payload.vaultId != binding.vaultId || payload.serverUrl != binding.serverUrl ||
            payload.revision != binding.revision || payload.reference != binding.reference
        ) fail(BitwardenTransportSecretFailure.BINDING_MISMATCH)
        val headers = payload.headers.map { BitwardenHeaderDraft(it.name, it.value) }
        if (BitwardenTransportPolicy.validateHeaders(headers).isNotEmpty()) {
            fail(BitwardenTransportSecretFailure.CORRUPT)
        }
        // Full TLS/endpoint validation occurs in the environment resolver before any connection.
        if (!payload.configurationFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            fail(BitwardenTransportSecretFailure.CORRUPT)
        }
        return BitwardenTransportSecrets(headers, payload.tls.toConfig(), payload.configurationFingerprint)
    }

    private inline fun <T> protectedCall(action: () -> T): T = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Do not retain the provider exception/cause: it can contain private preference content.
        fail(BitwardenTransportSecretFailure.UNAVAILABLE)
    }

    @Serializable
    private class Payload(
        val version: Int,
        val vaultId: Long,
        val serverUrl: String,
        val revision: String,
        val reference: String,
        val configurationFingerprint: String,
        val headers: List<Header>,
        val tls: Tls,
    )

    @Serializable
    private class Header(val name: String, val value: String)

    @Serializable
    private class Tls(
        val certificateAlias: String?,
        val caCertificatePem: String?,
        val mtlsEnabled: Boolean,
        val clientCertPkcs12Base64: String?,
        val clientCertPassword: String?,
    ) {
        fun toConfig() = BitwardenTlsConfig(
            certificateAlias, caCertificatePem, mtlsEnabled, clientCertPkcs12Base64, clientCertPassword,
        )
        companion object {
            fun from(tls: BitwardenTlsConfig) = Tls(
                tls.certificateAlias, tls.caCertificatePem, tls.mtlsEnabled,
                tls.clientCertPkcs12Base64, tls.clientCertPassword,
            )
        }
    }

    companion object {
        const val PREFIX = "bw_transport_secret_v1_"
        private const val MAX_PAYLOAD_CHARS = 3 * 1024 * 1024
        private fun isUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun fail(reason: BitwardenTransportSecretFailure): Nothing =
            throw BitwardenTransportSecretException(reason)
    }
}
