package takagi.ru.monica.attachments

import android.content.Context
import takagi.ru.monica.attachments.executor.BitwardenAttachmentExecutor
import takagi.ru.monica.attachments.executor.BitwardenAttachmentReconciler
import takagi.ru.monica.attachments.executor.KeePassAttachmentExecutor
import takagi.ru.monica.attachments.executor.KeePassAttachmentReconciler
import takagi.ru.monica.attachments.executor.LocalAttachmentExecutor
import takagi.ru.monica.attachments.facade.AttachmentBatchMoveAdvisor
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.repository.AttachmentRepository
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentPreviewCache
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService

/**
 * 附件子系统的进程级入口，用法：
 *
 * ```
 * val facade = AttachmentContainer.facade(context)
 * val advisor = AttachmentContainer.batchMoveAdvisor(context)
 * val reconciler = AttachmentContainer.bitwardenReconciler(context)
 * ```
 *
 * 设计要点：
 * - 所有重资源（Storage / Executor / Facade）按需懒加载，持有 applicationContext；
 * - 不依赖 Koin，避免与现有模块注入方式耦合；
 * - `KeePassKdbxService` 用来注入 [KeePassAttachmentExecutor]，如果业务侧已经通过别的
 *   路径创建了 service，可通过 [registerKeePassService] 替换默认实例。
 */
object AttachmentContainer {
    internal var originalKeePassStore: takagi.ru.monica.keepass.KeePassMonicaPasswordStore? = null
        private set
    internal fun bindOriginalKeePassStore(store: takagi.ru.monica.keepass.KeePassMonicaPasswordStore) {
        originalKeePassStore = store
        repositoryCache?.bindOriginalKeePassStore(store)
    }

    @Volatile private var appContext: Context? = null

    @Volatile private var repositoryCache: AttachmentRepository? = null
    @Volatile private var storageCache: AttachmentStorage? = null
    @Volatile private var keyVaultCache: AttachmentKeyVault? = null
    @Volatile private var previewCacheCache: AttachmentPreviewCache? = null

    @Volatile private var localExecutorCache: LocalAttachmentExecutor? = null
    @Volatile private var bitwardenExecutorCache: BitwardenAttachmentExecutor? = null
    @Volatile private var keepassExecutorCache: KeePassAttachmentExecutor? = null
    @Volatile private var advisorCache: AttachmentBatchMoveAdvisor? = null
    @Volatile private var reconcilerCache: BitwardenAttachmentReconciler? = null
    @Volatile private var keepassReconcilerCache: KeePassAttachmentReconciler? = null
    @Volatile private var facadeCache: AttachmentFacade? = null
    @Volatile private var mdbxVaultStoreCache: MdbxRepository? = null

    @Volatile private var keepassServiceOverride: KeePassKdbxService? = null
    @Volatile private var defaultKeePassServiceCache: KeePassKdbxService? = null

    fun facade(context: Context): AttachmentFacade {
        val app = ensureContext(context)
        facadeCache?.let { return it }
        synchronized(this) {
            facadeCache?.let { return it }
            val facade = AttachmentFacade(
                context = app,
                repository = repository(app),
                localExecutor = localExecutor(app),
                bitwardenExecutor = bitwardenExecutor(app),
                keepassExecutor = keepassExecutor(app),
                storage = storage(app),
                keyVault = keyVault(app),
                previewCache = previewCache(app),
                passwordEntryDao = PasswordDatabase.getDatabase(app).passwordEntryDao(),
                secureItemDao = PasswordDatabase.getDatabase(app).secureItemDao(),
                mdbxVaultStore = mdbxVaultStore(app),
                fileProviderAuthority = "${app.packageName}.fileprovider"
            )
            facadeCache = facade
            return facade
        }
    }

    fun batchMoveAdvisor(context: Context): AttachmentBatchMoveAdvisor {
        val app = ensureContext(context)
        advisorCache?.let { return it }
        synchronized(this) {
            advisorCache?.let { return it }
            val advisor = AttachmentBatchMoveAdvisor(repository(app))
            advisorCache = advisor
            return advisor
        }
    }

