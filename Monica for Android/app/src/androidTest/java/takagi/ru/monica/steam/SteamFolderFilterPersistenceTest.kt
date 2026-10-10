package takagi.ru.monica.steam

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.data.*
import takagi.ru.monica.steam.ui.*

class SteamFolderFilterPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun folderPreferencesAreIsolatedByVaultAndSurviveReopening() {
        val sources = listOf(SteamStorageSource.Local,SteamStorageSource.Mdbx(987001),SteamStorageSource.Mdbx(987002),
            SteamStorageSource.KeePass(987001),SteamStorageSource.Bitwarden(987001))
        val previous = sources.associateWith { readSteamFolderId(context,it) }
        try {
            sources.forEachIndexed { i,source -> saveSteamFolderId(context,source,"folder/$i:游戏") }
            sources.forEachIndexed { i,source -> assertEquals("folder/$i:游戏",readSteamFolderId(context,source)) }
            saveSteamFolderId(context,sources[1],null)
            assertNull(readSteamFolderId(context,sources[1]))
            assertEquals("folder/2:游戏",readSteamFolderId(context,sources[2]))
        } finally { previous.forEach { (source,folder) -> saveSteamFolderId(context,source,folder) } }
    }

    @Test fun viewModelRestoresFolderAndLiveLocalMovesUpdateVisibleAccounts() = runBlocking {
        val source = readSteamStorageSource(context)
        val folder = readSteamFolderId(context,SteamStorageSource.Local)
        val db = Room.inMemoryDatabaseBuilder(context,SteamDatabase::class.java).build()
        val repository = SteamAccountRepository(db.steamAccountDao(),SecurityManager(context))
        var model: SteamViewModel? = null
        try {
            saveSteamStorageSource(context,SteamStorageSource.Local)
            saveSteamFolderId(context,SteamStorageSource.Local,"7")
            val fixture = SteamAccount(0,"76561198000000000","fixture","Fixture","android:fixture",
                "c2VjcmV0",null,null,null,null,null,null,"{}",false,0,1,1)
            val first = repository.insertCopy(fixture,7)
            val second = repository.insertCopy(fixture,8)
            repeat(2) {
                val current = withContext(Dispatchers.Main) { SteamViewModel(context,repository) }
                model = current
                val state = withTimeout(10000) { current.uiState.first { it.accounts.size==2 } }
                assertEquals("7",state.folderId)
                assertEquals(listOf(first),filterSteamAccountsByFolder(state.accounts,SteamFolderFilter(state.storageSource,state.folderId)).map { it.id })
                if (it==1) {
                    withContext(Dispatchers.Main) { current.selectFolderFilter(SteamFolderFilter(SteamStorageSource.Local,"8")) }
                    assertEquals(listOf(second),filterSteamAccountsByFolder(current.uiState.value.accounts,SteamFolderFilter(SteamStorageSource.Local,"8")).map { it.id })
                    repository.moveToCategory(first,8)
                    val moved = withTimeout(10000) { current.uiState.first { state -> state.accounts.all { account -> account.categoryId==8L } } }
                    assertEquals(2,filterSteamAccountsByFolder(moved.accounts,SteamFolderFilter(moved.storageSource,moved.folderId)).size)
                }
                current.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                model = null
            }
        } finally {
            model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            db.close()
            saveSteamStorageSource(context,source)
            saveSteamFolderId(context,SteamStorageSource.Local,folder)
        }
    }
}
