package takagi.ru.monica.data.dedup

import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.XmlContentParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import takagi.ru.monica.keepass.KeePassXmlContentParser

/** Dedicated scan parser: normal vault opening remains unchanged. Unknown XML is never a complete proof. */
internal class SingleVaultDedupKeePassXmlAudit : XmlContentParser {
    var complete: Boolean = true
        private set

    override fun unmarshalContent(xmlData: ByteArray, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
        require(xmlData.size <= 32 * 1024 * 1024)
        val observed = mutableMapOf<String, Int>()
        val audited = audit(xmlData) { kind -> observed[kind] = (observed[kind] ?: 0) + 1 }
        var nativeContext: XmlContext.Decode? = null
        val decoded = KeePassXmlContentParser.unmarshalContent(xmlData) { meta -> contextBlock(meta).also { nativeContext = it } }
        val decodedCounts = mutableMapOf("Icon" to decoded.meta.customIcons.size, "DeletedObject" to decoded.deletedObjects.size)
        fun countEntry(entry: app.keemobile.kotpass.models.Entry) {
            decodedCounts["Entry"] = (decodedCounts["Entry"] ?: 0) + 1
            decodedCounts["Reference"] = (decodedCounts["Reference"] ?: 0) + entry.binaries.size
            decodedCounts["Association"] = (decodedCounts["Association"] ?: 0) + (entry.autoType?.items?.size ?: 0)
            entry.history.forEach(::countEntry)
        }
        fun countGroup(group: app.keemobile.kotpass.models.Group) {
            decodedCounts["Group"] = (decodedCounts["Group"] ?: 0) + 1
            group.entries.forEach(::countEntry); group.groups.forEach(::countGroup)
        }
        countGroup(decoded.group)
        complete = complete && audited && setOf("Entry", "Group", "Reference", "Icon", "DeletedObject", "Association").all { observed[it].orZero() == decodedCounts[it].orZero() }
        if ((observed["XmlBinary"] ?: 0) > 0 && observed["XmlBinary"] != nativeContext?.binaries?.size) complete = false
        return decoded
    }
    override fun unmarshalContent(source: InputStream, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= 32L * 1024 * 1024) { "Native dedup snapshot exceeds supported size" }
            output.write(buffer, 0, read)
        }
        return unmarshalContent(output.toByteArray(), contextBlock)
    }
    override fun marshalContent(context: XmlContext.Encode, content: DatabaseContent, pretty: Boolean): String =
        KeePassXmlContentParser.marshalContent(context, content, pretty)

    companion object {
        private val children = mapOf(
            "" to "KeePassFile", "KeePassFile" to "Meta Root", "Root" to "Group DeletedObjects",
            "Meta" to "Generator HeaderHash SettingsChanged DatabaseName DatabaseNameChanged DatabaseDescription DatabaseDescriptionChanged DefaultUserName DefaultUserNameChanged MaintenanceHistoryDays Color MasterKeyChanged MasterKeyChangeRec MasterKeyChangeForce RecycleBinEnabled RecycleBinUUID RecycleBinChanged EntryTemplatesGroup EntryTemplatesGroupChanged HistoryMaxItems HistoryMaxSize LastSelectedGroup LastTopVisibleGroup Binaries MemoryProtection CustomIcons CustomData",
            "Group" to "UUID Name Notes IconID CustomIconUUID Tags IsExpanded DefaultAutoTypeSequence EnableAutoType EnableSearching LastTopVisibleEntry PreviousParentGroup Times Group Entry CustomData",
            "Entry" to "UUID IconID CustomIconUUID ForegroundColor BackgroundColor OverrideURL Tags History QualityCheck PreviousParentGroup Times AutoType String Binary CustomData",
            "History" to "Entry", "String" to "Key Value", "Binary" to "Key Value", "Binaries" to "Binary",
            "Times" to "CreationTime LastModificationTime LastAccessTime ExpiryTime Expires UsageCount LocationChanged",
            "AutoType" to "Enabled DataTransferObfuscation DefaultSequence Association", "Association" to "Window KeystrokeSequence",
            "CustomData" to "Item", "Item" to "Key Value LastModificationTime", "CustomIcons" to "Icon", "Icon" to "UUID Data Name LastModificationTime",
            "MemoryProtection" to "ProtectTitle ProtectUserName ProtectPassword ProtectURL ProtectNotes", "DeletedObjects" to "DeletedObject", "DeletedObject" to "UUID DeletionTime"
        ).mapValues { it.value.split(' ').toSet() }
        private val repeated = mapOf("Group" to setOf("Group", "Entry"), "Entry" to setOf("String", "Binary"),
            "History" to setOf("Entry"), "Binaries" to setOf("Binary"), "CustomData" to setOf("Item"),
            "CustomIcons" to setOf("Icon"), "AutoType" to setOf("Association"), "DeletedObjects" to setOf("DeletedObject"))
        private val booleans = setOf("RecycleBinEnabled", "IsExpanded", "QualityCheck", "Expires", "Enabled", "ProtectTitle", "ProtectUserName", "ProtectPassword", "ProtectURL", "ProtectNotes")
        private val uuids = setOf("UUID", "CustomIconUUID", "PreviousParentGroup", "RecycleBinUUID", "EntryTemplatesGroup", "LastSelectedGroup", "LastTopVisibleGroup", "LastTopVisibleEntry")

        private fun canonicalBinary(value: String, size: Int): Boolean = runCatching {
            val decoded = java.util.Base64.getDecoder().decode(value)
            decoded.size == size && java.util.Base64.getEncoder().encodeToString(decoded) == value
        }.getOrDefault(false)

        private fun completeTimestamp(value: String): Boolean = value.isEmpty() || if (':' in value) {
            runCatching { java.time.Instant.parse(value).nano == 0 && '.' !in value }.getOrDefault(false)
        } else canonicalBinary(value, 8)
        private class Frame(val name: String) {
            val text = StringBuilder()
            val seen = mutableSetOf<String>()
            val keys = mutableSetOf<String>()
        }

        private fun Int?.orZero() = this ?: 0

        fun audit(bytes: ByteArray, observe: (String) -> Unit = {}): Boolean {
            var valid = true
            val stack = ArrayDeque<Frame>()
            val handler = object : DefaultHandler2() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    require(stack.size < 64) { "Native XML nesting limit" }
                    val parent = stack.lastOrNull()
                    if (qName in setOf("Group", "Entry", "Icon", "DeletedObject", "Association")) observe(qName)
                    if (qName == "Binary" && parent?.name == "Entry") observe("Reference")
                    if (qName == "Binary" && parent?.name == "Binaries") observe("XmlBinary")
                    if (qName !in children[parent?.name.orEmpty()].orEmpty()) valid = false
                    if (parent != null && !parent.seen.add(qName) && qName !in repeated[parent.name].orEmpty()) valid = false
                    val allowedAttributes = when {
                        qName == "Value" && parent?.name == "String" -> setOf("Protected")
                        qName == "Value" && parent?.name == "Binary" -> setOf("Ref")
                        qName == "Binary" && parent?.name == "Binaries" -> setOf("ID", "Compressed")
                        else -> emptySet()
                    }
                    for (i in 0 until attributes.length) {
                        val name = attributes.getQName(i)
                        if (name !in allowedAttributes) valid = false
                        if (name in setOf("Protected", "ProtectInMemory", "Compressed") && attributes.getValue(i) !in setOf("True", "False")) valid = false
                        if (name == "ID" && parent != null) {
                            val id = attributes.getValue(i)
                            if (id.toIntOrNull()?.takeIf { it >= 0 }?.toString() != id || !parent.keys.add(id)) valid = false
                        }
                        if (name == "Ref" && attributes.getValue(i).toIntOrNull()?.takeIf { it >= 0 }?.toString() != attributes.getValue(i)) valid = false
                    }
                    stack.addLast(Frame(qName))
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { stack.lastOrNull()?.text?.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val frame = stack.removeLast()
                    val value = frame.text.toString()
                    when (frame.name) {
                        "KeePassFile" -> if (!frame.seen.containsAll(setOf("Meta", "Root"))) valid = false
                        "Root" -> if ("Group" !in frame.seen) valid = false
                        "Group", "Entry" -> if ("UUID" !in frame.seen) valid = false
                        "String", "Item" -> if (!frame.seen.containsAll(setOf("Key", "Value"))) valid = false
                        "Binary" -> if (stack.lastOrNull()?.name == "Entry" && !frame.seen.containsAll(setOf("Key", "Value"))) valid = false
                        "Icon" -> if (!frame.seen.containsAll(setOf("UUID", "Data"))) valid = false
                        "DeletedObject" -> if (!frame.seen.containsAll(setOf("UUID", "DeletionTime"))) valid = false
                        "Binaries" -> if (frame.keys != frame.keys.indices.map { it.toString() }.toSet()) valid = false
                        "Association" -> if (!frame.seen.containsAll(setOf("Window", "KeystrokeSequence"))) valid = false
                    }
                    if (frame.name in setOf("Window", "KeystrokeSequence") && value.isEmpty()) valid = false
                    if (frame.name == "DataTransferObfuscation" && value !in setOf("0", "1")) valid = false
                    if (frame.name.endsWith("Time") || frame.name.endsWith("Changed")) {
                        if (!completeTimestamp(value)) valid = false
                    }
                    // Nullable references are written as empty elements by the native codec.
                    // Actual object UUIDs remain mandatory; nonempty references stay canonical.
                    if (frame.name in uuids && !(frame.name != "UUID" && value.isEmpty()) && !canonicalBinary(value, 16)) valid = false
                    if (frame.name in booleans && value !in setOf("True", "False")) valid = false
                    if (frame.name in setOf("EnableAutoType", "EnableSearching") && value !in setOf("True", "False", "Null")) valid = false
                    if (frame.name == "IconID" && value.toIntOrNull() !in app.keemobile.kotpass.constants.PredefinedIcon.entries.indices) valid = false
                    if (frame.name == "Key") {
                        if (value.isEmpty()) valid = false
                        val container = stack.toList().dropLast(1).lastOrNull()
                        if (container != null && !container.keys.add(value)) valid = false
                    }
                    if (frame.name == "UUID" && stack.lastOrNull()?.name == "Icon") {
                        val icons = stack.toList().dropLast(1).lastOrNull()
                        if (icons != null && !icons.keys.add(value)) valid = false
                    }
                    if (frame.name == "Entry" && !frame.keys.containsAll(setOf("Title", "UserName", "Password", "URL", "Notes"))) valid = false
                }
                override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD is not supported") }
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("Entities are not supported") }
            }
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.parse(InputSource(bytes.inputStream()))
            return valid
        }
    }
}
