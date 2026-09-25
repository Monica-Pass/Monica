package takagi.ru.monica.keepass

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import java.time.Instant
import java.util.Locale
import java.util.UUID
import takagi.ru.monica.utils.KeePassFieldReferenceResolver

/**
 * A lossless, read-only view of an unlocked native KDBX session.
 *
 * The original Kotpass objects remain attached to every record so the generic
 * KeePass editor can patch a field without rebuilding (and accidentally
 * discarding) metadata that Monica does not understand yet.
 */
internal data class KeePassNativeBrowserSnapshot(
    val databaseId: Long,
    val sourceRevision: KeePassSourceRevision,
    val rootGroup: KeePassNativeGroupRecord,
    val groups: List<KeePassNativeGroupRecord>,
    val entries: List<KeePassNativeEntryRecord>,
    val groupsByIdentity: Map<KeePassNativeGroupIdentity, List<KeePassNativeGroupRecord>>,
    val entriesByIdentity: Map<KeePassNativeEntryIdentity, List<KeePassNativeEntryRecord>>,
    val groupsByLegacyPath: Map<String, List<KeePassNativeGroupRecord>>,
    val customIcons: Map<UUID, CustomIcon> = emptyMap(),
    val customIconReferences: Map<UUID, Int> = emptyMap(),
    /** The metadata-designated KeePass entry-template group, when valid. */
    val templateGroupIdentity: KeePassNativeGroupIdentity? = null,
) {
    fun group(identity: KeePassNativeGroupIdentity): KeePassNativeGroupRecord? =
        groupsByIdentity[identity]?.firstOrNull()

    fun entry(identity: KeePassNativeEntryIdentity): KeePassNativeEntryRecord? =
        entriesByIdentity[identity]?.firstOrNull()

    fun descendantGroupIdentities(
        identity: KeePassNativeGroupIdentity,
        includeSelf: Boolean = true
    ): Set<KeePassNativeGroupIdentity> {
        if (identity.databaseId != databaseId || identity !in groupsByIdentity) return emptySet()
        val result = linkedSetOf<KeePassNativeGroupIdentity>()
        val pending = ArrayDeque<KeePassNativeGroupIdentity>()
        if (includeSelf) result += identity
        pending += identity
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            groupsByIdentity[parent].orEmpty()
                .asSequence()
                .flatMap { group -> group.childGroups.asSequence() }
                .forEach { childIdentity ->
                    if (result.add(childIdentity)) pending += childIdentity
                }
        }
        return result
    }
}

internal data class KeePassNativeGroupRecord(
    val identity: KeePassNativeGroupIdentity,
    val occurrenceIndex: Int,
    val name: String,
    val notes: String,
    val parentGroup: KeePassNativeGroupIdentity?,
    val legacyPath: String?,
    val depth: Int,
    val isInRecycleBin: Boolean,
    val icon: PredefinedIcon,
    val customIconUuid: UUID?,
    val customIcon: CustomIcon?,
    val times: TimeData?,
    val expanded: Boolean,
    val defaultAutoTypeSequence: String?,
    val enableAutoType: GroupOverride,
    val enableSearching: GroupOverride,
    val tags: List<String>,
    val customData: Map<String, CustomDataValue>,
    val childGroups: List<KeePassNativeGroupIdentity>,
    val childEntries: List<KeePassNativeEntryIdentity>,
    val nativeGroup: Group
)

internal enum class KeePassNativeEntryKind {
    PASSWORD,
    TOTP,
    NOTE,
    BANK_CARD,
    DOCUMENT,
    PASSKEY,
    TEMPLATE,
    UNKNOWN
}

internal data class KeePassNativeFieldRecord(
    val name: String,
    val rawValue: String,
    val displayValue: String,
    val isProtected: Boolean,
    val role: KeePassFieldRole,
    val nativeValue: EntryValue
)

internal data class KeePassNativeAttachmentRecord(
    val name: String,
    val hash: String,
    val binary: BinaryData?
) {
    val isMissing: Boolean get() = binary == null
}

