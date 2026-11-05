package takagi.ru.monica.bitwarden.api

import org.junit.Assert.*
import org.junit.Test

class BitwardenEnvironmentResolverTest {
    private val revision = "12345678-1234-1234-1234-123456789abc"

    private fun resolved(draft: BitwardenEnvironmentDraft, rev: String = revision): BitwardenEnvironment {
        val result = BitwardenEnvironmentResolver.resolve(draft, rev)
        assertTrue(result.errors.toString(), result.isValid)
        return requireNotNull(result.value)
    }

    @Test fun officialPresetsKeepMonicaDefaultsAndNoReferer() {
        listOf(BitwardenEnvironmentPreset.US to "com", BitwardenEnvironmentPreset.EU to "eu").forEach { (preset, region) ->
            val env = resolved(BitwardenEnvironmentDraft(preset, baseUrl = "https://inactive.example/"))
            assertEquals("https://vault.bitwarden.$region/", env.base.toString())
            assertEquals(env.base, env.web)
            assertEquals("https://api.bitwarden.$region/", env.api.toString())
            assertEquals("https://identity.bitwarden.$region/", env.identity.toString())
            assertEquals("https://notifications.bitwarden.$region/hub", BitwardenTransportPolicy.notificationHubUrl(env).toString())
            assertEquals("https://send.bitwarden.$region/#/send/", BitwardenTransportPolicy.sendUrlPrefix(env))
            assertNull(BitwardenTransportPolicy.referer(env))
            assertFalse(env.webExplicit)
        }
    }

