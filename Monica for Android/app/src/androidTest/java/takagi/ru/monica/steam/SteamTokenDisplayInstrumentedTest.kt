package takagi.ru.monica.steam

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.steam.core.SteamTotp
import takagi.ru.monica.steam.data.*
import takagi.ru.monica.steam.ui.*
import takagi.ru.monica.ui.components.TotpCodeCard
import java.io.File
import java.util.UUID

class SteamTokenDisplayInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "/wECAwQFBgcICQoLDA0ODxAREhM="

    @Test fun actualImportEncryptedReloadAndCardCopyMatchFixedSteamVectors() = runBlocking {
        assertTrue(withContext(Dispatchers.Default) { SecureStorageStartup.prepareForEntry(context, 15000) } is SecureStartupResult.Ready)
        val previousSource = readSteamStorageSource(context)
        val previousFolder = readSteamFolderId(context, SteamStorageSource.Local)
        val db = Room.inMemoryDatabaseBuilder(context, SteamDatabase::class.java).build()
        val repository = SteamAccountRepository(db.steamAccountDao(), SecurityManager(context))
        val file = File(context.cacheDir, "synthetic-token-${UUID.randomUUID()}.maFile")
        val content = """{"shared_secret":"$key","identity_secret":"YWJjZGVmZ2hpamtsbW5vcHFyc3Q=",
          "uri":"otpauth://totp/Steam:synthetic?secret=74AQEAYEAUDAOCAJBIFQYDIOB4IBCEQT&issuer=Steam",
          "account_name":"Synthetic slash key","server_time":1600000000,"Session":null,"fully_enrolled":false}"""
        file.writeText(content)
        var model: SteamViewModel? = null
        try {
            saveSteamStorageSource(context, SteamStorageSource.Local)
            saveSteamFolderId(context, SteamStorageSource.Local, null)
            val current = withContext(Dispatchers.Main) { SteamViewModel(context, repository) }.also { model = it }
            withContext(Dispatchers.Main) { current.importMaFile(Uri.fromFile(file), null, "", "", "", allowMissingSteamId = true) }
            val state = withTimeout(10000) { current.uiState.first { it.accounts.size == 1 && !it.loading } }
            val account = state.accounts.single()
            assertEquals(key, account.sharedSecret)
            assertFalse(account.canUseConfirmations)
            assertFalse(account.canApproveLogins)
            val reloaded = requireNotNull(repository.getAccount(account.id))
            assertEquals(key, reloaded.sharedSecret)
            assertEquals(content, file.readText())
            val screen = Class.forName("takagi.ru.monica.steam.ui.SteamScreenKt")
            fun <T> adapter(name: String): T {
                @Suppress("UNCHECKED_CAST")
                return screen.getDeclaredMethod(name, SteamAccount::class.java).apply { isAccessible = true }.invoke(null, reloaded) as T
            }
            val data = adapter<TotpData>("toSteamTotpUiData")
            val item = adapter<SecureItem>("toSteamTotpUiItem")
            val seconds = mutableLongStateOf(1_700_000_000L)
            var copied: String? = null
            var directCopied: String? = null
            compose.setContent { MaterialTheme { Column {
                TotpCodeCard(item = item, parsedTotpData = data, sharedTickSeconds = seconds.longValue,
                    appSettings = AppSettings(copyNextCodeWhenExpiring = false, authenticatorCardHideCodeByDefault = false),
                    onCopyCode = { copied = it }, onCardClick = {},
                    onLongClick = { directCopied = SteamTotp.generateAuthCode(reloaded.sharedSecret, seconds.longValue) },
                    modifier = Modifier.testTag("synthetic_steam_card"))
                TotpCodeCard(item = item.copy(id = item.id + 1), parsedTotpData = data, sharedTickSeconds = seconds.longValue,
                    appSettings = AppSettings(copyNextCodeWhenExpiring = false, authenticatorCardHideCodeByDefault = false),
                    onCopyCode = { copied = it }, modifier = Modifier.testTag("synthetic_steam_detail_card"))
            } } }
            compose.onAllNodesWithText("PM RRM").assertCountEquals(2)
            compose.onNodeWithTag("synthetic_steam_detail_card").performClick()
            compose.runOnIdle { assertEquals("PMRRM", copied) }
            compose.onNodeWithTag("synthetic_steam_card").performTouchInput { longClick() }
            compose.runOnIdle { assertEquals("PMRRM", directCopied) }
            File(context.cacheDir, "steam-token-display.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.runOnIdle { seconds.longValue = 1_700_000_030L }
            compose.onAllNodesWithText("43 3DY").assertCountEquals(2)
            compose.onNodeWithTag("synthetic_steam_detail_card").performClick()
            compose.runOnIdle { assertEquals("433DY", copied) }
            assertEquals(content, file.readText())
        } finally {
            model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            db.close(); file.delete()
            saveSteamStorageSource(context, previousSource)
            saveSteamFolderId(context, SteamStorageSource.Local, previousFolder)
        }
    }
}
