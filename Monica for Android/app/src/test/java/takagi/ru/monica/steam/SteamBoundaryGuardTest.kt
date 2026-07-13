package takagi.ru.monica.steam

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.BottomNavVisibility

class SteamBoundaryGuardTest {
    @Test
    fun standardReleaseBuildDisablesTheBrokenLintVitalGate() {
        val buildScript = projectFile("app/build.gradle").readText()

        assertTrue(buildScript.contains("checkReleaseBuilds false"))
        assertTrue(buildScript.contains("checkDependencies false"))
    }

    @Test
    fun authorizedDeviceRemovalDoesNotRetainTheRejectedSteamCmRuntime() {
        val rules = projectFile("app/proguard-rules.pro").readText()
        val buildScript = projectFile("app/build.gradle").readText()

        assertFalse(rules.contains("in.dragonbra.javasteam"))
        assertFalse(rules.contains("io.ktor.client.engine.cio"))
        assertFalse(buildScript.contains("in.dragonbra:javasteam"))
        assertFalse(buildScript.contains("com.google.protobuf:protobuf-java"))
    }

    @Test
    fun steamDockIsPresentButHiddenByDefault() {
        val oldVisibleOrder = listOf(
            BottomNavContentTab.VAULT_V2,
            BottomNavContentTab.PASSWORDS,
            BottomNavContentTab.AUTHENTICATOR,
            BottomNavContentTab.CARD_WALLET,
            BottomNavContentTab.PASSKEY,
            BottomNavContentTab.NOTES,
            BottomNavContentTab.SEND
        )

        assertEquals(oldVisibleOrder, BottomNavContentTab.DEFAULT_ORDER.take(oldVisibleOrder.size))
        assertEquals(BottomNavContentTab.STEAM, BottomNavContentTab.DEFAULT_ORDER.last())
        assertFalse(BottomNavVisibility().isVisible(BottomNavContentTab.STEAM))
        assertEquals(5, BottomNavVisibility().visibleCount())
    }

    @Test
    fun steamDoesNotChangeMainPasswordDatabaseSchema() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt").readText()

