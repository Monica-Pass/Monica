package takagi.ru.monica.passkey.hardware

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Only consumes a validated final CredentialProvider request, never an offline RP/user form. */
class HardwareWebAuthn(val json: JSONObject, val rpId: String, val origin: String, suppliedHash: ByteArray?, val create: Boolean) {
    val userName: String = json.optJSONObject("user")?.optString("name").orEmpty().take(256)
    val verification = json.optJSONObject("authenticatorSelection")?.optString("userVerification", "preferred")
        ?: json.optString("userVerification", "preferred")
    private val clientJson: String?
    val clientHash: ByteArray
    init {
        require(json.toString().length <= 65535 && verification in listOf("required", "preferred", "discouraged"))
        val challenge = decode(json.getString("challenge"), 1024)
        require(challenge.isNotEmpty())
        require(suppliedHash == null || suppliedHash.size == 32)
        clientJson = if (suppliedHash != null) null else JSONObject().apply {
            put("type", if (create) "webauthn.create" else "webauthn.get")
            put("challenge", encode(challenge)); put("origin", origin); put("crossOrigin", false)
        }.toString().replace("\\/", "/")
        clientHash = suppliedHash?.copyOf() ?: FidoCommands.hashClientData(clientJson!!)
        val requestedOrigin = json.optString("origin")
        require(requestedOrigin.isEmpty() || requestedOrigin.removeSuffix("/") == origin) { "Origin mismatch" }
        val extensions = json.optJSONObject("extensions")
        if (extensions != null) {
            // Unimplemented extensions must not be silently reported as having succeeded.
            for (key in extensions.keys()) require(key == "credProps" || extensions.opt(key) == false || extensions.isNull(key)) { "Unsupported extension" }
        }
        if (create) {
            require(json.optString("attestation", "none") in listOf("none", "indirect", "direct")) { "Unsupported attestation" }
            require(json.optJSONObject("authenticatorSelection")?.optString("authenticatorAttachment") != "platform") { "Platform-only request" }
            require(decode(json.getJSONObject("user").getString("id"), 64).isNotEmpty())
            require(algorithms().isNotEmpty())
        }
    }

    private fun algorithms(): List<Pair<String, Int>> {
        val list = json.getJSONArray("pubKeyCredParams")
        require(list.length() in 1..64)
        return (0 until list.length()).mapNotNull { index ->
            val entry = list.getJSONObject(index)
            if (entry.getString("type") == "public-key") "public-key" to entry.getInt("alg") else null
        }
    }

