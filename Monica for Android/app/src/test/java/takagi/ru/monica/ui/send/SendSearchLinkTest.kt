package takagi.ru.monica.ui.send

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Link recognition is local parsing; no account or network client is needed. */
class SendSearchLinkTest {
    private val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 11 })

    @Test fun ordinaryQueriesAndNonSendUrlsStayOrdinarySearches() {
        listOf("", " ", "Synthetic meeting notes", "https://example.test/", "send.bitwarden.com",
            "https://example.test/#/send/", "https://example.test/#/send/$id/$key/extra")
            .forEach { assertNull(sendLinkHost(it)) }
    }

    @Test fun officialUsAndEuLinksExposeOnlyTheirHost() {
        listOf("send.bitwarden.com", "send.bitwarden.eu").forEach { host ->
            assertEquals(host, sendLinkHost("https://$host/#$id/$key"))
            assertEquals(host, sendLinkHost("https://$host/#/send/$id/$key"))
        }
        assertEquals("vault.bitwarden.eu", sendLinkHost("https://vault.bitwarden.eu/#/send/$id/$key"))
    }

    @Test fun selfHostedBasePathAndOuterWhitespaceAreAcceptedWithoutAnAccount() {
        assertEquals("vault.example.test", sendLinkHost(" \nhttps://vault.example.test/team/vault/#/send/$id/$key\t "))
        assertEquals("vault.example.test", sendLinkHost("https://vault.example.test:8443/team/vault/#/send/$id/$key"))
    }

    @Test fun plainHttpAndEmbeddedCredentialsNeverOfferAnOpenAction() {
        listOf("http://vault.example.test/#/send/$id/$key",
            "https://user:password@vault.example.test/#/send/$id/$key",
            "https://send.bitwarden.com@evil.example.test/#/send/$id/$key",
            "https://user%40example.test@vault.example.test/#/send/$id/$key")
            .forEach { assertNull(sendLinkHost(it)) }
    }

    @Test fun malformedOrNonCanonicalKeyMaterialIsRejected() {
        listOf("https://vault.example.test/#/send/short/$key",
            "https://vault.example.test/#/send/$id/short",
            "https://vault.example.test/#/send/$id/AAAAAAAAAAAAAAAAAAAAAB",
            "https://vault.example.test/#/send/$id/$key#ignored",
            "https://vault.example.test/#/send/$id/${key.dropLast(1)}=")
            .forEach { assertNull(sendLinkHost(it)) }
    }

    @Test fun unsafeBasePathsAndOfficialHostOverridesAreRejected() {
        listOf("https://vault.example.test/team/../vault/#/send/$id/$key",
            "https://vault.example.test/team/%2e%2e/vault/#/send/$id/$key",
            "https://vault.example.test/team%2fvault/#/send/$id/$key",
            "https://vault.example.test/?token=synthetic#/send/$id/$key",
            "https://send.bitwarden.eu:8443/#$id/$key",
            "https://send.bitwarden.com/extra/#$id/$key",
            "https://vault.example.test/space here/#/send/$id/$key")
            .forEach { assertNull(sendLinkHost(it)) }
    }

    @Test fun oversizedInputDoesNotProduceAReceiverAction() {
        assertNull(sendLinkHost("https://vault.example.test/" + "a".repeat(8192) + "/#/send/$id/$key"))
    }
}
