package takagi.ru.monica.repository

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*

class MdbxPasswordEditorPayloadTest {
    private fun detail(payload: String, version: UInt = 1u) = MdbxNativeObjectDetail(
        MdbxNativeObjectSummary("native-id", "folder", "login", "Original", version, "revision"), payload, emptyList())
    private val base = PasswordEntry(id = 2, title = "Stale", website = "old", username = "old", password = "old", mdbxDatabaseId = 3)
    private fun decode(d: MdbxNativeObjectDetail) = MdbxPasswordEditorPayload.decode(d, base, "root", { it }, { it })

    @Test fun nativeValuesOverrideStaleRoomAndPreserveEmptyPassword() {
        val d = detail("""{"password_plain":"","password":"old","username":"native","website":"","notes":""}""")
        val read = decode(d)
        assertEquals("", read.password); assertEquals("native", read.username); assertEquals("", read.website)
        assertEquals("native-id", read.replicaGroupId); assertEquals("folder", read.mdbxFolderId)
    }

    @Test fun editPreservesFutureNestedFieldsNumbersNullAndFieldMetadata() {
        val d = detail("""{"password_plain":"secret","username":"native","future":{"n":900719925474099312345,"f":1.234567890123456789,"a":[null,false,""]},"custom_fields":[{"title":" same ","value":"one","is_protected":true,"uuid":"first","future":{"n":900719925474099312345}},{"title":" same ","value":"two","is_protected":false,"uuid":"second"}]}""")
        val read = decode(d)
        val fields = MdbxPasswordEditorPayload.fields(d, read.id).map(CustomFieldDraft::fromCustomField)
        val edited = listOf(fields[1], fields[0].copy(value = "changed"))
        val result = Json.parseToJsonElement(MdbxPasswordEditorPayload.encode(d, read, read.copy(password = "new"), edited, { it })).jsonObject
        val original = Json.parseToJsonElement(d.payload).jsonObject
        assertEquals(original["future"], result["future"])
        val saved = result.getValue("custom_fields").jsonArray
        assertEquals("second", saved[0].jsonObject.getValue("uuid").jsonPrimitive.content)
        assertEquals("first", saved[1].jsonObject.getValue("uuid").jsonPrimitive.content)
        assertEquals(original.getValue("custom_fields").jsonArray[0].jsonObject["future"], saved[1].jsonObject["future"])
        assertEquals(" same ", saved[1].jsonObject.getValue("title").jsonPrimitive.content)
        assertTrue(saved[1].jsonObject.getValue("is_protected").jsonPrimitive.boolean)
    }

    @Test fun noOpDoesNotAddDefaultsOrChangeUnknownFields() {
        val d = detail("""{"username":null,"password_plain":"literal","future":false,"customFields":[{"label":"x","value":"","isProtected":true,"extra":null}]}""")
        val read = decode(d)
        val result = MdbxPasswordEditorPayload.encode(d, read, read, MdbxPasswordEditorPayload.fields(d, read.id).map(CustomFieldDraft::fromCustomField), { it })
        assertEquals(Json.parseToJsonElement(d.payload), Json.parseToJsonElement(result))
    }

    @Test fun futureSchemaAndStructuredKnownFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { decode(detail("{}", 2u)) }
        assertThrows(IllegalStateException::class.java) { decode(detail("""{"username":{"unexpected":true}}""")) }
    }

    @Test fun newFieldsNeverStealExistingFieldMetadata() {
        val d = detail("""{"custom_fields":[{"title":"original","value":"x","uuid":"retained"}]}""")
        val read = decode(d)
        val result = Json.parseToJsonElement(MdbxPasswordEditorPayload.encode(d, read, read,
            listOf(CustomFieldDraft(id = -1, title = "new", value = "y")), { it })).jsonObject
        assertNull(result.getValue("custom_fields").jsonArray.single().jsonObject["uuid"])
    }

    @Test fun nativeUuidIsNeverRehashedAsRoomIdentity() {
        val id = "900da173-9cb6-45db-b077-8ec507b81799"
        assertEquals(id, mdbxPasswordObjectId(base.copy(replicaGroupId = id)))
        assertEquals(id, mdbx2PhysicalEntryId("vault", id))
        assertNotEquals("password:2", mdbx2PhysicalEntryId("vault", "password:2"))
    }

    @Test fun legacyLogicalIdentityIsPreservedForExistingImporters() {
        assertEquals("password:999", decode(detail("""{"monica_entry_id":"password:999"}""")).replicaGroupId)
    }

    @Test fun noteBindingPreservesUnchangedReferenceAndRequiresNativeTargetForChanges() {
        val d = detail("""{"bound_note_entry_id":"note:original","bound_note_room_id":999}""")
        val before = decode(d).copy(boundNoteId = 7)
        val retained = MdbxPasswordEditorPayload.encode(d, before, before, emptyList(), { it })
        assertEquals(Json.parseToJsonElement(d.payload), Json.parseToJsonElement(retained))
        val cleared = Json.parseToJsonElement(MdbxPasswordEditorPayload.encode(d, before,
            before.copy(boundNoteId = null), emptyList(), { it })).jsonObject
        assertEquals(JsonNull, cleared["bound_note_entry_id"])
        assertEquals(JsonNull, cleared["bound_note_room_id"])
        assertThrows(IllegalArgumentException::class.java) {
            MdbxPasswordEditorPayload.encode(d, before, before.copy(boundNoteId = 8), emptyList(), { it })
        }
    }
}
