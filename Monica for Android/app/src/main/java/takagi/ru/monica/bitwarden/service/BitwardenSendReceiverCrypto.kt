package takagi.ru.monica.bitwarden.service

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated Send v1 only. Deliberately independent of permissive legacy vault decoding. */
internal class BitwardenSendReceiverCrypto(material: ByteArray) : AutoCloseable {
    private var raw: ByteArray? = material
    private var enc: ByteArray?
    private var auth: ByteArray?

    init {
        require(material.size == 16)
        val extracted = hmac("bitwarden-send".toByteArray(StandardCharsets.UTF_8), material)
        val info = "send".toByteArray(StandardCharsets.UTF_8)
        val first = hmac(extracted, info + byteArrayOf(1))
        val second = hmac(extracted, first + info + byteArrayOf(2))
        enc = first; auth = second
        extracted.fill(0); info.fill(0)
    }

    @Synchronized private fun keys(): Pair<ByteArray, ByteArray> {
        val aes = enc ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.CLOSED)
        val mac = auth ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.CLOSED)
        return aes.copyOf() to mac.copyOf()
    }

    fun passwordHash(password: CharArray): String {
        val salt = synchronized(this) {
            raw?.copyOf() ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.CLOSED)
        }
        var passwordBytes: ByteArray? = null
        var hash: ByteArray? = null
        try {
            receiverRequire(password.size in 1..4096, BitwardenSendReceiverFailure.INVALID_INPUT)
            val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
            passwordBytes = ByteArray(encoded.remaining()).also(encoded::get)
            if (encoded.hasArray()) encoded.array().fill(0)
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(passwordBytes, salt, 100_000)
            hash = (generator.generateDerivedParameters(256) as KeyParameter).key
            return Base64.getEncoder().encodeToString(hash)
        } finally { password.fill('\u0000'); passwordBytes?.fill(0); hash?.fill(0); salt.fill(0) }
    }

    fun decryptText(encoded: String, maxPlainBytes: Int = 1024 * 1024): String {
        val (aes, macKey) = keys()
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var expectedMac: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            receiverRequire(encoded.length <= 2 * 1024 * 1024 && encoded.startsWith("2."),
                BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            val parts = encoded.substring(2).split('|')
            receiverRequire(parts.size == 3, BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            iv = decode(parts[0]); ciphertext = decode(parts[1]); expectedMac = decode(parts[2])
            receiverRequire(iv.size == 16 && expectedMac.size == 32 && ciphertext.isNotEmpty() && ciphertext.size % 16 == 0,
                BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            val calculated = hmac(macKey, iv, ciphertext)
            try { receiverRequire(MessageDigest.isEqual(calculated, expectedMac), BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED) }
            finally { calculated.fill(0) }
            plaintext = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(iv))
                doFinal(ciphertext)
            }
            receiverRequire(plaintext.size <= maxPlainBytes, BitwardenSendReceiverFailure.TOO_LARGE)
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
        } catch (error: BitwardenSendReceiverException) { throw error }
        catch (_: Exception) { throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED) }
        finally { aes.fill(0); macKey.fill(0); iv?.fill(0); ciphertext?.fill(0); expectedMac?.fill(0); plaintext?.fill(0) }
    }

    /** Opens output only after whole-file HMAC AND padding validation on the same retained handle. */
    fun decryptFile(
        file: RandomAccessFile,
        expectedBytes: Long?,
        openVerifiedOutput: () -> OutputStream,
        requireCurrent: () -> Unit,
        maxCipherBytes: Long = MAX_FILE_BYTES,
    ): Long {
        val (aes, macKey) = keys()
        val header = ByteArray(49)
        val buffer = ByteArray(64 * 1024)
        var finalBlock: ByteArray? = null
        var priorBlock: ByteArray? = null
        var paddingBlock: ByteArray? = null
        try {
            requireCurrent()
            val size = file.length()
            receiverRequire(size <= maxCipherBytes, BitwardenSendReceiverFailure.TOO_LARGE)
            receiverRequire(size >= 65 && (size - 49) % 16 == 0L && (expectedBytes == null || size == expectedBytes),
                BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            file.seek(0); file.readFully(header)
            receiverRequire(header[0].toInt() == 2, BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            val iv = header.copyOfRange(1, 17)
            val expectedMac = header.copyOfRange(17, 49)
            try {
                val verifier = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")); update(iv) }
                var remaining = size - 49
                while (remaining > 0) {
                    requireCurrent()
                    val count = file.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    receiverRequire(count > 0, BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
                    verifier.update(buffer, 0, count); remaining -= count
                }
                val calculated = verifier.doFinal()
                try { receiverRequire(MessageDigest.isEqual(expectedMac, calculated), BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED) }
                finally { calculated.fill(0) }

                finalBlock = ByteArray(16); file.seek(size - 16); file.readFully(finalBlock)
                priorBlock = if (size == 65L) iv.copyOf() else ByteArray(16).also { file.seek(size - 32); file.readFully(it) }
                paddingBlock = Cipher.getInstance("AES/CBC/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(priorBlock))
                    doFinal(finalBlock)
                }
                val padding = paddingBlock.last().toInt() and 0xff
                receiverRequire(padding in 1..16 && paddingBlock.takeLast(padding).all { (it.toInt() and 0xff) == padding },
                    BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
                val plainSize = size - 49 - padding
                requireCurrent()
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(iv))
                }
                file.seek(49); remaining = size - 49
                openVerifiedOutput().use { output ->
                    while (remaining > 0) {
                        requireCurrent()
                        val count = file.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        receiverRequire(count > 0, BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
                        val decoded = cipher.update(buffer, 0, count)
                        if (decoded != null) try { requireCurrent(); output.write(decoded) } finally { decoded.fill(0) }
                        remaining -= count
                    }
                    val decoded = cipher.doFinal()
                    try { requireCurrent(); output.write(decoded); output.flush(); requireCurrent() }
                    finally { decoded.fill(0) }
                }
                return plainSize
            } finally { iv.fill(0); expectedMac.fill(0) }
        } finally {
            aes.fill(0); macKey.fill(0); header.fill(0); buffer.fill(0)
            finalBlock?.fill(0); priorBlock?.fill(0); paddingBlock?.fill(0)
        }
    }

    @Synchronized override fun close() {
        raw?.fill(0); enc?.fill(0); auth?.fill(0)
        raw = null; enc = null; auth = null
    }
    override fun toString(): String = "BitwardenSendReceiverCrypto(redacted)"

    companion object {
        const val MAX_FILE_BYTES: Long = 512L * 1024 * 1024
        private fun decode(raw: String): ByteArray {
            val value = Base64.getDecoder().decode(raw)
            if (Base64.getEncoder().encodeToString(value) != raw) {
                value.fill(0)
                throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            }
            return value
        }
        private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }
    }
}