    @Test fun selfHostedCaseSensitivePathsAndIpPortsArePreserved() {
        val ipv4 = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, "http://192.168.1.8:8080/Vault"))
        assertEquals("http://192.168.1.8:8080/Vault/api/", ipv4.api.toString())
        assertEquals("http://192.168.1.8:8080/Vault/identity/", ipv4.identity.toString())
        assertEquals("http://192.168.1.8:8080/Vault/", BitwardenTransportPolicy.referer(ipv4))
        val ipv6 = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, "https://[2001:db8::5]:8443/Team/"))
        assertEquals("https://[2001:db8::5]:8443/Team/notifications/hub", BitwardenTransportPolicy.notificationHubUrl(ipv6).toString())
        assertEquals("https://[2001:db8::5]:8443/Team/#/send/", BitwardenTransportPolicy.sendUrlPrefix(ipv6))
    }

    @Test fun everyExplicitServiceIsUsedWithoutDoubleSuffixes() {
        val env = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, "https://base.example/Vault/",
            webUrl = "https://web.example/Web", apiUrl = "https://api.example/V2",
            identityUrl = "https://login.example/Auth/", notificationsUrl = "https://notify.example/Notify"))
        assertEquals("https://web.example/Web/", env.web.toString())
        assertEquals("https://api.example/V2/", env.api.toString())
        assertEquals("https://login.example/Auth/", env.identity.toString())
        assertEquals("https://notify.example/Notify/hub", BitwardenTransportPolicy.notificationHubUrl(env).toString())
        assertEquals("https://base.example/Vault/", BitwardenTransportPolicy.referer(env))
        assertEquals("https://web.example/Web/#/send/", BitwardenTransportPolicy.sendUrlPrefix(env))
    }

    @Test fun explicitOfficialLookingCustomUrlsAreNeverReclassified() {
        val env = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            "https://vault.bitwarden.com/Team", webUrl = "https://vault.bitwarden.eu:8443/Other"))
        assertEquals(BitwardenEnvironmentPreset.CUSTOM, env.preset)
        assertEquals("https://vault.bitwarden.com/Team/api/", env.api.toString())
        assertEquals("https://vault.bitwarden.eu:8443/Other/#/send/", BitwardenTransportPolicy.sendUrlPrefix(env))
        val root = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, "https://vault.bitwarden.com"))
        assertEquals("https://vault.bitwarden.com/api/", root.api.toString())
        assertEquals("https://vault.bitwarden.com/#/send/", BitwardenTransportPolicy.sendUrlPrefix(root))
    }

    @Test fun blankOverridesInheritButInvalidExplicitOverridesNeverFallback() {
        val inherited = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            "https://base.example/Vault", apiUrl = "  ", identityUrl = ""))
        assertEquals("https://base.example/Vault/api/", inherited.api.toString())
        val invalid = BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US,
            apiUrl = " https://api.example/ ", identityUrl = "https://user:secret@login.example"), revision)
        assertNull(invalid.value)
        assertTrue(invalid.errors.any { it.field == BitwardenEnvironmentField.API && it.code == BitwardenEnvironmentErrorCode.CONTROL_OR_WHITESPACE })
        assertTrue(invalid.errors.any { it.field == BitwardenEnvironmentField.IDENTITY && it.code == BitwardenEnvironmentErrorCode.USERINFO_FORBIDDEN })
    }

    @Test fun endpointsRejectRawFormsBeforeLenientUrlNormalization() {
        val cases = mapOf(
            "" to BitwardenEnvironmentErrorCode.REQUIRED,
            "https://@example.test/" to BitwardenEnvironmentErrorCode.USERINFO_FORBIDDEN,
            "https://example.test/?" to BitwardenEnvironmentErrorCode.QUERY_FORBIDDEN,
            "https://example.test/#" to BitwardenEnvironmentErrorCode.FRAGMENT_FORBIDDEN,
            "https://example.test/a\nb" to BitwardenEnvironmentErrorCode.CONTROL_OR_WHITESPACE,
            "ftp://example.test/" to BitwardenEnvironmentErrorCode.UNSUPPORTED_SCHEME,
            "https://example.test\\evil" to BitwardenEnvironmentErrorCode.UNSAFE_PATH,
            "https://example.test/A/../B" to BitwardenEnvironmentErrorCode.UNSAFE_PATH,
            "https://example.test/A/.%2e/B" to BitwardenEnvironmentErrorCode.UNSAFE_PATH,
            "https://example.test/A/%2F/B" to BitwardenEnvironmentErrorCode.UNSAFE_PATH,
            "https://example.test/A/%5c/B" to BitwardenEnvironmentErrorCode.UNSAFE_PATH,
            "https://example.test/%00" to BitwardenEnvironmentErrorCode.UNSAFE_PATH,
            "https://example.test:0/" to BitwardenEnvironmentErrorCode.INVALID_URL,
            "https://example.test:/" to BitwardenEnvironmentErrorCode.INVALID_URL,
            "https://example.test:65536/" to BitwardenEnvironmentErrorCode.INVALID_URL,
            "https:///path" to BitwardenEnvironmentErrorCode.INVALID_URL,
            "https://[broken]/" to BitwardenEnvironmentErrorCode.INVALID_URL,
            "https://example.test/unfinished%" to BitwardenEnvironmentErrorCode.INVALID_URL,
        )
        cases.forEach { (raw, code) ->
            val validation = BitwardenEnvironmentResolver.validateEndpoint(raw, BitwardenEnvironmentField.BASE)
            assertNull(raw, validation.value)
            assertEquals(raw, listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.BASE, code)), validation.errors)
        }
    }

    @Test fun discoveryEndpointValidationRequiresHttpsAndKeepsSafeEncodedPaths() {
        assertEquals(BitwardenEnvironmentErrorCode.HTTPS_REQUIRED, BitwardenEnvironmentResolver.validateEndpoint(
            "http://192.168.1.8:8080/Vault", BitwardenEnvironmentField.BASE, requireHttps = true).errors.single().code)
        val valid = BitwardenEnvironmentResolver.validateEndpoint("https://[::1]:8443/Team%20Name", BitwardenEnvironmentField.BASE, true)
        assertEquals("https://[::1]:8443/Team%20Name/", valid.value.toString())
        assertTrue(valid.errors.isEmpty())
    }

    @Test fun legacyRowsPreserveActualOverridesAndDeriveMissingOnCustomBase() {
        val stored = BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot(
            serverUrl = "https://legacy.example/Vault", apiUrl = "https://split.example/API/"), revision)
        assertTrue(stored.errors.toString(), stored.isValid)
        assertEquals("https://split.example/API/", stored.value!!.api.toString())
        assertEquals("https://legacy.example/Vault/identity/", stored.value!!.identity.toString())
        val official = BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot("https://identity.bitwarden.eu"), revision)
        assertEquals(BitwardenEnvironmentPreset.EU, official.value!!.preset)
        assertEquals("https://vault.bitwarden.eu/", official.value!!.base.toString())
    }

    @Test fun invalidLegacyOverridesAndOfficialLookalikePathsDoNotFallBack() {
        val invalid = BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot(
            "https://legacy.example/Vault", apiUrl = "javascript:bad"), revision)
        assertNull(invalid.value)
        assertEquals(BitwardenEnvironmentField.API, invalid.errors.single().field)
        val custom = BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot("https://vault.bitwarden.com/Case"), revision)
        assertEquals(BitwardenEnvironmentPreset.CUSTOM, custom.value!!.preset)
        assertEquals("https://vault.bitwarden.com/Case/api/", custom.value!!.api.toString())
    }

    @Test fun fingerprintIgnoresCallerRevisionAndHeaderOrderButIncludesValues() {
        fun draft(headers: List<BitwardenHeaderDraft>) = BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            "https://base.example/Vault", headers = headers)
        val first = resolved(draft(listOf(BitwardenHeaderDraft("X-Second", "two"), BitwardenHeaderDraft("X-First", "one"))))
        val equivalent = resolved(draft(listOf(BitwardenHeaderDraft("x-first", "one"), BitwardenHeaderDraft("x-second", "two"))),
            "ffffffff-1234-1234-1234-123456789abc")
        assertEquals(first.fingerprint, equivalent.fingerprint)
        assertNotEquals(first.revision, equivalent.revision)
        assertNotEquals(first.fingerprint, resolved(draft(listOf(BitwardenHeaderDraft("X-First", "changed")))).fingerprint)
        assertEquals(listOf("X-Second", "X-First"), first.headers.map { it.name })
    }

    @Test fun fingerprintIncludesExplicitWebProvenanceAndTlsMetadata() {
        val inherited = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US))
        val explicit = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US, webUrl = inherited.web.toString()))
        assertEquals(inherited.web, explicit.web)
        assertNotEquals(inherited.fingerprint, explicit.fingerprint)
        assertNotEquals(BitwardenTransportPolicy.sendUrlPrefix(inherited), BitwardenTransportPolicy.sendUrlPrefix(explicit))
        val metadata = resolved(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US, tls = BitwardenTlsConfig(certificateAlias = "label")))
        assertNotEquals(inherited.fingerprint, metadata.fingerprint)
    }

    @Test fun sourceCollectionsCannotModifyAnExistingEnvironmentOrErrors() {
        val source = mutableListOf(BitwardenHeaderDraft("X-Credential", "first"))
        val draft = BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US, headers = source)
        source.clear()
        val env = resolved(draft)
        assertEquals("first", env.headers.single().value)
        assertThrows(UnsupportedOperationException::class.java) { (env.headers as MutableList).clear() }
        val invalid = BitwardenEnvironmentResolver.validateEndpoint("", BitwardenEnvironmentField.BASE)
        assertThrows(UnsupportedOperationException::class.java) { (invalid.errors as MutableList).clear() }
    }

    @Test fun stringificationDoesNotExposeSecretInputsOrFingerprint() {
        val secret = "synthetic-sensitive-value"
        val header = BitwardenHeaderDraft("X-Private", secret)
        val draft = BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US, headers = listOf(header),
            tls = BitwardenTlsConfig(clientCertPassword = secret))
        val result = BitwardenEnvironmentResolver.resolve(draft, revision)
        val env = requireNotNull(result.value)
        listOf(header.toString(), draft.toString(), result.toString(), env.toString(), env.headers.single().toString(),
            BitwardenLegacyEnvironmentSnapshot(secret).toString()).forEach {
            assertFalse(it.contains(secret)); assertFalse(it.contains(env.fingerprint))
        }
        val rejected = BitwardenEnvironmentResolver.validateEndpoint("https://user:$secret@example.test/", BitwardenEnvironmentField.BASE)
        assertFalse(rejected.toString().contains(secret))
        assertFalse(rejected.errors.toString().contains(secret))
    }

    @Test fun invalidRevisionIsAValidationErrorAndNonEndpointFieldIsProgrammerError() {
        val result = BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.US), "not-a-revision")
        assertEquals(listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.REVISION, BitwardenEnvironmentErrorCode.INVALID_REVISION)), result.errors)
        assertThrows(IllegalArgumentException::class.java) {
            BitwardenEnvironmentResolver.validateEndpoint("https://example.test", BitwardenEnvironmentField.HEADER_NAME)
        }
    }
}
