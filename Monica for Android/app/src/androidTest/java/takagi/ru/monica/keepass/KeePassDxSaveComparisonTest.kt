package takagi.ru.monica.keepass

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Optional benchmark of the official KeePassDX APK, using only a disposable synthetic vault. */
class KeePassDxSaveComparisonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val pkg = "com.kunzisoft.keepass.libre"
    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i)))
        }
    }
    private suspend fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        try {
            return withTimeout(20000) {
                while (true) {
                    nodes(automation.rootInActiveWindow).firstOrNull(predicate)?.let { return@withTimeout it }
                    delay(50)
                }
                error("unreachable")
            }
        } catch (error: TimeoutCancellationException) {
            val ids = nodes(automation.rootInActiveWindow).map { "${it.packageName}:${it.viewIdResourceName}:${it.className}" }.take(80).joinToString("\n")
            throw AssertionError("Comparator control unavailable; visible IDs:\n$ids", error)
        }
    }
    private suspend fun id(name: String) = find { it.viewIdResourceName == "$pkg:id/$name" }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun text(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    @Test fun officialEditorCommitsSameSyntheticVault() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("compareKeePassDx") == "true")
        val context = instrumentation.targetContext
        val fixture = java.io.File(context.getExternalFilesDir(null), "kdbx-comparator-original.kdbx")
        require(fixture.isFile) { "Push the captured 300-entry synthetic editor fixture first" }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            notificationTimeout = 0
        }
        KeePassDeleteInvestigationTest().scenario { _, service, databaseId, _, uri ->
            val resolver = context.contentResolver
            resolver.openOutputStream(uri, "wt")!!.use { it.write(fixture.readBytes()) }
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            val checkbox = id("password_checkbox")
            if (!checkbox.isChecked) click(checkbox)
            text(id("password_text_view"), "Synthetic KDBX deletion test")
            click(find { it.viewIdResourceName == "$pkg:id/activity_password_open_button" && it.isEnabled })
            click(find { it.text?.toString() == "Synthetic 0" })
            click(id("entry_edit_fab"))
            // KeePassDX's default user-verification challenge happens before editing.
            val entryOrChallenge = find { it.viewIdResourceName == "$pkg:id/setup_check_password_edit_text" || (it.isEditable && it.text?.toString() == "Synthetic 0") }
            if (entryOrChallenge.viewIdResourceName == "$pkg:id/setup_check_password_edit_text") {
                text(entryOrChallenge, "Synt")
                click(find { it.viewIdResourceName == "android:id/button1" && it.isEnabled })
            }
            text(find { it.isEditable && it.text?.toString() == "Synthetic 0" }, "DX same fixture saved")
            val save = id("entry_edit_validate")
            val returned = AtomicLong()
            automation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.className?.toString() == "com.kunzisoft.keepass.activities.EntryActivity") {
                    returned.compareAndSet(0L, SystemClock.elapsedRealtime())
                }
            }
            val started = SystemClock.elapsedRealtime()
            try {
                click(save)
                withTimeout(30000) { while (returned.get() == 0L) delay(10) }
                val closed = withTimeout(30000) {
                    var time = 0L
                    while (time < started) {
                        time = resolver.query(uri, arrayOf("write_finished_ms"), null, null, null)!!.use { it.moveToFirst(); it.getLong(0) }
                        if (time < started) delay(10)
                    }
                    time
                }
                val opened = resolver.query(uri, arrayOf("write_opened_ms"), null, null, null)!!.use { it.moveToFirst(); it.getLong(0) }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "KDBX_SAVE_PERF stage=keepassdx_save_action_until_return ms=${returned.get() - started}\n" +
                        "KDBX_SAVE_PERF stage=keepassdx_save_action_until_write_open ms=${opened - started}\n" +
                        "KDBX_SAVE_PERF stage=keepassdx_save_action_until_write_close ms=${closed - started}\n")
                })
                val actual = service.openNativeBrowser(databaseId, true).getOrThrow()
                assertEquals(300, actual.entries.size)
                assertEquals(1, actual.entries.count { it.title == "DX same fixture saved" })
                val saved = resolver.openInputStream(uri)!!.use {
                    takagi.ru.monica.utils.KeePassCodecSupport.decode(it,
                        app.keemobile.kotpass.database.Credentials.from(
                            app.keemobile.kotpass.cryptography.EncryptedValue.fromString("Synthetic KDBX deletion test")))
                } as app.keemobile.kotpass.database.KeePassDatabase.Ver4x
                val kdf = saved.header.kdfParameters as app.keemobile.kotpass.database.header.KdfParameters.Argon2
                assertEquals(32uL * 1024uL * 1024uL, kdf.memory)
                assertEquals(8uL, kdf.iterations)
                assertEquals(2U, kdf.parallelism)
                assertEquals(0x13U, kdf.version)
                assertEquals(app.keemobile.kotpass.database.header.KdfParameters.Argon2.Variant.Argon2d, kdf.variant)
            } finally { automation.setOnAccessibilityEventListener(null) }
        }
    }
}
