package takagi.ru.monica.autofill_ng.defaultmanager

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

/** Real Android secure settings through a test shell transport, not Shizuku/root authorization. */
class HyperOsSettingsDeviceTest {
    @Test fun actualSettingsRepairExternalChangeReadOnlyComparisonAndRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && context.packageName.startsWith("takagi.ru.monica.perf328"))
        check(InstrumentationRegistry.getArguments().getString("autofillIsolatedUser") == "true")
        fun shell(args: List<String>): CommandResult {
            val command = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            // UiAutomation tokenizes its command directly; quoting requires an actual shell.
            // Feed the script through stdin, preserving an original empty setting value too.
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use {
                it.write(command + "\nprintf '\\nMONICA_EXIT=%s\\n' \"\$?\"\n")
            }
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
            val marker = Regex("\\nMONICA_EXIT=(\\d+)\\s*$").find(output)
                ?: error("Shell command did not report completion")
            return CommandResult(marker.groupValues[1].toInt(), output.substring(0, marker.range.first).trim())
        }
        val store = ShellSettingsStore(::shell)
        val controller = SettingsController(store)
        val original = controller.read(user)
        val prefs = context.getSharedPreferences("hyperos_diagnostics", Context.MODE_PRIVATE)
        val oldBaseline = prefs.getString("verified", null)
        try {
            val applied = controller.apply(original, MonicaDefaultServices.credential, MonicaDefaultServices.autofill, true)
            HyperOsDiagnostics.verified(context, applied)
            assertTrue(applied.equivalentTo(controller.read(user)))
            assertEquals(HyperOsDrift.UNCHANGED, HyperOsConfigurationStatus.from(controller.read(user),
                HyperOsDiagnostics.baseline(context), true, MonicaDefaultServices.credential, MonicaDefaultServices.autofill, user).drift)
            store.write(user, SettingKey.PRIMARY, null)
            repeat(2) {
                val changed = controller.read(user)
                assertEquals(HyperOsDrift.CHANGED, HyperOsConfigurationStatus.from(changed,
                    HyperOsDiagnostics.baseline(context), true, MonicaDefaultServices.credential, MonicaDefaultServices.autofill, user).drift)
                assertNull(controller.read(user)[SettingKey.PRIMARY])
            }
            val restored = controller.replace(controller.read(user), original)
            assertTrue(restored.equivalentTo(original))
        } finally {
            // Exact pre-test values only, isolated task-owned Android user.
            SettingKey.entries.forEach { store.write(user, it, original[it]) }
            prefs.edit().putString("verified", oldBaseline).commit()
            assertTrue(controller.read(user).equivalentTo(original))
        }
    }
}
