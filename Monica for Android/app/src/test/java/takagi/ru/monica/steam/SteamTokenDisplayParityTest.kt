package takagi.ru.monica.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.steam.core.SteamTotp
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.importer.SteamMaFileParser
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.util.TotpDataResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class SteamTokenDisplayParityTest {
    // Synthetic20-byte key; independent Python HMAC/Steam alphabet vector at1700000000.
    private val shared = "/wECAwQFBgcICQoLDA0ODxAREhM="
    private val base32 = "74AQEAYEAUDAOCAJBIFQYDIOB4IBCEQT"
    private val json = """{"shared_secret":"$shared","identity_secret":"YWJjZGVmZ2hpamtsbW5vcHFyc3Q=",
        "secret_1":"MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=","account_name":"synthetic",
        "uri":"otpauth://totp/Steam:synthetic?secret=$base32&issuer=Steam",
        "server_time":1600000000,"fully_enrolled":false,"Session":null}"""

    @Test fun actualSteamCardAdapterMatchesDirectCodeAfterSessionlessMaFileImport() {
        val payload = SteamMaFileParser().parse(json, fileName = "synthetic.maFile", allowMissingSteamId = true)
        assertEquals(shared, payload.sharedSecret)
        val account = SteamAccount(1, payload.steamId, payload.accountName, payload.displayName, payload.deviceId,
            payload.sharedSecret, payload.identitySecret, payload.revocationCode, payload.tokenGid, payload.accessToken,
            payload.refreshToken, payload.steamLoginSecure, payload.rawJson, false, 0, 1, 1)
        assertFalse(account.canUseConfirmations)
        assertFalse(account.canApproveLogins)
        val adapter = Class.forName("takagi.ru.monica.steam.ui.SteamScreenKt")
            .getDeclaredMethod("toSteamTotpUiData", SteamAccount::class.java).apply { isAccessible = true }
        val cardData = adapter.invoke(null, account) as TotpData
        assertEquals("PMRRM", SteamTotp.generateAuthCode(account.sharedSecret, 1_700_000_000L))
        assertEquals("Card display and direct copy must use the same key", "PMRRM",
            TotpGenerator.generateOtp(cardData, currentSeconds = 1_700_000_000L))
    }

    @Test fun multipleLeadingAndTrailingBase64SlashesRemainKeyBytes() {
        val vectors = listOf("//8CAwQFBgcICQoLDA0ODxAREhM=" to "CKKRC",
            "AQIDBAUGBwgJCgsMDQ4PEBESExT/" to "8QWVM")
        vectors.forEach { (key, expected) ->
            val data = requireNotNull(TotpDataResolver.fromAuthenticatorKey("steam://$key"))
            assertEquals(key, data.steamSharedSecretBase64)
            assertEquals(expected, TotpGenerator.generateOtp(data, currentSeconds = 1_700_000_000L))
            assertEquals(expected, SteamTotp.generateAuthCode(key, 1_700_000_000L))
        }
    }

    @Test fun maFileSteamUriVariantPreservesLeadingSlash() {
        val payload = SteamMaFileParser().parse("""{"shared_secret":"steam://$shared","account_name":"synthetic"}""",
            allowMissingSteamId = true)
        assertEquals(shared, payload.sharedSecret)
        assertEquals("PMRRM", SteamTotp.generateAuthCode(payload.sharedSecret, 1_700_000_000L))
    }

    @Test fun percentEncodedSteamUriDecodesOnceAndKeepsAlphabet() {
        val encoded = shared.replace("/", "%2F").replace("=", "%3D")
        val parsed = requireNotNull(TotpDataResolver.fromAuthenticatorKey("steam://$encoded"))
        assertEquals(shared, parsed.steamSharedSecretBase64)
        assertEquals("PMRRM", TotpGenerator.generateOtp(parsed, currentSeconds = 1_700_000_000L))
        val payload = SteamMaFileParser().parse("""{"uri":"steam://$encoded","account_name":"synthetic"}""",
            allowMissingSteamId = true)
        assertEquals(shared, payload.sharedSecret)
    }

    @Test fun sessionAndUriPresenceDoNotChangeEquivalentTokenKey() {
        val noUri = """{"shared_secret":"$shared","account_name":"synthetic","Session":null,"fully_enrolled":false}"""
        for (content in listOf(json, noUri, noUri.replace("false", "true"))) {
            val payload = SteamMaFileParser().parse(content, allowMissingSteamId = true)
            assertEquals(shared, payload.sharedSecret)
            val cardData = TotpData(secret = "steam://${payload.sharedSecret}")
            assertEquals("PMRRM", TotpGenerator.generateOtp(cardData, currentSeconds = 1_700_000_000L))
            assertEquals("433DY", TotpGenerator.generateOtp(cardData, currentSeconds = 1_700_000_030L))
        }
    }
}
