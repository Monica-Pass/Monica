package takagi.ru.monica.bitwarden.api

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.Security
import java.security.Provider
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/** Real synthetic PKCS12/X.509 bytes; no network, Android store or extra certificate dependency. */
class BitwardenEnvironmentTlsTest {
    private val revision = "12345678-1234-1234-1234-123456789abc"

    private fun resolve(tls: BitwardenTlsConfig, base: String = "https://vault.example/", web: String? = null, api: String? = null) =
        BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM,
            baseUrl = base, webUrl = web, apiUrl = api, tls = tls), revision)

    @Test fun validCaAndPrivateKeyPkcs12AreValidatedWithoutChangingEmptyPassword() {
        val tls = BitwardenTlsConfig(caCertificatePem = fixture.pem, mtlsEnabled = true,
            clientCertPkcs12Base64 = pkcs12(""), clientCertPassword = "")
        val result = resolve(tls)
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals("", result.value!!.tls.clientCertPassword)
        assertEquals(fixture.pem, result.value!!.tls.caCertificatePem)
        assertTrue(BitwardenTransportPolicy.canUseAccountTransport(result.value!!, "https://vault.example/api/ciphers"))
    }

    @Test fun pkcs12SpaceOnlyPasswordIsPreservedAndWrongPasswordReturnsOnlyCodes() {
        val encoded = pkcs12("   ")
        val correct = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = encoded, clientCertPassword = "   "))
        assertTrue(correct.errors.toString(), correct.isValid)
        assertEquals("   ", correct.value!!.tls.clientCertPassword)
        val wrong = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = encoded, clientCertPassword = "synthetic-wrong-password"))
        assertNull(wrong.value)
        assertTrue(wrong.errors.any { it.code == BitwardenEnvironmentErrorCode.INVALID_CLIENT_CERTIFICATE })
        assertFalse(wrong.toString().contains("synthetic-wrong-password"))
        assertFalse(wrong.toString().contains(encoded))
    }

    @Test fun enabledMtlsCannotSilentlyUseNoCertificateOrCertificateOnlyContainer() {
        val missing = resolve(BitwardenTlsConfig(mtlsEnabled = true))
        assertEquals(BitwardenEnvironmentErrorCode.CLIENT_CERTIFICATE_REQUIRED, missing.errors.single().code)
        val certOnly = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = pkcs12("test", privateKey = false), clientCertPassword = "test"))
        assertNull(certOnly.value)
        assertEquals(BitwardenEnvironmentErrorCode.CLIENT_PRIVATE_KEY_REQUIRED, certOnly.errors.single().code)
        val empty = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = pkcs12("test", privateKey = false, includeCertificate = false), clientCertPassword = "test"))
        assertEquals(BitwardenEnvironmentErrorCode.CLIENT_PRIVATE_KEY_REQUIRED, empty.errors.single().code)
    }

    @Test fun badPemAndInvalidPkcs12NeverFallBackToSystemDefaults() {
        listOf("", "   ", "not-a-certificate", fixture.pem + "trailing junk").forEach { pem ->
            val result = resolve(BitwardenTlsConfig(caCertificatePem = pem))
            assertNull(result.value)
            assertTrue(result.errors.any { it.field == BitwardenEnvironmentField.TLS_CA && it.code == BitwardenEnvironmentErrorCode.INVALID_CA_CERTIFICATE })
        }
        val p12 = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = "%%%not-base64%%%"))
        assertEquals(BitwardenEnvironmentErrorCode.INVALID_CLIENT_CERTIFICATE, p12.errors.single().code)
    }

    @Test fun mtlsRequiresEveryNativeEndpointHttpsButWebOnlyIsNotACertificateScope() {
        val tls = BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = pkcs12("test"), clientCertPassword = "test")
        val httpBase = resolve(tls, base = "http://vault.example/Proxy/")
        assertNull(httpBase.value)
        assertEquals(setOf(BitwardenEnvironmentField.BASE, BitwardenEnvironmentField.API,
            BitwardenEnvironmentField.IDENTITY, BitwardenEnvironmentField.NOTIFICATIONS),
            httpBase.errors.filter { it.code == BitwardenEnvironmentErrorCode.HTTPS_REQUIRED }.map { it.field }.toSet())
        val httpApi = resolve(tls, api = "http://api.example/")
        assertEquals(listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.API, BitwardenEnvironmentErrorCode.HTTPS_REQUIRED)), httpApi.errors)
        val httpWeb = resolve(tls, web = "http://web.example/Ui/")
        assertTrue(httpWeb.errors.toString(), httpWeb.isValid)
        assertFalse(BitwardenTransportPolicy.canUseAccountTransport(httpWeb.value!!, "http://web.example/Ui/"))
    }

    @Test fun certificateInputBudgetsApplyBeforeParsingOversizedData() {
        val ca = resolve(BitwardenTlsConfig(caCertificatePem = "x".repeat(BitwardenTransportPolicy.MAX_CA_CERTIFICATE_BYTES + 1)))
        assertEquals(BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE, ca.errors.single().code)
        val raw = resolve(BitwardenTlsConfig(mtlsEnabled = true,
            clientCertPkcs12Base64 = "A".repeat(BitwardenTransportPolicy.MAX_CLIENT_CERTIFICATE_BYTES * 2 + 1)))
        assertEquals(BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE, raw.errors.single().code)
        val decoded = Base64.getEncoder().encodeToString(ByteArray(BitwardenTransportPolicy.MAX_CLIENT_CERTIFICATE_BYTES + 1))
        val parsed = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = decoded))
        assertEquals(BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE, parsed.errors.single().code)
    }

    @Test fun lineWrappedPkcs12AndMultiplePemCertificatesAreAccepted() {
        val wrapped = pkcs12("test").chunked(64).joinToString("\r\n")
        val result = resolve(BitwardenTlsConfig(caCertificatePem = fixture.pem + "\n" + fixture.pem,
            mtlsEnabled = true, clientCertPkcs12Base64 = wrapped, clientCertPassword = "test"))
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(wrapped, result.value!!.tls.clientCertPkcs12Base64)
    }

    @Test fun everyRepeatedPemBlockIsValidatedWithExplicitProvidersWithoutChangingGlobalOrder() {
        val before = Security.getProviders().toList()
        val pem = fixture.pem + "\r\n\t" + fixture.pem
        explicitFactories().forEach { factory ->
            BitwardenEnvironmentResolver.validateCaCertificates(pem, factory)
        }
        assertEquals(before, Security.getProviders().toList())
    }

    @Test fun corruptPemBlocksAndInterBlockJunkAreRejectedByEveryExplicitProvider() {
        val before = Security.getProviders().toList()
        val broken = "-----BEGIN CERTIFICATE-----\nAQIDBA==\n-----END CERTIFICATE-----\n"
        val invalidInputs = listOf(
            broken + fixture.pem,
            fixture.pem + broken,
            fixture.pem + broken + fixture.pem,
            "prefix-junk" + fixture.pem,
            fixture.pem + "between-junk" + fixture.pem,
            fixture.pem + "trailing-junk",
            fixture.pem + fixture.pem.replace("-----END CERTIFICATE-----", "-----END PRIVATE KEY-----"),
        )
        invalidInputs.forEach { pem ->
            explicitFactories().forEach { factory ->
                assertThrows(factory.provider.name, Exception::class.java) {
                    BitwardenEnvironmentResolver.validateCaCertificates(pem, factory)
                }
            }
            val result = resolve(BitwardenTlsConfig(caCertificatePem = pem))
            assertNull(result.value)
            assertEquals(listOf(BitwardenEnvironmentError(BitwardenEnvironmentField.TLS_CA,
                BitwardenEnvironmentErrorCode.INVALID_CA_CERTIFICATE)), result.errors)
        }
        assertEquals(before, Security.getProviders().toList())
    }

    private fun explicitFactories(): List<CertificateFactory> = listOf(
        requireNotNull(Security.getProvider("SUN")), BouncyCastleProvider(),
        // Robolectric supplies Conscrypt at runtime; do not add a production/compile dependency.
        Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null) as Provider,
    ).map { CertificateFactory.getInstance("X.509", it) }

    @Test fun togglingMtlsChangesFingerprintWithoutDiscardingRetainedCertificate() {
        val encoded = pkcs12("test")
        val enabled = resolve(BitwardenTlsConfig(mtlsEnabled = true, clientCertPkcs12Base64 = encoded, clientCertPassword = "test"))
        val disabled = resolve(BitwardenTlsConfig(mtlsEnabled = false, clientCertPkcs12Base64 = encoded, clientCertPassword = "test"))
        assertTrue(enabled.isValid && disabled.isValid)
        assertNotEquals(enabled.value!!.fingerprint, disabled.value!!.fingerprint)
        assertEquals(encoded, disabled.value!!.tls.clientCertPkcs12Base64)
        assertFalse(disabled.value!!.tls.mtlsEnabled)
    }

    private fun pkcs12(password: String, privateKey: Boolean = true, includeCertificate: Boolean = true): String {
        val chars = password.toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, chars)
            if (privateKey) store.setKeyEntry("client", fixture.keyPair.private, chars, arrayOf<Certificate>(fixture.certificate))
            else if (includeCertificate) store.setCertificateEntry("certificate-only", fixture.certificate)
            val bytes = ByteArrayOutputStream().use { output -> store.store(output, chars); output.toByteArray() }
            return try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        } finally { chars.fill('\u0000') }
    }

    private class Fixture(val keyPair: KeyPair, val certificate: X509Certificate, val pem: String)

    companion object {
        private fun sequence(vararg values: ASN1Encodable) = DERSequence(values)

        private val fixture: Fixture by lazy {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val subject = X500Name("CN=Monica Synthetic Environment Fixture")
            val tbs = sequence(DERTaggedObject(true, 0, ASN1Integer(2)), ASN1Integer(1), algorithm, subject,
                sequence(Time(Date(1_577_836_800_000L)), Time(Date(4_102_444_800_000L))), subject,
                SubjectPublicKeyInfo.getInstance(keyPair.public.encoded))
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keyPair.private); update(tbs.encoded) }.sign()
            val encoded = sequence(tbs, algorithm, DERBitString(signature)).encoded
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            certificate.verify(keyPair.public)
            val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(encoded).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
            Fixture(keyPair, certificate, pem)
        }
    }
}
