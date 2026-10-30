package app.keemobile.kotpass.models

import java.time.Instant

data class TimeData(
    val creationTime: Instant?,
    val lastAccessTime: Instant?,
    val lastModificationTime: Instant?,
    val locationChanged: Instant?,
    val expiryTime: Instant?,
    val expires: Boolean = false,
    val usageCount: Int = 0
) {
    companion object {
        fun create() = requireNotNull(Instant.now())
            .let { now ->
                TimeData(
                    creationTime = now,
                    lastAccessTime = now,
                    lastModificationTime = now,
                    locationChanged = now,
                    expiryTime = null,
                    expires = false,
                    usageCount = 0
                )
            }
    }
}
