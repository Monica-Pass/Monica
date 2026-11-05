package takagi.ru.monica.bitwarden.service

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.ui.BitwardenAccountNavigation

class BitwardenAccountInputPolicyTest {
    private val server = "https://vault.bitwarden.com"
    private val email = "a@example.test"
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Unsafe account input accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun normalizesEmailWithoutChangingTheToken() {
        assertEquals(email, BitwardenAccountInputPolicy.email("  A@EXAMPLE.TEST  "))
        assertEquals("opaque+/=token", BitwardenAccountInputPolicy.verificationToken("opaque+/=token", server, email))
    }

    @Test fun rejectsMissingLocalPartAndMultipleOrWhitespaceEmails() {
        listOf("@example.test", "a@b@example.test", "a b@example.test", "a@example.", "a@.test").forEach {
            rejects { BitwardenAccountInputPolicy.email(it) }
        }
    }

    @Test fun acceptsOfficialFragmentVerificationLinkBoundToTheAccount() {
        val link = "$server/#/register/finish?email=a%40example.test&emailVerificationToken=opaque%2B%2F%3D"
        assertEquals("opaque+/=", BitwardenAccountInputPolicy.verificationToken(link, server, email))
    }

    @Test fun pastedLinkCannotSelectAnotherServerOrAccount() {
        rejects { BitwardenAccountInputPolicy.verificationToken("https://vault.bitwarden.eu/?token=t", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("$server/?email=b%40example.test&token=t", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("http://vault.bitwarden.com/?token=t", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("https://user@vault.bitwarden.com/?token=t", server, email) }
    }

    @Test fun rejectsDuplicatedVerificationParametersAndOversizedTokens() {
        rejects { BitwardenAccountInputPolicy.verificationToken("$server/?token=one&token=two", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("$server/?emailVerificationToken=one&token=two", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("$server/?email=a%40example.test&email=b%40example.test&token=t", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("$server/?email=a%40example.test&email=a%40example.test&token=t", server, email) }
        rejects { BitwardenAccountInputPolicy.verificationToken("x".repeat(64 * 1024 + 1), server, email) }
    }

    @Test fun selfHostedVerificationIsBoundToTheExactPort() {
        val custom = "https://example.test:8443/base"
        assertEquals("t", BitwardenAccountInputPolicy.verificationToken("$custom/#/register?token=t", custom, email))
        rejects { BitwardenAccountInputPolicy.verificationToken("https://example.test/?token=t", custom, email) }
    }

    @Test fun verificationLinkIsBoundToWebPathBeforeNormalization() {
        val web = "https://example.test:8443/Team/Web/"
        assertEquals("t", BitwardenAccountInputPolicy.verificationToken("${web}#/register?token=t", web, email))
        listOf("/Team/WebSibling/", "/team/Web/", "/other/", "/Team/Web/../other/",
            "/Team/Web/%2e%2e/other/", "/Team/Web%2Fother/").forEach { path ->
            rejects { BitwardenAccountInputPolicy.verificationToken("https://example.test:8443${path}?token=t", web, email) }
        }
    }

    @Test fun webSettingsKeepTheAccountServerWithoutTransmittingCredentials() {
        assertEquals("$server/#/settings", BitwardenAccountNavigation.webSettingsUrl(server + "/"))
        assertEquals("https://example.test/base/#/settings", BitwardenAccountNavigation.webSettingsUrl("https://example.test/base"))
        rejects { BitwardenAccountNavigation.webSettingsUrl("https://user:password@example.test") }
        rejects { BitwardenAccountNavigation.webSettingsUrl("https://example.test?token=secret") }
        rejects { BitwardenAccountNavigation.webSettingsUrl("javascript:alert(1)") }
    }
}
