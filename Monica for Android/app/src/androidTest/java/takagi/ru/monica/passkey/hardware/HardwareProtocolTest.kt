package takagi.ru.monica.passkey.hardware

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.math.BigInteger
import javax.crypto.*
import javax.crypto.spec.*
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.HKDFParameters

/** Synthetic CTAP peer: keys and signatures are generated in the fixture, never in Monica. */
class HardwareProtocolTest {
    private fun creation(uv: String = "required", resident: String = "required", attestation: String = "direct") = JSONObject("""{
        "challenge":"AQIDBA", "rp":{"id":"example.com","name":"Example"},
        "user":{"id":"BQYH","name":"fixture","displayName":"Test account"},
        "pubKeyCredParams":[{"type":"public-key","alg":-7}],"attestation":"$attestation",
        "authenticatorSelection":{"residentKey":"$resident","userVerification":"$uv"},"extensions":{"credProps":true}
    }""")
    private fun request(json: JSONObject = creation(), create: Boolean = true, hash: ByteArray? = null) = HardwareWebAuthn(json, "example.com", "https://login.example.com", hash, create)

    @Test fun realPackedAttestationAndAssertionVerifyForPinProtocolsOneAndTwo() = runBlocking {
        for (protocol in 1..2) {
            val key = VirtualKey(protocol)
            val req = request()
            val registered = JSONObject(req.register(key, key.info, "1234"))
            val result = registered.getJSONObject("response")
            val attestation = CborMap.decode(HardwareWebAuthn.decode(result.getString("attestationObject"), 65535))!!
            val authData = attestation.bytes("authData")!!
            val signature = attestation.map("attStmt")!!.bytes("sig")!!
            val client = HardwareWebAuthn.decode(result.getString("clientDataJSON"), 2048)
            verify(key.credentials.public, authData + sha(client), signature)
            assertEquals("https://login.example.com", JSONObject(String(client)).getString("origin"))
            assertEquals("packed", attestation.string("fmt"))
            val spki = HardwareWebAuthn.decode(result.getString("publicKey"), 256)
            assertArrayEquals(key.credentials.public.encoded, spki)
            assertArrayEquals(sha(client), key.lastClientHash)
            assertEquals(1, key.pinRequests)
            assertTrue(registered.getJSONObject("clientExtensionResults").getJSONObject("credProps").getBoolean("rk"))
            val get = request(JSONObject("""{"rpId":"example.com","challenge":"CAkK","userVerification":"required","allowCredentials":[]}"""), false)
            val assertion = get.assertions(key, key.info, "1234").single()
            val response = JSONObject(get.assertionResponse(assertion)).getJSONObject("response")
            val returnedClient = HardwareWebAuthn.decode(response.getString("clientDataJSON"), 2048)
            verify(key.credentials.public, assertion.authData + sha(returnedClient), assertion.signature)
            assertEquals("webauthn.get", JSONObject(String(returnedClient)).getString("type"))
            assertEquals("BQYH", response.getString("userHandle"))
            assertEquals(2, key.pinRequests)
        }
    }

    @Test fun browserSuppliedHashIsUsedUnchangedAndNoFabricatedClientDataReturned() = runBlocking {
        val key = VirtualKey(2)
        val hash = ByteArray(32) { (it + 31).toByte() }
        val response = JSONObject(request(hash = hash).register(key, key.info, "1234")).getJSONObject("response")
        assertFalse(response.has("clientDataJSON"))
        assertArrayEquals(hash, key.lastClientHash)
    }

    @Test fun attestationNoneRemovesIdentifyingStatementAndAaguid() = runBlocking {
        val key = VirtualKey(1)
        val response = JSONObject(request(creation(attestation = "none")).register(key, key.info, "1234"))
        val att = CborMap.decode(HardwareWebAuthn.decode(response.getJSONObject("response").getString("attestationObject"), 65535))!!
        assertEquals("none", att.string("fmt"))
        assertEquals(emptyMap<Any, Any>(), att["attStmt"])
        assertArrayEquals(ByteArray(16), att.bytes("authData")!!.copyOfRange(37, 53))
    }

    @Test fun incorrectPinDoesNotRetryOrReachMakeCredential() = runBlocking {
        for (protocol in 1..2) {
            val key = VirtualKey(protocol)
            try { request().register(key, key.info, "wrong"); fail("Must fail") } catch (e: CTAP.Exception) { assertEquals(CTAP.Error.PIN_INVALID, e.error) }
            assertEquals(1, key.pinRequests)
            assertEquals(0, key.makeRequests)
        }
    }

