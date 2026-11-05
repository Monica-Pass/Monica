package takagi.ru.monica.bitwarden.api

import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.util.UUID

/** Resolution only; repository session leases authorize actual use, including cached clients. */
internal class BitwardenVaultEnvironmentStore(private val secrets: BitwardenTransportSecretStore?) {
    private class LegacyRevision(val revision: String, val fingerprint: String)
    private val legacyRevisions = mutableMapOf<Long, LegacyRevision>()

    @Synchronized
    fun resolve(vault: BitwardenVault): BitwardenEnvironment {
        if (vault.transportSecretRef != null) {
            val binding = binding(vault)
            val material = secrets?.load(binding)
                ?: throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.UNAVAILABLE)
            val preset = BitwardenEnvironmentPreset.entries.firstOrNull { it.name == vault.environmentPreset }
                ?: throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.CORRUPT)
            val resolved = checked(BitwardenEnvironmentResolver.resolve(
                BitwardenEnvironmentDraft(
                    preset = preset, baseUrl = vault.serverUrl, webUrl = vault.webVaultUrl,
                    apiUrl = vault.apiUrl, identityUrl = vault.identityUrl,
                    notificationsUrl = vault.notificationsUrl, headers = material.headers, tls = material.tls,
                ), vault.transportRevision,
            ))
            if (resolved.base.toString() != binding.serverUrl || resolved.fingerprint != material.configurationFingerprint) {
                throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.BINDING_MISMATCH)
            }
            return resolved
        }
        // Partial new-format records are never permission to reconnect with legacy secrets.
        if (vault.transportRevision.isNotEmpty() || vault.environmentPreset != null ||
            vault.webVaultUrl != null || vault.notificationsUrl != null
        ) throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.MISSING)
        val previous = legacyRevisions[vault.id]
        var result = checked(BitwardenEnvironmentResolver.resolveLegacy(
            BitwardenLegacyEnvironmentSnapshot(vault.serverUrl, vault.apiUrl, vault.identityUrl, vault.legacyTls()),
            previous?.revision ?: UUID.randomUUID().toString(),
        ))
        if (previous != null && previous.fingerprint != result.fingerprint) {
            result = checked(BitwardenEnvironmentResolver.resolveLegacy(
                BitwardenLegacyEnvironmentSnapshot(vault.serverUrl, vault.apiUrl, vault.identityUrl, vault.legacyTls()),
                UUID.randomUUID().toString(),
            ))
        }
        legacyRevisions[vault.id] = LegacyRevision(result.revision, result.fingerprint)
        return result
    }

    @Synchronized
    fun forget(vaultId: Long? = null) {
        if (vaultId == null) legacyRevisions.clear() else legacyRevisions.remove(vaultId)
    }

    companion object {
        fun binding(vault: BitwardenVault): BitwardenTransportSecretBinding {
            val canonicalBase = BitwardenEnvironmentResolver.validateEndpoint(
                vault.serverUrl, BitwardenEnvironmentField.BASE,
            ).value?.toString() ?: throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.INVALID_BINDING)
            return BitwardenTransportSecretBinding(vault.id, canonicalBase, vault.transportRevision,
                vault.transportSecretRef ?: throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.MISSING))
        }

        private fun checked(result: BitwardenValidation<BitwardenEnvironment>): BitwardenEnvironment =
            result.value ?: throw BitwardenTransportSecretException(BitwardenTransportSecretFailure.CORRUPT)

        private fun BitwardenVault.legacyTls() = BitwardenTlsConfig(
            tlsCertificateAlias, tlsCaCertificatePem, tlsMtlsEnabled,
            tlsClientCertPkcs12Base64, tlsEncryptedClientCertPassword,
        )
    }
}

internal fun BitwardenEnvironment.toDraft() = BitwardenEnvironmentDraft(
    preset, base.toString(), web.toString().takeIf { webExplicit }, api.toString(),
    identity.toString(), notifications.toString(), headers.map { BitwardenHeaderDraft(it.name, it.value) }, tls,
)
