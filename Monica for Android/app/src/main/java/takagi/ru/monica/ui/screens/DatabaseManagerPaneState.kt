package takagi.ru.monica.ui.screens

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.repository.DatabaseManagerLocation
import takagi.ru.monica.repository.DatabaseManagerSnapshot
import takagi.ru.monica.repository.databaseManagerSelectionRange
import takagi.ru.monica.repository.resolveLocalFolderId

@Stable
internal class DatabaseManagerPane(initial: String? = null) {
    var databaseKey by mutableStateOf(initial)
    var folderId by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var search by mutableStateOf(false)
    var descending by mutableStateOf(false)
    var selected by mutableStateOf(emptySet<String>())
    var visibleIds by mutableStateOf(emptyList<String>())
    var selectionMode by mutableStateOf(false)
    var selectionAnchor by mutableStateOf<String?>(null)
    var snapshot by mutableStateOf<DatabaseManagerSnapshot?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    private var backStack by mutableStateOf(emptyList<List<String>>())
    private var forwardStack by mutableStateOf(emptyList<List<String>>())
    val canGoBack get() = backStack.isNotEmpty()
    val canGoForward get() = forwardStack.isNotEmpty()
    val isSelecting get() = selectionMode || selected.isNotEmpty()
    val location get() = databaseKey?.let { DatabaseManagerLocation(ImportDestination.fromKey(it), folderId) }
    val isReady get() = location != null && !loading && error == null && snapshot?.location?.database == location?.database
    private fun route() = listOf(databaseKey.orEmpty(), folderId.orEmpty())

    fun installSnapshot(value: DatabaseManagerSnapshot) {
        snapshot = value
        if (value.location.database.key == databaseKey) folderId = value.resolveLocalFolderId(folderId)
    }

    fun clearSelection() { selected = emptySet(); selectionAnchor = null; selectionMode = false }
    fun toggleSelection(id: String) {
        selectionMode = true
        selected = if (id in selected) selected - id else selected + id
        selectionAnchor = id
    }
    fun swipeSelect(id: String, orderedIds: List<String>) {
        val target = orderedIds.indexOf(id)
        if (target < 0) return
        val start = orderedIds.indexOf(selectionAnchor)
        selectionMode = true
        if (start < 0) { selected = setOf(id); selectionAnchor = id }
        else selected = databaseManagerSelectionRange(emptySet(), orderedIds, start, target, true)
    }
    fun selectAll(ids: List<String>) {
        selectionMode = true
        selected = if (selected.containsAll(ids)) emptySet() else ids.toSet()
        selectionAnchor = null
    }
    fun navigate(key: String?, folder: String? = null) {
        if (key != databaseKey || folder != folderId) {
            backStack = (backStack + listOf(route())).takeLast(100)
            forwardStack = emptyList()
        }
        showRoute(listOf(key.orEmpty(), folder.orEmpty()))
    }
    private fun showRoute(route: List<String>) {
        databaseKey = route[0].ifBlank { null }; folderId = route[1].ifBlank { null }
        snapshot?.takeIf { it.location.database.key == databaseKey }?.let { folderId = it.resolveLocalFolderId(folderId) }
        query = ""; clearSelection(); error = null
    }
    fun goBack() {
        if (!canGoBack) return
        forwardStack = forwardStack + listOf(route())
        val target = backStack.last(); backStack = backStack.dropLast(1); showRoute(target)
    }
    fun goForward() {
        if (!canGoForward) return
        backStack = backStack + listOf(route())
        val target = forwardStack.last(); forwardStack = forwardStack.dropLast(1); showRoute(target)
    }
    companion object {
        val Saver = listSaver<DatabaseManagerPane, Any>(save = {
            listOf(it.databaseKey.orEmpty(), it.folderId.orEmpty(), it.query, it.search, it.descending,
                it.selected.toList(), it.selectionMode, it.selectionAnchor.orEmpty(), it.backStack, it.forwardStack)
        }, restore = {
            DatabaseManagerPane((it[0] as String).ifBlank { null }).apply {
                folderId = (it[1] as String).ifBlank { null }; query = it[2] as String; search = it[3] as Boolean
                descending = it[4] as Boolean; selected = (it[5] as List<*>).filterIsInstance<String>().toSet()
                selectionMode = it.getOrNull(6) as? Boolean ?: selected.isNotEmpty()
                selectionAnchor = (it.getOrNull(7) as? String)?.ifBlank { null }
                fun stack(index: Int) = (it.getOrNull(index) as? List<*>)?.mapNotNull { route ->
                    (route as? List<*>)?.filterIsInstance<String>()?.takeIf { parts -> parts.size == 2 }
                }.orEmpty()
                backStack = stack(8); forwardStack = stack(9)
            }
        })
    }
}
