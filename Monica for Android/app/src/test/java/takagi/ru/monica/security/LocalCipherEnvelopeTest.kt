package takagi.ru.monica.security

import org.junit.Assert.*
import org.junit.Test

class LocalCipherEnvelopeTest {
    @Test fun readerAndMigrationRecognizeEveryKotlinOuterWhitespaceCharacter() {
        val whitespace = Char.MIN_VALUE..Char.MAX_VALUE
        whitespace.filter { it.isWhitespace() }.forEach { space ->
            LocalCipherEnvelope.Format.entries.forEach { format ->
                assertEquals("U+${space.code.toString(16)}", format,
                    LocalCipherEnvelope.format("$space${format.prefix}fixture$space"))
                assertEquals(format.deviceBound, LocalCipherEnvelope.isDeviceBound("$space${format.prefix}fixture"))
            }
        }
    }

    @Test fun detectionDoesNotTreatPlaintextUnknownFormatsOrEmbeddedPrefixesAsCiphertext() {
        listOf("", " \n\t", "ordinary C2|text", "V3|new-format", "mdk|case-sensitive", "C2 |not-a-prefix",
            "\u200bC2|zero-width-space-is-not-trimmable", "base64-old-or-plain").forEach {
            assertNull(it, LocalCipherEnvelope.format(it))
            assertFalse(LocalCipherEnvelope.isDeviceBound(it))
        }
    }
}
