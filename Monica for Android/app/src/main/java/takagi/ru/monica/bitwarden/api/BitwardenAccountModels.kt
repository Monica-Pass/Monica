@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package takagi.ru.monica.bitwarden.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class BitwardenEmailRequest(val email: String)

@Serializable
data class BitwardenRegistrationStartRequest(
    val email: String,
    val name: String? = null,
    val receiveMarketingEmails: Boolean = false,
)

@Serializable
data class BitwardenRegistrationStartResponse(
    @JsonNames("EmailVerificationToken") val emailVerificationToken: String? = null,
)

@Serializable
data class BitwardenRegistrationVerifyRequest(val email: String, val emailVerificationToken: String)

@Serializable
data class BitwardenAccountDevicesResponse(
    @JsonNames("Data") val data: List<BitwardenAccountDevice>,
)

/** Display-only device details deliberately exclude encrypted user/private keys. */
@Serializable
data class BitwardenAccountDevice(
    @JsonNames("Id") val id: String,
    @JsonNames("Name") val name: String = "",
    @JsonNames("Identifier") val identifier: String = "",
    @JsonNames("Type") val type: Int = -1,
    @JsonNames("CreationDate") val creationDate: String? = null,
    @JsonNames("LastActivityDate") val lastActivityDate: String? = null,
    @JsonNames("IsTrusted") val isTrusted: Boolean? = null,
)
