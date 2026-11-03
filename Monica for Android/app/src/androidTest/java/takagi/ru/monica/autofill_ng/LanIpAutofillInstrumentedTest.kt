package takagi.ru.monica.autofill_ng

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.PasswordUrlRules

class LanIpAutofillInstrumentedTest {
    private fun row(id: Long, url: String) = PasswordEntry(id = id, title = "Synthetic $id", website = url, username = "", password = "")
    private fun noise() = (1L..300L).map { row(it, "https://unrelated-$it.example.test") }

    @Test fun ipv4NativeIndexAndLooseFallbackKeepDifferentDevicesSeparate() {
        val entries = noise() + listOf(row(501, "http://192.168.1.1:8080/login"), row(502, "http://10.0.1.1"),
            row(503, "http://192.168.1.10"), row(504, "http://192.168.1.1.evil.example"))
        val native = BitwardenLikeAutofillMatcherNg()
        val baseline = BitwardenLikeAutofillMatcherNg(useNativeIndex = false, cacheMetadata = false)
        try {
            for (strict in listOf(true, false)) {
                val config = BitwardenLikeAutofillMatcherNg.Config(strictOnly = strict, allowBaseDomainMatch = true, allowSubdomainMatch = true)
                assertEquals(listOf(501L), native.match(entries, "com.android.chrome", "192.168.1.1", config = config).map { it.id })
                assertEquals(baseline.match(entries, "com.android.chrome", "192.168.1.1", config = config),
                    native.match(entries, "com.android.chrome", "192.168.1.1", config = config))
            }
            assertTrue("Exercise the actual packaged JNI index", native.nativeQueryCount > 0)
        } finally { native.clear(); baseline.clear() }
    }

    @Test fun ipv6NativeIndexCanonicalizesAndRefreshesChangedAddresses() {
        val native = BitwardenLikeAutofillMatcherNg()
        try {
            val entries = noise() + listOf(row(511, "http://[fd00:0:0:0:0:0:0:1]:8080/login"), row(512, "http://[fd00::2]:8080/login"))
            for (target in listOf("fd00::1", "[fd00::1]", "http://[fd00::1]:8080/login")) {
                assertEquals(target, listOf(511L), native.match(entries, "com.android.chrome", target).map { it.id })
            }
            val changed = entries.map { if (it.id == 511L) it.copy(website = "http://[fd00::3]:8080/login") else it }
            assertTrue(native.match(changed, "com.android.chrome", "fd00::1").isEmpty())
            assertEquals(listOf(511L), native.match(changed, "com.android.chrome", "fd00::3").map { it.id })
            assertTrue("Exercise the actual packaged JNI index", native.nativeQueryCount > 0)
        } finally { native.clear() }
    }

    @Test fun realAndroidAddressCacheAndExplicitRulesSupportIpHosts() {
        takagi.ru.monica.service.BrowserAutofillContextStore.update("com.android.chrome", "http://[fd00:0:0:0:0:0:0:1]:8080/login")
        assertEquals("fd00::1", takagi.ru.monica.service.BrowserAutofillContextStore.getRecentDomain("com.android.chrome"))
        takagi.ru.monica.service.BrowserAutofillContextStore.update("com.android.chrome", "http://999.168.1.1")
        assertNull(takagi.ru.monica.service.BrowserAutofillContextStore.getRecentDomain("com.android.chrome"))
        val hostRule = PasswordUrlRules.Rule("http://[fd00::1]:8080/login", PasswordUrlRules.Mode.HOST)
        assertTrue(PasswordUrlRuleMatcher.score(hostRule, "fd00:0:0:0:0:0:0:1") > 0)
        assertEquals(0, PasswordUrlRuleMatcher.score(hostRule, "fd00::2"))
        assertEquals(0, PasswordUrlRuleMatcher.score(PasswordUrlRules.Rule("192.168.1.1", PasswordUrlRules.Mode.BASE_DOMAIN), "10.0.1.1"))
        assertNull(AutofillHostPolicy.host("http://999.168.1.1"))
    }
}
