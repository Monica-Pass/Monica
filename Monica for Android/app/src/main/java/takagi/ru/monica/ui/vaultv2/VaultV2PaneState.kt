package takagi.ru.monica.ui.vaultv2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

internal data class VaultV2FolderNavigationEntry(
    val storageFilterType: String,
    val storageFilterPrimaryId: Long?,
    val storageFilterSecondaryKey: String?,
    val storageFilterIdentityKey: String?,
    val scrollIndex: Int,
    val scrollOffset: Int,
)

@Stable
class VaultV2PaneState internal constructor(
    scrollIndex: Int,
    scrollOffset: Int,
    fastScrollRequestKey: Int,
    fastScrollProgress: Float,
    scrollToTopRequestKey: Int,
    storageFilterType: String,
    storageFilterPrimaryId: Long?,
    storageFilterSecondaryKey: String?,
    storageFilterIdentityKey: String? = null,
    hasInitializedStorageFilter: Boolean,
    selectionCount: Int,
    isArchiveView: Boolean,
    archiveReturnStorageFilterType: String?,
    archiveReturnStorageFilterPrimaryId: Long?,
    archiveReturnStorageFilterSecondaryKey: String?,
    archiveReturnStorageFilterIdentityKey: String? = null,
    retainedState: VaultV2RetainedState = VaultV2RetainedState(),
) {
    internal val computedListSnapshots: VaultV2RetainedSourceSnapshotStore<
        VaultV2ComputedSnapshotKey,
        VaultV2ComputedSources,
        VaultV2ComputedListState
    > = retainedState.computedListSnapshots
    internal val visibleListSnapshots = retainedState.visibleListSnapshots
    private val retainedListState = retainedState
    private val folderNavigationHistory = retainedState.folderNavigationHistory

    val hasFolderNavigationHistory: Boolean
        get() = folderNavigationHistory.isNotEmpty()

    // Persisted navigation metadata, not screen UI state. Updating it on every
    // scroll frame must not invalidate the whole vault composable.
    var scrollIndex: Int = scrollIndex
        private set

    var scrollOffset: Int = scrollOffset
        private set

    var fastScrollRequestKey by mutableIntStateOf(fastScrollRequestKey)
        private set

    var fastScrollProgress by mutableFloatStateOf(fastScrollProgress.coerceIn(0f, 1f))
        private set

    var scrollToTopRequestKey by mutableIntStateOf(scrollToTopRequestKey)
        private set

    var showBackToTop by mutableStateOf(false)

    var fastScrollIndicatorLabel by mutableStateOf<String?>(null)

    var isFastScrollbarInteracting by mutableStateOf(false)
        private set

    private var createFolderDialogRequested by mutableStateOf(false)

    val hasPendingCreateFolderDialogRequest: Boolean
        get() = createFolderDialogRequested

    var storageFilterType by mutableStateOf(storageFilterType)
        private set

    var storageFilterPrimaryId by mutableStateOf(storageFilterPrimaryId)
        private set

    var storageFilterSecondaryKey by mutableStateOf(storageFilterSecondaryKey)
        private set

    var storageFilterIdentityKey by mutableStateOf(storageFilterIdentityKey)
        private set

    var hasInitializedStorageFilter by mutableStateOf(hasInitializedStorageFilter)
        private set

    var selectionCount by mutableIntStateOf(selectionCount)
        private set

    var isArchiveView by mutableStateOf(isArchiveView)
        private set

    internal var archiveReturnStorageFilterType by mutableStateOf(archiveReturnStorageFilterType)

    internal var archiveReturnStorageFilterPrimaryId by mutableStateOf(archiveReturnStorageFilterPrimaryId)

    internal var archiveReturnStorageFilterSecondaryKey by mutableStateOf(archiveReturnStorageFilterSecondaryKey)

    internal var archiveReturnStorageFilterIdentityKey by mutableStateOf(archiveReturnStorageFilterIdentityKey)

    internal var overviewListOpen by mutableStateOf(false)
    internal var overviewFolderBrowsing by mutableStateOf(false)
    internal var overviewScope by mutableStateOf<String?>(null)
    internal var overviewItemType by mutableStateOf<String?>(null)
    internal var overviewFavorites by mutableStateOf(false)
    internal var overviewScrollIndex: Int = 0
    internal var overviewScrollOffset: Int = 0
    internal var overviewCardKey by mutableStateOf<String?>(null)
    internal val overviewCardStack get() = retainedListState.overviewCardStack
    internal var overviewSnapshot: VaultOverviewSnapshot?
        get() = retainedListState.overviewSnapshot
        set(value) { retainedListState.overviewSnapshot = value }

    fun updateScrollPosition(index: Int, offset: Int) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = offset.coerceAtLeast(0)
        if (scrollIndex != safeIndex) {
            scrollIndex = safeIndex
        }
        if (scrollOffset != safeOffset) {
            scrollOffset = safeOffset
        }
    }

    fun requestFastScroll(progress: Float) {
        fastScrollProgress = progress.coerceIn(0f, 1f)
        fastScrollRequestKey += 1
    }

    fun updateFastScrollProgress(progress: Float) {
        fastScrollProgress = progress.coerceIn(0f, 1f)
    }

    fun updateFastScrollbarInteraction(interacting: Boolean) {
        isFastScrollbarInteracting = interacting
    }

    fun requestScrollToTop() {
        scrollToTopRequestKey += 1
    }

    fun requestCreateFolderDialog() {
        createFolderDialogRequested = true
    }

    fun consumeCreateFolderDialogRequest(): Boolean {
        if (!createFolderDialogRequested) return false
        createFolderDialogRequested = false
        return true
    }

    fun updateStorageFilter(
        type: String,
        primaryId: Long? = null,
        secondaryKey: String? = null,
        identityKey: String? = null,
    ) {
        storageFilterType = type
        storageFilterPrimaryId = primaryId
        storageFilterSecondaryKey = secondaryKey
        storageFilterIdentityKey = identityKey
        hasInitializedStorageFilter = true
    }

    internal fun pushFolderNavigationPosition(index: Int, offset: Int) {
        folderNavigationHistory += VaultV2FolderNavigationEntry(
            storageFilterType = storageFilterType,
            storageFilterPrimaryId = storageFilterPrimaryId,
            storageFilterSecondaryKey = storageFilterSecondaryKey,
            storageFilterIdentityKey = storageFilterIdentityKey,
            scrollIndex = index.coerceAtLeast(0),
            scrollOffset = offset.coerceAtLeast(0),
        )
        while (folderNavigationHistory.size > 32) {
            folderNavigationHistory.removeAt(0)
        }
    }

    internal fun popFolderNavigationPosition(): VaultV2FolderNavigationEntry? {
        if (folderNavigationHistory.isEmpty()) return null
        return folderNavigationHistory.removeAt(folderNavigationHistory.lastIndex)
    }

    internal fun clearFolderNavigationHistory() {
        folderNavigationHistory.clear()
    }

    fun ensureAggregateDefaultStorageFilter() {
        if (hasInitializedStorageFilter) return
        hasInitializedStorageFilter = true
        if (
            storageFilterType == VAULT_V2_STORAGE_FILTER_LOCAL &&
            storageFilterPrimaryId == null &&
            storageFilterSecondaryKey == null
        ) {
            storageFilterType = VAULT_V2_STORAGE_FILTER_ALL
        }
    }

    fun clearTransientUi() {
        showBackToTop = false
        fastScrollIndicatorLabel = null
        isFastScrollbarInteracting = false
    }

    fun clearRetainedListSnapshots() {
        retainedListState.clear()
    }

    internal fun seedManualStackMetadata(
        revisions: List<VaultV2PasswordRevision>,
    ): VaultV2ManualStackMetadata? = retainedListState.seedManualStackMetadata(revisions)

    internal fun updateManualStackMetadata(metadata: VaultV2ManualStackMetadata) {
        retainedListState.updateManualStackMetadata(metadata)
    }

    fun updateSelectionCount(count: Int) {
        selectionCount = count.coerceAtLeast(0)
    }

    fun openArchiveView() {
        if (!isArchiveView) {
            archiveReturnStorageFilterType = storageFilterType
            archiveReturnStorageFilterPrimaryId = storageFilterPrimaryId
            archiveReturnStorageFilterSecondaryKey = storageFilterSecondaryKey
            archiveReturnStorageFilterIdentityKey = storageFilterIdentityKey
        }
        isArchiveView = true
        requestScrollToTop()
    }

    fun closeArchiveView(scrollToTop: Boolean = true) {
        archiveReturnStorageFilterType?.let { returnType ->
            updateStorageFilter(
                type = returnType,
                primaryId = archiveReturnStorageFilterPrimaryId,
                secondaryKey = archiveReturnStorageFilterSecondaryKey,
                identityKey = archiveReturnStorageFilterIdentityKey,
            )
        }
        archiveReturnStorageFilterType = null
        archiveReturnStorageFilterPrimaryId = null
        archiveReturnStorageFilterSecondaryKey = null
        archiveReturnStorageFilterIdentityKey = null
        isArchiveView = false
        if (scrollToTop) requestScrollToTop()
    }

}

