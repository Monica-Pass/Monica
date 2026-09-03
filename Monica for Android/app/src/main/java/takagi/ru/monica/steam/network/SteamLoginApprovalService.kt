package takagi.ru.monica.steam.network

import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import takagi.ru.monica.steam.core.SteamLoginApprovalSigner
import takagi.ru.monica.steam.data.SteamAccount

data class SteamQrChallenge(
    val version: Int,
    val clientId: Long
) {
    companion object {
        fun parse(raw: String): SteamQrChallenge? {
            val normalized = raw.trim().takeIf { it.isNotBlank() } ?: return null
            return parseCandidates(normalized)
                .asSequence()
                .flatMap { candidate ->
                    sequenceOf(candidate, candidate.decodeUrlComponent())
                }
                .mapNotNull(::parseCandidate)
                .firstOrNull()
        }

        private fun parseCandidates(raw: String): List<String> {
            val trimmed = raw.trimQrPayload()
            val urls = URL_PATTERN.findAll(trimmed)
                .map { it.value.trimQrPayload() }
                .toList()
            return buildList {
                add(trimmed)
                addAll(urls)
                (urls + trimmed).forEach { value ->
                    unwrapSteamOpenUrl(value)?.let(::add)
                }
            }
        }

        private fun parseCandidate(value: String): SteamQrChallenge? {
            val uri = runCatching { URI(value.trimQrPayload()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host !in ALLOWED_STEAM_QR_HOSTS) return null
            val segments = uri.rawPath
                ?.split('/')
                ?.filter { it.isNotBlank() }
                ?: return null
            val index = segments.indexOf("q")
            if (index < 0 || segments.size < index + 3) return null
            val version = segments[index + 1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val clientId = parseUnsignedClientId(segments[index + 2]) ?: return null
            return SteamQrChallenge(version = version, clientId = clientId)
        }

        private fun parseUnsignedClientId(value: String): Long? {
            if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
            val unsigned = runCatching { BigInteger(value) }.getOrNull() ?: return null
            if (unsigned <= BigInteger.ZERO || unsigned > UNSIGNED_LONG_MAX) return null
            return if (unsigned <= SIGNED_LONG_MAX) {
                unsigned.toLong()
            } else {
                unsigned.subtract(UNSIGNED_LONG_BASE).toLong()
            }
        }

        private fun unwrapSteamOpenUrl(value: String): String? {
            val match = STEAM_OPEN_URL_PATTERN.find(value.trimQrPayload()) ?: return null
            return match.groupValues[1].decodeUrlComponent().trimQrPayload().takeIf { it.isNotBlank() }
        }

        private fun String.decodeUrlComponent(): String {
            return runCatching {
                URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrDefault(this)
        }

        private fun String.trimQrPayload(): String {
            return trim()
                .trim('"', '\'', '`', '<', '>', '(', ')', '[', ']', '{', '}')
                .trimEnd('.', ',', ';')
        }

        private val ALLOWED_STEAM_QR_HOSTS = setOf(
            "s.team",
            "steamcommunity.com",
            "www.steamcommunity.com"
        )
        private val URL_PATTERN = Regex("""(?:https?://|steam://)\S+""", RegexOption.IGNORE_CASE)
        private val STEAM_OPEN_URL_PATTERN = Regex("""^steam://openurl/(.+)$""", RegexOption.IGNORE_CASE)
        private val SIGNED_LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
        private val UNSIGNED_LONG_BASE = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
        private val UNSIGNED_LONG_MAX = UNSIGNED_LONG_BASE.subtract(BigInteger.ONE)
    }
}

data class SteamPendingLogin(
    val clientId: Long,
    val version: Int,
    val ip: String,
    val city: String,
    val country: String,
    val deviceName: String,
    val detectedAtMillis: Long = System.currentTimeMillis()
) {
    val location: String
        get() = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
}

class SteamLoginApprovalService(
    private val api: SteamApiClient = SteamApiClient()
) {
    fun pendingLogins(account: SteamAccount): List<SteamPendingLogin> {
        if (!account.hasRealSteamId) return emptyList()
        val token = account.accessToken ?: return emptyList()
        val ids = pendingLoginClientIds(token)
        return ids.mapNotNull { clientId ->
            runCatching { sessionInfo(account, clientId) }.getOrNull()
        }
    }

    fun sessionInfo(account: SteamAccount, clientId: Long): SteamPendingLogin? {
        if (!account.hasRealSteamId) return null
        val token = account.accessToken ?: return null
        val request = SteamProtoWriter().apply {
            writeVarint(1, clientId)
        }
        val fields = SteamProtoReader(
            api.callProtobuf(
                iface = "IAuthenticationService",
                method = "GetAuthSessionInfo",
                request = request,
                accessToken = token
            )
        ).parse()
        return SteamPendingLogin(
            clientId = clientId,
            version = fields[8]?.asInt ?: 0,
            ip = fields[1]?.asString.orEmpty(),
            city = fields[3]?.asString.orEmpty(),
            country = fields[5]?.asString.orEmpty(),
            deviceName = fields[7]?.asString.orEmpty()
        )
    }

    fun respondToQr(account: SteamAccount, challenge: SteamQrChallenge, approve: Boolean): Boolean {
        return respondToSession(
            account = account,
            clientId = challenge.clientId,
            version = challenge.version,
            approve = approve
        )
    }

    fun respondToSession(
        account: SteamAccount,
        clientId: Long,
        version: Int,
        approve: Boolean
    ): Boolean {
        require(account.canApproveLogins) { "Steam account has no real SteamID or access token" }
        val token = requireNotNull(account.accessToken) { "access token required" }
        val signature = SteamLoginApprovalSigner.signature(
            sharedSecretBase64 = account.sharedSecret,
            version = version,
            clientId = clientId,
            steamId = account.steamId.toLong()
        )
        val request = SteamProtoWriter().apply {
            writeVarint(1, version.toLong())
            writeVarint(2, clientId)
            writeFixed64(3, account.steamId.toLong())
            writeBytes(4, signature)
            writeBool(5, approve)
            writeVarint(6, 1L)
        }
        val responseFields = SteamProtoReader(
            api.callProtobuf(
                iface = "IAuthenticationService",
                method = "UpdateAuthSessionWithMobileConfirmation",
                request = request,
                accessToken = token
            )
        ).parse()
        return responseFields.isEmpty() || (responseFields[1]?.asBool ?: true)
    }

    private fun pendingLoginClientIds(accessToken: String): List<Long> {
        val bytes = api.callProtobuf(
            iface = "IAuthenticationService",
            method = "GetAuthSessionsForAccount",
            request = SteamProtoWriter(),
            accessToken = accessToken,
            useGet = true
        )
        return SteamProtoReader(bytes).parseAll().flatMap { field ->
            if (field.number != 1) {
                emptyList()
            } else if (field.varint != null) {
                listOf(field.varint)
            } else if (field.bytes != null) {
                SteamProtoReader.decodePackedVarints(field.bytes)
            } else {
                emptyList()
            }
        }
    }
}
