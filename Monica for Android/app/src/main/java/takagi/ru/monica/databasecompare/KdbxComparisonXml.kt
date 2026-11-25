package takagi.ru.monica.databasecompare

import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.XmlContentParser
import takagi.ru.monica.keepass.KeePassXmlContentParser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/** Never let a comparison silently discard XML which the native model cannot represent. */
internal class KdbxComparisonXml(private val checkActive: () -> Unit = {}) : XmlContentParser {
    private data class Frame(val name: String, val compressedBinary: Boolean = false, val children: MutableSet<String> = mutableSetOf(), val keys: MutableSet<String> = mutableSetOf(), val text: StringBuilder = StringBuilder())
    override fun unmarshalContent(xmlData: ByteArray, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
        require(xmlData.size <= MAX_XML_BYTES) { "KDBX comparison XML is too large" }
        val counts = scan(xmlData)
        checkActive()
        val content = KeePassXmlContentParser.unmarshalContent(xmlData, contextBlock)
        fun entryCount(e: app.keemobile.kotpass.models.Entry, associations: Boolean): Int =
            (if (associations) e.autoType?.items?.size ?: 0 else e.binaries.size) + e.history.sumOf { entryCount(it, associations) }
        fun groupCount(g: app.keemobile.kotpass.models.Group, associations: Boolean): Int =
            g.entries.sumOf { entryCount(it, associations) } + g.groups.sumOf { groupCount(it, associations) }
        if (counts.binaryReferences != groupCount(content.group, false) || counts.associations != groupCount(content.group, true) || counts.icons != content.meta.customIcons.size || counts.deletions != content.deletedObjects.size) throw KdbxUnsupportedContent()
        return content
    }

    override fun unmarshalContent(source: InputStream, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
        val bytes = source.readBytesBounded(MAX_XML_BYTES)
        return try { unmarshalContent(bytes, contextBlock) } finally { bytes.fill(0) }
    }

    override fun marshalContent(context: XmlContext.Encode, content: DatabaseContent, pretty: Boolean): String =
        KeePassXmlContentParser.marshalContent(context, content, pretty)

    internal fun validate(bytes: ByteArray) { scan(bytes) }

