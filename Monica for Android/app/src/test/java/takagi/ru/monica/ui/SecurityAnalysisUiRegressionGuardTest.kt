package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAnalysisUiRegressionGuardTest {

    @Test
    fun pageKeepsLoadingInsideTheScoreCardWithoutATopProgressBar() {
        val source = screenSource()

        assertFalse(source.contains("LinearProgressIndicator("))
        assertFalse(source.contains("Brush.linearGradient"))
        assertFalse(source.contains("Brush.sweepGradient"))
        assertFalse(source.contains("OverviewPill"))
    }

    @Test
    fun riskCategoriesUseCompactListAndUniqueAffectedAccountCount() {
        val source = screenSource()

        assertFalse(source.contains("items.chunked(2)"))
        assertTrue(source.contains("SecurityIssueRow"))
        assertTrue(source.contains("affectedAccountCount"))
    }

    @Test
    fun duplicateGroupsAreCollapsedByDefaultAndUseCompactEntryRows() {
        val source = screenSource()

        assertTrue(source.contains("CollapsibleSecurityGroupCard"))
        assertTrue(source.contains("initiallyExpanded = false"))
        assertTrue(source.contains("MonicaExpandableContent(expanded = expanded)"))
        assertTrue(source.contains("SecurityDetailEntryRow"))
        assertFalse(source.contains("private fun SecurityDetailEntryCard"))
    }

    @Test
    fun detailGroupsUseFilledM3CardsAndPasswordEntriesResolveTheirOwnIcons() {
        val source = screenSource()

        assertTrue(source.contains("SecurityPasswordEntryIcon(entry = entry)"))
        assertTrue(source.contains("containerColor = MaterialTheme.colorScheme.surfaceVariant"))
        assertFalse(source.contains("OutlinedCard("))
    }

    private fun screenSource(): String = projectFile(
        "app/src/main/java/takagi/ru/monica/ui/screens/SecurityAnalysisScreen.kt"
    ).readText()

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
