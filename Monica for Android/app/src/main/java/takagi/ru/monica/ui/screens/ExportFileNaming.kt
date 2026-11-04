package takagi.ru.monica.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportDocumentSpec(
    val fileName: String,
    val mimeType: String
)

fun exportDocumentSpec(
    selectedOption: ExportOption,
    currentTimeMillis: Long = System.currentTimeMillis(),
    encryptedZip: Boolean = false,
): ExportDocumentSpec {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(currentTimeMillis))
    val fileName = when (selectedOption) {
        ExportOption.ZIP_BACKUP -> if (encryptedZip) {
            "monica_backup_${timestamp}.enc.zip"
        } else {
            "monica_backup_${timestamp}.zip"
        }
        ExportOption.KDBX -> "monica_${timestamp}.kdbx"
        ExportOption.BITWARDEN_JSON -> "bitwarden_${timestamp}.json"
        ExportOption.BITWARDEN_JSON_ENCRYPTED -> "bitwarden_${timestamp}.encrypted.json"
        ExportOption.STEAM_MAFILE -> "steam_mafiles_${timestamp}.zip"
    }

    val mimeType = when {
        fileName.endsWith(".json") -> "application/json"
        fileName.endsWith(".zip") -> "application/zip"
        fileName.endsWith(".kdbx") -> "application/octet-stream"
        else -> "*/*"
    }

    return ExportDocumentSpec(fileName, mimeType)
}
