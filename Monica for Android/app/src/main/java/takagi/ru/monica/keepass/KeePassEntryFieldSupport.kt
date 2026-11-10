package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import java.util.UUID

/** Shared compatibility rules used by the service and its field writers. */
internal object KeePassEntryFieldSupport {
    fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun preserveExistingLoginFields(fields: EntryFields, existing: Entry?): EntryFields {
        if (existing == null) return fields
        return EntryFields.of(*fields.filterNot { (name, value) ->
            name in setOf("UserName", "Password", "URL") && value.content.isEmpty() && existing.fields.containsKey(name)
        }.map { it.key to it.value }.toTypedArray())
    }
}
