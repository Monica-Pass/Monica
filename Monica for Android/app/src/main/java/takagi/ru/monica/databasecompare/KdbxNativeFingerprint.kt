package takagi.ru.monica.databasecompare

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.*
import java.security.MessageDigest

/** Length-framed native values; no delimiter ambiguity, normalization, or persisted secret hashes. */
internal object KdbxNativeFingerprint {
    private class Digest {
        val md = MessageDigest.getInstance("SHA-256")
        fun value(value: Any?) {
            if (value == null) { md.update(0.toByte()); return }
            md.update(1.toByte())
            // KDBX 3 stores ISO instants with fractional seconds. Keep that
            // precision when comparing current entries and deduplicating history.
            val bytes = when (value) { is ByteArray -> value; else -> value.toString().toByteArray(Charsets.UTF_8) }
            md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); md.update(bytes)
        }
        fun custom(data: Map<String, CustomDataValue>) {
            value(data.size); data.toSortedMap().forEach { (k, v) -> value(k); value(v.value); value(v.lastModified) }
        }
        fun times(t: TimeData?) {
            value(t != null)
            t?.let { value(it.creationTime); value(it.lastModificationTime); value(it.lastAccessTime); value(it.locationChanged); value(it.expiryTime); value(it.expires); value(it.usageCount) }
        }
        fun entry(e: Entry, history: Boolean = true) {
            value(e.uuid); value(e.icon); value(e.customIconUuid); value(e.foregroundColor.orEmpty()); value(e.backgroundColor.orEmpty()); value(e.overrideUrl)
            times(e.times); value(e.previousParentGroup); value(e.qualityCheck)
            value(e.fields.size); e.fields.forEach { (k,v) -> value(k); value(v is EntryValue.Encrypted); value(v.content) }
            value(e.tags.size); e.tags.forEach(::value)
            value(e.binaries.size); e.binaries.forEach { value(it.name); value(it.hash.hex()) }
            custom(e.customData)
            value(e.autoType != null); e.autoType?.let { a -> value(a.enabled); value(a.obfuscation); value(a.defaultSequence.orEmpty()); value(a.items.size); a.items.forEach { value(it.window); value(it.keystrokeSequence) } }
            if (history) { value(e.history.size); e.history.forEach { entry(it) } }
        }
        fun group(g: Group, children: Boolean) {
            value(g.uuid); value(g.name); value(g.notes); value(g.icon); value(g.customIconUuid); times(g.times)
            value(g.expanded); value(g.defaultAutoTypeSequence.orEmpty()); value(g.enableAutoType); value(g.enableSearching); value(g.lastTopVisibleEntry); value(g.previousParentGroup)
            value(g.tags.size); g.tags.forEach(::value); custom(g.customData)
            if (children) { value(g.groups.size); g.groups.forEach { group(it, true) }; value(g.entries.size); g.entries.forEach { entry(it) } }
            else { value(g.groups.size); g.groups.forEach { value(it.uuid) }; value(g.entries.size); g.entries.forEach { value(it.uuid) } }
        }
        fun meta(m: Meta) {
            value(m.settingsChanged); value(m.name); value(m.nameChanged); value(m.description); value(m.descriptionChanged)
            value(m.defaultUser); value(m.defaultUserChanged); value(m.maintenanceHistoryDays); value(m.color.orEmpty())
            value(m.masterKeyChanged); value(m.masterKeyChangeRec); value(m.masterKeyChangeForce)
            value(m.recycleBinEnabled); value(m.recycleBinUuid); value(m.recycleBinChanged); value(m.entryTemplatesGroup); value(m.entryTemplatesGroupChanged)
            value(m.historyMaxItems); value(m.historyMaxSize); value(m.lastSelectedGroup); value(m.lastTopVisibleGroup)
            value(m.memoryProtection.size); m.memoryProtection.sortedBy { it.name }.forEach(::value); custom(m.customData)
        }
        fun result() = md.digest().joinToString("") { "%02x".format(it) }
    }
    fun entry(e: Entry, history: Boolean = true): String = Digest().apply { entry(e, history) }.result()
    fun group(g: Group): String = Digest().apply { group(g, false) }.result()
    fun meta(m: Meta): String = Digest().apply { meta(m) }.result()
    fun database(db: KeePassDatabase): String = Digest().apply {
        meta(db.content.meta); group(db.content.group, true)
        value(db.content.deletedObjects.size); db.content.deletedObjects.sortedBy { it.id.toString() }.forEach { value(it.id); value(it.deletionTime) }
        value(db.content.meta.customIcons.size)
        db.content.meta.customIcons.entries.sortedBy { it.key.toString() }.forEach { (id,icon) -> value(id); value(icon.name.orEmpty()); value(icon.lastModified); value(icon.data) }
        value(db.binaries.size)
        db.binaries.entries.sortedBy { it.key.hex() }.forEach { (hash,binary) -> value(hash.hex()); value(binary.memoryProtection); value(binary.rawContent) }
    }.result()
}
