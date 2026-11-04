package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Export only this app's synthetic acceptance captures through its own user-scoped context. */
@RunWith(AndroidJUnit4::class)
class BitwardenScreenshotExportInstrumentedTest {
    @Test fun exportAcceptanceCaptures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = requireNotNull(context.getExternalFilesDir(null))
        val destination = File(context.filesDir, "bitwarden_acceptance_images").apply { mkdirs() }
        source.walkTopDown().filter { file ->
            file.isFile && file.extension == "png" &&
                (file.name.startsWith("auth-account-") || file.parentFile?.name?.startsWith("send-management-") == true ||
                    file.parentFile?.name?.startsWith("sync-issues-") == true)
        }.forEach { it.copyTo(File(destination, if (it.name.startsWith("auth-account-")) it.name else it.parentFile.name + "-" + it.name), overwrite = true) }
    }
}
