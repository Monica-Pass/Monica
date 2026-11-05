package takagi.ru.monica.bitwarden

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretException
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretFailure
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretStore
import takagi.ru.monica.bitwarden.api.BitwardenVaultEnvironmentStore
import takagi.ru.monica.data.bitwarden.BitwardenVault

/** Routing/protected-reference adapter tests; fake callbacks are not device encryption evidence. */
class BitwardenVaultEnvironmentStoreTest {
    private val entries = linkedMapOf<String, String>()
    private val protectedCalls = mutableListOf<String>()
    private val protectedStore = BitwardenTransportSecretStore(
        read = { name -> protectedCalls += "read"; entries[name] },
        write = { name, value -> protectedCalls += "write"; entries[name] = value },
        remove = { name -> protectedCalls += "remove"; entries.remove(name); Unit },
        newId = { "00000000-0000-4000-8000-000000000201" },
    )

    private fun legacy() = BitwardenVault(
        id = 41, email = "synthetic@example.invalid", canonicalEmail = "synthetic@example.invalid",
        accountKey = "email-form-key", serverUrl = "https://proxy.example.invalid/Vault",
        apiUrl = "https://api.example.invalid/Explicit/V2", identityUrl = "https://identity.example.invalid/Auth",
    )

    private fun newRow(headers: List<BitwardenHeaderDraft> = emptyList()): BitwardenVault {
        val environment = requireNotNull(BitwardenEnvironmentResolver.resolve(
            BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, BASE,
                webUrl = "https://web.example.invalid/Team/", apiUrl = legacy().apiUrl,
                identityUrl = legacy().identityUrl, notificationsUrl = "https://notify.example.invalid/Notify/",
                headers = headers), REVISION,
        ).value)
        val binding = protectedStore.stage(41, environment)
        return legacy().copy(
            serverUrl = binding.serverUrl, environmentPreset = "CUSTOM",
            webVaultUrl = "https://web.example.invalid/Team/", notificationsUrl = "https://notify.example.invalid/Notify/",
            transportRevision = binding.revision, transportSecretRef = binding.reference,
        )
    }

    private fun expectFailure(reason: BitwardenTransportSecretFailure, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected $reason", error is BitwardenTransportSecretException)
        error as BitwardenTransportSecretException
        assertEquals(reason, error.reason)
        assertNull(error.cause)
    }

    @Test
    fun legacyResolutionDoesNotUseProtectedStoreAndKeepsRevisionAcrossAccountKeyPromotion() {
        val resolver = BitwardenVaultEnvironmentStore(protectedStore)
        val original = legacy()
        val first = resolver.resolve(original)
        assertEquals(BASE, first.base.toString())
        assertEquals("https://api.example.invalid/Explicit/V2/", first.api.toString())
        assertEquals("https://identity.example.invalid/Auth/", first.identity.toString())
        val promoted = original.copy(accountKey = "user-id-form-key", userId = "synthetic-user-id")
        assertEquals(first.revision, resolver.resolve(promoted).revision)
        assertEquals(first.revision, resolver.resolve(promoted.copy(accountKey = original.accountKey, userId = null)).revision)
        assertEquals("", original.transportRevision)
        assertNull(original.transportSecretRef)
        assertTrue(protectedCalls.isEmpty())
        assertTrue(entries.isEmpty())
    }

    @Test
    fun legacyEndpointChangeGetsFreshRevisionWhileOtherVaultAndEquivalentReadRemainStable() {
        val resolver = BitwardenVaultEnvironmentStore(protectedStore)
        val row = legacy()
        val first = resolver.resolve(row)
        val other = row.copy(id = 42, accountKey = "other-key")
        val otherRevision = resolver.resolve(other).revision
        val changed = row.copy(apiUrl = "https://api.example.invalid/Explicit/V3/")
        val updated = resolver.resolve(changed)
        assertNotEquals(first.revision, updated.revision)
        assertEquals("https://api.example.invalid/Explicit/V3/", updated.api.toString())
        assertEquals(updated.revision, resolver.resolve(changed).revision)
        assertEquals(otherRevision, resolver.resolve(other).revision)
        assertTrue(protectedCalls.isEmpty())
    }

    @Test
    fun protectedEmptyTlsOverridesInvalidLegacyTlsWithoutWritingOrFallingBack() {
        val saved = newRow(listOf(BitwardenHeaderDraft("X-Proxy-Secret", "  synthetic secret  "))).copy(
            tlsMtlsEnabled = true, tlsCertificateAlias = "old-alias", tlsCaCertificatePem = "invalid-legacy-ca",
            tlsClientCertPkcs12Base64 = "invalid-legacy-p12", tlsEncryptedClientCertPassword = "old-password",
        )
        protectedCalls.clear()
        val environment = BitwardenVaultEnvironmentStore(protectedStore).resolve(saved)
        assertEquals(BitwardenTlsConfig(), environment.tls)
        assertEquals(listOf("  synthetic secret  "), environment.headers.map { it.value })
        assertEquals("https://web.example.invalid/Team/", environment.web.toString())
        assertEquals("https://notify.example.invalid/Notify/", environment.notifications.toString())
        assertEquals(REVISION, environment.revision)
        assertEquals(listOf("read"), protectedCalls)
    }

    @Test
    fun missingCorruptOrUnavailableProtectedEntryNeverReconnectsUsingLegacySettings() {
        val saved = newRow()
        val reference = requireNotNull(saved.transportSecretRef)
        val resolver = BitwardenVaultEnvironmentStore(protectedStore)
        entries.remove(reference)
        expectFailure(BitwardenTransportSecretFailure.MISSING) { resolver.resolve(saved) }
        entries[reference] = "malformed-protected-payload"
        expectFailure(BitwardenTransportSecretFailure.CORRUPT) { resolver.resolve(saved) }
        expectFailure(BitwardenTransportSecretFailure.UNAVAILABLE) { BitwardenVaultEnvironmentStore(null).resolve(saved) }
        assertEquals("malformed-protected-payload", entries[reference])
    }

    @Test
    fun partialNewColumnsAndChangedDestinationsFailClosedBeforeAReplacementEnvironmentIsReturned() {
        val resolver = BitwardenVaultEnvironmentStore(protectedStore)
        val old = legacy()
        for (partial in listOf(old.copy(transportRevision = REVISION), old.copy(environmentPreset = "CUSTOM"),
            old.copy(webVaultUrl = "https://web.example.invalid/"), old.copy(notificationsUrl = "https://notify.example.invalid/"))) {
            expectFailure(BitwardenTransportSecretFailure.MISSING) { resolver.resolve(partial) }
        }
        assertTrue(protectedCalls.isEmpty())
        val saved = newRow(listOf(BitwardenHeaderDraft("X-Proxy-Secret", "synthetic custom-only secret")))
        expectFailure(BitwardenTransportSecretFailure.CORRUPT) { resolver.resolve(saved.copy(environmentPreset = "UNKNOWN")) }
        for (preset in listOf("US", "EU")) {
            // The protected payload is bound to custom BASE. Preset resolution must not retarget it.
            expectFailure(BitwardenTransportSecretFailure.BINDING_MISMATCH) { resolver.resolve(saved.copy(environmentPreset = preset)) }
        }
        for (retargeted in listOf(
            saved.copy(apiUrl = "https://replacement.example.invalid/api/"),
            saved.copy(identityUrl = "https://replacement.example.invalid/identity/"),
            saved.copy(webVaultUrl = "https://replacement.example.invalid/web/"),
            saved.copy(notificationsUrl = "https://replacement.example.invalid/notify/"),
        )) {
            // A matching Base/ref/revision cannot authorize changed Room service destinations.
            expectFailure(BitwardenTransportSecretFailure.BINDING_MISMATCH) { resolver.resolve(retargeted) }
        }
        assertEquals(BASE, resolver.resolve(saved).base.toString())
        assertEquals(1, entries.size)
    }

    private companion object {
        const val BASE = "https://proxy.example.invalid/Vault/"
        const val REVISION = "00000000-0000-4000-8000-000000000200"
    }
}
