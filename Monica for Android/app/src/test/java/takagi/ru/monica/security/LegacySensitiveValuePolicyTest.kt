package takagi.ru.monica.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import java.util.Base64

class LegacySensitiveValuePolicyTest {
    private val shortSecret = "JBSWY3DPEHPK3PXP"
    private val longSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
    private fun accepts(raw: String) = LegacySensitiveValuePolicy.acceptsItem(ItemType.TOTP, raw)

    @Test fun commonBase32AcceptsCaseAndExistingSpaceHyphenSeparators() {
        assertTrue(accepts(shortSecret))
        assertTrue(accepts("  jbsw-y3dp ehpk-3pxp  "))
        assertTrue(accepts("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        assertTrue(LegacySensitiveValuePolicy.acceptsItem(ItemType.TOTP, shortSecret))
        assertTrue(LegacySensitiveValuePolicy.acceptsAuthenticatorKey(shortSecret))
    }

    @Test fun base32RejectsInvalidAlphabetResidualBitsAndPadding() {
        for (raw in listOf("invalid secret!", "1234567890", "JBSWY3DPEHPK3PXP0", "$shortSecret=",
                "${shortSecret}A", "${shortSecret}AB", "not-a-key")) {
            assertFalse(accepts(raw))
        }
        assertFalse(accepts("JBSWY3DP\tEHPK3PXP"))
        assertFalse(accepts("JBSWY3DP\nEHPK3PXP"))
    }

    @Test fun prefixesAndBase64OnlyValuesAreNeverAssumedPlaintext() {
        for (prefix in listOf("MDK|", "C2|", "V2|", "V3|", "future-format|", "v500:")) {
            assertFalse(accepts(prefix + shortSecret))
            assertFalse(LegacySensitiveValuePolicy.acceptsItem(ItemType.BANK_CARD, prefix + "{}"))
        }
        assertFalse(accepts("QUJDREVGR0hJSktMTU5PUFFSU1Q="))
        assertFalse(accepts(Base64.getEncoder().encodeToString(ByteArray(60) { it.toByte() })))
    }

    @Test fun bareValuesAtLegacyCiphertextMinimumAreRetainedEvenWhenAllLettersAreBase32() {
        // Forty 'A' characters are simultaneously 25 bytes of Base32 and 30 bytes of Base64.
        assertFalse(accepts("A".repeat(40)))
        assertFalse(accepts("A".repeat(32) + " " + "A".repeat(8)))
        assertFalse(accepts("A".repeat(32) + "-" + "A".repeat(8)))
        assertTrue(accepts("A".repeat(32))) // Only 24 bytes if interpreted as Base64: cannot be V1.
    }

    @Test fun longBase32NeedsAnExplicitOtpFormat() {
        assertFalse(accepts(longSecret))
        assertTrue(accepts("otpauth://totp/Synthetic?secret=$longSecret"))
        assertTrue(accepts("""{"secret":"$longSecret","issuer":"Synthetic"}"""))
    }

    @Test fun passwordAuthenticatorEligibilityMatchesItsUriOrBase32OnlyReader() {
        assertTrue(LegacySensitiveValuePolicy.acceptsAuthenticatorKey("otpauth://totp/Synthetic?secret=$longSecret"))
        for (raw in listOf("""{"secret":"$shortSecret"}""", "MDK|broken", "A".repeat(40), "arbitrary plaintext!")) {
            assertFalse(LegacySensitiveValuePolicy.acceptsAuthenticatorKey(raw))
        }
    }

    @Test fun currentAndLegacyOtpJsonKeepUnknownAttributesEligibleForOriginalByteWrapping() {
        assertTrue(accepts(""" { "secret" : "$shortSecret", "issuer":"Synthetic", "future":{"a":[1,true,null]}, "escape":"\u0041" } """))
        assertTrue(accepts("""{"key":"$shortSecret","serviceName":"Synthetic","account":"test@example.invalid"}"""))
        assertTrue(accepts("""{"secret":"$shortSecret","otpType":"HOTP","counter":42,"algorithm":"SHA256","digits":8}"""))
    }

    @Test fun encryptedOrMalformedInnerSecretIsNotMadeEligibleByPlainOuterJson() {
        for (secret in listOf("MDK|broken", "C2|broken", "V2|broken", "V42|unknown", "QUJDREVGR0hJSktMTU5PUFFSU1Q=", "anything!")) {
            assertFalse(accepts("""{"secret":"$secret","issuer":"Synthetic"}"""))
        }
        assertFalse(accepts("""{"secret":{"value":"$shortSecret"}}"""))
        assertFalse(accepts("""{"secret":null}"""))
        assertFalse(accepts("""{"issuer":"Synthetic"}"""))
        assertFalse(accepts("""{"key":"$shortSecret","otpType":"HOTP","counter":2}"""))
    }

    @Test fun ambiguousAliasesAndUnknownOtpParametersAreRetained() {
        assertFalse(accepts("""{"secret":"$shortSecret","key":"GEZDGNBVGY3TQOJQ"}"""))
        for (field in listOf("\"period\":0", "\"period\":{}", "\"digits\":11", "\"counter\":-1",
                "\"algorithm\":\"ROT13\"", "\"otpType\":\"FUTURE\"", "\"pin\":\"MDK|cipher\"")) {
            assertFalse(accepts("""{"secret":"$shortSecret",$field}"""))
        }
    }

    @Test fun standardTotpHotpAndYandexUrisHaveValidatedSecretsAndParameters() {
        assertTrue(accepts("otpauth://totp/Synthetic:user?secret=$shortSecret&issuer=Synthetic&algorithm=SHA512&digits=8&period=60"))
        assertTrue(accepts("otpauth://hotp/Synthetic:user?secret=$shortSecret&counter=0"))
        assertTrue(accepts("otpauth://yaotp/Synthetic:user?secret=$shortSecret"))
        assertTrue(accepts("otpauth://totp/Synthetic?secret=$shortSecret&encoder=steam&digits=5"))
    }

    @Test fun uriParserSuccessAloneDoesNotAcceptMissingBadOrRepeatedOtpInputs() {
        for (raw in listOf("otpauth://totp/Synthetic?secret=not-a-valid-secret!",
                "otpauth://totp/Synthetic?secret=MDK%7Cbroken", "otpauth://totp/Synthetic?issuer=Synthetic",
                "otpauth://future/Synthetic?secret=$shortSecret", "otpauth://totp/?secret=$shortSecret",
                "otpauth://hotp/Synthetic?secret=$shortSecret", "otpauth://totp/Synthetic?secret=$shortSecret&digits=no",
                "otpauth://totp/Synthetic?secret=$shortSecret&secret=$shortSecret",
                "otpauth://totp/Synthetic?secret=$shortSecret&%73ecret=$shortSecret",
                "otpauth://totp/Synthetic?secret=$shortSecret#fragment",
                "otpauth://totp/Synthetic?secret=$shortSecret&issuer=%FF",
                "otpauth://totp/Synthetic?secret=%ZZ", "https://example.invalid/?secret=$shortSecret")) {
            assertFalse(accepts(raw))
        }
    }

    @Test fun explicitSteamAndMobileOtpFormatsRemainRecognizable() {
        assertTrue(accepts("steam://QUJDREVGR0hJSktMTU5PUFFSU1Q="))
        assertTrue(accepts("steam://$shortSecret"))
        assertTrue(accepts("motp://Synthetic:account?secret=0123456789abcdef&pin=1234"))
        assertTrue(accepts("""{"secret":"0123456789abcdef","otpType":"MOTP","pin":"1234"}"""))
        assertFalse(accepts("motp://Synthetic:account?secret=0123456789abcdef"))
        assertFalse(accepts("steam://MDK%7Cbroken"))
        assertFalse(accepts("steam://QUJDREVGR0hJSktMTU5PUFFSU1Q=?unknown=1"))
        assertFalse(accepts("phonefactor://unsupported"))
        assertFalse(accepts("otpauth-migration://offline?data=unknown"))
    }

    @Test fun realBankCardShapesRequireTheirOwnFieldsAndExistingCodecReadability() {
        assertTrue(LegacySensitiveValuePolicy.acceptsItem(ItemType.BANK_CARD,
            """{"cardNumber":"4242 4242 4242 4242","cardholderName":"Synthetic","expiryMonth":"12","expiryYear":"30","future":{"nested":[1,2]}}"""))
        assertTrue(LegacySensitiveValuePolicy.acceptsItem(ItemType.BANK_CARD,
            """{"number":"4242424242424242","expMonth":"12","expYear":"30"}"""))
        for (raw in listOf("{}", "[]", "null", "\"card\"", """{"secret":"$shortSecret"}""",
                """{"number":"4242424242424242"}""", """{"cardNumber":{}}""", """{"cardNumber":true}""",
                """{"cardNumber":"MDK|broken"}""", """{"cardNumber":"4242","cvv":"V3|unknown"}""")) {
            assertFalse(LegacySensitiveValuePolicy.acceptsItem(ItemType.BANK_CARD, raw))
        }
    }

    @Test fun documentShapeMustBeTypedAndReadableByTheExistingCodec() {
        assertTrue(LegacySensitiveValuePolicy.acceptsItem(ItemType.DOCUMENT,
            """{"documentType":"PASSPORT","documentNumber":"SYNTHETIC-001","fullName":"Synthetic","future":[{"k":true}]}"""))
        assertTrue(LegacySensitiveValuePolicy.acceptsItem(ItemType.DOCUMENT,
            """{"documentType":"driver_license","documentNumber":"SYNTHETIC-002","firstName":"Synthetic"}"""))
        for (raw in listOf("{}", """{"cardNumber":"4242"}""", """{"documentNumber":"Synthetic"}""",
                """{"documentType":"unknown","documentNumber":"Synthetic"}""",
                """{"documentType":"PASSPORT","documentNumber":"C2|unreadable"}""",
                """{"type":"passport","number":"Synthetic"}""")) {
            assertFalse(LegacySensitiveValuePolicy.acceptsItem(ItemType.DOCUMENT, raw))
        }
    }

    @Test fun unrelatedTypesAreNotPartOfThisMigration() {
        for (type in listOf(ItemType.PASSWORD, ItemType.NOTE, ItemType.BILLING_ADDRESS, ItemType.PAYMENT_ACCOUNT)) {
            assertFalse(LegacySensitiveValuePolicy.acceptsItem(type, """{"secret":"$shortSecret"}"""))
        }
    }

    @Test fun malformedJsonAndDuplicateIncludingEscapedKeysAreRejected() {
        for (raw in listOf("""{"secret":"$shortSecret",}""", """{secret:"$shortSecret"}""",
                """{"secret":"$shortSecret"} trailing""", """{"secret":"$shortSecret","secret":"$shortSecret"}""",
                """{"secret":"$shortSecret","\u0073ecret":"$shortSecret"}""",
                """{"secret":"$shortSecret","future":NaN}""",
                """{"secret":"$shortSecret","future":012}""",
                """{"secret":"$shortSecret","future":{"a":1,"a":2}}""")) {
            assertFalse(accepts(raw))
        }
        assertTrue(accepts("""{"secret":"$shortSecret","future":{"a":1},"other":{"a":2},"text":"{[\"key\":]}"}"""))
    }

    @Test fun sizeAndNestingAreBoundedBeforeParsing() {
        assertFalse(accepts(""))
        assertFalse(accepts(" \r\n\t"))
        assertFalse(accepts("A".repeat(1024 * 1024 + 1)))
        assertFalse(accepts("""{"secret":"$shortSecret","future":"${"中".repeat(400_000)}"}"""))
        assertFalse(accepts("""{"secret":"$shortSecret","future":${"[".repeat(2000)}0${"]".repeat(2000)}}"""))
        assertTrue(accepts("""{"secret":"$shortSecret","future":${"[".repeat(8)}0${"]".repeat(8)}}"""))
    }
}