internal data class KeePassNativeHistoryVersion(
    val index: Int,
    val uuid: UUID,
    val title: String,
    val fields: List<KeePassNativeFieldRecord>,
    val attachments: List<KeePassNativeAttachmentRecord>,
    val tags: List<String>,
    val customData: Map<String, CustomDataValue>,
    val autoType: AutoTypeData?,
    val icon: PredefinedIcon,
    val customIconUuid: UUID?,
    val customIcon: CustomIcon?,
    val foregroundColor: String?,
    val backgroundColor: String?,
    val overrideUrl: String,
    val times: TimeData?,
    val previousParentGroup: UUID?,
    val qualityCheck: Boolean,
    val nativeEntry: Entry
)

internal data class KeePassNativeEntryRecord(
    val identity: KeePassNativeEntryIdentity,
    val occurrenceIndex: Int,
    val parentGroup: KeePassNativeGroupIdentity,
    val legacyGroupPath: String?,
    val isInRecycleBin: Boolean,
    val kind: KeePassNativeEntryKind,
    val title: String,
    val fields: List<KeePassNativeFieldRecord>,
    val attachments: List<KeePassNativeAttachmentRecord>,
    val history: List<KeePassNativeHistoryVersion>,
    val tags: List<String>,
    val customData: Map<String, CustomDataValue>,
    val autoType: AutoTypeData?,
    val icon: PredefinedIcon,
    val customIconUuid: UUID?,
    val customIcon: CustomIcon?,
    val foregroundColor: String?,
    val backgroundColor: String?,
    val overrideUrl: String,
    val times: TimeData?,
    val previousParentGroup: UUID?,
    val qualityCheck: Boolean,
    val nativeEntry: Entry
) {
    fun field(name: String): KeePassNativeFieldRecord? =
        fields.firstOrNull { field -> field.name.equals(name, ignoreCase = true) }
}

