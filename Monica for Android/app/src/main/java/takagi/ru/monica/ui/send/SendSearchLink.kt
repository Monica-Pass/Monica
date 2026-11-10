package takagi.ru.monica.ui.send

import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverLink

/** Local recognition only. Do not retain the decoded key or include the link in diagnostics. */
internal fun sendLinkHost(query: String): String? = runCatching {
    BitwardenSendReceiverLink.parse(query.trim()).use { it.webBase.host }
}.getOrNull()
