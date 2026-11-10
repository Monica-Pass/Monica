package takagi.ru.monica.ui.screens

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.util.OtpParametersDraft
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.util.TotpUriParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class PasswordAuthenticatorDraftTest {
    private val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun allFiveTypesKeepTheirParametersAndGeneratedCodeAfterPasswordRoundtrip() {
        for (type in OtpType.entries) {
            val secret = if (type == OtpType.MOTP) "a1b2c3d4e5f60708" else base32
            val parameters = OtpParametersDraft(period = "45", digits = "8", algorithm = "SHA256",
                counter = "1099511627776", pin = "1234").selectType(type)
            val original = parameters.toData(secret, type, "Example", "alice@example.invalid")
            val payload = buildPasswordScreenAuthenticatorPayload(secret, type, original.issuer, original.accountName, parameters)
            val draft = resolvePasswordScreenAuthenticatorDraft(payload)
            assertEquals(type.name, type, draft.otpType)
            val restored = requireNotNull(TotpDataResolver.fromAuthenticatorKey(payload))
            assertEquals(type.name, original.secret, restored.secret)
            if (type != OtpType.HOTP) assertEquals(type.name, original.period, restored.period)
            assertEquals(type.name, original.digits, restored.digits)
            assertEquals(type.name, original.algorithm, restored.algorithm)
            if (type == OtpType.HOTP) assertEquals(original.counter, restored.counter)
            if (type in listOf(OtpType.MOTP, OtpType.YANDEX)) assertEquals(original.pin, restored.pin)
            assertEquals(type.name, TotpGenerator.generateOtp(original, currentSeconds = 1_700_000_000),
                TotpGenerator.generateOtp(restored, currentSeconds = 1_700_000_000))
        }
    }

    @Test fun hotpUsesTheStoredCounterAndPreviewDoesNotAdvanceIt() {
        val payload = buildPasswordScreenAuthenticatorPayload(base32, OtpType.HOTP, "Example", "alice",
            OtpParametersDraft(counter = "0"))
        val parsed = requireNotNull(TotpDataResolver.fromAuthenticatorKey(payload))
        repeat(2) { assertEquals("755224", TotpGenerator.generateOtp(parsed)) }
        assertEquals(0L, parsed.counter)
    }

    @Test fun publicMotpQrOmitsPinWhilePasswordStorageIncludesIt() {
        val data = OtpParametersDraft(pin = "0421").selectType(OtpType.MOTP)
            .toData("abcd1234", OtpType.MOTP, "Example:Work", "alice&ops")
        val publicUri = TotpUriParser.generateUri("Example", data)
        assertFalse(publicUri.contains("pin="))
        val stored = TotpDataResolver.toBitwardenPayload("Example", data)
        val parsed = requireNotNull(TotpDataResolver.fromAuthenticatorKey(stored))
        assertEquals("0421", parsed.pin)
        assertEquals("Example:Work", parsed.issuer)
        assertEquals("alice&ops", parsed.accountName)
        assertEquals("abcd1234", parsed.secret)
    }

    @Test fun credentialDraftsRetainIndependentCounterPinAndUnfinishedInput() {
        val drafts = listOf(OtpParametersDraft(counter = "9007199254740993"), OtpParametersDraft(pin = "0012"),
            OtpParametersDraft(period = "", digits = ""))
        val restored = drafts.map { OtpParametersDraft.decode(it.encode()) }
        assertEquals(drafts, restored)
        assertFalse(restored[2].isValid(OtpType.TOTP))
        assertTrue(restored[0].isValid(OtpType.HOTP))
        assertFalse(restored[0].copy(counter = "9223372036854775808").isValid(OtpType.HOTP))
        assertFalse(restored[1].copy(pin = "12").isValid(OtpType.MOTP))
    }

    @Test fun changingSecretKeepsImportedHotpSettings() {
        val source = "otpauth://hotp/Example:alice?secret=$base32&counter=99&digits=8&algorithm=SHA512"
        val imported = resolvePasswordScreenAuthenticatorDraft(source)
        val edited = buildPasswordScreenAuthenticatorPayload("JBSWY3DPEHPK3PXP", imported.otpType,
            "Example", "alice", imported.parameters)
        val data = requireNotNull(TotpDataResolver.fromAuthenticatorKey(edited))
        assertEquals(OtpType.HOTP, data.otpType)
        assertEquals(99L, data.counter)
        assertEquals(8, data.digits)
        assertEquals("SHA512", data.algorithm)
    }
}
