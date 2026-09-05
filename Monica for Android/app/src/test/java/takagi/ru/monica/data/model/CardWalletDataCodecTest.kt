package takagi.ru.monica.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWalletDataCodecTest {

    @Test
    fun bankCardFaceConfigRoundTrips() {
        val data = BankCardData(
            cardNumber = "4242424242424242",
            cardholderName = "Monica User",
            expiryMonth = "12",
            expiryYear = "30",
            cardFace = CardFaceConfig(
                imageAttachmentName = CardFaceAttachment.fileNameFor("0123456789abcdef"),
                displayMode = CardFaceDisplayMode.CARD_NUMBER_ONLY,
                showBrandIcon = false
            )
        )

        val encoded = CardWalletDataCodec.encodeBankCardData(data)
        val decoded = CardWalletDataCodec.parseBankCardData(encoded)

        assertEquals(data, decoded)
        assertTrue(encoded.contains("cardFace"))
    }

    @Test
    fun brandIconPreferenceSurvivesSyncMetadataAndOlderCardFacesKeepTheirDefault() {
        val face = CardFaceConfig(
            imageAttachmentName = CardFaceAttachment.fileNameFor("0123456789abcdef"),
            displayMode = CardFaceDisplayMode.CARD_NUMBER_ONLY,
            showBrandIcon = false
        )
        assertEquals(face, CardWalletDataCodec.parseCardFaceConfig(CardWalletDataCodec.encodeCardFaceConfig(face)))
        val legacy = """{"imageAttachmentName":"monica_card_face_0123456789abcdef.jpg","displayMode":"ALL"}"""
        assertTrue(requireNotNull(CardWalletDataCodec.parseCardFaceConfig(legacy)).showBrandIcon)
    }

    @Test
    fun legacyBankCardWithoutCardFaceKeepsOriginalLayout() {
        val legacy = """
            {
              "cardNumber": "4242424242424242",
              "cardholderName": "Monica User",
              "expiryMonth": "12",
              "expiryYear": "30"
            }
        """.trimIndent()

        val decoded = CardWalletDataCodec.parseBankCardData(legacy)

        assertNotNull(decoded)
        assertNull(decoded?.cardFace)
    }

    @Test
    fun untrustedCardFaceAttachmentNameIsDiscarded() {
        val raw = """
            {
              "cardNumber": "4242424242424242",
              "cardholderName": "Monica User",
              "expiryMonth": "12",
              "expiryYear": "30",
              "cardFace": {
                "imageAttachmentName": "../../secret.jpg",
                "displayMode": "HIDDEN"
              }
            }
        """.trimIndent()

        val decoded = CardWalletDataCodec.parseBankCardData(raw)

        assertNull(decoded?.cardFace)
    }

    @Test
    fun billingAddressDataRoundTrips() {
        val data = BillingAddressData(
            fullName = "Monica User",
            company = "Monica Pass",
            streetAddress = "1 Test Street",
            apartment = "Room 2",
            city = "Tokyo",
            stateProvince = "Tokyo",
            postalCode = "100-0001",
            country = "JP",
            phone = "+81 00 0000 0000",
            email = "monica@example.com",
            isDefault = true
        )

        val encoded = CardWalletDataCodec.encodeBillingAddressData(data)
        val decoded = CardWalletDataCodec.parseBillingAddressData(encoded)

        assertEquals(data, decoded)
    }

    @Test
    fun documentAndBillingCardFacesRoundTripAndValidateAttachmentName() {
        val face = CardFaceConfig(
            imageAttachmentName = CardFaceAttachment.fileNameFor("fedcba9876543210"),
            displayMode = CardFaceDisplayMode.HIDDEN,
            showBrandIcon = false
        )
        val document = DocumentData(
            documentType = DocumentType.PASSPORT,
            documentNumber = "P1234567",
            fullName = "Monica User",
            cardFace = face
        )
        val billing = BillingAddressData(
            fullName = "Monica User",
            streetAddress = "1 Test Street",
            cardFace = face
        )

        assertEquals(document, CardWalletDataCodec.parseDocumentData(CardWalletDataCodec.encodeDocumentData(document)))
        assertEquals(billing, CardWalletDataCodec.parseBillingAddressData(CardWalletDataCodec.encodeBillingAddressData(billing)))

        val untrustedDocument = """{
            "documentType":"PASSPORT",
            "documentNumber":"P1234567",
            "fullName":"Monica User",
            "cardFace":{"imageAttachmentName":"../../secret.jpg"}
        }"""
        val untrustedBilling = """{
            "fullName":"Monica User",
            "streetAddress":"1 Test Street",
            "cardFace":{"imageAttachmentName":"file:///secret.jpg"}
        }"""
        assertEquals(null, CardWalletDataCodec.parseDocumentData(untrustedDocument)?.cardFace)
        assertEquals(null, CardWalletDataCodec.parseBillingAddressData(untrustedBilling)?.cardFace)
    }

    @Test
    fun legacyBillingAddressJsonParsesAsBillingAddressData() {
        val legacy = BillingAddress(
            streetAddress = "1 Test Street",
            apartment = "Room 2",
            city = "Tokyo",
            stateProvince = "Tokyo",
            postalCode = "100-0001",
            country = "JP"
        )
        val encoded = CardWalletDataCodec.encodeBillingAddress(legacy)

        val decoded = CardWalletDataCodec.parseBillingAddressData(encoded)

        assertNotNull(decoded)
        assertEquals("1 Test Street", decoded?.streetAddress)
        assertEquals("Room 2", decoded?.apartment)
        assertEquals("Tokyo", decoded?.city)
        assertFalse(decoded?.isEmpty() ?: true)
    }

    @Test
    fun paymentAccountDataRoundTrips() {
        val data = PaymentAccountData(
            paymentType = PaymentAccountType.PAYMENT_APP,
            provider = "PayPal",
            accountName = "Shopping",
            accountHolderName = "Monica User",
            email = "monica@example.com",
            phone = "+1 000 000 0000",
            username = "monica",
            accountId = "acct_123",
            linkedCardLast4 = "4242",
            billingAddress = CardWalletDataCodec.encodeBillingAddress(
                BillingAddress(
                    streetAddress = "1 Test Street",
                    city = "Tokyo",
                    postalCode = "100-0001",
                    country = "JP"
                )
            ),
            website = "https://paypal.com",
            currency = "USD",
            notes = "Primary payment app",
            isDefault = true,
            customFields = listOf(SecureCustomField(label = "Customer ID", value = "C-123"))
        )

        val encoded = CardWalletDataCodec.encodePaymentAccountData(data)
        val decoded = CardWalletDataCodec.parsePaymentAccountData(encoded)

        assertEquals(data, decoded)
    }

    @Test
    fun legacyPaymentAccountJsonParsesAsPaymentAccountData() {
        val legacy = """
            {
              "type": "bank_account",
              "service": "Wise",
              "name": "Travel balance",
              "holderName": "Monica User",
              "email": "monica@example.com",
              "accountNumber": "****1234",
              "swift": "TRWIBEB1",
              "address1": "1 Test Street",
              "city": "Tokyo",
              "zip": "100-0001",
              "country": "JP"
            }
        """.trimIndent()

        val decoded = CardWalletDataCodec.parsePaymentAccountData(legacy)

        assertNotNull(decoded)
        assertEquals(PaymentAccountType.BANK_ACCOUNT, decoded?.paymentType)
        assertEquals("Wise", decoded?.provider)
        assertEquals("Travel balance", decoded?.accountName)
        assertEquals("****1234", decoded?.maskedAccountNumber)
        assertEquals("TRWIBEB1", decoded?.swiftBic)
        assertEquals("Tokyo", CardWalletDataCodec.parseBillingAddress(decoded?.billingAddress.orEmpty()).city)
        assertFalse(decoded?.isEmpty() ?: true)
    }
}
