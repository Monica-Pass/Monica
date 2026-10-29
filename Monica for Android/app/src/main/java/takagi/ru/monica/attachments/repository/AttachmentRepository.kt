package takagi.ru.monica.attachments.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.*
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.attachments.data.AttachmentDao
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource

/**
 * 附件元数据仓库，对 ViewModel / Facade 暴露 flow 优先、挂起风格的 API。
 *
 * 这里只负责 Room 层的读写事务，不处理加密、网络或 Bitwarden/KeePass 特化逻辑，
 * 那些职责在 storage / executor / facade 子包中。
 */
class AttachmentRepository(
    private val dao: AttachmentDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val originalStore = MutableStateFlow<KeePassMonicaPasswordStore?>(null)
    private val cachedOriginal = MutableStateFlow<Map<Long, Attachment>>(emptyMap())
    internal fun bindOriginalKeePassStore(store: KeePassMonicaPasswordStore) { originalStore.value = store }
    private val originalRows: Flow<Map<AttachmentOwner, List<Attachment>>> = originalStore.flatMapLatest { store ->
        store?.attachmentRows ?: flowOf(emptyMap())
    }.combine(cachedOriginal) { rows, cache -> rows.mapValues { (_, assets) -> assets.map { cache[it.id] ?: it } } }
    private fun Attachment.owner() = parentPasswordId?.let(AttachmentOwner::password)
        ?: AttachmentOwner.secureItem(requireNotNull(parentSecureItemId))
    private fun native(owner: AttachmentOwner) = KeePassMonicaPasswordStore.isUiId(owner.id)

    // ---------------------------------------------------------------- 读

    fun observeByPassword(passwordId: Long): Flow<List<Attachment>> =
        observe(AttachmentOwner.password(passwordId))

    fun observeBySecureItem(secureItemId: Long): Flow<List<Attachment>> =
        observe(AttachmentOwner.secureItem(secureItemId))

    fun observe(owner: AttachmentOwner): Flow<List<Attachment>> = if (native(owner)) originalRows.map { it[owner].orEmpty() } else when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.observeActiveByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.observeActiveBySecureItem(owner.id)
    }

    suspend fun listByPassword(passwordId: Long, includeDeleted: Boolean = false): List<Attachment> =
        list(AttachmentOwner.password(passwordId), includeDeleted)

    suspend fun listBySecureItem(
        secureItemId: Long,
        includeDeleted: Boolean = false
    ): List<Attachment> = list(AttachmentOwner.secureItem(secureItemId), includeDeleted)

    suspend fun list(
        owner: AttachmentOwner,
        includeDeleted: Boolean = false
    ): List<Attachment> = if (native(owner)) originalRows.first()[owner].orEmpty() else when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD ->
            if (includeDeleted) dao.getAllByParent(owner.id) else dao.getActiveByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM ->
            if (includeDeleted) dao.getAllBySecureItem(owner.id) else dao.getActiveBySecureItem(owner.id)
    }

    suspend fun getById(id: Long): Attachment? = if (KeePassMonicaPasswordStore.isUiId(id))
        originalRows.first().values.flatten().singleOrNull { it.id == id } else dao.getById(id)

    suspend fun getByBitwardenAttachmentId(attachmentId: String): Attachment? =
        dao.findByBitwardenAttachmentId(attachmentId)

    suspend fun listByParentAndSource(passwordId: Long, source: AttachmentSource): List<Attachment> =
        listByOwnerAndSource(AttachmentOwner.password(passwordId), source)

    suspend fun listByOwnerAndSource(
        owner: AttachmentOwner,
        source: AttachmentSource
    ): List<Attachment> = if (native(owner)) list(owner).filter { it.sourceEnum == source } else when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.getByParentAndSource(owner.id, source.name)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.getBySecureItemAndSource(owner.id, source.name)
    }

    suspend fun countActive(passwordId: Long): Int = countActive(AttachmentOwner.password(passwordId))

    suspend fun countActive(owner: AttachmentOwner): Int = if (native(owner)) list(owner).size else when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.countActiveByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.countActiveBySecureItem(owner.id)
    }

    /** 返回 [passwordIds] 中存在未软删附件的 id 集合。 */
    suspend fun idsWithActiveAttachments(passwordIds: List<Long>): Set<Long> {
        if (passwordIds.isEmpty()) return emptySet()
        return dao.parentsWithActiveAttachments(passwordIds.filterNot(KeePassMonicaPasswordStore::isUiId)).toSet() +
            originalRows.first().filter { it.key.kind == AttachmentOwner.Kind.PASSWORD && it.key.id in passwordIds && it.value.isNotEmpty() }.keys.map { it.id }
    }

    suspend fun secureItemIdsWithActiveAttachments(secureItemIds: List<Long>): Set<Long> {
        if (secureItemIds.isEmpty()) return emptySet()
        return dao.secureItemsWithActiveAttachments(secureItemIds.filterNot(KeePassMonicaPasswordStore::isUiId)).toSet() +
            originalRows.first().filter { it.key.kind == AttachmentOwner.Kind.SECURE_ITEM && it.key.id in secureItemIds && it.value.isNotEmpty() }.keys.map { it.id }
    }

    /** 返回当前数据库里所有仍被引用的本地密文文件相对路径。 */
    suspend fun allReferencedLocalPaths(): Set<String> =
        dao.selectAllLocalPaths().filterNotNull().toSet() + cachedOriginal.value.values.mapNotNull { it.localPath }

    /** 列出所有"本地已下载 + 未软删除"的附件（用于备份 / 迁移）。 */
    suspend fun listAllActiveLocalAttachments(): List<Attachment> =
        dao.selectAllActiveLocalAttachments()

    /**
     * 把某密码下 [fromSource] 的附件改写为 LOCAL（清 Bitwarden/KeePass 专属字段）。
     * 返回影响的行数。
     */
    suspend fun convertSourceToLocal(
        passwordId: Long,
        fromSource: AttachmentSource
    ): Int = convertSourceToLocal(AttachmentOwner.password(passwordId), fromSource)

    suspend fun convertSourceToLocal(
        owner: AttachmentOwner,
        fromSource: AttachmentSource
    ): Int {
        check(!native(owner)) { "Original KeePass attachment ownership must be copied before moving" }
        if (fromSource == AttachmentSource.LOCAL) return 0
        return when (owner.kind) {
            AttachmentOwner.Kind.PASSWORD -> dao.rewriteSourceToLocal(
                passwordId = owner.id,
                fromSource = fromSource.name,
                now = clock()
            )
            AttachmentOwner.Kind.SECURE_ITEM -> dao.rewriteSecureItemSourceToLocal(
                secureItemId = owner.id,
                fromSource = fromSource.name,
                now = clock()
            )
        }
    }

    // ---------------------------------------------------------------- 写

    suspend fun insert(attachment: Attachment): Long {
        if (native(attachment.owner())) {
            val id = requireNotNull(originalStore.value).attachmentId(attachment.owner(), requireNotNull(attachment.keepassBinaryRef))
            cachedOriginal.update { it + (id to attachment.copy(id = id)) }
            return id
        }
        return dao.insert(
        attachment.copy(
            createdAt = if (attachment.createdAt == 0L) clock() else attachment.createdAt,
            updatedAt = if (attachment.updatedAt == 0L) clock() else attachment.updatedAt
        )
    )
    }

    suspend fun update(attachment: Attachment): Int {
        if (KeePassMonicaPasswordStore.isUiId(attachment.id)) {
            check(getById(attachment.id) != null) { "Original KeePass attachment is unavailable" }
            cachedOriginal.update { it + (attachment.id to attachment.copy(updatedAt = clock())) }
            return 1
        }
        return dao.update(attachment.copy(updatedAt = clock()))
    }

    suspend fun markDownloadState(id: Long, state: AttachmentDownloadState) {
        if (KeePassMonicaPasswordStore.isUiId(id)) { getById(id)?.let { update(it.copy(downloadState = state.name)) }; return }
        dao.updateDownloadState(id, state.name, clock())
    }

    // ---------------------------------------------------------------- 删

    /** 永久删除单条附件的元数据，不处理底层密文文件的清理。 */
    suspend fun deleteById(id: Long): Int {
        if (KeePassMonicaPasswordStore.isUiId(id)) { cachedOriginal.update { it - id }; return 1 }
        return dao.deleteById(id)
    }

    /** 永久清空某个密码的所有附件元数据，用于密码永久删除级联。 */
    suspend fun purgeByPassword(passwordId: Long): Int = purge(AttachmentOwner.password(passwordId))

    suspend fun purge(owner: AttachmentOwner): Int = if (native(owner)) {
        val ids = cachedOriginal.value.values.filter { it.owner() == owner }.map { it.id }.toSet()
        cachedOriginal.update { it - ids }; ids.size
    } else when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.purgeByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.purgeBySecureItem(owner.id)
    }

    /** 随密码软删除一并软删附件（仅元数据标记，不释放密文）。 */
    suspend fun softDeleteByPassword(passwordId: Long): Int {
        return softDelete(AttachmentOwner.password(passwordId))
    }

    suspend fun softDelete(owner: AttachmentOwner): Int {
        if (native(owner)) return 0 // The KDBX recycle bin owns this lifecycle.
        val now = clock()
        return when (owner.kind) {
            AttachmentOwner.Kind.PASSWORD ->
                dao.softDeleteByParent(owner.id, deletedAt = now, updatedAt = now)
            AttachmentOwner.Kind.SECURE_ITEM ->
                dao.softDeleteBySecureItem(owner.id, deletedAt = now, updatedAt = now)
        }
    }

    /** 与密码从回收站恢复联动。 */
    suspend fun restoreByPassword(passwordId: Long): Int =
        restore(AttachmentOwner.password(passwordId))

    suspend fun restore(owner: AttachmentOwner): Int = if (native(owner)) 0 else when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.restoreByParent(owner.id, updatedAt = clock())
        AttachmentOwner.Kind.SECURE_ITEM -> dao.restoreBySecureItem(owner.id, updatedAt = clock())
    }
}
