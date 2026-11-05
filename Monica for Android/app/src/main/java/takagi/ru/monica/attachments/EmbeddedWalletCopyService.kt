package takagi.ru.monica.attachments

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.notes.domain.NoteContentCodec
import java.io.Closeable
import java.io.File

/** Prepares a full independent copy before changing any password fields. */
internal class EmbeddedWalletCopyService(context: Context) {
    private val context = context.applicationContext
    private val facade = AttachmentContainer.facade(this.context)

    data class Prepared(val snapshot: EmbeddedWalletContent.Snapshot, val assets: EmbeddedWalletAssetDraft) : Closeable {
        override fun close() = assets.close()
    }

    /** The caller supplies decrypted item data. Source database ids are only used during copying. */
    suspend fun prepare(item: SecureItem): Prepared {
        var completed: Prepared? = null
        try { return withContext(Dispatchers.IO) {
        require(item.id > 0 && !item.isDeleted)
        val owner = AttachmentOwner.secureItem(item.id)
        val keepassContext = item.keepassDatabaseId?.let { databaseId ->
            AttachmentFacade.KeePassContext(databaseId, requireNotNull(item.keepassEntryUuid?.takeIf(String::isNotBlank)))
        }
        if (keepassContext != null) {
            AttachmentContainer.keepassReconciler(context).reconcile(owner = owner,
                databaseId = keepassContext.databaseId, entryUuid = keepassContext.entryUuid,
                excludedFileNames = emptySet())
        }
        val bitwardenContext = item.bitwardenVaultId?.let { vaultId ->
            val vault = requireNotNull(PasswordDatabase.getDatabase(context).bitwardenVaultDao().getVaultById(vaultId))
            val repository = BitwardenRepository.getInstance(context)
            val snapshot = repository.fetchAttachmentCipherSnapshot(vault,
                requireNotNull(item.bitwardenCipherId?.takeIf(String::isNotBlank)))
            val fetched = requireNotNull(snapshot)
            try { facade.reconcileBitwardenAttachments(owner, fetched.attachments, fetched.context) }
            catch (error: Throwable) { fetched.context.wrappingKey.clear(); throw error }
            fetched.context
        }
        try {
        // The complete metadata list must exist before we claim that every attachment was copied.
        val attachments = facade.list(owner)
        val portableItem = if (item.itemType == ItemType.NOTE) {
            val decoded = NoteContentCodec.decodeFromItem(item)
            val raw = runCatching { Json.parseToJsonElement(item.itemData).jsonObject }.getOrNull()
            val encoded = NoteContentCodec.encode(decoded.content, decoded.tags, decoded.isMarkdown, decoded.customFields).first
            item.copy(itemData = JsonObject(Json.parseToJsonElement(encoded).jsonObject + (raw ?: emptyMap())).toString())
        } else item
        val data = Json.parseToJsonElement(portableItem.itemData).jsonObject
        val inlineIds = if (item.itemType == ItemType.NOTE)
            NoteContentCodec.extractInlineImageIds(NoteContentCodec.decodeFromItem(portableItem).content) else emptyList()
        val faceName = data["cardFace"]?.takeIf { it is JsonObject }?.jsonObject
            ?.get("imageAttachmentName")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        if (faceName != null) require(attachments.count { it.fileName == faceName } == 1) { "Card face is unavailable" }
        val managedNames = KeePassSecureItemPhotoAttachments.managedFileNames(item.itemType).toList()
        val sources = attachments.map { attachment ->
            val role = when (attachment.fileName) {
                faceName -> EmbeddedWalletContent.AssetRole.CARD_FACE
                managedNames.getOrNull(0) -> EmbeddedWalletContent.AssetRole.FRONT
                managedNames.getOrNull(1) -> EmbeddedWalletContent.AssetRole.BACK
                in inlineIds -> EmbeddedWalletContent.AssetRole.INLINE_IMAGE
                else -> EmbeddedWalletContent.AssetRole.ATTACHMENT
            }
            EmbeddedWalletAssetDraft.Source(attachment.fileName, attachment.mimeType, role,
                expectedSize = attachment.sizeBytes.takeIf { it >= 0 }, expectedSha256 = attachment.sha256Hex) {
                facade.copyAttachmentTo(attachment.id, it, bitwardenContext, keepassContext)
            }
        }.toMutableList()
        val imageManager = ImageManager(context)
        val imagePaths = (if (item.itemType == ItemType.NOTE) NoteContentCodec.decodeImagePaths(item.imagePaths)
            else LegacyImageAttachmentSupport.paths(item.imagePaths))
        val sourceNames = attachments.map { it.fileName }.toMutableList()
        (imagePaths + inlineIds).distinct().forEachIndexed { index, path ->
            if (path.isBlank()) return@forEachIndexed
            val name = if (item.itemType == ItemType.NOTE) path else managedNames.getOrNull(index)
            if (name != null && attachments.any { it.fileName == name }) return@forEachIndexed
            sourceNames += name ?: "Image ${index + 1}"
            sources += EmbeddedWalletAssetDraft.Source(name ?: "Image ${index + 1}", "application/octet-stream",
                if (item.itemType == ItemType.NOTE) EmbeddedWalletContent.AssetRole.INLINE_IMAGE else when (index) {
                    0 -> EmbeddedWalletContent.AssetRole.FRONT
                    1 -> EmbeddedWalletContent.AssetRole.BACK
                    else -> EmbeddedWalletContent.AssetRole.ATTACHMENT
                }) { output ->
                val bytes = checkNotNull(imageManager.readImageBytes(path)) { "Source image is unavailable" }
                try { output.write(bytes) } finally { bytes.fill(0) }
            }
        }
        val draft = EmbeddedWalletAssetDraft.prepare(File(context.cacheDir, "wallet-copy-drafts"), sources)
        try {
            var snapshot = EmbeddedWalletContent.create(portableItem).withAssets(draft.assets)
            if (faceName != null) {
                val index = attachments.indexOfFirst { it.fileName == faceName }
                snapshot = snapshot.edited(snapshot.title, snapshot.notes, buildJsonObject {
                    put("cardFace", buildJsonObject { put("imageAttachmentName", draft.assets[index].name) })
                })
            }
            if (item.itemType == ItemType.NOTE) {
                val content = NoteContentCodec.appendInlineImageRefs(NoteContentCodec.decodeFromItem(portableItem).content, imagePaths)
                val remapped = content.replace(Regex("monica-image://([^\\)\\s]+)")) { match ->
                    val name = match.groupValues[1]
                    require(sourceNames.count { it == name } == 1) { "Ambiguous note image" }
                    "monica-image://${draft.assets[sourceNames.indexOf(name)].name}"
                }
                snapshot = snapshot.edited(snapshot.title, snapshot.notes, buildJsonObject { put("content", remapped) })
            }
            Prepared(snapshot, draft).also { completed = it }
        } catch (error: Throwable) {
            draft.close()
            throw error
        }
        } finally { bitwardenContext?.wrappingKey?.clear() }
        } } catch (error: Throwable) { completed?.close(); throw error }
    }
}