    @Test fun setAndChangePinReachPeerOnceAndOldPinStopsWorking() = runBlocking {
        for (protocol in 1..2) {
            val key = VirtualKey(protocol).apply { currentPin = null }
            val pin = HardwarePin(key, protocol)
            pin.agreement().use { it.setOrChange("5678", null, 4) }
            assertEquals("5678", key.currentPin)
            pin.agreement().use { it.setOrChange("new secure PIN", "5678", 4) }
            assertEquals("new secure PIN", key.currentPin)
            assertEquals(2, key.pinWrites)
            try { pin.agreement().use { it.token("5678", 1, "example.com", true).close() }; fail() }
            catch (e: CTAP.Exception) { assertEquals(CTAP.Error.PIN_INVALID, e.error) }
        }
    }

    @Test fun ctap20UsesOneUnscopedPinTokenRequestWithoutFallbackRetry() = runBlocking {
        val key = VirtualKey(1)
        request().register(key, key.info.copy(options = mapOf("clientPin" to true, "rk" to true)), "1234")
        assertEquals(1, key.pinRequests)
        assertEquals(1, key.makeRequests)
    }

    @Test fun requiredVerificationAndResidentKeyAreCheckedBeforeGeneratingCredential() = runBlocking {
        for (options in listOf(mapOf("rk" to true), mapOf("clientPin" to true))) {
            val key = VirtualKey(1)
            try { request().register(key, key.info.copy(options = options), "1234"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(0, key.makeRequests)
        }
    }

    @Test fun readOnlyCredentialManagementEnumeratesRpAndAccountsWithoutResetOrDelete() = runBlocking {
        val key = VirtualKey(2)
        val snapshot = HardwareCredentials.read(key, key.info.copy(options = key.info.options + ("credMgmt" to true)), "1234")
        assertEquals(2, snapshot.existing)
        assertEquals(10, snapshot.remaining)
        assertEquals(listOf("one.example.com", "two.example.com"), snapshot.accounts.map { it.rpId })
        assertEquals(listOf(1, 2, 3, 4, 4), key.managementCommands)
        assertEquals(0, key.makeRequests)
        assertEquals(0, key.pinWrites)
    }

    @Test fun invalidPinAndUnsupportedRequestsFailBeforeDeviceMutation() = runBlocking {
        for (pin in listOf("123", "a".repeat(64), "a\u0000bc", "e\u0301abc")) {
            assertThrows(IllegalArgumentException::class.java) { HardwarePin.validatePin(pin) }
        }
        assertThrows(IllegalArgumentException::class.java) { request(creation(attestation = "enterprise")) }
        assertThrows(IllegalArgumentException::class.java) { request(creation().put("origin", "https://evil.example")) }
        assertThrows(IllegalArgumentException::class.java) { request(creation().put("extensions", JSONObject().put("prf", JSONObject()))) }
        val key = VirtualKey(2)
        val oversized = org.json.JSONArray().apply { repeat(4) { put(JSONObject().put("type", "public-key").put("id", "AQ")) } }
        try { request(creation().put("excludeCredentials", oversized)).register(key, key.info.copy(maxCredentialCountInList = 3), "1234"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, key.pinRequests)
        assertEquals(0, key.makeRequests)
    }

    @Test fun wrongRpHashAndMissingUserVerificationCannotReturnSuccess() = runBlocking {
        for (failure in listOf("rp", "uv")) {
            val key = VirtualKey(1).apply { corrupt = failure }
            try { request().register(key, key.info, "1234"); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun authenticatorCannotReturnCredentialOutsideAllowList() = runBlocking {
        val key = VirtualKey(2)
        val json = JSONObject("""{"rpId":"example.com","challenge":"AQI","allowCredentials":[{"type":"public-key","id":"_w"}]}""")
        try { request(json, false).assertions(key, key.info, "1234"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun cborRejectsDuplicateKeysTruncationOversizedAndDeeplyNestedData() {
        for (bad in listOf("a201010102", "59010001", "9f01ff", "a101", "0102", "1bffffffffffffffff", "81".repeat(18) + "00")) {
            assertThrows(IllegalArgumentException::class.java) { CborDecoder.decode(bad.hexToByteArray()) }
        }
        assertEquals(mapOf(1L to true), CborDecoder.decode("a101f5".hexToByteArray()))
    }

    @Test fun trustedWebOriginsRequireHttpsRpRelationshipAndNonPublicSuffix() {
        val rules = setOf("com", "uk", "co.uk", "*.ck", "!www.ck")
        assertEquals("https://login.example.com", HardwareRequestOrigin.requireWebOrigin("example.com", "https://login.example.com/", rules))
        for ((rp, origin) in listOf("com" to "https://evil.com", "co.uk" to "https://a.co.uk", "example.com" to "http://example.com", "example.com" to "https://evil-example.com", "example.com" to "https://example.com@evil.com", "a.ck" to "https://x.a.ck")) {
            assertThrows(IllegalArgumentException::class.java) { HardwareRequestOrigin.requireWebOrigin(rp, origin, rules) }
        }
        assertTrue(HardwareRequestOrigin.registrable("www.ck", rules))
        assertFalse(HardwareRequestOrigin.registrable("a.ck", rules))
    }

    @Test fun digitalAssetLinksRequireBothPackageAndCertificateAndRelation() {
        val statements = org.json.JSONArray("""[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"fixture.app","sha256_cert_fingerprints":["AA:BB"]}}]""")
        assertTrue(HardwareRequestOrigin.associated(statements, "fixture.app", "AA:BB"))
        assertFalse(HardwareRequestOrigin.associated(statements, "different.app", "AA:BB"))
        assertFalse(HardwareRequestOrigin.associated(statements, "fixture.app", "CC:DD"))
    }

    @Test fun hidReportUsageDoesNotSelectKeyboardOrOtpInterface() {
        assertTrue(UsbTransport.hasFidoUsage("06d0f10901a101".hexToByteArray()))
        assertFalse(UsbTransport.hasFidoUsage("05010906a101".hexToByteArray()))
        assertFalse(UsbTransport.hasFidoUsage("06d0".hexToByteArray()))
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    private fun verify(key: PublicKey, data: ByteArray, signature: ByteArray) {
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(signature) })
    }

    private class VirtualKey(val protocol: Int) : FidoTransport {
        override val transportType = TransportType.USB
        override val isConnected = true
        override fun reclaimConnection() {}
        override fun close() {}
        val credentials = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private val agreement = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val info = DeviceInfo(versions = listOf("FIDO_2_1"), aaguid = Aaguid(ByteArray(16) { 9 }),
            options = mapOf("clientPin" to true, "rk" to true, "pinUvAuthToken" to true), pinUvAuthProtocols = listOf(protocol), maxMsgSize = 4096)
        var currentPin: String? = "1234"
        var pinRequests = 0; var makeRequests = 0; var pinWrites = 0; var corrupt = ""
        var lastClientHash = ByteArray(0)
        val managementCommands = mutableListOf<Int>()
        private val token = ByteArray(32) { (it + 1).toByte() }
        private val id = byteArrayOf(21, 22, 23, 24)
        private fun key(public: PublicKey): CborRaw {
            val point = (public as ECPublicKey).w
            fun coord(n: BigInteger) = n.toByteArray().let { if (it.size >= 32) it.takeLast(32).toByteArray() else ByteArray(32 - it.size) + it }
            return CborRaw(cbor { map { 1 to 2; 3 to -7; -1 to 1; -2 to bytes(coord(point.affineX)); -3 to bytes(coord(point.affineY)) } }.toList())
        }
        override suspend fun sendCtapCommand(command: ByteArray): ByteArray {
            val map = if (command.size > 1) CborMap.decode(command.copyOfRange(1, command.size))!! else null
            return when (command[0].toInt()) {
                6 -> {
                    check(map!!.int(1) == protocol)
                    val sub = map.int(2)!!
                    if (sub == 1) return byteArrayOf(0) + cbor { map { 3 to 8 } }
                    if (sub == 2) return byteArrayOf(0) + cbor { map { 1 to CborRaw(cbor { map { val p = CborDecoder.decode(key(agreement.public).bytes.toByteArray()) as Map<*, *>; for ((k,v) in p) (k as Number).toInt() to if (k == 3L) -25 else v } }.toList()) } }
                    val platform = map.map(3)!!
                    val params = (agreement.public as ECPublicKey).params
                    val point = ECPoint(BigInteger(1, platform.bytes(-2)), BigInteger(1, platform.bytes(-3)))
                    val remote = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
                    val secret = KeyAgreement.getInstance("ECDH").run { init(agreement.private); doPhase(remote, true); generateSecret() }
                    fun derive(info: String): ByteArray = ByteArray(32).also { HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(secret, ByteArray(32), info.toByteArray())) }.generateBytes(it, 0, 32) }
                    val hkey = if (protocol == 1) MessageDigest.getInstance("SHA-256").digest(secret) else derive("CTAP2 HMAC key")
                    val akey = if (protocol == 1) hkey else derive("CTAP2 AES key")
                    fun mac(data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(hkey, "HmacSHA256")); doFinal(data).copyOf(if (protocol == 1) 16 else 32) }
                    fun decrypt(enc: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(akey, "AES"), IvParameterSpec(if (protocol == 1) ByteArray(16) else enc.copyOfRange(0, 16)))
                        doFinal(if (protocol == 1) enc else enc.copyOfRange(16, enc.size))
                    }
                    fun pinHash(pin: String) = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).copyOf(16)
                    if (sub in listOf(5, 9)) {
                        pinRequests++
                        if (currentPin == null || !decrypt(map.bytes(6)!!).contentEquals(pinHash(currentPin!!))) return byteArrayOf(0x31)
                        if (sub == 9) { check(map.int(9) in listOf(1, 2, 4)); if (map.int(9) != 4) check(map.string(10) == "example.com") }
                        val iv = ByteArray(16) { 0x55 }
                        val enc = Cipher.getInstance("AES/CBC/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(akey, "AES"), IvParameterSpec(if (protocol == 1) ByteArray(16) else iv)); doFinal(token) }
                        return byteArrayOf(0) + cbor { map { 2 to bytes(if (protocol == 1) enc else iv + enc) } }
                    }
                    val newEnc = map.bytes(5)!!; val oldEnc = map.bytes(6)
                    check(map.bytes(4)!!.contentEquals(mac(newEnc + (oldEnc ?: byteArrayOf()))))
                    if (sub == 4 && !decrypt(oldEnc!!).contentEquals(pinHash(currentPin!!))) return byteArrayOf(0x31)
                    currentPin = String(decrypt(newEnc).takeWhile { it != 0.toByte() }.toByteArray())
                    pinWrites++
                    byteArrayOf(0)
                }
                10 -> {
                    val sub = map!!.int(1)!!
                    managementCommands.add(sub)
                    if (sub != 3 && sub != 5) {
                        check(map.int(3) == protocol)
                        val data = byteArrayOf(sub.toByte()) + if (map[2] == null) byteArrayOf() else cbor { map { 1 to (map.map(2)!!.bytes(1)!!) } }
                        val expected = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(token, "HmacSHA256")); doFinal(data).copyOf(if (protocol == 1) 16 else 32) }
                        check(expected.contentEquals(map.bytes(4)))
                    }
                    when (sub) {
                        1 -> byteArrayOf(0) + cbor { map { 1 to 2; 2 to 10 } }
                        2, 3 -> {
                            val rp = if (sub == 2) "one.example.com" else "two.example.com"
                            byteArrayOf(0) + cbor { map { 3 to map { "id" to rp }; 4 to bytes(MessageDigest.getInstance("SHA-256").digest(rp.toByteArray())); if (sub == 2) 5 to 2 } }
                        }
                        4 -> byteArrayOf(0) + cbor { map { 6 to map { "id" to bytes(byteArrayOf(5)); "name" to "fixture" }; 9 to 1 } }
                        else -> error("Unexpected management mutation")
                    }
                }
                1, 2 -> {
                    val create = command[0] == 1.toByte()
                    if (create) makeRequests++
                    val hash = map!!.bytes(if (create) 1 else 2)!!
                    lastClientHash = hash
                    val param = map.bytes(if (create) 8 else 6)!!
                    val expected = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(token, "HmacSHA256")); doFinal(hash).copyOf(if (protocol == 1) 16 else 32) }
                    check(expected.contentEquals(param))
                    val rpHash = MessageDigest.getInstance("SHA-256").digest(if (corrupt == "rp") "evil.com".toByteArray() else "example.com".toByteArray())
                    val flags = (if (corrupt == "uv") 1 else 5) or if (create) 0x40 else 0
                    val auth = rpHash + byteArrayOf(flags.toByte(), 0, 0, 0, 1) + if (create) ByteArray(16) { 9 } + byteArrayOf(0, id.size.toByte()) + id + key(credentials.public).bytes.toByteArray() else byteArrayOf()
                    val sig = Signature.getInstance("SHA256withECDSA").run { initSign(credentials.private); update(auth + hash); sign() }
                    byteArrayOf(0) + if (create) cbor { map { 1 to "packed"; 2 to bytes(auth); 3 to map { "alg" to -7; "sig" to bytes(sig) } } }
                    else cbor { map { 1 to map { "type" to "public-key"; "id" to bytes(id) }; 2 to bytes(auth); 3 to bytes(sig); 4 to map { "id" to bytes(byteArrayOf(5, 6, 7)); "name" to "fixture" } } }
                }
                else -> error("Unexpected CTAP command")
            }
        }
    }
}
