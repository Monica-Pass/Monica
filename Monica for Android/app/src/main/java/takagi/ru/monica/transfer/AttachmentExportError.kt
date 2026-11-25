package takagi.ru.monica.transfer

import android.content.Context
import kotlinx.coroutines.CancellationException
import takagi.ru.monica.R
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.normalizeAttachmentFailure
import takagi.ru.monica.attachments.storage.AttachmentKeyUnavailableException
import takagi.ru.monica.attachments.ui.attachmentErrorMessage
import takagi.ru.monica.attachments.util.AttachmentLogger
import takagi.ru.monica.utils.PortableSecretExportException
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Display names are kept out of the exception message/stack trace exported in diagnostic logs. */
internal class AttachmentExportException(
    val itemTitle: String,
    val fileName: String,
    cause: Throwable,
) : Exception("Attachment export failed", cause)

internal suspend fun <T> withAttachmentExportError(attachment: ExportAttachment, action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    AttachmentLogger.logFailure(
        event = AttachmentLogger.Event.EXPORT,
        attachmentId = attachment.localAttachmentId,
        source = attachment.source,
        error = normalizeAttachmentFailure(error),
        extras = mapOf(
            "ownerKind" to attachment.owner.kind.name,
            "ownerId" to attachment.owner.id,
            "keyFailure" to (error as? AttachmentKeyUnavailableException)?.reason?.name,
            "causeClasses" to generateSequence(error as Throwable) { it.cause?.takeUnless { cause -> cause === it } }
                .take(6).joinToString(",") { it.javaClass.simpleName },
        ),
    )
    throw AttachmentExportException(attachment.ownerTitle.ifBlank { "#${attachment.owner.id}" }, attachment.fileName, error)
}

internal fun databaseExportErrorMessage(context: Context, error: Throwable): String {
    val strings = AppLocaleStringResolver(context)
    if (error is PortableSecretExportException) return strings.get(
        R.string.backup_sensitive_data_unreadable,
        error.entryTitle.ifBlank { strings.get(R.string.untitled) },
    )
    if (error !is AttachmentExportException) return error.message ?: strings.get(R.string.export_data_error)
    val cause = checkNotNull(error.cause)
    val reason = when {
        cause is AttachmentKeyUnavailableException -> strings.get(R.string.transfer_attachment_key_unavailable)
        normalizeAttachmentFailure(cause) == AttachmentError.CryptoError ->
            strings.get(R.string.transfer_attachment_content_unreadable)
        else -> attachmentErrorMessage(context, cause)
    }
    return strings.get(R.string.transfer_attachment_export_failed, error.itemTitle, error.fileName, reason)
}
