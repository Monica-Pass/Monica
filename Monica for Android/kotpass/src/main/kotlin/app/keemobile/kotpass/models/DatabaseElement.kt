package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.PredefinedIcon
import java.util.UUID

sealed interface DatabaseElement {
    val uuid: UUID
    val times: TimeData?
    val icon: PredefinedIcon
    val customIconUuid: UUID?
    val tags: List<String>
}
