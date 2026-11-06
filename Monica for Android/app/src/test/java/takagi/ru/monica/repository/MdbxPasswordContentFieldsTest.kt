package takagi.ru.monica.repository

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasswordEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class MdbxPasswordContentFieldsTest {
    @Test fun wifiMetadataSurvivesColdReadsLegacyPayloadsAndUnknownExtensions() {
        val raw = """{"ssid":"network","security":"WEP","proxy":{"kind":"future"},"unknown":[1,true]}"""
        val entry = original.copy(wifiMetadata = raw)
        val encoded = MdbxPasswordContentFields.writeTo(JSONObject(), entry) { it }
        assertEquals(raw, encoded.getString("wifi_metadata"))
        assertEquals(raw, MdbxPasswordContentFields.readInto(encoded, original).wifiMetadata)
        assertEquals(raw, MdbxPasswordContentFields.readInto(JSONObject(), original, entry).wifiMetadata)
        assertEquals(raw, MdbxPasswordContentFields.readInto(JSONObject().put("wifiMetadata", raw), original).wifiMetadata)
        assertEquals(JSONObject(raw).toString(), MdbxPasswordContentFields.readInto(JSONObject().put("wifi_metadata", JSONObject(raw)), original).wifiMetadata)
        assertEquals("", MdbxPasswordContentFields.readInto(JSONObject().put("wifi_metadata", ""), entry).wifiMetadata)
        assertThrows(IllegalArgumentException::class.java) {
            MdbxPasswordContentFields.readInto(JSONObject().put("wifi_metadata", 42), entry)
        }
    }
    private val original = PasswordEntry(title = "content", username = "", password = "", website = "",
        notes = "line one\n  line two  \n", email = "a@example.invalid", phone = "+1 202 555 0140",
        addressLine = "12 Example Street", city = "City", state = "State", zipCode = "10000", country = "US",
        creditCardNumber = "4242424242424242", creditCardHolder = "ALICE", creditCardExpiry = "09/30", creditCardCVV = "123")

    @Test fun nativeContentSurvivesReopeningWithoutAnExistingProjection() {
        val payload = MdbxPasswordContentFields.writeTo(JSONObject(), original) { it }
        val blank = original.copy(email = "", phone = "", addressLine = "", city = "", state = "", zipCode = "", country = "",
            creditCardNumber = "", creditCardHolder = "", creditCardExpiry = "", creditCardCVV = "")
        assertEquals(original, MdbxPasswordContentFields.readInto(JSONObject(payload.toString()), blank))
    }

    @Test fun olderWritersPreserveUnknownFieldsButExplicitClearsPropagate() {
        assertEquals(original, MdbxPasswordContentFields.readInto(JSONObject(), original))
        val cleared = MdbxPasswordContentFields.readInto(JSONObject().put("address_line", "")
            .put("credit_card_number_plain", JSONObject.NULL), original)
        assertEquals(original.copy(addressLine = "", creditCardNumber = ""), cleared)
        val newProjection = original.copy(title = "updated", email = "")
        assertEquals(original.copy(title = "updated"), MdbxPasswordContentFields.readInto(JSONObject(), newProjection, original))
    }

    @Test fun deviceLocalCardCiphertextIsMadePortableBeforeTheNativeVaultEncryptsIt() {
        val encrypted = original.copy(creditCardNumber = "local-number", creditCardCVV = "local-cvv")
        val payload = MdbxPasswordContentFields.writeTo(JSONObject(), encrypted) {
            when (it) { "local-number" -> original.creditCardNumber; "local-cvv" -> original.creditCardCVV; else -> error("Unexpected secret") }
        }
        assertEquals(original, MdbxPasswordContentFields.readInto(payload, encrypted))
    }
}
