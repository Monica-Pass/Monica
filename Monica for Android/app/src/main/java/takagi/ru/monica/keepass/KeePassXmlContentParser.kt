package takagi.ru.monica.keepass

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.TextElement
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * The XML reader used by kotpass 0.10.0 trims every text node and its compact
 * writer drops whitespace-only values. Neither is safe for password fields.
 * Keep kotpass's pinned model mapping and encryption, replacing only XML text
 * handling. The model readers are isolated in [KeePassKotpassXmlReader] and
 * covered by KDBX 3/4 round-trip tests when updating the dependency.
 */
internal object KeePassXmlContentParser : XmlContentParser {
    override fun unmarshalContent(
        xmlData: ByteArray,
        contextBlock: (Meta) -> XmlContext.Decode,
    ): DatabaseContent = xmlData.inputStream().use { unmarshalContent(it, contextBlock) }

    override fun unmarshalContent(
        source: InputStream,
        contextBlock: (Meta) -> XmlContext.Decode,
    ): DatabaseContent {
        val handler = ContentHandler()
        val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        // Both Android Expat and the JVM SAX reader support this property.
        // Reject DTDs before any entity expansion, including internal entities.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(source))
        val document = handler.root ?: throw FormatError.InvalidXml("No document found.")
        require(document.nodeName == "KeePassFile") { "Invalid KeePass XML document" }
        val meta = document.firstOrNull("Meta")?.let { node -> KeePassKotpassXmlReader.readMeta(node) }
            ?: throw FormatError.InvalidXml("No metadata found.")
        val root = document.firstOrNull("Root") ?: throw FormatError.InvalidXml("No root found.")
        val group = root.firstOrNull("Group")?.let { node ->
            restoreGroupTags(KeePassKotpassXmlReader.readGroup(contextBlock(meta), node), node)
        }
            ?: throw FormatError.InvalidXml("No root group found.")
        val deletedNodes = root.firstOrNull("DeletedObjects")?.children?.filterIsInstance<Node>().orEmpty()
        val deleted: List<DeletedObject> = deletedNodes.filter { it.nodeName == "DeletedObject" }
            .mapNotNull { node -> KeePassKotpassXmlReader.readDeletedObject(node) }
        return DatabaseContent(meta, group, deleted)
    }

    // KeePass and KeePassDX separate tags with commas and semicolons. Kotpass
    // additionally splits colons, which changes tags such as "project:internal".
    private fun Node.readTags(): List<String> = firstOrNull("Tags")?.children
        ?.filterIsInstance<TextElement>()?.joinToString("") { it.text }
        ?.split(',', ';')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct().orEmpty()

    private fun restoreGroupTags(group: Group, node: Node): Group {
        val children = node.children.filterIsInstance<Node>()
        val groupNodes = children.filter { it.nodeName == "Group" }
        val entryNodes = children.filter { it.nodeName == "Entry" }
        check(group.groups.size == groupNodes.size && group.entries.size == entryNodes.size) {
            "KeePass XML group mapping lost a native node"
        }
        return group.copy(
            tags = node.readTags(),
            groups = group.groups.zip(groupNodes) { child, xml -> restoreGroupTags(child, xml) },
            entries = group.entries.zip(entryNodes) { entry, xml -> restoreEntryTags(entry, xml) },
        )
    }

    private fun restoreEntryTags(entry: Entry, node: Node): Entry {
        val historyNodes = node.firstOrNull("History")?.children?.filterIsInstance<Node>()
            ?.filter { it.nodeName == "Entry" }.orEmpty()
        check(entry.history.size == historyNodes.size) { "KeePass XML history mapping lost a native node" }
        return entry.copy(tags = node.readTags(),
            history = entry.history.zip(historyNodes) { historic, xml -> restoreEntryTags(historic, xml) })
    }

    override fun marshalContent(context: XmlContext.Encode, content: DatabaseContent, pretty: Boolean): String {
        // Single-line text elements in pretty mode retain whitespace-only text.
        // XML parsers normalize literal CR, so encode it as a character reference.
        val xml = DefaultXmlContentParser.marshalContent(context, content, pretty = true)
        val rootStart = xml.indexOf("<KeePassFile>")
        check(rootStart >= 0) { "KeePass XML writer did not produce its root element" }
        // Character references are illegal in the whitespace before the root.
        return xml.substring(0, rootStart) + xml.substring(rootStart).replace("\r", "&#13;")
    }

    private class ContentHandler : DefaultHandler2() {
        private data class Frame(val node: Node, val text: StringBuilder = StringBuilder())
        private val stack = ArrayDeque<Frame>()
        var root: Node? = null
            private set

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val node = Node(qName)
            for (index in 0 until attributes.length) {
                node.attribute(attributes.getQName(index), attributes.getValue(index))
            }
            if (stack.isEmpty()) root = node else stack.last().node.addElement(node)
            stack.addLast(Frame(node))
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.text?.append(chars, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val frame = stack.removeLast()
            if (frame.node.children.none { it is Node }) {
                // Coalesce SAX chunks, CDATA and character references; kotpass
                // reads one text element per leaf. Keep empty custom-data values.
                if (frame.text.isNotEmpty() || frame.node.nodeName in setOf("Key", "Value")) {
                    frame.node.text(frame.text.toString())
                }
            } else if (frame.text.isNotBlank()) {
                throw SAXException("Mixed XML content is not supported in KeePass elements")
            }
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("DTD is not permitted in KeePass XML")
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
            throw SAXException("External entities are not permitted in KeePass XML")
        }

        override fun error(error: SAXParseException) { throw error }
        override fun fatalError(error: SAXParseException) { throw error }
    }
}
