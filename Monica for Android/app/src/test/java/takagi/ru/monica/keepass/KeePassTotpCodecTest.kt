package takagi.ru.monica.keepass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.util.TotpGenerator

class KeePassTotpCodecTest {
    @Test
    fun `invalid native HOTP counters must never restart from zero`() {
        for (counter in listOf("-1", "9223372036854775808", "invalid")) {
            val fields = mapOf("HmacOtp-Secret-Base32" to "JBSWY3DPEHPK3PXP", "HmacOtp-Counter" to counter)
            assertNull(KeePassTotpCodec.parseFields({ fields[it].orEmpty() }))
        }
    }


    @Test
    fun nativeKeePassSecretEncodingsGenerateRfc4226And6238Codes() {
        val representations = mapOf(
            "Secret" to "12345678901234567890",
            "Secret-Hex" to "3132333435363738393031323334353637383930",
            "Secret-Base32" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            "Secret-Base64" to "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA="
        )
        representations.forEach { (encoding, secret) ->
            val timeFields = mapOf("TimeOtp-$encoding" to secret, "TimeOtp-Length" to "8")
            val time = requireNotNull(KeePassTotpCodec.parseFields({ timeFields[it].orEmpty() }))
            assertEquals("94287082", TotpGenerator.generateTotp(time.secret, 59, time.period, time.digits, time.algorithm))
            val counterFields = mapOf("HmacOtp-$encoding" to secret, "HmacOtp-Counter" to "2")
            val counter = requireNotNull(KeePassTotpCodec.parseFields({ counterFields[it].orEmpty() }))
            assertEquals(OtpType.HOTP, counter.otpType)
            assertEquals("359152", TotpGenerator.generateHotp(counter.secret, counter.counter, counter.digits, counter.algorithm))
        }
    }

    @Test
    fun nativeKeePassAlgorithmsMatchRfc6238Vectors() {
        listOf(
            Triple("HMAC-SHA-256", "12345678901234567890123456789012", "46119246"),
            Triple("HMAC-SHA-512", "1234567890123456789012345678901234567890123456789012345678901234", "90693936")
        ).forEach { (algorithm, secret, code) ->
            val fields = mapOf("TimeOtp-Secret" to secret, "TimeOtp-Length" to "8", "TimeOtp-Algorithm" to algorithm)
            val data = requireNotNull(KeePassTotpCodec.parseFields({ fields[it].orEmpty() }))
            assertEquals(code, TotpGenerator.generateTotp(data.secret, 59, data.period, data.digits, data.algorithm))
        }
    }

    @Test
    fun writesConsistentKeePassNativeAndUriRepresentations() {
        listOf(OtpType.TOTP, OtpType.HOTP, OtpType.STEAM).forEach { type ->
            val original = TotpData(secret = "JBSWY3DPEHPK3PXP", otpType = type,
                issuer = "Example", accountName = "alice", digits = if (type == OtpType.STEAM) 5 else 6,
                counter = if (type == OtpType.HOTP) 42 else 0)
            val written = KeePassTotpCodec.toKeePassFields(original, "Example")
            val uri = requireNotNull(KeePassTotpCodec.parseFields({ written[it].orEmpty() }))
            assertEquals(original, uri)
            if (type != OtpType.STEAM) {
                val nativeOnly = written.filterKeys { it.startsWith("TimeOtp-") || it.startsWith("HmacOtp-") }
                val native = requireNotNull(KeePassTotpCodec.parseFields({ nativeOnly[it].orEmpty() }, "Example", "alice"))
                assertEquals(original, native)
            }
        }
    }

    @Test
    fun malformedOrAmbiguousNativeSecretsAreNotTreatedAsValidCodes() {
        val fields = mapOf("TimeOtp-Secret-Base32" to "JBSWY3DPEHPK3PXP", "TimeOtp-Secret" to "different secret")
        assertNull(KeePassTotpCodec.parseFields({ fields[it].orEmpty() }))
        assertNull(KeePassTotpCodec.parse(KeePassTotpCodec.Fields(otp = "key=invalid!&step=30&size=6")))
    }

