package takagi.ru.monica.data.dedup

import androidx.room.withTransaction
import java.net.URI
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordOwnership
import takagi.ru.monica.data.PasskeyOwnership
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.bitwarden.BitwardenVaultDao
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.isLocalOnlyPasskey
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.data.model.PaymentAccountData
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.StringResolver
import kotlin.coroutines.coroutineContext

internal class DedupMergeService(
    private val passwordRepository: PasswordRepository,
    private val secureItemRepository: SecureItemRepository,
    private val passkeyRepository: PasskeyRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao,
    private val localMdbxDatabaseDao: LocalMdbxDatabaseDao,
    private val bitwardenVaultDao: BitwardenVaultDao,
    private val securityManager: SecurityManager,
    private val strings: StringResolver,
    private val database: PasswordDatabase,
    private val attachmentSupport: DedupAttachmentSupport? = null
) : DedupMergeOperations {
    private val json = Json { ignoreUnknownKeys = true }
    private val compatibility = DedupMergeCompatibility(database, securityManager)
    private data class Review(val plan: DedupMergePlan, val evidence: String, val generation: Long, val epoch: Long, val planIntegrity: String)
    @Volatile private var review: Review? = null
    private val reviewLock = Any()
    private val operationEpoch = java.util.concurrent.atomic.AtomicLong()
    override fun invalidate() = synchronized(reviewLock) { operationEpoch.incrementAndGet(); review = null }
    private val executionMutex = kotlinx.coroutines.sync.Mutex()
    private val digestKey = javax.crypto.spec.SecretKeySpec(ByteArray(32).also(java.security.SecureRandom()::nextBytes), "HmacSHA256")

    private val mergeExecutor = DedupMergeExecutor(
        RepositoryDedupMergeWriter(
            passwordRepository = passwordRepository,
            secureItemRepository = secureItemRepository,
            customFieldRepository = customFieldRepository,
            passkeyRepository = passkeyRepository,
            database = database,
            attachmentSupport = attachmentSupport
        ),
        strings = strings
    )

    override suspend fun getSourceOptions(): List<DedupMergeSourceOption> {
        val entries = passwordRepository.getAllPasswordEntries().first()
        val secureItems = secureItemRepository.getAllItems().first()
        val passkeys = passkeyRepository.getAllPasskeysSync()
        return getSourceOptions(entries, secureItems, passkeys)
    }

    private suspend fun getSourceOptions(
        entries: List<PasswordEntry>, secureItems: List<SecureItem>, passkeys: List<PasskeyEntry>
    ): List<DedupMergeSourceOption> {
        val keepassDatabases = localKeePassDatabaseDao.getAllDatabasesSync()
        val mdbxDatabases = localMdbxDatabaseDao.getAvailableDatabasesSnapshot()
        val bitwardenVaults = bitwardenVaultDao.getAllVaults()
        val passwordCounts = entries.groupingBy(::sourceKeyOf).eachCount()
        val secureItemCounts = secureItems.groupingBy(::sourceKeyOf).eachCount()
        val passkeyCounts = passkeys.groupingBy(::sourceKeyOf).eachCount()

        return buildList {
            add(
                DedupMergeSourceOption(
                    key = SOURCE_MONICA,
                    kind = DedupMergeSourceKind.MONICA_LOCAL,
                    label = strings.get(R.string.database_source_local),
                    passwordCount = passwordCounts[SOURCE_MONICA] ?: 0,
                    secureItemCount = secureItemCounts[SOURCE_MONICA] ?: 0,
                    passkeyCount = passkeyCounts[SOURCE_MONICA] ?: 0
                )
            )
            mdbxDatabases.forEach { database ->
                val key = mdbxSourceKey(database.id)
                add(
                    DedupMergeSourceOption(
                        key = key,
                        kind = DedupMergeSourceKind.MDBX,
                        label = database.name.ifBlank { "MDBX ${database.id}" },
                        passwordCount = passwordCounts[key] ?: 0,
                        secureItemCount = secureItemCounts[key] ?: 0,
                        passkeyCount = passkeyCounts[key] ?: 0
                    )
                )
            }
            keepassDatabases.forEach { database ->
                val key = keepassSourceKey(database.id)
                add(
                    DedupMergeSourceOption(
                        key = key,
                        kind = DedupMergeSourceKind.KEEPASS,
                        label = database.name.ifBlank { "KeePass ${database.id}" },
                        passwordCount = passwordCounts[key] ?: 0,
                        secureItemCount = secureItemCounts[key] ?: 0,
                        passkeyCount = passkeyCounts[key] ?: 0
                    )
                )
            }
            bitwardenVaults.forEach { vault ->
                val key = bitwardenSourceKey(vault.id)
                add(
                    DedupMergeSourceOption(
                        key = key,
                        kind = DedupMergeSourceKind.BITWARDEN,
                        label = bitwardenLabel(vault),
                        passwordCount = passwordCounts[key] ?: 0,
                        secureItemCount = secureItemCounts[key] ?: 0,
                        passkeyCount = passkeyCounts[key] ?: 0
                    )
                )
            }
        }.sortedWith(compareBy<DedupMergeSourceOption> { it.kind.ordinal }.thenBy { it.label })
    }

    override suspend fun getTargetOptions(): List<DedupMergeTargetOption> {
        val entries = passwordRepository.getAllPasswordEntries().first()
        val secureItems = secureItemRepository.getAllItems().first()
        val passkeys = passkeyRepository.getAllPasskeysSync()
        val mdbxDatabases = localMdbxDatabaseDao.getAvailableDatabasesSnapshot()
        val passwordCounts = entries.groupingBy(::sourceKeyOf).eachCount()
        val secureCounts = secureItems.groupingBy(::sourceKeyOf).eachCount()
        val passkeyCounts = passkeys.groupingBy(::sourceKeyOf).eachCount()
        return buildList {
            add(
                DedupMergeTargetOption(
                    target = DedupMergeTarget.MonicaLocal,
                    sourceKey = SOURCE_MONICA,
                    label = strings.get(R.string.database_source_local),
                    passwordCount = passwordCounts[SOURCE_MONICA] ?: 0,
                    secureItemCount = secureCounts[SOURCE_MONICA] ?: 0,
                    passkeyCount = passkeyCounts[SOURCE_MONICA] ?: 0
                )
            )
            mdbxDatabases.forEach { database ->
                add(
                    DedupMergeTargetOption(
                        target = DedupMergeTarget.MdbxDatabase(
                            databaseId = database.id,
                            label = database.name.ifBlank { "MDBX ${database.id}" }
                        ),
                        sourceKey = mdbxSourceKey(database.id),
                        label = database.name.ifBlank { "MDBX ${database.id}" },
                        passwordCount = passwordCounts[mdbxSourceKey(database.id)] ?: 0,
                        secureItemCount = secureCounts[mdbxSourceKey(database.id)] ?: 0,
                        passkeyCount = passkeyCounts[mdbxSourceKey(database.id)] ?: 0
                    )
                )
            }
        }
    }

    override suspend fun buildPlan(
        selectedSourceKeys: Set<String>,
        target: DedupMergeTarget?,
        conflictPolicy: DedupConflictPolicy
    ): DedupMergePlan {
        val epoch = synchronized(reviewLock) { review = null; operationEpoch.incrementAndGet() }
        return scanPlan(selectedSourceKeys, target, conflictPolicy, epoch)
    }

    private suspend fun scanPlan(
        selectedSourceKeys: Set<String>, target: DedupMergeTarget?, conflictPolicy: DedupConflictPolicy, epoch: Long,
    ): DedupMergePlan {
        synchronized(reviewLock) {
            check(operationEpoch.get() == epoch) { strings.get(R.string.dedup_merge_changed_review) }
            review = null
        }
        val generation = takagi.ru.monica.security.SessionManager.lockGeneration.value
        check(takagi.ru.monica.security.SessionManager.isUnlocked.value) { strings.get(R.string.dedup_merge_changed_review) }
        val nativeStore = passwordRepository.monicaKeePassStore
        val native = mutableMapOf<Long, takagi.ru.monica.keepass.KeePassMonicaPasswordStore.Snapshot>()
        val unavailable = mutableSetOf<String>()
        for (key in selectedSourceKeys.filter { it.startsWith("keepass:") }) {
            coroutineContext.ensureActive()
            val id = key.removePrefix("keepass:").toLongOrNull()
            try {
                requireNotNull(id)
                native[id] = requireNotNull(nativeStore).refresh(id)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                unavailable += key
            }
        }
        val allEntries = passwordRepository.getAllPasswordEntries().first().filterNot { sourceKeyOf(it) in native.keys.map(::keepassSourceKey) } +
            native.values.flatMap { it.rows }
        val allSecureItems = secureItemRepository.getAllItems().first().filterNot { sourceKeyOf(it) in native.keys.map(::keepassSourceKey) } +
            native.values.flatMap { it.secureItems }
        val allPasskeys = passkeyRepository.getAllPasskeysSync().filterNot { sourceKeyOf(it) in native.keys.map(::keepassSourceKey) } +
            native.values.flatMap { it.passkeys }
        val sourceOptions = getSourceOptions(allEntries, allSecureItems, allPasskeys)
        val selectedOptions = sourceOptions.filter { it.key in selectedSourceKeys }
        if (selectedOptions.isEmpty() || target == null || target.sourceKey() in selectedSourceKeys) {
            review = null
            return DedupMergePlan(selectedSources = selectedOptions, target = target, conflictPolicy = conflictPolicy)
        }
        val retainedSources = selectedOptions.filter { it.kind == DedupMergeSourceKind.MDBX }
        val eligibleSourceKeys = selectedSourceKeys - retainedSources.map { it.key }.toSet()
        val sourceEntries = allEntries.filter { sourceKeyOf(it) in eligibleSourceKeys && !it.isDeleted }
        val sourceSecureItems = allSecureItems.filter { sourceKeyOf(it) in eligibleSourceKeys && !it.isDeleted }
        val sourcePasskeys = allPasskeys.filter { sourceKeyOf(it) in eligibleSourceKeys }
        val targetEntries = allEntries.filter { it.belongsToTarget(target) && !it.isDeleted }
        val targetSecureItems = allSecureItems.filter { it.belongsToTarget(target) && !it.isDeleted }
        val (deviceSync, syncAttachmentRows) = database.withTransaction {
            val audit = SingleVaultDedupDeviceSyncAudit.read(database)
            audit to database.singleVaultDedupLocalDao().attachments().filter { it.id in audit.attachmentIds }
        }
        // The copy writer does not migrate historical values. Keep those source projects
        // intact and include their history in the reviewed evidence instead of silently flattening it.
        val relevantPasswordIds = (sourceEntries + targetEntries).filter { it.keepassDatabaseId == null }.map { it.id }.toSet()
        val relevantItemKeys = (sourceSecureItems + targetSecureItems).filter { it.keepassDatabaseId == null }
            .map { it.itemType.name to it.id }.toSet()
        suspend fun readHistory() = database.withTransaction {
            val dao = database.singleVaultDedupLocalDao()
            dao.history().filter { it.entryId in relevantPasswordIds } to dao.timeline().filter {
                if (it.itemType == "PASSWORD") it.itemId in relevantPasswordIds else (it.itemType to it.itemId) in relevantItemKeys
            }
        }
        val historicalContent = readHistory()
        val historyPasswordIds = historicalContent.first.map { it.entryId }.toSet() +
            historicalContent.second.filter { it.itemType == "PASSWORD" }.map { it.itemId }
        val historyItemKeys = historicalContent.second.filter { it.itemType != "PASSWORD" }.map { it.itemType to it.itemId }.toSet()
        val syncPasswordIds = deviceSync.passwordIds + syncAttachmentRows.filter { it.id in deviceSync.attachmentIds }.mapNotNull { it.parentPasswordId }
        val syncItemIds = deviceSync.itemIds + syncAttachmentRows.filter { it.id in deviceSync.attachmentIds }.mapNotNull { it.parentSecureItemId }
        val customFieldsByEntry = customFieldRepository.getFieldsByEntryIds((sourceEntries + targetEntries)
            .filterNot { takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(it.id) }.map { it.id }).toMutableMap()
        native.values.forEach { customFieldsByEntry.putAll(it.customFields) }
        val attachments = attachmentSupport?.snapshot(sourceEntries + targetEntries, sourceSecureItems + targetSecureItems)
        val unsupported = mutableListOf<DedupMergeUnsupportedItem>()
        val rawBitwarden = mutableMapOf<String, String>()
        val invalidPasswords = mutableSetOf<Long>()
        val invalidSecure = mutableSetOf<Long>()
        val passwordContent = mutableMapOf<Long, List<String>>()
        val secureContent = mutableMapOf<Long, List<String>>()
        val secureTargetData = mutableMapOf<Long, String>()
        val passwordIdentities = mutableMapOf<Long, String>()
        val secureItemIdentities = mutableMapOf<Long, String>()
        val passkeyLinkedPasswordIds = allPasskeys.mapNotNullTo(mutableSetOf()) { it.boundPasswordId }
        val passkeyLinkedItemIds = mutableSetOf<Long>()
        suspend fun supportedBitwarden(vaultId: Long?, cipherId: String?, revision: String?, modified: Boolean, expectedType: Int): Boolean {
            if (vaultId == null) return true
            val raw = compatibility.bitwarden(vaultId, cipherId, revision, modified) {
                (sourceEntries + targetEntries).filter { it.bitwardenVaultId == vaultId && it.bitwardenCipherId == cipherId }
                    .mapTo(passkeyLinkedPasswordIds) { it.id }
            } ?: return false
            if (raw.isNotEmpty() && raw["type"] != JsonPrimitive(expectedType)) return false
            rawBitwarden["$vaultId:$cipherId:$revision"] = raw.toString()
            return true
        }
        fun nativeRecord(databaseId: Long?, uuid: String?) = databaseId?.let { native[it] }?.sourceBrowser?.entries
            ?.singleOrNull { it.identity.entryUuid.toString() == uuid }
        val opaquePasskeyPasswordIds = (sourceEntries + targetEntries).filter { entry ->
            entry.loginType.trim().equals("PASSKEY", ignoreCase = true) || customFieldsByEntry[entry.id].orEmpty().any {
                it.title.contains("passkey", ignoreCase = true) || it.title.contains("fido", ignoreCase = true)
            }
        }.mapTo(mutableSetOf()) { it.id }
        passkeyLinkedPasswordIds += opaquePasskeyPasswordIds
        (sourceEntries + targetEntries).filter { entry ->
            entry.passkeyBindings.isNotEmpty() ||
                entry.keepassDatabaseId != null && nativeRecord(entry.keepassDatabaseId, entry.keepassEntryUuid)?.kind ==
                    takagi.ru.monica.keepass.KeePassNativeEntryKind.PASSKEY ||
                entry.bitwardenVaultId != null && !entry.bitwardenCipherId.isNullOrEmpty() && allPasskeys.any {
                    it.bitwardenVaultId == entry.bitwardenVaultId && it.bitwardenCipherId == entry.bitwardenCipherId
                }
        }.mapTo(passkeyLinkedPasswordIds) { it.id }
        fun assetParts(refs: List<DedupAttachmentRef>): List<String> {
            require(refs.all { it.readable })
            return listOf("attachments", refs.size.toString()) + refs.map { it.contentKey }.sorted()
        }
        for (entry in sourceEntries + targetEntries) {
            try {
                coroutineContext.ensureActive()
                val explicitNestedPasskey = DedupMergeContent.drafts(customFieldsByEntry[entry.id].orEmpty(), ::decryptComparablePassword).any { field ->
                    val structured = runCatching { SingleVaultDedupProjectBuilder.strictJson(field.value) }.getOrNull()
                    structured != null && DedupStructuredPasskeyPolicy.containsPasskeyContent(
                        SingleVaultDedupLocalContent.decodeJson(structured, ::decryptComparablePassword))
                }
                if (explicitNestedPasskey) passkeyLinkedPasswordIds += entry.id
                require(sourceKeyOf(entry) !in unavailable && compatibility.password(entry))
                require(entry.id !in historyPasswordIds)
                require(entry.id !in passkeyLinkedPasswordIds)
                require(sourceKeyOf(entry) != SOURCE_MONICA || deviceSync.complete && entry.id !in syncPasswordIds)
                require(supportedBitwarden(entry.bitwardenVaultId, entry.bitwardenCipherId, entry.bitwardenRevisionDate, entry.bitwardenLocalModified, entry.bitwardenCipherType))
                if (entry.keepassDatabaseId != null) {
                    val record = requireNotNull(nativeRecord(entry.keepassDatabaseId, entry.keepassEntryUuid))
                    require(compatibility.nativePassword(record, entry, customFieldsByEntry[entry.id].orEmpty()) &&
                        compatibility.nativeAncestors(record, native.getValue(entry.keepassDatabaseId).sourceBrowser))
                }
                passwordIdentities[entry.id] = mergeIdentityKey(entry, customFieldsByEntry[entry.id].orEmpty())
                passwordContent[entry.id] = DedupMergeContent.password(entry, customFieldsByEntry[entry.id].orEmpty(), ::decryptComparablePassword) +
                    assetParts(attachments?.password(entry.id).orEmpty())
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                invalidPasswords += entry.id
            }
        }
        for (item in sourceSecureItems + targetSecureItems) {
            try {
                coroutineContext.ensureActive()
                require(sourceKeyOf(item) !in unavailable && (item.replicaGroupId == null || item.mdbxDatabaseId != null))
                require((item.itemType.name to item.id) !in historyItemKeys &&
                    compatibility.secure(item, attachments?.secureItemFileNames(item.id).orEmpty()) { passkeyLinkedItemIds += item.id })
                require(sourceKeyOf(item) != SOURCE_MONICA || deviceSync.complete && item.id !in syncItemIds)
                require(supportedBitwarden(item.bitwardenVaultId, item.bitwardenCipherId, item.bitwardenRevisionDate, item.bitwardenLocalModified,
                    if (item.itemType == ItemType.NOTE) 2 else -1))
                if (item.keepassDatabaseId != null) {
                    val record = requireNotNull(nativeRecord(item.keepassDatabaseId, item.keepassEntryUuid))
                    require(compatibility.nativeSecure(record, item) && compatibility.nativeAncestors(record,
                        native.getValue(item.keepassDatabaseId).sourceBrowser))
                }
                secureItemIdentities[item.id] = mergeIdentityKey(item)
                secureContent[item.id] = DedupMergeContent.secure(item, ::decryptComparablePassword,
                    attachments?.imageKey(item.imagePaths) ?: item.imagePaths) + assetParts(attachments?.secureItem(item.id).orEmpty())
                if (target is DedupMergeTarget.MdbxDatabase && sourceKeyOf(item) in eligibleSourceKeys) {
                    secureTargetData[item.id] = prepareMdbxItemData(item)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                invalidSecure += item.id
            }
        }
        val content = resolveMergeContent(
            sourceEntries, targetEntries, sourceSecureItems, targetSecureItems, sourceOptions, target, conflictPolicy,
            customFieldsByEntry, attachments, native, unavailable, invalidPasswords, invalidSecure, passwordContent,
            secureContent, secureTargetData, passwordIdentities, secureItemIdentities, passkeyLinkedPasswordIds,
            passkeyLinkedItemIds, unsupported)
        // User policy: every Passkey stays at its source; never resolve or compare private keys.
        val resolvedPasskeys = DedupPasskeyPlanner().build(sourcePasskeys) { labelForSourceKey(sourceKeyOf(it), sourceOptions) }
        val skippedPasskeys = sourcePasskeys.size
        // A changed source/lock while obtaining fields or attachments invalidates the scan.
        check(takagi.ru.monica.security.SessionManager.lockGeneration.value == generation) { strings.get(R.string.dedup_merge_changed_review) }
        native.forEach { (id, snapshot) ->
            val current = try { nativeStore?.refresh(id) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                error(strings.get(R.string.dedup_merge_changed_review))
            }
            check(current?.sourceBrowser?.sourceRevision == snapshot.sourceBrowser.sourceRevision && current.lockVersion == snapshot.lockVersion &&
                nativeStore?.isDatabaseAvailable(id) == true) { strings.get(R.string.dedup_merge_changed_review) }
        }
        val observedEntries = (sourceEntries + targetEntries).filterNot { it.keepassDatabaseId != null }
        val currentEntries = passwordRepository.getAllPasswordEntries().first().filter { sourceKeyOf(it) in eligibleSourceKeys + target.sourceKey() && !it.isDeleted && it.keepassDatabaseId == null }
        check(observedEntries.sortedBy { it.id } == currentEntries.sortedBy { it.id }) { strings.get(R.string.dedup_merge_changed_review) }
        val currentFields = customFieldRepository.getFieldsByEntryIds(observedEntries.map { it.id })
        check(observedEntries.all { currentFields[it.id].orEmpty() == customFieldsByEntry[it.id].orEmpty() }) { strings.get(R.string.dedup_merge_changed_review) }
        val observedSecure = (sourceSecureItems + targetSecureItems).filter { it.keepassDatabaseId == null }
        val currentSecure = secureItemRepository.getAllItems().first().filter { sourceKeyOf(it) in eligibleSourceKeys + target.sourceKey() && !it.isDeleted && it.keepassDatabaseId == null }
        check(observedSecure.sortedBy { it.id } == currentSecure.sortedBy { it.id }) { strings.get(R.string.dedup_merge_changed_review) }
        check(database.withTransaction {
            SingleVaultDedupDeviceSyncAudit.read(database) == deviceSync &&
                database.singleVaultDedupLocalDao().attachments().filter { it.id in deviceSync.attachmentIds } == syncAttachmentRows
        }) { strings.get(R.string.dedup_merge_changed_review) }
        check(readHistory() == historicalContent) { strings.get(R.string.dedup_merge_changed_review) }
        val observedKeys = allPasskeys.filter { it.keepassDatabaseId == null && sourceKeyOf(it) in eligibleSourceKeys + target.sourceKey() }
        val currentKeys = passkeyRepository.getAllPasskeysSync().filter { it.keepassDatabaseId == null && sourceKeyOf(it) in eligibleSourceKeys + target.sourceKey() }
        check(observedKeys.sortedBy { it.id } == currentKeys.sortedBy { it.id }) { strings.get(R.string.dedup_merge_changed_review) }
        val evidence = buildReviewEvidence(
            listOf(generation.toString(), target.sourceKey(), conflictPolicy.name, "device-sync", deviceSync.revision,
            "history", historicalContent.toString()), selectedSourceKeys, eligibleSourceKeys, target,
            sourceEntries, targetEntries, sourceSecureItems, targetSecureItems, allPasskeys, customFieldsByEntry,
            passwordContent, secureContent, native, rawBitwarden)
        val plan = buildReviewedPlan(selectedOptions, target, conflictPolicy, sourceEntries, sourceSecureItems,
            sourcePasskeys, skippedPasskeys, content, resolvedPasskeys, unsupported, retainedSources)
        coroutineContext.ensureActive()
        synchronized(reviewLock) {
            check(operationEpoch.get() == epoch && takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                takagi.ru.monica.security.SessionManager.lockGeneration.value == generation) { strings.get(R.string.dedup_merge_changed_review) }
            review = Review(plan, evidence, generation, epoch, fingerprint(listOf(plan.toString())))
        }
        return plan
    }

    // Keep collection-heavy pure work out of the suspend state machine. All reads and
    // session/revision checks remain in scanPlan, in their original order.
    private data class ResolvedMergeContent(
        val passwords: List<DedupResolvedPassword>,
        val secureItems: List<DedupResolvedSecureItem>,
        val passkeyRetainedProjects: List<DedupPasskeyRetainedProject>
    )

    private fun resolveMergeContent(
        sourceEntries: List<PasswordEntry>,
        targetEntries: List<PasswordEntry>,
        sourceSecureItems: List<SecureItem>,
        targetSecureItems: List<SecureItem>,
        sourceOptions: List<DedupMergeSourceOption>,
        target: DedupMergeTarget,
        conflictPolicy: DedupConflictPolicy,
        customFieldsByEntry: Map<Long, List<CustomField>>,
        attachments: DedupAttachmentSupport.Snapshot?,
        native: Map<Long, takagi.ru.monica.keepass.KeePassMonicaPasswordStore.Snapshot>,
        unavailable: Set<String>,
        invalidPasswords: MutableSet<Long>,
        invalidSecure: Set<Long>,
        passwordContent: Map<Long, List<String>>,
        secureContent: Map<Long, List<String>>,
        secureTargetData: Map<Long, String>,
        passwordIdentities: Map<Long, String>,
        secureItemIdentities: Map<Long, String>,
        passkeyLinkedPasswordIds: MutableSet<Long>,
        passkeyLinkedItemIds: Set<Long>,
        unsupported: MutableList<DedupMergeUnsupportedItem>
    ): ResolvedMergeContent {
        val projectKeys = mutableMapOf<Long, String>()
        val projectClaims = mutableMapOf<Long, Set<String>>()
        val opaqueProjectSources = mutableSetOf<String>()
        for (entry in sourceEntries) {
            val claims = mutableSetOf<String>()
            entry.passwordGroupId?.let(claims::add)
            try {
                val fields = DedupMergeContent.drafts(customFieldsByEntry[entry.id].orEmpty(), ::decryptComparablePassword)
                fields.filter { it.title == takagi.ru.monica.data.model.ProjectCredentialGroup.FIELD }.forEach { field ->
                    val metadata = kotlinx.serialization.json.Json.parseToJsonElement(field.value).jsonObject
                    metadata["projectId"]?.takeIf { it != kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.let {
                        require(it.isString && it.content.isNotEmpty()); claims += it.content
                    }
                }
                projectClaims[entry.id] = claims
                val project = DedupMergeContent.projectId(entry, fields)
                projectKeys[entry.id] = sourceKeyOf(entry) + ":" + (project?.let { "project:$it" } ?: "row:${entry.id}")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                invalidPasswords += entry.id
                if (!projectClaims.containsKey(entry.id)) opaqueProjectSources += sourceKeyOf(entry)
                projectClaims[entry.id] = claims
                projectKeys[entry.id] = sourceKeyOf(entry) + ":" + (entry.passwordGroupId?.let { "project:$it" } ?: "row:${entry.id}")
            }
        }
        // Inconsistent ownership must retain every connected sibling, including both sides of a conflicting ID.
        val affectedClaims = mutableSetOf<Pair<String, String>>()
        val passkeyClaims = mutableSetOf<Pair<String, String>>()
        var changed: Boolean
        do {
            sourceEntries.filter { it.id in invalidPasswords }.forEach { entry ->
                projectClaims[entry.id].orEmpty().forEach { affectedClaims += sourceKeyOf(entry) to it }
            }
            sourceEntries.filter { it.id in passkeyLinkedPasswordIds }.forEach { entry ->
                projectClaims[entry.id].orEmpty().forEach { passkeyClaims += sourceKeyOf(entry) to it }
            }
            changed = false
            sourceEntries.forEach { entry ->
                if (projectClaims[entry.id].orEmpty().any { (sourceKeyOf(entry) to it) in passkeyClaims })
                    if (passkeyLinkedPasswordIds.add(entry.id)) changed = true
                if (sourceKeyOf(entry) in opaqueProjectSources || projectClaims[entry.id].orEmpty().any { (sourceKeyOf(entry) to it) in affectedClaims })
                    if (invalidPasswords.add(entry.id)) changed = true
            }
        } while (changed)
        val passkeyRetainedProjects = sourceEntries.filter { it.id in passkeyLinkedPasswordIds }
            .groupBy { projectKeys.getValue(it.id) }.map { (projectKey, members) ->
                val entry = members.sortedWith(compareByDescending<PasswordEntry> { it.isGroupCover }.thenBy { it.id }).first()
                DedupPasskeyRetainedProject(sourceKeyOf(entry), labelForSourceKey(sourceKeyOf(entry), sourceOptions),
                    projectKey, entry.title, members.map { it.id }.sorted())
            } + sourceSecureItems.filter { it.id in passkeyLinkedItemIds }.map { item ->
                DedupPasskeyRetainedProject(sourceKeyOf(item), labelForSourceKey(sourceKeyOf(item), sourceOptions),
                    "${sourceKeyOf(item)}:item:${item.itemType.name}:${item.id}", item.title, emptyList(), listOf(item.id))
            }
        val clonedProjects = mutableMapOf<Long, Pair<String?, List<CustomField>>>()
        val atomicProjectKeys = mutableMapOf<Long, String>()
        for ((key, members) in sourceEntries.groupBy { projectKeys.getValue(it.id) }) {
            try {
                require(members.none { it.id in invalidPasswords })
                val needsAtomic = members.size > 1
                // External-file side effects cannot be rolled back by a Room project transaction.
                if (needsAtomic) require(target == DedupMergeTarget.MonicaLocal &&
                    members.all { attachments?.password(it.id).orEmpty().isEmpty() })
                val clone = DedupMergeContent.cloneProject(members, customFieldsByEntry,
                    ::decryptComparablePassword, securityManager::encryptData)
                if (target is DedupMergeTarget.MdbxDatabase) {
                    // The generic native writer copies field values verbatim. Until it
                    // has an explicit independent field format, do not publish a copy
                    // that still needs the source Monica key to interpret its fields.
                    require(clone.values.all { (_, fields) -> fields.none { field ->
                        securityManager.looksLikeMonicaCiphertext(field.value) ||
                            runCatching { containsMonicaCiphertext(json.parseToJsonElement(field.value)) }.getOrDefault(false)
                    } })
                }
                clonedProjects.putAll(clone)
                if (needsAtomic) members.forEach { atomicProjectKeys[it.id] = key }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                members.forEach { invalidPasswords += it.id }
            }
        }
        sourceEntries.filter { it.id in invalidPasswords }.forEach {
            unsupported += DedupMergeUnsupportedItem(labelForSourceKey(sourceKeyOf(it), sourceOptions), it.title)
        }
        sourceSecureItems.filter { it.id in invalidSecure }.forEach {
            unsupported += DedupMergeUnsupportedItem(labelForSourceKey(sourceKeyOf(it), sourceOptions), it.title)
        }
        unavailable.filter { key -> sourceEntries.none { sourceKeyOf(it) == key } && sourceSecureItems.none { sourceKeyOf(it) == key } }
            .forEach { unsupported += DedupMergeUnsupportedItem(labelForSourceKey(it, sourceOptions), "") }
        // Native entries without a Monica projection still count as retained, never as an empty database.
        for ((id, snapshot) in native) {
            val projected = (snapshot.rows.mapNotNull { it.keepassEntryUuid } + snapshot.secureItems.mapNotNull { it.keepassEntryUuid } +
                snapshot.sourceBrowser.entries.filter { it.kind == takagi.ru.monica.keepass.KeePassNativeEntryKind.PASSKEY }.map { it.identity.entryUuid.toString() }).toSet()
            snapshot.sourceBrowser.entries.filter { !it.isInRecycleBin && it.identity.entryUuid.toString() !in projected }.forEach {
                unsupported += DedupMergeUnsupportedItem(labelForSourceKey(keepassSourceKey(id), sourceOptions), it.title)
            }
        }
        val targetContents = targetEntries.mapNotNull { passwordContent[it.id] }.toSet()
        val targetSecureContents = targetSecureItems.mapNotNull { secureContent[it.id] }.toSet()
        val sameIdentity = sourceEntries.filterNot { it.id in invalidPasswords }.groupBy { passwordIdentities.getValue(it.id) }
        val targetIdentities = targetEntries.filterNot { it.id in invalidPasswords }.map { passwordIdentities.getValue(it.id) }.toSet()
        val resolvedPasswords = sourceEntries.filterNot { it.id in invalidPasswords }
            // Project members are indivisible even if two encrypted password rows are identical.
            .groupBy { entry -> passwordContent.getValue(entry.id) + if (atomicProjectKeys.containsKey(entry.id)) listOf("member", entry.id.toString()) else emptyList() }
            .values.map { variants ->
                val keeper = selectBestPassword(variants, customFieldsByEntry, conflictPolicy)
                val identity = passwordIdentities.getValue(keeper.id)
                val project = clonedProjects.getValue(keeper.id)
                val atomic = atomicProjectKeys[keeper.id]
                val exists = target !is DedupMergeTarget.MdbxDatabase && atomic == null && passwordContent.getValue(keeper.id) in targetContents
                DedupResolvedPassword(mergeKey = fingerprint(passwordContent.getValue(keeper.id)),
                    entry = buildTargetEntry(keeper, target).copy(passwordGroupId = project.first), customFields = project.second,
                    sourceEntryIds = variants.map { it.id }, sourceLabels = variants.map { labelForSourceKey(sourceKeyOf(it), sourceOptions) }.distinct(),
                    conflictFields = if (sameIdentity[identity].orEmpty().mapNotNull { passwordContent[it.id] }.distinct().size > 1)
                        setOf(strings.get(R.string.content)) else emptySet(),
                    existsInTarget = exists, targetHasDifferentContent = target !is DedupMergeTarget.MdbxDatabase && !exists && identity in targetIdentities,
                    preferredSourceLabel = labelForSourceKey(sourceKeyOf(keeper), sourceOptions),
                    attachments = attachments?.password(keeper.id).orEmpty(), projectCopyKey = atomic)
            }.sortedWith(compareBy<DedupResolvedPassword> { it.existsInTarget }.thenBy { it.entry.title }.thenBy { it.entry.username })
        val secureIdentities = sourceSecureItems.filterNot { it.id in invalidSecure }.groupBy { secureItemIdentities.getValue(it.id) }
        val targetSecureIdentities = targetSecureItems.filterNot { it.id in invalidSecure }.map { secureItemIdentities.getValue(it.id) }.toSet()
        val resolvedSecureItems = sourceSecureItems.filterNot { it.id in invalidSecure }.groupBy { secureContent.getValue(it.id) }.values.map { variants ->
            val keeper = selectBestSecureItem(variants, conflictPolicy)
            val identity = secureItemIdentities.getValue(keeper.id)
            val exists = target !is DedupMergeTarget.MdbxDatabase && secureContent.getValue(keeper.id) in targetSecureContents
            DedupResolvedSecureItem(mergeKey = fingerprint(secureContent.getValue(keeper.id)),
                item = buildTargetItem(keeper, target).copy(itemData = secureTargetData[keeper.id] ?: keeper.itemData),
                sourceItemIds = variants.map { it.id }, sourceLabels = variants.map { labelForSourceKey(sourceKeyOf(it), sourceOptions) }.distinct(),
                conflictFields = if (secureIdentities[identity].orEmpty().mapNotNull { secureContent[it.id] }.distinct().size > 1)
                    setOf(strings.get(R.string.content)) else emptySet(), existsInTarget = exists,
                targetHasDifferentContent = target !is DedupMergeTarget.MdbxDatabase && !exists && identity in targetSecureIdentities,
                preferredSourceLabel = labelForSourceKey(sourceKeyOf(keeper), sourceOptions), attachments = attachments?.secureItem(keeper.id).orEmpty())
        }.sortedWith(compareBy<DedupResolvedSecureItem> { it.existsInTarget }.thenBy { it.item.title })
        return ResolvedMergeContent(resolvedPasswords, resolvedSecureItems, passkeyRetainedProjects)
    }

    private fun buildReviewEvidence(
        reviewContext: List<String>,
        selectedSourceKeys: Set<String>,
        eligibleSourceKeys: Set<String>,
        target: DedupMergeTarget,
        sourceEntries: List<PasswordEntry>,
        targetEntries: List<PasswordEntry>,
        sourceSecureItems: List<SecureItem>,
        targetSecureItems: List<SecureItem>,
        allPasskeys: List<PasskeyEntry>,
        customFieldsByEntry: Map<Long, List<CustomField>>,
        passwordContent: Map<Long, List<String>>,
        secureContent: Map<Long, List<String>>,
        native: Map<Long, takagi.ru.monica.keepass.KeePassMonicaPasswordStore.Snapshot>,
        rawBitwarden: Map<String, String>
    ): String {
        return fingerprint(reviewContext + selectedSourceKeys.sorted() +
            (sourceEntries + targetEntries).sortedBy { it.id }.flatMap { listOf("row", it.id.toString(), sourceKeyOf(it), it.updatedAt.time.toString(),
                it.categoryId.toString(), it.mdbxFolderId.orEmpty(), it.keepassGroupUuid.orEmpty(), it.bitwardenFolderId.orEmpty()) +
                (passwordContent[it.id] ?: listOf("unreadable")) + listOf("raw-row", it.toString(), it.passwordGroupId.orEmpty(),
                    "raw-fields", customFieldsByEntry[it.id].orEmpty().size.toString()) +
                customFieldsByEntry[it.id].orEmpty().flatMap { field -> listOf(field.id.toString(), field.title, field.value,
                    field.isProtected.toString(), field.sortOrder.toString()) } } +
            (sourceSecureItems + targetSecureItems).sortedBy { it.id }.flatMap { listOf("secure", it.id.toString(), sourceKeyOf(it), it.updatedAt.time.toString()) +
                (secureContent[it.id] ?: listOf("unreadable")) + if (it.keepassDatabaseId == null) listOf("raw-item", it.toString()) else emptyList() } +
            allPasskeys.filter { sourceKeyOf(it) in eligibleSourceKeys + target.sourceKey() }.sortedBy { it.id }.map { it.toString() } +
            native.toSortedMap().flatMap { (id, value) -> listOf(id.toString(), value.sourceBrowser.sourceRevision.sha256, value.lockVersion.toString()) } +
            rawBitwarden.toSortedMap().flatMap { listOf(it.key, it.value) })
    }

    private fun buildReviewedPlan(
        selectedOptions: List<DedupMergeSourceOption>,
        target: DedupMergeTarget,
        conflictPolicy: DedupConflictPolicy,
        sourceEntries: List<PasswordEntry>,
        sourceSecureItems: List<SecureItem>,
        sourcePasskeys: List<PasskeyEntry>,
        skippedPasskeys: Int,
        content: ResolvedMergeContent,
        resolvedPasskeys: List<DedupResolvedPasskey>,
        unsupported: List<DedupMergeUnsupportedItem>,
        retainedSources: List<DedupMergeSourceOption>
    ): DedupMergePlan {
        val resolvedPasswords = content.passwords
        val resolvedSecureItems = content.secureItems
        val passkeyRetainedProjects = content.passkeyRetainedProjects
        return DedupMergePlan(selectedSources = selectedOptions, target = target, conflictPolicy = conflictPolicy,
            totalSourcePasswords = sourceEntries.size, totalSourceSecureItems = sourceSecureItems.size,
            totalSourcePasskeys = sourcePasskeys.size, unsupportedSourcePasskeys = skippedPasskeys,
            uniquePasswords = resolvedPasswords.size, uniqueSecureItems = resolvedSecureItems.size,
            duplicateGroups = resolvedPasswords.count { it.sourceEntryIds.size > 1 }, duplicateSecureItemGroups = resolvedSecureItems.count { it.sourceItemIds.size > 1 },
            passwordConflictGroups = resolvedPasswords.count { it.conflictFields.isNotEmpty() }, secureItemConflictGroups = resolvedSecureItems.count { it.conflictFields.isNotEmpty() },
            targetExistingDuplicates = resolvedPasswords.count { it.existsInTarget }, targetExistingSecureItems = resolvedSecureItems.count { it.existsInTarget },
            previewPasswords = resolvedPasswords, previewSecureItems = resolvedSecureItems, previewPasskeys = resolvedPasskeys, unsupportedItems = unsupported,
            retainedSources = retainedSources, passkeyRetainedProjects = passkeyRetainedProjects,
            mdbxTargetAddsIndependentCopies = target is DedupMergeTarget.MdbxDatabase,
            reviewToken = java.util.UUID.randomUUID().toString(), warnings = buildList {
                if (unsupported.isNotEmpty()) add(strings.get(R.string.dedup_merge_incompatible_source, unsupported.size))
                if (resolvedPasswords.any { it.conflictFields.isNotEmpty() } || resolvedSecureItems.any { it.conflictFields.isNotEmpty() }) add(strings.get(R.string.dedup_merge_variants_preserved))
                if (skippedPasskeys > 0) add(strings.get(R.string.dedup_merge_warning_passkeys, skippedPasskeys))
                if (sourceEntries.isEmpty() && sourceSecureItems.isEmpty() && sourcePasskeys.isEmpty() && unsupported.isEmpty() && retainedSources.isEmpty()) add(strings.get(R.string.dedup_merge_warning_empty_sources))
            })
    }

    override suspend fun executePlan(plan: DedupMergePlan, onProgress: (DedupMergeExecutionProgress) -> Unit): DedupMergeExecutionResult {
        executionMutex.lock()
        try {
            val approved = review
            check(approved != null && approved.plan == plan && approved.planIntegrity == fingerprint(listOf(plan.toString())) && plan.reviewToken != null &&
                approved.epoch == operationEpoch.get() &&
                approved.generation == takagi.ru.monica.security.SessionManager.lockGeneration.value) { strings.get(R.string.dedup_merge_changed_review) }
            val target = requireNotNull(plan.target)
            check(plan.previewPasskeys.all { !it.existsInTarget && it.skipReason == DedupPasskeySkipReason.PRESERVED }) {
                strings.get(R.string.dedup_merge_changed_review)
            }
            val retainedPasswordIds = plan.passkeyRetainedProjects.flatMap { it.passwordIds }.toSet()
            check(plan.previewPasswords.none { row -> row.sourceEntryIds.any { it in retainedPasswordIds } }) {
                strings.get(R.string.dedup_merge_changed_review)
            }
            val retainedItemIds = plan.passkeyRetainedProjects.flatMap { it.secureItemIds }.toSet()
            check(plan.previewSecureItems.none { row -> row.sourceItemIds.any { it in retainedItemIds } }) {
                strings.get(R.string.dedup_merge_changed_review)
            }
            if (target is DedupMergeTarget.MdbxDatabase) require(localMdbxDatabaseDao.getAvailableDatabasesSnapshot().any { it.id == target.databaseId })
            scanPlan(plan.selectedSources.map { it.key }.toSet(), target, plan.conflictPolicy, approved.epoch)
            val executingEpoch = approved.epoch
            synchronized(reviewLock) {
                check(operationEpoch.get() == executingEpoch && review?.epoch == executingEpoch && review?.evidence == approved.evidence &&
                    review?.plan?.passkeyRetainedProjects == approved.plan.passkeyRetainedProjects) {
                    strings.get(R.string.dedup_merge_changed_review)
                }
                // Consume before any write: a partial/unknown result cannot be retried with the same approval.
                review = null
            }
            return mergeExecutor.execute(passwords = plan.previewPasswords.filterNot { it.existsInTarget },
                secureItems = plan.previewSecureItems.filterNot { it.existsInTarget }, passkeys = emptyList(),
                skippedExistingPasswords = plan.targetExistingDuplicates, skippedExistingSecureItems = plan.targetExistingSecureItems,
                skippedExistingPasskeys = plan.targetExistingPasskeys, skippedUnsupportedPasskeys = plan.unsupportedSourcePasskeys,
                targetLabel = target.label(), onProgress = onProgress, isCurrent = { operationEpoch.get() == executingEpoch &&
                    takagi.ru.monica.security.SessionManager.isUnlocked.value && takagi.ru.monica.security.SessionManager.lockGeneration.value == approved.generation })
        } finally { executionMutex.unlock() }
    }

    private fun selectBestPassword(
        entries: List<PasswordEntry>,
        customFieldsByEntry: Map<Long, List<CustomField>>,
        conflictPolicy: DedupConflictPolicy
    ): PasswordEntry {
        val comparator = when (conflictPolicy) {
            DedupConflictPolicy.MOST_COMPLETE -> compareBy<PasswordEntry> { fieldCompletenessScore(it, customFieldsByEntry[it.id].orEmpty()) }
                .thenBy { it.updatedAt.time }
                .thenBy { if (it.isFavorite) 1 else 0 }
            DedupConflictPolicy.NEWEST -> compareBy<PasswordEntry> { it.updatedAt.time }
                .thenBy { fieldCompletenessScore(it, customFieldsByEntry[it.id].orEmpty()) }
        }
        return entries.maxWithOrNull(comparator) ?: entries.first()
    }

    private fun fieldCompletenessScore(entry: PasswordEntry, customFields: List<CustomField>): Int {
        return listOf(
            entry.title,
            entry.website,
            entry.username,
            entry.password,
            entry.notes,
            entry.appPackageName,
            entry.appName,
            entry.email,
            entry.phone,
            entry.addressLine,
            entry.city,
            entry.state,
            entry.zipCode,
            entry.country,
            entry.creditCardNumber,
            entry.creditCardHolder,
            entry.creditCardExpiry,
            entry.creditCardCVV,
            entry.authenticatorKey,
            entry.passkeyBindings,
            entry.sshKeyData,
            entry.ssoProvider,
            entry.wifiMetadata,
            entry.customIconValue.orEmpty()
        ).count { it.isNotBlank() } + customFields.count { it.title.isNotBlank() && it.value.isNotBlank() }
    }

    private fun exactContentFingerprint(entry: PasswordEntry, customFields: List<CustomField>): String =
        fingerprint(DedupMergeContent.password(entry, customFields, ::decryptComparablePassword))

    private fun buildTargetEntry(entry: PasswordEntry, target: DedupMergeTarget): PasswordEntry {
        return when (target) {
            DedupMergeTarget.MonicaLocal -> entry.copy(
                id = 0,
                categoryId = null,
                boundNoteId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = null,
                mdbxFolderId = null,
                bitwardenVaultId = null,
                bitwardenCipherId = null,
                bitwardenFolderId = null,
                bitwardenRevisionDate = null,
                bitwardenLocalModified = false,
                ssoRefEntryId = null,
                replicaGroupId = null,
                isDeleted = false,
                deletedAt = null,
                isArchived = entry.isArchived,
                archivedAt = entry.archivedAt
            )
            is DedupMergeTarget.MdbxDatabase -> entry.copy(
                id = 0,
                categoryId = null,
                boundNoteId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = target.databaseId,
                mdbxFolderId = null,
                bitwardenVaultId = null,
                bitwardenCipherId = null,
                bitwardenFolderId = null,
                bitwardenRevisionDate = null,
                bitwardenLocalModified = false,
                ssoRefEntryId = null,
                replicaGroupId = "password:${java.util.UUID.randomUUID()}",
                isDeleted = false,
                deletedAt = null,
                isArchived = entry.isArchived,
                archivedAt = entry.archivedAt
            )
        }
    }

    private fun selectBestSecureItem(
        items: List<SecureItem>,
        conflictPolicy: DedupConflictPolicy
    ): SecureItem {
        val completeness = items.associateWith(::secureItemCompletenessScore)
        val comparator = when (conflictPolicy) {
            DedupConflictPolicy.MOST_COMPLETE -> compareBy<SecureItem> { completeness.getValue(it) }
                .thenBy { it.updatedAt.time }
                .thenBy { if (it.isFavorite) 1 else 0 }
            DedupConflictPolicy.NEWEST -> compareBy<SecureItem> { it.updatedAt.time }
                .thenBy { completeness.getValue(it) }
        }
        return items.maxWithOrNull(comparator) ?: items.first()
    }

    private fun secureItemCompletenessScore(item: SecureItem): Int {
        val raw = decryptComparablePassword(item.itemData)
        val payloadScore = runCatching {
            contentCompletenessScore(json.parseToJsonElement(raw))
        }.getOrElse { if (raw.isNotBlank()) 1 else 0 }
        return listOf(item.title, item.notes).count { it.isNotBlank() } + imageCount(item.imagePaths) + payloadScore
    }

    private fun imageCount(value: String): Int = runCatching {
        takagi.ru.monica.attachments.LegacyImageAttachmentSupport.paths(value).count { it.isNotBlank() }
    }.getOrElse { if (value.isBlank()) 0 else 1 }

    private fun contentCompletenessScore(element: JsonElement, depth: Int = 0): Int {
        if (depth >= 64) return 0
        return when (element) {
            is JsonObject -> element.entries.sumOf { (key, value) ->
                // A decorative card face does not make a credential more complete.
                if (key == "cardFace") 0 else contentCompletenessScore(value, depth + 1)
            }
            is JsonArray -> element.sumOf { contentCompletenessScore(it, depth + 1) }
            is JsonPrimitive -> if (element.isString && decryptComparablePassword(element.content).isNotBlank()) 1 else 0
        }
    }

    private fun exactContentFingerprint(item: SecureItem, imageKey: String? = null): String {
        return listOf(
            item.itemType.name,
            normalizeText(item.title),
            item.notes.trim(),
            imageKey ?: item.imagePaths.trim(),
            exactSecureItemDataFingerprint(item)
        ).let(::fingerprint)
    }

    private fun exactSecureItemDataFingerprint(item: SecureItem): String {
        val raw = decryptComparablePassword(item.itemData)
        // Compare the whole payload, including custom/unknown fields. A fixed list of
        // model properties silently drops new fields and may skip a different target.
        // Decryption is only for comparison; the original encrypted payload is copied.
        val canonical = runCatching {
            canonicalContent(json.parseToJsonElement(raw)).toString()
        }.getOrDefault(raw)
        return fingerprint(listOf(canonical))
    }

    private fun canonicalContent(element: JsonElement, depth: Int = 0): JsonElement {
        if (depth >= 64) return element
        return when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, value) ->
                canonicalContent(value, depth + 1)
            })
            is JsonArray -> JsonArray(element.map { canonicalContent(it, depth + 1) })
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(decryptComparablePassword(element.content))
            } else element
        }
    }

    /** Keep Room encrypted while the native target receives a self-contained known JSON payload. */
    private fun prepareMdbxItemData(item: SecureItem): String {
        val parsed = json.parseToJsonElement(decryptComparablePassword(item.itemData))
        return if (containsMonicaCiphertext(parsed)) securityManager.encryptData(canonicalContent(parsed).toString()) else item.itemData
    }

    private fun containsMonicaCiphertext(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.values.any(::containsMonicaCiphertext)
        is JsonArray -> value.any(::containsMonicaCiphertext)
        is JsonPrimitive -> value.isString && securityManager.looksLikeMonicaCiphertext(value.content)
    }

    private fun buildTargetItem(item: SecureItem, target: DedupMergeTarget): SecureItem {
        return when (target) {
            DedupMergeTarget.MonicaLocal -> item.copy(
                id = 0,
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = null,
                mdbxFolderId = null,
                isDeleted = false,
                deletedAt = null,
                replicaGroupId = null,
                bitwardenVaultId = null,
                bitwardenCipherId = null,
                bitwardenFolderId = null,
                bitwardenRevisionDate = null,
                bitwardenLocalModified = false,
                syncStatus = "NONE"
            )
            is DedupMergeTarget.MdbxDatabase -> item.copy(
                id = 0,
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = target.databaseId,
                mdbxFolderId = null,
                isDeleted = false,
                deletedAt = null,
                replicaGroupId = "${when (item.itemType) {
                    ItemType.NOTE -> "note"
                    ItemType.TOTP -> "totp"
                    ItemType.BANK_CARD -> "card"
                    ItemType.DOCUMENT -> "document-ref"
                    ItemType.BILLING_ADDRESS -> "billing-address"
                    ItemType.PAYMENT_ACCOUNT -> "payment-account"
                    ItemType.PASSWORD -> "password"
                }}:${java.util.UUID.randomUUID()}",
                bitwardenVaultId = null,
                bitwardenCipherId = null,
                bitwardenFolderId = null,
                bitwardenRevisionDate = null,
                bitwardenLocalModified = false,
                syncStatus = "NONE"
            )
        }
    }

    private fun PasswordEntry.belongsToTarget(target: DedupMergeTarget): Boolean {
        return sourceKeyOf(this) == target.sourceKey()
    }

    private fun SecureItem.belongsToTarget(target: DedupMergeTarget): Boolean {
        return sourceKeyOf(this) == target.sourceKey()
    }

    private fun mergeIdentityKey(entry: PasswordEntry, customFields: List<CustomField>): String {
        val identity = DedupPasswordIdentity.key(entry)
        return if (identity.startsWith("entry|")) {
            "exact|${exactContentFingerprint(entry, customFields)}"
        } else {
            fingerprint(listOf(identity))
        }
    }

    private fun mergeIdentityKey(item: SecureItem): String {
        return when (item.itemType) {
            ItemType.TOTP -> {
                val data = decodeTotpData(item)
                val secret = data?.secret
                    ?.let(::decryptComparablePassword)
                    ?.let(::normalizeSecret)
                    .orEmpty()
                val issuer = data?.issuer?.let(::normalizeText).orEmpty()
                val accountName = data?.accountName?.let(::normalizeText).orEmpty()
                when {
                    secret.isNotBlank() -> fingerprint(listOf("otp", secret, data!!.otpType.name,
                        data.algorithm.uppercase(Locale.ROOT), data.period.toString(), data.digits.toString(),
                        data.counter.toString(), decryptComparablePassword(data.pin)))
                    issuer.isNotBlank() && accountName.isNotBlank() -> fingerprint(listOf("totp_account", issuer, accountName))
                    else -> "secure_exact|${exactContentFingerprint(item)}"
                }
            }
            ItemType.BANK_CARD -> {
                val cardNumber = decodeBankCardData(item)
                    ?.cardNumber
                    ?.let(::decryptComparablePassword)
                    ?.filter { it.isDigit() }
                    .orEmpty()
                if (cardNumber.isNotBlank()) {
                    fingerprint(listOf("bank_card", cardNumber))
                } else {
                    "secure_exact|${exactContentFingerprint(item)}"
                }
            }
            ItemType.DOCUMENT -> {
                val data = decodeDocumentData(item)
                val documentNumber = data
                    ?.documentNumber
                    ?.let(::decryptComparablePassword)
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                    .orEmpty()
                if (documentNumber.isNotBlank()) {
                    fingerprint(listOf("document", data?.documentType?.name.orEmpty(), documentNumber))
                } else {
                    "secure_exact|${exactContentFingerprint(item)}"
                }
            }
            ItemType.BILLING_ADDRESS -> {
                val data = decodeBillingAddressData(item)
                val addressKey = listOf(
                    data?.fullName?.let(::normalizeText).orEmpty(),
                    data?.streetAddress?.let(::normalizeText).orEmpty(),
                    data?.apartment?.let(::normalizeText).orEmpty(),
                    data?.city?.let(::normalizeText).orEmpty(),
                    data?.stateProvince?.let(::normalizeText).orEmpty(),
                    data?.postalCode?.trim()?.uppercase(Locale.ROOT).orEmpty(),
                    data?.country?.let(::normalizeText).orEmpty()
                )
                if (addressKey.any { it.isNotBlank() }) {
                    fingerprint(listOf("billing_address") + addressKey)
                } else {
                    "secure_exact|${exactContentFingerprint(item)}"
                }
            }
            ItemType.PAYMENT_ACCOUNT -> {
                val data = decodePaymentAccountData(item)
                val accountKey = listOf(
                    data?.provider?.let(::normalizeText).orEmpty(),
                    data?.accountName?.let(::normalizeText).orEmpty(),
                    data?.email?.let(::normalizeText).orEmpty(),
                    data?.username?.let(::normalizeText).orEmpty(),
                    data?.accountId?.let(::normalizeText).orEmpty(),
                    data?.maskedAccountNumber?.trim().orEmpty(),
                    data?.linkedCardLast4?.trim().orEmpty()
                )
                if (accountKey.any { it.isNotBlank() }) {
                    fingerprint(listOf("payment_account") + accountKey)
                } else {
                    "secure_exact|${exactContentFingerprint(item)}"
                }
            }
            ItemType.NOTE,
            ItemType.PASSWORD -> "secure_exact|${exactContentFingerprint(item)}"
        }
    }

    private fun sourceKeyOf(entry: PasswordEntry): String {
        return when (val owner = entry.resolveOwnership()) {
            is PasswordOwnership.Mdbx -> mdbxSourceKey(owner.databaseId)
            is PasswordOwnership.KeePass -> keepassSourceKey(owner.databaseId)
            is PasswordOwnership.Bitwarden -> bitwardenSourceKey(owner.vaultId)
            is PasswordOwnership.MonicaLocal -> SOURCE_MONICA
            is PasswordOwnership.Conflict -> "ownership-conflict"
        }
    }

    private fun sourceKeyOf(item: SecureItem): String {
        return when (val owner = item.resolveOwnership()) {
            is SecureItemOwnership.Mdbx -> mdbxSourceKey(owner.databaseId)
            is SecureItemOwnership.KeePass -> keepassSourceKey(owner.databaseId)
            is SecureItemOwnership.Bitwarden -> owner.vaultId?.let(::bitwardenSourceKey) ?: "ownership-conflict"
            is SecureItemOwnership.MonicaLocal -> SOURCE_MONICA
            is SecureItemOwnership.Conflict -> "ownership-conflict"
        }
    }

    private fun sourceKeyOf(entry: PasskeyEntry): String {
        return when (val owner = entry.resolveOwnership()) {
            is PasskeyOwnership.Mdbx -> mdbxSourceKey(owner.databaseId)
            is PasskeyOwnership.KeePass -> keepassSourceKey(owner.databaseId)
            is PasskeyOwnership.Bitwarden -> owner.vaultId?.let(::bitwardenSourceKey) ?: "ownership-conflict"
            is PasskeyOwnership.MonicaLocal -> SOURCE_MONICA
            is PasskeyOwnership.Conflict -> "ownership-conflict"
        }
    }

    private fun labelForSourceKey(
        sourceKey: String,
        sourceOptions: List<DedupMergeSourceOption>
    ): String {
        return sourceOptions.firstOrNull { it.key == sourceKey }?.label ?: when {
            sourceKey.startsWith("mdbx:") -> "MDBX"
            sourceKey.startsWith("keepass:") -> "KeePass"
            sourceKey.startsWith("bitwarden:") -> "Bitwarden"
            else -> strings.get(R.string.database_source_local)
        }
    }

    private fun bitwardenLabel(vault: BitwardenVault): String {
        return vault.displayName
            ?.takeIf { it.isNotBlank() && !it.equals("Bitwarden", ignoreCase = true) }
            ?: vault.email.takeIf { it.isNotBlank() }
            ?: compactServerLabel(vault.serverUrl)
            ?: "Bitwarden ${vault.id}"
    }

    private fun compactServerLabel(serverUrl: String): String? {
        return runCatching {
            URI(serverUrl).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun DedupMergeTarget.label(): String {
        return when (this) {
            DedupMergeTarget.MonicaLocal -> strings.get(R.string.database_source_local)
            is DedupMergeTarget.MdbxDatabase -> label
        }
    }

    private fun DedupMergeTarget.sourceKey(): String {
        return when (this) {
            DedupMergeTarget.MonicaLocal -> SOURCE_MONICA
            is DedupMergeTarget.MdbxDatabase -> mdbxSourceKey(databaseId)
        }
    }

    private fun normalizeText(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun normalizeWebsite(value: String): String {
        return DedupPasswordIdentity.normalizeWebsite(value)
    }

    private fun normalizeSecret(value: String): String {
        return value.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
    }

    private fun decryptComparablePassword(value: String): String {
        if (value.isEmpty()) return ""
        return securityManager.decryptDataIfMonicaCiphertext(value)
    }

    private fun fingerprint(parts: List<String>): String {
        val digest = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(digestKey) }
        parts.forEach { part ->
            digest.update("${part.length}:".toByteArray(Charsets.UTF_8))
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        return buildString(64) {
            digest.doFinal().forEach { byte ->
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 15])
            }
        }
    }

    private fun decodeTotpData(item: SecureItem): TotpData? {
        // The generic resolver normalizes Base32 secrets while parsing. Decrypt
        // nested fields first: uppercasing a C2/MDK payload corrupts its auth tag.
        // This copy is only for comparison; the stored payload remains unchanged.
        val readable = canonicalContent(json.parseToJsonElement(decryptComparablePassword(item.itemData)))
        return TotpDataResolver.parseStoredItemData(
            itemData = readable.toString(),
            fallbackIssuer = item.title
        )
    }

    private fun decodeBankCardData(item: SecureItem): BankCardData? {
        return CardWalletDataCodec.parseBankCardData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
    }

    private fun decodeDocumentData(item: SecureItem): DocumentData? {
        return CardWalletDataCodec.parseDocumentData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
    }

    private fun decodeBillingAddressData(item: SecureItem): BillingAddressData? {
        return CardWalletDataCodec.parseBillingAddressData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
    }

    private fun decodePaymentAccountData(item: SecureItem): PaymentAccountData? {
        return CardWalletDataCodec.parsePaymentAccountData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
    }

    private fun decodeNoteData(item: SecureItem): NoteData? {
        return runCatching { json.decodeFromString<NoteData>(item.itemData) }.getOrNull()
    }

    private fun DocumentData.displayNameForCompare(): String {
        return listOf(firstName, middleName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { fullName }
    }

    private companion object {
        const val SOURCE_MONICA = "monica"

        fun mdbxSourceKey(databaseId: Long): String = "mdbx:$databaseId"
        fun keepassSourceKey(databaseId: Long): String = "keepass:$databaseId"
        fun bitwardenSourceKey(vaultId: Long): String = "bitwarden:$vaultId"
    }
}
