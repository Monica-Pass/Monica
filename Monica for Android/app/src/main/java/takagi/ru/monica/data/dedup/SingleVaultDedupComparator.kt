package takagi.ru.monica.data.dedup

/** Read-only containment. No writes, secret hashes, approximate scores, or union coverage. */
object SingleVaultDedupComparator {
    private class WorkLimit : RuntimeException()

    fun analyze(
        projects: List<SingleVaultDedupProject>,
        checkActive: () -> Unit = {},
        maxComparisons: Int = 200_000,
        maxProjects: Int = 10_000
    ): SingleVaultDedupAnalysis {
        require(maxComparisons > 0 && maxProjects > 0)
        val summaries = projects.map { it.summary }
        val blockers = projects.associate { it.summary.key to it.blockers }.toMutableMap()
        if (projects.map { it.summary.key }.distinct().size != projects.size) {
            return SingleVaultDedupAnalysis(summaries, emptyList(), projects.associate {
                it.summary.key to (it.blockers + SingleVaultDedupBlockReason.INCONSISTENT_PROJECT)
            })
        }
        if (projects.map { it.summary.sourceKey }.distinct().size > 1) {
            return SingleVaultDedupAnalysis(summaries, emptyList(), projects.associate {
                it.summary.key to (it.blockers + SingleVaultDedupBlockReason.DIFFERENT_SOURCE)
            })
        }
        fun limited() = SingleVaultDedupAnalysis(summaries, emptyList(), projects.associate {
            it.summary.key to (it.blockers + SingleVaultDedupBlockReason.COMPARISON_LIMIT)
        }, comparisonLimitReached = true)
        if (projects.size > maxProjects) return limited()
        var work = 0
        fun tick() {
            checkActive()
            if (++work > maxComparisons) throw WorkLimit()
        }
        return try {
            val available = projects.filter {
                if (it.blockers.isNotEmpty()) false
                else if (it.summary.contentCount <= 0 || it.content == null) {
                    blockers[it.summary.key] = it.blockers + SingleVaultDedupBlockReason.EMPTY_CONTENT
                    false
                } else true
            }.sortedBy { it.summary.key }
            // Exact title is part of the safety context. Other context is compared
            // structurally below; this bucket only avoids unrelated N-squared work.
            val candidates = mutableListOf<SingleVaultDedupCandidate>()
            for (bucket in available.groupBy { it.summary.title }.values) {
                checkActive()
                val cache = mutableMapOf<Pair<String, String>, Boolean>()
                fun covers(keeper: SingleVaultDedupProject, candidate: SingleVaultDedupProject): Boolean =
                    cache.getOrPut(keeper.summary.key to candidate.summary.key) {
                        contains(requireNotNull(keeper.content), requireNotNull(candidate.content), ::tick)
                    }
                val representatives = mutableListOf<SingleVaultDedupProject>()
                for (project in bucket) {
                    if (representatives.none { covers(it, project) && covers(project, it) }) {
                        representatives += project
                    }
                }
                val survivors = representatives.filter { project ->
                    representatives.none { other -> other !== project && covers(other, project) && !covers(project, other) }
                }
                // Never point at another deletion candidate. Recheck direct
                // containment instead of trusting a transitive deletion chain.
                for (project in bucket) {
                    if (project in survivors) continue
                    val keeper = survivors.firstOrNull { covers(it, project) } ?: continue
                    candidates += SingleVaultDedupCandidate(project.summary.key, keeper.summary.key, project.summary.contentCount)
                }
            }
            SingleVaultDedupAnalysis(summaries, candidates.sortedBy { it.candidateKey }, blockers.filterValues { it.isNotEmpty() })
        } catch (_: WorkLimit) {
            // A partial scan can be shown as incomplete, but never supplies a
            // deletion plan that looks complete after silently truncating work.
            limited()
        }
    }

    fun covers(
        keeper: SingleVaultDedupProject,
        candidate: SingleVaultDedupProject,
        checkActive: () -> Unit = {},
        maxComparisons: Int = 200_000
    ): Boolean {
        require(maxComparisons > 0)
        if (keeper.blockers.isNotEmpty() || candidate.blockers.isNotEmpty() ||
            keeper.summary.sourceKey != candidate.summary.sourceKey ||
            keeper.summary.title != candidate.summary.title ||
            keeper.summary.contentCount <= 0 || candidate.summary.contentCount <= 0 ||
            keeper.content == null || candidate.content == null) return false
        var work = 0
        return try {
            contains(keeper.content, candidate.content, tick = {
                checkActive()
                if (++work > maxComparisons) throw WorkLimit()
            })
        } catch (_: WorkLimit) { false }
    }

    private fun contains(keeper: SingleVaultDedupNode, candidate: SingleVaultDedupNode, tick: () -> Unit, depth: Int = 0): Boolean {
        tick()
        if (depth > 64) throw WorkLimit()
        if (keeper.kind != candidate.kind || keeper.orderedChildren != candidate.orderedChildren ||
            candidate.attributes.any { (key, value) -> keeper.attributes[key] != value } ||
            candidate.children.size > keeper.children.size) return false
        if (candidate.orderedChildren) {
            var next = 0
            for (child in candidate.children) {
                while (next < keeper.children.size && !contains(keeper.children[next], child, tick, depth + 1)) next++
                if (next == keeper.children.size) return false
                next++
            }
            return true
        }
        // Bipartite matching preserves multiplicity. A greedy first match would
        // incorrectly reject a broad node when a later narrow node needs it.
        val assigned = IntArray(keeper.children.size) { -1 }
        for (start in candidate.children.indices) {
            val queue = java.util.ArrayDeque<Int>()
            val previous = IntArray(candidate.children.size) { -1 }
            val viaKeeper = IntArray(candidate.children.size) { -1 }
            val seen = BooleanArray(keeper.children.size)
            queue.add(start)
            var freeKeeper = -1
            var lastCandidate = -1
            while (queue.isNotEmpty() && freeKeeper < 0) {
                val index = queue.removeFirst()
                for (j in keeper.children.indices) {
                    tick()
                    if (seen[j] || !contains(keeper.children[j], candidate.children[index], tick, depth + 1)) continue
                    seen[j] = true
                    if (assigned[j] < 0) { freeKeeper = j; lastCandidate = index; break }
                    val displaced = assigned[j]
                    previous[displaced] = index
                    viaKeeper[displaced] = j
                    queue.add(displaced)
                }
            }
            if (freeKeeper < 0) return false
            var index = lastCandidate
            var j = freeKeeper
            while (index >= 0) {
                assigned[j] = index
                j = viaKeeper[index]
                index = previous[index]
            }
        }
        return true
    }
}
