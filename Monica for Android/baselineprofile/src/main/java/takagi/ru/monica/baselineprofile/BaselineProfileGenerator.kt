package takagi.ru.monica.baselineprofile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "takagi.ru.monica.benchmark"
private const val RETAINED_RECORD = "Folder upgrade retained record"

/** Run only against a separately installed, explicitly seeded synthetic fixture. */
@RunWith(AndroidJUnit4::class)
@RequiresApi(Build.VERSION_CODES.P)
class BaselineProfileGenerator {
    @get:Rule val baselineProfileRule = BaselineProfileRule()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val targetPackage: String get() {
        val target = arguments.getString("monicaProfilePackage") ?: PACKAGE_NAME
        check(target.endsWith(".benchmark")) { "Use the isolated benchmark variant" }
        check(arguments.getString("monicaSyntheticFixture") == "true") {
            "Seed the synthetic benchmark vault and pass monicaSyntheticFixture=true"
        }
        return target
    }

    @Test fun generateBaselineProfile() = baselineProfileRule.collect(packageName = targetPackage) {
        pressHome()
        startActivityAndWait()
        unlockSyntheticVault()
        requireRecord()
        requireAction("Search", "搜尋", "搜索").click()
        val search = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        checkNotNull(search) { "Search input was not shown" }.text = RETAINED_RECORD
        device.waitForIdle()
        requireRecord()
        // Explicitly leave search rather than optionally collecting whichever screen appears.
        requireAction("Close search", "關閉搜尋", "关闭搜索").click()
        requireAction("Settings", "設定", "设置").click()
        check(device.wait(Until.hasObject(By.textContains("主密碼與鎖定")), 2_000) ||
            device.hasObject(By.textContains("主密码与锁定")) ||
            device.hasObject(By.textContains("Master password"))) { "Settings was not reached" }
    }

    @Test fun generateStartupProfile() = baselineProfileRule.collect(
        packageName = targetPackage, includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000)) {
            "The real cold authentication surface was not reached"
        }
    }

    private fun MacrobenchmarkScope.requireRecord() {
        check(device.wait(Until.hasObject(By.text(RETAINED_RECORD)), 10_000)) {
            "The seeded, unlocked password list was not reached"
        }
    }

    private fun MacrobenchmarkScope.unlockSyntheticVault() {
        val password = checkNotNull(arguments.getString("monicaFixturePassword")) {
            "A synthetic fixture password is required"
        }
        val edit = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        checkNotNull(edit) { "Vault must begin at real authentication" }.text = password
        requireAction("Unlock", "解鎖", "解锁").click()
        requireRecord()
    }

    private fun MacrobenchmarkScope.requireAction(vararg labels: String): UiObject2 {
        for (label in labels) {
            (device.findObject(By.desc(label)) ?: device.findObject(By.text(label)))?.let { return it }
        }
        error("Required UI action missing: ${labels.joinToString()}")
    }
}
