package takagi.ru.monica.repository

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasswordEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MdbxPasswordContentFieldsTest {
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
