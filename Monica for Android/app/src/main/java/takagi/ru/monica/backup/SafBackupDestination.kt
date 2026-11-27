package takagi.ru.monica.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class FolderDocument(val uri: String, val name: String, val size: Long)

/** New, immutable documents only. A provider readback proves its bytes, not remote cloud completion. */
internal class SafBackupDestination(context: Context, val tree: Uri) {
    private val resolver = context.applicationContext.contentResolver
    private val parent get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val isLocal get() = tree.authority == "com.android.externalstorage.documents"

    fun requirePermission() {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            throw SecurityException("Folder permission is unavailable")
        }
    }

    fun label(): String {
        requirePermission()
        return resolver.query(parent, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (!it.moveToFirst() || it.getString(1) != Document.MIME_TYPE_DIR) throw IOException("Folder is unavailable")
            it.getString(0).orEmpty()
        } ?: throw IOException("Folder is unavailable")
    }

    fun list(): List<FolderDocument> {
        requirePermission()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE),
            null, null, null)?.use { cursor ->
            val result = mutableListOf<FolderDocument>()
            while (cursor.moveToNext()) {
                if (result.size >= 20_000) throw IOException("Folder listing is too large")
                result += FolderDocument(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(),
                    cursor.getString(1).orEmpty(), if (cursor.isNull(2)) -1 else cursor.getLong(2))
            }
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null) throw IOException("Folder listing is incomplete")
            if (result.map { it.uri }.distinct().size != result.size) throw IOException("Ambiguous folder listing")
            result
        } ?: throw IOException("Folder listing failed")
    }

    fun create(permanent: Boolean, existing: Set<String>): Pair<Uri, String> {
        requirePermission()
        val name = "monica_backup_${System.currentTimeMillis()}_${UUID.randomUUID()}${if (permanent) "_permanent" else ""}.enc.zip"
        val uri = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("Provider did not create a backup")
        if (uri.toString() in existing || uri.authority != tree.authority ||
            !DocumentsContract.isChildDocument(resolver, parent, uri)) throw IOException("Provider did not create a new child document")
        return uri to name
    }

    fun write(uri: Uri, file: File, check: () -> Unit) {
        check()
        resolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    check()
                    val count = input.read(bytes)
                    if (count < 0) break
                    output.write(bytes, 0, count)
                }
            }
            check()
            output.flush()
        } ?: throw IOException("Provider did not open the backup")
        check()
    }

    fun verify(record: FolderBackupRecord, check: () -> Unit) {
        require(record.tree == tree.toString())
        requirePermission()
        val actual = resolver.openInputStream(Uri.parse(record.uri))?.use {
            FolderBackupPolicy.digest(it, record.size, check)
        } ?: throw IOException("Provider did not reopen the backup")
        if (actual != record.sha256) throw IOException("Backup verification failed")
    }

    fun deleteOwned(record: FolderBackupRecord, check: () -> Unit, beforeDelete: () -> Unit) {
        verify(record, check)
        beforeDelete()
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(record.uri))) throw IOException("Provider refused backup cleanup")
    }

    companion object {
        const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
