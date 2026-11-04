package takagi.ru.monica.bitwarden.service

/**
 * Decrypted Bitwarden field used by the password custom-field adapter.
 * The production sync layer keeps the encrypted API object and uses this value only for decisions.
 */
internal data class BitwardenPlainCustomField(
    val name: String?,
    val value: String?,
    val type: Int,
    val linkedId: Int? = null
)

/**
 * Provider-neutral representation matching Monica's existing password custom-field model.
 */
internal data class MonicaPlainCustomField(
    val name: String,
    val value: String,
    val isProtected: Boolean
)

internal data class IncomingPasswordCustomFieldMerge(
    val fields: List<MonicaPlainCustomField>,
    val needsUpload: Boolean
)

/**
 * Keeps Monica's local model unchanged and limits Bitwarden-specific handling to sync boundaries.
 */
internal object BitwardenPasswordCustomFieldAdapter {
    const val TYPE_TEXT = 0
    const val TYPE_HIDDEN = 1

    private val reservedPasswordFieldNames = setOf(
        "monica_app_package",
        "appPackageName",
        "monica_app_name",
        "appName",
        "monica_email",
        "email",
        "monica_phone",
        "phone",
        "monica_address_line",
        "addressLine",
        "address",
        "monica_city",
        "city",
        "monica_state",
        "state",
        "monica_zip_code",
        "zipCode",
        "monica_country",
        "country",
        "monica_passkey_bindings",
        "monica_login_type",
        "monica_ssh_algorithm",
        "monica_ssh_key_size",
        "monica_ssh_public_key",
        "monica_ssh_private_key",
        "monica_ssh_fingerprint",
        "monica_ssh_comment",
        "monica_ssh_format",
        "monica_ssh_metadata"
    )

    fun extractUserFields(fields: List<BitwardenPlainCustomField>): List<MonicaPlainCustomField> {
        return fields.mapNotNull { field ->
            if (!isEditableUserField(field)) return@mapNotNull null
            MonicaPlainCustomField(
                name = field.name.orEmpty(),
                value = field.value.orEmpty(),
                isProtected = field.type == TYPE_HIDDEN
            )
        }
    }

    /**
     * A record whose server revision has changed accepts the server list.
     * Equal revisions can represent data created before this adapter existed, so both lists are
     * combined by exact occurrence and only local-only values are scheduled for upload.
     */
    fun mergeIncoming(
        local: List<MonicaPlainCustomField>,
        remote: List<MonicaPlainCustomField>,
        sameRevision: Boolean
    ): IncomingPasswordCustomFieldMerge {
        if (!sameRevision) {
            return IncomingPasswordCustomFieldMerge(
                fields = remote,
                needsUpload = false
            )
        }

        val merged = appendMissingOccurrences(primary = remote, secondary = local)
        return IncomingPasswordCustomFieldMerge(
            fields = merged,
            needsUpload = merged != remote
        )
    }

    /**
     * Returns encrypted remote-field indexes that must survive an update request.
     * Before a cipher has completed one adapter-aware sync, unmatched remote user fields are kept.
     * Afterwards Monica's local text/hidden list is authoritative, which makes deletions possible.
     */
    fun remoteIndexesToPreserveForUpload(
        remote: List<BitwardenPlainCustomField>,
        local: List<MonicaPlainCustomField>,
        localSystemFieldNames: Set<String>,
        initialized: Boolean
    ): Set<Int> {
        val remainingLocalOccurrences = local.groupingBy { it }.eachCount().toMutableMap()
        return buildSet {
            remote.forEachIndexed { index, field ->
                val name = field.name
                if (!name.isNullOrBlank() && name in localSystemFieldNames) {
                    return@forEachIndexed
                }
                if (!isEditableUserField(field)) {
                    add(index)
                    return@forEachIndexed
                }
                if (initialized) {
                    return@forEachIndexed
                }

                val localField = MonicaPlainCustomField(
                    name = name.orEmpty(),
                    value = field.value.orEmpty(),
                    isProtected = field.type == TYPE_HIDDEN
                )
                val remaining = remainingLocalOccurrences[localField] ?: 0
                if (remaining > 0) {
                    if (remaining == 1) {
                        remainingLocalOccurrences.remove(localField)
                    } else {
                        remainingLocalOccurrences[localField] = remaining - 1
                    }
                } else {
                    add(index)
                }
            }
        }
    }

    private fun isEditableUserField(field: BitwardenPlainCustomField): Boolean {
        val name = field.name
        return !name.isNullOrBlank() &&
            field.linkedId == null &&
            field.type in TYPE_TEXT..TYPE_HIDDEN &&
            name !in reservedPasswordFieldNames
    }

    private fun appendMissingOccurrences(
        primary: List<MonicaPlainCustomField>,
        secondary: List<MonicaPlainCustomField>
    ): List<MonicaPlainCustomField> {
        val remainingPrimaryOccurrences = primary.groupingBy { it }.eachCount().toMutableMap()
        val additions = buildList {
            secondary.forEach { field ->
                val remaining = remainingPrimaryOccurrences[field] ?: 0
                if (remaining > 0) {
                    if (remaining == 1) {
                        remainingPrimaryOccurrences.remove(field)
                    } else {
                        remainingPrimaryOccurrences[field] = remaining - 1
                    }
                } else {
                    add(field)
                }
            }
        }
        return primary + additions
    }
}