internal object KeePassNativeBrowserBuilder {
    fun build(
        session: KeePassNativeSession,
        resolutionContextBuilder: (Iterable<Entry>) -> takagi.ru.monica.utils.KeePassEntryResolutionContext =
            KeePassFieldReferenceResolver::buildContext
    ): KeePassNativeBrowserSnapshot {
        val allEntries = sequence {
            for (node in session.entryNodes) {
                yield(node.entry)
                yieldAll(node.entry.history)
            }
        }
        val resolutionContext = if (allEntries.any(::containsReferenceToken)) {
            // History shares UUIDs with live entries and must never become a reference target.
            resolutionContextBuilder(session.entryNodes.map { it.entry })
        } else {
            null
        }
        val customIcons = session.database.content.meta.customIcons
        val referencedCustomIconUuids = mutableListOf<UUID>()
        fun collectCustomIconReferences(group: Group) {
            group.customIconUuid?.let(referencedCustomIconUuids::add)
            group.entries.forEach { entry ->
                entry.customIconUuid?.let(referencedCustomIconUuids::add)
                entry.history.forEach { snapshot ->
                    snapshot.customIconUuid?.let(referencedCustomIconUuids::add)
                }
            }
            group.groups.forEach(::collectCustomIconReferences)
        }
        collectCustomIconReferences(session.database.content.group)
        val binaryPool = session.database.binaries
        val templateGroupUuid = KeePassTemplateEngine.templateGroupUuid(session.database)
        val templateGroups = templateGroupUuid?.let { uuid ->
            val templateIdentity = KeePassNativeGroupIdentity(session.databaseId, uuid)
            val result = linkedSetOf<KeePassNativeGroupIdentity>()
            val pending = ArrayDeque<KeePassNativeGroupIdentity>()
            pending += templateIdentity
            while (pending.isNotEmpty()) {
                val parent = pending.removeFirst()
                if (!result.add(parent)) continue
                session.groupsByIdentity[parent].orEmpty()
                    .asSequence()
                    .flatMap { node -> node.group.groups.asSequence() }
                    .forEach { child ->
                        pending += KeePassNativeGroupIdentity(session.databaseId, child.uuid)
                    }
            }
            result
        }.orEmpty()

        val groupOccurrence = mutableMapOf<KeePassNativeGroupIdentity, Int>()
        val groups = session.groupNodes.map { node ->
            val occurrenceIndex = groupOccurrence.getOrDefault(node.identity, 0)
            groupOccurrence[node.identity] = occurrenceIndex + 1
            KeePassNativeGroupRecord(
                identity = node.identity,
                occurrenceIndex = occurrenceIndex,
                name = node.group.name,
                notes = node.group.notes,
                parentGroup = node.parentGroup,
                legacyPath = node.legacyPath,
                depth = node.depth,
                isInRecycleBin = node.isInRecycleBin,
                icon = node.group.icon,
                customIconUuid = node.group.customIconUuid,
                customIcon = node.group.customIconUuid?.let(customIcons::get),
                times = node.group.times,
                expanded = node.group.expanded,
                defaultAutoTypeSequence = node.group.defaultAutoTypeSequence,
                enableAutoType = node.group.enableAutoType,
                enableSearching = node.group.enableSearching,
                tags = node.group.tags.toList(),
                customData = node.group.customData.toMap(),
                childGroups = node.group.groups.map { child ->
                    KeePassNativeGroupIdentity(session.databaseId, child.uuid)
                },
                childEntries = node.group.entries.map { child ->
                    KeePassNativeEntryIdentity(session.databaseId, child.uuid)
                },
                nativeGroup = node.group
            )
        }

        val entryOccurrence = mutableMapOf<KeePassNativeEntryIdentity, Int>()
        val entries = session.entryNodes.map { node ->
            val occurrenceIndex = entryOccurrence.getOrDefault(node.identity, 0)
            entryOccurrence[node.identity] = occurrenceIndex + 1
            val isTemplate = node.parentGroup in templateGroups || isTemplateEntry(node.entry)
            node.entry.toRecord(
                identity = node.identity,
                occurrenceIndex = occurrenceIndex,
                parentGroup = node.parentGroup,
                legacyGroupPath = node.legacyGroupPath,
                isInRecycleBin = node.isInRecycleBin,
                isTemplate = isTemplate,
                resolutionContext = resolutionContext,
                binaryPool = binaryPool,
                customIcons = customIcons
            )
        }

        val groupsByIdentity = groups.groupByTo(linkedMapOf()) { group -> group.identity }
        val entriesByIdentity = entries.groupByTo(linkedMapOf()) { entry -> entry.identity }
        val groupsByLegacyPath = groups.asSequence()
            .mapNotNull { group -> group.legacyPath?.let { path -> path to group } }
            .groupByTo(linkedMapOf(), keySelector = { it.first }, valueTransform = { it.second })
        val rootGroup = groups.firstOrNull { group -> group.parentGroup == null }
            ?: error("Native KeePass browser has no root group")

        return KeePassNativeBrowserSnapshot(
            databaseId = session.databaseId,
            sourceRevision = session.sourceRevision,
            rootGroup = rootGroup,
            groups = groups,
            entries = entries,
            groupsByIdentity = groupsByIdentity,
            entriesByIdentity = entriesByIdentity,
            groupsByLegacyPath = groupsByLegacyPath,
            customIcons = customIcons,
            customIconReferences = KeePassCustomIconEditor.countReferences(referencedCustomIconUuids),
            templateGroupIdentity = templateGroupUuid
                ?.takeIf { uuid -> groupsByIdentity.containsKey(KeePassNativeGroupIdentity(session.databaseId, uuid)) }
                ?.let { uuid -> KeePassNativeGroupIdentity(session.databaseId, uuid) },
        )
    }

