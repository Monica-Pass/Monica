package takagi.ru.monica.keepass

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.PasskeyEntry

object KeePassPasskeySyncCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(passkey: PasskeyEntry): String {
        return json.encodeToString(Payload.serializer(), Payload.from(passkey))
    }

    fun decode(
        raw: String,
        databaseId: Long,
        groupPath: String?,
        groupUuid: String?
    ): PasskeyEntry? {
        return runCatching {
            val payload = json.decodeFromString(Payload.serializer(), raw)
            payload.toPasskeyEntry(
                databaseId = databaseId,
                groupPath = groupPath,
                groupUuid = groupUuid
            )
        }.getOrNull()
    }

    @Serializable
    private data class Payload(
        val credentialId: String,
        val rpId: String,
        val rpName: String,
        val userId: String,
        val userName: String,
        val userDisplayName: String,
        val publicKeyAlgorithm: Int,
        val publicKey: String,
        val privateKeyAlias: String,
        val createdAt: Long,
        val lastUsedAt: Long,
        val useCount: Int,
        val iconUrl: String? = null,
        val isDiscoverable: Boolean,
        val isUserVerificationRequired: Boolean,
        val transports: String,
        val aaguid: String,
        val signCount: Long,
        val backupEligible: Boolean? = null,
        val backupState: Boolean? = null,
        val notes: String = "",
        val passkeyMode: String = PasskeyEntry.MODE_KEEPASS_COMPAT
    ) {
        fun toPasskeyEntry(
            databaseId: Long,
            groupPath: String?,
            groupUuid: String?
        ): PasskeyEntry {
            return PasskeyEntry(
                credentialId = credentialId,
                rpId = rpId,
                rpName = rpName,
                userId = userId,
                userName = userName,
                userDisplayName = userDisplayName,
                publicKeyAlgorithm = publicKeyAlgorithm,
                publicKey = publicKey,
                privateKeyAlias = privateKeyAlias,
                createdAt = createdAt,
                lastUsedAt = lastUsedAt,
                useCount = useCount,
                iconUrl = iconUrl,
                isDiscoverable = isDiscoverable,
                isUserVerificationRequired = isUserVerificationRequired,
                transports = transports,
                aaguid = aaguid,
                signCount = signCount,
                isBackedUp = false,
                backupEligible = backupEligible,
                backupState = backupState,
                notes = notes,
                keepassDatabaseId = databaseId,
                keepassGroupPath = groupPath,
                syncStatus = "NONE",
                passkeyMode = when (passkeyMode) {
                    PasskeyEntry.MODE_BW_COMPAT -> PasskeyEntry.MODE_BW_COMPAT
                    PasskeyEntry.MODE_KEEPASS_COMPAT -> PasskeyEntry.MODE_KEEPASS_COMPAT
                    else -> PasskeyEntry.MODE_KEEPASS_COMPAT
                }
            )
        }

        companion object {
            fun from(passkey: PasskeyEntry): Payload {
                return Payload(
                    credentialId = passkey.credentialId,
                    rpId = passkey.rpId,
                    rpName = passkey.rpName,
                    userId = passkey.userId,
                    userName = passkey.userName,
                    userDisplayName = passkey.userDisplayName,
                    publicKeyAlgorithm = passkey.publicKeyAlgorithm,
                    publicKey = passkey.publicKey,
                    privateKeyAlias = passkey.privateKeyAlias,
                    createdAt = passkey.createdAt,
                    lastUsedAt = passkey.lastUsedAt,
                    useCount = passkey.useCount,
                    iconUrl = passkey.iconUrl,
                    isDiscoverable = passkey.isDiscoverable,
                    isUserVerificationRequired = passkey.isUserVerificationRequired,
                    transports = passkey.transports,
                    aaguid = passkey.aaguid,
                    signCount = passkey.signCount,
                    backupEligible = passkey.backupEligible,
                    backupState = passkey.backupState,
                    notes = passkey.notes,
                    passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT
                )
            }
        }
    }
}
