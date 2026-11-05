package takagi.ru.monica.bitwarden

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.BitwardenEnvironment
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenHeader
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretBinding
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretException
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretFailure
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretStore
import takagi.ru.monica.data.bitwarden.BitwardenVault

/** Tests the protected-store adapter contract; this fake is not disk-encryption evidence. */
class BitwardenTransportSecretStoreTest {
    private val entries = linkedMapOf<String, String>()
    private val events = mutableListOf<String>()
    private var nextId = 1

    private fun store(
        read: (String) -> String? = { events += "read:$it"; entries[it] },
        write: (String, String) -> Unit = { name, value -> events += "write:$name"; entries[name] = value },
        remove: (String) -> Unit = { events += "remove:$it"; entries.remove(it); Unit },
        newId: () -> String = { uuid(nextId++) },
    ) = BitwardenTransportSecretStore(read, write, remove, newId)

    private fun environment(
        revision: String = REVISION,
        headers: List<BitwardenHeaderDraft> = listOf(BitwardenHeaderDraft("X-Proxy-Secret", HEADER_SECRET)),
    ): BitwardenEnvironment {
        val result = BitwardenEnvironmentResolver.resolve(
            BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, BASE, headers = headers),
            revision,
        )
        assertTrue(result.errors.toString(), result.isValid)
        return requireNotNull(result.value)
    }

    // Deliberately bypass certificate validation only to test lossless payload serialization.
    // These TLS sentinels do not claim to be usable certificates or pass the resolver.
    private fun environmentWithTls(tls: BitwardenTlsConfig): BitwardenEnvironment {
        val base = BASE.toHttpUrl()
        return BitwardenEnvironment(
            base, base, base.resolve("api/")!!, base.resolve("identity/")!!,
            base.resolve("notifications/")!!, BitwardenEnvironmentPreset.CUSTOM, false,
            listOf(BitwardenHeader("X-Proxy-Secret", HEADER_SECRET), BitwardenHeader("X-Empty", "")),
            tls, REVISION, "0".repeat(64),
        )
    }

    private fun changed(
        binding: BitwardenTransportSecretBinding,
        vaultId: Long = binding.vaultId,
        base: String = binding.serverUrl,
        revision: String = binding.revision,
        reference: String = binding.reference,
    ) = BitwardenTransportSecretBinding(vaultId, base, revision, reference)

    private fun expectFailure(reason: BitwardenTransportSecretFailure, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected typed failure $reason", error is BitwardenTransportSecretException)
        error as BitwardenTransportSecretException
        assertEquals(reason, error.reason)
        assertNull(error.cause)
        assertFalse(error.toString().contains(HEADER_SECRET))
        assertFalse(error.stackTraceToString().contains(PROVIDER_SECRET))
    }

    private fun payloadWith(raw: String, name: String, value: JsonElement): String =
        JsonObject(Json.parseToJsonElement(raw).jsonObject.toMutableMap().apply { put(name, value) }).toString()

    @Test
    fun stageVerifiesProtectedReadbackAndRecreatedAdapterPreservesExactHeaderAndTlsValues() {
        val tls = BitwardenTlsConfig("synthetic-alias", "synthetic-ca", true, "synthetic-p12", "  ")
        val binding = store().stage(VAULT_ID, environmentWithTls(tls))

        assertEquals(listOf("read:${binding.reference}", "write:${binding.reference}", "read:${binding.reference}"), events)
        assertEquals(VAULT_ID, binding.vaultId)
        assertEquals(BASE, binding.serverUrl)
        assertEquals(REVISION, binding.revision)
        val loaded = store().load(binding)
        assertEquals(listOf("X-Proxy-Secret", "X-Empty"), loaded.headers.map { it.name })
        assertEquals(listOf(HEADER_SECRET, ""), loaded.headers.map { it.value })
        assertEquals(tls, loaded.tls)
        assertEquals("  ", loaded.tls.clientCertPassword)
        assertEquals("0".repeat(64), loaded.configurationFingerprint)
    }

    @Test
    fun freshEmptyPayloadIsAuthoritativeAndDoesNotRevivePriorHeadersOrTls() {
        val adapter = store()
        val old = adapter.stage(VAULT_ID, environmentWithTls(BitwardenTlsConfig(clientCertPassword = "old-secret")))
        val cleared = adapter.stage(VAULT_ID, environment(uuid(102), emptyList()))
        assertNotEquals(old.reference, cleared.reference)
        assertNotEquals(old.revision, cleared.revision)
        assertTrue(adapter.load(cleared).headers.isEmpty())
        assertEquals(BitwardenTlsConfig(), adapter.load(cleared).tls)
        assertEquals(HEADER_SECRET, adapter.load(old).headers.first().value)
        assertEquals(2, entries.size)
        assertFalse(events.any { it.startsWith("remove:") })
    }

    @Test
    fun persistedBindingSurvivesAccountKeyPromotionWithoutSerializingMutableAccountIdentity() {
        val binding = store().stage(VAULT_ID, environment())
        val emailRow = BitwardenVault(
            id = VAULT_ID, email = "synthetic@example.invalid", canonicalEmail = "synthetic@example.invalid",
            accountKey = "email-form-key", serverUrl = binding.serverUrl,
            transportRevision = binding.revision, transportSecretRef = binding.reference,
        )
        val idRow = emailRow.copy(accountKey = "user-id-form-key", userId = "synthetic-user-id")
        val reloggedRow = idRow.copy(accountKey = "email-form-key", userId = null)
        for (row in listOf(emailRow, idRow, reloggedRow)) {
            val rowBinding = BitwardenTransportSecretBinding(
                row.id, row.serverUrl, row.transportRevision, requireNotNull(row.transportSecretRef),
            )
            assertEquals(HEADER_SECRET, store().load(rowBinding).headers.single().value)
        }
        val raw = entries.getValue(binding.reference)
        for (absent in listOf("accountKey", "account_key", "canonicalEmail", "userId", emailRow.email, idRow.accountKey)) {
            assertFalse("Mutable account identity must not enter durable binding", raw.contains(absent))
        }
    }

    @Test
    fun invalidBindingAndCollidingReferenceNeverWriteOrRemoveProtectedValues() {
        expectFailure(BitwardenTransportSecretFailure.INVALID_BINDING) { store().stage(0, environment()) }
        expectFailure(BitwardenTransportSecretFailure.INVALID_BINDING) {
            store(newId = { "not-a-uuid" }).stage(VAULT_ID, environment())
        }
        assertTrue(events.isEmpty())
        val adapter = store(newId = { uuid(19) })
        val binding = adapter.stage(VAULT_ID, environment())
        val before = entries.toMap()
        events.clear()
        expectFailure(BitwardenTransportSecretFailure.INVALID_BINDING) { adapter.stage(VAULT_ID, environment()) }
        assertEquals(before, entries)
        assertEquals(listOf("read:${binding.reference}"), events)
    }

    @Test
    fun invalidExternalBindingsAreRejectedBeforeLookingUpProtectedEntries() {
        val good = BitwardenTransportSecretBinding(VAULT_ID, BASE, REVISION, BitwardenTransportSecretStore.PREFIX + uuid(1))
        for (bad in listOf(changed(good, vaultId = -1), changed(good, revision = "legacy-revision"),
            changed(good, reference = "unrelated_preference"), changed(good, base = BASE.removeSuffix("/")),
            changed(good, base = "https://user:secret@proxy.example.invalid/Vault/"))) {
            expectFailure(BitwardenTransportSecretFailure.INVALID_BINDING) { store().load(bad) }
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun missingOrAlteredReadbackCannotPublishAStagedBindingOrDeleteTheWrittenEntry() {
        expectFailure(BitwardenTransportSecretFailure.WRITE_NOT_VERIFIED) {
            store(write = { _, _ -> }).stage(VAULT_ID, environment())
        }
        expectFailure(BitwardenTransportSecretFailure.WRITE_NOT_VERIFIED) {
            store(write = { name, _ -> entries[name] = "altered-provider-value" }).stage(VAULT_ID, environment())
        }
        assertEquals(listOf("altered-provider-value"), entries.values.toList())
        assertFalse(events.any { it.startsWith("remove:") })
    }

    @Test
    fun providerFailureAfterWriteRetainsEntryAndReturnsOnlyTypedRedactedFailure() {
        var reads = 0
        val adapter = store(read = { name ->
            if (++reads == 1) entries[name] else throw IllegalStateException(PROVIDER_SECRET)
        })
        expectFailure(BitwardenTransportSecretFailure.UNAVAILABLE) { adapter.stage(VAULT_ID, environment()) }
        assertEquals(1, entries.size)
        assertTrue(entries.values.single().contains(HEADER_SECRET))
        assertFalse(events.any { it.startsWith("remove:") })
    }

    @Test
    fun readWriteAndRemoveProviderExceptionsNeverExposeProviderMessageOrCause() {
        val providerError = IllegalArgumentException(PROVIDER_SECRET)
        expectFailure(BitwardenTransportSecretFailure.UNAVAILABLE) {
            store(read = { throw providerError }).stage(VAULT_ID, environment())
        }
        expectFailure(BitwardenTransportSecretFailure.UNAVAILABLE) {
            store(write = { _, _ -> throw providerError }).stage(VAULT_ID, environment())
        }
        val binding = store().stage(VAULT_ID, environment())
        expectFailure(BitwardenTransportSecretFailure.UNAVAILABLE) {
            store(read = { throw providerError }).load(binding)
        }
        expectFailure(BitwardenTransportSecretFailure.UNAVAILABLE) {
            store(remove = { throw providerError }).deleteIfUnreferenced(binding) { false }
        }
        assertTrue(entries.containsKey(binding.reference))
    }

    @Test
    fun cancellationFromProtectedProvidersPropagatesWithoutBeingReclassified() {
        val cancelled = CancellationException("synthetic cancellation")
        val binding = store().stage(VAULT_ID, environment())
        val actions = listOf<() -> Unit>(
            { store(read = { throw cancelled }).load(binding) },
            { store(write = { _, _ -> throw cancelled }).stage(VAULT_ID, environment()) },
            { store(remove = { throw cancelled }).deleteIfUnreferenced(binding) { false } },
        )
        for (action in actions) assertSame(cancelled, runCatching(action).exceptionOrNull())
        assertTrue(entries.containsKey(binding.reference))
    }

    @Test
    fun absentMalformedUnknownVersionAndOversizedPayloadsFailClosed() {
        val adapter = store()
        val binding = adapter.stage(VAULT_ID, environment())
        val original = entries.getValue(binding.reference)
        entries.remove(binding.reference)
        expectFailure(BitwardenTransportSecretFailure.MISSING) { adapter.load(binding) }
        val corrupt = listOf("{", "{}", payloadWith(original, "version", JsonPrimitive(2)),
            payloadWith(original, "configurationFingerprint", JsonPrimitive("invalid-fingerprint")),
            payloadWith(original, "unknownField", JsonPrimitive("synthetic")), "x".repeat(3 * 1024 * 1024 + 1))
        for (raw in corrupt) {
            entries[binding.reference] = raw
            expectFailure(BitwardenTransportSecretFailure.CORRUPT) { adapter.load(binding) }
            assertEquals(raw, entries[binding.reference])
        }
    }

    @Test
    fun changedVaultBaseRevisionOrCopiedReferenceCannotOpenAnotherBinding() {
        val adapter = store()
        val binding = adapter.stage(VAULT_ID, environment())
        val original = entries.getValue(binding.reference)
        val copied = BitwardenTransportSecretStore.PREFIX + uuid(80)
        entries[copied] = original
        for (other in listOf(changed(binding, vaultId = VAULT_ID + 1),
            changed(binding, base = "https://other.example.invalid/Vault/"),
            changed(binding, revision = uuid(103)), changed(binding, reference = copied))) {
            expectFailure(BitwardenTransportSecretFailure.BINDING_MISMATCH) { adapter.load(other) }
        }
        assertEquals(HEADER_SECRET, adapter.load(binding).headers.single().value)
        assertEquals(original, entries[binding.reference])
    }

    @Test
    fun decodedReservedOrInjectedHeadersAreCorruptRatherThanAnUnprotectedFallback() {
        val adapter = store()
        val binding = adapter.stage(VAULT_ID, environment())
        val original = entries.getValue(binding.reference)
        for ((name, value) in listOf("Authorization" to HEADER_SECRET, "X-Proxy" to "value\r\nInjected: secret")) {
            val header = JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value)))
            entries[binding.reference] = payloadWith(original, "headers", JsonArray(listOf(header)))
            expectFailure(BitwardenTransportSecretFailure.CORRUPT) { adapter.load(binding) }
        }
    }

    @Test
    fun exposedContainersAreRedactedAndLoadedHeaderCollectionCannotBeChanged() {
        val env = environmentWithTls(BitwardenTlsConfig(clientCertPassword = PROVIDER_SECRET))
        val binding = store().stage(VAULT_ID, env)
        val secrets = store().load(binding)
        for (text in listOf(env.toString(), binding.toString(), secrets.toString(), secrets.headers.toString())) {
            assertFalse(text.contains(HEADER_SECRET))
            assertFalse(text.contains(PROVIDER_SECRET))
            assertFalse(text.contains(binding.reference))
        }
        @Suppress("UNCHECKED_CAST")
        val mutable = secrets.headers as MutableList<BitwardenHeaderDraft>
        assertTrue(runCatching { mutable.clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(2, secrets.headers.size)
        assertEquals(HEADER_SECRET, store().load(binding).headers.first().value)
    }

    @Test
    fun retirementRejectsLiveReferencesBeforeReadingOrRemovingAnEntry() {
        val adapter = store()
        val binding = adapter.stage(VAULT_ID, environment())
        events.clear()
        expectFailure(BitwardenTransportSecretFailure.STILL_REFERENCED) {
            adapter.deleteIfUnreferenced(binding) { reference ->
                assertEquals(binding.reference, reference)
                true
            }
        }
        assertTrue(events.isEmpty())
        assertTrue(entries.containsKey(binding.reference))
    }

    @Test
    fun retirementCannotDeleteMismatchedOrCorruptEntriesEvenWhenUnreferenced() {
        val adapter = store()
        val binding = adapter.stage(VAULT_ID, environment())
        val other = adapter.stage(VAULT_ID + 1, environment(uuid(104)))
        val before = entries.toMap()
        expectFailure(BitwardenTransportSecretFailure.BINDING_MISMATCH) {
            adapter.deleteIfUnreferenced(changed(binding, reference = other.reference)) { false }
        }
        assertEquals(before, entries)
        entries[binding.reference] = "corrupt"
        expectFailure(BitwardenTransportSecretFailure.CORRUPT) { adapter.deleteIfUnreferenced(binding) { false } }
        assertEquals("corrupt", entries[binding.reference])
        assertEquals(before[other.reference], entries[other.reference])
        assertFalse(events.any { it.startsWith("remove:") })
    }

    @Test
    fun retirementDeletesOnlyVerifiedUnreferencedEntryAndMissingEntryIsIdempotent() {
        val adapter = store()
        val old = adapter.stage(VAULT_ID, environment())
        val active = adapter.stage(VAULT_ID, environment(uuid(105)))
        adapter.deleteIfUnreferenced(old) { it == active.reference }
        assertFalse(entries.containsKey(old.reference))
        assertEquals(HEADER_SECRET, adapter.load(active).headers.single().value)
        events.clear()
        adapter.deleteIfUnreferenced(old) { false }
        assertEquals(listOf("read:${old.reference}"), events)
    }

    @Test
    fun ignoredRemovalFailsReadbackAndNeverRemovesOtherConfiguration() {
        val binding = store().stage(VAULT_ID, environment())
        val before = entries.toMap()
        expectFailure(BitwardenTransportSecretFailure.WRITE_NOT_VERIFIED) {
            store(remove = {}).deleteIfUnreferenced(binding) { false }
        }
        assertEquals(before, entries)
    }

    private companion object {
        const val VAULT_ID = 41L
        const val BASE = "https://proxy.example.invalid/Vault/"
        const val REVISION = "00000000-0000-4000-8000-000000000100"
        const val HEADER_SECRET = "  synthetic-proxy-secret  "
        const val PROVIDER_SECRET = "synthetic-provider-private-content"
        fun uuid(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    }
}