    private fun Entry.toRecord(
        identity: KeePassNativeEntryIdentity,
        occurrenceIndex: Int,
        parentGroup: KeePassNativeGroupIdentity,
        legacyGroupPath: String?,
        isInRecycleBin: Boolean,
        isTemplate: Boolean,
        resolutionContext: takagi.ru.monica.utils.KeePassEntryResolutionContext?,
        binaryPool: Map<okio.ByteString, BinaryData>,
        customIcons: Map<UUID, CustomIcon>
    ): KeePassNativeEntryRecord {
        val fields = fields.toNativeFields(this, resolutionContext)
        return KeePassNativeEntryRecord(
            identity = identity,
            occurrenceIndex = occurrenceIndex,
            parentGroup = parentGroup,
            legacyGroupPath = legacyGroupPath,
            isInRecycleBin = isInRecycleBin,
            kind = classifyEntry(this, fields, isTemplate),
            title = fields.findValue("Title"),
            fields = fields,
            attachments = binaries.map { reference ->
                KeePassNativeAttachmentRecord(
                    name = reference.name,
                    hash = reference.hash.hex(),
                    binary = binaryPool[reference.hash]
                )
            },
            history = history.mapIndexed { index, version ->
                version.toHistoryVersion(index, resolutionContext, binaryPool, customIcons)
            },
            tags = tags.toList(),
            customData = customData.toMap(),
            autoType = autoType,
            icon = icon,
            customIconUuid = customIconUuid,
            customIcon = customIconUuid?.let(customIcons::get),
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            overrideUrl = overrideUrl,
            times = times,
            previousParentGroup = previousParentGroup,
            qualityCheck = qualityCheck,
            nativeEntry = this
        )
    }

    private fun Entry.toHistoryVersion(
        index: Int,
        resolutionContext: takagi.ru.monica.utils.KeePassEntryResolutionContext?,
        binaryPool: Map<okio.ByteString, BinaryData>,
        customIcons: Map<UUID, CustomIcon>
    ): KeePassNativeHistoryVersion {
        val nativeFields = fields.toNativeFields(this, resolutionContext)
        return KeePassNativeHistoryVersion(
            index = index,
            uuid = uuid,
            title = nativeFields.findValue("Title"),
            fields = nativeFields,
            attachments = binaries.map { reference ->
                KeePassNativeAttachmentRecord(
                    name = reference.name,
                    hash = reference.hash.hex(),
                    binary = binaryPool[reference.hash]
                )
            },
            tags = tags.toList(),
            customData = customData.toMap(),
            autoType = autoType,
            icon = icon,
            customIconUuid = customIconUuid,
            customIcon = customIconUuid?.let(customIcons::get),
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            overrideUrl = overrideUrl,
            times = times,
            previousParentGroup = previousParentGroup,
            qualityCheck = qualityCheck,
            nativeEntry = this
        )
    }

    private fun Map<String, EntryValue>.toNativeFields(
        entry: Entry,
        resolutionContext: takagi.ru.monica.utils.KeePassEntryResolutionContext?
    ): List<KeePassNativeFieldRecord> = entries.map { (name, value) ->
        val rawValue = value.safeContent()
        KeePassNativeFieldRecord(
            name = name,
            rawValue = rawValue,
            displayValue = KeePassFieldReferenceResolver.resolveValue(rawValue, entry, resolutionContext),
            isProtected = value is EntryValue.Encrypted,
            role = KeePassFieldRegistry.roleOf(name),
            nativeValue = value
        )
    }

    private fun classifyEntry(
        entry: Entry,
        fields: List<KeePassNativeFieldRecord>,
        isTemplate: Boolean
    ): KeePassNativeEntryKind {
        if (isTemplate) return KeePassNativeEntryKind.TEMPLATE

        when (fields.findValue("MonicaItemType").trim().uppercase(Locale.ROOT)) {
            "PASSWORD" -> return KeePassNativeEntryKind.PASSWORD
            "TOTP" -> return KeePassNativeEntryKind.TOTP
            "NOTE" -> return KeePassNativeEntryKind.NOTE
            "BANK_CARD" -> return KeePassNativeEntryKind.BANK_CARD
            "DOCUMENT" -> return KeePassNativeEntryKind.DOCUMENT
            "PASSKEY" -> return KeePassNativeEntryKind.PASSKEY
        }

        if (entry.fields.keys.any { key -> key.startsWith("KPEX_PASSKEY_", ignoreCase = true) } ||
            fields.findValue("MonicaPasskeyData").isNotBlank() ||
            fields.findValue("MonicaPasskeyCredentialId").isNotBlank()
        ) {
            return KeePassNativeEntryKind.PASSKEY
        }

        val hasCredential = sequenceOf("UserName", "Password", "URL")
            .any { fieldName -> fields.findValue(fieldName).isNotBlank() }
        val hasTotp = sequenceOf("otp", "TOTP Seed")
            .any { fieldName -> fields.findValue(fieldName).isNotBlank() }
        return when {
            hasCredential -> KeePassNativeEntryKind.PASSWORD
            hasTotp -> KeePassNativeEntryKind.TOTP
            else -> KeePassNativeEntryKind.UNKNOWN
        }
    }

