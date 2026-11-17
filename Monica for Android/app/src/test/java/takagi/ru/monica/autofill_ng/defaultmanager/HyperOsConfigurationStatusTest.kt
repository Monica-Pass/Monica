package takagi.ru.monica.autofill_ng.defaultmanager

import org.junit.Assert.*
import org.junit.Test

class HyperOsConfigurationStatusTest {
    private val credential = "local.monica/.Credential"
    private val autofill = "local.monica/.Autofill"
    private val original = SettingsSnapshot(10, mapOf(SettingKey.CREDENTIALS to credential,
        SettingKey.PRIMARY to credential, SettingKey.AUTOFILL to autofill))
    private fun status(current: SettingsSnapshot?, baseline: SettingsSnapshot? = original, monitor: Boolean = true) =
        HyperOsConfigurationStatus.from(current, baseline, monitor, credential, autofill, 10)

    @Test fun unreadableDoesNotMeanDisabledOrChanged() {
        val result = status(null)
        assertEquals(HyperOsCheck.UNKNOWN, result.primary)
        assertEquals(HyperOsDrift.UNKNOWN, result.drift)
    }
    @Test fun canonicalNamesAndProviderOrderDoNotCauseFalseDrift() {
        val long = original.copy(values = original.values.mapValues { canonicalComponent(it.value!!) })
        assertEquals(HyperOsDrift.UNCHANGED, status(long).drift)
        assertEquals(HyperOsCheck.YES, status(long).primary)
    }
    @Test fun eachKeyCanIndependentlyDriftWithoutWriting() {
        for (key in SettingKey.entries) {
            val changed = original.copy(values = original.values + (key to "local.other/.Service"))
            assertEquals(HyperOsDrift.CHANGED, status(changed).drift)
            assertEquals(credential, original[SettingKey.PRIMARY])
        }
    }
    @Test fun additionalProvidersCountAsConfigurationChange() {
        val changed = original.copy(values = original.values + (SettingKey.CREDENTIALS to "$credential:local.other/.Service"))
        assertEquals(HyperOsCheck.YES, status(changed).credential)
        assertEquals(HyperOsDrift.CHANGED, status(changed).drift)
    }
    @Test fun disabledMonitoringAndMissingBaselineAreDifferent() {
        assertEquals(HyperOsDrift.OFF, status(null, monitor = false).drift)
        assertEquals(HyperOsDrift.NO_BASELINE, status(original, baseline = null).drift)
    }
    @Test fun otherAndroidUsersCannotSupplyCurrentOrBaseline() {
        assertEquals(HyperOsCheck.UNKNOWN, status(original.copy(userId = 0)).primary)
        assertEquals(HyperOsDrift.NO_BASELINE, status(original, original.copy(userId = 0)).drift)
    }
    @Test fun emptySelectionIsKnownAbsence() {
        val empty = original.copy(values = SettingKey.entries.associateWith { null })
        assertEquals(HyperOsCheck.NO, status(empty).primary)
        assertEquals(HyperOsDrift.CHANGED, status(empty).drift)
    }
}
