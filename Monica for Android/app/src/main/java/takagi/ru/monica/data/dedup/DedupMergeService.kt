package takagi.ru.monica.data.dedup

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
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
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
    private val attachmentSupport: DedupAttachmentSupport? = null
) : DedupMergeOperations {
    private val json = Json { ignoreUnknownKeys = true }
    private val mergeExecutor = DedupMergeExecutor(
        RepositoryDedupMergeWriter(
            passwordRepository = passwordRepository,
            secureItemRepository = secureItemRepository,
            customFieldRepository = customFieldRepository,
            passkeyRepository = passkeyRepository,
            attachmentSupport = attachmentSupport
        ),
        strings = strings
    )

    override suspend fun getSourceOptions(): List<DedupMergeSourceOption> {
        val entries = passwordRepository.getAllPasswordEntries().first()
        val secureItems = secureItemRepository.getAllItems().first()
        val passkeys = passkeyRepository.getAllPasskeysSync()
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
        val sourceOptions = getSourceOptions()
        val selectedOptions = sourceOptions.filter { it.key in selectedSourceKeys }
        if (selectedOptions.isEmpty() || target == null || target.sourceKey() in selectedSourceKeys) {
            return DedupMergePlan(selectedSources = selectedOptions, target = target, conflictPolicy = conflictPolicy)
        }
        val allEntries = passwordRepository.getAllPasswordEntries().first()
        val allSecureItems = secureItemRepository.getAllItems().first()
        val allPasskeys = passkeyRepository.getAllPasskeysSync()
        val targetSourceKey = target?.sourceKey()
        val sourceEntries = allEntries.filter { sourceKeyOf(it) in selectedSourceKeys }
        val sourceSecureItems = allSecureItems.filter { sourceKeyOf(it) in selectedSourceKeys }
        val sourcePasskeys = allPasskeys.filter { sourceKeyOf(it) in selectedSourceKeys }
        val resolvedPasskeys = DedupPasskeyPlanner(securityManager).build(
            sources = sourcePasskeys,
            targetEntries = allPasskeys.filter { sourceKeyOf(it) == targetSourceKey },
            target = target,
            policy = conflictPolicy,
            sourceLabel = { labelForSourceKey(sourceKeyOf(it), sourceOptions) }
        )
        val skippedPasskeys = resolvedPasskeys.filter { it.skipReason != null }.sumOf { it.sourceEntryIds.size }
        val targetEntries = target
            ?.let { selectedTarget -> allEntries.filter { it.belongsToTarget(selectedTarget) } }
            .orEmpty()
        val targetSecureItems = target
            ?.let { selectedTarget -> allSecureItems.filter { it.belongsToTarget(selectedTarget) } }
            .orEmpty()
        val customFieldsByEntry = customFieldRepository.getFieldsByEntryIds(
            (sourceEntries + targetEntries).map { it.id }.distinct()
        )
        val attachments = attachmentSupport?.snapshot(sourceEntries + targetEntries, sourceSecureItems + targetSecureItems)
        val targetExistingKeys = targetEntries
            .map { entry -> mergeIdentityKey(entry, customFieldsByEntry[entry.id].orEmpty()) }
            .toSet()
        val targetExistingFingerprints = targetEntries
            .map { entry -> withAttachments(exactContentFingerprint(entry, customFieldsByEntry[entry.id].orEmpty()),
                attachments?.password(entry.id).orEmpty()) }
            .toSet()
        val targetExistingSecureKeys = targetSecureItems
            .map(::mergeIdentityKey)
            .toSet()
        val targetExistingSecureFingerprints = targetSecureItems
            .map { item -> withAttachments(exactContentFingerprint(item, attachments?.imageKey(item.imagePaths)),
                attachments?.secureItem(item.id).orEmpty()) }
            .toSet()

        val resolvedPasswords = sourceEntries
            .groupBy { entry -> mergeIdentityKey(entry, customFieldsByEntry[entry.id].orEmpty()) }
            .map { (mergeKey, group) ->
                coroutineContext.ensureActive()
                val keeper = selectBestPassword(group, customFieldsByEntry, conflictPolicy)
                val mergedEntry = mergePasswordGroup(keeper, group)
                val mergedCustomFields = mergeCustomFields(keeper, group, customFieldsByEntry)
                val mergedAttachments = group.flatMap { attachments?.password(it.id).orEmpty() }
                    .sortedByDescending { it.readable }
                    .distinctBy { it.contentKey }
                val existsInTarget = withAttachments(exactContentFingerprint(mergedEntry, mergedCustomFields), mergedAttachments) in targetExistingFingerprints
                val sourceLabels = group
                    .map { entry -> labelForSourceKey(sourceKeyOf(entry), sourceOptions) }
                    .distinct()
                DedupResolvedPassword(
                    mergeKey = mergeKey,
                    entry = target?.let { buildTargetEntry(mergedEntry, it) } ?: mergedEntry.copy(id = 0),
                    customFields = mergedCustomFields,
                    sourceEntryIds = group.map { it.id },
                    sourceLabels = sourceLabels,
                    conflictFields = conflictFields(group, customFieldsByEntry),
                    existsInTarget = existsInTarget,
                    targetHasDifferentContent = !existsInTarget && mergeKey in targetExistingKeys,
                    preferredSourceLabel = labelForSourceKey(sourceKeyOf(keeper), sourceOptions),
                    attachments = mergedAttachments
                )
            }
            .sortedWith(
                compareBy<DedupResolvedPassword> { it.existsInTarget }
                    .thenByDescending { it.sourceEntryIds.size }
                    .thenBy { it.entry.title.lowercase(Locale.ROOT) }
                    .thenBy { it.entry.username.lowercase(Locale.ROOT) }
            )

        val resolvedSecureItems = sourceSecureItems
            .groupBy(::mergeIdentityKey)
            .map { (mergeKey, group) ->
                coroutineContext.ensureActive()
                val keeper = selectBestSecureItem(group, conflictPolicy)
                val mergedItem = mergeSecureItemGroup(keeper, group)
                val mergedAttachments = group.flatMap { attachments?.secureItem(it.id).orEmpty() }
                    .sortedByDescending { it.readable }
                    .distinctBy { it.contentKey }
                val existsInTarget = withAttachments(exactContentFingerprint(mergedItem, attachments?.imageKey(mergedItem.imagePaths)),
                    mergedAttachments) in targetExistingSecureFingerprints
                val sourceLabels = group
                    .map { item -> labelForSourceKey(sourceKeyOf(item), sourceOptions) }
                    .distinct()
                DedupResolvedSecureItem(
                    mergeKey = mergeKey,
                    item = target?.let { buildTargetItem(mergedItem, it) } ?: mergedItem.copy(id = 0),
                    sourceItemIds = group.map { it.id },
                    sourceLabels = sourceLabels,
                    conflictFields = conflictFields(group),
                    existsInTarget = existsInTarget,
                    targetHasDifferentContent = !existsInTarget && mergeKey in targetExistingSecureKeys,
                    preferredSourceLabel = labelForSourceKey(sourceKeyOf(keeper), sourceOptions),
                    attachments = mergedAttachments
                )
            }
            .sortedWith(
                compareBy<DedupResolvedSecureItem> { it.existsInTarget }
                    .thenByDescending { it.sourceItemIds.size }
                    .thenBy { it.item.itemType.ordinal }
                    .thenBy { it.item.title.lowercase(Locale.ROOT) }
            )

        val warnings = buildList {
            if (selectedSourceKeys.size < DedupMergeSelection.MINIMUM_SOURCE_DATABASES) {
                add(strings.get(R.string.dedup_merge_need_sources))
            }
            if (target == null) add(strings.get(R.string.dedup_merge_need_target))
            if (sourceEntries.isEmpty() && sourceSecureItems.isEmpty() && sourcePasskeys.isEmpty() && selectedSourceKeys.isNotEmpty()) {
                add(strings.get(R.string.dedup_merge_warning_empty_sources))
            }
            if (targetSourceKey != null && targetSourceKey in selectedSourceKeys) {
                add(strings.get(R.string.dedup_merge_warning_target_is_source))
            }
            if (resolvedPasswords.any { it.existsInTarget }) {
                add(strings.get(R.string.dedup_merge_warning_existing_passwords))
            }
            if (resolvedSecureItems.any { it.existsInTarget }) {
                add(strings.get(R.string.dedup_merge_warning_existing_secure_items))
            }
            if (skippedPasskeys > 0) {
                add(strings.get(R.string.dedup_merge_warning_passkeys, skippedPasskeys))
            }
        }

        return DedupMergePlan(
            selectedSources = selectedOptions,
            target = target,
            conflictPolicy = conflictPolicy,
            totalSourcePasswords = sourceEntries.size,
            totalSourceSecureItems = sourceSecureItems.size,
            unsupportedSourcePasskeys = skippedPasskeys,
            totalSourcePasskeys = sourcePasskeys.size,
            uniquePasswords = resolvedPasswords.size,
            uniqueSecureItems = resolvedSecureItems.size,
            duplicateGroups = resolvedPasswords.count { it.sourceEntryIds.size > 1 },
            duplicateSecureItemGroups = resolvedSecureItems.count { it.sourceItemIds.size > 1 },
            passwordConflictGroups = resolvedPasswords.count { it.conflictFields.isNotEmpty() },
            secureItemConflictGroups = resolvedSecureItems.count { it.conflictFields.isNotEmpty() },
            targetExistingDuplicates = resolvedPasswords.count { it.existsInTarget },
            targetExistingSecureItems = resolvedSecureItems.count { it.existsInTarget },
            previewPasswords = resolvedPasswords,
            previewSecureItems = resolvedSecureItems,
            previewPasskeys = resolvedPasskeys,
            warnings = warnings
        )
    }

    override suspend fun executePlan(
        plan: DedupMergePlan,
        onProgress: (DedupMergeExecutionProgress) -> Unit
    ): DedupMergeExecutionResult {
        val target = plan.target ?: error(strings.get(R.string.dedup_merge_need_target))
        require(plan.selectedSources.size >= DedupMergeSelection.MINIMUM_SOURCE_DATABASES) {
            strings.get(R.string.dedup_merge_need_sources)
        }
        require(target.sourceKey() !in plan.selectedSources.map { it.key }.toSet()) {
            strings.get(R.string.dedup_merge_target_cannot_be_source)
        }
        require(getTargetOptions().any { it.sourceKey == target.sourceKey() }) {
            strings.get(R.string.dedup_merge_need_target)
        }
        val freshPlan = buildPlan(
            selectedSourceKeys = plan.selectedSources.map { it.key }.toSet(),
            target = target,
            conflictPolicy = plan.conflictPolicy
        )
        val rowsToInsert = freshPlan.previewPasswords.filterNot { it.existsInTarget }
        val secureItemsToInsert = freshPlan.previewSecureItems.filterNot { it.existsInTarget }
        val passkeysToInsert = freshPlan.previewPasskeys.filter { it.writable }
        require(freshPlan.selectedSources.size >= DedupMergeSelection.MINIMUM_SOURCE_DATABASES) {
            strings.get(R.string.dedup_merge_sources_unavailable)
        }
        if (rowsToInsert.isEmpty() && secureItemsToInsert.isEmpty() && passkeysToInsert.isEmpty()) {
            return DedupMergeExecutionResult(
                insertedPasswords = 0,
                skippedExistingPasswords = freshPlan.targetExistingDuplicates,
                skippedExistingSecureItems = freshPlan.targetExistingSecureItems,
                skippedExistingPasskeys = freshPlan.targetExistingPasskeys,
                skippedUnsupportedPasskeys = freshPlan.unsupportedSourcePasskeys,
                failedPasswords = 0,
                targetLabel = target.label()
            )
        }

        return mergeExecutor.execute(
            passwords = rowsToInsert,
            secureItems = secureItemsToInsert,
            passkeys = passkeysToInsert,
            skippedExistingPasswords = freshPlan.targetExistingDuplicates,
            skippedExistingSecureItems = freshPlan.targetExistingSecureItems,
            skippedExistingPasskeys = freshPlan.targetExistingPasskeys,
            skippedUnsupportedPasskeys = freshPlan.unsupportedSourcePasskeys,
            targetLabel = target.label(),
            onProgress = onProgress
        )
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

    private fun mergePasswordGroup(
        keeper: PasswordEntry,
        entries: List<PasswordEntry>
    ): PasswordEntry {
        val iconSource = entries.firstOrNull { it.customIconValue?.isNotBlank() == true }
        return keeper.copy(
            title = firstNonBlank(keeper.title, entries) { it.title },
            website = firstNonBlank(keeper.website, entries) { it.website },
            username = firstNonBlank(keeper.username, entries) { it.username },
            password = keeper.password.ifEmpty {
                entries.firstOrNull { it.password.isNotEmpty() }?.password.orEmpty()
            },
            notes = firstNonBlank(keeper.notes, entries) { it.notes },
            isFavorite = entries.any { it.isFavorite },
            appPackageName = firstNonBlank(keeper.appPackageName, entries) { it.appPackageName },
            appName = firstNonBlank(keeper.appName, entries) { it.appName },
            email = firstNonBlank(keeper.email, entries) { it.email },
            phone = firstNonBlank(keeper.phone, entries) { it.phone },
            addressLine = firstNonBlank(keeper.addressLine, entries) { it.addressLine },
            city = firstNonBlank(keeper.city, entries) { it.city },
            state = firstNonBlank(keeper.state, entries) { it.state },
            zipCode = firstNonBlank(keeper.zipCode, entries) { it.zipCode },
            country = firstNonBlank(keeper.country, entries) { it.country },
            creditCardNumber = firstNonBlank(keeper.creditCardNumber, entries) { it.creditCardNumber },
            creditCardHolder = firstNonBlank(keeper.creditCardHolder, entries) { it.creditCardHolder },
            creditCardExpiry = firstNonBlank(keeper.creditCardExpiry, entries) { it.creditCardExpiry },
            creditCardCVV = firstNonBlank(keeper.creditCardCVV, entries) { it.creditCardCVV },
            authenticatorKey = firstNonBlank(keeper.authenticatorKey, entries) { it.authenticatorKey },
            passkeyBindings = firstNonBlank(keeper.passkeyBindings, entries) { it.passkeyBindings },
            sshKeyData = firstNonBlank(keeper.sshKeyData, entries) { it.sshKeyData },
            ssoProvider = firstNonBlank(keeper.ssoProvider, entries) { it.ssoProvider },
            wifiMetadata = firstNonBlank(keeper.wifiMetadata, entries) { it.wifiMetadata },
            customIconType = iconSource?.customIconType ?: keeper.customIconType,
            customIconValue = iconSource?.customIconValue ?: keeper.customIconValue,
            customIconUpdatedAt = iconSource?.customIconUpdatedAt ?: keeper.customIconUpdatedAt
        )
    }

    private fun mergeCustomFields(
        keeper: PasswordEntry,
        entries: List<PasswordEntry>,
        customFieldsByEntry: Map<Long, List<CustomField>>
    ): List<CustomField> {
        val orderedEntries = listOf(keeper) + entries.filter { it.id != keeper.id }
        val seenFingerprints = mutableSetOf<String>()
        var nextSortOrder = 0
        return orderedEntries
            .flatMap { entry ->
                customFieldsByEntry[entry.id]
                    .orEmpty()
                    .sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id })
            }
            .mapNotNull { field ->
                if (field.title.isBlank()) return@mapNotNull null
                val fingerprint = listOf(
                    normalizeText(field.title),
                    decryptComparablePassword(field.value),
                    field.isProtected.toString()
                ).joinToString("\u0000")
                if (!seenFingerprints.add(fingerprint)) {
                    null
                } else {
                    field.copy(id = 0, entryId = 0, sortOrder = nextSortOrder++)
                }
            }
    }

    private fun firstNonBlank(
        current: String,
        entries: List<PasswordEntry>,
        selector: (PasswordEntry) -> String
    ): String {
        if (current.isNotBlank()) return current
        return entries.asSequence()
            .map(selector)
            .firstOrNull { it.isNotBlank() }
            ?: current
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

    private fun conflictFields(
        entries: List<PasswordEntry>,
        customFieldsByEntry: Map<Long, List<CustomField>>
    ): Set<String> {
        if (entries.size <= 1) return emptySet()
        return buildSet {
            addIfDistinct(entries) { normalizeText(it.title) }?.let { add(strings.get(R.string.title)) }
            addIfDistinct(entries) { normalizeWebsite(it.website) }?.let { add(strings.get(R.string.website)) }
            addIfDistinct(entries) { DedupPasswordIdentity.normalizeUsername(it.username) }?.let { add(strings.get(R.string.username)) }
            addIfDistinct(entries) { decryptComparablePassword(it.password) }?.let { add(strings.get(R.string.password)) }
            addIfDistinct(entries) { it.notes.trim() }?.let { add(strings.get(R.string.notes)) }
            addIfDistinct(entries) { normalizeSecret(it.authenticatorKey) }?.let { add(strings.get(R.string.item_type_authenticator)) }
            addIfDistinct(entries) { it.loginType.uppercase(Locale.ROOT) }?.let { add(strings.get(R.string.dedup_merge_field_type)) }
            val customFieldConflicts = entries.flatMap { customFieldsByEntry[it.id].orEmpty() }
                .filter { it.title.isNotBlank() }
                .groupBy { normalizeText(it.title) to it.isProtected }
                .values.any { fields -> fields.map { decryptComparablePassword(it.value) }.filter { it.isNotEmpty() }.toSet().size > 1 }
            if (customFieldConflicts) add(strings.get(R.string.custom_fields))
        }
    }

    private fun addIfDistinct(entries: List<PasswordEntry>, selector: (PasswordEntry) -> String): Unit? {
        return if (entries.map(selector).filter { it.isNotEmpty() }.toSet().size > 1) Unit else null
    }

    private fun exactContentFingerprint(entry: PasswordEntry, customFields: List<CustomField>): String {
        val customFieldFingerprint = customFields
            .filter { it.title.isNotBlank() }
            .sortedWith(compareBy({ normalizeText(it.title) }, { decryptComparablePassword(it.value) }, { it.isProtected }))
            .joinToString("\u0002") { field ->
                listOf(normalizeText(field.title), decryptComparablePassword(field.value), field.isProtected.toString())
                    .joinToString("\u0001")
            }
        return listOf(
            normalizeText(entry.title),
            normalizeWebsite(entry.website),
            DedupPasswordIdentity.normalizeUsername(entry.username),
            decryptComparablePassword(entry.password),
            entry.notes.trim(),
            normalizeText(entry.appPackageName),
            normalizeText(entry.appName),
            normalizeText(entry.email),
            entry.phone.trim(),
            entry.addressLine.trim(),
            entry.city.trim(),
            entry.state.trim(),
            entry.zipCode.trim(),
            entry.country.trim(),
            decryptComparablePassword(entry.creditCardNumber),
            entry.creditCardHolder.trim(),
            entry.creditCardExpiry.trim(),
            decryptComparablePassword(entry.creditCardCVV),
            normalizeSecret(entry.authenticatorKey),
            entry.passkeyBindings.trim(),
            entry.sshKeyData.trim(),
            entry.loginType.uppercase(Locale.ROOT).ifBlank { "PASSWORD" },
            normalizeText(entry.ssoProvider),
            entry.wifiMetadata.trim(),
            customFieldFingerprint
        ).let(::fingerprint)
    }

    private fun buildTargetEntry(entry: PasswordEntry, target: DedupMergeTarget): PasswordEntry {
        val now = Date()
        return when (target) {
            DedupMergeTarget.MonicaLocal -> entry.copy(
                id = 0,
                createdAt = now,
                updatedAt = now,
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
                isArchived = false,
                archivedAt = null
            )
            is DedupMergeTarget.MdbxDatabase -> entry.copy(
                id = 0,
                createdAt = now,
                updatedAt = now,
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
                replicaGroupId = null,
                isDeleted = false,
                deletedAt = null,
                isArchived = false,
                archivedAt = null
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

    private fun mergeSecureItemGroup(
        keeper: SecureItem,
        items: List<SecureItem>
    ): SecureItem {
        return keeper.copy(
            title = firstNonBlankSecure(keeper.title, items) { it.title },
            notes = firstNonBlankSecure(keeper.notes, items) { it.notes },
            itemData = firstNonBlankSecure(keeper.itemData, items) { it.itemData },
            imagePaths = if (imageCount(keeper.imagePaths) > 0) keeper.imagePaths
                else items.firstOrNull { imageCount(it.imagePaths) > 0 }?.imagePaths.orEmpty(),
            isFavorite = items.any { it.isFavorite }
        )
    }

    private fun firstNonBlankSecure(
        current: String,
        items: List<SecureItem>,
        selector: (SecureItem) -> String
    ): String {
        if (current.isNotBlank()) return current
        return items.asSequence()
            .map(selector)
            .firstOrNull { it.isNotBlank() }
            ?: current
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

    private fun conflictFields(items: List<SecureItem>): Set<String> {
        if (items.size <= 1) return emptySet()
        return buildSet {
            addIfDistinctSecure(items) { normalizeText(it.title) }?.let { add(strings.get(R.string.title)) }
            addIfDistinctSecure(items) { it.notes.trim() }?.let { add(strings.get(R.string.notes)) }
            addIfDistinctSecure(items) { exactSecureItemDataFingerprint(it) }?.let { add(strings.get(R.string.content)) }
            addIfDistinctSecure(items) { it.imagePaths.trim() }?.let { add(strings.get(R.string.attachments)) }
        }
    }

    private fun addIfDistinctSecure(items: List<SecureItem>, selector: (SecureItem) -> String): Unit? {
        return if (items.map(selector).toSet().size > 1) Unit else null
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

    private fun withAttachments(content: String, attachments: List<DedupAttachmentRef>): String =
        fingerprint(listOf(content) + attachments.map { if (it.readable) it.contentKey else "unavailable:${it.attachmentId}" }.distinct().sorted())

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

    private fun buildTargetItem(item: SecureItem, target: DedupMergeTarget): SecureItem {
        val now = Date()
        return when (target) {
            DedupMergeTarget.MonicaLocal -> item.copy(
                id = 0,
                createdAt = now,
                updatedAt = now,
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
                createdAt = now,
                updatedAt = now,
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                keepassEntryUuid = null,
                keepassGroupUuid = null,
                mdbxDatabaseId = target.databaseId,
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
        return runCatching { securityManager.decryptDataIfMonicaCiphertext(value) }
            .getOrDefault(value)
    }

    private fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update("${part.length}:".toByteArray(Charsets.UTF_8))
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 15])
            }
        }
    }

    private fun decodeTotpData(item: SecureItem): TotpData? {
        return TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
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
