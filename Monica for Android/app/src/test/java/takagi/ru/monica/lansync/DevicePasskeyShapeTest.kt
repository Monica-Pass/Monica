package takagi.ru.monica.lansync

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.credentialexchange.CxfPasskeyMaterial
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
class DevicePasskeyShapeTest {
    @Test fun spki_and_cose_encodings_of_one_key_both_validate() {
        for (algorithm in listOf(-7, -257, -37, -8)) {
            val pair = keyPair(algorithm)
            val material = requireNotNull(CxfPasskeyMaterial.decode(pair.private.encoded, algorithm))
            // Control: the same private key already passes with its COSE public key.
            DevicePasskeyShape.verify(data(pair, algorithm, material.cosePublicKey))
            DevicePasskeyShape.verify(data(pair, algorithm, base64(pair.public.encoded)))
        }
    }

    @Test fun mismatched_public_keys_still_fail_for_spki_and_cose() {
        for (algorithm in listOf(-7, -257, -37, -8)) {
            val pair = keyPair(algorithm)
            val other = keyPair(algorithm)
            val publicKeys = listOf(base64(other.public.encoded),
                requireNotNull(CxfPasskeyMaterial.decode(other.private.encoded, algorithm)).cosePublicKey)
            for (publicKey in publicKeys) {
                assertInvalidMaterial(data(pair, algorithm, publicKey))
            }
        }
    }

    @Test fun algorithm_mismatch_and_trailing_spki_bytes_still_fail() {
        val pair = keyPair(-7)
        assertInvalidMaterial(data(pair, -8, base64(pair.public.encoded)))
        assertInvalidMaterial(data(pair, -7, base64(pair.public.encoded + byteArrayOf(0))))
    }

    @Test fun missing_rsa_signature_algorithm_is_not_guessed_from_spki() {
        val pair = keyPair(-37)
        val payload = data(pair, -37, base64(pair.public.encoded)).getJSONObject("payload")
        payload.remove("public_key_algorithm")
        val error = assertThrows(LanSyncException::class.java) {
            DevicePasskeyShape.preparePayload(org.robolectric.RuntimeEnvironment.getApplication(), payload)
        }
        assertEquals("invalid_passkey_material", error.code)
        assertEquals("passkey_algorithm_missing", error.operation)
    }

    @Test fun system_reference_without_public_cache_does_not_adopt_an_unproven_public_key() {
        val provider = object : java.security.Provider("AndroidKeyStore", 1.0, "Synthetic reference store") {
            init { put("KeyStore.AndroidKeyStore", ReferenceKeyStore::class.java.name) }
        }
        assertNull(java.security.Security.getProvider(provider.name))
        java.security.Security.addProvider(provider)
        try {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val pair = keyPair(-7)
            val previous = data(pair, -7, base64(pair.public.encoded)).getJSONObject("payload")
                .put("private_key_alias", "synthetic-system-alias")
            assertTrue(takagi.ru.monica.passkey.PasskeyPrivateKeySupport.hasUsablePrivateKey("synthetic-system-alias"))
            val incoming = JSONObject(previous.toString()).put("private_key_alias", "").put("key_status", "reference")
            // A matching cached public identity permits retaining the original system alias.
            DevicePasskeyShape.retainNativeKey(context, incoming, previous)
            assertEquals("synthetic-system-alias", incoming.getString("private_key_alias"))
            incoming.put("private_key_alias", "")
            previous.put("public_key", "")
            val error = assertThrows(LanSyncException::class.java) {
                DevicePasskeyShape.retainNativeKey(context, incoming, previous)
            }
            assertEquals("passkey_conflict", error.code)
            assertEquals("passkey_public_key_unverifiable", error.operation)
            assertEquals("", incoming.getString("private_key_alias"))
            assertEquals("synthetic-system-alias", previous.getString("private_key_alias"))
        } finally { java.security.Security.removeProvider(provider.name) }
    }

    class ReferenceKeyStore : java.security.KeyStoreSpi() {
        override fun engineGetEntry(alias: String?, protection: java.security.KeyStore.ProtectionParameter?): java.security.KeyStore.Entry? = null
        override fun engineGetKey(alias: String?, password: CharArray?): java.security.Key? = null
        override fun engineGetCertificateChain(alias: String?): Array<java.security.cert.Certificate>? = null
        override fun engineGetCertificate(alias: String?): java.security.cert.Certificate? = null
        override fun engineGetCreationDate(alias: String?): java.util.Date? = null
        override fun engineSetKeyEntry(alias: String?, key: java.security.Key?, password: CharArray?, chain: Array<out java.security.cert.Certificate>?) = unsupported()
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out java.security.cert.Certificate>?) = unsupported()
        override fun engineSetCertificateEntry(alias: String?, certificate: java.security.cert.Certificate?) = unsupported()
        override fun engineDeleteEntry(alias: String?) = unsupported()
        override fun engineAliases(): java.util.Enumeration<String> = java.util.Collections.enumeration(listOf("synthetic-system-alias"))
        override fun engineContainsAlias(alias: String?): Boolean = alias == "synthetic-system-alias"
        override fun engineSize(): Int = 1
        override fun engineIsKeyEntry(alias: String?): Boolean = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(certificate: java.security.cert.Certificate?): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = unsupported()
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {}
        private fun unsupported(): Nothing = throw UnsupportedOperationException("Read-only fixture")
    }

    private fun assertInvalidMaterial(value: JSONObject) {
        val error = assertThrows(LanSyncException::class.java) { DevicePasskeyShape.verify(value) }
        assertTrue(error.message.orEmpty().contains("invalid_passkey_material"))
    }

    @Test fun backup_flags_keep_immutable_eligibility_and_reject_invalid_states() {
        val pair = keyPair(-7)
        val original = data(pair, -7, base64(pair.public.encoded)).getJSONObject("payload")
            .put("backup_eligible", false).put("backup_state", false)
        val reference = JSONObject(original.toString()).put("private_key_alias", "").put("key_status", "reference")
        reference.remove("backup_eligible"); reference.remove("backup_state")
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        DevicePasskeyShape.retainNativeKey(context, reference, original)
        assertEquals(false, reference.get("backup_eligible"))
        assertEquals(false, reference.get("backup_state"))
        reference.put("private_key_alias", "").put("backup_eligible", true)
        val conflict = assertThrows(LanSyncException::class.java) { DevicePasskeyShape.retainNativeKey(context, reference, original) }
        assertEquals("passkey_conflict", conflict.code)
        val invalid = data(pair, -7, base64(pair.public.encoded))
        invalid.getJSONObject("payload").put("backup_eligible", false).put("backup_state", true)
        assertInvalidMaterial(invalid)
    }

    private fun data(pair: KeyPair, algorithm: Int, publicKey: String): JSONObject =
        DeviceVaultShape.normalize(JSONObject()
            .put("credential_id", "synthetic-credential").put("rp_id", "example.test")
            .put("user_id", "Zml4dHVyZQ==").put("public_key_algorithm", algorithm)
            .put("public_key", publicKey).put("private_key_alias", base64(pair.private.encoded))
            .put("key_status", "portable"), "Synthetic passkey", "", "passkey")

    private fun keyPair(algorithm: Int): KeyPair = when (algorithm) {
        -7 -> KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        -8 -> KeyPairGenerator.getInstance("Ed25519")
        else -> KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    }.generateKeyPair()

    private fun base64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
}
