package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue

class KeePassEntryFieldPatch private constructor(
    private val replacementFields: Map<String, EntryValue>,
    private val removeManagedField: (String) -> Boolean,
    private val removeFieldNames: Set<String>
) {
    fun applyTo(entry: Entry): Entry {
        val updatedFields = linkedMapOf<String, EntryValue>()

        entry.fields.forEach { (name, value) ->
            val shouldRemove =
                name in removeFieldNames ||
                    shouldRemoveManagedField(name)
            if (!shouldRemove) {
                updatedFields[name] = value
            }
        }
        updatedFields.putAll(replacementFields)

        return entry.copy(
            fields = EntryFields.of(*updatedFields.toList().toTypedArray())
        )
    }

    private fun shouldRemoveManagedField(name: String): Boolean {
        if (!removeManagedField(name)) return false
        return when (KeePassFieldRegistry.roleOf(name)) {
            KeePassFieldRole.MONICA_PASSWORD,
            KeePassFieldRole.MONICA_SECURE_ITEM,
            KeePassFieldRole.MONICA_PASSKEY -> true
            else -> false
        }
    }

    fun toChangePatch(
        managedScope: KeePassManagedFieldScope,
        baseEntry: Entry? = null
    ): KeePassFieldChangePatch {
        return KeePassFieldChangePatch(
            managedScope = managedScope,
            replacementFields = replacementFields.map { (name, value) ->
                KeePassFieldChange(
                    name = name,
                    value = runCatching { value.content }.getOrDefault(""),
                    protected = value is EntryValue.Encrypted
                )
            },
            removeFieldNames = removeFieldNames.toList(),
            baseFields = baseEntry?.let { buildBaseFields(it) }.orEmpty()
        )
    }

    private fun buildBaseFields(entry: Entry): List<KeePassFieldBaseValue> {
        val touchedNames = linkedSetOf<String>()
        replacementFields.keys.forEach { touchedNames += it }
        removeFieldNames.forEach { touchedNames += it }
        entry.fields.forEach { (name, _) ->
            if (shouldRemoveManagedField(name) || name in removeFieldNames) {
                touchedNames += name
            }
        }

        return touchedNames
            .map { name ->
                val existing = entry.fields[name]
                if (existing == null) {
                    KeePassFieldBaseValue(
                        name = name,
                        present = false
                    )
                } else {
                    KeePassFieldBaseValue(
                        name = name,
                        value = existing.content,
                        protected = existing is EntryValue.Encrypted,
                        present = true
                    )
                }
            }
    }

    companion object {
        fun fromEntryFields(
            replacementFields: EntryFields,
            removeManagedField: (String) -> Boolean,
            removeFieldNames: Iterable<String> = emptyList()
        ): KeePassEntryFieldPatch {
            return KeePassEntryFieldPatch(
                replacementFields = replacementFields.toMap(),
                removeManagedField = removeManagedField,
                removeFieldNames = removeFieldNames
                    .filter(String::isNotBlank)
                    .toSet()
            )
        }
    }
}