    @Test
    fun parsesTrayTotpPositionalSettingsWithoutConfusingDefaultPeriodWithDigits() {
        val data = requireNotNull(KeePassTotpCodec.parse(KeePassTotpCodec.Fields(seed = "JBSWY3DPEHPK3PXP", settings = "30;8")))
        assertEquals(30, data.period)
        assertEquals(8, data.digits)
    }

    @Test
    fun parsesKeeOtpKeyValueFormatInsteadOfTreatingTheWholeQueryAsASecret() {
        val data = requireNotNull(KeePassTotpCodec.parse(KeePassTotpCodec.Fields(otp = "key=JBSWY3DPEHPK3PXP&step=60&size=8")))
        assertEquals("JBSWY3DPEHPK3PXP", data.secret)
        assertEquals(60, data.period)
        assertEquals(8, data.digits)
    }

    @Test
    fun uriComponentsAreDecodedOnceAndLiteralPlusInAccountIsPreserved() {
        val data = requireNotNull(KeePassTotpCodec.parse(KeePassTotpCodec.Fields(
            otp = "otpauth://totp/Example:alice+tag%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=R%26D%20%2526"
        )))
        assertEquals("alice+tag@example.com", data.accountName)
        assertEquals("R&D %26", data.issuer)
    }

    @Test
    fun steamEncoderAndTrayTotpSteamSettingsProduceSteamTokens() {
        val uri = requireNotNull(KeePassTotpCodec.parse(KeePassTotpCodec.Fields(
            otp = "otpauth://totp/Steam:alice?secret=JBSWY3DPEHPK3PXP&encoder=steam"
        )))
        val tray = requireNotNull(KeePassTotpCodec.parse(KeePassTotpCodec.Fields(seed = "JBSWY3DPEHPK3PXP", settings = "30;S")))
        assertEquals(OtpType.STEAM, uri.otpType)
        assertEquals(OtpType.STEAM, tray.otpType)
        assertEquals(5, tray.digits)
    }

    @Test
    fun parsesOtpAuthUri() {
        val data = KeePassTotpCodec.parse(
            KeePassTotpCodec.Fields(
                otp = "otpauth://totp/GitHub:user%40example.com?secret=jbsw-y3dp%20ehpk3pxp&issuer=GitHub&algorithm=SHA256&digits=8&period=45",
                issuer = "Fallback",
                accountName = "fallback-user",
                link = "https://github.com"
            )
        )

        assertNotNull(data)
        requireNotNull(data)
        assertEquals("JBSWY3DPEHPK3PXP", data.secret)
        assertEquals("GitHub", data.issuer)
        assertEquals("user@example.com", data.accountName)
        assertEquals("SHA256", data.algorithm)
        assertEquals(8, data.digits)
        assertEquals(45, data.period)
        assertEquals(OtpType.TOTP, data.otpType)
        assertEquals("https://github.com", data.link)
    }

    @Test
    fun parsesPlainOtpSecretWithSettings() {
        val data = KeePassTotpCodec.parse(
            KeePassTotpCodec.Fields(
                otp = "jbsw-y3dp ehpk3pxp",
                settings = "period=60;digits=8;algorithm=sha512",
                issuer = "GitLab",
                accountName = "user"
            )
        )

        assertNotNull(data)
        requireNotNull(data)
        assertEquals("JBSWY3DPEHPK3PXP", data.secret)
        assertEquals("GitLab", data.issuer)
        assertEquals("user", data.accountName)
        assertEquals(60, data.period)
        assertEquals(8, data.digits)
        assertEquals("SHA512", data.algorithm)
    }

