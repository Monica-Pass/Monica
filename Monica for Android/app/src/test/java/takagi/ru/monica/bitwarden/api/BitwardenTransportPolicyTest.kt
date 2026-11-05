package takagi.ru.monica.bitwarden.api

import org.junit.Assert.*
import org.junit.Test

class BitwardenTransportPolicyTest {
    private fun environment(http: Boolean = false): BitwardenEnvironment = requireNotNull(
        BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            baseUrl = "${if (http) "http" else "https"}://base.example:8443/Vault/",
            webUrl = "https://web.example/Web/", apiUrl = "https://api.example/API/",
            identityUrl = "https://login.example/Identity/", notificationsUrl = "https://notify.example/Notify/"),
            "12345678-1234-1234-1234-123456789abc").value)

    @Test fun exactNativeScopesPermitQueriesButRejectOtherOriginsPathsAndWebOnly() {
        val env = environment()
        listOf("https://base.example:8443/Vault", "https://base.example:8443/Vault/file?sig=synthetic",
            "https://api.example/API/ciphers", "https://login.example/Identity/connect/token", "https://notify.example/Notify/hub")
            .forEach { assertTrue(it, BitwardenTransportPolicy.canUseAccountTransport(env, it)) }
        listOf("https://base.example:8443/Vault2/file", "https://base.example:8443/vault/file",
            "https://base.example/Vault/file", "https://child.base.example:8443/Vault/file",
            "http://base.example:8443/Vault/file", "https://base.example:8443/Other/file",
            "https://web.example/Web/", "https://api.example/Other/")
            .forEach { assertFalse(it, BitwardenTransportPolicy.canUseAccountTransport(env, it)) }
    }

    @Test fun normalizationCannotRestoreAccountCredentialsToAnAmbiguousRawTransferPath() {
        val env = environment()
        listOf("https://base.example:8443/Vault/a/../file?sig=synthetic",
            "https://base.example:8443/Vault/%2E/file", "https://base.example:8443/Vault/a%2Fb")
            .forEach { raw ->
                assertFalse(raw, BitwardenTransportPolicy.canUseAccountTransport(env, raw))
                val target = requireNotNull(BitwardenTransportPolicy.validateTransferUrl(env, raw).value)
                assertFalse(target.useAccountTransport)
                assertTrue(target.url.isHttps)
            }
        val normalized = requireNotNull(BitwardenTransportPolicy.validateTransferUrl(env,
            "https://base.example:8443/Vault/a/../file").value)
        assertEquals("/Vault/file", normalized.url.encodedPath)
        assertFalse(normalized.useAccountTransport)
    }

    @Test fun foreignSignedHttpsSelectsPlainClientAndPreservesTheQuery() {
        val env = environment()
        val url = "https://storage.example/blob?sig=synthetic%2Fcapability&version=1"
        val target = requireNotNull(BitwardenTransportPolicy.validateTransferUrl(env, url).value)
        assertEquals(url, target.url.toString())
        assertFalse(target.useAccountTransport)
        assertFalse(target.toString().contains("synthetic"))
        val own = requireNotNull(BitwardenTransportPolicy.validateTransferUrl(env,
            "https://base.example:8443/Vault/blob?sig=synthetic").value)
        assertTrue(own.useAccountTransport)
    }

    @Test fun explicitHttpWorksOnlyForItsConfiguredAccountScope() {
        val env = environment(http = true)
        assertTrue(requireNotNull(BitwardenTransportPolicy.validateTransferUrl(env,
            "http://base.example:8443/Vault/file").value).useAccountTransport)
        val foreign = BitwardenTransportPolicy.validateTransferUrl(env, "http://storage.example/file")
        assertNull(foreign.value)
        assertEquals(BitwardenEnvironmentErrorCode.HTTPS_REQUIRED, foreign.errors.single().code)
        assertNull(BitwardenTransportPolicy.validateTransferUrl(env, "http://base.example:8443/Vault/a/../file").value)
    }

    @Test fun credentialedMalformedAndFragmentTransfersAreRejectedWithSafeCodes() {
        val env = environment()
        listOf("https://user:synthetic@storage.example/file", "https://storage.example/file#synthetic",
            "ftp://storage.example/file", "https://storage.example:0/file", "https://storage.example/a\nb",
            "https://storage.example\\other/file").forEach { raw ->
            val result = BitwardenTransportPolicy.validateTransferUrl(env, raw)
            assertNull(raw, result.value)
            assertTrue(result.errors.all { it.field == BitwardenEnvironmentField.TRANSFER_URL })
            assertFalse(result.toString().contains("synthetic"))
        }
    }

    @Test fun protocolAndRetrofitHeadersAreReservedCaseInsensitively() {
        val reserved = listOf("Authorization", "Proxy-Authorization", "Proxy-Authenticate", "Host", "Cookie", "Set-Cookie",
            "Referer", "Origin", "User-Agent", "Content-Type", "Content-Length", "Content-Encoding", "Transfer-Encoding",
            "Connection", "Keep-Alive", "TE", "Trailer", "Upgrade", "Accept", "Accept-Encoding", "Accept-Language",
            "Cache-Control", "Auth-Email", "Device-Type", "Keyguard-Client", "Bitwarden-Client-Name", "Bitwarden-Client-Version",
            "Sec-WebSocket-Key", "sEc-Custom")
        reserved.forEach { name ->
            assertTrue(name, BitwardenTransportPolicy.isReservedHeader(name))
            assertTrue(name, BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft(name, "synthetic")))
                .any { it.code == BitwardenEnvironmentErrorCode.RESERVED_HEADER })
        }
        assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("CF-Access-Client-Id", "id"),
            BitwardenHeaderDraft("CF-Access-Client-Secret", "secret"), BitwardenHeaderDraft("X-Custom", "value"))).isEmpty())
    }

    @Test fun duplicateErrorsUseOriginalRowAndSpacesAreNotTrimmedFromValues() {
        val errors = BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("", ""),
            BitwardenHeaderDraft("X-Custom", "  preserve me  "), BitwardenHeaderDraft("x-custom", "second")))
        assertEquals(listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.HEADER_NAME,
            BitwardenEnvironmentErrorCode.DUPLICATE_HEADER, 2)), errors)
        assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("X-Blank", ""))).isEmpty())
        assertEquals(BitwardenEnvironmentErrorCode.INVALID_HEADER_NAME,
            BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft(" X-Invalid", "value"))).first().code)
    }

    @Test fun headerLimitsAndInjectionChecksPreventInvalidPublication() {
        assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("X".repeat(128), "v".repeat(8192)))).isEmpty())
        assertTrue(BitwardenTransportPolicy.validateHeaders(List(17) { BitwardenHeaderDraft("", "") })
            .any { it.code == BitwardenEnvironmentErrorCode.TOO_MANY_HEADERS })
        assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("X".repeat(129), "v")))
            .any { it.code == BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE })
        assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("X", "v".repeat(8193))))
            .any { it.code == BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE })
        assertTrue(BitwardenTransportPolicy.validateHeaders(List(4) { BitwardenHeaderDraft("X-$it", "v".repeat(8192)) })
            .any { it.code == BitwardenEnvironmentErrorCode.TOTAL_HEADERS_TOO_LARGE })
        listOf("a\r\nb", "a\tb", "\u007f", "秘密").forEach { value ->
            assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft("X-Custom", value)))
                .any { it.code == BitwardenEnvironmentErrorCode.INVALID_HEADER_VALUE })
        }
        listOf("", "X:Bad", "X Bad", "X-秘密").forEach { name ->
            assertTrue(BitwardenTransportPolicy.validateHeaders(listOf(BitwardenHeaderDraft(name, "value")))
                .any { it.code == BitwardenEnvironmentErrorCode.INVALID_HEADER_NAME })
        }
    }

    @Test fun notificationsFieldIsALiteralServiceBaseAndWebDoesNotChangeReferer() {
        val env = requireNotNull(BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            "https://base.example/Proxy", webUrl = "https://web.example/Ui", notificationsUrl = "https://notify.example/hub/"),
            "12345678-1234-1234-1234-123456789abc").value)
        assertEquals("https://notify.example/hub/hub", BitwardenTransportPolicy.notificationHubUrl(env).toString())
        assertEquals("https://base.example/Proxy/", BitwardenTransportPolicy.referer(env))
        assertEquals("https://web.example/Ui/#/send/", BitwardenTransportPolicy.sendUrlPrefix(env))
    }
}
