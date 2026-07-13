package takagi.ru.monica.steam.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.steam.data.SteamAccount

class SteamAuthorizedDeviceServiceTest {
    @Test
    fun fetchMarksTheRequestingTokenAsCurrentDevice() {
        val currentTokenId = -2L
        val otherTokenId = 42L
        val responsePayload = SteamProtoWriter().apply {
            writeMessage(1, deviceMessage(currentTokenId, "This phone"))
            writeMessage(1, deviceMessage(otherTokenId, "Old phone"))
            writeFixed64(2, currentTokenId)
        }.toByteArray()
        val service = SteamAuthorizedDeviceService(
            api = SteamApiClient(successClient(responsePayload))
        )

        val devices = service.fetch(account())

        assertEquals(2, devices.size)
        assertTrue(devices.single { it.tokenId == "18446744073709551614" }.isCurrent)
        assertFalse(devices.single { it.tokenId == "42" }.isCurrent)
    }

    private fun deviceMessage(tokenId: Long, description: String): SteamProtoWriter {
        return SteamProtoWriter().apply {
            writeFixed64(1, tokenId)
            writeString(2, description)
            writeVarint(4, 3L)
            writeBool(5, true)
        }
    }

    private fun successClient(payload: ByteArray): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("x-eresult", "1")
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun account(): SteamAccount {
        return SteamAccount(
            id = 1L,
            steamId = "76561198000000001",
            accountName = "steam_user",
            displayName = "steam_user",
            deviceId = "android:test",
            sharedSecret = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
            identitySecret = "YWJjZGVmZ2hpamtsbW5vcHFyc3Q=",
            revocationCode = "R12345",
            tokenGid = "token-gid",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            steamLoginSecure = "76561198000000001||access-token",
            rawSteamGuardJson = "{}",
            selected = true,
            sortOrder = 0,
            createdAt = 1L,
            updatedAt = 1L
        )
    }
}