    @Test
    fun parsesTotpSeedWithSeparateFields() {
        val data = KeePassTotpCodec.parse(
            KeePassTotpCodec.Fields(
                seed = "abcd efgh ijkl mnop",
                period = "45",
                digits = "7",
                algorithm = "sha256",
                issuer = "KeePassDX",
                accountName = "alice"
            )
        )

        assertNotNull(data)
        requireNotNull(data)
        assertEquals("ABCDEFGHIJKLMNOP", data.secret)
        assertEquals(45, data.period)
        assertEquals(7, data.digits)
        assertEquals("SHA256", data.algorithm)
    }

    @Test
    fun parsesHotpCounterFromSettingsOrSeparateField() {
        val settingsData = KeePassTotpCodec.parse(
            KeePassTotpCodec.Fields(
                seed = "JBSWY3DPEHPK3PXP",
                settings = "type=hotp counter=42"
            )
        )
        assertNotNull(settingsData)
        requireNotNull(settingsData)
        assertEquals(OtpType.HOTP, settingsData.otpType)
        assertEquals(42L, settingsData.counter)

        val separateData = KeePassTotpCodec.parse(
            KeePassTotpCodec.Fields(
                seed = "JBSWY3DPEHPK3PXP",
                counter = "99"
            )
        )
        assertNotNull(separateData)
        requireNotNull(separateData)
        assertEquals(OtpType.HOTP, separateData.otpType)
        assertEquals(99L, separateData.counter)
    }

    @Test
    fun returnsNullWhenNoSecretExists() {
        val data = KeePassTotpCodec.parse(
            KeePassTotpCodec.Fields(
                settings = "period=60;digits=8",
                issuer = "No secret"
            )
        )

        assertNull(data)
    }

    @Test
    fun emitsKeePassCompatibleTotpFields() {
        val fields = KeePassTotpCodec.toKeePassFields(
            data = TotpData(
                secret = "jbsw-y3dp ehpk3pxp",
                issuer = "GitHub",
                accountName = "user@example.com",
                period = 45,
                digits = 8,
                algorithm = "sha256"
            ),
            title = "GitHub"
        )

        assertEquals("JBSWY3DPEHPK3PXP", fields.getValue(KeePassTotpCodec.FIELD_TOTP_SEED))
        assertEquals("45", fields.getValue(KeePassTotpCodec.FIELD_TOTP_PERIOD))
        assertEquals("8", fields.getValue(KeePassTotpCodec.FIELD_TOTP_DIGITS))
        assertEquals("SHA256", fields.getValue(KeePassTotpCodec.FIELD_TOTP_ALGORITHM))
        assertEquals("TOTP", fields.getValue(KeePassTotpCodec.FIELD_OTP_TYPE))
        assertEquals("45;8", fields.getValue(KeePassTotpCodec.FIELD_TOTP_SETTINGS))
        assertEquals("HMAC-SHA-256", fields.getValue("TimeOtp-Algorithm"))
        assertEquals(
            "otpauth://totp/GitHub%3Auser%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=45",
            fields.getValue(KeePassTotpCodec.FIELD_OTP)
        )
    }

    @Test
    fun emitsKeePassCompatibleHotpFields() {
        val fields = KeePassTotpCodec.toKeePassFields(
            data = TotpData(
                secret = "JBSWY3DPEHPK3PXP",
                issuer = "Example",
                accountName = "alice",
                otpType = OtpType.HOTP,
                counter = 12L
            ),
            title = "Example"
        )

        assertEquals("HOTP", fields.getValue(KeePassTotpCodec.FIELD_OTP_TYPE))
        assertEquals("12", fields.getValue(KeePassTotpCodec.FIELD_HOTP_COUNTER))
        assertEquals("12", fields.getValue("HmacOtp-Counter"))
        assertEquals("JBSWY3DPEHPK3PXP", fields.getValue("HmacOtp-Secret-Base32"))
        assertNull(fields[KeePassTotpCodec.FIELD_TOTP_SEED])
        assertEquals(
            "otpauth://hotp/Example%3Aalice?secret=JBSWY3DPEHPK3PXP&issuer=Example&counter=12",
            fields.getValue(KeePassTotpCodec.FIELD_OTP)
        )
    }
}
