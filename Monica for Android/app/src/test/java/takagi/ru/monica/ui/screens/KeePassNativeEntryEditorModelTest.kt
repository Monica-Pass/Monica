package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.data.model.TotpData

class KeePassNativeEntryEditorModelTest {
    @Test
    fun tagsUseKeePassSeparatorsAndRetainColons() {
        assertEquals(listOf("work", "home", "project:internal"),
            parseNativeEntryTags(" work, home;project:internal\r\nwork\n"))
    }


    @Test
    fun `new draft starts with password style fields and encrypted password`() {
        val draft = newNativeEntryEditorDraft()

        assertEquals(
            listOf("Title", "UserName", "Password", "URL", "Notes"),
            draft.fields.map { it.name },
        )
        assertEquals(NativeEntryStandardSlot.TITLE, draft.standard(NativeEntryStandardSlot.TITLE)?.slot)
        assertTrue(draft.standard(NativeEntryStandardSlot.PASSWORD)?.protected == true)
        assertTrue(draft.customFields.isEmpty())
    }

    @Test
    fun `existing aliases use password form slots without renaming fields`() {
        val draft = buildNativeEntryEditorDraft(
            listOf(
                KeePassFieldChange("Name", "Example"),
                KeePassFieldChange("Login", "alice"),
                KeePassFieldChange("pwd", "secret", protected = true),
                KeePassFieldChange("URI", "https://example.com"),
                KeePassFieldChange("Comment", "memo"),
                KeePassFieldChange("Recovery code", "1234", protected = true),
            ),
        )

        assertEquals("Name", draft.standard(NativeEntryStandardSlot.TITLE)?.name)
        assertEquals("Login", draft.standard(NativeEntryStandardSlot.USERNAME)?.name)
        assertEquals("pwd", draft.standard(NativeEntryStandardSlot.PASSWORD)?.name)
        assertEquals("URI", draft.standard(NativeEntryStandardSlot.URL)?.name)
        assertEquals("Comment", draft.standard(NativeEntryStandardSlot.NOTES)?.name)
        assertEquals(listOf("Recovery code"), draft.customFields.map { it.name })
        assertTrue(draft.customFields.single().protected)
    }

    @Test
    fun `second field for the same semantic slot remains an editable custom field`() {
        val draft = buildNativeEntryEditorDraft(
            listOf(
                KeePassFieldChange("Title", "Example"),
                KeePassFieldChange("UserName", "alice"),
                KeePassFieldChange("Login", "secondary"),
            ),
        )

        assertEquals("UserName", draft.standard(NativeEntryStandardSlot.USERNAME)?.name)
        assertEquals(listOf("Login"), draft.customFields.map { it.name })
    }

    @Test
    fun `save plan preserves field order names values and protection`() {
        val draft = buildNativeEntryEditorDraft(
            listOf(
                KeePassFieldChange("Plugin A", "opaque"),
                KeePassFieldChange("Title", "Before"),
                KeePassFieldChange("Password", "old", protected = true),
                KeePassFieldChange("Plugin Secret", "hidden", protected = true),
            ),
        )
        val updated = draft.copy(
            fields = draft.fields.map { field ->
                if (field.slot == NativeEntryStandardSlot.TITLE) field.copy(value = "After") else field
            },
        )

        assertEquals(
            listOf(
                KeePassFieldChange("Plugin A", "opaque"),
                KeePassFieldChange("Title", "After"),
                KeePassFieldChange("Password", "old", protected = true),
                KeePassFieldChange("Plugin Secret", "hidden", protected = true),
            ),
            updated.toFieldChanges(),
        )
    }

    @Test
    fun `validation permits unnamed entries and requires unique nonblank field names`() {
        val emptyDraft = newNativeEntryEditorDraft()
        val titleId = emptyDraft.standard(NativeEntryStandardSlot.TITLE)!!.id
        val draft = emptyDraft.copy(
            fields = emptyDraft.fields.map { if (it.id == titleId) it.copy(value = "Example") else it },
        )

        assertNull(
            validateNativeEntryEditorDraft(
                draft.copy(fields = draft.fields.map { if (it.id == titleId) it.copy(value = "") else it }),
            ),
        )
        assertEquals(
            NativeEntryDraftError.FIELD_NAME_REQUIRED,
            validateNativeEntryEditorDraft(
                draft.copy(fields = draft.fields + newNativeCustomField(draft.fields, name = "")),
            ),
        )
        assertEquals(
            NativeEntryDraftError.DUPLICATE_FIELD_NAME,
            validateNativeEntryEditorDraft(
                draft.copy(fields = draft.fields + newNativeCustomField(draft.fields, name = "Title")),
            ),
        )
        assertNull(validateNativeEntryEditorDraft(draft))
    }

