package takagi.ru.monica.passkey

import org.json.JSONObject

/** WebAuthn flags are credential metadata, not a device's successful-backup marker. */
object PasskeyBackupFlags {
    fun authenticatorFlags(backupEligible: Boolean?, backupState: Boolean?): Int {
        val eligible = backupEligible ?: true
        val backedUp = backupState ?: eligible
        require(eligible || !backedUp) { "Invalid WebAuthn backup flags" }
        return 0x05 or (if (eligible) 0x08 else 0) or (if (backedUp) 0x10 else 0)
    }

    fun mergeEligibility(
        existing: Boolean?,
        incoming: Boolean?,
        hasExistingCredential: Boolean = true,
    ): Boolean? {
        // NULL on a stored Android credential already signs as BE=true. It is not
        // interchangeable with a brand-new import whose eligibility is unknown.
        require(!hasExistingCredential || incoming == null || (existing ?: true) == incoming) {
            "WebAuthn backup eligibility changed for an existing credential"
        }
        return incoming ?: existing
    }

    fun readBoolean(payload: JSONObject, key: String): Boolean? {
        if (!payload.has(key) || payload.isNull(key)) return null
        val value = payload.get(key)
        require(value is Boolean) { "Invalid WebAuthn backup flag" }
        return value
    }
}
