package takagi.ru.monica.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.utils.KeePassRawStringField
import takagi.ru.monica.utils.extractKeePassCustomFieldsForPasswordEntry

class KeePassCustomFieldExtractionTest {

    @Test
    fun keepsNonStandardKeePassStringFieldsForDetailDisplay() {
        val fields = extractKeePassCustomFieldsForPasswordEntry(
            listOf(
                KeePassRawStringField("Title", "GitHub", isProtected = false),
                KeePassRawStringField("UserName", "monica", isProtected = false),
                KeePassRawStringField("Password", "secret", isProtected = true),
                KeePassRawStringField("Security question", "First pet?", isProtected = false),
                KeePassRawStringField("Recovery PIN", "123456", isProtected = true),
                KeePassRawStringField("MonicaLocalId", "42", isProtected = false),
                KeePassRawStringField("_etm_template", "1", isProtected = false),
                KeePassRawStringField("Empty custom", "", isProtected = false)
            )
        )

        assertEquals(3, fields.size)
        assertEquals("Security question", fields[0].title)
        assertEquals("First pet?", fields[0].value)
        assertFalse(fields[0].isProtected)
        assertEquals(0, fields[0].sortOrder)
        assertEquals("Recovery PIN", fields[1].title)
        assertEquals("123456", fields[1].value)
        assertTrue(fields[1].isProtected)
        assertEquals(1, fields[1].sortOrder)
        assertEquals("Empty custom", fields[2].title)
        assertEquals("", fields[2].value)
    }

    @Test
    fun monicaPasswordCompatibilityFieldsAreNotDuplicatedAsCustomFields() {
        val fields = extractKeePassCustomFieldsForPasswordEntry(
            listOf(
                KeePassRawStringField("Email", "user@example.com", isProtected = false),
                KeePassRawStringField("Phone", "+10000000000", isProtected = false),
                KeePassRawStringField("Address", "One Infinite Loop", isProtected = false),
                KeePassRawStringField("Card Number", "4111111111111111", isProtected = true),
                KeePassRawStringField("Card CVV", "123", isProtected = true),
                KeePassRawStringField("SSO Provider", "Google", isProtected = false),
                KeePassRawStringField("App Package Name", "com.example.app", isProtected = false),
                KeePassRawStringField("Security question", "First pet?", isProtected = false)
            )
        )

        assertEquals(1, fields.size)
        assertEquals("Security question", fields.single().title)
    }
}