    @Test
    fun `case and whitespace distinct custom names survive an unrelated edit`() {
        val source = listOf(
            KeePassFieldChange("title", "custom lowercase"),
            KeePassFieldChange("Title", "Canonical"),
            KeePassFieldChange(" Title ", "surrounded"),
            KeePassFieldChange("Empty", ""),
        )
        val draft = buildNativeEntryEditorDraft(source)
        assertNull(validateNativeEntryEditorDraft(draft))
        assertEquals("Canonical", draft.standard(NativeEntryStandardSlot.TITLE)?.value)
        assertEquals(source, draft.toFieldChanges())
        assertEquals(listOf("title", " Title ", "Empty"), draft.customFields.map { it.name })
    }

    @Test
    fun `new custom field is plain and ordered after existing fields`() {
        val draft = newNativeEntryEditorDraft()
        val custom = newNativeCustomField(draft.fields, name = "Account ID")

        assertEquals("Account ID", custom.name)
        assertFalse(custom.protected)
        assertEquals(draft.fields.maxOf { it.order } + 1, custom.order)
        assertNull(custom.slot)
    }

    @Test
    fun `totp fields are managed by the authenticator card and remain keepass compatible`() {
        val source = listOf(
            KeePassFieldChange("Title", "Mail"),
            KeePassFieldChange("otp", "otpauth://totp/old?secret=AAAA", protected = true),
            KeePassFieldChange("Plugin", "opaque"),
        )

        val merged = mergeNativeTotpFields(
            fields = source,
            data = TotpData(
                secret = "JBSWY3DPEHPK3PXP",
                issuer = "Example",
                accountName = "alice@example.com",
                period = 30,
                digits = 6,
                algorithm = "SHA1",
            ),
            title = "Mail",
        )

        assertEquals(1, merged.count { it.name.equals("otp", ignoreCase = true) })
        assertTrue(merged.any { it.name == "TOTP Seed" && it.protected })
        assertTrue(merged.any { it.name == "Plugin" && it.value == "opaque" })
        assertFalse(buildNativeEntryEditorDraft(merged).customFields.any {
            isNativeTotpFieldName(it.name)
        })
    }

    @Test
    fun `advancing HOTP preserves raw URI encoding secrets and custom fields`() {
        val uri = "otpauth://hotp/Example%3Aalice%2Btag?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&counter=1&issuer=Example&custom=a%2Bb"
        val source = listOf(KeePassFieldChange("otp", uri, true),
            KeePassFieldChange("HmacOtp-Secret-Hex", "3132333435363738393031323334353637383930", true),
            KeePassFieldChange("HmacOtp-Counter", "1"), KeePassFieldChange("Plugin", "{S:Custom}"))
        val next = advanceNativeHotpFields(source, parseNativeTotpFields(source)!!)
        assertEquals(uri.replace("counter=1", "counter=2"), next.first { it.name == "otp" }.value)
        assertEquals(source[1], next.first { it.name == "HmacOtp-Secret-Hex" })
        assertEquals(source[3], next.first { it.name == "Plugin" })
        assertEquals(2L, parseNativeTotpFields(next)!!.counter)
    }

    @Test
    fun `advancing native HOTP keeps a referenced secret and rejects overflow`() {
        val fields = listOf(KeePassFieldChange("HmacOtp-Secret-Base32", "{S:SharedSecret}", true),
            KeePassFieldChange("HmacOtp-Counter", "0"))
        val data = TotpData(secret = "JBSWY3DPEHPK3PXP", otpType = takagi.ru.monica.data.model.OtpType.HOTP)
        val next = advanceNativeHotpFields(fields, data)
        assertEquals(fields.first(), next.first())
        assertEquals("1", next.first { it.name == "HmacOtp-Counter" }.value)
        assertTrue(runCatching { advanceNativeHotpFields(fields, data.copy(counter = Long.MAX_VALUE)) }.isFailure)
    }

    @Test
    fun `invalid OTP replacement is rejected instead of removing the existing secret`() {
        val fields = listOf(KeePassFieldChange("Title", "Keep OTP"),
            KeePassFieldChange("otp", "otpauth://totp/Keep?secret=JBSWY3DPEHPK3PXP", true))
        assertTrue(runCatching {
            mergeNativeTotpFields(fields, TotpData(secret = "invalid-secret!"), "Keep OTP")
        }.isFailure)
    }

    @Test
    fun `existing keepass totp fields populate the authenticator editor`() {
        val parsed = parseNativeTotpFields(
            listOf(
                KeePassFieldChange("Title", "GitHub"),
                KeePassFieldChange("UserName", "alice"),
                KeePassFieldChange("TOTP Seed", "jbsw y3dp ehpk3pxp", protected = true),
                KeePassFieldChange("TOTP Period", "45"),
                KeePassFieldChange("TOTP Digits", "8"),
                KeePassFieldChange("TOTP Algorithm", "sha256"),
            ),
        )

        requireNotNull(parsed)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secret)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("alice", parsed.accountName)
        assertEquals(45, parsed.period)
        assertEquals(8, parsed.digits)
        assertEquals("SHA256", parsed.algorithm)
    }
}