        assertTrue(source.contains("version = 72"))
        assertFalse(source.contains("SteamAccountEntity::class"))
        assertFalse(source.contains("abstract fun steamAccountDao"))
    }

    @Test
    fun steamRepositoryDoesNotDependOnSecureItems() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamAccountRepository.kt"
        ).readText()

        assertFalse(source.contains("SecureItemRepository"))
        assertFalse(source.contains("SecureItemDao"))
        assertFalse(source.contains("ItemType.TOTP"))
        assertTrue(source.contains("SecurityManager"))
    }

    @Test
    fun steamLocalStorageEncryptsAccountFieldsAndMigratesExistingRows() {
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamAccountRepository.kt"
        ).readText()
        val daoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamAccountDao.kt"
        ).readText()
        val databaseSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamDatabase.kt"
        ).readText()

        assertTrue(repositorySource.contains("steamId = encrypt(payload.steamId)"))
        assertTrue(repositorySource.contains("accountName = encrypt(payload.accountName)"))
        assertTrue(repositorySource.contains("displayName = encrypt(payload.displayName)"))
        assertTrue(repositorySource.contains("deviceId = encrypt(payload.deviceId)"))
        assertTrue(repositorySource.contains("sharedSecret = encrypt(payload.sharedSecret)"))
        assertTrue(repositorySource.contains("rawSteamGuardJson = encrypt(payload.rawJson)"))
        assertTrue(repositorySource.contains("displayName = encrypt(displayName.trim().ifBlank { existingPlain.accountName })"))
        assertTrue(repositorySource.contains("sortOrder = existing?.sortOrder ?: dao.nextSortOrder()"))

        assertTrue(repositorySource.contains("steamId = decrypt(entity.steamId).orEmpty()"))
        assertTrue(repositorySource.contains("accountName = decrypt(entity.accountName).orEmpty()"))
        assertTrue(repositorySource.contains("displayName = decrypt(entity.displayName).orEmpty()"))
        assertTrue(repositorySource.contains("deviceId = decrypt(entity.deviceId).orEmpty()"))
        assertTrue(repositorySource.contains("sortOrder = entity.sortOrder"))
        assertTrue(repositorySource.contains("findExistingBySteamId"))
        assertFalse(daoSource.contains("getBySteamId"))
        assertFalse(daoSource.contains("ORDER BY selected DESC, updatedAt DESC"))
        assertTrue(daoSource.contains("ORDER BY sortOrder ASC, id ASC"))
        assertTrue(daoSource.contains("updateSortOrders(items: List<Pair<Long, Int>>)"))

        assertTrue(databaseSource.contains("version = 3"))
        assertTrue(databaseSource.contains(".addMigrations(migration1To2(context.applicationContext))"))
        assertTrue(databaseSource.contains(".addMigrations(migration2To3())"))
        assertTrue(databaseSource.contains("encryptExistingSteamRows"))
        assertTrue(databaseSource.contains("ALTER TABLE steam_accounts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"))
        assertTrue(databaseSource.contains("SELECT id FROM steam_accounts ORDER BY selected DESC, updatedAt DESC"))
        assertTrue(databaseSource.contains("\"steam_id\""))
        assertTrue(databaseSource.contains("\"accountName\""))
        assertTrue(databaseSource.contains("\"displayName\""))
        assertTrue(databaseSource.contains("\"deviceId\""))
        assertTrue(databaseSource.contains("\"rawSteamGuardJson\""))
        assertTrue(databaseSource.contains("securityManager.encryptDataLegacyCompat(value)"))
    }

    @Test
    fun webDavBackupExportsSteamAccountsAsMaFiles() {
        val helperSource = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt").readText()
        val applierSource = projectFile("app/src/main/java/takagi/ru/monica/utils/BackupRestoreApplier.kt").readText()
        val codecSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/importer/SteamMaFileBackupCodec.kt"
        ).readText()

        assertTrue(helperSource.contains("STEAM_MAFILE_BACKUP_DIR = \"steam/mafiles\""))
        assertTrue(helperSource.contains("preferences.includeAuthenticators"))
        assertTrue(helperSource.contains("createSteamMaFileBackups(securityManager)"))
        assertTrue(helperSource.contains("SteamMaFileBackupCodec.encode(account)"))
        assertTrue(helperSource.contains("isSteamMaFileBackupEntry(normalizedEntryName)"))
        assertTrue(helperSource.contains("restoreSteamMaFilePayload(tempFile)"))
        assertTrue(helperSource.contains("steamMaFiles = steamMaFiles"))
        assertTrue(helperSource.contains("if (steamMaFiles.isNotEmpty())"))
        assertTrue(helperSource.contains("clearSteamAccounts = true"))
        assertFalse(helperSource.contains("getDatabasePath(\"steam_database\")"))

        assertTrue(applierSource.contains("SteamAccountRepository("))
        assertTrue(applierSource.contains("steamRepository.upsertFromMaFile(payload)"))
        assertTrue(applierSource.contains("steamAccountImported"))
        assertTrue(codecSource.contains("shared_secret"))
        assertTrue(codecSource.contains("identity_secret"))
        assertTrue(codecSource.contains("monica_display_name"))
        assertTrue(codecSource.contains("SteamLoginSecure"))
        assertTrue(codecSource.contains("AccessToken"))
        assertTrue(codecSource.contains("RefreshToken"))
    }

    @Test
    fun exportDataPageCanExportPlainSteamMaFiles() {
        val exportScreenSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/ExportDataScreen.kt")
            .readText()
        val exportModelsSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/ExportModels.kt")
            .readText()
        val exportNamingSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/ExportFileNaming.kt")
            .readText()
        val exportViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DataExportImportViewModel.kt"
        ).readText()
        val mainActivitySource = projectFile("app/src/main/java/takagi/ru/monica/MainActivity.kt").readText()
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()
        val zhStrings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(exportModelsSource.contains("STEAM_MAFILE"))
        assertTrue(exportNamingSource.contains("ExportOption.STEAM_MAFILE -> \"steam_mafiles_${'$'}{timestamp}.zip\""))

        assertTrue(exportScreenSource.contains("ExportOption.STEAM_MAFILE"))
        assertTrue(exportScreenSource.contains("onLoadSteamMaFileCandidates"))
        assertTrue(exportScreenSource.contains("onPrepareSteamMaFileExport"))
        assertTrue(exportScreenSource.contains("onWritePreparedSteamMaFileExport"))
        assertTrue(exportScreenSource.contains("SteamMaFileExportOptionsContent("))
        assertTrue(exportScreenSource.contains("showSteamMaFileRiskDialog"))
        assertTrue(exportScreenSource.contains("M3IdentityVerifyDialog("))
        assertTrue(exportScreenSource.contains("securityManager.verifyMasterPassword(steamMaFilePasswordInput)"))
        assertTrue(exportScreenSource.contains("biometricHelper.authenticate("))

        assertTrue(exportViewModelSource.contains("loadSteamMaFileExportCandidates"))
        assertTrue(exportViewModelSource.contains("prepareSteamMaFileExport"))
        assertTrue(exportViewModelSource.contains("writePreparedSteamMaFileExport"))
        assertTrue(exportViewModelSource.contains("SteamAccountRepository("))
        assertTrue(exportViewModelSource.contains("SteamMaFileBackupCodec.encode(account)"))
        assertTrue(exportViewModelSource.contains("ZipOutputStream(tempFile.outputStream())"))
        assertFalse(exportViewModelSource.contains("getDatabasePath(\"steam_database\")"))

        assertTrue(mainActivitySource.contains("onLoadSteamMaFileCandidates = {"))
        assertTrue(mainActivitySource.contains("onPrepareSteamMaFileExport = { accountIds ->"))
        assertTrue(mainActivitySource.contains("onWritePreparedSteamMaFileExport = { uri, preparedExport ->"))
        assertTrue(defaultStrings.contains("<string name=\"export_option_steam_mafile\">Steam maFile</string>"))
        assertTrue(zhStrings.contains("<string name=\"export_option_steam_mafile\">Steam maFile</string>"))
    }

    @Test
    fun maFileSteamIdCompletionIsASecondDialogOnlyAfterMissingSteamId() {
        val screenSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()

        val importDialogBlock = screenSource
            .substringAfter("private fun SteamMaFileImportDialog(")
            .substringBefore("private fun SteamMaFileSteamIdCompletionDialog(")
        val completionDialogBlock = screenSource
            .substringAfter("private fun SteamMaFileSteamIdCompletionDialog(")
            .substringBefore("private fun String.isValidSteamIdOrAccountId()")

        assertTrue(viewModelSource.contains("pendingMaFileSteamIdRequest"))
        assertTrue(viewModelSource.contains("error.message == \"maFile missing steamid\" && steamId.isBlank()"))
        assertTrue(screenSource.contains("uiState.pendingMaFileSteamIdRequest?.let { request ->"))
        assertFalse(importDialogBlock.contains("steam_mafile_steamid_label"))
        assertFalse(importDialogBlock.contains("steam_mafile_steamid_completion_desc"))
        assertTrue(completionDialogBlock.contains("steam_mafile_steamid_label"))
        assertTrue(completionDialogBlock.contains("steam_mafile_steamid_completion_desc"))
        assertTrue(completionDialogBlock.contains("isValidSteamIdOrAccountId()"))
    }

    @Test
    fun steamLoginImportLogsDoNotPersistRawAccountData() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/service/SteamLoginImportService.kt"
        ).readText()

        assertFalse(source.contains("payload=${'$'}beginPayload"))
        assertFalse(source.contains("phoneHint=${'$'}phoneHint"))
        assertFalse(source.contains("user=${'$'}userName"))
        assertFalse(source.contains("steamId=${'$'}steamId"))
        assertFalse(source.contains("Legacy RSA response invalid: ${'$'}response"))
        assertFalse(source.contains("message=${'$'}{responseMessage ?: \"\"}"))
    }

    @Test
    fun steamAuthorizedDeviceRemovalUsesCredentialAuthPollForOneDevice() {
        val screenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt"
        ).readText()
        val loginServiceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/service/SteamLoginImportService.kt"
        ).readText()

        assertTrue(screenSource.contains("steam_authorized_device_revoke_password_warning"))
        assertTrue(screenSource.contains("onRevokeAuthorizedDevice: (SteamAuthorizedDevice, String, String) -> Unit"))
        assertTrue(screenSource.contains("viewModel.revokeAuthorizedDevice("))
        assertTrue(screenSource.contains("value = revokeUserName"))
        assertTrue(screenSource.contains("onRevokeDevice(device, revokeUserName.trim(), revokePassword)"))
        assertTrue(screenSource.contains("showRevokePasswordPicker"))
        assertTrue(screenSource.contains("PasswordEntryPickerBottomSheet("))
        assertTrue(screenSource.contains("R.string.steam_current_device"))
        assertTrue(viewModelSource.contains("fun revokeAuthorizedDevice("))
        assertTrue(viewModelSource.contains("loginImportService.revokeAuthorizedDevice("))
        assertTrue(viewModelSource.contains("transport=auth_poll"))
        assertTrue(loginServiceSource.contains("writeFixed64(3, tokenToRevoke)"))
        assertTrue(loginServiceSource.contains("method = \"PollAuthSessionStatus\""))
        assertTrue(loginServiceSource.contains("method = \"RevokeToken\""))
        assertTrue(loginServiceSource.contains("throwApiErrors = true"))
        assertFalse(loginServiceSource.contains("method = \"RevokeRefreshToken\""))

        val revokeBlock = viewModelSource
            .substringAfter("fun revokeAuthorizedDevice(")
            .substringBefore("fun respondPendingLogin(")
        assertFalse(revokeBlock.contains("getOrDefault(false)"))
        assertFalse(revokeBlock.contains("steam_login_response_failed"))
    }

    @Test
    fun steamDiagnosticsAreAvailableFromBothLoginEntrypointsAndDeveloperExport() {
        val steamViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt"
        ).readText()
        val importViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DataExportImportViewModel.kt"
        ).readText()
        val developerSettingsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/DeveloperSettingsScreen.kt"
        ).readText()

        assertTrue(steamViewModelSource.contains("SteamDiagLogger.initialize(appContext.applicationContext)"))
        assertTrue(importViewModelSource.contains("SteamDiagLogger.initialize(context.applicationContext)"))
        assertTrue(developerSettingsSource.contains("SteamDiagLogger.initialize(context.applicationContext)"))
        assertTrue(developerSettingsSource.contains("SteamDiagLogger.exportPersistedLogs(2000)"))
        assertTrue(developerSettingsSource.contains("=== Steam Persisted Logs ==="))
        assertTrue(developerSettingsSource.contains("SteamDiagLogger.clear()"))
    }

    @Test
    fun steamPageDoesNotUseLegacyTotpImportWritePath() {
        val steamSources = listOf(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt",
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt",
            "app/src/main/java/takagi/ru/monica/steam/importer/SteamMaFileParser.kt"
        ).joinToString("\n") { projectFile(it).readText() }

        assertFalse(steamSources.contains("DataExportImportViewModel"))
        assertFalse(steamSources.contains("importSteamMaFile"))
        assertFalse(steamSources.contains("insertSteamGuardEntry"))
    }

    @Test
    fun steamMdbxSourceUsesMaFileEntriesWithoutLocalRoomImport() {
        val repositorySource = projectFile("app/src/main/java/takagi/ru/monica/repository/MdbxRepository.kt")
            .readText()
        val storeSource = projectFile("app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt")
            .readText()
        val steamMdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamMdbxAccountStore.kt"
        ).readText()
        val steamViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt"
        ).readText()
        val steamScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()

        assertTrue(repositorySource.contains("listSteamMaFileEntries"))
        assertTrue(repositorySource.contains("upsertSteamMaFileEntry"))
        assertTrue(repositorySource.contains("deleteSteamMaFileEntry"))
        assertTrue(storeSource.contains("STEAM_MAFILE_ENTRY_TYPE = \"steam-mafile\""))
        assertTrue(storeSource.contains(".put(\"mafile_json\", maFileJson)"))

        assertTrue(steamMdbxStoreSource.contains("repository.listSteamMaFileEntries(databaseId)"))
        assertTrue(steamMdbxStoreSource.contains("parser.parse("))
        assertTrue(steamMdbxStoreSource.contains("SteamMaFileBackupCodec.encode(account)"))
        assertTrue(steamMdbxStoreSource.contains("runtimeAccountId(databaseId, entry.entryId)"))

        assertTrue(steamViewModelSource.contains("SteamStorageSource.Mdbx"))
        assertTrue(steamViewModelSource.contains("saveMaFilePayload(payload)"))
        assertTrue(steamViewModelSource.contains("reloadMdbxAccounts(source"))
        assertTrue(steamViewModelSource.contains("fun transferAccounts("))
        assertTrue(steamViewModelSource.contains("fun updateDisplayName(accountId: Long, displayName: String)"))
        assertTrue(steamViewModelSource.contains("writeAccountsToStorageSource(accounts, targetSource)"))
        assertTrue(steamViewModelSource.contains("deleteAccountsFromStorageSource(source, accounts.map { it.id })"))
        assertTrue(
            steamViewModelSource.indexOf("writeAccountsToStorageSource(accounts, targetSource)") <
                steamViewModelSource.indexOf("deleteAccountsFromStorageSource(source, accounts.map { it.id })")
        )
        assertTrue(steamViewModelSource.contains("repository.upsertFromMaFile(account.toCompleteMaFilePayload())"))
        assertTrue(steamViewModelSource.contains("SteamMaFileBackupCodec.encode(this)"))
        assertTrue(steamViewModelSource.contains("existingBySteamId[account.steamId]?.entryId"))
        assertTrue(steamViewModelSource.contains("store.upsertAccount("))
        assertTrue(steamScreenSource.contains("SteamStorageSourceMenu("))
        assertTrue(steamScreenSource.contains("SteamMaFileTransferSheet("))
        assertTrue(steamScreenSource.contains("SteamMaFileTransferAction.MOVE"))
        assertTrue(steamScreenSource.contains("SteamMaFileTransferAction.COPY"))
        assertTrue(steamScreenSource.contains("SteamRemarkEditDialog("))
        assertTrue(steamScreenSource.contains("UnifiedCategoryFilterChipMenuDropdown("))
        assertTrue(steamScreenSource.contains("val mdbxDatabasesState by passwordDatabase.localMdbxDatabaseDao()"))
        assertTrue(steamScreenSource.contains(".collectAsState(initial = null)"))
        assertTrue(steamScreenSource.contains("val mdbxDatabasesLoaded = mdbxDatabasesState != null"))
        assertTrue(steamScreenSource.contains("LaunchedEffect(uiState.storageSource, mdbxDatabasesLoaded"))
        assertTrue(steamScreenSource.contains("mdbxDatabasesLoaded &&"))
        assertFalse(steamScreenSource.contains("UnifiedCategoryFilterChipMenu("))
    }

    @Test
    fun importDataPageDoesNotExposeLegacySteamGuardImportEntry() {
        val importOptionsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/ImportTypeOptions.kt"
        ).readText()

        assertFalse(importOptionsSource.contains("key = \"steam\""))
        assertFalse(importOptionsSource.contains("R.string.import_type_steam_title"))
        assertFalse(importOptionsSource.contains("Icons.Default.SportsEsports"))
    }

    @Test
    fun steamPageUsesMonicaTopBarAndLocalizedMenuInsteadOfWideTabs() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(source.contains("ExpressiveTopBar"))
        assertTrue(source.contains("PasswordTopActionsDropdownMenu"))
        assertFalse(source.contains("ScrollableTabRow"))
        assertFalse(source.contains("listOf(\"Code\", \"Confirm\", \"Login\", \"Import\")"))
        assertFalse(source.contains("Text(\""))
        assertFalse(source.contains("var searchQuery"))
        assertFalse(source.contains("var isSearchExpanded"))
        assertFalse(source.contains("SteamSection.IMPORT"))
        assertFalse(source.contains("SteamAccountSelector("))
        assertFalse(source.contains("SteamImportContent("))
        assertTrue(source.contains("if (selectedAccount == null)"))
        assertTrue(source.contains("BadgedBox"))
        assertTrue(source.contains("pendingConfirmationCount"))
        assertTrue(source.contains("CONFIRMATIONS(R.string.steam_section_confirmations, Icons.Default.VerifiedUser)"))
        assertTrue(source.contains("val targetSection = when (selectedSection)"))
        assertTrue(source.contains("SteamSection.CODE -> SteamSection.CONFIRMATIONS"))
        assertTrue(source.contains("SteamSection.CONFIRMATIONS -> SteamSection.CODE"))
        assertTrue(source.contains("imageVector = targetSection.icon"))
        assertTrue(source.contains("contentDescription = stringResource(targetSection.labelRes)"))
        assertTrue(source.contains("if (targetSection == SteamSection.CONFIRMATIONS && pendingConfirmationCount > 0)"))
        assertTrue(source.contains("SteamAddMethodDialog"))
        assertTrue(source.contains("SteamEmptyAccountContent"))
        assertTrue(source.contains("private fun SteamCodeContent(\n    accounts: List<SteamAccount>,"))
        assertTrue(source.contains("selectedTokenAccountIds"))
        assertFalse(source.contains("SteamTokenSelectionBar"))
        assertTrue(source.contains("SteamAccountDetailContent"))
        assertTrue(source.contains("SteamLoginApprovalSection"))
        assertTrue(source.contains("SteamAuthorizedDevicesSection"))
        assertTrue(source.contains("SteamAvatarImage"))
        assertTrue(source.contains("STEAM_AVATAR_CACHE_TTL_MS"))
        assertTrue(source.contains("steamAvatarCacheFile"))
        assertTrue(source.contains("readSteamAvatarCache"))
        assertTrue(source.contains("freshAvatar ?: cachedAvatar"))
        assertTrue(source.contains("floatingActionButton = {"))
        assertTrue(source.contains("AnimatedContent("))
        assertTrue(source.contains("targetState = detailAccount?.id"))
        assertTrue(source.contains("easyNotesScreenEnter().togetherWith(easyNotesScreenExit())"))
        assertTrue(source.contains("label = \"SteamTopBarNavigation\""))
        assertTrue(source.contains("label = \"SteamDetailNavigation\""))
        assertTrue(source.contains("if (detailAccount != null)"))
        assertTrue(source.contains("SteamDetailTopBar("))
        assertFalse(source.contains("detailAccount != null && showStandaloneSettingsEntry"))
        assertFalse(source.contains("collapsedTitleEndPadding = topBarTitleEndPadding"))
        assertTrue(source.contains("val tokenQrAccount = remember("))
        assertTrue(source.contains("selectedSection == SteamSection.CODE"))
        assertTrue(source.contains("selectedTokenAccountIds.isEmpty()"))
        assertTrue(source.contains("readLastSteamQrAccountId(context)"))
        assertTrue(source.contains("saveLastSteamQrAccountId(context, accountId)"))
        assertTrue(source.contains("AnimatedVisibility("))
        assertTrue(source.contains("FloatingActionButton("))
        assertTrue(source.contains("rememberLastSteamQrAccount(account.id)"))
        assertFalse(source.contains("SteamAccountDetailHeader"))
        assertFalse(source.contains("SteamAccountPasswordLoginCard"))
        assertFalse(source.contains("R.string.steam_password_not_saved"))
        assertFalse(source.contains("R.string.steam_password_login_section"))
        assertFalse(source.contains("var showAccountMenu"))
        assertFalse(source.contains("SteamAccountSwitchSheet"))
        assertFalse(source.contains("rememberModalBottomSheetState"))
        assertFalse(source.contains("Modifier.widthIn(max = 72.dp)"))
        assertFalse(source.contains("SteamAccountSwitchMenu"))
        assertFalse(source.contains("SteamSection.values().forEach"))
        assertFalse(source.contains("steam_more_options_with_confirmations"))
        assertFalse(source.contains("CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))"))
        assertTrue(source.contains(".align(Alignment.BottomCenter)"))
        assertTrue(source.contains(".padding(horizontal = 16.dp, vertical = 8.dp)"))

        val detailTopBar = source
            .substringAfter("private fun SteamDetailTopBar(")
            .substringBefore("@Composable\nprivate fun SteamTopActionsMenu(")
        assertTrue(detailTopBar.contains("TopAppBar("))
        assertTrue(detailTopBar.contains("IconButton(onClick = onNavigateBack)"))
        assertTrue(detailTopBar.contains("Icons.Default.ArrowBack"))
        assertTrue(detailTopBar.contains("onRemoveAuthenticator: (() -> Unit)? = null"))
        assertTrue(detailTopBar.contains("actions = {"))
        assertTrue(detailTopBar.contains("Icons.Default.Delete"))
        assertTrue(detailTopBar.contains("R.string.steam_remove_authenticator_action"))
        assertTrue(detailTopBar.contains("windowInsets = WindowInsets(0, 0, 0, 0)"))
        assertFalse(detailTopBar.contains("ExpressiveTopBar"))
        assertFalse(detailTopBar.contains("Icons.Default.MoreVert"))

        val topBarAccountAction = source
            .substringAfter("actions = {")
            .substringBefore("if (selectedAccount != null)")
        assertTrue(topBarAccountAction.contains("Icons.Default.Refresh"))
        assertTrue(topBarAccountAction.contains("viewModel.refreshConfirmations()"))
        assertTrue(topBarAccountAction.contains("showAddAccountDialog = true"))
        assertTrue(topBarAccountAction.contains("Icons.Default.Add"))
        assertTrue(topBarAccountAction.contains("R.string.steam_add_account_button"))
        assertTrue(topBarAccountAction.indexOf("Icons.Default.Refresh") < topBarAccountAction.indexOf("Icons.Default.Add"))

        val topActionsMenu = source
            .substringAfter("private fun SteamTopActionsMenu(")
            .substringBefore("@Composable\nprivate fun SteamCodeContent(")
        assertFalse(topActionsMenu.contains("accounts: List<SteamAccount>"))
        assertFalse(topActionsMenu.contains("accounts.forEach"))
        assertFalse(topActionsMenu.contains("onSelectAccount"))
        assertFalse(topActionsMenu.contains("onSelectSection"))
        assertFalse(topActionsMenu.contains("SteamSection.values"))
        assertFalse(topActionsMenu.contains("pendingConfirmationCount"))
        assertFalse(topActionsMenu.contains("onAddAccount"))
        assertFalse(topActionsMenu.contains("onDeleteAccount"))
        assertFalse(topActionsMenu.contains("R.string.steam_add_account_button"))
        assertFalse(topActionsMenu.contains("R.string.steam_delete_account_menu"))
        assertFalse(topActionsMenu.contains("R.string.refresh"))
        assertFalse(topActionsMenu.contains("onRefresh"))
        assertFalse(topActionsMenu.contains("R.string.steam_switch_account"))
        assertTrue(topActionsMenu.contains("R.string.nav_settings"))

        val codeContent = source
            .substringAfter("private fun SteamCodeContent(")
            .substringBefore("@Composable\nprivate fun SteamAccountDetailContent(")
        assertTrue(codeContent.contains("accounts: List<SteamAccount>"))
        assertTrue(codeContent.contains("selectedAccountIds: List<Long>"))
        assertTrue(codeContent.contains("onToggleSelection: (SteamAccount) -> Unit"))
        assertTrue(codeContent.contains("onSelectAll: () -> Unit"))
        assertTrue(codeContent.contains("onDeleteSelected: () -> Unit"))
        assertTrue(codeContent.contains("onTransferSelected: () -> Unit"))
        assertTrue(codeContent.contains("onOpenDetail: (SteamAccount) -> Unit"))
        assertTrue(codeContent.contains("appSettings: AppSettings"))
        assertTrue(codeContent.contains("rememberTotpTickerMillis(appSettings.validatorSmoothProgress)"))
        assertTrue(codeContent.contains("appSettings.validatorUnifiedProgressBar == UnifiedProgressBarMode.ENABLED"))
        assertTrue(codeContent.contains("UnifiedProgressBar("))
        assertTrue(codeContent.contains("style = appSettings.validatorProgressBarStyle"))
        assertTrue(codeContent.contains("smoothProgress = appSettings.validatorSmoothProgress"))
        assertTrue(codeContent.contains("timeOffset = (appSettings.totpTimeOffset * 1000).toLong()"))
        assertTrue(codeContent.contains("rememberReorderableLazyListState(lazyListState)"))
        assertTrue(codeContent.contains("onUpdateSortOrders(newOrders)"))
        assertTrue(codeContent.contains("Modifier.longPressDraggableHandle("))
        assertTrue(codeContent.contains("ReorderableItem("))
        assertTrue(codeContent.contains("LazyColumn("))
        assertFalse(codeContent.contains("LazyVerticalGrid("))
        assertFalse(codeContent.contains("columns = GridCells.Fixed(2)"))
        assertTrue(codeContent.contains("SwipeActions("))
        assertTrue(codeContent.contains("onSwipeRight = { onToggleSelection(account) }"))
        assertTrue(codeContent.contains("allowSwipeLeft = false"))
        assertTrue(codeContent.contains("SelectionActionBar("))
        assertTrue(codeContent.contains("onSelectAll = onSelectAll"))
        assertTrue(codeContent.contains("onMoveToCategory = onTransferSelected"))
        assertTrue(codeContent.contains("if (selectionMode)"))
        assertTrue(codeContent.contains(".align(Alignment.BottomStart)"))
        assertTrue(codeContent.contains("onOpenDetail(account)"))
        assertTrue(codeContent.contains("copyCode(SteamTotp.generateAuthCode(account.sharedSecret"))
        assertTrue(codeContent.contains("isSelectionMode = selectionMode"))
        assertTrue(codeContent.contains("sharedProgressTimeMillis = sharedProgressTimeMillis"))
        assertTrue(codeContent.contains("appSettings = appSettings"))
        assertFalse(codeContent.contains("onLongClick = { onToggleSelection(account) }"))
        assertTrue(codeContent.contains("SteamAvatarImage("))

        val openDetailBlock = source
            .substringAfter("onOpenDetail = { account ->")
            .substringBefore("}")
        assertTrue(openDetailBlock.contains("detailAccountId = account.id"))
        assertFalse(openDetailBlock.contains("viewModel.selectAccount(account.id)"))

        val detailContent = source
            .substringAfter("private fun SteamAccountDetailContent(")
            .substringBefore("@Composable\nprivate fun SteamAccountCredentialCard(")
        assertTrue(detailContent.contains("authorizedDevices: List<SteamAuthorizedDevice>"))
        assertTrue(detailContent.contains("onEditRemark: () -> Unit"))
        assertTrue(detailContent.contains("onCompleteSteamIdLogin: () -> Unit"))
        assertTrue(detailContent.contains("if (!account.hasRealSteamId)"))
        assertTrue(detailContent.contains("SteamMissingSteamIdPromptCard("))
        assertTrue(detailContent.contains("hasIdentitySecret = !account.identitySecret.isNullOrBlank()"))
        assertTrue(detailContent.contains("SteamAuthorizedDevicesSection("))
        assertTrue(detailContent.contains("onRevokeAuthorizedDevice: (SteamAuthorizedDevice, String, String) -> Unit"))
        assertTrue(source.contains("uiState.authorizedDevices"))
        assertTrue(source.contains("editRemarkAccount"))
        assertTrue(source.contains("viewModel.updateDisplayName(account.id, remark)"))
        assertTrue(source.contains("SteamRemarkInfoRow("))
        assertTrue(source.contains("SteamRemarkEditDialog("))
        assertTrue(source.contains("SteamSensitiveInfoRow("))
        assertTrue(source.contains("R.string.steam_revocation_code_label"))
        assertTrue(source.contains("account.revocationCode.orEmpty()"))
        assertTrue(source.contains("revocationCodeVisible"))
        assertTrue(source.contains("ClipboardUtils.copyToClipboard("))
        assertTrue(source.contains("sensitive = true"))
        assertTrue(source.contains("R.string.steam_revocation_code_copied"))
        assertTrue(source.contains("steam_credential_bindings"))
        assertTrue(source.contains("steam_revoke_credential_set"))
        assertTrue(source.contains("credentialPreferences.edit().putLong(credentialPreferenceKey, entry.id)"))
        assertTrue(source.contains("useBoundCredential = boundUserName.isNotBlank() && boundPassword.isNotBlank()"))
        assertTrue(source.contains("R.string.steam_revoke_credential_auto_verify"))
        assertTrue(source.contains("R.string.steam_remark_optional_label"))
        assertTrue(source.contains("remarkNameOrEmpty()"))
        assertTrue(source.contains("steamIdCompletionAccountId"))
        assertTrue(source.contains("viewModel.beginSteamIdCompletionLogin(account.id, userName, password, credentialEntryId)"))
        assertTrue(source.contains("boundCredentialPassword"))
        assertTrue(source.contains("copiedMessageRes = R.string.steam_login_password_copied"))
        assertTrue(source.contains("titleRes = R.string.steam_steamid_completion_login_title"))
        assertTrue(source.contains("showRemarkField = false"))
        assertTrue(source.contains("if (scanQr != null && account != null && account.hasRealSteamId)"))
        assertTrue(source.contains("viewModel.refreshAuthorizedDevices(animatedDetailAccount.id)"))
        assertTrue(source.contains("viewModel.revokeAuthorizedDevice("))

        val missingSteamIdPromptContent = source
            .substringAfter("private fun SteamMissingSteamIdPromptCard(")
            .substringBefore("@Composable\nprivate fun SteamAccountCredentialCard(")
        assertTrue(missingSteamIdPromptContent.contains("R.string.steam_steamid_completion_title"))
        assertTrue(missingSteamIdPromptContent.contains("R.string.steam_steamid_completion_desc"))
        assertTrue(missingSteamIdPromptContent.contains("R.string.steam_steamid_completion_code_only_desc"))
        assertTrue(missingSteamIdPromptContent.contains("R.string.steam_steamid_completion_login_button"))

        val authorizedDevicesContent = source
            .substringAfter("private fun SteamAuthorizedDevicesSection(")
            .substringBefore("@Composable\nprivate fun SteamAuthorizedDeviceRow(")
        assertTrue(authorizedDevicesContent.contains("R.string.steam_authorized_devices_label"))
        assertTrue(authorizedDevicesContent.contains("R.string.steam_no_authorized_device_session"))
        assertTrue(authorizedDevicesContent.contains("R.string.steam_no_authorized_devices"))
        assertTrue(authorizedDevicesContent.contains("onRefresh"))
        assertTrue(authorizedDevicesContent.contains("pendingRevokeDevice"))
        assertTrue(authorizedDevicesContent.contains("AlertDialog("))
        assertTrue(authorizedDevicesContent.contains("onRevokeDevice(device, revokeUserName.trim(), revokePassword)"))
        assertTrue(authorizedDevicesContent.contains("PasswordVisualTransformation()"))
        assertTrue(authorizedDevicesContent.contains("passwordEntriesForPicker"))
        assertTrue(authorizedDevicesContent.contains("pickerSecurityManager.decryptData(entry.username)"))
        assertTrue(authorizedDevicesContent.contains("pickerSecurityManager.decryptData(entry.password)"))

        val authorizedDeviceRowContent = source
            .substringAfter("private fun SteamAuthorizedDeviceRow(")
            .substringBefore("@Composable\nprivate fun AuthorizedDeviceDetails(")
        assertTrue(authorizedDeviceRowContent.contains("onRequestRevoke"))
        assertTrue(authorizedDeviceRowContent.contains("!device.isCurrent"))

        val confirmationsContent = source
            .substringAfter("private fun SteamConfirmationsContent(")
            .substringBefore("@Composable\nprivate fun ConfirmationRow(")
        assertTrue(confirmationsContent.contains("accounts: List<SteamAccount>"))
        assertTrue(confirmationsContent.contains("onSelectAccount: (Long) -> Unit"))
        assertTrue(confirmationsContent.contains("var showAccountPicker by remember"))
        assertTrue(confirmationsContent.contains("var pendingAccountSwitchId by remember"))
        assertTrue(confirmationsContent.contains("LaunchedEffect(showAccountPicker, pendingAccountSwitchId)"))
        assertTrue(confirmationsContent.contains("pendingAccountSwitchId = selected.id"))
        assertTrue(confirmationsContent.contains("onSelectAccount(accountId)"))
        assertTrue(confirmationsContent.contains("SteamConfirmationAccountPickerSheet("))
        assertTrue(confirmationsContent.contains("SteamConfirmationAccountCard("))
        assertTrue(confirmationsContent.contains("onClick = { showAccountPicker = true }"))
        assertTrue(confirmationsContent.contains("MonicaModalBottomSheet("))
        assertTrue(confirmationsContent.contains("R.string.steam_switch_account"))

        val selectAccountContent = viewModelSource
            .substringAfter("fun selectAccount(id: Long)")
            .substringBefore("fun updateSortOrders")
        assertTrue(selectAccountContent.contains("selectRuntimeAccount(selectedId)"))
        assertTrue(selectAccountContent.indexOf("selectRuntimeAccount(selectedId)") < selectAccountContent.indexOf("repository.select(selectedId)"))

        val selectRuntimeAccountContent = viewModelSource
            .substringAfter("private fun selectRuntimeAccount(id: Long)")
            .substringBefore("private suspend fun saveMaFilePayload")
        assertTrue(selectRuntimeAccountContent.contains("confirmations = emptyList()"))
        assertTrue(selectRuntimeAccountContent.contains("pendingLogins = emptyList()"))
        assertTrue(selectRuntimeAccountContent.contains("authorizedDevices = emptyList()"))
        assertTrue(selectRuntimeAccountContent.contains("selectedConfirmationIds = emptySet()"))

        val topBarSource = projectFile("app/src/main/java/takagi/ru/monica/ui/components/ExpressiveTopBar.kt")
            .readText()
        assertTrue(topBarSource.contains("collapsedTitleEndPadding: Dp = 180.dp"))
        assertTrue(topBarSource.contains("val pillReserve = if (isSearchExpanded) 0.dp else collapsedTitleEndPadding"))
    }

    @Test
    fun steamPageSupportsQrScanSmoothProgressAndBulkSelection() {
        val screenSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
        val mainActivitySource = projectFile("app/src/main/java/takagi/ru/monica/MainActivity.kt")
            .readText()
        val navSource = projectFile("app/src/main/java/takagi/ru/monica/navigation/Screens.kt")
            .readText()
        val steamQrScannerSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamQrScannerScreen.kt")
            .readText()
        val qrScannerSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/QrScannerScreen.kt")
            .readText()
        val extensionsScreenSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/ExtensionsScreen.kt")
            .readText()
        val bottomNavSource = projectFile("app/src/main/java/takagi/ru/monica/ui/main/navigation/BottomNavModel.kt")
            .readText()
        val appGradleSource = projectFile("app/build.gradle").readText()
        val manifestSource = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(navSource.contains("object SteamQrScan : Screen(\"steam_qr_scan?accountId={accountId}\")"))
        assertTrue(navSource.contains("const val ARG_ACCOUNT_ID = \"accountId\""))
        assertTrue(navSource.contains("fun createRoute(accountId: Long? = null)"))
        assertTrue(mainActivitySource.contains("steam_qr_result"))
        assertTrue(mainActivitySource.contains("steam_qr_account_id"))
        assertTrue(mainActivitySource.contains("Screen.SteamQrScan.createRoute(accountId)"))
        assertTrue(mainActivitySource.contains("SteamQrScannerScreen("))
        assertFalse(mainActivitySource.contains("SteamScannerPreferences(context.applicationContext)"))
        assertFalse(mainActivitySource.contains("useSteamMlKitScanner"))
        assertFalse(mainActivitySource.contains(".collectAsState(initial = null)"))
        assertFalse(mainActivitySource.contains("useMlKitScanner.collectAsState(initial = false)"))
        val steamQrRoute = mainActivitySource
            .substringAfter("route = Screen.SteamQrScan.route")
            .substringBefore("SteamQrScannerScreen(")
        assertTrue(steamQrRoute.contains("enterTransition = { easyNotesScreenEnter() }"))
        assertTrue(steamQrRoute.contains("exitTransition = { easyNotesScreenExit() }"))
        assertTrue(steamQrRoute.contains("popEnterTransition = { easyNotesScreenEnter() }"))
        assertTrue(steamQrRoute.contains("popExitTransition = { easyNotesScreenExit() }"))
        assertTrue(screenSource.contains("pendingSteamQrResult"))
        assertTrue(screenSource.contains("pendingSteamQrAccountId"))
        assertTrue(screenSource.contains("onScanSteamQrCode"))
        assertTrue(screenSource.contains("pendingScannedQr"))
        assertTrue(screenSource.contains("R.string.steam_qr_login_title"))
        assertTrue(screenSource.contains("R.string.scan_qr_code"))
        assertTrue(screenSource.contains("autoPromptedLoginClientIds"))
        assertTrue(screenSource.contains("SteamLoginNotificationHelper.show(context, login)"))
        assertTrue(screenSource.contains("selectedSection == SteamSection.CODE"))
        assertTrue(screenSource.contains("R.string.steam_login_request_title"))
        assertTrue(screenSource.contains("R.string.steam_time_label"))
        assertTrue(screenSource.contains("formatSteamLoginTime(login.detectedAtMillis)"))
        assertTrue(screenSource.contains("R.string.select_all"))

        assertTrue(steamQrScannerSource.contains("initialAccountId: Long?"))
        assertFalse(steamQrScannerSource.contains("useMlKitScanner: Boolean"))
        assertTrue(steamQrScannerSource.contains("onQrCodeScanned: (String, Long?) -> Unit"))
        assertTrue(steamQrScannerSource.contains("readLastSteamQrAccountId(context)"))
        assertTrue(steamQrScannerSource.contains("rememberSaveable(initialAccountId, rememberedAccountId)"))
        assertTrue(steamQrScannerSource.contains("import com.google.zxing.BarcodeFormat"))
        assertTrue(steamQrScannerSource.contains("import takagi.ru.monica.ui.screens.QrScannerScreen"))
        assertTrue(steamQrScannerSource.contains("repository.getAccounts()"))
        assertTrue(steamQrScannerSource.contains("withContext(Dispatchers.IO)"))
        assertFalse(steamQrScannerSource.contains("SteamViewModel.factory"))
        assertFalse(steamQrScannerSource.contains("viewModel("))
        assertFalse(steamQrScannerSource.contains("collectAsState"))
        assertFalse(steamQrScannerSource.contains("import com.google.mlkit.vision.barcode.BarcodeScanning"))
        assertFalse(steamQrScannerSource.contains("SteamQrScannerEngine.MlKit"))
        assertFalse(steamQrScannerSource.contains("SteamQrScannerEngine.ZXing"))
        assertFalse(steamQrScannerSource.contains("if (useMlKitScanner)"))
        assertFalse(steamQrScannerSource.contains("isUnbundledMlKitAvailable(context)"))
        assertFalse(steamQrScannerSource.contains("BarcodeScanning.getClient("))
        assertFalse(steamQrScannerSource.contains("onFallbackToZxing"))
        assertTrue(steamQrScannerSource.contains("QrScannerScreen("))
        assertFalse(steamQrScannerSource.contains("SteamZxingQrScannerScreen("))
        assertFalse(steamQrScannerSource.contains("withFrameNanos"))
        assertFalse(steamQrScannerSource.contains("cameraStartRequested = true"))
        assertFalse(steamQrScannerSource.contains("releaseSteamMlKitCameraAsync("))
        assertFalse(steamQrScannerSource.contains("STEAM_QR_CAMERA_RELEASE_DELAY_MS"))
        assertTrue(steamQrScannerSource.contains("allowedFormats = listOf(BarcodeFormat.QR_CODE)"))
        assertTrue(steamQrScannerSource.contains("resultValidator = ::isValidSteamQrPayload"))
        assertTrue(steamQrScannerSource.contains("invalidResultMessage = stringResource(R.string.steam_qr_invalid_link)"))
        assertTrue(steamQrScannerSource.contains("SteamDiagLogger.initialize(appContext)"))
        assertTrue(steamQrScannerSource.contains("SteamDiagLogger::append"))
        assertTrue(steamQrScannerSource.contains("diagnosticLabel = \"steam_qr\""))
        assertTrue(steamQrScannerSource.contains("event=screen_enter"))
        assertTrue(steamQrScannerSource.contains("SteamQrChallenge.parse(raw) != null"))
        assertFalse(steamQrScannerSource.contains("STEAM_QR_MLKIT_FALLBACK_DELAY_MS"))
        assertFalse(steamQrScannerSource.contains("if (!scanConsumed.get())"))
        assertFalse(steamQrScannerSource.contains("STEAM_QR_MIN_STABLE_HITS"))
        assertFalse(steamQrScannerSource.contains("acceptLiveCandidate("))
        assertFalse(steamQrScannerSource.contains("MultiFormatAnalyzer("))
        assertFalse(steamQrScannerSource.contains("SteamQrZxingLiteAnalyzer("))
        assertFalse(appGradleSource.contains("com.google.android.gms:play-services-mlkit-barcode-scanning"))
        assertTrue(appGradleSource.contains("com.google.mlkit:barcode-scanning"))
        assertFalse(appGradleSource.contains("zxing-lite"))
        assertFalse(manifestSource.contains("com.google.mlkit.vision.DEPENDENCIES"))
        assertFalse(manifestSource.contains("android:value=\"barcode\""))
        assertFalse(extensionsScreenSource.contains("SteamScannerPreferences(context.applicationContext)"))
        assertFalse(extensionsScreenSource.contains("GoogleApiAvailability.getInstance()"))
        assertFalse(extensionsScreenSource.contains("ConnectionResult.SUCCESS"))
        assertFalse(extensionsScreenSource.contains("steam_mlkit_scanner_title"))
        assertFalse(extensionsScreenSource.contains("steamScannerPreferences.updateUseMlKitScanner(enabled)"))
        assertTrue(qrScannerSource.contains("BarcodeScanning.getClient("))
        assertTrue(qrScannerSource.contains("ProcessCameraProvider.getInstance(context)"))
        assertTrue(qrScannerSource.contains("InputImage.fromMediaImage("))
        assertTrue(qrScannerSource.contains("InputImage.fromFilePath(context, uri)"))
        assertFalse(qrScannerSource.contains("ML_KIT_FRAME_TIMEOUT_MS"))
        assertFalse(qrScannerSource.contains("ML_KIT_CAMERA_STALE_MS"))
        assertFalse(qrScannerSource.contains("scannerGeneration"))
        assertFalse(qrScannerSource.contains("onFrameTimeout"))
        assertFalse(qrScannerSource.contains("LifecycleEventObserver"))
        assertTrue(qrScannerSource.contains("processingFrame.compareAndSet(false, true)"))
        assertFalse(qrScannerSource.contains("DecoratedBarcodeView"))
        assertFalse(qrScannerSource.contains("MultiFormatReader"))
        assertFalse(qrScannerSource.contains("DefaultDecoderFactory"))
        assertTrue(steamQrScannerSource.contains("initialAccountId != null && initialAccountId in existingIds"))
        assertTrue(steamQrScannerSource.contains("rememberedAccountId != null && rememberedAccountId in existingIds"))
        assertTrue(steamQrScannerSource.contains("saveLastSteamQrAccountId(context, account.id)"))
        assertTrue(steamQrScannerSource.contains("saveLastSteamQrAccountId(context, accountId)"))
        assertTrue(steamQrScannerSource.contains("SteamQrScannerBottomContent("))
        assertTrue(steamQrScannerSource.contains("SteamAvatarImage("))
        assertTrue(steamQrScannerSource.contains("account = selectedAccount"))
        assertTrue(steamQrScannerSource.contains("account = account"))
        assertTrue(steamQrScannerSource.contains("MonicaModalBottomSheet("))
        assertTrue(steamQrScannerSource.contains("navigationBarsPadding()"))
        assertTrue(steamQrScannerSource.contains("LazyColumn("))
        assertTrue(steamQrScannerSource.contains("SteamQrAccountOptionRow("))
        assertTrue(steamQrScannerSource.contains("heightIn(max = 360.dp)"))
        assertTrue(steamQrScannerSource.contains(".height(58.dp)"))
        assertFalse(steamQrScannerSource.contains("DialogProperties"))
        assertFalse(steamQrScannerSource.contains("AlertDialog("))
        assertFalse(steamQrScannerSource.contains("OutlinedButton("))
        assertFalse(steamQrScannerSource.contains("TextButton("))
        assertTrue(steamQrScannerSource.contains(".weight(1f)"))
        assertTrue(steamQrScannerSource.contains(".height(72.dp)"))
        assertTrue(steamQrScannerSource.contains(".size(72.dp)"))
        assertTrue(steamQrScannerSource.contains(".clip(albumShape)"))
        assertTrue(steamQrScannerSource.contains(".background(albumContainerColor)"))
        assertTrue(steamQrScannerSource.contains("contentAlignment = Alignment.Center"))
        assertTrue(steamQrScannerSource.contains("modifier = Modifier.align(Alignment.BottomCenter)"))
        assertTrue(steamQrScannerSource.contains("indication = null"))
        assertTrue(steamQrScannerSource.contains("collectIsPressedAsState()"))
        assertTrue(steamQrScannerSource.contains("R.string.steam_qr_album_select"))

        assertTrue(qrScannerSource.contains("allowedFormats: Collection<BarcodeFormat> = DEFAULT_SCANNER_FORMATS"))
        assertTrue(qrScannerSource.contains("resultValidator: (String) -> Boolean = { true }"))
        assertTrue(qrScannerSource.contains("createMlKitBarcodeScanner(mlKitFormats)"))
        assertTrue(qrScannerSource.contains("analyzeFrameWithMlKit("))
        assertTrue(qrScannerSource.contains("processImageWithMlKit("))
        assertTrue(qrScannerSource.contains("invalidResultMessage: String? = null"))
        assertTrue(qrScannerSource.contains("diagnosticLabel: String? = null"))
        assertTrue(qrScannerSource.contains("onDiagnostic: ((String) -> Unit)? = null"))
        assertTrue(qrScannerSource.contains("QrScannerDiagnostics("))
        assertTrue(qrScannerSource.contains("QR_SCAN_DIAG_HEARTBEAT_MS = 30_000L"))
        assertTrue(qrScannerSource.contains("\"heartbeat\""))
        assertTrue(qrScannerSource.contains("\"first_frame\""))
        assertTrue(qrScannerSource.contains("\"camera_bind_success\""))
        assertTrue(qrScannerSource.contains("\"camera_bind_failed\""))
        assertTrue(qrScannerSource.contains("\"gallery_result\""))
        assertTrue(qrScannerSource.contains("onInvalid: () -> Unit"))
        assertTrue(qrScannerSource.contains("candidates.isEmpty()"))
        assertTrue(qrScannerSource.contains("url?.url"))
        assertTrue(qrScannerSource.contains("allowedFormats.toMlKitFormatList()"))
        assertTrue(qrScannerSource.contains("Barcode.FORMAT_QR_CODE"))
        assertFalse(qrScannerSource.contains("ML_KIT_FRAME_TIMEOUT_MS"))
        assertFalse(qrScannerSource.contains("scannerGeneration"))

        assertTrue(viewModelSource.contains("CODE_TICK_INTERVAL_MS = 250L"))
        assertTrue(viewModelSource.contains("periodProgress"))
        assertTrue(screenSource.contains("TotpCodeCard"))
        assertTrue(screenSource.contains("toSteamTotpUiData"))
        assertTrue(screenSource.contains("steam://${'$'}{sharedSecret}"))
        assertFalse(screenSource.contains("AccountDetails(account)"))

        assertTrue(viewModelSource.contains("fun selectAllConfirmations()"))
        assertTrue(viewModelSource.contains("fun clearSelectedConfirmations()"))
        assertTrue(bottomNavSource.contains("SteamDockIcon"))
        assertFalse(bottomNavSource.contains("SportsEsports"))
        assertFalse(screenSource.contains("SportsEsports"))
    }

    @Test
    fun steamLoginChallengeCanPickExistingMonicaSteamCode() {
        val screenSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
        val loginServiceSource = projectFile("app/src/main/java/takagi/ru/monica/steam/service/SteamLoginImportService.kt")
            .readText()
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()
        val zhStrings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(screenSource.contains("availableCodeAccounts = uiState.accounts"))
        assertTrue(viewModelSource.contains("val canUseMonicaCode: Boolean"))
        assertTrue(viewModelSource.contains("canUseMonicaCode = SteamLoginImportService.isSteamGuardCodeChallengeType(confirmationType)"))

        val steamGuardTypeBlock = loginServiceSource
            .substringAfter("fun isSteamGuardCodeChallengeType(confirmationType: Int): Boolean")
            .substringBefore("fun isPollingChallengeType")
        assertTrue(steamGuardTypeBlock.contains("AUTH_CODE_TYPE_DEVICE"))
        assertTrue(steamGuardTypeBlock.contains("LEGACY_CODE_TYPE_TWO_FACTOR"))
        assertFalse(steamGuardTypeBlock.contains("AUTH_CODE_TYPE_EMAIL"))
        assertFalse(steamGuardTypeBlock.contains("ADD_AUTHENTICATOR_SMS_ACTIVATION_CODE"))
        assertFalse(steamGuardTypeBlock.contains("ADD_AUTHENTICATOR_EMAIL_ACTIVATION_CODE"))

        val qrLoginDialog = screenSource
            .substringAfter("private fun SteamQrLoginImportDialog(")
            .substringBefore("@Composable\nprivate fun SteamAuthenticatorCodePickerBottomSheet(")
        assertTrue(qrLoginDialog.contains("availableCodeAccounts: List<SteamAccount>"))
        assertTrue(qrLoginDialog.contains("legacyTotpItems by passwordDatabase.secureItemDao()"))
        assertTrue(qrLoginDialog.contains("getActiveItemsByType(ItemType.TOTP)"))
        assertTrue(qrLoginDialog.contains("var showMonicaCodePicker by remember"))
        assertTrue(qrLoginDialog.contains("pendingChallenge?.canUseMonicaCode == true"))
        assertTrue(qrLoginDialog.contains("hasLegacySteamCode"))
        assertTrue(qrLoginDialog.contains("R.string.steam_use_monica_code"))
        assertTrue(qrLoginDialog.contains("SteamAuthenticatorCodePickerBottomSheet("))
        assertTrue(qrLoginDialog.contains("legacyTotpItems = legacyTotpItems"))
        assertTrue(qrLoginDialog.contains("challengeCode = code"))

        val loginDialog = screenSource
            .substringAfter("private fun SteamLoginImportDialog(")
            .substringBefore("if (showSteamPasswordPicker && pendingChallenge == null)")
        assertTrue(loginDialog.contains("availableCodeAccounts: List<SteamAccount>"))
        assertTrue(loginDialog.contains("legacyTotpItems by passwordDatabase.secureItemDao()"))
        assertTrue(loginDialog.contains("getActiveItemsByType(ItemType.TOTP)"))
        assertTrue(loginDialog.contains("var showMonicaCodePicker by remember"))
        assertTrue(loginDialog.contains("pendingChallenge?.canUseMonicaCode == true"))
        assertTrue(loginDialog.contains("hasLegacySteamCode"))
        assertTrue(loginDialog.contains("R.string.steam_use_monica_code"))
        assertTrue(loginDialog.contains("SteamAuthenticatorCodePickerBottomSheet("))
        assertTrue(loginDialog.contains("legacyTotpItems = legacyTotpItems"))
        assertTrue(loginDialog.contains("challengeCode = code"))

        val codePicker = screenSource
            .substringAfter("private fun SteamAuthenticatorCodePickerBottomSheet(")
            .substringBefore("@Composable\nprivate fun SteamQrLoginCodeImage(")
        assertTrue(codePicker.contains("MonicaModalBottomSheet("))
        assertTrue(codePicker.contains("rememberTotpTickerMillis(smooth = true)"))
        assertTrue(codePicker.contains("SteamTotp.generateAuthCode(account.sharedSecret, currentSeconds)"))
        assertTrue(codePicker.contains("SteamAvatarImage(account = account"))
        assertTrue(codePicker.contains("legacyTotpItems: List<SecureItem>"))
        assertTrue(codePicker.contains("toLegacySteamAuthenticatorCodeSource(securityManager, currentSeconds)"))
        assertTrue(codePicker.contains("LegacySteamAuthenticatorCodePickerRow("))
        assertTrue(codePicker.contains("TotpDataResolver.parseStoredItemData"))
        assertTrue(codePicker.contains("TotpGenerator.generateOtp(normalized, currentSeconds = currentSeconds)"))
        assertTrue(codePicker.contains("normalized.otpType != OtpType.STEAM"))
        assertTrue(codePicker.contains("length == 5 && any { it.isLetter() }"))
        assertTrue(codePicker.contains("Icons.Default.Search"))
        assertTrue(codePicker.contains("R.string.search_authenticator"))
        assertTrue(codePicker.contains("R.string.steam_authenticator_code_picker_title"))
        assertTrue(codePicker.contains("R.string.steam_authenticator_code_picker_empty"))

        assertTrue(defaultStrings.contains("steam_use_monica_code"))
        assertTrue(defaultStrings.contains("steam_authenticator_code_picker_title"))
        assertTrue(zhStrings.contains("steam_use_monica_code"))
        assertTrue(zhStrings.contains("steam_authenticator_code_picker_title"))
    }

    @Test
    fun steamDetailCanRebindAccountWithoutReplacingToken() {
        val screenSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
            .replace("\r\n", "\n")
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()
        val zhStrings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        val detailTopBarCall = screenSource
            .substringAfter("SteamDetailTopBar(")
            .substringBefore(")\\n                } else")
        assertTrue(detailTopBarCall.contains("onRebindAccount = animatedDetailAccount?.let"))
        assertTrue(detailTopBarCall.contains("steamAccountRebindAccountId = account.id"))

        val detailTopBar = screenSource
            .substringAfter("private fun SteamDetailTopBar(")
            .substringBefore("@Composable\nprivate fun SteamTopActionsMenu(")
        assertTrue(detailTopBar.contains("onRebindAccount: (() -> Unit)? = null"))
        assertTrue(detailTopBar.contains("Icons.Default.Login"))
        assertTrue(detailTopBar.contains("R.string.steam_account_rebind_action"))
        assertTrue(detailTopBar.indexOf("if (onRebindAccount != null)") < detailTopBar.indexOf("if (onRemoveAuthenticator != null)"))

        val rebindDialog = screenSource
            .substringAfter("steamAccountRebindAccount?.let { account ->")
            .substringBefore("uiState.pendingMaFileSteamIdRequest")
        assertTrue(rebindDialog.contains("SteamLoginImportDialog("))
        assertTrue(rebindDialog.contains("viewModel.beginSteamAccountRebindLogin(account.id, userName, password, credentialEntryId)"))
        assertTrue(rebindDialog.contains("R.string.steam_account_rebind_login_title"))
        assertTrue(rebindDialog.contains("R.string.steam_account_rebind_login_message"))
        assertTrue(rebindDialog.contains("showRemarkField = false"))

        assertTrue(viewModelSource.contains("fun beginSteamAccountRebindLogin("))
        assertTrue(viewModelSource.contains("pendingLoginCredentialEntryId = credentialEntryId"))
        assertTrue(viewModelSource.contains("putLong(\"steam_${'$'}{result.steamId}_password_entry_id\", entryId)"))
        assertTrue(viewModelSource.contains("pendingLoginRebindAccount = true"))
        assertTrue(viewModelSource.contains("loginImportService.beginSessionLogin(userName, password)"))
        assertTrue(viewModelSource.contains("val isRebind = pendingLoginRebindAccount"))
        assertTrue(viewModelSource.contains("if (account.hasRealSteamId && !isRebind)"))
        assertTrue(viewModelSource.contains("replaceExistingBinding = isRebind"))
        assertTrue(viewModelSource.contains("return R.string.steam_account_rebind_done"))
        assertTrue(viewModelSource.contains("sharedSecret = account.sharedSecret"))
        assertTrue(viewModelSource.contains("identitySecret = account.identitySecret ?: loginPayload.identitySecret"))
        assertTrue(viewModelSource.contains("val resolvedDeviceId = if (replaceExistingBinding)"))
        assertTrue(viewModelSource.contains("persistCompletedSteamIdAccount(completed)"))

        assertTrue(defaultStrings.contains("steam_account_rebind_action"))
        assertTrue(defaultStrings.contains("steam_account_rebind_done"))
        assertTrue(zhStrings.contains("steam_account_rebind_action"))
        assertTrue(zhStrings.contains("steam_account_rebind_done"))
    }

    @Test
    fun steamErrorsExplainMissingQrAndConfirmationRequirements() {
        val screenSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val scannerSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamQrScannerScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
            .replace("\r\n", "\n")
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()
        val zhStrings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(screenSource.contains("steamLoginApprovalUnavailableText(account)"))
        assertTrue(screenSource.contains("steamConfirmationUnavailableText(account)"))
        assertTrue(screenSource.contains("loginApprovalUnavailableReason"))
        assertTrue(screenSource.contains("enabled = loginApprovalUnavailableReason == null"))
        assertTrue(scannerSource.contains("steamQrUnavailableText()"))
        assertTrue(scannerSource.contains("R.string.steam_no_login_missing_shared_secret"))
        assertTrue(scannerSource.contains("R.string.steam_no_login_missing_session_detail"))

        assertTrue(viewModelSource.contains("private fun SteamAccount.loginApprovalUnavailableMessage(): String?"))
        assertTrue(viewModelSource.contains("private fun SteamAccount.confirmationUnavailableMessage(): String?"))
        assertTrue(viewModelSource.contains("sharedSecret.isBlank() -> appContext.getString(R.string.steam_no_login_missing_shared_secret)"))
        assertTrue(viewModelSource.contains("identitySecret.isNullOrBlank() -> appContext.getString(R.string.steam_no_confirmation_missing_identity_secret)"))
        assertTrue(viewModelSource.contains("account.loginApprovalUnavailableMessage()?.let"))
        assertTrue(viewModelSource.contains("account.confirmationUnavailableMessage()?.let"))

        listOf(
            "steam_no_login_missing_shared_secret",
            "steam_no_login_missing_session_detail",
            "steam_no_confirmation_missing_identity_secret",
            "steam_no_confirmation_missing_session_detail"
        ).forEach { key ->
            assertTrue(defaultStrings.contains(key))
            assertTrue(zhStrings.contains(key))
        }
    }

    @Test
    fun steamAddDialogCanImportCodeOnlyFromSharedSecret() {
        val screenSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
            .replace("\r\n", "\n")
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()
        val zhStrings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(screenSource.contains("KEY_ONLY"))
        assertTrue(screenSource.contains("onSelectKeyOnly = {"))
        assertTrue(screenSource.contains("addAccountMethod = SteamAddAccountMethod.KEY_ONLY"))
        assertTrue(screenSource.contains("SteamKeyOnlyImportDialog("))
        assertTrue(screenSource.contains("viewModel.importCodeOnlyKey(displayName, accountName, sharedSecret)"))
        assertTrue(screenSource.contains("R.string.steam_add_method_key_only"))
        assertTrue(screenSource.contains("R.string.steam_key_only_title"))
        assertTrue(screenSource.contains("R.string.steam_key_only_desc"))
        assertTrue(screenSource.contains("R.string.steam_key_only_secret_label"))
        assertTrue(screenSource.contains("val canImport = accountName.isNotBlank() && sharedSecret.isNotBlank()"))

        assertTrue(viewModelSource.contains("fun importCodeOnlyKey(displayName: String, accountName: String, sharedSecret: String)"))
        assertTrue(viewModelSource.contains("\"shared_secret\" to JsonPrimitive(sharedSecret.trim())"))
        assertTrue(viewModelSource.contains("\"monica_missing_steamid\" to JsonPrimitive(true)"))
        assertTrue(viewModelSource.contains("allowMissingSteamId = true"))
        assertTrue(viewModelSource.contains("saveMaFilePayload(payload)"))
        assertTrue(viewModelSource.contains("R.string.steam_account_imported_code_only"))

        listOf(
            "steam_add_method_key_only",
            "steam_key_only_title",
            "steam_key_only_desc",
            "steam_key_only_secret_label",
            "steam_key_only_import_button"
        ).forEach { key ->
            assertTrue(defaultStrings.contains(key))
            assertTrue(zhStrings.contains(key))
        }
    }

    @Test
    fun steamDockUsesFixedSteamLabelAndControllerIcon() {
        val bottomNavSource = projectFile("app/src/main/java/takagi/ru/monica/ui/main/navigation/BottomNavModel.kt")
            .readText()
        val quickSetupSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/QuickSetupScreen.kt")
            .readText()
        val settingsSource = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt")
            .readText()
        val iconSource = projectFile("app/src/main/java/takagi/ru/monica/ui/main/navigation/SteamDockIcon.kt")
            .readText()
        val zhStrings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()

        assertTrue(bottomNavSource.contains("object Steam : BottomNavItem(BottomNavContentTab.STEAM, SteamDockIcon)"))
        assertTrue(quickSetupSource.contains("BottomNavContentTab.STEAM -> SteamDockIcon"))
        assertTrue(settingsSource.contains("BottomNavContentTab.STEAM -> SteamDockIcon"))
        assertTrue(iconSource.contains("name = \"SteamDockIcon\""))
        assertTrue(iconSource.contains("quadTo(129f, 800f, 86.5f, 757f)"))
        assertFalse(bottomNavSource.contains("BottomNavContentTab.STEAM, Icons.Default.VerifiedUser"))
        assertFalse(quickSetupSource.contains("BottomNavContentTab.STEAM -> Icons.Default.VerifiedUser"))
        assertFalse(settingsSource.contains("BottomNavContentTab.STEAM -> Icons.Default.VerifiedUser"))
        assertTrue(zhStrings.contains("<string name=\"nav_steam_short\">Steam</string>"))
        assertTrue(defaultStrings.contains("<string name=\"nav_steam_short\">Steam</string>"))
    }

    @Test
    fun steamConfirmationPageUsesSlimSwipeSelectionLayout() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val confirmationContent = source
            .substringAfter("private fun SteamConfirmationsContent(")
            .substringBefore("@Composable\nprivate fun SteamLoginApprovalSection(")

        assertTrue(confirmationContent.contains("SteamConfirmationAccountCard("))
        assertTrue(confirmationContent.contains("modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)"))
        assertTrue(confirmationContent.contains("LazyColumn("))
        assertTrue(confirmationContent.contains("SwipeActions("))
        assertTrue(confirmationContent.contains("onSwipeRight = { onToggle(confirmation.id) }"))
        assertTrue(confirmationContent.contains("allowSwipeLeft = false"))
        assertTrue(confirmationContent.contains("SelectionActionBar("))
        assertTrue(confirmationContent.contains("onSelectAll = onSelectAll"))
        assertTrue(confirmationContent.contains("onDelete = null"))
        assertTrue(confirmationContent.contains("FloatingActionButton("))
        assertTrue(confirmationContent.contains("showBulkActionDialog = true"))
        assertTrue(confirmationContent.contains(".fillMaxWidth()"))
        assertTrue(confirmationContent.contains("Spacer(modifier = Modifier.weight(1f))"))
        assertTrue(confirmationContent.contains("R.string.steam_confirmation_action_title"))
        assertTrue(confirmationContent.contains("SteamConfirmationItemImage("))
        assertTrue(confirmationContent.contains("ContentScale.Fit"))
        assertTrue(confirmationContent.contains("loadSteamConfirmationImage("))
        assertTrue(confirmationContent.contains("pendingAction = ConfirmationActionRequest("))
        assertTrue(confirmationContent.contains("confirmations = listOf(confirmation)"))
        assertTrue(confirmationContent.contains("onLongClick = { onToggle(confirmation.id) }"))
        assertFalse(confirmationContent.contains("Button(onClick = onRefresh"))
        assertFalse(confirmationContent.contains("Checkbox("))
        assertFalse(confirmationContent.contains("onRespond(confirmation, true)"))
        assertFalse(confirmationContent.contains("onRespond(confirmation, false)"))

        val confirmationRowSource = source
            .substringAfter("private fun ConfirmationRow(")
            .substringBefore("@Composable\nprivate fun SteamConfirmationItemImage(")
        assertTrue(confirmationRowSource.contains("combinedClickable("))
        assertTrue(confirmationRowSource.contains("onLongClick = onLongClick"))

        val selectionBarSource = projectFile("app/src/main/java/takagi/ru/monica/ui/common/selection/SelectionActionBar.kt")
            .readText()
        assertTrue(selectionBarSource.contains("onDelete: (() -> Unit)? = null"))
        assertTrue(selectionBarSource.contains("onDelete?.let"))

        val serviceSource = projectFile("app/src/main/java/takagi/ru/monica/steam/network/SteamConfirmationService.kt")
            .readText()
        assertTrue(serviceSource.contains("val imageUrl: String"))
        assertTrue(serviceSource.contains("imageUrl = imageUrl()"))
        assertTrue(serviceSource.contains("\"image_url\""))

        val authorizedDeviceServiceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/network/SteamAuthorizedDeviceService.kt"
        ).readText()
        assertTrue(authorizedDeviceServiceSource.contains("method = \"EnumerateTokens\""))
        assertTrue(authorizedDeviceServiceSource.contains("writeBool(1, false)"))
        assertFalse(authorizedDeviceServiceSource.contains("fun deauthorizeAll("))
        assertFalse(authorizedDeviceServiceSource.contains("twofactor/manage_action"))
        assertFalse(authorizedDeviceServiceSource.contains("method = \"RevokeRefreshToken\""))
        assertFalse(authorizedDeviceServiceSource.contains("SteamLoginApprovalSigner.tokenSignature"))
        assertFalse(authorizedDeviceServiceSource.contains("SteamCmRefreshTokenRevoker"))
        assertTrue(authorizedDeviceServiceSource.contains("fields[9]?.bytes?.let(::parseUsage)"))
        assertTrue(authorizedDeviceServiceSource.contains("fields[10]?.bytes?.let(::parseUsage)"))

        assertTrue(source.contains("removeAuthenticatorRequest"))
        assertTrue(source.contains("removeAuthenticatorVerifyAccount"))
        assertTrue(source.contains("removeAuthenticatorVerifyMode"))
        assertTrue(source.contains("SteamAuthenticatorRemovalMode.LOCAL_ONLY"))
        assertTrue(source.contains("SteamDetailTopBar("))
        assertTrue(source.contains("onRemoveAuthenticator = animatedDetailAccount?.let"))
        assertTrue(source.contains("M3IdentityVerifyDialog("))
        assertTrue(source.contains("securityManager.verifyMasterPassword(removeAuthenticatorPasswordInput)"))
        assertTrue(source.contains("viewModel.removeAuthenticator(account.id)"))
        assertTrue(source.contains("viewModel.deleteLocalAuthenticator(account.id)"))
        assertTrue(source.contains("R.string.steam_remove_authenticator_remote_action"))
        assertTrue(source.contains("R.string.steam_remove_authenticator_local_action"))
        assertTrue(source.contains("R.string.steam_remove_authenticator_local_hint"))
        assertTrue(source.contains("R.string.steam_remove_authenticator_action"))

        val viewModelSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt")
            .readText()
        assertTrue(viewModelSource.contains("fun removeAuthenticator(accountId: Long)"))
        assertTrue(viewModelSource.contains("authenticatorService.remove(account)"))
        assertTrue(viewModelSource.contains("repository.delete(accountId)"))
        assertTrue(viewModelSource.contains("fun deleteLocalAuthenticator(accountId: Long)"))
        assertTrue(viewModelSource.contains("R.string.steam_remove_authenticator_local_done"))
        val localDeleteBlock = viewModelSource
            .substringAfter("fun deleteLocalAuthenticator(accountId: Long)")
            .substringBefore("fun removeAuthenticator(accountId: Long)")
        assertTrue(localDeleteBlock.contains("deleteAccountByActiveSource(accountId)"))
        assertFalse(localDeleteBlock.contains("authenticatorService.remove"))
        val activeDeleteBlock = viewModelSource
            .substringAfter("private suspend fun deleteAccountByActiveSource(accountId: Long)")
            .substringBefore("private suspend fun reloadMdbxAccounts(")
        assertTrue(activeDeleteBlock.contains("repository.delete(accountId)"))
        assertTrue(activeDeleteBlock.contains("store.deleteAccount(source.databaseId, entryId)"))

        val authenticatorServiceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/network/SteamAuthenticatorService.kt"
        ).readText()
        assertTrue(authenticatorServiceSource.contains("method = \"RemoveAuthenticator\""))
        assertTrue(authenticatorServiceSource.contains("iface = \"ITwoFactorService\""))
        assertTrue(authenticatorServiceSource.contains("writeString(2, revocationCode)"))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