    private fun isTemplateEntry(entry: Entry): Boolean = entry.fields.entries.any { (name, value) ->
        name.equals("_etm_template", ignoreCase = true) && value.safeContent().isNotBlank()
    }

    private fun containsReferenceToken(entry: Entry): Boolean {
        return entry.fields.values.any { value ->
            value.safeContent().contains("{REF:", ignoreCase = true)
        }
    }
}

internal enum class KeePassNativeSearchField {
    TITLE,
    USERNAME,
    PASSWORD,
    URL,
    NOTES,
    CUSTOM_FIELDS,
    TAGS,
    GROUP_NAME,
    ENTRY_UUID,
    GROUP_UUID
}

internal data class KeePassNativeSearchOptions(
    val query: String,
    val fields: Set<KeePassNativeSearchField> = DEFAULT_FIELDS,
    val groupScope: KeePassNativeGroupIdentity? = null,
    val includeSubgroups: Boolean = true,
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val searchProtectedValues: Boolean = false,
    val includeExpired: Boolean = true,
    val includeRecycleBin: Boolean = false,
    val includeTemplates: Boolean = false
) {
    companion object {
        val DEFAULT_FIELDS: Set<KeePassNativeSearchField> = linkedSetOf(
            KeePassNativeSearchField.TITLE,
            KeePassNativeSearchField.USERNAME,
            KeePassNativeSearchField.URL,
            KeePassNativeSearchField.NOTES,
            KeePassNativeSearchField.CUSTOM_FIELDS,
            KeePassNativeSearchField.TAGS,
            KeePassNativeSearchField.GROUP_NAME
        )
    }
}

internal data class KeePassNativeSearchMatch(
    val entry: KeePassNativeEntryRecord,
    val matchedFields: Set<KeePassNativeSearchField>,
    val matchedFieldNames: Set<String>
)

internal data class KeePassNativeSearchResult(
    val entries: List<KeePassNativeEntryRecord>,
    val matches: List<KeePassNativeSearchMatch>,
    val error: String? = null
)

internal object KeePassNativeSearch {
    fun search(
        browser: KeePassNativeBrowserSnapshot,
        options: KeePassNativeSearchOptions,
        now: Instant = Instant.now()
    ): KeePassNativeSearchResult {
        val matcher = try {
            SearchMatcher(options)
        } catch (error: IllegalArgumentException) {
            return KeePassNativeSearchResult(
                entries = emptyList(),
                matches = emptyList(),
                error = error.message ?: "Invalid regular expression"
            )
        }

        val allowedGroups = options.groupScope?.let { scope ->
            if (options.includeSubgroups) {
                browser.descendantGroupIdentities(scope)
            } else {
                setOf(scope).takeIf { browser.group(scope) != null }.orEmpty()
            }
        }
        val matches = browser.entries.asSequence()
            .filter { entry -> allowedGroups == null || entry.parentGroup in allowedGroups }
            .filter { entry -> options.includeRecycleBin || !entry.isInRecycleBin }
            .filter { entry -> options.includeTemplates || entry.kind != KeePassNativeEntryKind.TEMPLATE }
            .filter { entry -> options.includeExpired || !entry.isExpiredAt(now) }
            .mapNotNull { entry -> matchEntry(browser, entry, options, matcher) }
            .toList()
        return KeePassNativeSearchResult(
            entries = matches.map { match -> match.entry },
            matches = matches
        )
    }