internal fun vaultV2PaneStateSaver(
    retainedState: VaultV2RetainedState,
): Saver<VaultV2PaneState, Any> = listSaver(
    save = {
        listOf(
            it.scrollIndex,
            it.scrollOffset,
            it.fastScrollRequestKey,
            it.fastScrollProgress,
            it.scrollToTopRequestKey,
            it.storageFilterType,
            it.storageFilterPrimaryId,
            it.storageFilterSecondaryKey,
            it.hasInitializedStorageFilter,
            it.selectionCount,
            it.isArchiveView,
            it.archiveReturnStorageFilterType,
            it.archiveReturnStorageFilterPrimaryId,
            it.archiveReturnStorageFilterSecondaryKey,
            it.storageFilterIdentityKey,
            it.archiveReturnStorageFilterIdentityKey,
            it.overviewListOpen,
            it.overviewScope,
            it.overviewItemType,
            it.overviewFavorites,
            it.overviewScrollIndex,
            it.overviewScrollOffset,
            it.overviewCardKey,
            it.overviewFolderBrowsing,
        )
    },
    restore = { restored ->
        VaultV2PaneState(
            scrollIndex = restored[0] as Int,
            scrollOffset = restored[1] as Int,
            fastScrollRequestKey = restored[2] as Int,
            fastScrollProgress = restored[3] as Float,
            scrollToTopRequestKey = restored[4] as Int,
            storageFilterType = restored[5] as String,
            storageFilterPrimaryId = restored[6] as Long?,
            storageFilterSecondaryKey = restored[7] as String?,
            hasInitializedStorageFilter = restored.getOrNull(8) as? Boolean ?: false,
            selectionCount = restored.getOrNull(9) as? Int ?: 0,
            isArchiveView = restored.getOrNull(10) as? Boolean ?: false,
            archiveReturnStorageFilterType = restored.getOrNull(11) as? String,
            archiveReturnStorageFilterPrimaryId = restored.getOrNull(12) as? Long,
            archiveReturnStorageFilterSecondaryKey = restored.getOrNull(13) as? String,
            storageFilterIdentityKey = restored.getOrNull(14) as? String,
            archiveReturnStorageFilterIdentityKey = restored.getOrNull(15) as? String,
            retainedState = retainedState,
        ).apply {
            overviewListOpen = restored.getOrNull(16) as? Boolean ?: false
            overviewScope = restored.getOrNull(17) as? String
            overviewItemType = restored.getOrNull(18) as? String
            overviewFavorites = restored.getOrNull(19) as? Boolean ?: false
            overviewScrollIndex = restored.getOrNull(20) as? Int ?: 0
            overviewScrollOffset = restored.getOrNull(21) as? Int ?: 0
            overviewCardKey = restored.getOrNull(22) as? String
            overviewFolderBrowsing = restored.getOrNull(23) as? Boolean ?: false
        }
    },
)

