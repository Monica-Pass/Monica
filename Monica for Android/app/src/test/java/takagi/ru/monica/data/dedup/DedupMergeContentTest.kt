package takagi.ru.monica.data.dedup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*

class DedupMergeContentTest {
    private fun entry(id: Long = 1) = PasswordEntry(id, "Example", "https://example.invalid", "User", "secret")
    private fun fields(vararg pairs: Pair<String, String>) = pairs.mapIndexed { index, pair ->
        CustomField(index.toLong() + 1, 1, pair.first, pair.second, true, index)
    }
    private fun key(row: PasswordEntry = entry(), fields: List<CustomField> = emptyList()) = DedupMergeContent.password(row, fields) { it }

    @Test fun controlCharactersHaveNoAmbiguousFieldEncoding() {
        assertNotEquals(key(fields = fields("a" to "b\u0001c")), key(fields = fields("a\u0001b" to "c")))
        assertNotEquals(key(fields = fields("a" to "b\u0002c")), key(fields = fields("a" to "b", "c" to "")))
    }

    @Test fun duplicateCountBlankNamesWhitespaceCaseAndProtectionRemainMeaningful() {
        val field = fields("Recovery" to " value ").single()
        listOf(listOf(field, field.copy(id = 2, sortOrder = 1)), listOf(field.copy(title = "recovery")),
            listOf(field.copy(value = "value")), listOf(field.copy(isProtected = false)), listOf(field.copy(title = "")))
            .forEach { assertNotEquals(key(fields = listOf(field)), key(fields = it)) }
        assertNotEquals(key(fields = fields("" to "data")), key())
    }

    @Test fun everyCredentialVariantAndNativeLinkRemainsDistinct() {
        val row = entry()
        listOf(row.copy(password = "new"), row.copy(website = "http://example.invalid"), row.copy(username = "user"),
            row.copy(notes = " "), row.copy(email = "x"), row.copy(phone = "x"), row.copy(addressLine = "x"),
            row.copy(city = "x"), row.copy(state = "x"), row.copy(zipCode = "x"), row.copy(country = "x"),
            row.copy(creditCardNumber = "x"), row.copy(creditCardHolder = "x"), row.copy(creditCardExpiry = "x"),
            row.copy(creditCardCVV = "x"), row.copy(authenticatorKey = "x"), row.copy(passkeyBindings = "x"),
            row.copy(sshKeyData = "x"), row.copy(wifiMetadata = "x"), row.copy(ssoProvider = "x"),
            row.copy(boundNoteId = 4), row.copy(ssoRefEntryId = 4), row.copy(customIconType = "SIMPLE_ICON", customIconValue = "github"),
            row.copy(isFavorite = true), row.copy(isArchived = true), row.copy(passwordGroupId = "project"))
            .forEach { assertNotEquals(key(row), key(it)) }
    }

    @Test fun securePayloadPreservesUnknownValuesArraysAndTypes() {
        fun key(value: String) = DedupMergeContent.secure(SecureItem(itemType = ItemType.NOTE, title = "Note", itemData = value), { it }, "")
        assertEquals(key("{\"a\":1,\"future\":false}"), key("{\"future\":false,\"a\":1}"))
        assertNotEquals(key("{\"future\":false}"), key("{\"future\":0}"))
        assertNotEquals(key("{\"future\":[1,2]}"), key("{\"future\":[2,1]}"))
        assertNotEquals(key("{\"future\":\" secret \"}"), key("{\"future\":\"secret\"}"))
    }