    fun bitwardenReconciler(context: Context): BitwardenAttachmentReconciler {
        val app = ensureContext(context)
        reconcilerCache?.let { return it }
        synchronized(this) {
            reconcilerCache?.let { return it }
            // BitwardenSyncService asks for this reconciler during repository
            // construction. Keep Attachment Room/storage out of that startup path;
            // the reconciler resolves them on its first real reconcile call.
            val reconciler = BitwardenAttachmentReconciler.lazy(
                repositoryProvider = { repository(app) },
                storageProvider = { storage(app) }
            )
            reconcilerCache = reconciler
            return reconciler
        }
    }

    fun keepassReconciler(context: Context): KeePassAttachmentReconciler {
        val app = ensureContext(context)
        keepassReconcilerCache?.let { return it }
        synchronized(this) {
            keepassReconcilerCache?.let { return it }
            val reconciler = KeePassAttachmentReconciler.create(
                repository = repository(app),
                executor = keepassExecutor(app),
                storage = storage(app)
            )
            keepassReconcilerCache = reconciler
            return reconciler
        }
    }

    fun repository(context: Context): AttachmentRepository {
        val app = ensureContext(context)
        repositoryCache?.let { return it }
        synchronized(this) {
            repositoryCache?.let { return it }
            val db = PasswordDatabase.getDatabase(app)
            val repo = AttachmentRepository(db.attachmentDao())
            originalKeePassStore?.let(repo::bindOriginalKeePassStore)
            repositoryCache = repo
            return repo
        }
    }

    fun registerKeePassService(service: KeePassKdbxService) {
        keepassServiceOverride = service
        keepassExecutorCache = null
        keepassReconcilerCache = null
        facadeCache = null
    }

    private fun storage(app: Context): AttachmentStorage =
        storageCache ?: synchronized(this) {
            storageCache ?: AttachmentStorage(app).also { storageCache = it }
        }

    private fun keyVault(app: Context): AttachmentKeyVault =
        keyVaultCache ?: synchronized(this) {
            keyVaultCache ?: AttachmentKeyVault(SecurityManager(app)).also { keyVaultCache = it }
        }

    private fun previewCache(app: Context): AttachmentPreviewCache =
        previewCacheCache ?: synchronized(this) {
            previewCacheCache ?: AttachmentPreviewCache(app).also { previewCacheCache = it }
        }

    private fun localExecutor(app: Context): LocalAttachmentExecutor =
        localExecutorCache ?: synchronized(this) {
            localExecutorCache ?: LocalAttachmentExecutor(
                context = app,
                storage = storage(app),
                keyVault = keyVault(app)
            ).also { localExecutorCache = it }
        }

    private fun bitwardenExecutor(app: Context): BitwardenAttachmentExecutor =
        bitwardenExecutorCache ?: synchronized(this) {
            bitwardenExecutorCache ?: BitwardenAttachmentExecutor(
                context = app,
                storage = storage(app),
                keyVault = keyVault(app)
            ).also { bitwardenExecutorCache = it }
        }

    private fun keepassExecutor(app: Context): KeePassAttachmentExecutor =
        keepassExecutorCache ?: synchronized(this) {
            keepassExecutorCache ?: run {
                KeePassAttachmentExecutor(
                    context = app,
                    kdbxServiceProvider = { keepassService(app) },
                    storage = storage(app),
                    keyVault = keyVault(app)
                ).also { keepassExecutorCache = it }
            }
        }

    private fun keepassService(app: Context): KeePassKdbxService =
        keepassServiceOverride ?: defaultKeePassServiceCache ?: synchronized(this) {
            keepassServiceOverride ?: defaultKeePassServiceCache ?: run {
                val db = PasswordDatabase.getDatabase(app)
                KeePassKdbxService(
                    context = app,
                    dao = db.localKeePassDatabaseDao(),
                    securityManager = SecurityManager(app)
                ).also { defaultKeePassServiceCache = it }
            }
        }

    private fun mdbxVaultStore(app: Context): MdbxRepository =
        mdbxVaultStoreCache ?: synchronized(this) {
            mdbxVaultStoreCache ?: run {
                val db = PasswordDatabase.getDatabase(app)
                MdbxRepositoryFactory.create(
                    context = app,
                    database = db,
                    securityManager = SecurityManager(app)
                ).also { mdbxVaultStoreCache = it }
            }
        }

    private fun ensureContext(context: Context): Context {
        val app = context.applicationContext
        if (appContext !== app) {
            synchronized(this) {
                if (appContext !== app) {
                    appContext = app
                }
            }
        }
        return app
    }
}
