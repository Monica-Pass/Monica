package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.PredefinedIcon
import java.util.UUID

data class Entry(
    override val uuid: UUID,
    override val icon: PredefinedIcon = PredefinedIcon.Key,
    override val customIconUuid: UUID? = null,
    val foregroundColor: String? = null,
    val backgroundColor: String? = null,
    val overrideUrl: String = "",
    override val times: TimeData? = TimeData.create(),
    val autoType: AutoTypeData? = null,
    val fields: EntryFields = EntryFields.createDefault(),
    override val tags: List<String> = listOf(),
    val binaries: List<BinaryReference> = listOf(),
    val history: List<Entry> = listOf(),
    val customData: Map<String, CustomDataValue> = mapOf(),
    val previousParentGroup: UUID? = null,
    val qualityCheck: Boolean = true
) : DatabaseElement {
    operator fun get(field: BasicField): EntryValue? = fields[field()]
}
