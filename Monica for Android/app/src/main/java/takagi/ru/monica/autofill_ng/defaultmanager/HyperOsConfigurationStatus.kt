package takagi.ru.monica.autofill_ng.defaultmanager

internal enum class HyperOsCheck { UNKNOWN, YES, NO, UNSUPPORTED }
internal enum class HyperOsDrift { OFF, NO_BASELINE, UNKNOWN, UNCHANGED, CHANGED }

internal data class HyperOsConfigurationStatus(
    val credential: HyperOsCheck = HyperOsCheck.UNKNOWN,
    val primary: HyperOsCheck = HyperOsCheck.UNKNOWN,
    val autofill: HyperOsCheck = HyperOsCheck.UNKNOWN,
    val drift: HyperOsDrift = HyperOsDrift.OFF,
) {
    companion object {
        fun from(current: SettingsSnapshot?, baseline: SettingsSnapshot?, monitor: Boolean,
                 credential: String, autofill: String, userId: Int): HyperOsConfigurationStatus {
            val snapshot = current?.takeIf { it.userId == userId }
            fun selected(key: SettingKey, component: String): HyperOsCheck =
                snapshot?.let { if (canonicalComponent(component) in components(it[key])) HyperOsCheck.YES else HyperOsCheck.NO }
                    ?: HyperOsCheck.UNKNOWN
            val drift = when {
                !monitor -> HyperOsDrift.OFF
                baseline == null || baseline.userId != userId -> HyperOsDrift.NO_BASELINE
                snapshot == null -> HyperOsDrift.UNKNOWN
                snapshot.equivalentTo(baseline) -> HyperOsDrift.UNCHANGED
                else -> HyperOsDrift.CHANGED
            }
            return HyperOsConfigurationStatus(selected(SettingKey.CREDENTIALS, credential),
                selected(SettingKey.PRIMARY, credential), selected(SettingKey.AUTOFILL, autofill), drift)
        }
    }
}
