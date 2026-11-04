package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.Serializable
import takagi.ru.monica.bitwarden.api.SendApiResponse
import takagi.ru.monica.data.bitwarden.BitwardenSend

/** KEEP preserves both missing and unknown server fields on existing Sends. */
@Serializable enum class SendAuthAction(val wireValue: Int?) { KEEP(null), NONE(2), PASSWORD(1), EMAIL(0) }

internal object BitwardenSendAuth {
    private val address = Regex("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$")

    fun recipients(values: List<String>): List<String> {
        require(values.size in 1..100)
        val normalized = values.map(String::trim)
        require(normalized.all { it.length <= 320 && address.matches(it) })
        require(normalized.distinct().size == normalized.size)
        require(normalized.sumOf(String::length) <= 8192)
        return normalized
    }

    fun requested(draft: BitwardenSendDraft, creating: Boolean): SendAuthAction = when {
        draft.authAction != SendAuthAction.KEEP -> draft.authAction
        draft.passwordAction == SendPasswordAction.REPLACE -> SendAuthAction.PASSWORD
        draft.passwordAction == SendPasswordAction.REMOVE -> SendAuthAction.NONE
        creating -> if (draft.password.isNullOrEmpty()) SendAuthAction.NONE else SendAuthAction.PASSWORD
        else -> SendAuthAction.KEEP
    }

    fun validate(draft: BitwardenSendDraft) {
        when (draft.authAction) {
            SendAuthAction.EMAIL -> {
                recipients(draft.recipientEmails)
                require(draft.passwordAction != SendPasswordAction.REPLACE && draft.password.isNullOrEmpty())
            }
            SendAuthAction.NONE -> require(draft.passwordAction != SendPasswordAction.REPLACE && draft.password.isNullOrEmpty())
            SendAuthAction.PASSWORD -> require(draft.passwordAction != SendPasswordAction.REMOVE)
            SendAuthAction.KEEP -> Unit
        }
    }

    fun apiEmails(draft: BitwardenSendDraft): String? =
        if (requested(draft, creating = true) == SendAuthAction.EMAIL) recipients(draft.recipientEmails).joinToString(",") else null

    fun matches(actual: BitwardenSend, draft: BitwardenSendDraft, previous: BitwardenSend? = null): Boolean {
        return when (requested(draft, creating = previous == null)) {
            SendAuthAction.EMAIL -> !actual.hasPassword && actual.authType == 0 && actual.recipientEmails?.split(',')?.map(String::trim) == recipients(draft.recipientEmails)
            SendAuthAction.PASSWORD -> actual.hasPassword && actual.authType in listOf(null, 1) && actual.recipientEmails.isNullOrBlank()
            SendAuthAction.NONE -> !actual.hasPassword && actual.authType in listOf(null, 2) && actual.recipientEmails.isNullOrBlank()
            SendAuthAction.KEEP -> previous != null && actual.authType == previous.authType &&
                actual.recipientEmails == previous.recipientEmails && actual.hasPassword == previous.hasPassword
        }
    }

    fun matchesApi(actual: SendApiResponse, requestAuth: SendAuthAction, emails: String?): Boolean = when (requestAuth) {
        SendAuthAction.EMAIL -> actual.password == null && actual.authType == 0 && actual.emails?.split(',')?.map(String::trim) == emails?.split(',')
        SendAuthAction.PASSWORD -> actual.password != null && actual.authType in listOf(null, 1) && actual.emails.isNullOrBlank()
        SendAuthAction.NONE -> actual.password == null && actual.authType in listOf(null, 2) && actual.emails.isNullOrBlank()
        SendAuthAction.KEEP -> false
    }
}
