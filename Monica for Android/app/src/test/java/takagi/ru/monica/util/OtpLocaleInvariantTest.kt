package takagi.ru.monica.util

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class OtpLocaleInvariantTest {
    @Test fun rfcHotpAndTotpVectorsRemainAsciiAcrossDisplayLanguages() {
        val original = Locale.getDefault()
        try {
            val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
            val hotp = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
            val totp = listOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471", 1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130")
            for (tag in listOf("ar", "ar-EG", "ar-SA", "fr-FR", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                hotp.forEachIndexed { counter, expected ->
                    assertEquals("$tag HOTP $counter", expected, TotpGenerator.generateHotp(secret, counter.toLong()))
                }
                totp.forEach { (time, expected) ->
                    assertEquals("$tag TOTP $time", expected, TotpGenerator.generateTotp(secret, time, digits = 8))
                }
            }
        } finally { Locale.setDefault(original) }
    }
}
