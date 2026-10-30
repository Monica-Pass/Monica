package app.keemobile.kotpass.models

import java.time.Instant

/**
 * Arbitrary string data holder for database/group/entry metadata.
 */
data class CustomDataValue(
    val value: String,
    val lastModified: Instant? = null
)