    private fun matchEntry(
        browser: KeePassNativeBrowserSnapshot,
        entry: KeePassNativeEntryRecord,
        options: KeePassNativeSearchOptions,
        matcher: SearchMatcher
    ): KeePassNativeSearchMatch? {
        if (options.query.isEmpty()) {
            return KeePassNativeSearchMatch(entry, emptySet(), emptySet())
        }
        val matchedFields = linkedSetOf<KeePassNativeSearchField>()
        val matchedNames = linkedSetOf<String>()

        fun matchField(
            searchField: KeePassNativeSearchField,
            field: KeePassNativeFieldRecord?
        ) {
            if (searchField !in options.fields || field == null) return
            if (field.isProtected && !options.searchProtectedValues) return
            if (matcher.matches(field.displayValue)) {
                matchedFields += searchField
                matchedNames += field.name
            }
        }

        matchField(KeePassNativeSearchField.TITLE, entry.field("Title"))
        matchField(KeePassNativeSearchField.USERNAME, entry.field("UserName"))
        matchField(KeePassNativeSearchField.PASSWORD, entry.field("Password"))
        matchField(KeePassNativeSearchField.URL, entry.field("URL"))
        matchField(KeePassNativeSearchField.NOTES, entry.field("Notes"))

        if (KeePassNativeSearchField.CUSTOM_FIELDS in options.fields) {
            entry.fields.asSequence()
                .filterNot { field -> field.name.lowercase(Locale.ROOT) in STANDARD_FIELD_NAMES }
                .filter { field -> options.searchProtectedValues || !field.isProtected }
                .filter { field -> matcher.matches(field.displayValue) || matcher.matches(field.name) }
                .forEach { field ->
                    matchedFields += KeePassNativeSearchField.CUSTOM_FIELDS
                    matchedNames += field.name
                }
        }

        if (KeePassNativeSearchField.TAGS in options.fields && entry.tags.any(matcher::matches)) {
            matchedFields += KeePassNativeSearchField.TAGS
        }
        if (KeePassNativeSearchField.ENTRY_UUID in options.fields && matcher.matches(entry.identity.entryUuid.toString())) {
            matchedFields += KeePassNativeSearchField.ENTRY_UUID
        }
        if (KeePassNativeSearchField.GROUP_UUID in options.fields && matcher.matches(entry.parentGroup.groupUuid.toString())) {
            matchedFields += KeePassNativeSearchField.GROUP_UUID
        }
        if (KeePassNativeSearchField.GROUP_NAME in options.fields &&
            parentGroups(browser, entry.parentGroup).any { group -> matcher.matches(group.name) }
        ) {
            matchedFields += KeePassNativeSearchField.GROUP_NAME
        }

        return if (matchedFields.isEmpty()) null else KeePassNativeSearchMatch(entry, matchedFields, matchedNames)
    }

    private fun parentGroups(
        browser: KeePassNativeBrowserSnapshot,
        start: KeePassNativeGroupIdentity
    ): Sequence<KeePassNativeGroupRecord> = sequence {
        val visited = mutableSetOf<KeePassNativeGroupIdentity>()
        var current: KeePassNativeGroupIdentity? = start
        while (current != null && visited.add(current)) {
            val group = browser.group(current) ?: break
            yield(group)
            current = group.parentGroup
        }
    }

    private fun KeePassNativeEntryRecord.isExpiredAt(now: Instant): Boolean =
        times?.takeIf { value -> value.expires }
            ?.expiryTime
            ?.let { expiry -> !expiry.isAfter(now) }
            ?: false

    private class SearchMatcher(options: KeePassNativeSearchOptions) {
        private val query = options.query
        private val caseSensitive = options.caseSensitive
        private val regex = if (options.useRegex) {
            try {
                Regex(
                    pattern = query,
                    options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                )
            } catch (error: Throwable) {
                throw IllegalArgumentException(error.message ?: "Invalid regular expression", error)
            }
        } else {
            null
        }

        fun matches(value: String): Boolean = when {
            regex != null -> regex.containsMatchIn(value)
            else -> value.contains(query, ignoreCase = !caseSensitive)
        }
    }

    private val STANDARD_FIELD_NAMES = setOf("title", "username", "password", "url", "notes")
}

private fun EntryValue.safeContent(): String = runCatching { content }.getOrDefault("")

private fun List<KeePassNativeFieldRecord>.findValue(name: String): String =
    firstOrNull { field -> field.name.equals(name, ignoreCase = true) }?.displayValue.orEmpty()
