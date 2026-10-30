package app.keemobile.kotpass.models

import java.time.Instant
import java.util.UUID

data class DeletedObject(
    val id: UUID,
    val deletionTime: Instant
)