    @Test fun twoPasswordsCloneAsOneProjectWithFreshCoherentIdentifiers() {
        val project = uuid(1); val group = uuid(2)
        val entries = listOf(entry(1).copy(passwordGroupId = project), entry(2).copy(password = "second", passwordGroupId = project))
        fun rowFields(id: Long) = fields(ProjectCredentialGroup.FIELD to metadata(project, group, uuid(id.toInt() + 2), id.toInt() - 1),
            EntryContentFields.ORDER to "CREDENTIAL:$group").map { it.copy(entryId = id) }
        val source = entries.associate { it.id to rowFields(it.id) }
        var counter = 20
        val cloned = DedupMergeContent.cloneProject(entries, source, { it }, { it }, { uuid(counter++) })
        assertEquals(1, cloned.values.map { it.first }.distinct().size)
        assertNotEquals(project, cloned.getValue(1).first)
        val parsed = cloned.mapValues { (_, value) -> requireNotNull(ProjectCredentialGroup.read(DedupMergeContent.drafts(value.second) { it })) }
        assertEquals(parsed.getValue(1).groupId, parsed.getValue(2).groupId)
        assertNotEquals(group, parsed.getValue(1).groupId)
        assertNotEquals(parsed.getValue(1).passwordId, parsed.getValue(2).passwordId)
        assertEquals(cloned.getValue(1).first, parsed.getValue(1).projectId)
        assertTrue(cloned.values.all { value -> value.second.single { it.title == EntryContentFields.ORDER }.value == "CREDENTIAL:${parsed.getValue(1).groupId}" })
        val restored = ProjectCredentialGroup.restore(entries.map { it.copy(passwordGroupId = cloned.getValue(it.id).first) },
            cloned.mapValues { DedupMergeContent.drafts(it.value.second) { it } })
        assertEquals(listOf("secret", "second"), restored.single().passwords.map { it.value })
    }

    @Test fun blockIdentifiersFragmentsAndOrderAreRemappedTogether() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY).edited("API", mapOf("key" to "synthetic-key"))
        val drafts = PasswordContentBlocks.put(listOf(CustomFieldDraft(title = EntryContentFields.ORDER, value = PasswordContentBlocks.token(block.id))), block)
        val input = drafts.mapIndexed { index, field -> field.toCustomField(1, index).copy(id = index + 1L) }
        var counter = 20
        val result = DedupMergeContent.cloneProject(listOf(entry()), mapOf(1L to input), { it }, { it }, { uuid(counter++) }).getValue(1).second
        val output = DedupMergeContent.drafts(result) { it }
        val cloned = requireNotNull(PasswordContentBlocks.read(output).single().block)
        assertNotEquals(block.id, cloned.id)
        assertEquals(block.data, cloned.data)
        assertEquals(PasswordContentBlocks.token(cloned.id), output.single { it.title == EntryContentFields.ORDER }.value)
    }

    @Test fun unknownMetadataBrokenBlocksAndConflictingGroupCopiesBlockCloning() {
        val project = uuid(1); val group = uuid(2)
        val metadata = metadata(project, group, uuid(3), 0)
        val invalid = listOf(
            fields(ProjectCredentialGroup.FIELD to metadata, ProjectCredentialGroup.FIELD to metadata.replace("\"label\":\"\"", "\"label\":\"changed\"")),
            fields(ProjectCredentialGroup.FIELD to metadata.dropLast(1) + ",\"futureReference\":\"other\"}"),
            fields(PasswordContentBlocks.PREFIX + uuid(6) to "{}"))
        invalid.forEach { input ->
            assertTrue(runCatching { DedupMergeContent.cloneProject(listOf(entry().copy(passwordGroupId = project)), mapOf(1L to input), { it }, { it }) }.isFailure)
        }
    }

    @Test fun equalDuplicateCredentialCopiesAndOrdinaryDuplicatesArePreserved() {
        val project = uuid(1); val group = uuid(2)
        val value = metadata(project, group, uuid(3), 0)
        val input = fields(ProjectCredentialGroup.FIELD to value, ProjectCredentialGroup.FIELD to value,
            "Recovery" to "same", "Recovery" to "same", "" to "blank-name-data")
        val output = DedupMergeContent.cloneProject(listOf(entry().copy(passwordGroupId = project)), mapOf(1L to input), { it }, { it }).getValue(1).second
        assertNotNull(ProjectCredentialGroup.read(DedupMergeContent.drafts(output) { it }))
        assertEquals(2, output.count { it.title == "Recovery" })
        assertEquals("blank-name-data", output.single { it.title == "" }.value)
    }

    private fun metadata(project: String, group: String, password: String, order: Int) = buildJsonObject {
        put("version", 1); put("projectId", project); put("groupId", group); put("passwordId", password)
        put("label", ""); put("primary", true); put("groupOrder", 0); put("passwordOrder", order)
    }.toString()
    private fun uuid(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
}
