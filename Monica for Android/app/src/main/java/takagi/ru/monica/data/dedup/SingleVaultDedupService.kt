package takagi.ru.monica.data.dedup

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.*
import kotlin.coroutines.coroutineContext

/** Authorization and reviewed snapshots stay here, never in navigation/saved state. */
internal class SingleVaultDedupService(
    private val database: PasswordDatabase,
    private val adaptersProvider: suspend () -> List<SingleVaultDedupSourceAdapter>,
) : SingleVaultDedupOperations {
    private class Plan(val id: String, val generation: Long, val lock: Long,
        val adapter: SingleVaultDedupSourceAdapter, val capture: SingleVaultDedupCapture, val analysis: SingleVaultDedupAnalysis)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val publicationLock = Any()
    @Volatile private var plan: Plan? = null
    @Volatile private var adapters = emptyList<SingleVaultDedupSourceAdapter>()

    override fun invalidate() = synchronized(publicationLock) { generation.incrementAndGet(); plan = null; adapters = emptyList() }

    private suspend fun guard(): () -> Unit {
        val version = generation.get()
        val lock = SessionManager.lockGeneration.value
        val context = coroutineContext
        return {
            context.ensureActive()
            check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == lock && generation.get() == version) {
                "Deduplication authorization expired"
            }
        }.also { it() }
    }

    override suspend fun sources(): List<SingleVaultDedupUiSource> = mutex.withLock {
        val checkActive = guard()
        discoverAdapters(checkActive).map { it.source }
    }

    private suspend fun discoverAdapters(checkActive: () -> Unit): List<SingleVaultDedupSourceAdapter> {
        checkActive()
        val loaded = adaptersProvider()
        require(loaded.map { it.source.key }.distinct().size == loaded.size)
        synchronized(publicationLock) { checkActive(); adapters = loaded }
        return loaded
    }

    override suspend fun analyze(sourceKey: String, includeArchived: Boolean): SingleVaultDedupUiAnalysis = mutex.withLock {
        val checkActive = guard()
        plan = null
        val version = generation.get()
        val lock = SessionManager.lockGeneration.value
        // The UI revokes the old plan when selecting a source or starting a scan.
        // Discovery belongs to this new authorization, never to the revoked capture.
        val adapter = discoverAdapters(checkActive).single { it.source.key == sourceKey }
        check(adapter.source.issue !in setOf(SingleVaultDedupUiIssue.LOCKED, SingleVaultDedupUiIssue.SOURCE_UNAVAILABLE))
        val capture = adapter.capture(includeArchived, checkActive)
        val result = SingleVaultDedupComparator.analyze(capture.projects, checkActive)
        val id = UUID.randomUUID().toString()
        val projects = result.projects.map { project -> SingleVaultDedupUiProject(project.key, project.title,
            contentCount = project.contentCount,
            issues = result.blockers[project.key].orEmpty().map { SingleVaultDedupUiIssue.valueOf(it.name) }.toSet()) }
        val counts = result.projects.associate { it.key to it.contentCount }
        val ui = SingleVaultDedupUiAnalysis(id, sourceKey, projects, result.candidates.map {
            SingleVaultDedupUiCandidate(it.candidateKey, it.keeperKey, it.matchedContentCount,
                ((counts[it.keeperKey] ?: 0) - it.matchedContentCount).coerceAtLeast(0))
        }, result.comparisonLimitReached, source = adapter.source)
        synchronized(publicationLock) {
            checkActive()
            plan = Plan(id, version, lock, adapter, capture, result)
        }
        ui
    }

    override suspend fun clean(planId: String, selectedProjectIds: Set<String>): SingleVaultDedupUiOutcome = mutex.withLock {
        val checkActive = guard()
        val reviewed = plan
        plan = null // single use, including failures and uncertain commits
        if (reviewed == null || reviewed.id != planId || reviewed.generation != generation.get() ||
            reviewed.lock != SessionManager.lockGeneration.value || !reviewed.adapter.source.canClean ||
            selectedProjectIds.isEmpty()) return@withLock SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        val selected = reviewed.analysis.candidates.filter { it.candidateKey in selectedProjectIds }
        if (selected.size != selectedProjectIds.size || selected.any { it.keeperKey in selectedProjectIds })
            return@withLock SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SOURCE_CHANGED)
        try {
            checkActive()
            reviewed.adapter.clean(reviewed.capture, selected, checkActive)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            // A provider may have committed before losing its response. Recovery receipts decide the next step.
            SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED)
        }
    }

    override suspend fun recoveryBatches(): List<SingleVaultDedupUiRecoveryBatch> = mutex.withLock {
        val checkActive = guard()
        val available = adapters.ifEmpty { discoverAdapters(checkActive) }
        database.singleVaultDedupRecoveryDao().pending().map { row ->
            val source = available.singleOrNull { it.source.key == row.sourceKey }?.source
            SingleVaultDedupUiRecoveryBatch(row.id, source?.label.orEmpty(), row.createdAt, row.projectCount,
                canRestore = source?.canClean == true,
                issue = if (source?.canClean == true) null else source?.issue ?: SingleVaultDedupUiIssue.SOURCE_UNAVAILABLE,
                missingAsCopy = row.sourceKey.startsWith("keepass:"))
        }.also { checkActive() }
    }

    override suspend fun restore(batchId: String): SingleVaultDedupUiOutcome = mutex.withLock {
        val checkActive = guard()
        plan = null
        val row = database.singleVaultDedupRecoveryDao().get(batchId)
            ?: return@withLock SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
        if (row.state == "RESTORED") return@withLock SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
        val adapter = adapters.ifEmpty { discoverAdapters(checkActive) }.singleOrNull { it.source.key == row.sourceKey && it.source.canClean }
            ?: return@withLock SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED)
        try { adapter.restore(row, checkActive).copy(isRestoration = true)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) { SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED) }
    }
}
