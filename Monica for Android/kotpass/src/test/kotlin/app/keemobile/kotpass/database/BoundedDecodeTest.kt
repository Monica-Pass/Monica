package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BoundedDecodeTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("synthetic-bounded-decode"))
    private fun database(v3: Boolean): KeePassDatabase {
        if (v3) return KeePassDatabase.Ver3x.create("Root", Meta(), credentials)
        val db = KeePassDatabase.Ver4x.create("Root", Meta(), credentials)
        return db.copy(header = db.header.copy(kdfParameters = KdfParameters.Aes(10u, db.header.masterSeed)))
    }
    private fun encoded(db: KeePassDatabase): ByteArray = ByteArrayOutputStream().also { db.encode(it) }.toByteArray()

    @Test fun optInLimitRejectsExpandedXmlButDefaultStillOpensBothVersions() {
        for (v3 in listOf(true, false)) {
            val payload = "a".repeat(64 * 1024)
            val bytes = encoded(database(v3).modifyContent { copy(group = group.copy(notes = payload)) })
            assertTrue(bytes.size < 4096)
            val error = runCatching { KeePassDatabase.decode(bytes.inputStream(), credentials, maxDecompressedBytes = 4096) }.exceptionOrNull()
            // SAX wraps the stream failure while parsing XML; the bounded decoder must
            // still be the cause, rather than a generic parse or authentication failure.
            val limitFailure = generateSequence(error) { it.cause }
                .filterIsInstance<FormatError.InvalidContent>().firstOrNull()
            assertNotNull(error.toString(), limitFailure)
            assertEquals("Decompressed database content exceeds the configured limit.", limitFailure!!.message)
            assertEquals(payload, KeePassDatabase.decode(bytes.inputStream(), credentials).content.group.notes)
        }
    }

    @Test fun limitAppliesToV4InnerBinaryBeforeXmlParserRuns() {
        val binary = BinaryData.Uncompressed(false, ByteArray(64 * 1024) { 5 })
        val bytes = encoded(database(false).modifyBinaries { mapOf(binary.hash to binary) })
        var parserCalled = false
        val parser = object : XmlContentParser by DefaultXmlContentParser {
            override fun unmarshalContent(source: InputStream, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
                parserCalled = true
                return DefaultXmlContentParser.unmarshalContent(source, contextBlock)
            }
        }
        val error = runCatching { KeePassDatabase.decode(bytes.inputStream(), credentials, contentParser = parser, maxDecompressedBytes = 4096) }.exceptionOrNull()
        assertTrue(error.toString(), error is FormatError.InvalidContent)
        assertFalse(parserCalled)
        val decoded = KeePassDatabase.decode(bytes.inputStream(), credentials) as KeePassDatabase.Ver4x
        assertArrayEquals(binary.rawContent, decoded.innerHeader.binaries.getValue(binary.hash).rawContent)
    }

    @Test fun exactExpandedLimitAllowsEofAndOneByteLessRejects() {
        val bytes = encoded(database(true))
        var expandedSize = 0L
        val parser = object : XmlContentParser by DefaultXmlContentParser {
            override fun unmarshalContent(source: InputStream, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
                val xml = source.readBytes()
                expandedSize = xml.size.toLong()
                return DefaultXmlContentParser.unmarshalContent(xml, contextBlock)
            }
        }
        KeePassDatabase.decode(bytes.inputStream(), credentials, contentParser = parser)
        assertEquals("Root", KeePassDatabase.decode(bytes.inputStream(), credentials, contentParser = parser, maxDecompressedBytes = expandedSize).content.group.name)
        val error = runCatching { KeePassDatabase.decode(bytes.inputStream(), credentials, contentParser = parser, maxDecompressedBytes = expandedSize - 1) }.exceptionOrNull()
        assertTrue(error.toString(), error is FormatError.InvalidContent)
    }
}
