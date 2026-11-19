package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePaneLocalizationGuardTest {

    @Test
    fun widePanePlaceholdersUseLocalizedStringResources() {
        val expectedResources = mapOf(
            "app/src/main/java/takagi/ru/monica/ui/PasswordTabPane.kt" to
                listOf("select_password_hint"),
            "app/src/main/java/takagi/ru/monica/ui/AuthenticatorTabPane.kt" to
                listOf("select_totp_hint", "inline_totp_edit_unavailable"),
            "app/src/main/java/takagi/ru/monica/ui/cardwallet/CardWalletDetailPaneContent.kt" to
                listOf("select_item_hint"),
            "app/src/main/java/takagi/ru/monica/ui/note/NotePane.kt" to
                listOf("select_note_hint"),
            "app/src/main/java/takagi/ru/monica/ui/send/SendPane.kt" to
                listOf("select_send_preview_hint")
        )
        val hardcodedEnglish = listOf(
            "Select an item to view details",
            "Select a note to view or edit",
            "Select an item to preview",
            "Select a setting to view details",
            "This item is not available for inline editing"
        )

        expectedResources.forEach { (path, resourceNames) ->
            val source = projectFile(path).readText()
            resourceNames.forEach { resourceName ->
                assertTrue(
                    "$path must use R.string.$resourceName",
                    source.contains("stringResource(R.string.$resourceName)")
                )
            }
            hardcodedEnglish.forEach { text ->
                assertFalse("$path contains hardcoded English", source.contains(text))
            }
        }
    }

    @Test
    fun chineseResourcesCoverWidePanePlaceholders() {
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val expectedTranslations = mapOf(
            "select_password_hint" to "选择一个密码以查看详情",
            "select_totp_hint" to "选择一个验证器以查看详情",
            "select_item_hint" to "选择一个项目以查看详情",
            "select_note_hint" to "选择一条笔记进行查看或编辑",
            "select_send_preview_hint" to "选择一个项目进行预览",
            "select_setting_hint" to "选择一个设置项以查看详情",
            "inline_totp_edit_unavailable" to "当前项目无法在此处编辑"
        )

        expectedTranslations.forEach { (name, translation) ->
            assertTrue(
                "Missing Chinese translation for $name",
                strings.contains("<string name=\"$name\">$translation</string>")
            )
        }
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
