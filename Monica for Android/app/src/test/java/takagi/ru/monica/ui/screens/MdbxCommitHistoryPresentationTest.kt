package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.localization.xmlTestStrings
import takagi.ru.monica.repository.MdbxCommitChangeSummary
import takagi.ru.monica.repository.MdbxDeltaSummary

class MdbxCommitHistoryPresentationTest {
    private val strings = xmlTestStrings("zh")

    @Test
    fun batchCreatesBecomeOneReadableAddOperation() {
        val summary = delta(
            operationKind = "monica-upsert-entries",
            changes = listOf(
                change("entry", "one", "create"),
                change("entry", "two", "create"),
                change("entry", "three", "create")
            )
        )

        val presentation = summary.toHistoryPresentation(strings)

        assertEquals("添加了3 个条目", presentation.title)
        assertEquals("新增 3", presentation.supportingText)
        assertEquals(3, presentation.objectCount)
        assertTrue(presentation.canRevert)
        assertFalse(presentation.isSystemCommit)
    }

    @Test
    fun initializationCommitIsExplainedWithoutPretendingItHasObjects() {
        val presentation = delta(
            operationKind = "monica-initialize",
            commitKind = "change",
            changeScope = "project",
            changes = emptyList()
        ).toHistoryPresentation(strings)

        assertEquals("初始化数据库", presentation.title)
        assertEquals("建立数据库根目录和初始结构", presentation.supportingText)
        assertTrue(presentation.isSystemCommit)
        assertFalse(presentation.canRevert)
    }

    @Test
    fun keyRotationCommitIsSystemHistoryAndCannotRevert() {
        val presentation = delta(
            commitKind = "key-rotation",
            changeScope = "key-epoch",
            changes = emptyList()
        ).toHistoryPresentation(strings)

        assertEquals("数据库系统事件", presentation.title)
        assertTrue(presentation.supportingText.contains("加密密钥"))
        assertTrue(presentation.isSystemCommit)
        assertFalse(presentation.canRevert)
    }

    @Test
    fun mixedActionsUseCompactCounts() {
        val presentation = delta(
            changes = listOf(
                change("entry", "one", "create"),
                change("entry", "two", "update"),
                change("entry", "three", "delete")
            )
        ).toHistoryPresentation(strings)

        assertEquals("更新了3 个条目", presentation.title)
        assertEquals("新增 1 · 修改 1 · 删除 1", presentation.supportingText)
    }

    @Test
    fun objectTypeLabelsPreferMonicaContentType() {
        assertEquals("密码", mdbxHistoryObjectTypeLabel(strings, "entry", "login"))
        assertEquals("验证器", mdbxHistoryObjectTypeLabel(strings, "entry", "totp"))
        assertEquals("Steam 账号", mdbxHistoryObjectTypeLabel(strings, "entry", "steam-mafile"))
        assertEquals("文件夹", mdbxHistoryObjectTypeLabel(strings, "project"))
    }

    @Test
    fun switchingLanguageKeepsTheSameActionsAndRevertEligibility() {
        val summary = delta(changes = listOf(
            change("entry", "one", "create"),
            change("entry", "two", "create"),
            change("entry", "three", "create")
        ))
        val expected = listOf(
            Triple("en", "Added: Entry × 3", "Added 3"),
            Triple("ru", "Добавление: Запись × 3", "Добавлено: 3"),
            Triple("zh", "添加了3 个条目", "新增 3")
        )

        expected.forEach { (language, title, supportingText) ->
            val presentation = summary.toHistoryPresentation(xmlTestStrings(language))
            assertEquals(title, presentation.title)
            assertEquals(supportingText, presentation.supportingText)
            assertEquals(MdbxHistoryAction.CREATED, presentation.primaryAction)
            assertEquals(3, presentation.actionCounts.created)
            assertEquals(3, presentation.objectCount)
            assertTrue(presentation.canRevert)
        }
    }

    @Test
    fun translatedTagAndAddressLabelsKeepTheirObjectMeaning() {
        val english = xmlTestStrings("en")
        assertEquals("Tags", mdbxHistoryObjectTypeLabel(english, "object-label"))
        assertEquals("Address", mdbxHistoryObjectTypeLabel(english, "entry", "billing-address"))
    }

    private fun delta(
        operationKind: String? = "monica-upsert-entries",
        commitKind: String = "change",
        changeScope: String = "entry",
        changes: List<MdbxCommitChangeSummary>
    ) = MdbxDeltaSummary(
        commitId = "commit",
        deviceId = "device",
        localSeq = 1,
        commitKind = commitKind,
        changeScope = changeScope,
        changedObjectIds = changes.joinToString(prefix = "[", postfix = "]") { "\"${it.objectId}\"" },
        changedObjectPreview = "",
        changedFieldSummary = "",
        parentCount = 1,
        createdAt = "2026-08-01T00:00:00Z",
        operationKind = operationKind,
        changes = changes
    )

    private fun change(type: String, id: String, action: String) = MdbxCommitChangeSummary(
        objectType = type,
        objectId = id,
        action = action,
        fields = emptyList()
    )
}
