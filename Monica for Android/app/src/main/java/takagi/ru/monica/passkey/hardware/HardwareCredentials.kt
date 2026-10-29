package takagi.ru.monica.passkey.hardware

/** Read-only credential management. No reset/delete commands are exposed. */
object HardwareCredentials {
    data class Account(val rpId: String, val userName: String)
    data class Snapshot(val existing: Int, val remaining: Int, val accounts: List<Account>)

    suspend fun read(transport: FidoTransport, info: DeviceInfo, pin: String): Snapshot {
        require(info.supportsCredMgmt || info.supportsCredMgmtPreview)
        val protocol = HardwarePin(transport, HardwarePin.select(info))
        val token = protocol.agreement().use { it.token(pin, 4, null, info.supportsPinUvAuthToken) }
        try {
            suspend fun command(sub: Int, params: CborRaw? = null, authenticated: Boolean = true): CborMap {
                val message = byteArrayOf(sub.toByte()) + (params?.bytes?.toByteArray() ?: byteArrayOf())
                val bytes = transport.sendCtapCommand(byteArrayOf(if (info.usePreviewCommand) 0x41 else 0x0a) + cbor { map {
                    1 to sub; params?.let { 2 to it }
                    if (authenticated) { 3 to token.protocol; 4 to bytes(token.authenticate(message)) }
                } })
                CTAP.getResponseError(bytes)?.let { throw CTAP.Exception(it) }
                return CborMap.decode(bytes.copyOfRange(1, bytes.size)) ?: error("Invalid credential management response")
            }
            val meta = command(1)
            val existing = meta.int(1) ?: error("Missing credential count")
            val remaining = meta.int(2) ?: error("Missing capacity")
            require(existing in 0..4096 && remaining >= 0)
            if (existing == 0) return Snapshot(0, remaining, emptyList())
            val firstRp = command(2)
            val rpCount = firstRp.int(5) ?: error("Missing RP count")
            require(rpCount in 1..minOf(existing, 1024))
            val rps = mutableListOf(firstRp)
            repeat(rpCount - 1) { rps.add(command(3, authenticated = false)) }
            val accounts = mutableListOf<Account>()
            for (rp in rps) {
                val hash = rp.bytes(4) ?: error("Missing RP hash")
                val id = rp.map(3)?.string("id") ?: error("Missing RP")
                require(hash.size == 32 && hash.contentEquals(FidoCommands.hashRpId(id)))
                val params = CborRaw(cbor { map { 1 to bytes(hash) } }.toList())
                val first = command(4, params)
                val count = first.int(9) ?: error("Missing credential count")
                require(count in 1..existing && accounts.size + count <= existing)
                fun add(credential: CborMap) {
                    val user = credential.map(6) ?: error("Missing user")
                    accounts.add(Account(id.take(253), (user.string("displayName") ?: user.string("name") ?: "").take(256)))
                }
                add(first)
                repeat(count - 1) { add(command(5, authenticated = false)) }
            }
            return Snapshot(existing, remaining, accounts)
        } finally { token.close() }
    }
}
