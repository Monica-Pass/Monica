package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.decodeFromXml
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class KeePassXmlContentParserTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("synthetic"))
    private val source = Entry(UUID.randomUUID(), fields = EntryFields.of(
        "Title" to EntryValue.Plain(" Padded title "),
        "Code" to EntryValue.Plain(" upper "),
        " Code " to EntryValue.Plain(" different "),
        "code" to EntryValue.Plain("lower"),
        "Empty" to EntryValue.Plain(""),
        "Whitespace" to EntryValue.Plain(" \t\r\n "),
        "Protected" to EntryValue.Encrypted(EncryptedValue.fromString(" secret \r\n ")),
        "Notes" to EntryValue.Plain("\nfirst\r\nsecond\n"),
    ), tags = listOf("project:internal", "work"))

    @Test fun encryptedKdbx3And4RetainExactTextProtectionTagsAndHistory() {
        for (version3 in listOf(true, false)) {
            val database = database(version3).modifyParentGroup {
                copy(name = " Root ", notes = "\n Notes \n", tags = listOf("group:tag"),
                    entries = listOf(source.copy(history = listOf(source))))
            }
            val encoded = ByteArrayOutputStream().use {
                database.encode(it, contentParser = KeePassXmlContentParser)
                it.toByteArray()
            }
            val decoded = KeePassDatabase.decode(encoded.inputStream(), credentials, contentParser = KeePassXmlContentParser)
            val group = decoded.content.group
            val actual = group.entries.single()
            assertEquals(version3, decoded is KeePassDatabase.Ver3x)
            assertEquals(" Root ", group.name)
            assertEquals("\n Notes \n", group.notes)
            assertEquals(if (version3) emptyList<String>() else listOf("group:tag"), group.tags)
            assertEquals(source.tags, actual.tags)
            assertEquals(source.fields.keys, actual.fields.keys)
            source.fields.forEach { (name, value) ->
                assertEquals("$version3:$name", value.content, actual.fields.getValue(name).content)
                assertEquals(value is EntryValue.Encrypted, actual.fields.getValue(name) is EntryValue.Encrypted)
                assertEquals(value.content, actual.history.single().fields.getValue(name).content)
            }
            assertEquals(source.tags, actual.history.single().tags)
        }
    }

    @Test fun literalXmlKeepsCdataChunksEntitiesAndEmptyPluginValues() {
        val xml = """<KeePassFile><Meta><DatabaseName> Demo </DatabaseName>
            <CustomData><Item><Key> plugin </Key><Value/></Item></CustomData></Meta>
            <Root><Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name> Root </Name>
            <Entry><UUID>AAAAAAAAAAAAAAAAAAAAAQ==</UUID><Tags>project:internal,one;two</Tags>
            <String><Key> Code </Key><Value> first<![CDATA[<&middle>]]><!-- split -->last&#13;&#10; </Value></String>
            <String><Key>Code</Key><Value>different</Value></String>
            </Entry></Group><DeletedObjects/></Root></KeePassFile>""".trimIndent()
        val decoded = KeePassDatabase.decodeFromXml(xml.byteInputStream(), credentials, contentParser = KeePassXmlContentParser)
        assertEquals(" Demo ", decoded.content.meta.name)
        assertEquals("", decoded.content.meta.customData.getValue(" plugin ").value)
        val entry = decoded.content.group.entries.single()
        assertEquals(" first<&middle>last\r\n ", entry.fields.getValue(" Code ").content)
        assertEquals("different", entry.fields.getValue("Code").content)
        assertEquals(listOf("project:internal", "one", "two"), entry.tags)
    }

    @Test fun dtdAndExternalEntitiesAreRejectedBeforeReadingContent() {
        for (declaration in listOf("<!ENTITY test 'expanded'>", "<!ENTITY test SYSTEM 'file:///not-read'>")) {
            val xml = "<!DOCTYPE KeePassFile [$declaration]><KeePassFile><Meta><DatabaseName>&test;</DatabaseName></Meta></KeePassFile>"
            val result = runCatching {
                KeePassDatabase.decodeFromXml(xml.byteInputStream(), credentials, contentParser = KeePassXmlContentParser)
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DTD"))
        }
    }

    private fun database(version3: Boolean): KeePassDatabase {
        if (version3) return KeePassDatabase.Ver3x.create("Root", Meta(), credentials)
            .let { it.copy(header = it.header.copy(transformRounds = 1U)) }
        val database = KeePassDatabase.Ver4x.create("Root", Meta(), credentials)
        val seed = when (val kdf = database.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        return database.copy(header = database.header.copy(kdfParameters = KdfParameters.Aes(rounds = 1U, seed = seed)))
    }
}
