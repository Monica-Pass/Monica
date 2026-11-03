package takagi.ru.monica.autofill_ng

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.data.model.PasswordUrlRules.Mode
import takagi.ru.monica.data.model.PasswordUrlRules.Rule

class PasswordUrlRuleMatcherTest {
    private fun row(id: Long, url: String) = PasswordEntry(id = id, title = "example", website = url, username = "demo", password = "")
    private fun matches(mode: Mode, url: String, target: String) = PasswordUrlRuleMatcher.score(Rule(url, mode), target) > 0

    @Test fun hostOverrideSeparatesEnvironmentsWhileBaseDomainSharesCredentials() {
        val entries = listOf(row(1, "https://prod.example.com"), row(2, "https://accounts.example.com"), row(3, "https://test.example.com"))
        val matcher = BitwardenLikeAutofillMatcherNg(useNativeIndex = false)
        val rules = mapOf(1L to listOf(Rule(entries[0].website, Mode.HOST)), 2L to listOf(Rule(entries[1].website, Mode.BASE_DOMAIN)))
        assertEquals(setOf(2L, 3L), matcher.match(entries, "browser", "test.example.com", urlRules = rules).map { it.id }.toSet())
        assertEquals(setOf(1L, 2L, 3L), matcher.match(entries, "browser", "test.example.com").map { it.id }.toSet())
    }

    @Test fun neverCannotBeBypassedByTitleOrLooseMatchingAndRulesRefreshWithoutWebsiteEdits() {
        val matcher = BitwardenLikeAutofillMatcherNg(useNativeIndex = false)
        val entries = listOf(row(1, "https://example.com"))
        val config = BitwardenLikeAutofillMatcherNg.Config(strictOnly = false)
        assertTrue(matcher.match(entries, "browser", "example.com", config = config,
            urlRules = mapOf(1L to listOf(Rule(entries[0].website, Mode.NEVER)))).isEmpty())
        assertEquals(1, matcher.match(entries, "browser", "example.com", config = config,
            urlRules = mapOf(1L to listOf(Rule(entries[0].website, Mode.HOST)))).size)
        assertTrue(matcher.match(entries, "browser", "example.com", config = config, urlRules = mapOf(1L to emptyList())).isEmpty())
        assertTrue(matcher.match(entries, "browser", "example.com", config = config,
            urlRules = mapOf(1L to listOf(Rule("example.com", Mode.NEVER), Rule("unrelated.invalid", Mode.DEFAULT)))).isEmpty())
    }

    @Test fun explicitRuleOverridesGlobalNeverAndOneMatchingUrlIsEnough() {
        val entries = listOf(row(1, "https://example.com"), row(2, "https://example.com"))
        val config = BitwardenLikeAutofillMatcherNg.Config(defaultUriMatchDisabled = true)
        val rules = mapOf(1L to listOf(Rule("example.com", Mode.NEVER), Rule("login.example.com", Mode.HOST)))
        assertEquals(listOf(1L), BitwardenLikeAutofillMatcherNg(useNativeIndex = false)
            .match(entries, "browser", "login.example.com", config = config, urlRules = rules).map { it.id })
    }

    @Test fun registeredDomainsUsePublicSuffixBoundariesAndIpsRemainExact() {
        assertTrue(matches(Mode.BASE_DOMAIN, "https://a.example.co.uk", "b.example.co.uk"))
        assertFalse(matches(Mode.BASE_DOMAIN, "https://a.example.co.uk", "attacker.co.uk"))
        assertFalse(matches(Mode.BASE_DOMAIN, "https://alice.github.io", "bob.github.io"))
        assertFalse(matches(Mode.BASE_DOMAIN, "192.168.1.1", "192.168.1.2"))
        assertTrue(matches(Mode.BASE_DOMAIN, "localhost", "localhost"))
    }

