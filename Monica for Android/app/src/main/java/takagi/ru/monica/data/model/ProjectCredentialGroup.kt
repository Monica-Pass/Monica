package takagi.ru.monica.data.model

import java.util.UUID
import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry

/** Metadata only. Passwords and OTP use PasswordEntry's existing encrypted storage. */
object ProjectCredentialGroup {
    const val FIELD = "monica.content.credential"
    enum class RestoreFailure { UNSUPPORTED_METADATA, DUPLICATE_PASSWORD_ID, USERNAME_CONFLICT, OTP_CONFLICT, NOTE_CONFLICT }
    class RestoreException(val reason: RestoreFailure) : IllegalArgumentException(reason.name)
    fun token(id: String) = "CREDENTIAL:$id"
    data class Metadata(val raw: JsonObject) {
        val projectId get() = raw["projectId"]?.jsonPrimitive?.contentOrNull
        fun forProject(id: String): Metadata = copy(raw = JsonObject(raw + ("projectId" to JsonPrimitive(id))))
        val groupId get() = raw.getValue("groupId").jsonPrimitive.content
        val passwordId get() = raw.getValue("passwordId").jsonPrimitive.content
        val label get() = raw.getValue("label").jsonPrimitive.content
        val primary get() = raw.getValue("primary").jsonPrimitive.boolean
        val groupOrder get() = raw.getValue("groupOrder").jsonPrimitive.int
        val passwordOrder get() = raw.getValue("passwordOrder").jsonPrimitive.int
        val note get() = raw["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
    data class Password(val id: String = UUID.randomUUID().toString(), val value: String = "",
        val originalEntryId: Long? = null, val metadata: Metadata? = null)
    data class Group(val id: String = UUID.randomUUID().toString(), val label: String = "",
        val username: String = "", val passwords: List<Password> = listOf(Password()), val otp: String = "",
        val note: String = "",
        // Editor-only baseline; never written into credential metadata.
        internal val loadedOtp: String? = null,
        internal val originalOtpByPasswordId: Map<String, String> = emptyMap())
    data class Row(val username: String, val password: Password, val otp: String, val metadata: Metadata)

    fun read(fields: List<CustomFieldDraft>): Metadata? {
        val copies = fields.filter { it.title == FIELD }
        val first = copies.firstOrNull()?.value?.let(::parse) ?: return null
        // Older rows can contain repeated system fields. Accept only complete agreement,
        // including unknown properties; never pick a winner from conflicting copies.
        return first.takeIf { copies.drop(1).all { parse(it.value)?.raw == first.raw } }
    }
    fun parse(value: String): Metadata? = runCatching {
        val raw = Json.parseToJsonElement(value).jsonObject
        require(raw["version"]?.jsonPrimitive?.int == 1)
        val metadata = Metadata(raw)
        metadata.projectId?.let { require(UUID.fromString(it).toString() == it) }
        require(UUID.fromString(metadata.groupId).toString() == metadata.groupId)
        require(UUID.fromString(metadata.passwordId).toString() == metadata.passwordId)
        require(raw["label"]?.jsonPrimitive?.isString == true)
        require(raw["note"] == null || raw["note"]?.jsonPrimitive?.isString == true)
        require(metadata.groupOrder >= 0 && metadata.passwordOrder >= 0)
        metadata.primary
        metadata
    }.getOrNull()

    fun rows(groups: List<Group>): List<Row> {
        require(groups.isNotEmpty() && groups.map { it.id }.distinct().size == groups.size)
        val passwordIds = groups.flatMap { it.passwords.map { password -> password.id } }
        require(passwordIds.distinct().size == passwordIds.size)
        return groups.flatMapIndexed { groupIndex, group ->
            require(group.passwords.isNotEmpty() && group.passwords.map { it.id }.distinct().size == group.passwords.size)
            group.passwords.mapIndexed { passwordIndex, password ->
                val raw = JsonObject((password.metadata?.raw.orEmpty() - "note") + buildJsonObject {
                    put("version", 1); put("groupId", group.id); put("passwordId", password.id)
                    put("label", group.label); put("primary", groupIndex == 0)
                    if (group.note.isNotEmpty()) put("note", group.note)
                    put("groupOrder", groupIndex); put("passwordOrder", passwordIndex)
                })
                val otp = if (group.loadedOtp != null && group.otp == group.loadedOtp) {
                    group.originalOtpByPasswordId[password.id] ?: group.otp
                } else group.otp
                Row(group.username, password, otp, requireNotNull(parse(raw.toString())))
            }
        }
    }

    fun put(fields: List<CustomFieldDraft>, metadata: Metadata): List<CustomFieldDraft> {
        val existing = fields.filter { it.title == FIELD }
        require(existing.isEmpty() || read(fields) != null) { "Unreadable credential metadata must be preserved" }
        return EntryContentFields.collapseIdenticalOrderCopies(fields.filterNot { it.title == FIELD }) + (existing.firstOrNull()?.copy(
            value = metadata.raw.toString(), isProtected = existing.any { it.isProtected })
            ?: CustomFieldDraft(title = FIELD, value = metadata.raw.toString()))
    }

    /** Entries already belong to the same explicit project. No username/title inference. */
    fun restoreForEditing(entries: List<PasswordEntry>, fields: Map<Long, List<CustomFieldDraft>>): List<Group> =
        restore(entries, fields, preservePartialLocalOtp = true)

    fun restore(entries: List<PasswordEntry>, fields: Map<Long, List<CustomFieldDraft>>): List<Group> =
        restore(entries, fields, preservePartialLocalOtp = false)

    private fun restore(entries: List<PasswordEntry>, fields: Map<Long, List<CustomFieldDraft>>,
        preservePartialLocalOtp: Boolean): List<Group> {
        if (entries.isEmpty()) return emptyList()
        val metadata = entries.associate { entry ->
            val entryFields = fields[entry.id].orEmpty()
            if (entryFields.any { it.title == FIELD } && read(entryFields) == null)
                throw RestoreException(RestoreFailure.UNSUPPORTED_METADATA)
            entry.id to read(entryFields)
        }
        val identities = metadata.values.filterNotNull().map { it.passwordId }
        if (identities.distinct().size != identities.size) throw RestoreException(RestoreFailure.DUPLICATE_PASSWORD_ID)
        val legacyGroup = metadata.values.filterNotNull().firstOrNull { it.primary }?.groupId ?: UUID.randomUUID().toString()
        return entries.groupBy { metadata[it.id]?.groupId ?: legacyGroup }.values
            .sortedWith(compareBy<List<PasswordEntry>> { group -> metadata[group.first().id]?.groupOrder ?: 0 }.thenBy { it.minOf { row -> row.id } })
            .map { group ->
                val ordered = group.sortedWith(compareBy<PasswordEntry> { metadata[it.id]?.passwordOrder ?: 0 }.thenBy { it.id })
                val first = ordered.first(); val meta = metadata[first.id]
                if (ordered.any { it.username != first.username }) throw RestoreException(RestoreFailure.USERNAME_CONFLICT)
                val otpValues = ordered.map { it.authenticatorKey }.distinct()
                val partialOtp = otpValues.size == 2 && "" in otpValues
                val retainPartialOtp = preservePartialLocalOtp && partialOtp &&
                    ordered.all { it.isLocalOnlyEntry() && metadata[it.id] != null }
                if (otpValues.size > 1 && !retainPartialOtp) throw RestoreException(RestoreFailure.OTP_CONFLICT)
                if (ordered.any { metadata[it.id]?.note.orEmpty() != meta?.note.orEmpty() })
                    throw RestoreException(RestoreFailure.NOTE_CONFLICT)
                val otp = if (retainPartialOtp) otpValues.single { it.isNotEmpty() } else first.authenticatorKey
                Group(meta?.groupId ?: legacyGroup, meta?.label.orEmpty(), first.username,
                    ordered.map { row -> Password(metadata[row.id]?.passwordId ?: UUID.randomUUID().toString(), row.password, row.id, metadata[row.id]) },
                    otp, meta?.note.orEmpty(),
                    loadedOtp = otp.takeIf { retainPartialOtp },
                    originalOtpByPasswordId = if (retainPartialOtp) ordered.associate {
                        requireNotNull(metadata[it.id]).passwordId to it.authenticatorKey
                    } else emptyMap())
            }
    }
}
