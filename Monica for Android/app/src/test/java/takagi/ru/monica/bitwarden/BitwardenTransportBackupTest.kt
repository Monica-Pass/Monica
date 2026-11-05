package takagi.ru.monica.bitwarden

import android.app.Application
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.StringResolver

/** Real backup AES-GCM and resolver validation; fake map below models only final protected rebinding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BitwardenTransportBackupTest {
    private val strings = StringResolver { id, _ -> "resource:$id" }
    private val vault = BitwardenVault(id = 8123, email = EMAIL, canonicalEmail = EMAIL, userId = USER,
        serverUrl = BASE, apiUrl = API, identityUrl = IDENTITY,
        transportRevision = UUID.randomUUID().toString(), transportSecretRef = "synthetic-device-reference")

    private fun environment(tls: BitwardenTlsConfig = BitwardenTlsConfig(), empty: Boolean = false): BitwardenEnvironment =
        requireNotNull(BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(
            BitwardenEnvironmentPreset.CUSTOM, BASE, WEB, API, IDENTITY, NOTIFICATIONS,
            if (empty) emptyList() else listOf(BitwardenHeaderDraft("X-Proxy", SECRET), BitwardenHeaderDraft("X-Empty", "")), tls,
        ), UUID.randomUUID().toString()).value)

    private fun seal(environment: BitwardenEnvironment = environment(), password: String? = PASSWORD) =
        BitwardenTransportBackupCodec.seal(vault, environment, password, strings)

    private fun open(backup: BitwardenTransportBackup, server: String = BASE, api: String = API,
        identity: String = IDENTITY, email: String = EMAIL, user: String? = USER, password: String? = PASSWORD) =
        BitwardenTransportBackupCodec.open(backup, server, api, identity, email, user, password, strings)

    private fun changed(backup: BitwardenTransportBackup, version: Int = backup.version, preset: String = backup.preset,
        web: String? = backup.webUrl, notifications: String = backup.notificationsUrl, encrypted: String = backup.encryptedPayload) =
        BitwardenTransportBackup(version, preset, web, notifications, encrypted)

    private fun changedPayload(backup: BitwardenTransportBackup, change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): BitwardenTransportBackup {
        val values = Json.parseToJsonElement(EncryptionHelper.decryptString(backup.encryptedPayload, PASSWORD, strings))
            .jsonObject.toMutableMap()
        change(values)
        return changed(backup, encrypted = EncryptionHelper.encryptString(JsonObject(values).toString(), PASSWORD, strings))
    }

    private fun rejected(reason: BitwardenTransportBackupFailure, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected redacted transport backup rejection", error is BitwardenTransportBackupException)
        assertEquals(reason, (error as BitwardenTransportBackupException).reason)
        assertNull(error.cause)
        assertFalse(error.toString().contains(SECRET))
    }

    @Test fun encryptedRoundTripPreservesActualImportedTlsAndAllEndpointsWithoutLocalBindings() {
        val tls = tlsFixture()
        val original = environment(tls)
        val backup = seal(original)
        val serialized = Json.encodeToString(backup)
        listOf(SECRET, "BEGIN CERTIFICATE", requireNotNull(tls.clientCertPkcs12Base64), TLS_PASSWORD,
            vault.transportRevision, vault.transportSecretRef!!, "vaultId", "reference", "revision").forEach {
            assertFalse("Serialized transport must contain only encrypted private material", serialized.contains(it))
        }
        val restored = open(Json.decodeFromString(serialized))
        assertEquals(original.fingerprint, restored.fingerprint)
        assertEquals(original.base, restored.base)
        assertEquals(original.api, restored.api)
        assertEquals(original.identity, restored.identity)
        assertEquals(original.web, restored.web)
        assertEquals(original.notifications, restored.notifications)
        assertTrue(restored.webExplicit)
        assertEquals(listOf(SECRET, ""), restored.headers.map { it.value })
        assertEquals(tls, restored.tls)
        assertNotEquals(original.revision, restored.revision)
        assertNotEquals(restored.revision, open(backup).revision)
        assertEquals("BitwardenTransportBackup(redacted)", backup.toString())
    }

    @Test fun restoredPayloadGetsANewDeviceBindingAndAnEmptyPayloadStaysAuthoritative() {
        val original = environment(empty = true)
        val restored = open(seal(original))
        val entries = linkedMapOf<String, String>()
        val store = BitwardenTransportSecretStore(entries::get, { name, value -> entries[name] = value }, { entries.remove(it); Unit })
        val binding = store.stage(55, restored)
        assertEquals(55L, binding.vaultId)
        assertNotEquals(vault.transportRevision, binding.revision)
        assertNotEquals(vault.transportSecretRef, binding.reference)
        val loaded = store.load(binding)
        assertTrue(loaded.headers.isEmpty())
        assertEquals(BitwardenTlsConfig(), loaded.tls)
        assertEquals(restored.fingerprint, loaded.configurationFingerprint)
        val anotherDevice = store.stage(56, open(seal(original)))
        assertNotEquals(binding.reference, anotherDevice.reference)
        assertNotEquals(binding.revision, anotherDevice.revision)
    }

    @Test fun everyEditedDestinationOrPresetIsRejectedAgainstTheEncryptedFingerprint() {
        val backup = seal()
        listOf<() -> Unit>(
            { open(backup, server = "https://other.example.invalid/") },
            { open(backup, api = "https://api.example.invalid/Other/") },
            { open(backup, identity = "https://identity.example.invalid/Other/") },
            { open(changed(backup, preset = "US")) },
            { open(changed(backup, web = "https://other.example.invalid/Web/")) },
            { open(changed(backup, web = null)) },
            { open(changed(backup, notifications = "https://notify.example.invalid/Other/")) },
        ).forEach { rejected(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH, it) }
    }

    @Test fun portableSecretCannotBeMovedToAnotherAccountOrUserIdentity() {
        val backup = seal()
        rejected(BitwardenTransportBackupFailure.ACCOUNT_MISMATCH) { open(backup, email = "other@example.invalid") }
        rejected(BitwardenTransportBackupFailure.ACCOUNT_MISMATCH) { open(backup, user = "other-user") }
        rejected(BitwardenTransportBackupFailure.ACCOUNT_MISMATCH) { open(backup, user = null) }
        assertEquals(environment().fingerprint, open(backup, email = "  SYNTHETIC@example.invalid  ").fingerprint)
    }

    @Test fun noPasswordWrongPasswordAndDamagedCiphertextNeverUseTheLegacyFallback() {
        val backup = seal()
        rejected(BitwardenTransportBackupFailure.ENCRYPTION_REQUIRED) { open(backup, password = null) }
        rejected(BitwardenTransportBackupFailure.ENCRYPTION_REQUIRED) { open(backup, password = "") }
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) { open(backup, password = "incorrect") }
        val parts = backup.encryptedPayload.split(':').toMutableList()
        val bytes = Base64.getDecoder().decode(parts[2]).also { it[0] = (it[0].toInt() xor 1).toByte() }
        parts[2] = Base64.getEncoder().encodeToString(bytes)
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) { open(changed(backup, encrypted = parts.joinToString(":"))) }
    }

    @Test fun encryptionRequiresANonemptyPasswordButPreservesWhitespacePasswordsExactly() {
        rejected(BitwardenTransportBackupFailure.ENCRYPTION_REQUIRED) { seal(password = null) }
        rejected(BitwardenTransportBackupFailure.ENCRYPTION_REQUIRED) { seal(password = "") }
        val expected = environment(BitwardenTlsConfig(certificateAlias = "metadata-only", clientCertPassword = "  "))
        val backup = seal(expected, password = "   ")
        assertEquals("  ", open(backup, password = "   ").tls.clientCertPassword)
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) { open(backup, password = " ") }
    }

    @Test fun unsupportedVersionsAndMalformedSealedSchemasAreRejected() {
        val backup = seal()
        rejected(BitwardenTransportBackupFailure.UNSUPPORTED_VERSION) { open(changed(backup, version = 2)) }
        rejected(BitwardenTransportBackupFailure.UNSUPPORTED_VERSION) {
            open(changedPayload(backup) { it["version"] = JsonPrimitive(2) })
        }
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) {
            open(changedPayload(backup) { it["deviceLocalReference"] = JsonPrimitive("untrusted") })
        }
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) {
            open(changedPayload(backup) { it.remove("configurationFingerprint") })
        }
    }

    @Test fun decryptedTlsAndFingerprintAreRevalidatedBeforeAnyRepositoryPublication() {
        val backup = seal()
        rejected(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH) {
            open(changedPayload(backup) { it["configurationFingerprint"] = JsonPrimitive("0".repeat(64)) })
        }
        rejected(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH) {
            open(changedPayload(backup) {
                it["tls"] = JsonObject(it.getValue("tls").jsonObject.toMutableMap().apply {
                    put("mtlsEnabled", JsonPrimitive(true))
                })
            })
        }
    }

    @Test fun legacyCustomUrlsArePreservedOrDerivedFromTheirOwnBaseWithoutUsFallback() {
        val explicit = BitwardenTransportBackupCodec.legacyEnvironment(BASE, API, IDENTITY)
        assertEquals(API, explicit.api.toString())
        assertEquals(IDENTITY, explicit.identity.toString())
        val inherited = BitwardenTransportBackupCodec.legacyEnvironment(BASE, "", " ")
        assertEquals(BASE + "api/", inherited.api.toString())
        assertEquals(BASE + "identity/", inherited.identity.toString())
        rejected(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH) {
            BitwardenTransportBackupCodec.legacyEnvironment("", "", "")
        }
        rejected(BitwardenTransportBackupFailure.CONFIGURATION_MISMATCH) {
            BitwardenTransportBackupCodec.legacyEnvironment(BASE, "not-a-url", IDENTITY)
        }
    }

    @Test fun oversizedOrEmptyEncryptedMaterialIsRejectedBeforeDecryption() {
        val backup = seal()
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) { open(changed(backup, encrypted = "")) }
        rejected(BitwardenTransportBackupFailure.INVALID_PAYLOAD) {
            open(changed(backup, encrypted = "a".repeat(6 * 1024 * 1024 + 1)))
        }
    }

    /** Actual X.509/private-key PKCS#12 for the resolver, without network or global trust changes. */
    private fun tlsFixture(): BitwardenTlsConfig {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subject = X500Name("CN=Monica Synthetic Backup Certificate")
        fun sequence(vararg values: ASN1Encodable) = DERSequence(values)
        val extensions = Extensions(arrayOf(Extension(Extension.basicConstraints, true, DEROctetString(BasicConstraints(true).encoded))))
        val tbs = sequence(DERTaggedObject(true, 0, ASN1Integer(2)), ASN1Integer(1L), algorithm, subject,
            sequence(Time(Date(1_577_836_800_000L)), Time(Date(4_102_444_800_000L))), subject,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded), DERTaggedObject(true, 3, extensions))
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs.encoded) }.sign()
        val encoded = sequence(tbs, algorithm, DERBitString(signature)).encoded
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded))
        val password = TLS_PASSWORD.toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password); setKeyEntry("synthetic-client", pair.private, password, arrayOf(certificate))
        }
        val p12 = ByteArrayOutputStream().use { output -> store.store(output, password); output.toByteArray() }
        password.fill('\u0000')
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(encoded).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
        return BitwardenTlsConfig("display-only-alias", pem, true, Base64.getEncoder().encodeToString(p12), TLS_PASSWORD)
    }

    private companion object {
        const val BASE = "https://vault.example.invalid/Case/"
        const val API = "https://api.example.invalid/Service/"
        const val IDENTITY = "https://identity.example.invalid/Auth/"
        const val WEB = "https://web.example.invalid/Vault/"
        const val NOTIFICATIONS = "https://notify.example.invalid/Events/"
        const val EMAIL = "synthetic@example.invalid"
        const val USER = "synthetic-server-user"
        const val SECRET = "  synthetic-proxy-secret  "
        const val PASSWORD = "Synthetic encrypted configuration 42!"
        const val TLS_PASSWORD = "  synthetic-client-password  "
    }
}