    private data class Counts(val binaryReferences: Int, val associations: Int, val icons: Int, val deletions: Int)
    private fun scan(bytes: ByteArray): Counts {
        if (bytes.size > MAX_XML_BYTES) throw KdbxUnsupportedContent()
        val handler = object : DefaultHandler2() {
            val stack = ArrayDeque<Frame>()
            var count = 0
            var binaryReferences = 0
            var associations = 0
            var icons = 0
            var deletions = 0
            var expandedBinaryBytes = 0L
            val binaryIds = mutableSetOf<Int>()
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (++count % 128 == 0) checkActive()
                if (count > 500_000 || stack.size > 128) throw KdbxUnsupportedContent()
                val parent = stack.lastOrNull()
                if(qName == "Binary" && parent?.name == "Entry") binaryReferences++
                if(qName == "Association" && parent?.name == "AutoType") associations++
                if(qName == "Icon" && parent?.name == "CustomIcons") icons++
                if(qName == "DeletedObject" && parent?.name == "DeletedObjects") deletions++
                if(qName == "Binary" && parent?.name == "Binaries") {
                    val id = attributes.getValue("ID")?.toIntOrNull() ?: throw KdbxUnsupportedContent()
                    if(id < 0 || !binaryIds.add(id)) throw KdbxUnsupportedContent()
                }
                val allowed = when (parent?.name) {
                    null -> setOf("KeePassFile")
                    "KeePassFile" -> setOf("Meta", "Root")
                    "Root" -> setOf("Group", "DeletedObjects")
                    "Meta" -> META
                    "Group" -> GROUP
                    "Entry" -> ENTRY
                    "Times" -> TIMES
                    "String" -> setOf("Key", "Value")
                    "Binary" -> if (stack.elementAtOrNull(stack.size - 2)?.name == "Binaries") emptySet() else setOf("Key", "Value")
                    "AutoType" -> setOf("Enabled", "DataTransferObfuscation", "DefaultSequence", "Association")
                    "Association" -> setOf("Window", "KeystrokeSequence")
                    "CustomData" -> setOf("Item")
                    "Item" -> setOf("Key", "Value", "LastModificationTime")
                    "History" -> setOf("Entry")
                    "DeletedObjects" -> setOf("DeletedObject")
                    "DeletedObject" -> setOf("UUID", "DeletionTime")
                    "CustomIcons" -> setOf("Icon")
                    "Icon" -> setOf("UUID", "Data", "Name", "LastModificationTime")
                    "MemoryProtection" -> setOf("ProtectTitle", "ProtectUserName", "ProtectPassword", "ProtectURL", "ProtectNotes")
                    "Binaries" -> setOf("Binary")
                    else -> emptySet()
                }
                if (qName !in allowed) throw KdbxUnsupportedContent()
                val repeated = (parent?.name to qName) in REPEATED
                if (parent != null && !parent.children.add(qName) && !repeated) throw KdbxUnsupportedContent()
                val permittedAttributes = when {
                    qName == "Value" && parent?.name == "String" -> setOf("Protected")
                    qName == "Value" && parent?.name == "Binary" -> setOf("Ref")
                    qName == "Binary" && parent?.name == "Binaries" -> setOf("ID", "Compressed")
                    else -> emptySet()
                }
                for (i in 0 until attributes.length) {
                    if (attributes.getQName(i) !in permittedAttributes) throw KdbxUnsupportedContent()
                    if(attributes.getQName(i) in setOf("Protected", "Compressed") && attributes.getValue(i).lowercase() !in setOf("true", "false")) throw KdbxUnsupportedContent()
                }
                stack.addLast(Frame(qName, qName == "Binary" && parent?.name == "Binaries" && attributes.getValue("Compressed").equals("true", true)))
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { stack.lastOrNull()?.text?.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val frame = stack.removeLast()
                if (frame.name == "Binary" && stack.lastOrNull()?.name == "Binaries") {
                    // KDBX 3 can embed another gzip stream inside otherwise small XML.
                    // Count its expanded bytes with a fixed buffer before constructing native models.
                    val binary = try { Base64.getDecoder().decode(frame.text.filterNot(Char::isWhitespace).toString()) }
                        catch (_: IllegalArgumentException) { throw KdbxUnsupportedContent() }
                    try {
                        val source = if(frame.compressedBinary) GZIPInputStream(binary.inputStream()) else binary.inputStream()
                        source.use { input ->
                            val buffer = ByteArray(8192)
                            while(true) {
                                checkActive()
                                val n = input.read(buffer)
                                if(n < 0) break
                                expandedBinaryBytes += n
                                if(expandedBinaryBytes > MAX_XML_BYTES) throw KdbxUnsupportedContent()
                            }
                            buffer.fill(0)
                        }
                    } finally { binary.fill(0) }
                }
                if (frame.name == "Key") {
                    val parent = stack.lastOrNull()
                    val owner = stack.elementAtOrNull(stack.size - 2)
                    if (parent?.name in setOf("String", "Item") && owner != null && !owner.keys.add(frame.text.toString())) {
                        throw KdbxUnsupportedContent()
                    }
                }
                if(frame.name == "UUID" && stack.lastOrNull()?.name in setOf("Icon", "DeletedObject")) {
                    if(stack.elementAtOrNull(stack.size - 2)?.keys?.add(frame.text.toString()) == false) throw KdbxUnsupportedContent()
                }
                if(frame.name == "Binaries" && binaryIds.sorted() != (0 until binaryIds.size).toList()) throw KdbxUnsupportedContent()
                if (frame.name in setOf("String", "Item") && !frame.children.containsAll(setOf("Key", "Value"))) throw KdbxUnsupportedContent()
                if (frame.name == "Association" && !frame.children.containsAll(setOf("Window", "KeystrokeSequence"))) throw KdbxUnsupportedContent()
                if (frame.name in setOf("Group", "Entry", "DeletedObject", "Icon") && "UUID" !in frame.children) throw KdbxUnsupportedContent()
                val scalar = frame.text.toString()
                if (frame.name in BOOLEANS && !scalar.equals("true", true) && !scalar.equals("false", true)) throw KdbxUnsupportedContent()
                if (frame.name in setOf("EnableAutoType", "EnableSearching") && !scalar.equals("null", true) && !scalar.equals("true", true) && !scalar.equals("false", true)) throw KdbxUnsupportedContent()
                if (frame.name == "IconID" && frame.text.toString().toIntOrNull() !in 0..68) throw KdbxUnsupportedContent()
                if (frame.name == "DataTransferObfuscation" && frame.text.toString() !in setOf("0", "1")) throw KdbxUnsupportedContent()
                // Kotpass omits this flag. False has no effect; an active force-once policy cannot be discarded.
                if (frame.name == "MasterKeyChangeForceOnce" && !frame.text.toString().equals("false", true)) throw KdbxUnsupportedContent()
            }
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw KdbxUnsupportedContent() }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw KdbxUnsupportedContent() }
        }
        val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(bytes.inputStream()))
        return Counts(handler.binaryReferences, handler.associations, handler.icons, handler.deletions)
    }

    companion object {
        const val MAX_XML_BYTES = 64 * 1024 * 1024
        private val BOOLEANS = setOf("RecycleBinEnabled", "ProtectTitle", "ProtectUserName", "ProtectPassword", "ProtectURL", "ProtectNotes", "IsExpanded", "QualityCheck", "Expires", "Enabled")
        private val META = setOf("Generator", "HeaderHash", "SettingsChanged", "DatabaseName", "DatabaseNameChanged", "DatabaseDescription", "DatabaseDescriptionChanged", "DefaultUserName", "DefaultUserNameChanged", "MaintenanceHistoryDays", "Color", "MasterKeyChanged", "MasterKeyChangeRec", "MasterKeyChangeForce", "MasterKeyChangeForceOnce", "RecycleBinEnabled", "RecycleBinUUID", "RecycleBinChanged", "EntryTemplatesGroup", "EntryTemplatesGroupChanged", "HistoryMaxItems", "HistoryMaxSize", "LastSelectedGroup", "LastTopVisibleGroup", "MemoryProtection", "CustomIcons", "CustomData", "Binaries")
        private val GROUP = setOf("UUID", "Name", "Notes", "IconID", "CustomIconUUID", "Times", "IsExpanded", "DefaultAutoTypeSequence", "EnableAutoType", "EnableSearching", "LastTopVisibleEntry", "PreviousParentGroup", "Tags", "Group", "Entry", "CustomData")
        private val ENTRY = setOf("UUID", "IconID", "CustomIconUUID", "ForegroundColor", "BackgroundColor", "OverrideURL", "Tags", "QualityCheck", "PreviousParentGroup", "Times", "String", "Binary", "CustomData", "AutoType", "History")
        private val TIMES = setOf("CreationTime", "LastModificationTime", "LastAccessTime", "ExpiryTime", "Expires", "UsageCount", "LocationChanged")
        private val REPEATED = setOf("Group" to "Group", "Group" to "Entry", "Entry" to "String", "Entry" to "Binary", "History" to "Entry", "CustomData" to "Item", "CustomIcons" to "Icon", "Binaries" to "Binary", "DeletedObjects" to "DeletedObject", "AutoType" to "Association")
    }
}

internal class KdbxUnsupportedContent : SAXException("KDBX contains data this comparison cannot preserve")

internal fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size().toLong() + n <= limit) { "KDBX comparison input is too large" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
