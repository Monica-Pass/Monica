package takagi.ru.monica.data.dedup

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SingleVaultDedupComparatorTest {
    private fun atom(value: String) = SingleVaultDedupNode("field", mapOf("value" to value))
    private fun project(key: String, vararg values: String, source: String = "local", title: String = "Same") =
        projectNode(key, SingleVaultDedupNode("project", children = values.map(::atom), orderedChildren = false), values.size, source, title)
    private fun projectNode(key: String, node: SingleVaultDedupNode, count: Int = 1, source: String = "local", title: String = "Same") =
        SingleVaultDedupProject(SingleVaultDedupProjectSummary(key, source, title, emptyList(), count), node)

    @Test fun abcRequiresOneFinalSurvivingKeeper() {
        val projects = listOf(project("A", "1", "2", "3"), project("B", "1", "2"), project("C", "2", "3", "4"))
        val result = SingleVaultDedupComparator.analyze(projects)
        assertEquals(listOf(SingleVaultDedupCandidate("B", "A", 2)), result.candidates)
    }

    @Test fun unionCoverageDoesNotAuthorizeDeletion() {
        val result = SingleVaultDedupComparator.analyze(listOf(project("A", "1", "2"), project("B", "1", "3"), project("C", "2", "3")))
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun identicalProjectsAndContainmentChainsPointDirectlyToFinalKeeper() {
        val projects = listOf(project("D", "1"), project("C", "1", "2"), project("B", "1", "2", "3"), project("A", "1", "2", "3"))
        for (input in listOf(projects, projects.reversed(), projects.drop(1) + projects.first())) {
            val result = SingleVaultDedupComparator.analyze(input)
            assertEquals(listOf("B", "C", "D"), result.candidates.map { it.candidateKey })
            assertTrue(result.candidates.all { it.keeperKey == "A" })
        }
    }

    @Test fun multiplicityAndMeaningfulOrderAreNotSets() {
        assertFalse(SingleVaultDedupComparator.covers(project("A", "1"), project("B", "1", "1")))
        val first = projectNode("A", SingleVaultDedupNode("project", children = listOf(atom("1"), atom("2"), atom("3"))))
        val reversed = projectNode("B", SingleVaultDedupNode("project", children = listOf(atom("3"), atom("1"))))
        val subsequence = projectNode("C", SingleVaultDedupNode("project", children = listOf(atom("1"), atom("3"))))
        assertFalse(SingleVaultDedupComparator.covers(first, reversed))
        assertTrue(SingleVaultDedupComparator.covers(first, subsequence))
    }

    @Test fun bipartiteMatchingFindsAlternativeWithoutSharingOneNode() {
        val broad = SingleVaultDedupNode("field", mapOf("name" to "x"))
        val narrow = SingleVaultDedupNode("field", mapOf("name" to "x", "value" to "1"))
        val alternative = SingleVaultDedupNode("field", mapOf("name" to "x", "value" to "2"))
        val keeper = projectNode("A", SingleVaultDedupNode("project", children = listOf(narrow, alternative), orderedChildren = false))
        val candidate = projectNode("B", SingleVaultDedupNode("project", children = listOf(broad, narrow), orderedChildren = false))
        assertTrue(SingleVaultDedupComparator.covers(keeper, candidate))
        assertFalse(SingleVaultDedupComparator.covers(projectNode("C", SingleVaultDedupNode("project", children = listOf(narrow), orderedChildren = false)), candidate))
    }

    @Test fun boundUsernameAndPasswordCannotBeCombinedAcrossAccounts() {
        fun account(username: String, password: String) = SingleVaultDedupNode("account", mapOf("username" to username), listOf(atom(password)))
        val keeper = projectNode("A", SingleVaultDedupNode("project", children = listOf(account("alice", "1"), account("bob", "2")), orderedChildren = false))
        val candidate = projectNode("B", SingleVaultDedupNode("project", children = listOf(account("alice", "2")), orderedChildren = false))
        assertFalse(SingleVaultDedupComparator.covers(keeper, candidate))
    }

    @Test fun valuesNamesProtectionTypesAndControlCharactersStayExact() {
        val values = listOf("x", "X", " x", "x ", "x\u0001y", "x\u0000y", "é", "e\u0301")
        assertTrue(SingleVaultDedupComparator.analyze(values.mapIndexed { index, value -> project(index.toString(), value) }).candidates.isEmpty())
        val one = projectNode("A", SingleVaultDedupNode("text", mapOf("name" to "a", "value" to "b\u0001c", "protected" to "true")))
        val two = projectNode("B", SingleVaultDedupNode("text", mapOf("name" to "a\u0001b", "value" to "c", "protected" to "true")))
        assertFalse(SingleVaultDedupComparator.covers(one, two))
        assertFalse(SingleVaultDedupComparator.covers(one, projectNode("C", SingleVaultDedupNode("text", mapOf("name" to "a", "value" to "b\u0001c", "protected" to "false")))))
        assertFalse(SingleVaultDedupComparator.covers(one, projectNode("D", SingleVaultDedupNode("boolean", mapOf("name" to "a", "value" to "b\u0001c", "protected" to "true")))))
    }

    @Test fun incompleteEmptyMixedSourceAndDuplicateKeysCannotGenerateCandidates() {
        val complete = project("A", "1")
        for (reason in SingleVaultDedupBlockReason.entries) {
            val blocked = SingleVaultDedupProject(complete.summary.copy(key = "B"), complete.content, setOf(reason))
            assertTrue(SingleVaultDedupComparator.analyze(listOf(complete, blocked)).candidates.isEmpty())
        }
        assertTrue(SingleVaultDedupComparator.analyze(listOf(project("A"), project("B"))).candidates.isEmpty())
        assertTrue(SingleVaultDedupComparator.analyze(listOf(complete, project("B", "1", source = "remote"))).candidates.isEmpty())
        assertTrue(SingleVaultDedupComparator.analyze(listOf(complete, complete)).candidates.isEmpty())
        assertTrue(SingleVaultDedupComparator.analyze(listOf(complete, project("B", "1", title = "Other"))).candidates.isEmpty())
        val unsupported = SingleVaultDedupProject(complete.summary.copy(key = "passkey", contentCount = 0), null, setOf(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT))
        assertEquals(setOf(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT), SingleVaultDedupComparator.analyze(listOf(unsupported)).blockers["passkey"])
    }

    @Test fun workLimitDiscardsPartialCandidateListAndCancellationPropagates() {
        val projects = (1..10).map { project(it.toString(), "same") }
        val limited = SingleVaultDedupComparator.analyze(projects, maxComparisons = 3)
        assertTrue(limited.candidates.isEmpty())
        assertTrue(limited.comparisonLimitReached)
        assertEquals(10, limited.blockers.size)
        try {
            SingleVaultDedupComparator.analyze(projects, checkActive = { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun allSmallThreeProjectMultisetsKeepDirectCoveringSurvivors() {
        val choices = listOf(listOf("1"), listOf("2"), listOf("1", "1"), listOf("1", "2"), listOf("1", "1", "2"))
        for (a in choices) for (b in choices) for (c in choices) {
            val projects = listOf(project("A", *a.toTypedArray()), project("B", *b.toTypedArray()), project("C", *c.toTypedArray()))
            val result = SingleVaultDedupComparator.analyze(projects)
            val deleted = result.candidates.map { it.candidateKey }.toSet()
            for (candidate in result.candidates) {
                assertFalse(candidate.keeperKey in deleted)
                assertTrue(SingleVaultDedupComparator.covers(projects.single { it.summary.key == candidate.keeperKey }, projects.single { it.summary.key == candidate.candidateKey }))
            }
        }
    }
}
