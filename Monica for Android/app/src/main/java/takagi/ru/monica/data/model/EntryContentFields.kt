package takagi.ru.monica.data.model

import takagi.ru.monica.data.CustomFieldDraft

/** Portable additions use the existing encrypted custom-field pipeline on every backend. */
object EntryContentFields {
    const val ORDER = "monica.content.order"
    fun key(section: String, field: String) = "monica.content.${section.lowercase()}.$field"
    fun value(fields: List<CustomFieldDraft>, section: String, field: String): String =
        fields.firstOrNull { it.title == key(section, field) }?.value.orEmpty()
    fun update(fields: List<CustomFieldDraft>, section: String, field: String, value: String,
        protected: Boolean): List<CustomFieldDraft> {
        val title = key(section, field)
        val index = fields.indexOfFirst { it.title == title }
        return fields.toMutableList().apply {
            if (index >= 0) set(index, get(index).copy(value = value))
            else if (value.isNotEmpty()) add(CustomFieldDraft(id = CustomFieldDraft.nextTempId(map { it.id }),
                title = title, value = value, isProtected = protected))
        }
    }
    fun order(fields: List<CustomFieldDraft>): List<String> = fields.firstOrNull { it.title == ORDER }
        ?.value?.split(',')?.filter { it.isNotBlank() }?.distinct().orEmpty()
    fun collapseIdenticalOrderCopies(fields: List<CustomFieldDraft>): List<CustomFieldDraft> {
        val copies = fields.filter { it.title == ORDER }
        if (copies.size < 2 || copies.any { it.value != copies.first().value }) return fields
        return fields.filterNot { it.title == ORDER } + copies.first().copy(isProtected = copies.any { it.isProtected })
    }
    fun withOrder(fields: List<CustomFieldDraft>, sections: List<String>): List<CustomFieldDraft> {
        val merged = (sections + order(fields)).distinct()
        if (merged.isEmpty()) return fields
        val existing = fields.firstOrNull { it.title == ORDER }
        val value = merged.joinToString(",")
        return fields.filterNot { it.title == ORDER } + (existing?.copy(value = value,
            isProtected = fields.any { it.title == ORDER && it.isProtected })
            ?: CustomFieldDraft(id = CustomFieldDraft.nextTempId(fields.map { it.id }), title = ORDER, value = value))
    }
}
