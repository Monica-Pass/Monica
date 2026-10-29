package takagi.ru.monica.passkey.hardware

import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** CTAP PIN/UV protocols 1 and 2. One submitted PIN makes exactly one token request. */
class HardwarePin(private val transport: FidoTransport, val protocol: Int) {
    init { require(protocol in 1..2) }
    suspend fun retries(): Int = response(1).int(3) ?: error("Missing PIN retries")

    private suspend fun response(sub: Int, fields: CborMapEncoder.() -> Unit = {}): CborMap {
        val command = byteArrayOf(6) + cbor { map { 1 to protocol; 2 to sub; fields() } }
        val bytes = transport.sendCtapCommand(command)
        CTAP.getResponseError(bytes)?.let { throw CTAP.Exception(it) }
        return if (bytes.size == 1) CborMap.decode(byteArrayOf(0xa0.toByte()))!!
            else CborMap.decode(bytes.copyOfRange(1, bytes.size)) ?: error("Invalid PIN response")
    }

    suspend fun agreement(): Agreement {
        val remote = response(2).map(1) ?: error("Missing key agreement")
        require(remote.int(1) == 2 && remote.int(3) == -25 && remote.int(-1) == 1)
        val x = remote.bytes(-2) ?: error("Missing X")
        val y = remote.bytes(-3) ?: error("Missing Y")
        require(x.size == 32 && y.size == 32)
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val local = gen.generateKeyPair()
        val params = (local.public as ECPublicKey).params
        val xx = BigInteger(1, x); val yy = BigInteger(1, y)
        val p = (params.curve.field as ECFieldFp).p
        require(xx < p && yy < p && yy.pow(2).mod(p) == xx.pow(3).add(params.curve.a.multiply(xx)).add(params.curve.b).mod(p))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(xx, yy), params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(local.private); doPhase(publicKey, true); generateSecret() }
        try {
            val hmacKey = if (protocol == 1) sha(shared) else hkdf(shared, "CTAP2 HMAC key")
            val aesKey = if (protocol == 1) hmacKey.copyOf() else hkdf(shared, "CTAP2 AES key")
            val point = (local.public as ECPublicKey).w
            val cose = cbor { map { 1 to 2; 3 to -25; -1 to 1; -2 to bytes(coordinate(point.affineX)); -3 to bytes(coordinate(point.affineY)) } }
            return Agreement(hmacKey, aesKey, CborRaw(cose.toList()))
        } finally { shared.fill(0) }
    }

    inner class Agreement(private val hmacKey: ByteArray, private val aesKey: ByteArray, private val cose: CborRaw) : AutoCloseable {
        private fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(16).also { if (protocol == 2) SecureRandom().nextBytes(it) }
            val encrypted = crypt(Cipher.ENCRYPT_MODE, aesKey, iv, plain)
            return if (protocol == 2) iv + encrypted else encrypted
        }
        private fun decrypt(enc: ByteArray): ByteArray {
            require(enc.isNotEmpty() && enc.size % 16 == 0 && (protocol != 2 || enc.size >= 32))
            return crypt(Cipher.DECRYPT_MODE, aesKey, if (protocol == 1) ByteArray(16) else enc.copyOfRange(0, 16),
                if (protocol == 1) enc else enc.copyOfRange(16, enc.size))
        }
        private fun pinHash(pin: String): ByteArray {
            val raw = pin.toByteArray(Charsets.UTF_8)
            return try { sha(raw).copyOf(16) } finally { raw.fill(0) }
        }
        suspend fun token(pin: String?, permission: Int, rpId: String?, scoped: Boolean): Token {
            val hash = pin?.let(::pinHash)
            try {
                val sub = if (pin == null) 6 else if (scoped) 9 else 5
                val response = response(sub) {
                    3 to cose
                    hash?.let { 6 to bytes(encrypt(it)) }
                    if (sub != 5) { 9 to permission; rpId?.let { 10 to it } }
                }
                val token = decrypt(response.bytes(2) ?: error("Missing PIN token"))
                require(token.size in listOf(16, 32)) { "Invalid PIN token" }
                return Token(token, protocol)
            } finally { hash?.fill(0) }
        }
        suspend fun setOrChange(newPin: String, currentPin: String?, minLength: Int) {
            validatePin(newPin, minLength)
            val bytes = newPin.toByteArray(Charsets.UTF_8)
            val padded = ByteArray(64)
            val oldHash = currentPin?.let(::pinHash)
            try {
                bytes.copyInto(padded)
                val newEnc = encrypt(padded)
                val oldEnc = oldHash?.let(::encrypt)
                response(if (currentPin == null) 3 else 4) {
                    3 to cose; 4 to bytes(auth(hmacKey, newEnc + (oldEnc ?: byteArrayOf()), protocol)); 5 to bytes(newEnc)
                    oldEnc?.let { 6 to bytes(it) }
                }
            } finally { bytes.fill(0); padded.fill(0); oldHash?.fill(0) }
        }
        override fun close() { hmacKey.fill(0); aesKey.fill(0) }
    }

    class Token(private val value: ByteArray, val protocol: Int) : AutoCloseable {
        fun authenticate(message: ByteArray) = auth(value, message, protocol)
        override fun close() { value.fill(0) }
    }

    companion object {
        fun select(info: DeviceInfo): Int = when { 2 in info.pinUvAuthProtocols -> 2; 1 in info.pinUvAuthProtocols -> 1; else -> error("PIN protocol unsupported") }
        fun validatePin(pin: String, minLength: Int = 4) {
            Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(pin))
            require(pin == java.text.Normalizer.normalize(pin, java.text.Normalizer.Form.NFC) && !pin.contains('\u0000') &&
                pin.codePointCount(0, pin.length) >= maxOf(4, minLength) && pin.toByteArray(Charsets.UTF_8).size <= 63) { "Invalid PIN length or encoding" }
        }
        fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(input) }
        fun auth(key: ByteArray, input: ByteArray, protocol: Int) = hmac(key, input).copyOf(if (protocol == 1) 16 else 32)
        private fun hkdf(secret: ByteArray, info: String): ByteArray {
            val prk = hmac(ByteArray(32), secret)
            return try { hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1)) } finally { prk.fill(0) }
        }
        private fun crypt(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray) = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(input)
        }
        private fun coordinate(n: BigInteger): ByteArray = n.toByteArray().let { b -> if (b.size >= 32) b.takeLast(32).toByteArray() else ByteArray(32 - b.size) + b }
    }
}
