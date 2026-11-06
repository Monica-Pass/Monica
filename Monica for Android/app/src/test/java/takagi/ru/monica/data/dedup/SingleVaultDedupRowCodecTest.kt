package takagi.ru.monica.data.dedup

import java.lang.reflect.Modifier
import java.util.Date
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.attachments.model.Attachment

class SingleVaultDedupRowCodecTest {
    private val codec = SingleVaultDedupRowCodec
    private data class Case(val row: Any, val keys: Set<String>, val encode: (Any) -> JsonObject, val decode: (JsonObject) -> Any)
    private fun cases() = listOf(
        Case(PasswordEntry(title = "x", website = "", username = "", password = ""), codec.passwordEntryKeys, { codec.encode(it as PasswordEntry) }, codec::decodePasswordEntry),
        Case(SecureItem(itemType = ItemType.NOTE, title = "x", itemData = "{}"), codec.secureItemKeys, { codec.encode(it as SecureItem) }, codec::decodeSecureItem),
        Case(CustomField(entryId = 1, title = "x", value = "y"), codec.customFieldKeys, { codec.encode(it as CustomField) }, codec::decodeCustomField),
        Case(PasswordHistoryEntry(entryId = 1, password = "x"), codec.passwordHistoryEntryKeys, { codec.encode(it as PasswordHistoryEntry) }, codec::decodePasswordHistoryEntry),
        Case(PasswordArchiveSyncMeta(entryId = 1), codec.passwordArchiveSyncMetaKeys, { codec.encode(it as PasswordArchiveSyncMeta) }, codec::decodePasswordArchiveSyncMeta),
        Case(TimelineVersionSnapshot(operationLogId = 1, itemType = "PASSWORD", itemId = 1, operationType = "UPDATE", encryptedChangesJson = "x"), codec.timelineVersionSnapshotKeys, { codec.encode(it as TimelineVersionSnapshot) }, codec::decodeTimelineVersionSnapshot),
        Case(OperationLog(itemType = "PASSWORD", itemId = 1, itemTitle = "x", operationType = "UPDATE"), codec.operationLogKeys, { codec.encode(it as OperationLog) }, codec::decodeOperationLog),
        Case(Attachment(parentPasswordId = 1, source = "LOCAL", fileName = "x", mimeType = "text/plain", sizeBytes = 1,
            downloadState = "DOWNLOADED", createdAt = 1_793_878_896_981L, updatedAt = 1_793_878_896_982L),
            codec.attachmentKeys, { codec.encode(it as Attachment) }, codec::decodeAttachment)
    )

    @Test fun everyPersistedPropertyIsClassifiedAndRoundTripsIncludingMillisecondsAndNulls() {
        for (case in cases()) {
            val fields = case.row.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            assertEquals(case.row.javaClass.simpleName, fields.map { it.name }.toSet(), case.keys)
            fields.forEachIndexed { index, field ->
                field.isAccessible = true
                val value: Any = when (field.type) {
                    String::class.java -> "value-$index\u0000\n 空白 🔐 "
                    java.lang.Long.TYPE, java.lang.Long::class.java -> 1_793_878_896_982L + index
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> 101 + index
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> true
                    Date::class.java -> Date(1_793_878_896_982L + index)
                    ItemType::class.java -> ItemType.NOTE
                    else -> error("Unclassified field ${field.name}: ${field.type}")
                }
                field.set(case.row, value)
            }
            val encoded = case.encode(case.row)
            assertEquals(case.keys, encoded.keys)
            assertEquals(case.row, case.decode(encoded))
            assertEquals(encoded, case.encode(case.decode(encoded)))
            fields.filter { !it.type.isPrimitive }.forEach { field ->
                val candidate = JsonObject(encoded + (field.name to JsonNull))
                val nullable = runCatching { case.decode(candidate) }.getOrNull()
                if (nullable != null) assertEquals(JsonNull, case.encode(nullable)[field.name])
            }
        }
    }

    @Test fun missingUnknownAndCoercedRecoveryPropertiesAreRejected() {
        for (case in cases()) {
            val encoded = case.encode(case.row)
            assertTrue(runCatching { case.decode(JsonObject(encoded + ("future" to JsonPrimitive("must not drop")))) }.isFailure)
            assertTrue(runCatching { case.decode(JsonObject(encoded - "id")) }.isFailure || "id" !in encoded)
            if ("id" in encoded) assertTrue(runCatching { case.decode(JsonObject(encoded + ("id" to JsonPrimitive("1")))) }.isFailure)
        }
    }
}
