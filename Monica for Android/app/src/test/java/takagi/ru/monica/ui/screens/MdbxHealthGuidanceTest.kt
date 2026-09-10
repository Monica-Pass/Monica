package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.localization.xmlTestStrings
import takagi.ru.monica.repository.MdbxHealthIssueDiagnostic
import takagi.ru.monica.repository.MdbxHealthSeverity
import takagi.ru.monica.repository.MdbxVaultDiagnostics

class MdbxHealthGuidanceTest {
    private val strings = xmlTestStrings("zh")

    @Test
    fun duplicateTombstonesBecomeFriendlyRecoveryGuidance() {
        val objectId = "9ecd1faf-2173-3ebd-935f-e5cfbf3732c46"
        val diagnostics = diagnostics(
            integrityOk = false,
            healthIssues = listOf(
                issue(
                    severity = MdbxHealthSeverity.ERROR,
                    category = "tombstones",
                    description = "entry $objectId has 2 typed tombstones; expected exactly one current marker"
                )
            )
        )

        val guidance = diagnostics.healthGuidance(strings).single()

        assertEquals("删除记录重复", guidance.title)
        assertEquals(MdbxHealthGuidanceAction.SNAPSHOTS, guidance.action)
        assertFalse(guidance.summary.contains(objectId))
        assertTrue(guidance.impact.contains("同步"))
        assertTrue(guidance.steps.any { it.contains("全量快照") })
        assertTrue(guidance.technicalDetails.single().contains(objectId))
    }

    @Test
    fun unlockOnlyWarningDoesNotReportDatabaseDamage() {
        val diagnostics = diagnostics(
            integrityOk = true,
            healthIssues = listOf(
                issue(
                    severity = MdbxHealthSeverity.WARNING,
                    category = "vault-header-integrity",
                    description = "vault header authentication requires an unlocked keyring for verification"
                )
            )
        )

        val guidance = diagnostics.healthGuidance(strings).single()

        assertEquals(0, diagnostics.healthIssueCount)
        assertEquals(1, diagnostics.healthNoticeCount)
        assertEquals("等待完成安全校验", guidance.title)
        assertEquals(MdbxHealthGuidanceAction.RECHECK, guidance.action)
        assertTrue(guidance.summary.contains("解锁"))
    }

    @Test
    fun structuredIssuesAreCountedWithoutDuplicatingLegacySummaryTiles() {
        val diagnostics = diagnostics(
            integrityOk = false,
            danglingParentCount = 1,
            healthIssues = listOf(
                issue(
                    severity = MdbxHealthSeverity.ERROR,
                    category = "orphans",
                    description = "entry item-1 references non-existent project folder-1"
                )
            )
        )

        assertEquals(1, diagnostics.healthIssueCount)
    }

    @Test
    fun repeatedAttachmentProblemsAreGroupedButKeepTechnicalDetails() {
        val diagnostics = diagnostics(
            integrityOk = false,
            healthIssues = listOf(
                issue(
                    severity = MdbxHealthSeverity.ERROR,
                    category = "attachment-chunks",
                    description = "attachment a-1 has chunk_count=2 but 1 actual chunks"
                ),
                issue(
                    severity = MdbxHealthSeverity.ERROR,
                    category = "attachment-chunks",
                    description = "attachment a-2 has non-sequential chunk index: expected 1, got 2"
                )
            )
        )

        val guidance = diagnostics.healthGuidance(strings).single()

        assertEquals("附件分片不完整（2 项）", guidance.title)
        assertEquals(2, guidance.technicalDetails.size)
        assertEquals(MdbxHealthGuidanceAction.ATTACHMENTS, guidance.action)
    }

    @Test
    fun unknownCategoryGetsSafeGenericSteps() {
        val diagnostics = diagnostics(
            integrityOk = false,
            healthIssues = listOf(
                issue(
                    severity = MdbxHealthSeverity.ERROR,
                    category = "future-check",
                    description = "future diagnostic detail"
                )
            )
        )

        val guidance = diagnostics.healthGuidance(strings).single()

        assertEquals("发现未识别的数据库异常", guidance.title)
        assertEquals(MdbxHealthGuidanceAction.MAINTENANCE, guidance.action)
        assertTrue(guidance.steps.any { it.contains("备份") })
        assertEquals(listOf("future diagnostic detail"), guidance.technicalDetails)
    }

    @Test
    fun knownNativeCategoriesHaveDedicatedGuidance() {
        val samples = listOf(
            "integrity" to "basic integrity check failed: malformed page",
            "vault-header-integrity" to "vault header authentication failed: mismatch",
            "incremental-integrity-root" to "incremental integrity root is stale and requires rebuild",
            "commit-chain" to "commit c-1 references non-existent parent c-0",
            "commit-integrity" to "commit c-1 integrity tag mismatch",
            "attachment-chunks" to "attachment a-1 has chunk_count=2 but 1 actual chunks",
            "snapshots" to "snapshot s-1 failed hash or authenticated payload verification",
            "orphans" to "entry e-1 references non-existent project p-1",
            "collection-profiles" to "collection profile p-1 has no owning project",
            "tombstones" to "entry e-1 has 2 typed tombstones; expected exactly one current marker",
            "tombstone-acknowledgements" to "device d-1 tombstone acknowledgement failed causal validation",
            "purge-receipts" to "permanently purged entry e-1 still has a tombstone",
            "stale-heads" to "device d-1 head c-1 references non-existent commit"
        )

        samples.forEach { (category, description) ->
            val guidance = diagnostics(
                integrityOk = false,
                healthIssues = listOf(
                    issue(MdbxHealthSeverity.ERROR, category, description)
                )
            ).healthGuidance(strings).single()

            assertFalse("$category should have dedicated guidance", guidance.title.contains("未识别"))
        }
    }

    @Test
    fun changingLanguageKeepsDiagnosticClassificationAndTechnicalEvidence() {
        val diagnosticText = "vault header authentication requires an unlocked keyring for verification"
        val diagnostics = diagnostics(
            integrityOk = true,
            healthIssues = listOf(issue(
                MdbxHealthSeverity.WARNING,
                "vault-header-integrity",
                diagnosticText
            ))
        )
        val titles = mapOf(
            "en" to "Security verification is pending",
            "ru" to "Проверка безопасности ещё не завершена",
            "zh" to "等待完成安全校验"
        )

        titles.forEach { (language, title) ->
            val guidance = diagnostics.healthGuidance(xmlTestStrings(language)).single()
            assertEquals(title, guidance.title)
            assertEquals(MdbxHealthSeverity.WARNING, guidance.severity)
            assertEquals(MdbxHealthGuidanceAction.RECHECK, guidance.action)
            assertEquals(listOf(diagnosticText), guidance.technicalDetails)
            assertEquals(0, diagnostics.healthIssueCount)
        }
    }

    private fun diagnostics(
        integrityOk: Boolean,
        healthIssues: List<MdbxHealthIssueDiagnostic>,
        danglingParentCount: Int = 0
    ) = MdbxVaultDiagnostics(
        databaseId = 1L,
        filePath = "vault.mdbx",
        fileExists = true,
        fileSizeBytes = 1024L,
        isReadable = true,
        integrityOk = integrityOk,
        healthIssues = healthIssues,
        danglingParentCount = danglingParentCount,
        lastSyncStatus = "IN_SYNC"
    )

    private fun issue(
        severity: MdbxHealthSeverity,
        category: String,
        description: String
    ) = MdbxHealthIssueDiagnostic(
        severity = severity,
        category = category,
        description = description
    )
}