@Composable
internal fun rememberVaultV2PaneState(
    retainedState: VaultV2RetainedState,
): VaultV2PaneState {
    val saver = remember(retainedState) { vaultV2PaneStateSaver(retainedState) }
    return rememberSaveable(saver = saver) {
        VaultV2PaneState(
            scrollIndex = 0,
            scrollOffset = 0,
            fastScrollRequestKey = 0,
            fastScrollProgress = 0f,
            scrollToTopRequestKey = 0,
            storageFilterType = VAULT_V2_STORAGE_FILTER_ALL,
            storageFilterPrimaryId = null,
            storageFilterSecondaryKey = null,
            storageFilterIdentityKey = null,
            hasInitializedStorageFilter = false,
            selectionCount = 0,
            isArchiveView = false,
            archiveReturnStorageFilterType = null,
            archiveReturnStorageFilterPrimaryId = null,
            archiveReturnStorageFilterSecondaryKey = null,
            archiveReturnStorageFilterIdentityKey = null,
            retainedState = retainedState,
        )
    }
}

const val VAULT_V2_STORAGE_FILTER_ALL = "all"
const val VAULT_V2_STORAGE_FILTER_LOCAL = "local"
const val VAULT_V2_STORAGE_FILTER_STARRED = "starred"
const val VAULT_V2_STORAGE_FILTER_UNCATEGORIZED = "uncategorized"
const val VAULT_V2_STORAGE_FILTER_LOCAL_STARRED = "local_starred"
const val VAULT_V2_STORAGE_FILTER_LOCAL_UNCATEGORIZED = "local_uncategorized"
const val VAULT_V2_STORAGE_FILTER_CUSTOM = "custom"
const val VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE = "keepass_database"
const val VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP = "keepass_group"
const val VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_STARRED = "keepass_database_starred"
const val VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_UNCATEGORIZED = "keepass_database_uncategorized"
const val VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT = "bitwarden_vault"
const val VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER = "bitwarden_folder"
const val VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_STARRED = "bitwarden_vault_starred"
const val VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_UNCATEGORIZED = "bitwarden_vault_uncategorized"
const val VAULT_V2_STORAGE_FILTER_MDBX_DATABASE = "mdbx_database"
const val VAULT_V2_STORAGE_FILTER_MDBX_FOLDER = "mdbx_folder"
