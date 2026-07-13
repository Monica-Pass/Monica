package takagi.ru.monica.steam.network

import takagi.ru.monica.steam.data.SteamAccount

data class SteamAuthorizedDevice(
    val tokenId: String,
    val description: String,
    val platformType: Int,
    val loggedIn: Boolean,
    val firstSeen: SteamAuthorizedDeviceUsage?,
    val lastSeen: SteamAuthorizedDeviceUsage?,
    val isCurrent: Boolean = false
)

data class SteamAuthorizedDeviceUsage(
    val timeSeconds: Long,
    val country: String,
    val state: String,
    val city: String
) {
    val location: String
        get() = listOf(city, state, country).filter { it.isNotBlank() }.joinToString(", ")
}

class SteamAuthorizedDeviceService(
    private val api: SteamApiClient = SteamApiClient()
) {
    fun fetch(account: SteamAccount): List<SteamAuthorizedDevice> {
        if (!account.hasRealSteamId) return emptyList()
        val token = account.accessToken ?: return emptyList()
        val request = SteamProtoWriter().apply {
            writeBool(1, false)
        }
        val fields = SteamProtoReader(
            api.callProtobuf(
                iface = "IAuthenticationService",
                method = "EnumerateTokens",
                request = request,
                accessToken = token
            )
        ).parseAll()

        val requestingToken = fields
            .firstOrNull { it.number == 2 }
            ?.asFixed64UnsignedString
            .orEmpty()

        return fields
            .filter { it.number == 1 && it.bytes != null }
            .mapNotNull { field ->
                field.bytes?.let(::parseDevice)
            }
            .map { device ->
                device.copy(
                    isCurrent = requestingToken.isNotBlank() && device.tokenId == requestingToken
                )
            }
    }

    private fun parseDevice(bytes: ByteArray): SteamAuthorizedDevice? {
        val fields = SteamProtoReader(bytes).parse()
        val tokenId = fields[1]?.asFixed64UnsignedString.orEmpty()
        val description = fields[2]?.asString.orEmpty()
        if (tokenId.isBlank() && description.isBlank()) return null
        return SteamAuthorizedDevice(
            tokenId = tokenId,
            description = description,
            platformType = fields[4]?.asInt ?: 0,
            loggedIn = fields[5]?.asBool ?: false,
            firstSeen = fields[9]?.bytes?.let(::parseUsage),
            lastSeen = fields[10]?.bytes?.let(::parseUsage)
        )
    }

    private fun parseUsage(bytes: ByteArray): SteamAuthorizedDeviceUsage {
        val fields = SteamProtoReader(bytes).parse()
        return SteamAuthorizedDeviceUsage(
            timeSeconds = fields[1]?.asLong ?: 0L,
            country = fields[4]?.asString.orEmpty(),
            state = fields[5]?.asString.orEmpty(),
            city = fields[6]?.asString.orEmpty()
        )
    }

}