    @Test fun ipv6HostRulesCanonicalizeWithoutInventingAPathOrPort() {
        assertTrue(matches(Mode.HOST, "http://[fd00:0:0:0:0:0:0:1]:8080/login", "fd00::1"))
        assertTrue(matches(Mode.BASE_DOMAIN, "[fd00::1]", "fd00:0:0:0:0:0:0:1"))
        assertFalse(matches(Mode.BASE_DOMAIN, "fd00::1", "fd00::2"))
        assertFalse(matches(Mode.EXACT, "http://[fd00::1]:8080/login", "fd00::1"))
        assertFalse(matches(Mode.STARTS_WITH, "http://[fd00::1]:8080/login", "fd00::1"))
        assertTrue(matches(Mode.EXACT, "http://[fd00::1]:8080/login", "http://[fd00:0:0:0:0:0:0:1]:8080/login"))
    }

    @Test fun hostIsCaseInsensitiveButDoesNotCollapseWwwOrSiblingSubdomains() {
        assertTrue(matches(Mode.HOST, "https://LOGIN.example.com/path", "login.example.com"))
        assertFalse(matches(Mode.HOST, "https://example.com", "www.example.com"))
        assertFalse(matches(Mode.HOST, "https://a.example.com", "b.example.com"))
        assertFalse(matches(Mode.HOST, "example.com", "example.com.evil.invalid"))
    }

    @Test fun exactAndPrefixNeedRealUrlContextAndRetainPathAndQueryCase() {
        assertTrue(matches(Mode.EXACT, "https://example.com/Login?q=A", "https://example.com/Login?q=A"))
        assertFalse(matches(Mode.EXACT, "https://example.com/Login?q=A", "https://example.com/login?q=A"))
        assertFalse(matches(Mode.EXACT, "https://example.com/Login", "example.com"))
        assertFalse(matches(Mode.STARTS_WITH, "https://example.com/Login", "example.com"))
        assertTrue(matches(Mode.STARTS_WITH, "https://example.com/Login", "https://example.com/Login/next"))
        assertFalse(matches(Mode.STARTS_WITH, "https://example.com", "https://example.com.evil.invalid"))
        assertFalse(matches(Mode.STARTS_WITH, "example.com", "sub.example.com"))
        assertTrue(matches(Mode.STARTS_WITH, "example.com", "example.com"))
    }

    @Test fun regexIsLinearAndInvalidOrUnsupportedExpressionsDoNotMatch() {
        assertTrue(matches(Mode.REGEX, "^login\\.example\\.com$", "login.example.com"))
        assertFalse(matches(Mode.REGEX, "^https://example.com/Login$", "example.com"))
        assertTrue(matches(Mode.REGEX, "^https://example.com/Login$", "https://example.com/Login"))
        assertFalse(matches(Mode.REGEX, "[", "example.com"))
        assertFalse(PasswordUrlRuleMatcher.validRegex("(?<=foo)bar"))
        assertFalse(matches(Mode.REGEX, "(a+)+$", "a".repeat(5000) + ".example.com"))
    }

    @Test fun metadataRoundTripPreservesRegexCommasAndCaseAndDefaultRemoval() {
        val ordinary = CustomFieldDraft(title = "My field", value = "Retain me")
        val rules = listOf(Rule("^https://example.com/a{1,3}\\?q=(One|Two)$", Mode.REGEX), Rule("https://example.com/Case", Mode.HOST))
        val fields = PasswordUrlRules.put(listOf(ordinary), rules)
        assertEquals(rules, PasswordUrlRules.read(fields))
        assertEquals(listOf(ordinary), PasswordUrlRules.put(fields, rules.map { it.copy(mode = Mode.DEFAULT) }))
        assertNull(PasswordUrlRules.decode("{\"version\":2,\"urls\":[]}"))
        assertThrows(IllegalArgumentException::class.java) {
            PasswordUrlRules.put(listOf(CustomFieldDraft(title = PasswordUrlRules.FIELD, value = "future")), rules)
        }
    }
}
