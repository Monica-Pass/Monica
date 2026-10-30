package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.KdfTransform
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Meta
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.KeePassCodecSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Random

class KeePassNativeKdfInstrumentedTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDF equivalence"))
    private val database = KeePassDatabase.Ver4x.create("Probe", Meta(), credentials)
    private fun parameters(variant: KdfParameters.Argon2.Variant, version: UInt, lanes: UInt, memoryKiB: Int) =
        (database.header.kdfParameters as KdfParameters.Argon2).copy(
            variant = variant, version = version, parallelism = lanes, iterations = 2UL,
            memory = memoryKiB.toULong() * 1024UL,
            salt = okio.ByteString.of(*ByteArray(32) { (it+1).toByte() }), secretKey = null, associatedData = null)

    private fun transform(header: DatabaseHeader): ByteArray {
        val type = Class.forName("app.keemobile.kotpass.cryptography.KeyTransform")
        return type.getMethod("transformedKey", DatabaseHeader::class.java, Credentials::class.java)
            .invoke(type.getField("INSTANCE").get(null), header, credentials) as ByteArray
    }

    @Test fun bothVariantsAndVersionsMatchOriginalIncludingUnevenMemoryAndLanes() {
        for (variant in KdfParameters.Argon2.Variant.entries) for (version in listOf(0x10U, 0x13U))
            for (lanes in listOf(1U, 2U, 3U)) for (memory in listOf(64, 65)) {
                val params = parameters(variant, version, lanes, memory)
                val header = database.header.copy(kdfParameters = params)
                val expected = transform(header)
                val composite = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(requireNotNull(credentials.passphrase).getBinary())
                val originalInput = composite.copyOf()
                val native = KeePassNativeKdf.transform(params, composite)
                try {
                    assertNotNull("Native transform must be present for the validation ABI", native)
                    assertArrayEquals(expected, native)
                    assertArrayEquals(originalInput, composite)
                } finally { expected.fill(0); native?.fill(0); composite.fill(0); originalInput.fill(0) }
            }
    }

    @Test fun secretAssociatedDataAndUnknownVersionKeepTheOriginalTransform() {
        val base = parameters(KdfParameters.Argon2.Variant.Argon2id, 0x13U, 2U, 64)
        for (params in listOf(
            base.copy(secretKey = okio.ByteString.of(1,2,3)),
            base.copy(associatedData = okio.ByteString.of(4,5,6)),
            base.copy(version = 0x12U),
        )) {
            val header = database.header.copy(kdfParameters = params)
            assertNull(KeePassNativeKdf.transform(params, ByteArray(32)))
            val expected = transform(header)
            val actual = KdfTransform.withArgon2(KeePassNativeKdf) { transform(header) }
            try { assertArrayEquals(expected, actual) } finally { expected.fill(0); actual.fill(0) }
        }
    }

    private class FixedRandom : SecureRandom() {
        private val source = Random(323)
        override fun nextBytes(bytes: ByteArray) { source.nextBytes(bytes) }
    }

    @Test fun pairedProductionEncodingMeasuresOriginalAndNativeAtIdenticalCost() {
        val argon = database.copy(header = database.header.copy(kdfParameters =
            parameters(KdfParameters.Argon2.Variant.Argon2d, 0x13U, 2U, 32 * 1024)
                .copy(iterations = 8UL)))
        val aes = database.copy(header = database.header.copy(kdfParameters = KdfParameters.Aes(
            seed = okio.ByteString.of(*ByteArray(32) { (it + 2).toByte() }), rounds = 100000UL)))
        for ((label, source) in listOf("argon2d" to argon, "aes" to aes)) repeat(3) { iteration ->
            val results = mutableMapOf<String, ByteArray>()
            val order = if (iteration % 2 == 0) listOf("original", "native") else listOf("native", "original")
            for (codec in order) {
                val started = android.os.SystemClock.elapsedRealtime()
                val encoded = ByteArrayOutputStream().use { output ->
                    if (codec == "native") KeePassCodecSupport.encode(source, output, FixedRandom())
                    else source.encode(output, contentParser = KeePassCodecSupport.contentParser,
                        cipherProviders = KeePassCodecSupport.cipherProviders, random = FixedRandom())
                    output.toByteArray()
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                results[codec] = encoded
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2,
                    android.os.Bundle().apply {
                        putString("stream", "KDBX_SAVE_PERF stage=paired_${label}_${codec}_$iteration ms=$elapsed\n")
                    })
            }
            assertArrayEquals(results.getValue("original"), results.getValue("native"))
        }
    }

    @Test fun outOfRangeParametersLeaveDerivationWithOriginalCodec() {
        val base = parameters(KdfParameters.Argon2.Variant.Argon2id, 0x13U, 2U, 64)
        for (unsupported in listOf(base.copy(memory = Int.MAX_VALUE.toULong() + 1UL),
            base.copy(iterations = Int.MAX_VALUE.toULong() + 1UL),
            base.copy(parallelism = 0U), base.copy(memory = 1024UL),
            base.copy(salt = okio.ByteString.of(1, 2, 3)))) {
            assertNull(KeePassNativeKdf.transform(unsupported, ByteArray(32)))
        }
        assertNull(KeePassNativeAesKdf.transform(ByteArray(32), Long.MAX_VALUE.toULong() + 1UL, ByteArray(32)))
        assertNull(KeePassNativeAesKdf.transform(ByteArray(16), 1UL, ByteArray(32)))
    }

    @Test fun aesKdfVer3AndVer4MatchTheOriginalIncludingZeroAndHighRounds() {
        val seed = okio.ByteString.of(*ByteArray(32) { (it+2).toByte() })
        val composite = java.security.MessageDigest.getInstance("SHA-256")
            .digest(requireNotNull(credentials.passphrase).getBinary())
        try {
            for (rounds in listOf(0UL, 1UL, 32UL, 100000UL)) {
                val ver3 = KeePassDatabase.Ver3x.create("Probe", Meta(), credentials)
                val sources = listOf(
                    database.copy(header = database.header.copy(kdfParameters = KdfParameters.Aes(seed = seed, rounds = rounds))),
                    ver3.copy(header = ver3.header.copy(transformSeed = seed, transformRounds = rounds)))
                for (source in sources) {
                    val expected = transform(source.header)
                    val native = KeePassNativeAesKdf.transform(seed.toByteArray(), rounds, composite)
                    try { assertNotNull(native); assertArrayEquals(expected, native) }
                    finally { expected.fill(0); native?.fill(0) }
                    val originalFile = ByteArrayOutputStream().use { out ->
                        source.encode(out, contentParser = KeePassCodecSupport.contentParser,
                            cipherProviders = KeePassCodecSupport.cipherProviders, random = FixedRandom())
                        out.toByteArray()
                    }
                    val nativeFile = ByteArrayOutputStream().use { out ->
                        KeePassCodecSupport.encode(source, out, FixedRandom()); out.toByteArray()
                    }
                    assertArrayEquals(originalFile, nativeFile)
                    assertEquals(
                        KeePassDatabase.decode(ByteArrayInputStream(nativeFile), credentials,
                            contentParser = KeePassCodecSupport.contentParser,
                            cipherProviders = KeePassCodecSupport.cipherProviders).content,
                        KeePassCodecSupport.decode(ByteArrayInputStream(originalFile), credentials).content)
                }
            }
        } finally { composite.fill(0) }
    }

    @Test fun productionCodecBytesMatchOriginalAndBothCodecsOpenEachOthersFiles() {
        for (variant in KdfParameters.Argon2.Variant.entries) for (version in listOf(0x10U, 0x13U)) {
            val source = database.copy(header = database.header.copy(
                kdfParameters = parameters(variant, version, 2U, 64)))
            val original = ByteArrayOutputStream().use { out ->
                source.encode(out, contentParser = KeePassCodecSupport.contentParser,
                    cipherProviders = KeePassCodecSupport.cipherProviders, random = FixedRandom())
                out.toByteArray()
            }
            val native = ByteArrayOutputStream().use { out ->
                KeePassCodecSupport.encode(source, out, FixedRandom()); out.toByteArray()
            }
            assertArrayEquals(original, native)
            // KDBX timestamps have whole-second precision in both codecs.
            val expectedContent = KeePassDatabase.decode(ByteArrayInputStream(original), credentials,
                contentParser = KeePassCodecSupport.contentParser,
                cipherProviders = KeePassCodecSupport.cipherProviders).content
            assertEquals(expectedContent, KeePassCodecSupport.decode(ByteArrayInputStream(original), credentials).content)
            assertEquals(expectedContent, KeePassDatabase.decode(ByteArrayInputStream(native), credentials,
                contentParser = KeePassCodecSupport.contentParser, cipherProviders = KeePassCodecSupport.cipherProviders).content)
            val damaged = native.copyOf()
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            assertNotNull(runCatching { KeePassCodecSupport.decode(ByteArrayInputStream(damaged), credentials) }.exceptionOrNull())
        }
    }
}
