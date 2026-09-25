package takagi.ru.monica.data

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.BitwardenLikeAutofillMatcherNg
import takagi.ru.monica.data.model.ApiKeyDraft
import takagi.ru.monica.data.model.ApiKeyEntryFields
import takagi.ru.monica.ui.password.getPasswordInfoKey

class ApiKeyEntryFieldsTest {
    private val draft = ApiKeyDraft("自建 AI · Claude", "https://console.example.org", "sk-fixture-secret",
        "https://gateway.example.org/tenant/v1?region=cn", "工作环境\n不要用于生产")

    @Test fun preservesSeparateWebsiteAndEndpointAcrossProjection() {
        val fields = draft.customFields(emptyList()).associate { it.title to it.value }
        assertTrue(ApiKeyEntryFields.isApiKey(fields))
        assertEquals(draft, ApiKeyDraft.from(draft.toEntry(), fields))
        assertFalse(fields.values.contains(draft.key))
    }

    @Test fun editingAndClearingEndpointPreservesUnknownProtectedFieldsAndEntryIdentity() {
        val unrelated = CustomFieldDraft(title = "Future metadata", value = "future", isProtected = true)
        val old = draft.toEntry().copy(id = 42, keepassEntryUuid = "uuid", isFavorite = true)
        val changed = draft.copy(provider = "新名称", apiUrl = "")
        val fields = changed.customFields(listOf(unrelated) + ApiKeyEntryFields.encode(draft.apiUrl))
        assertEquals(listOf(unrelated, CustomFieldDraft(title = ApiKeyEntryFields.MARKER, value = ApiKeyEntryFields.TYPE)), fields)
        assertEquals("uuid", changed.toEntry(old).keepassEntryUuid)
        assertEquals(42L, changed.toEntry(old).id)
        assertTrue(changed.toEntry(old).isFavorite)
        assertEquals(draft.key, changed.toEntry(old).password)
    }

    @Test fun allowsCustomProvidersAndOptionalUrls() {
        assertTrue(draft.isValid)
        assertTrue(draft.copy(website = "", apiUrl = "").isValid)
        assertTrue(ApiKeyEntryFields.isValidOptionalUrl("http://192.168.1.10:11434/v1"))
        assertTrue(ApiKeyEntryFields.isValidOptionalUrl("https://[::1]:8080/v1"))
    }

    @Test fun rejectsMissingRequiredFieldsAndMalformedOrExecutableUrls() {
        assertFalse(draft.copy(provider = "  ").isValid)
        assertFalse(draft.copy(key = "\n").isValid)
        listOf("api.example.org", "javascript:alert(1)", "https://", "file:///tmp/key",
            "https://user:password@example.org", "https://example.org:99999", "https://exa\nmple.org")
            .forEach { url -> assertFalse(url, ApiKeyEntryFields.isValidOptionalUrl(url)) }
    }

    @Test fun secretsDoNotAppearInDraftDiagnostics() {
        assertFalse(draft.toString().contains(draft.key))
        assertFalse(draft.toString().contains(draft.notes))
    }

    @Test fun apiKeysNeverMatchLoginAutofillEvenWithExactAppAndDomain() {
        val login = PasswordEntry(id = 1, title = "AI", website = draft.website, username = "alice",
            password = "login-password", appPackageName = "com.example.ai")
        val api = draft.toEntry().copy(id = 2, appPackageName = "com.example.ai")
        val gpg = login.copy(id = 3, loginType = "GPG_KEY")
        val matcher = BitwardenLikeAutofillMatcherNg()
        listOf(true, false).forEach { strict ->
            val matches = matcher.match(listOf(api, login, gpg), "com.example.ai", "console.example.org",
                config = BitwardenLikeAutofillMatcherNg.Config(strictOnly = strict))
            assertEquals(listOf(1L), matches.map { it.id })
        }
    }

    @Test fun providerKeysKeepIndependentDetailLinksAndDoNotMergeIntoPasswords() {
        val api = draft.toEntry().copy(id = 1)
        assertNotEquals(getPasswordInfoKey(api), getPasswordInfoKey(api.copy(id = 2)))
        assertNotEquals(getPasswordInfoKey(api), getPasswordInfoKey(api.copy(loginType = "PASSWORD")))
    }
}
