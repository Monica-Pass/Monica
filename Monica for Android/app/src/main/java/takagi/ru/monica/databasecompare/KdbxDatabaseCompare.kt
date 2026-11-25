/*
 * Comparison/merge design references KeePassXC src/core/Merger.cpp and Compare.h,
 * Copyright (C) 2018 KeePassXC Team <team@keepassxc.org>, GPL-2.0-or-GPL-3.0.
 * Reference revision: cd42236540c1cfca332c0f051d981acfdacf7b4e.
 * This Kotlin implementation uses explicit reviewed choices instead of newer-wins.
 * Distributed under Monica's GNU GPL version 3; see the repository LICENSE.
 */
package takagi.ru.monica.databasecompare

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import takagi.ru.monica.utils.KeePassCodecSupport
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object KdbxDatabaseCompare {
    fun open(left: CompareInput, right: CompareInput, workingDirectory: File, checkActive: () -> Unit = {}): NativeDatabaseComparison {
        checkActive()
        require(left.snapshotId == null && right.snapshotId == null) { "KDBX input must be a file" }
        require(left.file.isFile && right.file.isFile)
        require(left.file.length() <= MAX_FILE_BYTES && right.file.length() <= MAX_FILE_BYTES) { "KDBX comparison input is too large" }
        require(workingDirectory.isDirectory || workingDirectory.mkdirs())
        val leftHash = sha(left.file); val rightHash = sha(right.file)
        try {
            val a = decode(left, checkActive)
            checkActive()
            val b = decode(right, checkActive)
            checkActive()
            check(leftHash == sha(left.file) && rightHash == sha(right.file)) { "KDBX comparison source changed" }
            return Session(left.file, right.file, leftHash, rightHash, left.label, right.label, a, b)
        } catch (_: KdbxUnsupportedContent) {
            return Blocked(left.label, right.label, CompareProblem.UNSUPPORTED_CONTENT)
        }
    }

    private fun decode(input: CompareInput, checkActive: () -> Unit): KeePassDatabase {
        KdbxComparisonHeader.validate(input.file)
        // Kotpass consumes a raw 32-byte keyfile in-place when protecting its key.
        // Keep the caller's credential buffer intact for retries and its own cleanup.
        val credentials = input.authenticatedCredentials?.invoke() ?: if (input.keyFile != null && input.password.isEmpty()) Credentials.from(input.keyFile.copyOf())
        else input.keyFile?.let { Credentials.from(EncryptedValue.fromString(String(input.password)), it.copyOf()) }
            ?: Credentials.from(EncryptedValue.fromString(String(input.password)))
        return input.file.inputStream().buffered().use {
            KeePassDatabase.decode(it, credentials, contentParser = KdbxComparisonXml(checkActive), cipherProviders = KeePassCodecSupport.cipherProviders, maxDecompressedBytes = KdbxComparisonXml.MAX_XML_BYTES.toLong())
        }
    }

    private class Blocked(left: String, right: String, problem: CompareProblem) : NativeDatabaseComparison {
        private var value: ComparePlan? = ComparePlan(CompareFormat.KDBX, left, right, emptyList(), setOf(problem))
        override val plan get() = checkNotNull(value) { "Comparison closed" }
        override fun writeResult(output: File, choices: Map<String, CompareChoice>, password: CharArray, checkActive: () -> Unit) = error("Unsupported KDBX comparison")
        override fun close() { value = null }
    }

    private data class LocatedGroup(val group: Group, val parent: UUID?, val path: String)
    private data class LocatedEntry(val entry: Entry, val parent: UUID, val path: String)
    private data class Index(val groups: LinkedHashMap<UUID, LocatedGroup>, val entries: LinkedHashMap<UUID, LocatedEntry>)

    private class Session(
        private val leftFile: File, private val rightFile: File,
        private val leftHash: String, private val rightHash: String,
        leftName: String, rightName: String, left: KeePassDatabase, right: KeePassDatabase
    ) : NativeDatabaseComparison {
        private var leftDb: KeePassDatabase? = left
        private var rightDb: KeePassDatabase? = right
        private var leftIndex: Index? = index(left)
        private var rightIndex: Index? = index(right)
        @Volatile private var value: ComparePlan? = buildPlan(leftName, rightName, left, right, checkNotNull(leftIndex), checkNotNull(rightIndex))
        override val plan get() = checkNotNull(value) { "Comparison closed" }

        @Synchronized override fun close() {
            value = null; leftIndex = null; rightIndex = null; leftDb = null; rightDb = null
        }

        override fun writeResult(output: File, choices: Map<String, CompareChoice>, password: CharArray, checkActive: () -> Unit) {
            val p = plan; p.validateChoices(choices)
            require(password.isNotEmpty()) { "A result password is required" }
            require(!output.exists()) { "Result file already exists" }
            require(output.canonicalFile != leftFile.canonicalFile && output.canonicalFile != rightFile.canonicalFile)
            checkActive(); verifySources()
            val a = checkNotNull(leftDb); val b = checkNotNull(rightDb)
            val ai = checkNotNull(leftIndex); val bi = checkNotNull(rightIndex)
            fun selection(id: String) = choices[id] ?: p.rows.single { it.id == id }.defaultChoice ?: error("Unresolved comparison selection")
            val groups = linkedMapOf<UUID, LocatedGroup>()
            val entries = linkedMapOf<UUID, LocatedEntry>()
            val related = a.content.group.uuid == b.content.group.uuid
            for (id in ai.groups.keys + bi.groups.keys) {
                checkActive()
                val l = ai.groups[id]; val r = bi.groups[id]
                val picked = when (selection("group:$id")) { CompareChoice.LEFT -> l; CompareChoice.RIGHT -> r; CompareChoice.BOTH -> error("Group duplication is unsupported") }
                if (picked != null) groups[id] = if (!related && id == b.content.group.uuid) picked.copy(parent = a.content.group.uuid) else picked
            }
            for (id in ai.entries.keys + bi.entries.keys) {
                checkActive()
                val l = ai.entries[id]; val r = bi.entries[id]
                when (selection("entry:$id")) {
                    CompareChoice.LEFT -> if (l != null) entries[id] = if (r == null) l else l.copy(entry = retainHistory(l.entry, r.entry))
                    CompareChoice.RIGHT -> if (r != null) entries[id] = if (l == null) r else r.copy(entry = retainHistory(r.entry, l.entry))
                    CompareChoice.BOTH -> {
                        require(l != null && r != null && a.content.meta.customData.isEmpty() && b.content.meta.customData.isEmpty() && canDuplicate(l.entry, r.entry, ai, bi)) { "Referenced credentials cannot be duplicated" }
                        entries[id] = l
                        val copiedId = UUID.randomUUID()
                        fun reidentify(e: Entry): Entry = e.copy(uuid = copiedId, history = e.history.map(::reidentify))
                        entries[copiedId] = r.copy(entry = reidentify(r.entry))
                    }
                }
            }
            val deletions = linkedMapOf<UUID, DeletedObject>()
            val ad = a.content.deletedObjects.associateBy { it.id }; val bd = b.content.deletedObjects.associateBy { it.id }
            for (id in ad.keys + bd.keys) {
                val picked = when (selection("deletion:$id")) { CompareChoice.LEFT -> ad[id]; CompareChoice.RIGHT -> bd[id]; CompareChoice.BOTH -> error("Invalid deletion selection") }
                if (picked != null) deletions[id] = picked
                // KeePass permits an existing live+tombstone pair (KeePass #1752).
                // Retain that state only when both decisions select the same source.
                if (picked != null && (id in entries || id in groups)) {
                    val liveChoice = selection(if(id in entries) "entry:$id" else "group:$id")
                    val source = if(liveChoice == CompareChoice.LEFT) a else b
                    require(liveChoice != CompareChoice.BOTH && liveChoice == selection("deletion:$id") && source.content.deletedObjects.any { it == picked }) { "Live content conflicts with selected deletion" }
                }
            }
            val root = checkNotNull(groups[a.content.group.uuid]) { "Result root is required" }
            val visiting = mutableSetOf<UUID>(); val visited = mutableSetOf<UUID>()
            fun tree(id: UUID): Group {
                require(visiting.add(id)) { "Selected group moves form a cycle" }
                val located = checkNotNull(groups[id]) { "Selected parent group is missing" }
                val groupOrder = located.group.groups.map { it.uuid }
                val entryOrder = located.group.entries.map { it.uuid }
                val group = located.group.copy(
                    groups = groups.values.filter { it.parent == id }.sortedBy { groupOrder.indexOf(it.group.uuid).let { n -> if(n < 0) Int.MAX_VALUE else n } }.map { tree(it.group.uuid) },
                    entries = entries.values.filter { it.parent == id }.sortedBy { entryOrder.indexOf(it.entry.uuid).let { n -> if(n < 0) Int.MAX_VALUE else n } }.map { it.entry }
                )
                visiting.remove(id); visited.add(id); return group
            }
            val mergedRoot = tree(root.group.uuid)
            require(visited.size == groups.size && entries.values.all { it.parent in visited }) { "Selected content has missing parents" }
            val selectedMeta = when (selection("metadata")) { CompareChoice.LEFT -> a.content.meta; CompareChoice.RIGHT -> b.content.meta; CompareChoice.BOTH -> error("Invalid metadata selection") }
            val icons = linkedMapOf<UUID, CustomIcon>()
            for (id in a.content.meta.customIcons.keys + b.content.meta.customIcons.keys) {
                val icon = when (selection("icon:$id")) { CompareChoice.LEFT -> a.content.meta.customIcons[id]; CompareChoice.RIGHT -> b.content.meta.customIcons[id]; CompareChoice.BOTH -> error("Invalid icon selection") }
                if (icon != null) icons[id] = icon
            }
            var result = a.modifyContent { copy(group = mergedRoot, meta = selectedMeta.copy(customIcons = icons, headerHash = null), deletedObjects = deletions.values.toList()) }
            val requiredBinaries = referencedBinaries(mergedRoot).toMutableSet()
            val orphanA = orphanBinaries(a); val orphanB = orphanBinaries(b)
            for(hash in orphanA + orphanB) {
                val choice = selection("attachment:${hash.hex()}")
                if((choice == CompareChoice.LEFT && hash in orphanA) || (choice == CompareChoice.RIGHT && hash in orphanB)) requiredBinaries += hash
            }
            result = result.modifyBinaries { _ -> buildMap {
                putAll(a.binaries.filterKeys { it in requiredBinaries })
                b.binaries.forEach { (hash, blob) ->
                    if(hash !in requiredBinaries) return@forEach
                    val existing = get(hash)
                    require(existing == null || (existing.rawContent.contentEquals(blob.rawContent) && existing.memoryProtection == blob.memoryProtection)) { "Conflicting binary content" }
                    if (existing == null) put(hash, blob)
                }
            } }
            validateDependencies(result)
            val originalReferences = referenceTargets(a) + referenceTargets(b).also { rightTargets ->
                val leftTargets = referenceTargets(a)
                require(rightTargets.none { (text, id) -> leftTargets[text]?.let { it != id } == true }) { "Reference meaning differs between databases" }
            }
            require(referenceTargets(result).all { (text, id) -> originalReferences[text] == id }) { "Selected changes alter a field reference target" }
            val credentials = Credentials.from(EncryptedValue.fromString(String(password)))
            result = when (result) {
                is KeePassDatabase.Ver3x -> result.copy(credentials = credentials)
                is KeePassDatabase.Ver4x -> result.copy(credentials = credentials)
            }
            val expected = KdbxNativeFingerprint.database(result)
            val staging = File(output.parentFile, ".compare-${UUID.randomUUID()}.kdbx")
            try {
                checkActive(); check(value != null); verifySources()
                staging.outputStream().use { result.encode(it, contentParser = KdbxComparisonXml(checkActive), cipherProviders = KeePassCodecSupport.cipherProviders) }
                checkActive()
                val reopened = staging.inputStream().use { KeePassDatabase.decode(it, credentials, contentParser = KdbxComparisonXml(checkActive), cipherProviders = KeePassCodecSupport.cipherProviders, maxDecompressedBytes = KdbxComparisonXml.MAX_XML_BYTES.toLong()) }
                require(expected == KdbxNativeFingerprint.database(reopened)) { "Result verification did not preserve native KDBX content" }
                validateDependencies(reopened)
                verifySources()
                synchronized(this) {
                    checkActive(); check(value != null)
                    java.nio.file.Files.move(staging.toPath(), output.toPath())
                }
            } finally { if (staging.exists()) staging.delete() }
        }

        private fun verifySources() = check(leftHash == sha(leftFile) && rightHash == sha(rightFile)) { "KDBX comparison source changed" }
    }

    private fun buildPlan(leftName: String, rightName: String, a: KeePassDatabase, b: KeePassDatabase, ai: Index, bi: Index): ComparePlan {
        val blockers = mutableSetOf<CompareProblem>()
        if (a.header.version != b.header.version) blockers += CompareProblem.HISTORY_INCOMPATIBLE
        val ap = (a as? KeePassDatabase.Ver4x)?.header?.publicCustomData.orEmpty()
        val bp = (b as? KeePassDatabase.Ver4x)?.header?.publicCustomData.orEmpty()
        if (ap != bp) blockers += CompareProblem.UNSUPPORTED_CONTENT
        val related = a.content.group.uuid == b.content.group.uuid
        if (!related && (ai.groups.keys.intersect(bi.groups.keys).isNotEmpty() || ai.entries.keys.intersect(bi.entries.keys).isNotEmpty())) blockers += CompareProblem.IDENTITY_CONFLICT
        if ((ai.groups.keys + bi.groups.keys).intersect(ai.entries.keys + bi.entries.keys).isNotEmpty()) blockers += CompareProblem.IDENTITY_CONFLICT
        try { validateDependencies(a); validateDependencies(b) } catch (_: IllegalArgumentException) { blockers += CompareProblem.UNRESOLVED_REFERENCE }
        val rows = mutableListOf<CompareRow>()
        for (id in ai.groups.keys + bi.groups.keys) {
            val l = ai.groups[id]; val r = bi.groups[id]
            val shown = requireNotNull(l ?: r)
            val changed = l != null && r != null && (l.parent != r.parent || KdbxNativeFingerprint.group(l.group) != KdbxNativeFingerprint.group(r.group))
            val explicitlyDeleted = a.content.deletedObjects.any { it.id == id } || b.content.deletedObjects.any { it.id == id }
            val choices = if (id == a.content.group.uuid) setOfNotNull(if(l != null) CompareChoice.LEFT else null, if(r != null) CompareChoice.RIGHT else null)
                else if (explicitlyDeleted) setOf(CompareChoice.LEFT, CompareChoice.RIGHT)
                else if (l == null) setOf(CompareChoice.RIGHT) else if (r == null) setOf(CompareChoice.LEFT) else setOf(CompareChoice.LEFT, CompareChoice.RIGHT)
            rows += CompareRow("group:$id", shown.group.name, shown.path, "group", change(l,r,changed),
                groupFields(l,r), choices, if (l == null) CompareChoice.RIGHT else if (r == null || !changed) CompareChoice.LEFT else null)
        }
        for (id in ai.entries.keys + bi.entries.keys) {
            val l = ai.entries[id]; val r = bi.entries[id]
            val shown = requireNotNull(l ?: r)
            val changed = l != null && r != null && (l.parent != r.parent || KdbxNativeFingerprint.entry(l.entry) != KdbxNativeFingerprint.entry(r.entry))
            val choices = setOf(CompareChoice.LEFT, CompareChoice.RIGHT) + if (l != null && r != null && changed && a.content.meta.customData.isEmpty() && b.content.meta.customData.isEmpty() && canDuplicate(l.entry,r.entry,ai,bi)) setOf(CompareChoice.BOTH) else emptySet()
            rows += CompareRow("entry:$id", shown.entry.fields.title?.content.orEmpty(), shown.path, "entry", change(l,r,changed),
                entryFields(l,r), choices, if (l == null) CompareChoice.RIGHT else if (r == null || !changed) CompareChoice.LEFT else null)
        }
        val am = a.content.meta; val bm = b.content.meta; val metaChanged = KdbxNativeFingerprint.meta(am) != KdbxNativeFingerprint.meta(bm)
        rows += CompareRow("metadata", "metadata", "", "metadata", if (metaChanged) CompareChange.CHANGED else CompareChange.UNCHANGED,
            metaFields(am,bm), defaultChoice = if (metaChanged) null else CompareChoice.LEFT)
        for (id in am.customIcons.keys + bm.customIcons.keys) {
            val l = am.customIcons[id]; val r = bm.customIcons[id]
            rows += CompareRow("icon:$id", (l ?: r)?.name ?: id.toString(), "", "icon", change(l,r,l != r),
                listOf(CompareField("Name",l?.name,r?.name),CompareField("Bytes",l?.data?.size?.toString(),r?.data?.size?.toString(),false),CompareField("Content",l?.data?.let(::digest),r?.data?.let(::digest))),
                defaultChoice = if (l == null) CompareChoice.RIGHT else if (r == null || l == r) CompareChoice.LEFT else null)
        }
        val orphanA=orphanBinaries(a);val orphanB=orphanBinaries(b)
        for(hash in orphanA + orphanB) {
            val l=a.binaries[hash]?.takeIf { hash in orphanA };val r=b.binaries[hash]?.takeIf { hash in orphanB }
            val differs=l!=null && r!=null && (!l.rawContent.contentEquals(r.rawContent) || l.memoryProtection!=r.memoryProtection)
            rows += CompareRow("attachment:${hash.hex()}",hash.hex(),"","attachment",change(l,r,differs),
                listOf(CompareField("Bytes",l?.rawContent?.size?.toString(),r?.rawContent?.size?.toString(),false),CompareField("Protected",l?.memoryProtection?.toString(),r?.memoryProtection?.toString(),false)),
                defaultChoice=if(l==null) CompareChoice.RIGHT else if(r==null || !differs) CompareChoice.LEFT else null)
        }
        val ad = a.content.deletedObjects.associateBy { it.id }; val bd = b.content.deletedObjects.associateBy { it.id }
        for (id in ad.keys + bd.keys) {
            val l = ad[id]; val r = bd[id]
            val live = id in ai.entries || id in bi.entries || id in ai.groups || id in bi.groups
            rows += CompareRow("deletion:$id", ai.entries[id]?.entry?.fields?.title?.content ?: bi.entries[id]?.entry?.fields?.title?.content ?: ai.groups[id]?.group?.name ?: bi.groups[id]?.group?.name ?: id.toString(), "", "deletion", CompareChange.DELETION,
                listOf(CompareField("DeletionTime", l?.deletionTime?.toString(),r?.deletionTime?.toString(),false)),
                defaultChoice = if (live) null else if (l == null) CompareChoice.RIGHT else if (r == null || l == r) CompareChoice.LEFT else null)
        }
        return ComparePlan(CompareFormat.KDBX,leftName,rightName,rows,blockers)
    }

    private fun change(left: Any?, right: Any?, changed: Boolean) = when { left == null -> CompareChange.ONLY_RIGHT; right == null -> CompareChange.ONLY_LEFT; changed -> CompareChange.CHANGED; else -> CompareChange.UNCHANGED }
    private fun index(db: KeePassDatabase): Index {
        val groups = linkedMapOf<UUID,LocatedGroup>(); val entries = linkedMapOf<UUID,LocatedEntry>()
        fun visit(g: Group,parent: UUID?,path: String) {
            require(groups.put(g.uuid,LocatedGroup(g,parent,path)) == null) { "Duplicate native group identity" }
            g.entries.forEach { require(entries.put(it.uuid,LocatedEntry(it,g.uuid,path)) == null) { "Duplicate native entry identity" } }
            g.groups.forEach { visit(it,g.uuid,"$path/${it.name}") }
        }
        visit(db.content.group,null,db.content.group.name); return Index(groups,entries)
    }

    private fun retainHistory(chosen: Entry, other: Entry): Entry {
        if (KdbxNativeFingerprint.entry(chosen) == KdbxNativeFingerprint.entry(other)) return chosen
        val current = KdbxNativeFingerprint.entry(chosen,false)
        val history = (chosen.history + other.history + other.copy(history = emptyList())).distinctBy { KdbxNativeFingerprint.entry(it) }
            .filterNot { KdbxNativeFingerprint.entry(it,false) == current && it.history.isEmpty() }
            .sortedBy { it.times?.lastModificationTime }
        return chosen.copy(history = history)
    }

    private fun canDuplicate(a: Entry,b: Entry, ai: Index,bi: Index): Boolean {
        if ((ai.groups.values + bi.groups.values).any { it.group.customData.isNotEmpty() } ||
            (ai.entries.values + bi.entries.values).any { it.entry.customData.isNotEmpty() }) return false
        fun unsafe(e: Entry): Boolean = e.customData.isNotEmpty() || e.fields.any { (k,v) ->
            k.contains("passkey",true) || k.contains("fido",true) || k.contains("monica",true) || v.content.contains("{REF:",true)
        } || e.history.any(::unsafe)
        if (unsafe(a) || unsafe(b)) return false
        val hex = a.uuid.toString().replace("-", "")
        return (ai.entries.values + bi.entries.values).none { located -> referenceTexts(located.entry).any { it.contains(hex,true) || it.contains(a.uuid.toString(),true) || it.contains("{REF:",true) } } &&
            (ai.groups.values + bi.groups.values).none { it.group.defaultAutoTypeSequence?.contains("{REF:",true)==true }
    }

    private fun validateDependencies(db: KeePassDatabase) {
        val index = index(db); val icons = db.content.meta.customIcons; val binaries = db.binaries
        fun validateEntry(e: Entry) {
            require(e.customIconUuid == null || e.customIconUuid in icons) { "Missing custom icon" }
            require(e.binaries.all { it.hash in binaries }) { "Missing attachment" }
            require(e.history.all { it.uuid == e.uuid }) { "History identity mismatch" }
            referenceTexts(e).forEach { value ->
                val refs = REF.findAll(value).toList()
                require(REF_START.findAll(value).count() == refs.size) { "Unsupported field reference" }
                refs.forEach { match ->
                    val search = match.groupValues[2].uppercase(); val query = match.groupValues[3]
                    val candidates = if (search == "I") index.entries.values.filter { it.entry.uuid.toString().replace("-", "").equals(query.replace("-", ""),true) }
                    else {
                        val field = when(search) { "T" -> "Title"; "U" -> "UserName"; "A" -> "URL"; "P" -> "Password"; "N" -> "Notes"; else -> null }
                        require(field != null) { "Unsupported field reference selector" }
                        index.entries.values.filter { it.entry.fields[field]?.content?.equals(query,true) == true }
                    }
                    require(candidates.size == 1) { "Missing or ambiguous field reference" }
                }
            }
            e.history.forEach(::validateEntry)
        }
        index.entries.values.forEach { validateEntry(it.entry) }
        index.groups.values.forEach { require(it.group.customIconUuid == null || it.group.customIconUuid in icons) { "Missing group custom icon" } }
        val zero = UUID(0,0); val meta = db.content.meta
        require(meta.recycleBinUuid == null || meta.recycleBinUuid == zero || meta.recycleBinUuid in index.groups) { "Missing recycle bin" }
        require(meta.entryTemplatesGroup == null || meta.entryTemplatesGroup == zero || meta.entryTemplatesGroup in index.groups) { "Missing template group" }
        referenceTargets(db)
    }

    private fun referenceTargets(db: KeePassDatabase): Map<String, UUID> {
        val idx = index(db)
        val targets = linkedMapOf<String,UUID>()
        fun text(value: String) {
            val matches = REF.findAll(value).toList()
            require(REF_START.findAll(value).count() == matches.size) { "Unsupported field reference" }
            matches.forEach { match ->
                val search=match.groupValues[2].uppercase();val query=match.groupValues[3]
                val candidates=if(search=="I") idx.entries.values.filter { it.entry.uuid.toString().replace("-", "").equals(query.replace("-", ""),true) }
                else {
                    val field=requireNotNull(mapOf("T" to "Title","U" to "UserName","A" to "URL","P" to "Password","N" to "Notes")[search])
                    idx.entries.values.filter { it.entry.fields[field]?.content?.equals(query,true)==true }
                }
                require(candidates.size==1) { "Reference cannot be resolved uniquely" }
                targets[match.value]=candidates.single().entry.uuid
            }
        }
        fun visit(e: Entry) { referenceTexts(e).forEach(::text);e.history.forEach(::visit) }
        idx.entries.values.forEach { visit(it.entry) }
        idx.groups.values.forEach { g -> g.group.defaultAutoTypeSequence?.let(::text);g.group.customData.values.forEach { text(it.value) } }
        db.content.meta.customData.values.forEach { text(it.value) }
        // Validate field-reference cycles at field granularity: self Title references are
        // harmless when the referred field is literal, but recursive password cycles are not.
        val visiting = mutableSetOf<Pair<UUID,String>>();val completed=mutableSetOf<Pair<UUID,String>>()
        fun follow(node: Pair<UUID,String>) {
            if(node in completed) return
            require(visiting.add(node)) { "Cyclic field reference" }
            val value=idx.entries[node.first]?.entry?.fields?.get(node.second)?.content.orEmpty()
            REF.findAll(value).forEach { ref ->
                val field=mapOf("T" to "Title","U" to "UserName","A" to "URL","P" to "Password","N" to "Notes")[ref.groupValues[1].uppercase()]
                if(field!=null) follow(requireNotNull(targets[ref.value]) to field)
            }
            visiting.remove(node);completed.add(node)
        }
        idx.entries.values.forEach { e -> e.entry.fields.keys.forEach { follow(e.entry.uuid to it) } }
        return targets
    }

    private fun referenceTexts(e: Entry): List<String> = buildList {
        addAll(e.fields.values.map { it.content });add(e.overrideUrl)
        e.autoType?.let { a -> a.defaultSequence?.let(::add);a.items.forEach { add(it.window);add(it.keystrokeSequence) } }
        e.customData.values.forEach { add(it.value) }
    }
    private fun referencedBinaries(root: Group): Set<okio.ByteString> = buildSet {
        fun entry(e: Entry) { addAll(e.binaries.map { it.hash });e.history.forEach(::entry) }
        fun group(g: Group) { g.entries.forEach(::entry);g.groups.forEach(::group) }
        group(root)
    }
    private fun orphanBinaries(db: KeePassDatabase) = db.binaries.keys - referencedBinaries(db.content.group)

    private fun groupFields(l: LocatedGroup?,r: LocatedGroup?): List<CompareField> {
        val a=l?.group; val b=r?.group
        return listOf(CompareField("Name",a?.name,b?.name),CompareField("Notes",a?.notes,b?.notes),CompareField("Parent",l?.parent?.toString(),r?.parent?.toString(),false),CompareField("Tags",a?.tags?.joinToString("\n"),b?.tags?.joinToString("\n")),CompareField("Properties",a?.copy(groups=emptyList(),entries=emptyList())?.toString(),b?.copy(groups=emptyList(),entries=emptyList())?.toString()))
            .filter { it.left != it.right }
    }
    private fun entryFields(l: LocatedEntry?,r: LocatedEntry?): List<CompareField> {
        val a=l?.entry;val b=r?.entry
        return buildList {
            (a?.fields?.keys.orEmpty() + b?.fields?.keys.orEmpty()).forEach { key ->
                val av=a?.fields?.get(key);val bv=b?.fields?.get(key)
                if(av?.content!=bv?.content || (av is EntryValue.Encrypted)!=(bv is EntryValue.Encrypted)) {
                    add(CompareField(key,av?.content,bv?.content,generatedLabel=false))
                    if((av is EntryValue.Encrypted)!=(bv is EntryValue.Encrypted)) add(CompareField(key,av?.let { (it is EntryValue.Encrypted).toString() },bv?.let { (it is EntryValue.Encrypted).toString() },false,generatedLabel=false,protectedFlag=true))
                }
            }
            add(CompareField("Parent",l?.path,r?.path))
            add(CompareField("History",a?.history?.size?.toString(),b?.history?.size?.toString(),false))
            for(i in 0 until maxOf(a?.history?.size ?: 0,b?.history?.size ?: 0)) {
                val ah=a?.history?.getOrNull(i);val bh=b?.history?.getOrNull(i)
                (ah?.fields?.keys.orEmpty() + bh?.fields?.keys.orEmpty()).forEach { key ->
                    add(CompareField(key,ah?.fields?.get(key)?.content,bh?.fields?.get(key)?.content,generatedLabel=false,historyIndex=i+1))
                }
                add(CompareField("Version",ah?.let { KdbxNativeFingerprint.entry(it) },bh?.let { KdbxNativeFingerprint.entry(it) },historyIndex=i+1))
            }
            add(CompareField("Attachments",a?.binaries?.joinToString("\n") { "${it.name} · ${it.hash.hex()}" },b?.binaries?.joinToString("\n") { "${it.name} · ${it.hash.hex()}" }))
            add(CompareField("Tags",a?.tags?.joinToString("\n"),b?.tags?.joinToString("\n")))
            add(CompareField("AutoType",a?.autoType?.toString(),b?.autoType?.toString()))
            add(CompareField("CustomData",a?.customData?.toString(),b?.customData?.toString()))
            add(CompareField("Times",a?.times?.toString(),b?.times?.toString(),false))
            add(CompareField("Properties",a?.let { "${it.icon};${it.customIconUuid};${it.foregroundColor};${it.backgroundColor};${it.overrideUrl};${it.qualityCheck};${it.previousParentGroup}" },b?.let { "${it.icon};${it.customIconUuid};${it.foregroundColor};${it.backgroundColor};${it.overrideUrl};${it.qualityCheck};${it.previousParentGroup}" }))
        }.filter { it.left != it.right }
    }
    private fun metaFields(a: Meta,b: Meta) = listOf(
        CompareField("Name",a.name,b.name),CompareField("Description",a.description,b.description),CompareField("DefaultUser",a.defaultUser,b.defaultUser),
        CompareField("RecycleBin",a.recycleBinUuid?.toString(),b.recycleBinUuid?.toString(),false),CompareField("Templates",a.entryTemplatesGroup?.toString(),b.entryTemplatesGroup?.toString(),false),
        CompareField("RecycleBinEnabled",a.recycleBinEnabled.toString(),b.recycleBinEnabled.toString(),false),
        CompareField("HistoryMaxItems",a.historyMaxItems.toString(),b.historyMaxItems.toString(),false),CompareField("HistoryMaxSize",a.historyMaxSize.toString(),b.historyMaxSize.toString(),false),
        CompareField("MaintenanceHistoryDays",a.maintenanceHistoryDays.toString(),b.maintenanceHistoryDays.toString(),false),
        CompareField("MemoryProtection",a.memoryProtection.toString(),b.memoryProtection.toString(),false),CompareField("Color",a.color,b.color,false),
        CompareField("MasterKeyChangeRec",a.masterKeyChangeRec.toString(),b.masterKeyChangeRec.toString(),false),CompareField("MasterKeyChangeForce",a.masterKeyChangeForce.toString(),b.masterKeyChangeForce.toString(),false),
        CompareField("CustomData",a.customData.toString(),b.customData.toString()),CompareField("NativeMetadata",KdbxNativeFingerprint.meta(a),KdbxNativeFingerprint.meta(b))
    ).filter { it.left != it.right }

    private fun sha(file: File): String = file.inputStream().use { input -> val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)};md.digest().joinToString(""){"%02x".format(it)} }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val REF = Regex("\\{REF:([TUPANI])@([TUPANI]):([^{}]+)\\}",RegexOption.IGNORE_CASE)
    private val REF_START = Regex("\\{REF:",RegexOption.IGNORE_CASE)
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
}