    private fun descriptors(key: String, info: DeviceInfo): List<FidoCommands.CredentialDescriptor> {
        val list = json.optJSONArray(key) ?: return emptyList()
        require(list.length() <= minOf(128, info.maxCredentialCountInList ?: 128)) { "Credential list too large for key" }
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            require(item.getString("type") == "public-key")
            val id = decode(item.getString("id"), minOf(4096, info.maxCredentialIdLength ?: 4096))
            require(id.isNotEmpty())
            FidoCommands.CredentialDescriptor("public-key", id)
        }
    }

    private suspend fun uv(transport: FidoTransport, info: DeviceInfo, pin: String?): Pair<FidoCommands.UvMode, HardwarePin.Token?> {
        val want = verification != "discouraged" || info.options["alwaysUv"] == true || (create && info.clientPinSet)
        if (!want) return FidoCommands.UvMode.None to null
        if (info.clientPinSet) {
            require(!pin.isNullOrEmpty()) { "PIN required" }
            val protocol = HardwarePin(transport, HardwarePin.select(info))
            val token = protocol.agreement().use { agreement ->
                agreement.token(pin, if (create) 1 else 2, rpId, info.supportsPinUvAuthToken)
            }
            return FidoCommands.UvMode.AuthToken(token.authenticate(clientHash), token.protocol) to token
        }
        if (info.supportsBuiltInUv) {
            if (info.supportsPinUvAuthToken) {
                val protocol = HardwarePin(transport, HardwarePin.select(info))
                val token = protocol.agreement().use { it.token(null, if (create) 1 else 2, rpId, true) }
                return FidoCommands.UvMode.AuthToken(token.authenticate(clientHash), token.protocol) to token
            }
            return FidoCommands.UvMode.BuiltIn to null
        }
        require(verification != "required" && info.options["alwaysUv"] != true) { "Set a PIN on this key first" }
        return FidoCommands.UvMode.None to null
    }

    suspend fun register(transport: FidoTransport, info: DeviceInfo, pin: String?): String {
        check(create)
        val selection = json.optJSONObject("authenticatorSelection")
        val resident = selection?.optString("residentKey").orEmpty()
        require(resident in listOf("", "required", "preferred", "discouraged"))
        val required = resident == "required" || (resident.isEmpty() && selection?.optBoolean("requireResidentKey") == true)
        require(!required || info.options["rk"] == true) { "Discoverable credentials unsupported" }
        val rk = required || (resident == "preferred" && info.options["rk"] == true)
        val excluded = descriptors("excludeCredentials", info)
        val params = algorithms()
        val (uv, token) = uv(transport, info, pin)
        try {
            val user = json.getJSONObject("user")
            val command = FidoCommands.buildMakeCredential(clientHash, rpId, json.getJSONObject("rp").optString("name").take(256),
                decode(user.getString("id"), 64), userName, user.optString("displayName").take(256), params,
                excluded, rk, uv)
            checkSize(command, info)
            val result = FidoCommands.parseMakeCredentialResponse(transport.sendCtapCommand(command)).getOrThrow()
            val data = validateAuthData(result.authData, uv !is FidoCommands.UvMode.None)
            val credential = data.attestedCredentialData ?: error("Missing attested credential")
            require(credential.credentialId.isNotEmpty() && params.any { it.second == credential.publicKeyAlgorithm })
            require(excluded.none { it.id.contentEquals(credential.credentialId) })
            val attestation = json.optString("attestation", "none")
            require(attestation != "enterprise") { "Enterprise attestation unsupported" }
            val bytes = result.authData.copyOf()
            if (attestation == "none") bytes.fill(0, 37, 53)
            val attObject = cbor { map {
                "fmt" to if (attestation == "none") "none" else result.fmt
                "attStmt" to if (attestation == "none") emptyMap<String, Any>() else result.attStmt
                "authData" to bytes(bytes)
            } }
            return credentialResponse(credential.credentialId).apply {
                put("response", JSONObject().apply {
                    clientJson?.let { put("clientDataJSON", encode(it.toByteArray(Charsets.UTF_8))) }
                    put("attestationObject", encode(attObject)); put("authenticatorData", encode(bytes))
                    put("publicKeyAlgorithm", credential.publicKeyAlgorithm)
                    p256Spki(credential)?.let { put("publicKey", encode(it)) }
                    put("transports", JSONArray().put(transport.transportType.value))
                })
                put("clientExtensionResults", JSONObject().apply {
                    if (json.optJSONObject("extensions")?.optBoolean("credProps") == true) put("credProps", JSONObject().put("rk", rk))
                })
            }.toString()
        } finally { token?.close() }
    }

    suspend fun assertions(transport: FidoTransport, info: DeviceInfo, pin: String?): List<FidoCommands.GetAssertionResponse> {
        check(!create)
        val allowed = descriptors("allowCredentials", info)
        val (uv, token) = uv(transport, info, pin)
        try {
            val command = FidoCommands.buildGetAssertion(rpId, clientHash, allowed, uv)
            checkSize(command, info)
            val first = FidoCommands.parseGetAssertionResponse(transport.sendCtapCommand(command)).getOrThrow()
            val count = first.numberOfCredentials ?: 1
            require(count in 1..100)
            val results = mutableListOf(first)
            repeat(count - 1) { results.add(FidoCommands.parseGetAssertionResponse(transport.sendCtapCommand(FidoCommands.buildGetNextAssertion())).getOrThrow()) }
            return results.map { result ->
                val data = validateAuthData(result.authData, uv !is FidoCommands.UvMode.None)
                require(!data.hasAttestedCredentialData && result.signature.isNotEmpty())
                val id = result.credential?.id ?: allowed.singleOrNull()?.id ?: error("Missing credential ID")
                require(id.isNotEmpty() && (allowed.isEmpty() || allowed.any { it.id.contentEquals(id) }))
                if (allowed.isEmpty()) require(result.user?.id?.isNotEmpty() == true) { "Missing discoverable user" }
                result.copy(credential = FidoCommands.CredentialDescriptor("public-key", id))
            }
        } finally { token?.close() }
    }

    fun assertionResponse(result: FidoCommands.GetAssertionResponse): String = credentialResponse(result.credential!!.id).apply {
        put("response", JSONObject().apply {
            clientJson?.let { put("clientDataJSON", encode(it.toByteArray(Charsets.UTF_8))) }
            put("authenticatorData", encode(result.authData)); put("signature", encode(result.signature))
            put("userHandle", result.user?.id?.let(::encode) ?: JSONObject.NULL)
        }); put("clientExtensionResults", JSONObject())
    }.toString()

    private fun validateAuthData(bytes: ByteArray, requireUv: Boolean): AuthenticatorData {
        val data = AuthenticatorData.parse(bytes) ?: error("Invalid authenticator data")
        require(data.rpIdHash.contentEquals(FidoCommands.hashRpId(rpId)) && data.userPresent && (!requireUv || data.userVerified)) { "Invalid authenticator proof" }
        require(data.flags and 0x10 == 0 || data.flags and 0x08 != 0) { "Invalid backup flags" }
        return data
    }

    private fun checkSize(command: ByteArray, info: DeviceInfo) { require(command.size <= (info.maxMsgSize ?: 1024)) { "Request exceeds key capacity" } }
    private fun credentialResponse(id: ByteArray) = JSONObject().put("id", encode(id)).put("rawId", encode(id)).put("type", "public-key").put("authenticatorAttachment", "cross-platform")

    companion object {
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun decode(value: String, max: Int): ByteArray {
            require(value.length <= ((max + 2) / 3) * 4 && value.matches(Regex("[A-Za-z0-9_-]*={0,2}")))
            return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP).also { require(it.size <= max && encode(it) == value.trimEnd('=')) }
        }
        private fun p256Spki(key: AttestedCredentialData): ByteArray? {
            if (key.keyType != 2 || key.curve != 1) return null
            val x = key.credentialPublicKey[-2L] as? ByteArray ?: return null
            val y = key.credentialPublicKey[-3L] as? ByteArray ?: return null
            require(x.size == 32 && y.size == 32)
            return "3059301306072a8648ce3d020106082a8648ce3d03010703420004".hexToByteArray() + x + y
        }
    }
}
