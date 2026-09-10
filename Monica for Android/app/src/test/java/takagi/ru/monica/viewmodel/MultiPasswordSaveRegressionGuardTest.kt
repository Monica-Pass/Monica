package takagi.ru.monica.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPasswordSaveRegressionGuardTest {

    @Test
    fun saveAcrossTargetsDoesNotDeleteSameTargetMultiPasswordRowsAsDuplicateReplicas() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val saveAcrossTargetsBody = source.substringAfter("fun savePasswordsAcrossTargets(")
            .substringBefore("private suspend fun canWriteKeePassTargets")

        assertTrue(
            "Existing entries must be grouped by target so all password rows for that target are passed back into saveGroupedPasswordsInternal.",
            saveAcrossTargetsBody.contains(".groupBy { it.toStorageTarget().stableKey }")
        )
        assertFalse(
            "Same-target entries can be valid multi-password rows; do not delete them as duplicate replicas.",
            saveAcrossTargetsBody.contains("duplicateReplicaIds")
        )
        assertFalse(
            "Same-target entries can be valid multi-password rows; cleanup must only remove deselected targets.",
            saveAcrossTargetsBody.contains("sameTargetEntries.filterNot")
        )
    }

    @Test
    fun detailScreenUsesResolvedGroupMembersEvenWhenReplicaGroupIdExists() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/PasswordDetailScreen.kt"
        ).readText()

        assertFalse(
            "Detail screen must not collapse replicaGroupId entries to only the current entry; multi-password rows can share the same replica group.",
            source.contains("if (!entry.replicaGroupId.isNullOrBlank()) {\n            listOf(entry)")
        )
        assertFalse(
            "Detail screen must not collapse replicaGroupId entries to only the current entry; multi-password rows can share the same replica group.",
            source.contains("if (!entry.replicaGroupId.isNullOrBlank()) {\r\n            listOf(entry)")
        )
        assertTrue(
            "Detail screen should use resolved group members for the password card.",
            source.contains("val detailPasswords = resolvedGroupPasswords.ifEmpty { listOf(entry) }")
        )
        assertTrue(
            "Detail screen should use groupPasswords when rendering the password card.",
            source.contains("groupPasswords.ifEmpty { listOf(entry) }")
        )
    }

    @Test
    fun addPasswordScreenLoadsMdbxDatabasesWithoutRouteInjectedViewModel() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()

        assertTrue(
            "Inline add-password surfaces must still show concrete MDBX vaults when they do not pass MdbxViewModel.",
            source.contains("?: database.localMdbxDatabaseDao().getAllDatabases()")
        )
        assertFalse(
            "Falling back to only the constructor list leaves FAB inline creation unable to choose a concrete MDBX vault.",
            source.contains("?: kotlinx.coroutines.flow.flowOf(mdbxDatabasesFallback)")
        )
    }

    @Test
    fun deletingKeePassDatabaseDeletesCachedRowsInsteadOfConvertingThemToLocal() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val deleteDatabaseBody = viewModelSource.substringAfter("fun deleteDatabase(")
            .substringBefore("fun exportDatabase")
        val passwordDaoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordEntryDao.kt"
        ).readText()
        val secureItemDaoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/SecureItemDao.kt"
        ).readText()

        assertTrue(
            "Removing a KeePass database must delete its cached password rows; clearing the binding makes them appear as Monica-local duplicates.",
            deleteDatabaseBody.contains("passwordEntryDao().deleteByKeePassDatabaseId(databaseId)")
        )
        assertTrue(
            "Removing a KeePass database must delete its cached secure-item rows; clearing the binding makes TOTP/cards/notes appear as Monica-local data.",
            deleteDatabaseBody.contains("secureItemDao().deleteByKeePassDatabaseId(databaseId)")
        )
        assertFalse(
            "KeePass database deletion must not convert password cache rows into Monica-local rows.",
            deleteDatabaseBody.contains("passwordEntryDao().clearKeePassBindingForDatabase(databaseId)")
        )
        assertFalse(
            "KeePass database deletion must not convert secure-item cache rows into Monica-local rows.",
            deleteDatabaseBody.contains("secureItemDao().clearKeePassBindingForDatabase(databaseId)")
        )
        assertTrue(
            "Password DAO needs a delete path scoped to the removed KeePass database.",
            passwordDaoSource.contains("DELETE FROM password_entries WHERE keepassDatabaseId = :databaseId")
        )
        assertTrue(
            "Secure item DAO needs a delete path scoped to the removed KeePass database.",
            secureItemDaoSource.contains("DELETE FROM secure_items WHERE keepass_database_id = :databaseId")
        )
    }

    @Test
    fun inlineTotpPreviewMatchesSimplePasswordPreviewAndKeepsCountdownInSync() {
        val previewSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/InlineTotpPreviewCard.kt"
        ).readText()
        val addTotpSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditTotpScreen.kt"
        ).readText()
        val addPasswordSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()

        assertTrue(
            "Inline TOTP previews should keep the compact code plus a one-second Material Expressive shape animation while the number uses the synchronized countdown.",
            previewSource.contains("fun InlineTotpPreviewCard(") &&
                previewSource.contains("val synchronizedRemainingSeconds") &&
                previewSource.contains("rememberInfiniteTransition(label = \"inline_totp_badge_shape_transition\")") &&
                previewSource.contains("durationMillis = 1000") &&
                previewSource.contains("MaterialExpressiveLoadingIndicator(") &&
                previewSource.contains("progress = { shapeProgress }") &&
                previewSource.contains("modifier = Modifier.size(60.dp)") &&
                previewSource.contains("color = if (isHotp) containerColor else contentColor") &&
                previewSource.contains("LoadingIndicatorDefaults.IndeterminateIndicatorPolygons") &&
                addTotpSource.contains("showHeader = false") &&
                addTotpSource.contains("showProgress = false") &&
                addPasswordSource.contains("showHeader = false") &&
                addPasswordSource.contains("showProgress = false")
        )
        assertFalse(
            "Inline TOTP preview must not bring back the visible TOTP/Steam header, shield icon, bottom progress bar, circular progress-ring badge, or determinate LoadingIndicator that freezes into one rotating shape.",
            previewSource.contains("LinearProgressIndicator") ||
                previewSource.contains("Icons.Default.Shield") ||
                previewSource.contains("Icons.Default.Games") ||
                previewSource.contains("\"TOTP\"") ||
                previewSource.contains("\"Steam\"") ||
                previewSource.contains("drawArc(") ||
                previewSource.contains("Canvas(") ||
                previewSource.contains("progress = { progress") ||
                previewSource.contains("progress = { animatedShapeProgress }") ||
                previewSource.contains("DeterminateIndicatorPolygons")
        )
    }

    @Test
    fun addPasswordAuthenticatorKeyFieldHasInlineScanAction() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val mainActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()
        val simpleMainSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val passwordTabPaneSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/PasswordTabPane.kt"
        ).readText()
        val mainScreenFabSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/MainScreenFab.kt"
        ).readText()
        val securitySection = source.substringAfter("// Security Card (TOTP)")
            .substringBefore("// Organization Card")
        val authenticatorKeyField = securitySection.substringAfter("value = authenticatorSecret")
            .substringBefore("ExposedDropdownMenuBox(")

        assertTrue(
            "The Add Password authenticator key field should expose QR scanning as the field trailing action so scanned secrets fill the same form directly.",
            authenticatorKeyField.contains("trailingIcon = {") &&
                authenticatorKeyField.contains("if (onScanAuthenticatorQrCode != null)") &&
                authenticatorKeyField.contains("IconButton(onClick = onScanAuthenticatorQrCode)") &&
                authenticatorKeyField.contains("Icons.Default.QrCodeScanner") &&
                source.contains("pendingQrResult?.let { qrValue ->") &&
                source.contains("applyScannedAuthenticator(qrValue)")
        )
        assertFalse(
            "Do not bring back the separate full-width Scan QR button below the authenticator key field.",
            securitySection.contains("FilledTonalButton(\n                                    onClick = onScanAuthenticatorQrCode")
        )
        assertTrue(
            "The main password page must pass the QR scanner action/result into inline and FAB Add Password sheets, otherwise the trailing scan icon disappears outside the standalone route.",
            mainActivitySource.contains("val mainQrResult = navController.currentBackStackEntry") &&
                mainActivitySource.contains("pendingPasswordAuthenticatorQrResult = mainQrResult") &&
                mainActivitySource.contains("onScanPasswordAuthenticatorQrCode = {") &&
                mainActivitySource.contains("navController.navigate(Screen.QrScanner.route)") &&
                simpleMainSource.contains("pendingPasswordAuthenticatorQrResult: String? = null") &&
                simpleMainSource.contains("onScanPasswordAuthenticatorQrCode: () -> Unit = {}") &&
                simpleMainSource.contains("pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult") &&
                simpleMainSource.contains("onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode") &&
                passwordTabPaneSource.contains("pendingQrResult = pendingPasswordAuthenticatorQrResult") &&
                passwordTabPaneSource.contains("onScanAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode") &&
                mainScreenFabSource.contains("pendingPasswordAuthenticatorQrResult: String? = null") &&
                mainScreenFabSource.contains("onScanPasswordAuthenticatorQrCode: () -> Unit = {}")
        )
    }

    @Test
    fun swipeableAddFabUsesEasyNotesStyleFullScreenTransition() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/SwipeableAddFab.kt"
        ).readText()
        val mainScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val fabTransition = source.substringAfter("AnimatedVisibility(\n            visible = !isExpanded")
            .substringBefore("Box(\n                modifier = Modifier")
        val renderMainSurface = mainScreenSource.substringAfter("fun RenderMainSurface() {")
            .substringBefore("val prepareTotpAddStorageDefaults")
        val scaledMainSurfaceLayer = renderMainSurface.substringAfter("Box(\n        modifier = Modifier")
            .substringBefore("if (useDraggableNav")
        val overlayCallIndex = mainScreenSource.indexOf("MainScreenFabOverlay(")
        val renderCallIndex = mainScreenSource.indexOf("RenderMainSurface()", startIndex = overlayCallIndex)

        assertTrue(
            "FAB add should keep the lightweight button transition and delegate full-screen editing to the page-level inline editor.",
            source.contains("onClick: () -> Unit") &&
                source.contains("onClick = onClick") &&
                fabTransition.contains("fadeIn(animationSpec = tween(160))") &&
                fabTransition.contains("scaleIn(initialScale = 0.9f, animationSpec = tween(180))") &&
                fabTransition.contains("fadeOut(animationSpec = tween(120))") &&
                fabTransition.contains("scaleOut(targetScale = 0.9f, animationSpec = tween(140))") &&
                renderMainSurface.contains("Box(modifier = Modifier.fillMaxSize())") &&
                scaledMainSurfaceLayer.contains(".matchParentSize()") &&
                overlayCallIndex in 0 until renderCallIndex
        )
        assertFalse(
            "Do not bring back the FAB-to-fullscreen resize animation; EasyNotes uses a screen transition instead.",
            source.contains("Animatable(0f)") ||
                source.contains("expandProgress") ||
                source.contains("lerp(fabSize") ||
                source.contains("requiredSize(fullWidth, fullHeight)") ||
                source.contains("offset { IntOffset")
        )
    }

    @Test
    fun addPasswordFromMdbxFolderPreservesFolderTargetAndShowsFolderPicker() {
        val addPasswordSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val mainActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()
        val helpersSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/MainScreenHelpers.kt"
        ).readText()
        val pickerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/MultiStorageTargetPickerBottomSheet.kt"
        ).readText()
        val selectorSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/MultiStorageTargetSelectorCard.kt"
        ).readText()
        val passwordTabSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/PasswordTabPane.kt"
        ).readText()
        val fabSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/MainScreenFab.kt"
        ).readText()

        assertTrue(
            "New-item defaults must carry the selected MDBX folder from a folder filter, otherwise Add Password falls back to the vault root or Monica local.",
            helpersSource.contains("val mdbxFolderId: String? = null") &&
                helpersSource.contains("is CategoryFilter.MdbxFolderFilter") &&
                helpersSource.contains("mdbxFolderId = filter.folderId")
        )
        assertTrue(
            "Route-level pending defaults must persist the MDBX folder id across navigation into Add Password.",
            mainActivitySource.contains("KEY_PENDING_ADD_MDBX_FOLDER_ID") &&
                mainActivitySource.contains("mdbxFolderId = get<String>(KEY_PENDING_ADD_MDBX_FOLDER_ID)") &&
                mainActivitySource.contains("initialMdbxFolderId = pendingStorageDefaults?.mdbxFolderId")
        )
        assertTrue(
            "Add Password must initialize and save a concrete MDBX folder target, not just the database id.",
            addPasswordSource.contains("initialMdbxFolderId: String? = null") &&
                addPasswordSource.contains("var mdbxFolderId by rememberSaveable") &&
                addPasswordSource.contains("mdbxFolderId = initialMdbxFolderId") &&
                addPasswordSource.contains("is CategoryFilter.MdbxFolderFilter -> StorageTarget.Mdbx(filter.databaseId, filter.folderId)") &&
                addPasswordSource.contains("mdbxFolderId = primaryTarget.folderId") &&
                addPasswordSource.contains("mdbxFolderId = mdbxFolderId")
        )
        assertTrue(
            "Add Password storage selector must load MDBX folders into the folders section and select folder targets.",
            pickerSource.contains("getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>>") &&
                pickerSource.contains("val mdbxFoldersByDatabase") &&
                pickerSource.contains("getMdbxFolders(database.id).collectAsState") &&
                pickerSource.contains("StorageTarget.Mdbx(source.database.id, folder.folderId)") &&
                pickerSource.contains("mdbxFolderDisplayLabel(folder, folders)") &&
                addPasswordSource.contains("getMdbxFolders = viewModel::getMdbxFolders")
        )
        assertTrue(
            "MDBX should be visually distinct from KeePass in the Add Password storage UI.",
            pickerSource.contains("override val icon: ImageVector = Icons.Default.Storage") &&
                selectorSource.contains("is StorageTarget.Mdbx -> StorageCardVisuals(") &&
                selectorSource.contains("icon = Icons.Default.Storage") &&
                selectorSource.contains("getMdbxFolders(primaryTarget.databaseId)") &&
                selectorSource.contains("is StorageTarget.Mdbx -> externalFolderName ?: mdbxVaultLabel")
        )
        assertTrue(
            "Wide/inline Add Password entry points must pass the inherited MDBX folder into the screen.",
            passwordTabSource.contains("initialMdbxFolderId = passwordNewItemDefaults.mdbxFolderId") &&
                fabSource.contains("aggregateStorageDefaults.mdbxFolderId")
        )
    }

    @Test
    fun customDirectoryMdbxVaultsAreRegisteredAsExternalSources() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        assertTrue(
            "Custom-directory vault creation must keep the selected SAF URI as the vault source path.",
            source.contains("val filePath = customDirVault?.externalUri?.toString() ?: localVaultFile.absolutePath")
        )
        assertTrue(
            "Custom-directory vault creation must be registered as external storage, not app-internal storage.",
            source.contains("MdbxStorageLocation.EXTERNAL")
        )
        assertTrue(
            "Custom-directory vault creation must keep a writable local copy for MDBX operations.",
            source.contains("workingCopyPath = localVaultFile.absolutePath")
        )
    }

    @Test
    fun mdbxIncomingNewObjectsAreCopiedBeforeEncryptedFieldsAreDecoded() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()

        val applyIncomingEntryBody = source.substringAfter("private fun applyIncomingEntry(")
            .substringBefore("private fun applyIncomingAttachment(")
        assertTrue(
            "A password created on another client is a new local entry; it must be copied before decoding incoming encrypted fields.",
            applyIncomingEntryBody.indexOf("if (localState == null)") <
                applyIncomingEntryBody.indexOf("val incomingPayload = normalizeVaultBytes")
        )

        val applyIncomingProjectBody = source.substringAfter("private fun applyIncomingProject(")
            .substringBefore("private fun applyIncomingEntry(")
        assertTrue(
            "A project created on another client is a new local project; it must be copied before decoding incoming encrypted fields.",
            applyIncomingProjectBody.indexOf("if (localState == null)") <
                applyIncomingProjectBody.indexOf("val incomingTitle = normalizeVaultBytes")
        )

        assertTrue(
            "When incoming credential lookup fails but the incoming file is the same vault and active epoch, merge should reuse the local epoch key.",
            source.contains("canReuseLocalEpochKeyForIncoming(local, incoming)")
        )
        assertTrue(
            "Project existence checks must not decode encrypted project titles with a null epoch key.",
            source.contains("SELECT 1 FROM projects WHERE project_id = ? LIMIT 1")
        )
    }

    @Test
    fun mdbxLocalMutationsPublishWorkingCopyToSourceAfterCommit() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()

        listOf(
            source.substringAfter("suspend fun resolveConflict(")
                .substringBefore("suspend fun applyIncomingVaultFile("),
            source.substringAfter("suspend fun upsertAttachment(")
                .substringBefore("suspend fun deleteAttachment("),
            source.substringAfter("suspend fun deleteAttachment(")
                .substringBefore("private suspend fun upsertEntryMutations("),
            source.substringAfter("private suspend fun <T : Any> mutateEntriesByVault(")
                .substringBefore("suspend fun readStoredEntries(")
        ).forEach { mutationBody ->
            assertTrue(
                "User-visible MDBX mutations should publish the working copy to SAF/WebDAV before reporting success.",
                mutationBody.contains("markWorkingCopyDirtyAndFlush(dbInfo, file)") ||
                    mutationBody.contains("markWorkingCopyDirtyAndFlushLocked(dbInfo, file)")
            )
        }

        val dirtyAndFlushBody = source.substringAfter("private suspend fun markWorkingCopyDirtyAndFlush(")
            .substringBefore("private fun checkpointWorkingCopyForFlush(")
        assertTrue(
            "MDBX publish helper must first mark pending upload so a failed upload is visible.",
            dirtyAndFlushBody.contains("markWorkingCopyDirty(database)") &&
                dirtyAndFlushBody.contains("flushWorkingCopyToSourceIfNeeded(database, workingCopy)")
        )

        val flushBody = source.substringAfter("private suspend fun flushWorkingCopyToSourceIfNeeded(")
            .substringBefore("private suspend fun markWorkingCopyDirty(")
        assertTrue(
            "MDBX source publishing must checkpoint the working copy before copying or uploading the MDBX file.",
            flushBody.contains("checkpointWorkingCopyForFlush(database, workingCopy)")
        )
        assertTrue(
            "Failed external/WebDAV publishes must leave sync status visible instead of silently dropping the local commit.",
            source.contains("MdbxSyncStatus.PENDING_UPLOAD")
        )
    }

    @Test
    fun mdbxBatchMutationsKeepDirectLocalWriteContract() {
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxRepository.kt"
        ).readText()
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val passwordRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val passkeyRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasskeyRepository.kt"
        ).readText()

        assertTrue(
            "MDBX repository methods must report success only after local commit and source publish.",
            repositorySource.contains("commit the local .mdbx working copy") &&
                repositorySource.contains("publish") &&
                repositorySource.contains("next manual sync")
        )
        assertTrue(
            "MDBX repository should expose batch operations so bulk moves avoid opening the same vault repeatedly.",
            repositorySource.contains("suspend fun upsertPasswords(entries: List<PasswordEntry>)") &&
                repositorySource.contains("suspend fun deletePasswords(entries: List<PasswordEntry>)") &&
                repositorySource.contains("suspend fun upsertPasskeys(passkeys: List<PasskeyEntry>)") &&
                repositorySource.contains("suspend fun deletePasskeys(passkeys: List<PasskeyEntry>)")
        )

        val upsertEntryMutationBody = storeSource.substringAfter("private suspend fun upsertEntryMutations(")
            .substringBefore("private suspend fun deleteEntryMutations(")
        val deleteEntryMutationBody = storeSource.substringAfter("private suspend fun deleteEntryMutations(")
            .substringBefore("private suspend fun <T : Any> mutateEntriesByVault(")
        val batchedMutationBody = storeSource.substringAfter("private suspend fun <T : Any> mutateEntriesByVault(")
            .substringBefore("private fun mutationDatabaseId(")
        assertTrue(
            "Batched MDBX mutations must commit a local SQLite transaction and publish once per vault before returning.",
            batchedMutationBody.contains("openExistingWritableVault(file).use") &&
                batchedMutationBody.contains("db.beginTransaction()") &&
                upsertEntryMutationBody.contains("vaultMutations.forEach") &&
                deleteEntryMutationBody.contains("vaultMutations.forEach") &&
                batchedMutationBody.contains("writeMutations(db, vaultMutations, epochKey)") &&
                batchedMutationBody.contains("db.setTransactionSuccessful()") &&
                batchedMutationBody.contains("markWorkingCopyDirtyAndFlushLocked(dbInfo, file)")
        )

        assertTrue(
            "Password MDBX bulk moves should use the batch repository path.",
            passwordRepositorySource.contains("mdbxRepository?.upsertPasswords(entriesForMdbx)") &&
                passwordRepositorySource.contains("mdbxRepository?.deletePasswords(")
        )
        assertTrue(
            "Passkey MDBX bulk writes should use the batch repository path.",
            passkeyRepositorySource.contains("mdbxRepository?.upsertPasskeys(passkeysForMdbx)") &&
                passkeyRepositorySource.contains("mdbxRepository?.deletePasskeys(")
        )
    }

    @Test
    fun mdbxSecureItemsKeepStableReplicaIdsAcrossRefresh() {
        val secureItemRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        val insertBody = secureItemRepositorySource.substringAfter("suspend fun insertItem(item: SecureItem): Long")
            .substringBefore("suspend fun updateItem(item: SecureItem)")
        assertTrue(
            "New MDBX secure items must persist their object id back to replicaGroupId; otherwise a refresh imports the same TOTP again.",
            insertBody.contains("replicaGroupId = if (item.mdbxDatabaseId != null) item.mdbxObjectId(id) else item.replicaGroupId") &&
                insertBody.contains("secureItemDao.updateItem(persistedItem)") &&
                insertBody.contains("mdbxRepository?.upsertSecureItem(persistedItem)")
        )

        val importBody = mdbxViewModelSource.substringAfter("private suspend fun importEntriesFromVault(databaseId: Long)")
            .substringBefore("private suspend fun clearImportedEntries(databaseId: Long)")
        assertTrue(
            "MDBX refresh must also match legacy secure-item rows by prefix:id, so users who already created TOTP before this fix do not get duplicates.",
            importBody.contains(".mapNotNull { item -> item.mdbxPrimaryImportEntryId()?.let { entryId -> entryId to item } }") &&
                mdbxViewModelSource.contains("private fun SecureItem.mdbxLegacyEntryId(): String?") &&
                mdbxViewModelSource.contains("ItemType.TOTP -> \"totp\"") &&
                mdbxViewModelSource.contains("private fun SecureItem.mdbxPrimaryImportEntryId(): String?")
        )
    }

    @Test
    fun mdbxGitHistoryHasObjectVersionsDiffAndRevert() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val revertCommitBody = viewModelSource
            .substringAfter("fun revertCommit(")
            .substringBefore("fun resolveConflict(")

        assertTrue(
            "MDBX history must store per-object versions so Diff/Revert are real, not just commit metadata.",
            storeSource.contains("CREATE TABLE IF NOT EXISTS object_versions")
        )
        assertTrue(
            "Entry writes must record object version snapshots in the same transaction as the commit.",
            storeSource.substringAfter("private fun writeEntryMutation(")
                .substringBefore("private fun writeEntryDeleteMutation(")
                .contains("recordEntryVersion(")
        )
        assertTrue(
            "Entry deletes must also record a deleted version snapshot so deletes can be reverted.",
            storeSource.substringAfter("private fun writeEntryDeleteMutation(")
                .substringBefore("suspend fun readStoredEntries(")
                .contains("recordEntryVersion(")
        )
        assertTrue(
            "Incoming sync must copy object version history between clients.",
            storeSource.substringAfter("private fun copyIncomingHistory(")
                .substringBefore("private fun copyIncomingFolders(")
                .contains("FROM object_versions")
        )
        assertTrue(
            "Store must expose commit diff.",
            storeSource.contains("suspend fun listCommitDiff(")
        )
        assertTrue(
            "Store must expose revert as a new commit operation.",
            storeSource.contains("suspend fun revertCommit(") &&
                storeSource.contains("commitKind = \"revert\"")
        )
        assertTrue(
            "ViewModel must expose commit diff to the history UI.",
            viewModelSource.contains("fun showCommitDiff(")
        )
        assertTrue(
            "ViewModel must expose revert and rebuild the Room mirror from the reverted MDBX state without rescuing deleted rows.",
            viewModelSource.contains("fun revertCommit(") &&
                revertCommitBody.contains("importEntriesFromVault(") &&
                revertCommitBody.contains("databaseId,") &&
                revertCommitBody.contains("orphanPolicy = MdbxImportOrphanPolicy.APPLY_REMOTE_STATE")
        )
        assertTrue(
            "History UI must expose Diff and Revert controls.",
            managerSource.contains("onShowDiff") &&
                managerSource.contains("onRevert") &&
                managerSource.contains("CommitObjectChangeCard(diff)") &&
                managerSource.contains("Text(strings.get(R.string.mdbx_ui_history_revert_action))")
        )
    }

    @Test
    fun mdbxEntryConflictResolutionWritesBackThroughHistory() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()

        val resolveBody = storeSource.substringAfter("suspend fun resolveConflict(")
            .substringBefore("suspend fun applyIncomingVaultFile(")
        assertTrue(
            "Entry conflict buttons must use the dedicated writeback path, not just update conflict metadata.",
            resolveBody.contains("resolveEntryConflict(")
        )

        val entryResolveBody = storeSource.substringAfter("private fun resolveEntryConflict(")
            .substringBefore("private fun readEntryVersionForCommit(")
        assertTrue(
            "Local-wins entry conflict resolution must advance the entry head through a merge commit.",
            entryResolveBody.contains("MdbxConflictResolution.LOCAL_WINS") &&
                entryResolveBody.contains("commitKind = \"merge\"") &&
                entryResolveBody.contains("updateEntryHeadForResolvedConflict(")
        )
        assertTrue(
            "Incoming-wins entry conflict resolution must apply the incoming object_versions snapshot.",
            entryResolveBody.contains("MdbxConflictResolution.INCOMING_WINS") &&
                entryResolveBody.contains("readEntryVersionForCommit(") &&
                entryResolveBody.contains("applyResolvedEntryVersion(")
        )
        assertTrue(
            "Custom and mark-resolved must not silently discard unresolved entry conflicts.",
            entryResolveBody.contains("Custom MDBX conflict merge needs a merged payload") &&
                entryResolveBody.contains("Entry conflicts must choose local or incoming")
        )
        assertTrue(
            "Android conflict resolution strings must match the Rust MDBX schema values.",
            storeSource.contains("LOCAL_WINS(\"local-wins\")") &&
                storeSource.contains("INCOMING_WINS(\"incoming-wins\")") &&
                storeSource.contains("CUSTOM_MERGE(\"custom\")")
        )
    }

    @Test
    fun mdbxUnlockAndWalAvoidKnownWriteSlowdowns() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val cryptoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultCrypto.kt"
        ).readText()

        assertTrue(
            "MDBX should request WAL in SQLite open flags before Android can mutate journal mode on an already-open vault.",
            storeSource.contains("SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING") &&
                storeSource.contains("SQLiteDatabase.openDatabase(file.absolutePath, null, flags)")
        )
        assertFalse(
            "MDBX writable open must not use openOrCreateDatabase because Android may try to switch an existing WAL vault back to TRUNCATE before our PRAGMA runs.",
            storeSource.contains("openOrCreateDatabase(file")
        )
        assertTrue(
            "MDBX WAL should use synchronous=NORMAL to avoid full fsync cost on every write.",
            storeSource.contains("PRAGMA synchronous=NORMAL")
        )
        assertTrue(
            "MDBX user-visible writes and publish/checkpoint work should be serialized per vault file.",
            storeSource.contains("private val vaultWriteLocks") &&
                storeSource.contains("withVaultWriteLock(file)") &&
                storeSource.contains("markWorkingCopyDirtyAndFlushLocked(dbInfo, file)")
        )
        assertTrue(
            "Unlock should derive the credential key once, verify it, and unwrap the epoch key in one pass.",
            cryptoSource.contains("fun unlockEpochKey(")
        )
        val readEpochKeyBody = storeSource.substringAfter("private fun readEpochKeyOrNull(")
            .substringBefore("private fun readStoredCredential(")
        assertTrue(
            "Store should use the one-pass unlock path instead of verifyCredential + unwrapEpochKey.",
            readEpochKeyBody.contains("MdbxVaultCrypto.unlockEpochKey(")
        )
        assertFalse(
            "Store readEpochKeyOrNull must not run the KDF twice by separately calling verifyCredential.",
            readEpochKeyBody.contains("MdbxVaultCrypto.verifyCredential(")
        )
        assertFalse(
            "Store readEpochKeyOrNull must not run the KDF twice by separately calling unwrapEpochKey.",
            readEpochKeyBody.contains("MdbxVaultCrypto.unwrapEpochKey(")
        )
    }

    @Test
    fun mdbxHistoryAndSnapshotViewsUseStaleWhileRevalidateCache() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        assertTrue(
            "MDBX history and snapshot structure pages need in-memory display caches to avoid flashing empty while IO refreshes.",
            viewModelSource.contains("private val deltaHistoryCache = ConcurrentHashMap<Long, CachedDeltaHistory>()") &&
                viewModelSource.contains("private val structurePreviewCache =") &&
                viewModelSource.contains("data class CachedDeltaHistory(") &&
                viewModelSource.contains("data class SnapshotStructureCacheKey(")
        )

        val showDeltaHistoryBody = viewModelSource.substringAfter("fun showDeltaHistory(")
            .substringBefore("private fun invalidateMdbxViewCaches(")
        assertTrue(
            "History loading must render existing or cached rows before refreshing from MDBX.",
            showDeltaHistoryBody.contains("val sameDatabaseState = current?.takeIf { it.databaseId == database.id }") &&
                showDeltaHistoryBody.contains("val cached = deltaHistoryCache[database.id]") &&
                showDeltaHistoryBody.contains("deltas = sameDatabaseState?.deltas ?: cached?.deltas.orEmpty()") &&
                showDeltaHistoryBody.contains("snapshots = sameDatabaseState?.snapshots ?: cached?.snapshots.orEmpty()") &&
                showDeltaHistoryBody.contains("updateDeltaHistoryCache(") &&
                showDeltaHistoryBody.contains("[MDBX][perf][showDeltaHistory]")
        )
        assertFalse(
            "History loading must not rebuild a blank Visible state while waiting for MDBX IO.",
            showDeltaHistoryBody.contains("MdbxDeltaDialogState.Visible(\n                databaseId = database.id,\n                databaseName = database.name,\n                isLoading = true")
        )

        val showSnapshotStructureBody = viewModelSource.substringAfter("fun showSnapshotStructure(")
            .substringBefore("fun closeSnapshotStructure(")
        assertTrue(
            "Snapshot structure loading must keep the current tree or cached tree while refreshing.",
            showSnapshotStructureBody.contains("val cachedPreview = cachedStructurePreview(databaseId, snapshotId)") &&
                showSnapshotStructureBody.contains("current.structurePreview") &&
                showSnapshotStructureBody.contains("?: cachedPreview") &&
                showSnapshotStructureBody.contains("updateStructurePreviewCache(databaseId, snapshotId, preview)") &&
                showSnapshotStructureBody.contains("[MDBX][perf][showSnapshotStructure]")
        )
        assertFalse(
            "Snapshot structure loading must not clear the tree before the refreshed preview is available.",
            showSnapshotStructureBody.contains(
                "selectedStructureSnapshotId = snapshotId,\n                structurePreview = null"
            )
        )

        val importEntriesBody = viewModelSource.substringAfter("private suspend fun importEntriesFromVault(")
            .substringBefore("private suspend fun clearImportedEntries(")
        assertTrue(
            "MDBX imports and sync refreshes must invalidate display caches and log timing so slow paths are visible.",
            importEntriesBody.contains("invalidateMdbxViewCaches(databaseId)") &&
                importEntriesBody.contains("measureTimeMillis") &&
                importEntriesBody.contains("[MDBX][perf][importEntriesFromVault]")
        )
        assertTrue(
            "MDBX imports must update the Room display cache by stable entry ids instead of deleting all rows first, otherwise startup refresh flashes empty and reorders the list.",
            importEntriesBody.contains("getByMdbxDatabaseIdSync(databaseId)") &&
                importEntriesBody.contains("existing = existingPasswordsByEntryId[stored.entryId]") &&
                !importEntriesBody.contains("clearImportedEntries(databaseId)")
        )
        val importPasswordBody = viewModelSource.substringAfter("private suspend fun importPasswordEntry(")
            .substringBefore("private suspend fun importSecureItem(")
        assertTrue(
            "MDBX password imports must preserve the existing Room row identity and sort metadata when refreshing an already imported entry.",
            importPasswordBody.contains("existing: PasswordEntry?") &&
                importPasswordBody.contains("id = existing?.id ?: 0L") &&
                importPasswordBody.contains("createdAt = existing?.createdAt ?: Date()") &&
                importPasswordBody.contains("updatedAt = existing?.updatedAt ?: Date()") &&
                importPasswordBody.contains("sortOrder = existing?.sortOrder ?: 0")
        )
    }

    @Test
    fun mdbxActiveVaultPreloadOnlyWarmsTheSelectedDatabase() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val simpleMainSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val vaultV2Source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()
        val activePreloadBody = viewModelSource
            .substringAfter("fun preloadActiveMdbxDatabase(databaseId: Long)")
            .substringBefore("// --- WebDAV connection ---")

        assertTrue(
            "MDBX should remember the active vault across process restarts without adding a Room migration.",
            viewModelSource.contains("ACTIVE_VAULT_PREFS_NAME") &&
                viewModelSource.contains("ACTIVE_VAULT_ID_KEY") &&
                viewModelSource.contains("val activeMdbxDatabaseId: StateFlow<Long?>") &&
                viewModelSource.contains("fun activateMdbxDatabase(databaseId: Long)")
        )
        assertTrue(
            "MDBX active preload must be single-vault and cancellable, and only the selected MDBX2 vault may refresh its Room mirror.",
            viewModelSource.contains("private var activePreloadJob: Job? = null") &&
                viewModelSource.contains("private var activePreloadDatabaseId: Long? = null") &&
                viewModelSource.contains("activePreloadJob?.cancel()") &&
                viewModelSource.contains("fun preloadActiveMdbxDatabase(databaseId: Long)") &&
                activePreloadBody.contains("val database = databaseDao.getDatabaseById(databaseId)") &&
                activePreloadBody.contains("if (database.engineTypeEnum == MdbxEngineType.RUST_MDBX2)") &&
                activePreloadBody.contains("importEntriesFromVault(database.id)") &&
                activePreloadBody.contains("vaultStore.getVaultDiagnostics(database.id)") &&
                activePreloadBody.contains("vaultStore.listDeltaHistory(database.id)") &&
                activePreloadBody.contains("vaultStore.listSnapshots(database.id)") &&
                !activePreloadBody.contains("databases.forEach")
        )
        assertTrue(
            "MDBX manager should stay on the format-management hub and only activate vaults after the user opens or navigates to them.",
            managerSource.contains("initialMdbxManagerPage(initialDatabaseId, initialPage)") &&
                managerSource.contains("databaseId == null || initialPage == MdbxManagerInitialPage.HOME -> MdbxManagerPage.Hub") &&
                managerSource.contains("viewModel.activateMdbxDatabase(database.id)") &&
                managerSource.contains("viewModel.activateMdbxDatabase(db.id)")
        )
        assertFalse(
            "Opening MDBX format management must not auto-enter the remembered active vault detail page.",
            managerSource.contains("var restoredActivePage by rememberSaveable") ||
                managerSource.contains("MdbxManagerPage.Detail(activeDatabase.id, activeDatabase.managerSource())") ||
                managerSource.contains("viewModel.preloadActiveMdbxDatabase(activeDatabase.id)")
        )
        assertFalse(
            "MDBX manager must not refresh diagnostics for every configured database on every database-list update.",
            managerSource.contains("viewModel.refreshConflictCounts(databases)")
        )
        assertTrue(
            "Password list MDBX filters should also mark the selected vault active, so app startup restores/preloads the user's current vault instead of only working inside the manager.",
            simpleMainSource.contains("is CategoryFilter.MdbxDatabase -> filter.databaseId") &&
                simpleMainSource.contains("is CategoryFilter.MdbxFolderFilter -> filter.databaseId") &&
                simpleMainSource.contains("mdbxViewModel.activateMdbxDatabase(databaseId)") &&
                vaultV2Source.contains("selectedMdbxDatabaseId = remember(storageSelection)") &&
                vaultV2Source.contains("mdbxViewModel?.activateMdbxDatabase(databaseId)")
        )
    }

    @Test
    fun mdbxArchitectureCompletionExposesOplogBundlesExternalRefsAndBenchmarks() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        listOf(
            "CREATE TABLE IF NOT EXISTS oplog",
            "CREATE TABLE IF NOT EXISTS sync_bundles",
            "CREATE TABLE IF NOT EXISTS crypto_contexts",
            "CREATE TABLE IF NOT EXISTS mdbx_benchmarks"
        ).forEach { schema ->
            assertTrue("MDBX schema must include $schema.", storeSource.contains(schema))
        }

        val appendCommitBody = storeSource.substringAfter("private fun appendCommit(")
            .substringBefore("private fun insertOplog(")
        assertTrue(
            "Every MDBX commit must also append an oplog row.",
            appendCommitBody.contains("insertOplog(")
        )

        assertTrue(
            "MDBX store must export serialized sync bundles.",
            storeSource.contains("suspend fun exportSyncBundle(") &&
                storeSource.contains("\"monica-mdbx-sync-bundle-v1\"") &&
                storeSource.contains("payloadHash = sha256Hex")
        )
        assertTrue(
            "MDBX store must import sync bundles and apply entry object versions.",
            storeSource.contains("suspend fun importSyncBundle(") &&
                storeSource.contains("latestBundleEntryVersions(payload)") &&
                storeSource.contains("applyBundleEntryVersion(")
        )
        assertTrue(
            "MDBX sync bundle import must preserve local divergent edits as explicit conflicts.",
            storeSource.contains("private fun applyBundleEntryVersion(") &&
                storeSource.contains("isAncestor(db, localState.headCommitId, version.commitId)") &&
                storeSource.contains("isAncestor(db, version.commitId, localState.headCommitId)") &&
                storeSource.contains("insertIncomingConflict(") &&
                storeSource.contains("conflictCount = conflicts")
        )
        assertTrue(
            "External attachment refs must be first-class and content-hash bound.",
            storeSource.contains("suspend fun upsertExternalAttachmentRef(") &&
                storeSource.contains("'external-hash-ref'") &&
                storeSource.contains("external_uri_ct") &&
                storeSource.contains("External MDBX attachment requires a content hash")
        )
        assertTrue(
            "Crypto context metadata must record field AAD and key purpose.",
            storeSource.contains("private fun recordCryptoContext(") &&
                storeSource.contains("mdbx:v1:\$objectType:\$objectId:\$fieldName") &&
                storeSource.contains("key_purpose")
        )
        assertTrue(
            "MDBX benchmark harness must record latency and file delta.",
            storeSource.contains("suspend fun runBenchmark(") &&
                storeSource.contains("fileDeltaBytes") &&
                storeSource.contains("INSERT OR REPLACE INTO mdbx_benchmarks")
        )
        assertTrue(
            "Dirty external/WebDAV vault uploads need a batchable background entrypoint.",
            storeSource.contains("suspend fun flushPendingWorkingCopy(") &&
                viewModelSource.contains("fun flushPendingVaultUploads(") &&
                viewModelSource.contains("MdbxSyncStatus.PENDING_UPLOAD.name") &&
                viewModelSource.contains("private suspend fun runMdbxPendingUploadThroughCoordinator(") &&
                viewModelSource.contains("SyncTaskRunner.requestAndAwait(request)") &&
                viewModelSource.substringAfter("fun flushPendingVaultUploads(")
                    .substringBefore("fun showAdvancedTools(")
                    .contains("runMdbxPendingUploadThroughCoordinator(") &&
                !viewModelSource.substringAfter("fun flushPendingVaultUploads(")
                    .substringBefore("fun showAdvancedTools(")
                    .contains("vaultStore.flushPendingWorkingCopy(") &&
                viewModelSource.substringAfter("fun flushPendingVaultUpload(")
                    .substringBefore("private fun pendingUploadOperationState(")
                    .contains("runMdbxPendingUploadThroughCoordinator(") &&
                !viewModelSource.substringAfter("fun flushPendingVaultUpload(")
                    .substringBefore("private fun pendingUploadOperationState(")
                    .contains("vaultStore.flushPendingWorkingCopy(")
            )
    }

    @Test
    fun keepassCompatibilityRefreshEntrypointsUseSyncTaskRunner() {
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val noteViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/NoteViewModel.kt"
        ).readText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readText()
        val bankCardViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/BankCardViewModel.kt"
        ).readText()
        val documentViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DocumentViewModel.kt"
        ).readText()

        val passwordSyncEntrypoint = passwordViewModelSource
            .substringAfter("private fun syncKeePassDatabase(")
            .substringBefore("private suspend fun syncKeePassDatabaseNow(")
        assertTrue(
            "Password KeePass filter/manual refresh must run through SyncTaskRunner instead of launching a direct workspace scan.",
            passwordSyncEntrypoint.contains("SyncTaskRunner.request(") &&
                passwordSyncEntrypoint.contains("SyncTarget.KeePassCompatibilityIndex(") &&
                passwordSyncEntrypoint.contains("SyncItemKind.PASSWORD") &&
                passwordSyncEntrypoint.contains("SyncItemKind.TOTP") &&
                !passwordSyncEntrypoint.contains("viewModelScope.launch(Dispatchers.Default)")
        )

        val noteSyncEntrypoint = noteViewModelSource
            .substringAfter("fun syncKeePassNotes(")
            .substringBefore("private suspend fun syncKeePassNotesNow(")
        assertTrue(
            "Note KeePass filter refresh must run through SyncTaskRunner so rapid filter changes do not spawn parallel scans.",
            noteSyncEntrypoint.contains("SyncTaskRunner.request(") &&
                noteSyncEntrypoint.contains("SyncTarget.KeePassCompatibilityIndex(") &&
                noteSyncEntrypoint.contains("SyncItemKind.NOTE")
        )

        val totpSyncEntrypoint = totpViewModelSource
            .substringAfter("private fun syncKeePassTotp(")
            .substringBefore("private suspend fun syncKeePassTotpNow(")
        assertTrue(
            "TOTP KeePass filter refresh must run through SyncTaskRunner so repeated page/filter entry stays single-flight.",
            totpSyncEntrypoint.contains("SyncTaskRunner.request(") &&
                totpSyncEntrypoint.contains("SyncTarget.KeePassCompatibilityIndex(") &&
                totpSyncEntrypoint.contains("SyncItemKind.TOTP")
        )

        val cardAllEntrypoint = bankCardViewModelSource
            .substringAfter("fun syncAllKeePassCards(")
            .substringBefore("suspend fun syncAllKeePassCardsNow(")
        val cardSingleEntrypoint = bankCardViewModelSource
            .substringAfter("fun syncKeePassCards(")
            .substringBefore("suspend fun syncKeePassCardsNow(")
        assertTrue(
            "Bank-card KeePass compatibility refresh wrappers must also use SyncTaskRunner when called outside CardWalletScreen.",
            cardAllEntrypoint.contains("SyncTaskRunner.request(") &&
                cardAllEntrypoint.contains("SyncItemKind.BANK_CARD") &&
                cardSingleEntrypoint.contains("SyncTaskRunner.request(") &&
                cardSingleEntrypoint.contains("SyncItemKind.BANK_CARD")
        )

        val documentAllEntrypoint = documentViewModelSource
            .substringAfter("fun syncAllKeePassDocuments(")
            .substringBefore("suspend fun syncAllKeePassDocumentsNow(")
        val documentSingleEntrypoint = documentViewModelSource
            .substringAfter("fun syncKeePassDocuments(")
            .substringBefore("suspend fun syncKeePassDocumentsNow(")
        assertTrue(
            "Document KeePass compatibility refresh wrappers must also use SyncTaskRunner when called outside CardWalletScreen.",
            documentAllEntrypoint.contains("SyncTaskRunner.request(") &&
                documentAllEntrypoint.contains("SyncItemKind.DOCUMENT") &&
                documentSingleEntrypoint.contains("SyncTaskRunner.request(") &&
            documentSingleEntrypoint.contains("SyncItemKind.DOCUMENT")
        )
    }

    @Test
    fun keepassRemoteManualAndVisibleSyncShareCoordinatorQueue() {
        val localKeePassViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val remoteUploadWorkerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/workers/KeePassRemoteUploadWorker.kt"
        ).readText()
        val syncContractsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/sync/SyncContracts.kt"
        ).readText()

        val manualSyncEntrypoint = localKeePassViewModelSource
            .substringAfter("fun syncRemoteDatabase(")
            .substringBefore("fun autoSyncVisibleRemoteDatabase(")
        val visibleSyncEntrypoint = localKeePassViewModelSource
            .substringAfter("fun autoSyncVisibleRemoteDatabase(")
            .substringBefore("private suspend fun syncRemoteDatabaseInternal(")

        assertTrue(
            "Manual/silent KeePass remote sync must await SyncTaskRunner so UI buttons cannot race visible auto-sync for the same remote KDBX.",
            manualSyncEntrypoint.contains("SyncTaskRunner.requestAndAwait(") &&
                manualSyncEntrypoint.contains("SyncTarget.KeePassDatabase(databaseId)") &&
                manualSyncEntrypoint.contains("dedupeKey = SyncKey(VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY)") &&
                manualSyncEntrypoint.contains("SyncTrigger.MANUAL") &&
                manualSyncEntrypoint.contains("SyncTrigger.RETRY") &&
                manualSyncEntrypoint.contains("SyncPriority.MANUAL") &&
                manualSyncEntrypoint.contains("SyncPriority.REPAIR") &&
                manualSyncEntrypoint.contains("SyncNetworkPolicy.REQUIRED")
        )
        assertTrue(
            "Visible KeePass remote auto-sync must stay on the same coordinator dedupe queue as manual sync.",
            visibleSyncEntrypoint.contains("SyncTaskRunner.request(") &&
            visibleSyncEntrypoint.contains("dedupeKey = SyncKey(VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY)") &&
                visibleSyncEntrypoint.contains("SyncNetworkPolicy.REQUIRED")
        )
        assertTrue(
            "Background KeePass remote upload worker must share the same coordinator queue as foreground/visible remote sync.",
            syncContractsSource.contains("const val KEEPASS_REMOTE_SYNC_DEDUPE_KEY = \"keepass_visible_remote\"") &&
                localKeePassViewModelSource.contains("VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY = KEEPASS_REMOTE_SYNC_DEDUPE_KEY") &&
                remoteUploadWorkerSource.contains("SyncTaskRunner.requestAndAwait(") &&
                remoteUploadWorkerSource.contains("SyncTarget.KeePassDatabase(targetDatabaseId)") &&
                remoteUploadWorkerSource.contains("dedupeKey = SyncKey(KEEPASS_REMOTE_SYNC_DEDUPE_KEY)") &&
                remoteUploadWorkerSource.contains("SyncTrigger.WORKER_RECOVERY") &&
                remoteUploadWorkerSource.contains("SyncNetworkPolicy.REQUIRED")
        )
        assertTrue(
            "Background KeePass remote upload worker must be a single WorkManager drain task: KEEP avoids chain storms, and the worker loops pending databases itself.",
            remoteUploadWorkerSource.contains("while (drainSteps < MAX_DRAIN_STEPS)") &&
                remoteUploadWorkerSource.contains("resolveTargetDatabaseId(requestedDatabaseId, skippedDatabaseIds)") &&
                remoteUploadWorkerSource.contains("requestedDatabaseId = null") &&
                remoteUploadWorkerSource.contains("ExistingWorkPolicy.KEEP") &&
                !remoteUploadWorkerSource.contains("ExistingWorkPolicy.APPEND_OR_REPLACE") &&
                !remoteUploadWorkerSource.contains("private suspend fun enqueueNextPendingIfAny") &&
                !remoteUploadWorkerSource.substringAfter("is UploadStepResult.Completed ->")
                    .substringBefore("is UploadStepResult.Merged ->")
                    .contains("enqueueIfPending(applicationContext)")
        )
    }

    @Test
    fun keepassRemoteWritesStayVisibleToOtherClients() {
        val kdbxServiceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val webDavFileSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavKeePassFileSource.kt"
        ).readText()
        val oneDriveFileSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/OneDriveKeePassFileSource.kt"
        ).readText()
        val localKeePassViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val workspaceRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/KeePassWorkspaceRepository.kt"
        ).readText()
        val compatibilityBridgeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/KeePassCompatibilityBridge.kt"
        ).readText()
        val fileSourceContract = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassFileSource.kt"
        ).readText()
        val localKeePassDatabaseSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/LocalKeePassDatabase.kt"
        ).readText()
        val webDavBrowserSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/LocalKeePassWebDavBrowser.kt"
        ).readText()
        val oneDriveBrowserSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/LocalKeePassOneDriveBrowser.kt"
        ).readText()
        val googleDriveBrowserSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/LocalKeePassGoogleDriveBrowser.kt"
        ).readText()
        val writeDatabaseBody = kdbxServiceSource
            .substringAfter("private suspend fun writeDatabase(")
            .substringBefore("private fun keePassPendingChangeRepository(")
        val webDavWriteBody = webDavFileSource
            .substringAfter("override suspend fun write(")
            .substringBefore("override suspend fun listChildren(")
        val webDavCreateBody = webDavFileSource
            .substringAfter("suspend fun createFileInDirectory(")
            .substringBefore("private fun resolveResource(")
        val manualSyncBody = localKeePassViewModelSource
            .substringAfter("private suspend fun syncRemoteDatabaseInternal(")
            .substringBefore("private suspend fun handleSyncRemoteFailure(")
        val passwordSyncBody = passwordViewModelSource
            .substringAfter("private suspend fun syncKeePassDatabaseNow(")
            .substringBefore("private suspend fun refreshAllKeePassDatabases(")
        val writeExternalBytesBody = kdbxServiceSource
            .substringAfter("private fun writeExternalBytes(")
            .substringBefore("private fun openExternalOutputStream(")
        val openExternalOutputStreamBody = kdbxServiceSource
            .substringAfter("private fun openExternalOutputStream(")
            .substringBefore("suspend fun resolveRemoteConflict(")

        assertTrue(
            "Saving a remote KeePass working copy must try a foreground remote upload before falling back to WorkManager; otherwise Monica can show local-only data that other KeePass clients cannot see.",
            writeDatabaseBody.contains("val syncOutcome = syncRemoteWorkingCopy(") &&
                writeDatabaseBody.contains("if (syncOutcome != null)") &&
                writeDatabaseBody.contains("resolvedDatabase = syncOutcome.finalDatabase") &&
                writeDatabaseBody.contains("} else {") &&
                writeDatabaseBody.indexOf("val syncOutcome = syncRemoteWorkingCopy(") <
                    writeDatabaseBody.indexOf("markRemoteWritePending(database, bytes)") &&
                writeDatabaseBody.indexOf("markRemoteWritePending(database, bytes)") <
                    writeDatabaseBody.indexOf("enqueueRemoteWorkingCopyUpload(database.id)")
        )
        assertTrue(
            "Remote KeePass writes must read the remote bytes back and decode them before marking sync success, so a bad WebDAV/OneDrive write cannot be reported as synchronized.",
            kdbxServiceSource.contains("private suspend fun verifyRemoteKdbxWrite(") &&
                kdbxServiceSource.contains("val remoteBytes = fileSource.read()") &&
                kdbxServiceSource.contains("remoteHash != expectedHash") &&
                kdbxServiceSource.contains("decodeDatabase(") &&
                kdbxServiceSource.contains("Remote KDBX write verified") &&
                kdbxServiceSource.contains("Remote KDBX write verification failed") &&
                kdbxServiceSource.contains("val verifiedRemote = verifyRemoteKdbxWrite(")
        )
        assertTrue(
            "Manual KeePass remote sync must use the same read-back/decode verification before marking a WebDAV/OneDrive write synchronized.",
            kdbxServiceSource.contains("internal suspend fun verifyRemoteKdbxWrite(") &&
                kdbxServiceSource.contains("sourceLabel = \"service-sync-merge\"") &&
                kdbxServiceSource.contains("sourceLabel = \"service-sync-upload\"") &&
                kdbxServiceSource.contains("baseHash = verifiedRemote.hash") &&
                kdbxServiceSource.contains("workingHash = verifiedRemote.hash") &&
                manualSyncBody.contains("kdbxService.syncRemoteDatabase(databaseId)")
        )
        assertFalse(
            "LocalKeePassViewModel should not keep a second copy of remote hash/version merge logic; all remote manual, visible, and password-page refreshes must share KeePassKdbxService.syncRemoteDatabase.",
            manualSyncBody.contains("fileSource.read()") ||
                manualSyncBody.contains("knownRemoteVersion") ||
                manualSyncBody.contains("sourceLabel = \"manual-sync-")
        )
        assertTrue(
            "Manual/visible KeePass remote refresh must compare the actual downloaded remote KDBX hash, not only provider etag/version. Some WebDAV providers return unchanged or empty version metadata, which made Monica B miss Monica A's writes.",
            kdbxServiceSource.contains("suspend fun syncRemoteDatabase(databaseId: Long): Result<KeePassRemoteSyncResult>") &&
                kdbxServiceSource.contains("val remoteBytes = fileSource.read()") &&
                kdbxServiceSource.contains("val remoteHash = GoogleDriveKeePassSupport.sha256Hex(remoteBytes)") &&
                kdbxServiceSource.contains("val remoteHasChanges =") &&
                kdbxServiceSource.contains("baseHash != remoteHash") &&
                kdbxServiceSource.contains("localChanged=${'$'}localHasChanges remoteChanged=${'$'}remoteHasChanges") &&
                kdbxServiceSource.indexOf("val remoteBytes = fileSource.read()") <
                    kdbxServiceSource.indexOf("if (remoteHasChanges)") &&
                !manualSyncBody.contains("knownRemoteVersion != currentRemoteVersion")
        )
        assertTrue(
            "Password-page KeePass force refresh must pull the remote working copy before rebuilding the Room projection. Otherwise Monica B can keep showing stale cached entries until it performs a local write.",
            workspaceRepositorySource.contains("suspend fun syncRemoteDatabase(databaseId: Long)") &&
                compatibilityBridgeSource.contains("suspend fun syncLegacyRemoteDatabase(databaseId: Long)") &&
                passwordSyncBody.contains("if (forceRefresh)") &&
                passwordSyncBody.contains("bridge.syncLegacyRemoteDatabase(databaseId)") &&
                passwordSyncBody.indexOf("bridge.syncLegacyRemoteDatabase(databaseId)") <
                    passwordSyncBody.indexOf(".loadLegacyWorkspace(databaseId")
        )
        assertTrue(
            "KeePass WebDAV writes should use a compatibility-first direct PUT. Hidden temp-file MOVE overwrites are rejected by common providers and leave only Monica's local working copy updated.",
            webDavWriteBody.contains("sardine.put(remoteUrl, bytes, KEEPASS_KDBX_MIME_TYPE)") &&
                webDavCreateBody.contains("sardine.put(targetUrl, bytes, KEEPASS_KDBX_MIME_TYPE)") &&
                !webDavFileSource.contains("sardine.move(") &&
                !webDavFileSource.contains("buildSiblingTempPath(") &&
                !webDavFileSource.contains(".monica-tmp")
        )
        assertTrue(
            "Remote KeePass providers should upload KDBX bytes with the KeePass MIME type instead of generic octet-stream, so cloud document providers keep the file editable for other clients.",
            fileSourceContract.contains("const val KEEPASS_KDBX_MIME_TYPE = \"application/x-keepass2\"") &&
                webDavFileSource.contains("KEEPASS_KDBX_MIME_TYPE") &&
                oneDriveFileSource.contains("contentType: String = KEEPASS_KDBX_MIME_TYPE") &&
                oneDriveFileSource.contains("toRequestBody(KEEPASS_KDBX_MIME_TYPE.toMediaType())")
        )
        assertTrue(
            "New remote KeePass databases should default to the broadest compatibility profile for Monica features: KDBX4 + AES cipher + AES-KDF rounds. Argon2 remains available only through advanced options.",
            localKeePassDatabaseSource.contains("const val DEFAULT_AES_KDF_ROUNDS = 600_000L") &&
                localKeePassDatabaseSource.contains("fun remoteCompatibilityDefaults()") &&
                localKeePassDatabaseSource.contains("formatVersion = KeePassFormatVersion.KDBX4") &&
                localKeePassDatabaseSource.contains("kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF") &&
                localKeePassDatabaseSource.contains("transformRounds = DEFAULT_AES_KDF_ROUNDS") &&
                webDavBrowserSource.contains("val defaultOptions = remember { KeePassDatabaseCreationOptions.remoteCompatibilityDefaults() }") &&
                oneDriveBrowserSource.contains("val defaultOptions = remember { KeePassDatabaseCreationOptions.remoteCompatibilityDefaults() }") &&
                googleDriveBrowserSource.contains("creationOptions = KeePassDatabaseCreationOptions.remoteCompatibilityDefaults()") &&
                !googleDriveBrowserSource.contains("creationOptions = KeePassDatabaseCreationOptions(),")
        )
        assertTrue(
            "When switching remote-create KDF algorithms, the rounds field should follow the algorithm default only while it is still untouched; AES-KDF must never inherit Argon2's 8 iterations.",
            localKeePassDatabaseSource.contains("fun defaultTransformRoundsFor(kdfAlgorithm: KeePassKdfAlgorithm): Long") &&
                webDavBrowserSource.contains("defaultTransformRoundsFor(kdfAlgorithm)") &&
                webDavBrowserSource.contains("defaultTransformRoundsFor(it)") &&
                oneDriveBrowserSource.contains("defaultTransformRoundsFor(kdfAlgorithm)") &&
                oneDriveBrowserSource.contains("defaultTransformRoundsFor(it)")
        )
        assertTrue(
            "SAF-backed KeePass writes should prefer provider-friendly truncate-write mode like mature KeePass clients, falling back to rwt only when wt is unavailable.",
            writeExternalBytesBody.contains("openExternalOutputStream(uri)?.use") &&
                openExternalOutputStreamBody.indexOf("openOutputStream(uri, \"wt\")") <
                    openExternalOutputStreamBody.indexOf("openOutputStream(uri, \"rwt\")") &&
                !kdbxServiceSource.contains("private fun openExternalFileDescriptor(")
        )
    }

    @Test
    fun mdbxSnapshotsExposeSingleFileBackupHistoryAndRollback() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()

        assertTrue(
            "MDBX snapshots need a summary model for the UI and diagnostics.",
            storeSource.contains("data class MdbxSnapshotSummary")
        )
        listOf(
            "ensureColumn(db, \"snapshots\", \"name\"",
            "ensureColumn(db, \"snapshots\", \"snapshot_type\"",
            "ensureColumn(db, \"snapshots\", \"is_full\"",
            "ensureColumn(db, \"snapshots\", \"auto_prune\"",
            "ensureColumn(db, \"snapshots\", \"payload_bytes\""
        ).forEach { schemaGuard ->
            assertTrue("MDBX snapshot schema must include $schemaGuard.", storeSource.contains(schemaGuard))
        }
        listOf(
            "suspend fun listSnapshots(",
            "suspend fun createSnapshot(",
            "suspend fun deleteSnapshot(",
            "suspend fun revertToSnapshot(",
            "suspend fun pruneAutomaticSnapshots("
        ).forEach { api ->
            assertTrue("MDBX store must expose snapshot API $api.", storeSource.contains(api))
        }

        val appendCommitBody = storeSource.substringAfter("private fun appendCommit(")
            .substringBefore("private fun insertOplog(")
        assertTrue(
            "Every local commit should create a cheap automatic delta snapshot anchor.",
            appendCommitBody.contains("createSnapshotLocked(") &&
                appendCommitBody.contains("fullSnapshot = false") &&
                appendCommitBody.contains("autoPrune = true")
        )
        assertTrue(
            "Automatic snapshots must be pruned by retention policy.",
            storeSource.contains("private fun pruneAutomaticSnapshotsLocked(") &&
                storeSource.contains("private fun automaticSnapshotRetention(")
        )
        assertTrue(
            "Delta snapshot rollback should reconstruct entry state from object_versions at the base commit.",
            storeSource.contains("private fun revertToCommitState(") &&
                storeSource.contains("readLatestEntryVersionsAtCommit(db, baseCommitId)")
        )

        listOf(
            "fun createSnapshot(",
            "fun deleteSnapshot(",
            "fun revertToSnapshot(",
            "fun pruneAutomaticSnapshots("
        ).forEach { api ->
            assertTrue("ViewModel must expose snapshot action $api.", viewModelSource.contains(api))
        }
        assertTrue(
            "Delta dialog state must carry snapshot rows and loading state.",
            viewModelSource.contains("val snapshots: List<MdbxSnapshotSummary>") &&
                viewModelSource.contains("val isSnapshotLoading: Boolean")
        )
        assertTrue(
            "Snapshot rollback must re-import MDBX entries into the local UI tables.",
            viewModelSource.substringAfter("fun revertToSnapshot(")
                .substringBefore("fun pruneAutomaticSnapshots(")
                .contains("importEntriesFromVault(")
        )

        assertTrue(
            "MDBX manager must expose a snapshot management panel.",
            managerSource.contains("MdbxSnapshotPage(") &&
                managerSource.contains("SnapshotCreationCard(") &&
                managerSource.contains("SnapshotRow(") &&
                managerSource.contains("onCreateSnapshot") &&
                managerSource.contains("onRevertSnapshot") &&
                managerSource.contains("onPruneAutomaticSnapshots")
        )
        assertTrue(
            "Snapshot UI must let the user choose increment versus full snapshot.",
            managerSource.contains("fullSnapshot") &&
                managerSource.contains("R.string.mdbx_ui_snapshot_incremental") &&
                managerSource.contains("R.string.mdbx_ui_snapshot_full")
        )
    }

    @Test
    fun mdbxSnapshotRollbackAppliesExactSnapshotStateWithoutOrphanRescue() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        val rollbackStoreBody = storeSource.substringAfter("private fun revertToEntryVersionSet(")
            .substringBefore("private fun pruneAutomaticSnapshotsLocked(")
        assertTrue(
            "Snapshot rollback must restore an exact entry set; entries created after the snapshot cannot remain active.",
            rollbackStoreBody.contains("targetVersionsById") &&
                rollbackStoreBody.contains("current.objectId !in targetVersionsById") &&
                rollbackStoreBody.contains("markEntryDeletedBySnapshotRevert(")
        )

        val rollbackViewModelBody = viewModelSource.substringAfter("fun revertToSnapshot(")
            .substringBefore("fun pruneAutomaticSnapshots(")
        assertTrue(
            "Rollback imports must apply the snapshot state instead of rescuing local active orphan rows back into MDBX.",
            rollbackViewModelBody.contains("MdbxImportOrphanPolicy.APPLY_REMOTE_STATE") &&
                viewModelSource.contains("MdbxImportOrphanPolicy.RESCUE_LOCAL_ACTIVE") &&
                viewModelSource.contains("applyRemoteStateToOrphanedMdbxPasswordRows(") &&
                viewModelSource.contains("applyRemoteStateToOrphanedMdbxSecureItemRows(") &&
                viewModelSource.contains("applyRemoteStateToOrphanedMdbxPasskeys(")
        )
    }

    @Test
    fun mdbxManagerLivesUnderDatabaseBackupAndUsesStandalonePages() {
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val syncBackupSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/SyncBackupScreen.kt"
        ).readText()
        val mainActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()

        assertTrue(
            "Database and backup settings must expose MDBX as its own category.",
            syncBackupSource.contains("onNavigateToMdbx") &&
                syncBackupSource.contains("SyncBackupSection(title = \"MDBX\")") &&
                syncBackupSource.contains("MDBX 数据库管理") &&
                mainActivitySource.contains("onNavigateToMdbx = {") &&
                mainActivitySource.contains("navController.navigate(Screen.MdbxManager.createRoute())")
        )
        assertTrue(
            "MDBX format-management entry must discard any old MDBX manager back stack state so it always lands on the MDBX hub.",
            mainActivitySource.contains("popUpTo(Screen.MdbxManager.routePattern) { inclusive = true }") &&
                mainActivitySource.contains("launchSingleTop = true")
        )
        assertTrue(
            "MDBX manager should open to a hub and then branch into local, WebDAV, and OneDrive management pages.",
            managerSource.contains("MdbxManagerHubPage(") &&
                managerSource.contains("R.string.mdbx_ui_manager_local_title") &&
                managerSource.contains("R.string.mdbx_ui_manager_webdav_title") &&
                managerSource.contains("R.string.mdbx_ui_manager_onedrive_title") &&
                managerSource.contains("MdbxManagerSource.LOCAL") &&
                managerSource.contains("MdbxManagerSource.WEBDAV") &&
                managerSource.contains("MdbxManagerSource.ONEDRIVE")
        )
        assertTrue(
            "MDBX source pages should stay list-first like KeePass management and open databases as standalone detail pages.",
            managerSource.contains("MdbxSourceManagementPage(") &&
                managerSource.contains("MdbxVaultSmallCard(") &&
                managerSource.contains("MdbxVaultDetailPage(") &&
                managerSource.contains("page = MdbxManagerPage.Detail")
        )
        assertTrue(
            "MDBX conflict, snapshot, commit history, and maintenance must be standalone manager subpages instead of transient sheets.",
            managerSource.contains("MdbxConflictPage(") &&
                managerSource.contains("MdbxSnapshotPage(") &&
                managerSource.contains("MdbxCommitHistoryPage(") &&
                managerSource.contains("MdbxMaintenancePage(") &&
                managerSource.contains("BackHandler(") &&
                managerSource.contains("MdbxManagerPage.Conflict") &&
                managerSource.contains("MdbxManagerPage.Snapshots") &&
                managerSource.contains("MdbxManagerPage.CommitHistory") &&
                managerSource.contains("MdbxManagerPage.Maintenance")
        )
        assertFalse(
            "MDBX manager must not expose the developer advanced tools as a normal user subpage.",
            managerSource.contains("MdbxManagerPage.Advanced")
        )
        assertFalse(
            "MDBX detail page must not expose a user-facing advanced tools action.",
            managerSource.contains("onShowAdvanced")
        )
        assertTrue(
            "MDBX detail page must expose a diagnostics and maintenance page for format upgrade troubleshooting.",
            managerSource.contains("R.string.mdbx_ui_manager_maintenance_title") &&
                managerSource.contains("onShowMaintenance") &&
                managerSource.contains("onRefreshDiagnostics") &&
                managerSource.contains("onFlushPendingUpload") &&
                managerSource.contains("MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_key_metrics))") &&
                managerSource.contains("MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_advanced_details))")
        )
    }

    @Test
    fun mdbxAdvancedControlsRemainInternalAndHiddenFromAndroidManager() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()

        listOf(
            "suspend fun exportSyncBundle(",
            "suspend fun importSyncBundle(",
            "suspend fun flushPendingWorkingCopy(",
            "suspend fun runBenchmark(",
            "CREATE TABLE IF NOT EXISTS attachment_chunks",
            "suspend fun upsertExternalAttachmentRef("
        ).forEach { api ->
            assertTrue("MDBX store must keep advanced capability $api.", storeSource.contains(api))
        }

        listOf(
            "val advancedDialogState",
            "fun showAdvancedTools(",
            "fun dismissAdvancedTools(",
            "fun exportSyncBundle(",
            "fun importSyncBundleFromJson(",
            "fun flushPendingVaultUpload(",
            "fun runBenchmark(",
            "syncBundleToExportJson(",
            "parseSyncBundleExportJson("
        ).forEach { api ->
            assertTrue("MdbxViewModel must expose Android advanced control $api.", viewModelSource.contains(api))
        }
        assertTrue(
            "Bundle import should refresh local UI tables and diagnostics after applying oplog payloads.",
            viewModelSource.substringAfter("fun importSyncBundleFromJson(")
                .substringBefore("fun flushPendingVaultUpload(")
                .contains("importEntriesFromVault(databaseId)") &&
                viewModelSource.substringAfter("fun importSyncBundleFromJson(")
                    .substringBefore("fun flushPendingVaultUpload(")
                    .contains("getVaultDiagnostics(databaseId)")
        )

        assertFalse(
            "MDBX detail page must keep developer advanced controls out of the normal user navigation.",
            managerSource.contains("onShowAdvanced")
        )
        assertFalse(
            "MDBX manager route model must not include a user-facing advanced tools subpage.",
            managerSource.contains("MdbxManagerPage.Advanced")
        )
        assertTrue(
            "Android manager may keep internal controls for bundle export/import, upload flush, chunk status, and benchmark.",
            managerSource.contains("MdbxAdvancedToolsPage(") &&
                managerSource.contains("onExportBundle") &&
                managerSource.contains("onImportBundle") &&
                managerSource.contains("onFlushPendingUpload") &&
                managerSource.contains("onRunBenchmark") &&
                managerSource.contains("R.string.mdbx_ui_chunk_verification") &&
                managerSource.contains("R.string.mdbx_ui_attachment_storage_format") &&
                managerSource.contains("benchmark")
        )
        assertTrue(
            "Android manager must expose later MDBX diagnostics in a standalone maintenance page.",
            managerSource.contains("MdbxMaintenancePage(") &&
                managerSource.contains("MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_key_metrics))") &&
                managerSource.contains("MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_advanced_details))") &&
                managerSource.contains("R.string.mdbx_ui_dangling_parents") &&
                managerSource.contains("R.string.mdbx_ui_dangling_heads") &&
                managerSource.contains("R.string.mdbx_ui_attachment_chunk_issues") &&
                managerSource.contains("R.string.mdbx_ui_attachment_storage_format") &&
                managerSource.contains("R.string.mdbx_ui_upload_pending")
        )
        assertTrue(
            "Exported sync bundles should be copyable from the Android UI.",
            managerSource.contains("ClipboardUtils.copyToClipboard") &&
                managerSource.contains("MDBX sync bundle")
        )
    }

    @Test
    fun mdbxDatabaseViewsExposePathNavigationAndSyncAction() {
        val vaultV2Source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()
        val passwordListContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt"
        ).readText()
        val quickFolderSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSupport.kt"
        ).readText()
        val quickFolderSectionsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSections.kt"
        ).readText()
        val passwordListMainPaneSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListMainPane.kt"
        ).readText()
        val topActionsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordTopActionsMenu.kt"
        ).readText()
        val passwordListTopSectionSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListTopSection.kt"
        ).readText()
        val quickStatusBarSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/QuickStatusBar.kt"
        ).readText()
        val mdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val simpleMainSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val compactTabsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/CompactDraggableTabContent.kt"
        ).readText()

        assertTrue(
            "VaultV2 must receive MdbxViewModel so the password-list menu can run the same sync path as the MDBX manager.",
            vaultV2Source.contains("mdbxViewModel: MdbxViewModel? = null")
        )
        assertTrue(
            "VaultV2 must resolve the selected MDBX database from the storage filter.",
            vaultV2Source.contains("val selectedMdbxDatabaseId = remember(storageSelection)") &&
                vaultV2Source.contains("is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> storageSelection.databaseId")
        )
        assertTrue(
            "VaultV2 top-right menu must expose MDBX sync and call syncVault for the selected vault.",
            vaultV2Source.contains("MdbxSyncTopActionsMenuItem(") &&
                vaultV2Source.contains("mdbxViewModel.syncVault(selectedMdbxDatabaseId)")
        )
        assertTrue(
            "MDBX selected database views should auto-sync on entry so another client's published writes are pulled without visiting settings.",
            mdbxViewModelSource.contains("fun autoSyncVisibleVault(") &&
                mdbxViewModelSource.contains("database.lastSyncStatus != MdbxSyncStatus.PENDING_UPLOAD.name") &&
                passwordListContentSource.contains("autoSyncVisibleVault(selectedId)") &&
                vaultV2Source.contains("autoSyncVisibleVault(databaseId)")
        )
        assertTrue(
            "MDBX database pages must participate in the same path breadcrumb builder as KeePass and Bitwarden pages.",
            quickFolderSource.contains("is CategoryFilter.MdbxDatabase,") &&
                quickFolderSource.contains("root_mdbx_") &&
                quickFolderSource.contains("mdbxDatabases.find { it.id == filter.databaseId }?.name")
        )
        assertTrue(
            "Password list and VaultV2 must pass MDBX databases into breadcrumb building so the current path shows the vault name.",
            passwordListContentSource.contains("mdbxDatabases = mdbxDatabases") &&
                vaultV2Source.contains("mdbxDatabases = mdbxDatabases")
        )
        assertTrue(
            "The user-facing MDBX menu action should say sync, not refresh.",
            topActionsSource.contains("MdbxSyncTopActionsMenuItem") &&
                topActionsSource.contains("同步 MDBX 数据库") &&
                !topActionsSource.contains("\"${'$'}{stringResource(R.string.refresh)} MDBX\"")
        )
        assertTrue(
            "MDBX database pages must expose sync as a quick status action beside, not inside, the breadcrumb path.",
            quickStatusBarSource.contains("fun QuickStatusBar(") &&
                quickStatusBarSource.contains("indicator: @Composable RowScope.() -> Unit") &&
                quickStatusBarSource.contains("breadcrumb: @Composable RowScope.() -> Unit") &&
                quickStatusBarSource.contains("actions: @Composable RowScope.() -> Unit") &&
                vaultV2Source.contains("private fun VaultV2QuickStatusBar(") &&
                vaultV2Source.contains("QuickStatusBar(") &&
                vaultV2Source.contains("VaultV2BreadcrumbPath(") &&
                vaultV2Source.contains("MdbxPathSyncActions(state = state)") &&
                !vaultV2Source.contains("private fun VaultV2NavigationBanner(")
        )
        assertTrue(
            "The shared MDBX quick status action must still show pending writes and an icon-only sync button.",
            quickFolderSectionsSource.contains("data class MdbxPathSyncState") &&
                quickFolderSectionsSource.contains("fun MdbxPathSyncActions") &&
                quickFolderSectionsSource.contains("mdbxPathPendingSyncCount") &&
                quickFolderSectionsSource.contains("AnimatedVisibility") &&
                quickFolderSectionsSource.contains("expandHorizontally") &&
                quickFolderSectionsSource.contains("shrinkHorizontally") &&
                quickFolderSectionsSource.contains("未同步${'$'}{state.pendingCount}条")
        )
        assertTrue(
            "The MDBX unsynced chip must use store diagnostics instead of a hard-coded status-only count.",
            mdbxStoreSource.contains("val pendingSyncCount: Int") &&
                mdbxStoreSource.contains("calculatePendingSyncCount(") &&
                mdbxStoreSource.contains("queryPendingLocalOperationCount(") &&
                mdbxViewModelSource.contains("val pendingSyncCounts") &&
                passwordListContentSource.contains("pendingSyncCounts") &&
                vaultV2Source.contains("val mdbxPendingSyncCounts")
        )
        assertTrue(
            "The MDBX sync action must stay a circular icon-only button that is always available on MDBX pages.",
            quickFolderSectionsSource.contains("shape = CircleShape") &&
                quickFolderSectionsSource.contains("IconButton(") &&
                quickFolderSectionsSource.contains("imageVector = Icons.Default.Sync") &&
                quickFolderSectionsSource.contains("contentDescription = \"同步 MDBX 数据库\"") &&
                !quickFolderSectionsSource.contains("TextButton")
        )
        assertTrue(
            "The quick status bar must let the breadcrumb path yield width to status actions when pending status appears.",
            vaultV2Source.contains("modifier = Modifier.weight(1f)") &&
                quickFolderSectionsSource.contains(".height(36.dp)") &&
                quickFolderSectionsSource.contains(".width(104.dp)") &&
                quickFolderSectionsSource.contains("animateContentSize") &&
                quickFolderSectionsSource.contains("expandFrom = Alignment.End")
        )
        assertTrue(
            "Password list must pass the MDBX path sync state into its breadcrumb banner.",
            passwordListContentSource.contains("mdbxPathSyncState = mdbxPathSyncState") &&
                passwordListMainPaneSource.contains("mdbxSyncState = mdbxPathSyncState")
        )
        assertTrue(
            "The MDBX quick status sync button must flush pending uploads first and otherwise run the normal vault sync.",
            passwordListContentSource.contains("flushPendingVaultUpload(database.id)") &&
                passwordListContentSource.contains("syncVault(database.id)") &&
                vaultV2Source.contains("flushPendingVaultUpload(database.id)") &&
                vaultV2Source.contains("syncVault(database.id)")
        )
        assertTrue(
            "The top-right MDBX sync menu must share the same pending-upload-first behavior as the path sync button.",
            passwordListTopSectionSource.contains("selectedMdbxDatabase?.mdbxPathShouldFlushPendingUpload() == true") &&
                passwordListTopSectionSource.contains("flushPendingVaultUpload(selectedMdbxDatabaseId)") &&
                vaultV2Source.contains("selectedMdbxDatabase?.mdbxPathShouldFlushPendingUpload() == true") &&
                vaultV2Source.contains("flushPendingVaultUpload(selectedMdbxDatabaseId)")
        )
        assertTrue(
            "All VaultV2 hosts must pass through the shared MdbxViewModel.",
            simpleMainSource.contains("mdbxViewModel = mdbxViewModel") &&
                compactTabsSource.contains("mdbxViewModel = mdbxViewModel")
        )
    }

    @Test
    fun quickFilterChipsMorphToSelectedShapeWhilePressed() {
        val expressiveChipSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/MonicaExpressiveFilterChip.kt"
        ).readText()
        val quickFilterChipSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFilterChips.kt"
        ).readText()

        assertTrue(
            "Quick filter chips should use the shared expressive chip implementation so pressed/selected shape behavior stays consistent.",
            quickFilterChipSource.contains("MonicaExpressiveFilterChip(") &&
                quickFilterChipSource.contains("interactionSource = interactionSource")
        )
        assertTrue(
            "A non-selected quick filter chip should morph to the selected chip corner radius while pressed, matching Material Expressive state continuity.",
            expressiveChipSource.contains("collectIsPressedAsState()") &&
                expressiveChipSource.contains("val targetCornerRadius = if (selected || isPressed) 12.dp else 20.dp") &&
                expressiveChipSource.contains("animateDpAsState(") &&
                expressiveChipSource.contains("label = \"monicaExpressiveFilterChipCornerRadius\"")
        )
    }

    @Test
    fun mdbxCreatedFoldersAreLoadedIntoPasswordCategoryMenus() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val quickFolderSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSupport.kt"
        ).readText()
        val listContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt"
        ).readText()
        val bottomSheetSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/UnifiedCategoryFilterBottomSheet.kt"
        ).readText()
        val chipMenuSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/UnifiedCategoryFilterChipMenu.kt"
        ).readText()
        val topSectionSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListTopSection.kt"
        ).readText()
        val vaultV2Source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()

        assertTrue(
            "Password filtering must represent a concrete MDBX folder, not only the MDBX database root.",
            viewModelSource.contains("data class MdbxFolderFilter(val databaseId: Long, val folderId: String)") &&
                bottomSheetSource.contains("data class MdbxFolderFilter(val databaseId: Long, val folderId: String)")
        )
        assertTrue(
            "Creating an MDBX folder must refresh the cached folder list so menus show the new folder immediately.",
            viewModelSource.contains("private val _mdbxFoldersByDatabase") &&
                viewModelSource.contains("fun getMdbxFolders(databaseId: Long)") &&
                viewModelSource.contains("fun refreshMdbxFolders(databaseId: Long)") &&
                viewModelSource.substringAfter("fun createMdbxFolder(")
                    .substringBefore("fun updateCategory(")
                    .contains("refreshMdbxFolders(databaseId)") &&
                !viewModelSource.substringAfter("fun createMdbxFolder(")
                    .substringBefore("fun updateCategory(")
                    .contains("result.onSuccess")
        )
        assertTrue(
            "Password list must load MDBX folders for the selected MDBX database or folder filter.",
            listContentSource.contains("is CategoryFilter.MdbxFolderFilter -> filter.databaseId") &&
                listContentSource.contains("selectedMdbxDatabaseId?.let(viewModel::getMdbxFolders)") &&
                listContentSource.contains("viewModel.refreshMdbxFolders(selectedId)")
        )
        assertTrue(
            "Quick-folder builders must receive MDBX folders and navigate to MdbxFolderFilter targets.",
            quickFolderSource.contains("selectedMdbxFolders: List<MdbxStoredFolderEntry>") &&
                quickFolderSource.contains("buildMdbxFolderQuickFolderShortcuts") &&
                quickFolderSource.contains("currentParentFolderId: String? = null") &&
                quickFolderSource.contains(".filter { folder -> folder.isDirectMdbxChildOf(currentParentFolderId) }") &&
                quickFolderSource.contains("val currentFolderId = (filter as? CategoryFilter.MdbxFolderFilter)?.folderId") &&
                quickFolderSource.contains("currentParentFolderId = currentFolderId") &&
                quickFolderSource.contains("internal fun MdbxStoredFolderEntry.isDirectMdbxChildOf(parentFolderId: String?)") &&
                quickFolderSource.contains("targetFilter = CategoryFilter.MdbxFolderFilter(databaseId, folder.folderId)") &&
                quickFolderSource.contains("is CategoryFilter.MdbxFolderFilter -> true") &&
                quickFolderSource.contains("keyPrefix = \"back_mdbx\"") &&
                quickFolderSource.contains("keyPrefix = \"menu_back_mdbx\"") &&
                quickFolderSource.contains("if (currentFolderId != null)") &&
                !quickFolderSource.contains("includeBackNavigation && currentFolderId != null") &&
                quickFolderSource.contains("val backTarget = parentFolderId?.let { CategoryFilter.MdbxFolderFilter(databaseId, it) }") &&
                quickFolderSource.contains("?: CategoryFilter.MdbxDatabase(databaseId)")
        )
        assertTrue(
            "MDBX breadcrumbs and titles must walk the full parent folder chain, so a/b/c does not collapse to a/c.",
            quickFolderSource.contains("internal data class MdbxFolderPathSegment(") &&
                quickFolderSource.contains("internal fun buildMdbxFolderPathSegments(") &&
                quickFolderSource.contains("var currentId: String? = folderId.trim().takeIf { it.isNotBlank() }") &&
                quickFolderSource.contains("currentId = folder?.parentFolderId.normalizedMdbxParentId()") &&
                quickFolderSource.contains("return segments.asReversed()") &&
                quickFolderSource.contains("val segments = buildMdbxFolderPathSegments(filter.folderId, selectedMdbxFolders)") &&
                quickFolderSource.contains("targetFilter = CategoryFilter.MdbxFolderFilter(filter.databaseId, segment.folderId)") &&
                quickFolderSource.contains("internal fun buildMdbxFolderPathLabel(") &&
                topSectionSource.contains("is CategoryFilter.MdbxFolderFilter -> buildMdbxFolderPathLabel(filter.folderId, selectedMdbxFolders)") &&
                vaultV2Source.contains("import takagi.ru.monica.ui.buildMdbxFolderPathLabel") &&
                vaultV2Source.contains("val folderLabel = buildMdbxFolderPathLabel(selected.folderId, mdbxFolders)")
        )
        assertTrue(
            "Both category menu surfaces must read MDBX folders from the shared folder flow.",
            bottomSheetSource.contains("getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>>") &&
                bottomSheetSource.contains("val folders by getMdbxFolders(database.id).collectAsState") &&
                bottomSheetSource.contains("val currentMdbxFolderId = (selected as? UnifiedCategoryFilterSelection.MdbxFolderFilter)") &&
                bottomSheetSource.contains(".filter { it.isDirectMdbxChildOf(currentMdbxFolderId) }") &&
                bottomSheetSource.contains("createDialogInitialMdbxDbId = currentMdbxSelection?.databaseId") &&
                bottomSheetSource.contains("createDialogInitialMdbxParentFolderId = currentMdbxSelection?.folderId") &&
                chipMenuSource.contains("getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>>") &&
                chipMenuSource.contains("selectedMdbxDatabaseId?.let(getMdbxFolders)") &&
                chipMenuSource.contains("val currentFolderId = (selected as? UnifiedCategoryFilterSelection.MdbxFolderFilter)?.folderId") &&
                chipMenuSource.contains(".filter { it.isDirectMdbxChildOf(currentFolderId) }") &&
                !chipMenuSource.contains("selection = UnifiedCategoryFilterSelection.MdbxDatabaseFilter(databaseId),\n                    isBack = true")
        )
        assertTrue(
            "Top-level password controls must pass MDBX folder loading into the chip menu and keep folder labels visible.",
            topSectionSource.contains("selectedMdbxFolders: List<MdbxStoredFolderEntry>") &&
                topSectionSource.contains("getMdbxFolders = viewModel::getMdbxFolders") &&
                topSectionSource.contains("UnifiedCategoryFilterSelection.MdbxFolderFilter(filter.databaseId, filter.folderId)")
        )
    }

    @Test
    fun mdbxFolderCreationUsesAndroidSafePragmasAndPersistentFailureLogs() {
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()

        val openBody = storeSource.substringAfter("private fun open(file: File): SQLiteDatabase")
            .substringBefore("private fun checkpoint(db: SQLiteDatabase)")
        assertFalse(
            "Android API 36 rejects query-like PRAGMA statements through execSQL; connection PRAGMA must use rawQuery.",
            openBody.contains("execSQL(\"PRAGMA")
        )
        assertTrue(
            "MDBX open should still apply the SQLite connection PRAGMAs required by the Rust storage contract.",
            openBody.contains("applyConnectionPragma(\"PRAGMA synchronous=NORMAL\")") &&
                openBody.contains("applyConnectionPragma(\"PRAGMA busy_timeout=5000\")") &&
                openBody.contains("applyConnectionPragma(\"PRAGMA secure_delete=ON\")")
        )
        assertTrue(
            "Connection PRAGMA helper must consume the returned cursor with rawQuery instead of execSQL.",
            storeSource.substringAfter("private fun SQLiteDatabase.applyConnectionPragma(sql: String)")
                .substringBefore("private fun checkpoint(db: SQLiteDatabase)")
                .contains("rawQuery(sql, emptyArray()).use")
        )

        val createFolderBody = storeSource.substringAfter("override suspend fun createFolder(")
            .substringBefore("override suspend fun listFolders(")
        val ensureRootFolderBody = storeSource.substringAfter("private fun ensureRootFolder(")
            .substringBefore("private fun ensureFolder(")
        val ensureFolderBody = storeSource.substringAfter("private fun ensureFolder(")
            .substringBefore("private fun upsertObjectIndex(")
        assertTrue(
            "MDBX folder creation must repair/create the root folder before looking up the selected parent, otherwise old/compat vaults cannot create folders at root.",
            createFolderBody.contains("ensureRootFolder(db, epochKey)") &&
                createFolderBody.indexOf("ensureRootFolder(db, epochKey)") <
                    createFolderBody.indexOf("SELECT path_key FROM folders WHERE folder_id = ?") &&
                ensureRootFolderBody.contains("VALUES ('root', NULL") &&
                ensureRootFolderBody.contains("path_key = '/'") &&
                ensureRootFolderBody.contains("parent_folder_id = NULL") &&
                ensureFolderBody.contains("if (folderId == \"root\")") &&
                ensureFolderBody.contains("ensureRootFolder(db, epochKey)")
        )
        assertTrue(
            "MDBX folder creation failures must be persisted to the MDBX diagnostic log so exported logs are actionable.",
            createFolderBody.contains("MdbxDiagLogger.append(") &&
                createFolderBody.contains("\"createFolder failed databaseId=") &&
                createFolderBody.contains("error=\${error.javaClass.simpleName}") &&
                createFolderBody.contains("throw error")
        )
    }

    @Test
    fun mdbxNestedFolderCreationPropagatesParentFolderFromCurrentSelection() {
        val dialogSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/CreateCategoryDialog.kt"
        ).readText()
        val bottomSheetSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/UnifiedCategoryFilterBottomSheet.kt"
        ).readText()
        val topSectionSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListTopSection.kt"
        ).readText()
        val categoryStateSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/category/CategoryManagementState.kt"
        ).readText()
        val vaultV2Source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()

        assertTrue(
            "CreateCategoryDialog must keep MDBX parent folder state and pass it to folder creation.",
            dialogSource.contains("onCreateMdbxProject: ((databaseId: Long, parentFolderId: String?, name: String) -> Unit)?") &&
                dialogSource.contains("initialMdbxParentFolderId: String?") &&
                dialogSource.contains("getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>>") &&
                dialogSource.contains("val createMdbxFolders by (") &&
                dialogSource.contains("getMdbxFolders(selectedCreateMdbxDbId!!)") &&
                dialogSource.contains("text = stringResource(R.string.create_select_mdbx_parent_folder)") &&
                dialogSource.contains("onClick = { createMdbxParentFolderId = folder.folderId }") &&
                dialogSource.contains("mdbxFolderDisplayLabel(folder, createMdbxFolders)") &&
                dialogSource.contains("createMdbxParentFolderId = initialMdbxParentFolderId") &&
                dialogSource.contains("onCreateMdbxProject?.invoke(dbId, createMdbxParentFolderId, name)")
        )
        assertTrue(
            "Password top create dialog must seed MDBX nested creation from the current folder filter.",
            topSectionSource.contains("initialMdbxDbId = initialDialogMdbxDbId") &&
                topSectionSource.contains("getMdbxFolders = viewModel::getMdbxFolders") &&
                topSectionSource.contains("initialMdbxParentFolderId = (currentFilter as? CategoryFilter.MdbxFolderFilter)?.folderId") &&
                topSectionSource.contains("viewModel.createMdbxFolder(databaseId, name, parentFolderId ?: \"root\")")
        )
        assertTrue(
            "Shared category management must treat MDBX folder filters as MDBX create targets and pass the parent id.",
            categoryStateSource.contains("val initialMdbxParentFolderId = (currentFilter as? UnifiedCategoryFilterSelection.MdbxFolderFilter)?.folderId") &&
                categoryStateSource.contains("getMdbxFolders = passwordViewModel::getMdbxFolders") &&
                categoryStateSource.contains("is UnifiedCategoryFilterSelection.MdbxFolderFilter -> Quadruple(CreateDialogTarget.Mdbx") &&
                categoryStateSource.contains("passwordViewModel.createMdbxFolder(databaseId, name, parentFolderId ?: \"root\")")
        )
        assertTrue(
            "MDBX bottom sheet must expose child-folder creation from a folder row and forward the selected parent.",
            bottomSheetSource.contains("createDialogInitialMdbxParentFolderId = folder.folderId") &&
                bottomSheetSource.contains("getMdbxFolders = getMdbxFolders") &&
                bottomSheetSource.contains("initialMdbxParentFolderId = createDialogInitialMdbxParentFolderId") &&
                bottomSheetSource.contains("onCreateMdbxProject: ((databaseId: Long, parentFolderId: String?, name: String) -> Unit)?")
        )
        assertTrue(
            "VaultV2 must pass MDBX folders into the bottom sheet and preserve parent folder creation in its create dialog.",
            vaultV2Source.contains("getMdbxFolders = passwordViewModel::getMdbxFolders") &&
                vaultV2Source.contains("is UnifiedCategoryFilterSelection.MdbxFolderFilter -> Triple(CreateDialogTarget.Mdbx") &&
            vaultV2Source.contains("initialMdbxParentFolderId = (storageSelection as? UnifiedCategoryFilterSelection.MdbxFolderFilter)?.folderId") &&
                vaultV2Source.contains("passwordViewModel.createMdbxFolder(databaseId, name, parentFolderId ?: \"root\")")
        )
    }

    @Test
    fun createCategoryDialogKeepsInputReachableWhenCategoryListIsLong() {
        val dialogSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/CreateCategoryDialog.kt"
        ).readText()
        val dialogTextBody = dialogSource.substringAfter("text = {")
            .substringBefore("confirmButton = {")

        assertTrue(
            "CreateCategoryDialog content must scroll vertically so long local/MDBX/KeePass folder lists cannot push the name field off-screen.",
            dialogSource.contains("import androidx.compose.foundation.verticalScroll") &&
                dialogSource.contains("val createDialogContentScroll = rememberScrollState()") &&
                dialogTextBody.contains(".verticalScroll(createDialogContentScroll)") &&
                dialogTextBody.contains("OutlinedTextField(") &&
                dialogTextBody.indexOf(".verticalScroll(createDialogContentScroll)") <
                    dialogTextBody.indexOf("OutlinedTextField(")
        )
    }

    @Test
    fun mdbxMoveAndCopySurfacesExposeAndPersistFolderTargets() {
        val moveSheetSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/UnifiedMoveToCategoryBottomSheet.kt"
        ).readText()
        val passkeyListSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/PasskeyListScreen.kt"
        ).readText()
        val passkeyCreateSource = projectFile(
            "app/src/main/java/takagi/ru/monica/passkey/PasskeyCreateActivity.kt"
        ).readText()
        val mixedBatchSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveMixedSupport.kt"
        ).readText()

        assertTrue(
            "Move/copy sheet must expose concrete MDBX folder targets instead of only database-root targets.",
            moveSheetSource.contains("data class MdbxFolderTarget(val databaseId: Long, val folderId: String)") &&
                moveSheetSource.contains("getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>>") &&
                moveSheetSource.contains("getMdbxFolders(activeSource.database.id)") &&
                moveSheetSource.contains("collectAsState(initial = emptyList())") &&
                moveSheetSource.contains("UnifiedMoveCategoryTarget.MdbxFolderTarget(")
        )
        val moveTargetListBody = moveSheetSource.substringAfter("LazyColumn(")
            .substringBefore("Surface(\n                modifier = Modifier\n                    .fillMaxWidth()\n                    .padding(top = 8.dp)")
        assertTrue(
            "Move/copy sheet must follow Add Password's picker model: choose a database/source first, then show only that source's folders.",
            moveSheetSource.contains("sealed interface MovePickerSource") &&
                moveSheetSource.contains("val activeSourceKey") &&
                moveSheetSource.contains("FlowRow(") &&
                moveSheetSource.contains("category_selection_menu_databases") &&
                moveSheetSource.contains("category_selection_menu_folders") &&
                moveSheetSource.contains("MoveSelectorSectionTitle(") &&
                moveSheetSource.contains("sources.forEach { source ->") &&
                moveSheetSource.contains("activeSource is MovePickerSource.MdbxDatabase") &&
                moveSheetSource.contains("if (activeSource is MovePickerSource.KeePassDatabase)")
        )
        assertTrue(
            "Move/copy sheet should stage a chosen category/folder first and require a final confirmation, avoiding accidental move/copy on row tap.",
            moveSheetSource.contains("val selectedTarget = remember { mutableStateOf<UnifiedMoveCategoryTarget?>(null) }") &&
                moveSheetSource.contains("fun stageTarget(") &&
                moveSheetSource.contains("fun confirmSelectedTarget()") &&
                moveSheetSource.contains("FilledTonalButton(") &&
                moveSheetSource.contains("onClick = ::confirmSelectedTarget") &&
                moveSheetSource.contains("selected = selectedTarget.value == target.target") &&
                moveSheetSource.contains("Text(\"${'$'}confirmLabel${'$'}actionLabel\")")
        )
        assertFalse(
            "Rows inside the move/copy target list must not execute the operation directly; only the confirm button may call onTargetSelected.",
            moveTargetListBody.contains("onTargetSelected(")
        )
        assertFalse(
            "Move/copy sheet must not regress to the old mixed expandable tree; database selection and folder selection are separate steps.",
            moveSheetSource.contains("BottomSheetAnimatedVisibility(") ||
                moveSheetSource.contains("ExpandLess") ||
                moveSheetSource.contains("ExpandMore") ||
                moveSheetSource.contains("MoveSectionCard(")
        )
        assertTrue(
            "Passkey move sheets must pass MDBX databases and folder flows so MDBX folders are selectable.",
            passkeyListSource.contains("mdbxDatabases = mdbxDatabases") &&
                passkeyListSource.contains("passwordViewModel?.getMdbxFolders(databaseId)") &&
                passkeyListSource.contains("is UnifiedMoveCategoryTarget.MdbxFolderTarget -> passkey.copy(") &&
                passkeyListSource.contains("mdbxFolderId = target.folderId")
        )
        assertTrue(
            "Passkey creation must preserve the selected or inherited MDBX folder instead of falling back to the vault root.",
            passkeyCreateSource.contains("private var pendingMdbxFolderId: String? = null") &&
                passkeyCreateSource.contains("var selectedMdbxFolderId by remember") &&
                passkeyCreateSource.contains("mdbxVaultStore.listFolders(databaseId)") &&
                passkeyCreateSource.contains("onMdbxFolderSelected(target.folderId)") &&
                passkeyCreateSource.contains("mdbxFolderId = if (initialMdbxDatabaseId != null) initialMdbxFolderId else null")
        )
        assertTrue(
            "Mixed password-page move/copy must propagate MDBX folder ids to passwords and supplementary item types.",
            mixedBatchSource.contains("val targetMdbxFolderId = when (target)") &&
                mixedBatchSource.contains("is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.folderId") &&
                mixedBatchSource.contains("viewModel.movePasswordsToMdbxFoldersAwait(") &&
                mixedBatchSource.contains("folderIdsByPasswordId = folderIdsByPasswordId") &&
                mixedBatchSource.contains("totpViewModel.moveTotpToStorage(") &&
                mixedBatchSource.contains("noteViewModel.moveNoteToStorage(") &&
                mixedBatchSource.contains("bankCardViewModel.moveCardToStorage(") &&
                mixedBatchSource.contains("documentViewModel.moveDocumentToStorage(") &&
                mixedBatchSource.contains("mdbxDatabaseId = targetMdbxDatabaseId") &&
                mixedBatchSource.contains("mdbxFolderId = targetMdbxFolderId")
        )
    }

    @Test
    fun mdbxManagerUsesScopedFeedbackAndQuietMaintenanceUi() {
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()

        assertTrue(
            "MDBX sync feedback must use Scaffold snackbar instead of a persistent page overlay.",
            managerSource.contains("val snackbarHostState = remember { SnackbarHostState() }") &&
                managerSource.contains("snackbarHost = {") &&
                managerSource.contains("SnackbarHost(hostState = snackbarHostState)") &&
                managerSource.contains("SnackbarDuration.Short") &&
                managerSource.contains("viewModel.clearOperationState()")
        )
        assertTrue(
            "MDBX manager must not bring back the custom bottom status overlay that blocks subpages.",
            !managerSource.contains("MdbxOperationStatusBar") &&
                !managerSource.contains("Alignment.BottomCenter")
        )
        assertTrue(
            "Snapshot management should stay visually quiet instead of using high-saturation tertiary panels.",
            managerSource.substringAfter("private fun SnapshotManagerPanel(")
                .substringBefore("private fun MdbxSnapshotStructurePage(")
                .let { snapshotPanelSource ->
                    snapshotPanelSource.contains("OutlinedCard(modifier = Modifier.fillMaxWidth())") &&
                        !snapshotPanelSource.contains("tertiaryContainer") &&
                        !snapshotPanelSource.contains("onTertiaryContainer")
                }
        )
        assertTrue(
            "Diagnostics should prioritize a concise maintenance flow and keep low-level details secondary.",
            managerSource.contains("private fun MaintenanceActionPanel(") &&
                managerSource.contains("MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_key_metrics))") &&
                managerSource.contains("MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_advanced_details))") &&
                !managerSource.contains("schema、commit 图、设备 head、快照、附件 chunk")
        )
        assertTrue(
            "History should use one semantic operation card per commit instead of a diagnostic dashboard.",
            managerSource.contains("private fun DeltaRow(") &&
                managerSource.contains("val presentation = remember(delta, strings) { delta.toHistoryPresentation(strings) }") &&
                managerSource.contains("presentation.primaryAction.historyIcon()") &&
                managerSource.contains("HistoryStatusPill(strings.get(R.string.mdbx_ui_system))") &&
                !managerSource.contains("private fun DeltaSummaryHeader(") &&
                !managerSource.substringAfter("private fun DeltaRow(")
                    .substringBefore("private fun shortId(")
                    .contains("StatusTile(")
        )
    }

    @Test
    fun mdbxHistorySnapshotsAndConflictsOpenFieldDiffViews() {
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val storeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val mdbx2RepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt"
        ).readText()
        val historyPresentationSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxCommitHistoryPresentation.kt"
        ).readText()

        assertTrue(
            "MDBX conflict rows should open a focused conflict diff detail instead of dumping previews in the list.",
            managerSource.contains("var selectedConflictId by rememberSaveable") &&
                managerSource.contains("ConflictSummaryRow(") &&
                managerSource.contains("ConflictDiffDetail(") &&
                managerSource.contains("onOpen = { selectedConflictId = conflict.conflictId }")
        )
        assertTrue(
            "MDBX conflict detail should render a field-level unified diff, not a code-style line diff.",
            managerSource.contains("private fun FieldDiffPanel(") &&
                managerSource.contains("private data class FieldChangeGroup(") &&
                managerSource.contains("private fun FieldChangeGroupBlock(") &&
                managerSource.contains("private fun FieldChangeRow(") &&
                managerSource.contains("private fun VersionValueRow(") &&
                managerSource.contains("marker = \"-\"") &&
                managerSource.contains("marker = \"+\"") &&
                managerSource.contains("backgroundColor = MaterialTheme.colorScheme.errorContainer.copy") &&
                managerSource.contains("backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy") &&
                managerSource.substringAfter("private fun FieldChangeGroupBlock(")
                    .substringBefore("private fun FieldChangeRow(")
                    .contains("strings.get(R.string.mdbx_ui_field_changes)") &&
                managerSource.contains("\"${'$'}{change.fieldLabel}:\"") &&
                managerSource.contains("value.ifBlank { \"null\" }") &&
                managerSource.contains("group.displayPath()") &&
                !managerSource.contains("fieldLabel = change.fieldLabel") &&
                !managerSource.contains("versionLabel =") &&
                !managerSource.contains("\"删除状态\"") &&
                !managerSource.contains("deletedLabel(") &&
                !managerSource.contains("strings.get(R.string.mdbx_ui_item_count, group.changes.size)") &&
                !managerSource.contains("FontFamily.Monospace") &&
                !managerSource.contains("private fun UnifiedDiffCard(") &&
                !managerSource.contains("DiffLineKind")
        )
        assertTrue(
            "Commit history should lazily group object cards and explain non-object system commits.",
            managerSource.contains("private fun CommitChangeGroupHeader(") &&
                managerSource.contains("private fun CommitEventExplanationCard(") &&
                managerSource.contains("items = diffs") &&
                managerSource.contains("CommitObjectChangeCard(diff)") &&
                !managerSource.contains("private fun CommitDiffPanel(") &&
                !managerSource.contains("此提交没有可显示的对象变更") &&
                managerSource.substringAfter("private fun ConflictDiffDetail(")
                    .substringBefore("@Composable\nprivate fun MdbxSnapshotPage(")
                    .contains("FieldDiffPanel(")
        )
        assertTrue(
            "Snapshot rows should let the user inspect the snapshot base commit diff before reverting.",
            managerSource.contains("onShowDiff: () -> Unit") &&
                managerSource.contains("onShowDiff = { onShowDiff(snapshot.baseCommitId) }") &&
                managerSource.contains("Text(strings.get(R.string.mdbx_ui_changes))")
        )
        val snapshotStructurePreviewBody = managerSource
            .substringAfter("private fun SnapshotStructurePreviewPage(")
            .substringBefore("private fun StructureTreePanel(")
        assertTrue(
            "Snapshot rows should open a real subpage for the VSCode-style structure preview and support landscape comparison.",
            managerSource.contains("data class SnapshotStructure(") &&
                managerSource.contains("page = MdbxManagerPage.SnapshotStructure(current.databaseId, current.source, snapshotId)") &&
                managerSource.contains("is MdbxManagerPage.SnapshotStructure -> {") &&
                managerSource.contains("viewModel.closeSnapshotStructure()") &&
                managerSource.contains("MdbxManagerPage.Snapshots(current.databaseId, current.source)") &&
                managerSource.contains("private fun MdbxSnapshotStructurePage(") &&
                managerSource.contains("private fun SnapshotStructurePreviewPage(") &&
                managerSource.contains("private fun StructureTreePanel(") &&
                managerSource.contains("private fun StructureTreeRow(") &&
                managerSource.contains("onShowSnapshotStructure: (String) -> Unit") &&
                managerSource.contains("onOpenStructure = { onShowSnapshotStructure(snapshot.snapshotId) }") &&
                managerSource.contains("Text(strings.get(R.string.mdbx_ui_structure))") &&
                managerSource.contains("ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE") &&
                managerSource.contains("ActivityInfo.SCREEN_ORIENTATION_PORTRAIT") &&
                managerSource.contains("requestedOrientation") &&
                managerSource.contains("title = strings.get(R.string.mdbx_ui_current_version)") &&
                managerSource.contains("title = strings.get(R.string.mdbx_ui_snapshot_version)") &&
                managerSource.contains("var snapshotCompareMode by rememberSaveable(snapshotPage?.databaseId, snapshotPage?.snapshotId)") &&
                managerSource.contains("val snapshotTopBarName = snapshotPage?.let") &&
                managerSource.contains("val snapshotTopBarMeta = snapshotPage?.let") &&
                managerSource.contains("IconButton(onClick = { snapshotCompareMode = !snapshotCompareMode })") &&
                managerSource.contains("if (snapshotCompareMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen") &&
                snapshotStructurePreviewBody.contains(".verticalScroll(rememberScrollState())") &&
                snapshotStructurePreviewBody.contains("VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)") &&
                snapshotStructurePreviewBody.contains("modifier = Modifier.weight(1f)") &&
                snapshotStructurePreviewBody.contains("framed = false") &&
                !snapshotStructurePreviewBody.contains("Icons.Default.Fullscreen") &&
                !managerSource.contains("rememberSaveable(snapshotId)") &&
                managerSource.contains("framed = false") &&
                managerSource.contains("private fun StructureIndentLines(") &&
                managerSource.contains(".height(34.dp)") &&
                managerSource.contains(".padding(start = 8.dp, end = 8.dp)") &&
                managerSource.contains(".fillMaxHeight()") &&
                managerSource.contains("private val structureTreeNodeComparator = compareBy<MdbxStructureNode>(") &&
                managerSource.contains("{ if (it.type == MdbxStructureNodeType.FOLDER) 0 else 1 }") &&
                managerSource.contains("childrenByParent[parentId].orEmpty().sortedWith(structureTreeNodeComparator)") &&
                !managerSource.substringAfter("private fun MdbxSnapshotPage(")
                    .substringBefore("private fun MdbxCommitHistoryPage(")
                    .contains("SnapshotStructurePreviewPage(") &&
                !managerSource.contains("onCloseSnapshotStructure") &&
                storeSource.contains("data class MdbxStructurePreview(") &&
                storeSource.contains("data class MdbxStructureNode(") &&
                storeSource.contains("enum class MdbxStructureNodeStatus") &&
                storeSource.contains("suspend fun getSnapshotStructurePreview(") &&
                storeSource.contains("private fun buildStructureNodes(") &&
                storeSource.contains("val visibleFolderIds = folders.keys") &&
                storeSource.contains("private fun structureNodeTypeSortRank(node: MdbxStructureNode): Int") &&
                storeSource.contains("if (node.type == MdbxStructureNodeType.FOLDER) 0 else 1") &&
                storeSource.contains(".thenBy { structureNodeTypeSortRank(it) }")
        )
        assertTrue(
            "Diff data must use readable paths and redact sensitive payload values.",
            managerSource.contains("private fun MdbxCommitDiff.toFieldChanges(strings: StringResolver)") &&
                managerSource.contains("private fun CommitObjectChangeCard(") &&
                managerSource.contains("private enum class ObjectChangeKind") &&
                managerSource.contains("ObjectChangeKind.DELETED -> strings.get(R.string.mdbx_ui_history_action_deleted, objectLabel)") &&
                managerSource.contains("private fun MdbxCommitDiff.displayObjectTitle(strings: StringResolver)") &&
                managerSource.contains("private fun MdbxConflictSummary.toFieldChanges(strings: StringResolver)") &&
                managerSource.contains("displayTitle?.takeIf") &&
                managerSource.contains("storagePath?.takeIf") &&
                managerSource.contains("strings.get(R.string.title)") &&
                managerSource.contains("fieldLabel = strings.get(R.string.content)") &&
                managerSource.contains("sensitive = true") &&
                managerSource.contains("R.string.mdbx_ui_sensitive_changes_hidden") &&
                storeSource.contains("val displayTitle: String?") &&
                storeSource.contains("val storagePath: String?") &&
                storeSource.contains("private fun readDiffDisplayInfo(") &&
                storeSource.contains("private fun folderDisplayPath(") &&
                storeSource.contains("displayTitle = displayInfo.title") &&
                storeSource.contains("storagePath = displayInfo.storagePath") &&
                mdbx2RepositorySource.contains("storagePath = diff.collectionId?.let(collectionPaths::get)") &&
                mdbx2RepositorySource.contains("contentType = objectSummary?.objectTypeId") &&
                !managerSource.contains("@@ payload") &&
                !managerSource.contains("@@ title")
        )
        assertTrue(
            "History detail should preserve the top app bar back path and use progressive disclosure.",
                managerSource.contains("val deltaState = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible") &&
                managerSource.contains("deltaState?.selectedDiffCommitId != null") &&
                managerSource.contains("viewModel.closeCommitDiff()") &&
                managerSource.contains("is MdbxManagerPage.CommitHistory -> strings.get(R.string.mdbx_ui_manager_history_title)") &&
                managerSource.contains("BackHandler(onBack = goBack)") &&
                managerSource.contains("private fun MdbxSnapshotPage(") &&
                managerSource.contains("private fun MdbxCommitHistoryPage(") &&
                managerSource.contains("MdbxNavigationActionRow(Icons.Default.Restore, strings.get(R.string.mdbx_ui_object_snapshot), onShowSnapshots)") &&
                managerSource.contains("MdbxNavigationActionRow(Icons.Default.History, strings.get(R.string.mdbx_ui_manager_history_title), onShowCommitHistory)") &&
                managerSource.contains("selectedDelta?.toHistoryPresentation(strings)") &&
                managerSource.contains("private fun CommitTechnicalInfoCard(") &&
                managerSource.contains("private fun CommitDetailHeader(") &&
                managerSource.contains("R.string.mdbx_ui_history_revert_title") &&
                historyPresentationSource.contains("fun MdbxDeltaSummary.toHistoryPresentation(strings: StringResolver)") &&
                storeSource.contains("val changedObjectPreview: String") &&
                storeSource.contains("val changedFieldSummary: String") &&
                storeSource.contains("val operationKind: String? = null") &&
                storeSource.contains("val changes: List<MdbxCommitChangeSummary> = emptyList()") &&
                storeSource.contains("private fun readCommitChangePreview(") &&
                storeSource.contains("private fun summarizeCommitObjects(") &&
                storeSource.contains("private fun summarizeCommitFields(") &&
                !managerSource.contains("onCloseDiff = { viewModel.closeCommitDiff() }") &&
                !managerSource.contains("Text(\"返回历史\")") &&
                !managerSource.contains("val pageTitle = if (state?.selectedDiffCommitId != null)") &&
                !managerSource.contains("MdbxDeltaPage(") &&
                !managerSource.contains("MdbxManagerPage.History") &&
                !managerSource.contains("历史 / 快照") &&
                !managerSource.contains("修改前") &&
                !managerSource.contains("修改后") &&
                !managerSource.contains("Text(\n                delta.changedObjectIds") &&
                !managerSource.contains("Text(\"Diff\")")
        )
        assertTrue(
            "Legacy dialog/list implementations must not return and reintroduce inline diff details.",
            !managerSource.contains("private fun MdbxConflictDialog(") &&
                !managerSource.contains("private fun MdbxDeltaDialog(") &&
                !managerSource.contains("private fun ConflictRow(") &&
                !managerSource.contains("private fun ConflictVersionPreview(")
        )
    }

    @Test
    fun normalPasswordPageShowsBatchTransferInQuickStatusBar() {
        val trackerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchTransferProgressTracker.kt"
        ).readText()
        val quickFolderSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSections.kt"
        ).readText()
        val quickStatusTransferSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/QuickStatusTransferBar.kt"
        ).readText()
        val listContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt"
        ).readText()
        val mainPaneSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListMainPane.kt"
        ).readText()
        val quickStatusDialogsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListQuickStatusDialogs.kt"
        ).readText()
        val moveSupportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveSupport.kt"
        ).readText()
        val unifiedMoveSheetSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/UnifiedMoveToCategoryBottomSheet.kt"
        ).readText()
        val mdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val passwordRepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        assertTrue(
            "The transfer tracker must keep a short success phase so the quick status bar can show the completed result before returning to breadcrumbs.",
            trackerSource.contains("enum class PasswordBatchTransferPhase") &&
                trackerSource.contains("val operationId: Long") &&
                trackerSource.contains("private var nextOperationId") &&
                trackerSource.contains("RUNNING") &&
                trackerSource.contains("SUCCESS") &&
                trackerSource.contains("fun complete(") &&
                trackerSource.contains("delay(1300)")
        )
        assertTrue(
            "The normal password page must show transfer progress in the quick status bar when the path banner is enabled, and auto-open the progress dialog when the banner is disabled.",
            listContentSource.contains("PasswordBatchTransferProgressTracker.progress.collectAsState()") &&
                listContentSource.contains("var showQuickStatusTransferDialog by remember { mutableStateOf(false) }") &&
                listContentSource.contains("var backgroundedTransferOperationId by remember { mutableStateOf<Long?>(null) }") &&
                listContentSource.contains("val quickStatusBannerEnabled = quickFolderPathBannerEnabledForCurrentFilter") &&
                listContentSource.contains("LaunchedEffect(quickStatusTransferState?.operationId, quickStatusBannerEnabled)") &&
                listContentSource.contains("if (!quickStatusBannerEnabled && state.operationId != backgroundedTransferOperationId)") &&
                listContentSource.contains("val hasQuickStatusProgress =") &&
                listContentSource.contains("quickStatusBannerEnabled &&") &&
                listContentSource.contains("quickStatusTransferState = quickStatusTransferState") &&
                listContentSource.contains("onQuickStatusTransferClick = {") &&
                listContentSource.contains("backgroundedTransferOperationId = null") &&
                listContentSource.contains("backgroundedTransferOperationId = quickStatusTransferState?.operationId") &&
                listContentSource.contains("PasswordListQuickStatusDialogs(") &&
                quickStatusDialogsSource.contains("quickStatusTransferState?.toDialogUiState()?.let") &&
                mainPaneSource.contains("quickStatusTransferState: PasswordBatchTransferGlobalProgressState? = null") &&
                mainPaneSource.contains("onQuickStatusTransferClick: (() -> Unit)? = null") &&
                mainPaneSource.contains("transferState = quickStatusTransferState") &&
                mainPaneSource.contains("onTransferStatusClick = onQuickStatusTransferClick")
        )
        assertTrue(
            "The transfer animation should live in a reusable quick status component, not as password-page-only UI.",
            quickStatusTransferSource.contains("data class QuickStatusTransferState(") &&
                quickStatusTransferSource.contains("enum class QuickStatusTransferPhase") &&
                quickStatusTransferSource.contains("fun QuickStatusTransferBar(") &&
                quickStatusTransferSource.contains("Icons.AutoMirrored.Filled.Send") &&
                quickStatusTransferSource.contains("val sourceWeight") &&
                quickStatusTransferSource.contains("val targetWeight") &&
                quickStatusTransferSource.contains("QuickStatusTransferSuccessStatus") &&
                quickStatusTransferSource.contains("\"移动\"") &&
                quickStatusTransferSource.contains("\"复制\"") &&
                quickFolderSource.contains("QuickStatusTransferBar(") &&
                quickFolderSource.contains("toQuickStatusTransferState(") &&
                quickFolderSource.contains("targetState = statusMode") &&
                quickFolderSource.contains("PasswordQuickStatusMode") &&
                quickFolderSource.contains("Modifier.clickable(enabled = onTransferStatusClick != null)") &&
                !quickFolderSource.contains("private fun PasswordQuickTransferStatusBar(") &&
                !quickFolderSource.contains("private fun PasswordQuickTransferSuccessStatus(") &&
                !quickFolderSource.contains("targetState = transferState")
        )
        assertTrue(
            "Completed password batch moves/copies should publish success to the quick status bar instead of clearing the state immediately, without auto-opening the old blocking progress dialog.",
            moveSupportSource.contains("var completedCleanly = false") &&
                moveSupportSource.contains("mutableStateOf(false)") &&
                moveSupportSource.contains("completedCleanly = true") &&
                moveSupportSource.contains("internal fun PasswordBatchTransferGlobalProgressState.toDialogUiState()") &&
                moveSupportSource.contains("PasswordBatchTransferProgressTracker.complete(") &&
                moveSupportSource.contains("PasswordBatchTransferProgressTracker.clear()") &&
                !moveSupportSource.contains("showProgressDialog = true")
        )
        assertTrue(
            "After the user confirms a move/copy target, multi-select mode should close immediately while the transfer continues in the quick status bar.",
            moveSupportSource.indexOf("onProgressUpdate(if (totalCount > 1) 1 else 0, totalCount)").let { progressIndex ->
                val dismissIndex = moveSupportSource.indexOf("onDismiss()", progressIndex.coerceAtLeast(0))
                val clearIndex = moveSupportSource.indexOf("onSelectionCleared()", dismissIndex.coerceAtLeast(0))
                val launchIndex = moveSupportSource.indexOf("viewModel.viewModelScope.launch {")
                progressIndex >= 0 &&
                    dismissIndex > progressIndex &&
                    clearIndex > dismissIndex &&
                    launchIndex > clearIndex
            }
        )
        assertTrue(
            "The normal password move/copy picker must refresh MDBX folders when an MDBX target database is selected, otherwise unopened MDBX databases show only the root target.",
            unifiedMoveSheetSource.contains("refreshMdbxFolders: (Long) -> Unit = {}") &&
                unifiedMoveSheetSource.contains("val activeMdbxDatabaseId = (activeSource as? MovePickerSource.MdbxDatabase)?.database?.id") &&
                unifiedMoveSheetSource.contains("LaunchedEffect(activeMdbxDatabaseId)") &&
                unifiedMoveSheetSource.contains("activeMdbxDatabaseId?.let(refreshMdbxFolders)") &&
                moveSupportSource.contains("refreshMdbxFolders = viewModel::refreshMdbxFolders")
        )
        val markLegacyEntryDeletedBody = mdbxStoreSource
            .substringAfter("private fun markLegacyEntryDeleted(")
            .substringBefore("private fun writeEntryDeleteMutation(")
        val mdbxRepositoryFactorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxRepositoryFactory.kt"
        ).readText()
        val mdbx2RepositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt"
        ).readText()
        assertTrue(
            "MDBX password object ids must reuse imported MDBX entry ids across clients, while tombstoning the broken local Room-id object written by older builds.",
            mdbxRepositoryFactorySource.contains("fun mdbxPasswordObjectId(entry: PasswordEntry): String") &&
                mdbxRepositoryFactorySource.contains("?.takeIf { it.startsWith(\"password:\")") &&
                mdbxRepositoryFactorySource.contains("?: \"password:${'$'}{entry.id}\"") &&
                mdbxStoreSource.contains("mdbxPasswordObjectId(entry)") &&
                mdbx2RepositorySource.contains("mdbxPasswordObjectId(entry)") &&
                mdbxStoreSource.contains("private fun legacyPasswordObjectId(entry: PasswordEntry): String?") &&
                mdbxStoreSource.contains("?.let { \"password:${'$'}{entry.id}\" }") &&
                mdbxStoreSource.contains("private fun isMdbxPasswordObjectId(value: String): Boolean") &&
                mdbxStoreSource.contains("legacyEntryId = legacyPasswordObjectId(entry)") &&
                mdbxStoreSource.contains("markLegacyEntryDeleted(db, legacyEntryId, commitId, now)") &&
                mdbxStoreSource.contains("insertTombstone(db, \"project\", legacyEntryId)") &&
                mdbxStoreSource.contains("insertTombstone(db, \"entry\", legacyEntryId)") &&
                mdbxStoreSource.contains("clearTombstone(db, \"project\", mutation.projectId)") &&
                mdbxStoreSource.contains("val projectId = version?.projectId ?: mutation.entryId") &&
                mdbxStoreSource.contains("insertTombstone(db, \"project\", projectId)") &&
                markLegacyEntryDeletedBody.contains("UPDATE projects SET deleted = 1") &&
                markLegacyEntryDeletedBody.contains("UPDATE entries SET deleted = 1") &&
                markLegacyEntryDeletedBody.contains("object_clock = object_clock + 1") &&
                markLegacyEntryDeletedBody.contains("head_commit_id = ?") &&
                passwordRepositorySource.contains("replicaGroupId = entry.mdbxPasswordObjectId()") &&
                passwordRepositorySource.contains("passwordEntryDao.updatePasswordEntries(entriesForMdbx)") &&
                passwordRepositorySource.contains("private fun PasswordEntry.mdbxPasswordObjectId(): String") &&
                passwordRepositorySource.contains("?.takeIf { it.isMdbxPasswordObjectId() }") &&
                passwordRepositorySource.contains("?: id.takeIf { it > 0 }?.let { \"password:${'$'}it\" }") &&
                passwordRepositorySource.contains("private fun String.isMdbxPasswordObjectId(): Boolean") &&
                mdbxViewModelSource.contains(".dedupeMdbxPasswordRowsByEntryId()") &&
                mdbxViewModelSource.contains("normalizeLegacyMdbxPasswordRows(") &&
                mdbxViewModelSource.contains("withNormalizedMdbxPasswordEntryId()") &&
                mdbxViewModelSource.contains("remoteRoomIdsByEntryId[entryId]") &&
                mdbxViewModelSource.contains("[MDBX][legacy-normalize]") &&
                mdbxViewModelSource.contains("[MDBX][duplicate-local-delete]")
        )
    }

    @Test
    fun passwordCategoryQuickFilterRowKeepsHorizontalScrollStateOutsideLazyHeader() {
        val quickFolderRowSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderFlow.kt"
        ).readText()
        val scrollableContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListScrollableContent.kt"
        ).readText()
        val vaultV2Source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()
        val chipRowBody = quickFolderRowSource.substringAfter("internal fun PasswordQuickFolderChipRow(")
            .substringBefore("private fun PasswordQuickFolderShortcut.resolveLeadingIcon")
        val passwordListBody = scrollableContentSource.substringAfter("fun PasswordListScrollableContent(")
            .substringBefore("if (quickFolderShortcuts.isNotEmpty())")
        val vaultV2ListBody = vaultV2Source.substringAfter("private fun VaultV2List(")
            .substringBefore("if (sections.isEmpty() && showLoadingIndicator)")

        assertTrue(
            "The folder chip row below password quick filters must receive a hoisted ScrollState so returning from detail keeps its horizontal position.",
            quickFolderRowSource.contains("import androidx.compose.foundation.ScrollState") &&
                chipRowBody.contains("scrollState: ScrollState") &&
                chipRowBody.contains(".horizontalScroll(scrollState)") &&
                !chipRowBody.contains("rememberScrollState()") &&
                passwordListBody.contains("val categoryQuickFilterScrollState = rememberScrollState()") &&
                passwordListBody.contains("scrollState = categoryQuickFilterScrollState") &&
                vaultV2ListBody.contains("val categoryQuickFilterScrollState = rememberScrollState()") &&
                vaultV2ListBody.contains("scrollState = categoryQuickFilterScrollState")
        )
    }

    @Test
    fun normalPasswordPageRunsBatchDeleteThroughQuickStatusBar() {
        val deleteTrackerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchDeleteProgressTracker.kt"
        ).readText()
        val quickDeleteSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/QuickStatusDeleteBar.kt"
        ).readText()
        val quickFolderSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSections.kt"
        ).readText()
        val listContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt"
        ).readText()
        val mainPaneSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListMainPane.kt"
        ).readText()
        val quickStatusDialogsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListQuickStatusDialogs.kt"
        ).readText()
        val dialogsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListDialogs.kt"
        ).readText()

        assertTrue(
            "Batch delete progress must keep a short success state so the quick status bar can show the completed result before returning to breadcrumbs.",
            deleteTrackerSource.contains("enum class PasswordBatchDeletePhase") &&
                deleteTrackerSource.contains("val operationId: Long") &&
                deleteTrackerSource.contains("private var nextOperationId") &&
                deleteTrackerSource.contains("SUCCESS") &&
                deleteTrackerSource.contains("fun complete(") &&
                deleteTrackerSource.contains("delay(1300)")
        )
        assertTrue(
            "The normal password page must collect delete progress, show it in the quick status bar when enabled, and auto-open details when the path banner is disabled.",
            listContentSource.contains("PasswordBatchDeleteProgressTracker.progress.collectAsState()") &&
                listContentSource.contains("var showQuickStatusDeleteDialog by remember { mutableStateOf(false) }") &&
                listContentSource.contains("var backgroundedDeleteOperationId by remember { mutableStateOf<Long?>(null) }") &&
                listContentSource.contains("LaunchedEffect(quickStatusDeleteState?.operationId, quickStatusBannerEnabled)") &&
                listContentSource.contains("if (!quickStatusBannerEnabled && state.operationId != backgroundedDeleteOperationId)") &&
                listContentSource.contains("quickStatusDeleteState != null") &&
                listContentSource.contains("quickStatusDeleteState = quickStatusDeleteState") &&
                listContentSource.contains("onQuickStatusDeleteClick = {") &&
                listContentSource.contains("backgroundedDeleteOperationId = null") &&
                listContentSource.contains("backgroundedDeleteOperationId = quickStatusDeleteState?.operationId") &&
                listContentSource.contains("PasswordListQuickStatusDialogs(") &&
                quickStatusDialogsSource.contains("quickStatusDeleteState?.toDialogUiState()?.let") &&
                mainPaneSource.contains("quickStatusDeleteState: PasswordBatchDeleteGlobalProgressState? = null") &&
                mainPaneSource.contains("onQuickStatusDeleteClick: (() -> Unit)? = null") &&
                mainPaneSource.contains("deleteState = quickStatusDeleteState") &&
                mainPaneSource.contains("onDeleteStatusClick = onQuickStatusDeleteClick")
        )
        assertTrue(
            "Delete status UI should live in the shared quick status area, not in the old blocking progress dialog path.",
            quickDeleteSource.contains("data class QuickStatusDeleteState(") &&
                quickDeleteSource.contains("enum class QuickStatusDeletePhase") &&
                quickDeleteSource.contains("fun QuickStatusDeleteBar(") &&
                quickDeleteSource.contains("QuickStatusDeleteSuccessStatus") &&
                quickDeleteSource.contains("正在删除") &&
                quickDeleteSource.contains("删除成功，已删除") &&
                quickFolderSource.contains("QuickStatusDeleteBar(") &&
                quickFolderSource.contains("toQuickStatusDeleteState(") &&
                quickFolderSource.contains("DELETE_RUNNING") &&
                quickFolderSource.contains("DELETE_SUCCESS")
        )
        assertTrue(
            "After confirming batch delete, the page must snapshot the selection before clearing multi-select so background deletion does not lose selected items.",
            dialogsSource.contains("onBatchDeleteStarted: () -> Unit = {}") &&
                dialogsSource.contains("onShowBatchDeleteDialogChange(false)") &&
                dialogsSource.contains("PasswordBatchDeleteProgressTracker.complete(successCount)") &&
                listContentSource.contains("val selectedPasswordIdsSnapshot = selectedPasswords.toSet()") &&
                listContentSource.contains("val selectedSupplementaryItemsSnapshot = selectedSupplementaryItems.toList()") &&
                listContentSource.contains("val selectedItemKeysSnapshot = selectedItemKeys.toList()") &&
                listContentSource.contains("onBatchDeleteStarted = {") &&
                listContentSource.contains("selectedItemKeys = emptySet()")
        )
        assertFalse(
            "The old confirmation-owned batch delete progress dialog must not come back; fallback auto-open is driven by the global quick status state.",
            dialogsSource.contains("showBatchDeleteProgressDialog")
        )
    }

    @Test
    fun mdbxBatchDeleteUsesSingleCommitBatchPaths() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val mdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val batchDeleteBody = viewModelSource
            .substringAfter("suspend fun deletePasswordEntriesBatch(")
            .substringBefore("private suspend fun handleBitwardenQueuedDelete(")
        val repositoryBatchUpdateBody = repositorySource
            .substringAfter("suspend fun updatePasswordEntries(entries: List<PasswordEntry>)")
            .substringBefore("suspend fun updatePasswordUpdatedAt(")
        val repositoryBatchDeleteBody = repositorySource
            .substringAfter("suspend fun deletePasswordEntries(entries: List<PasswordEntry>)")
            .substringBefore("suspend fun deletePasswordEntryById(")

        assertTrue(
            "Password batch delete must collect local targets and flush them through the batch helper instead of writing each item inside the main loop.",
            batchDeleteBody.contains("val localTargets = mutableListOf<") &&
                batchDeleteBody.contains("localTargets += entry to commandPolicy") &&
                batchDeleteBody.contains("applyLocalDeleteBatch(localTargets, trashEnabled)") &&
                batchDeleteBody.contains("deletedCount += applyLocalDeleteBatch(chunk, trashEnabled)") &&
                !batchDeleteBody.contains("moveEntryToTrashLocalOnly(entry, commandPolicy)") &&
                !batchDeleteBody.contains("permanentlyDeleteEntryLocalOnly(entry)")
        )
        assertTrue(
            "The local delete batch helper must call repository batch APIs once and clear archive sync metadata as one list operation.",
            viewModelSource.contains("private suspend fun applyLocalDeleteBatch(") &&
                viewModelSource.contains("repository.updatePasswordEntries(softDeletedEntries)") &&
                viewModelSource.contains("repository.deletePasswordEntries(originalEntries)") &&
                viewModelSource.contains("repository.deleteArchiveSyncMeta(originalEntries.map { it.id })")
        )
        assertTrue(
            "PasswordRepository batch update/delete must forward MDBX entries through MDBX batch APIs before writing Room in batches.",
            repositorySource.contains("suspend fun updatePasswordEntries(entries: List<PasswordEntry>)") &&
                repositoryBatchUpdateBody.contains("val nextMdbxEntries = normalizedEntries.filter { it.mdbxDatabaseId != null }") &&
                repositoryBatchUpdateBody.contains("mdbxRepository?.upsertPasswords(nextMdbxEntries)") &&
                repositoryBatchUpdateBody.contains("roomCommit = { passwordEntryDao.updatePasswordEntries(normalizedEntries) }") &&
                repositorySource.contains("suspend fun deletePasswordEntries(entries: List<PasswordEntry>)") &&
                repositoryBatchDeleteBody.contains("val mdbxEntries = entries.filter { it.mdbxDatabaseId != null }") &&
                repositoryBatchDeleteBody.contains("mirrorCommit = { mdbxRepository?.deletePasswords(mdbxEntries) }") &&
                repositoryBatchDeleteBody.contains("roomCommit = { passwordEntryDao.deletePasswordEntries(entries) }")
        )
        assertTrue(
            "MDBX entry batch mutations must share one commit whose changed-object list contains the whole batch, otherwise batch delete creates one commit and snapshot per item.",
            mdbxStoreSource.contains("val sharedCommit = sharedEntryCommit(db, vaultMutations.map { it.entryId }, epochKey)") &&
                mdbxStoreSource.contains("writeEntryMutation(db, mutation, epochKey, sharedCommit, now)") &&
                mdbxStoreSource.contains("writeEntryDeleteMutation(db, mutation, epochKey, sharedCommit, now)") &&
                mdbxStoreSource.contains("changedObjectIds: List<String> = listOf(objectId)") &&
                mdbxStoreSource.contains("val changedObjectIdsJson = JSONArray().apply") &&
                mdbxStoreSource.contains("encrypt(changedObjectIdsJson, epochKey)")
        )
    }

    @Test
    fun mdbxBatchMoveAndCopyUseSingleCommitBatchPaths() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val moveSupportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveSupport.kt"
        ).readText()
        val mixedMoveSupportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveMixedSupport.kt"
        ).readText()
        val batchCopyBody = moveSupportSource
            .substringAfter("internal suspend fun executePasswordBatchCopy(")
            .substringBefore("// 复制源密码的本地附件到新密码")
        val encryptedBatchCopyBody = viewModelSource
            .substringAfter("suspend fun createMdbxPasswordEntriesBatchAlreadyEncrypted(")
            .substringBefore("// =============== 自定义字段相关方法 ===============")

        assertTrue(
            "Moving existing passwords into MDBX must update the local Room rows as one batch after the single MDBX upsert, otherwise observers can fan the operation back out into per-item work.",
            repositorySource.contains("mdbxRepository?.upsertPasswords(entriesForMdbx)") &&
                repositorySource.contains("passwordEntryDao.updatePasswordEntries(entriesForMdbx)") &&
                !repositorySource.contains("entriesForMdbx.forEach { passwordEntryDao.updatePasswordEntry(it) }")
        )
        assertTrue(
            "Copying encrypted password rows into MDBX needs a dedicated batch path so it does not call addPasswordEntryWithResult once per selected item.",
            viewModelSource.contains("suspend fun createMdbxPasswordEntriesBatchAlreadyEncrypted(entries: List<PasswordEntry>): List<Long>") &&
                encryptedBatchCopyBody.contains("password = normalizedEntry.password") &&
                encryptedBatchCopyBody.contains("repository.insertPasswordEntries(encryptedEntries)")
        )
        assertTrue(
            "The normal password-page copy flow must collapse MDBX database/folder targets into one batch insert.",
            moveSupportSource.contains("addMdbxCopiedEntriesBatch: suspend (List<PasswordEntry>) -> List<Long>") &&
                batchCopyBody.contains("target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget || target is UnifiedMoveCategoryTarget.MdbxFolderTarget") &&
                batchCopyBody.contains("val copiedEntries = selectedEntries.map { entry -> buildCopiedEntryForTarget(entry, target) }") &&
                batchCopyBody.contains("val createdIds = addMdbxCopiedEntriesBatch(copiedEntries)") &&
                moveSupportSource.contains("viewModel.createMdbxPasswordEntriesBatchAlreadyEncrypted(entries)")
        )
        assertTrue(
            "The mixed aggregate page must use the same MDBX password batch insert; mixed selections must not create one MDBX commit per password row.",
            mixedMoveSupportSource.contains("target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget || target is UnifiedMoveCategoryTarget.MdbxFolderTarget") &&
                mixedMoveSupportSource.contains("val createdIds = viewModel.createMdbxPasswordEntriesBatchAlreadyEncrypted(copiedEntries)") &&
                mixedMoveSupportSource.contains("reportProgress(selectedEntries.size)")
        )
    }

    @Test
    fun mdbxPasswordCopyAlsoCopiesBoundTotp() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val moveSupportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveSupport.kt"
        ).readText()
        val mixedMoveSupportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveMixedSupport.kt"
        ).readText()
        val mdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()

        assertTrue(
            "Password copies into MDBX must remap any bound TOTP to the newly-created password id, otherwise Bitwarden logins with TOTP lose the authenticator after copy.",
            viewModelSource.contains("suspend fun copyBoundTotpsForPasswordCopies(idPairs: List<Pair<Long, Long>>): Int") &&
                viewModelSource.contains("boundPasswordId = newPassword.id") &&
                viewModelSource.contains("mdbxDatabaseId = newPassword.mdbxDatabaseId") &&
                viewModelSource.contains("bitwardenVaultId = null") &&
                viewModelSource.contains("TotpDataResolver.fromAuthenticatorKey(")
        )
        assertTrue(
            "The normal password-page copy flow must invoke bound-TOTP copy for MDBX targets using the source->new idPairs returned by the batch insert.",
            moveSupportSource.contains("viewModel.copyBoundTotpsForPasswordCopies(copiedIdPairs)") &&
                moveSupportSource.contains("target.passwordBatchStorageKey()?.startsWith(\"mdbx:\") == true")
        )
        assertTrue(
            "Mixed aggregate copy uses a separate MDBX password batch path, so it must build the same source->new idPairs and copy bound TOTP there too.",
            mixedMoveSupportSource.contains("val idPairs = createdIds.mapIndexedNotNull") &&
                mixedMoveSupportSource.contains("viewModel.copyBoundTotpsForPasswordCopies(idPairs)")
        )
        assertTrue(
            "MDBX password payload should retain authenticator_key, but it must pass through the portable write boundary so local-only ciphertext is never synced into MDBX.",
            mdbxStoreSource.contains("\"authenticator_key\",") &&
                mdbxStoreSource.contains("portableSensitiveValueForMdbx(") &&
                !mdbxStoreSource.contains(".put(\"authenticator_key\", entry.authenticatorKey)")
        )
    }

    @Test
    fun editingPasswordWithAuthenticatorReusesBoundTotpAndDoesNotClearPasswordWhenDeletingDuplicates() {
        val addPasswordSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readText()
        val saveTotpSection = addPasswordSource
            .substringAfter("// Save TOTP if authenticatorKey is provided")
            .substringBefore("} else if (currentAuthKey.isEmpty()")
        val savePasswordBoundTotpBody = totpViewModelSource
            .substringAfter("fun savePasswordBoundTotp(")
            .substringBefore("/**\r\n     * 根据ID获取TOTP项目")
            .ifBlank {
                totpViewModelSource
                    .substringAfter("fun savePasswordBoundTotp(")
                    .substringBefore("/**\n     * 根据ID获取TOTP项目")
            }
        val deleteTotpBody = totpViewModelSource
            .substringAfter("fun deleteTotpItem(")
            .substringBefore("// Virtual TOTP items are derived from password.authenticatorKey")

        assertTrue(
            "Editing a password with an authenticator must go through the bound-TOTP save path, which searches persisted rows by password id before updating.",
            saveTotpSection.contains("totpViewModel.savePasswordBoundTotps(") &&
                saveTotpSection.contains("passwordIds = savedPasswordIds.ifEmpty { listOf(firstPasswordId) }") &&
                savePasswordBoundTotpBody.contains("repository.getItemsByType(ItemType.TOTP).first()") &&
                savePasswordBoundTotpBody.contains("data.boundPasswordId == passwordId")
        )
        assertFalse(
            "Password editing must not use findTotpBySecret here; that method reads the filtered authenticator UI state and can miss the existing bound item, creating duplicates.",
            saveTotpSection.contains("findTotpBySecret(")
        )
        assertTrue(
            "The password editor must reuse a selected real TOTP first and create the first real bound TOTP when none exists, so the authenticator page can display and edit the saved name/key instead of opening an empty virtual item.",
            savePasswordBoundTotpBody.contains("val activeStoredItems = existingStoredTotps.mapNotNull") &&
                savePasswordBoundTotpBody.contains("val preferredItem = selectedSourceItem") &&
                savePasswordBoundTotpBody.contains("preferredTotpId != null && item.id == preferredTotpId") &&
                savePasswordBoundTotpBody.contains("id = preferredItem?.first?.id") &&
                savePasswordBoundTotpBody.contains("title = metadataSource?.first?.title ?: title") &&
                !savePasswordBoundTotpBody.contains("No persisted bound TOTP for passwordId=")
        )
        assertTrue(
            "The bound-TOTP save path should soft-delete extra persisted bindings for the same password.",
            savePasswordBoundTotpBody.contains("removeOtherBoundTotpsForPassword(") &&
                totpViewModelSource.contains("private suspend fun removeOtherBoundTotpsForPassword(") &&
                totpViewModelSource.contains("Soft-deleting extra bound TOTP")
        )
        assertTrue(
            "The authenticator list should collapse already-existing duplicate bound rows so users do not keep seeing one card per bad edit.",
            totpViewModelSource.contains("collapseDuplicateBoundStoredTotps(storedTotps)") &&
                totpViewModelSource.contains("private fun collapseDuplicateBoundStoredTotps(") &&
                totpViewModelSource.contains("val key = \"\$boundPasswordId|")
        )
        assertTrue(
            "Deleting one duplicated bound authenticator must not clear password.authenticatorKey while another equivalent bound item still exists.",
            deleteTotpBody.contains("hasEquivalentBoundItem") &&
                deleteTotpBody.contains("candidate.id == item.id") &&
                deleteTotpBody.contains("candidateData.boundPasswordId == boundId") &&
                deleteTotpBody.contains("&& !hasEquivalentBoundItem")
        )
    }

    @Test
    fun deletingPasswordBoundAuthenticatorWarnsAndStillDeletesPersistedTotp() {
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readText()
        val totpListContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readText()
        val deleteTotpBody = totpViewModelSource
            .substringAfter("fun deleteTotpItem(")
            .substringBefore("// Virtual TOTP items are derived from password.authenticatorKey")

        assertFalse(
            "Deleting a real bound authenticator must not abort just because the bound password row is missing.",
            deleteTotpBody.contains("passwordRepository.getPasswordEntryById(boundId) ?: return@launch")
        )
        assertTrue(
            "Batch deletion must exclude every item in the same delete request when deciding whether password.authenticatorKey should remain.",
            totpViewModelSource.contains("fun deleteTotpItems(") &&
                totpViewModelSource.contains("deletingItemIds: Set<Long> = emptySet()") &&
                deleteTotpBody.contains("candidate.id in deletingItemIds")
        )
        assertTrue(
            "The authenticator page must warn before deleting password-bound authenticators, including multi-select batches.",
            totpListContentSource.contains("BoundTotpDeleteWarningDialog(") &&
                totpListContentSource.contains("pendingBoundSingleDelete") &&
                totpListContentSource.contains("pendingBoundBatchDelete") &&
                totpListContentSource.contains("viewModel.deleteTotpItems(toDelete)")
        )
    }

    @Test
    fun saveFailuresAreReportedWithNonSecretDiagnostics() {
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readText()
        val savePasswordsAcrossTargetsBody = passwordViewModelSource
            .substringAfter("fun savePasswordsAcrossTargets(")
            .substringBefore("private suspend fun canWriteKeePassTargets")
        val saveGroupedBody = passwordViewModelSource
            .substringAfter("private suspend fun saveGroupedPasswordsInternal(")
            .substringBefore("private fun PasswordEntry.isPureMdbxCreateTarget()")
        val saveTotpItemBody = totpViewModelSource
            .substringAfter("fun saveTotpItem(")
            .substringBefore("fun saveTotpAcrossTargets(")
        val saveTotpAcrossTargetsBody = totpViewModelSource
            .substringAfter("fun saveTotpAcrossTargets(")
            .substringBefore("private suspend fun saveTotpItemInternal(")

        assertTrue(
            "Password saves must catch storage-layer exceptions, log target/id diagnostics, and still invoke the UI callback with null.",
            savePasswordsAcrossTargetsBody.contains("requestedTargetKeys") &&
                savePasswordsAcrossTargetsBody.contains("catch (e: Exception)") &&
                savePasswordsAcrossTargetsBody.contains("savePasswordsAcrossTargets crashed") &&
                savePasswordsAcrossTargetsBody.contains("onComplete(saveResult.firstPasswordId)") &&
                savePasswordsAcrossTargetsBody.contains("onCompleteWithIds(saveResult.firstPasswordId, saveResult.savedPasswordIds)")
        )
        assertTrue(
            "Grouped password updates must not silently report success when an existing row update is rejected.",
            saveGroupedBody.contains("val updated = updatePasswordEntryInternal(") &&
                saveGroupedBody.contains("entry = updatedEntry") &&
                saveGroupedBody.contains("saveGroupedPasswords aborted due to password update failure") &&
                saveGroupedBody.contains("return null")
        )
        assertTrue(
            "Legacy TOTP saves must use Log.e diagnostics instead of printStackTrace so rare user logs identify the failed storage target.",
            saveTotpItemBody.contains("Log.e(") &&
                saveTotpItemBody.contains("saveTotpItem failed id=") &&
                !saveTotpItemBody.contains("printStackTrace()")
        )
        assertTrue(
            "Multi-target TOTP saves must log empty targets, current target failures, and caught exceptions without logging TOTP secrets.",
            saveTotpAcrossTargetsBody.contains("target list is empty") &&
                saveTotpAcrossTargetsBody.contains("failed current target=") &&
                saveTotpAcrossTargetsBody.contains("saveTotpAcrossTargets crashed") &&
                !saveTotpAcrossTargetsBody.contains("secret=")
        )
    }

    @Test
    fun mdbxPasswordCopiesToMonicaLocalDoNotKeepMdbxIdentity() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val moveSupportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveSupport.kt"
        ).readText()
        val localCopyBody = viewModelSource
            .substringAfter("private fun buildMonicaLocalCopy(")
            .substringBefore("fun addSecureItem")
        val batchLocalCopyBody = moveSupportSource
            .substringAfter("internal fun buildCopiedEntryForTarget(")
            .substringBefore("is UnifiedMoveCategoryTarget.BitwardenVaultTarget")

        assertTrue(
            "A single MDBX password copied into Monica local must clear MDBX ownership and replica identity, otherwise local filters/search treat it as MDBX or collapse it with the source replica.",
            localCopyBody.contains("mdbxDatabaseId = null") &&
                localCopyBody.contains("mdbxFolderId = null") &&
                localCopyBody.contains("replicaGroupId = null")
        )
        assertTrue(
            "Batch copies into Monica local targets have three branches (uncategorized, archive, category); each must clear MDBX ownership and replica identity.",
            batchLocalCopyBody.countOccurrences("mdbxDatabaseId = null") >= 3 &&
                batchLocalCopyBody.countOccurrences("mdbxFolderId = null") >= 3 &&
                batchLocalCopyBody.countOccurrences("replicaGroupId = null") >= 3
        )
    }

    @Test
    fun editingPasswordReplicasPreservesExistingTargets() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val pickerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/MultiStorageTargetPickerBottomSheet.kt"
        ).readText()
        val saveAcrossTargetsBody = viewModelSource
            .substringAfter("fun savePasswordsAcrossTargets(")
            .substringBefore("private suspend fun canWriteKeePassTargets")

        assertFalse(
            "Editing a replica group must not delete existing targets just because they are absent from the edited selection; the UI contract says existing targets are preserved.",
            saveAcrossTargetsBody.contains("deletePasswordEntriesBatch(staleReplicas)")
        )
        assertTrue(
            "The storage target picker must treat existing targets as locked while editing, otherwise a missed click can remove a storage replica and look like data loss.",
            pickerSource.contains("val singleModeAllowed = lockedTargetKeys.isEmpty()") &&
                pickerSource.contains("if (!singleModeAllowed) return") &&
                pickerSource.contains("it.stableKey in lockedTargetKeys") &&
                pickerSource.contains("targetLocked = chip.target.stableKey in lockedTargetKeys") &&
                pickerSource.contains("if (targetLocked)")
        )
    }

    @Test
    fun localPasswordDaoQueriesExcludeMdbxRows() {
        val daoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordEntryDao.kt"
        ).readText()
        val localDeleteBody = daoSource
            .substringAfter("DELETE FROM password_entries\n        WHERE bitwarden_vault_id IS NULL")
            .substringBefore("suspend fun deleteAllLocalPasswordEntries")
        val localCountBody = daoSource
            .substringAfter("suspend fun getLastBitwardenRevisionDate")
            .substringBefore("suspend fun getAllKeePassEntries")

        assertTrue(
            "Local password cleanup must not delete MDBX rows; MDBX has its own ownership column and cleanup path.",
            localDeleteBody.contains("AND mdbx_database_id IS NULL")
        )
        assertTrue(
            "Local password counters/lists must exclude MDBX rows, otherwise MDBX-owned records can be treated as Monica local data.",
            localCountBody.countOccurrences("mdbx_database_id IS NULL") >= 3
        )
    }

    @Test
    fun mdbxPasswordRefreshRescuesLocalRowsInsteadOfDeletingThem() {
        val daoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordEntryDao.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val importBody = mdbxViewModelSource
            .substringAfter("private suspend fun importEntriesFromVault(")
            .substringBefore("private suspend fun clearImportedEntries")

        assertTrue(
            "MDBX import reconciliation should inspect active MDBX rows for local-vs-vault reconciliation.",
            daoSource.contains("SELECT * FROM password_entries WHERE mdbx_database_id = :databaseId AND isDeleted = 0 AND isArchived = 0")
        )
        assertTrue(
            "MDBX import must classify vault-missing rows and write active local rows back to MDBX instead of deleting them.",
            importBody.contains("deletedPasswordEntryIds") &&
                importBody.contains("[MDBX][orphan-classify] type=password") &&
                importBody.contains("rescueMissingRemoteMdbxPasswordRows(") &&
                importBody.contains("rescueRemoteDeletedMdbxPasswordRows(") &&
                importBody.contains("rescueMissingRemoteMdbxSecureItemRows(") &&
                importBody.contains("rescueRemoteDeletedMdbxSecureItemRows(") &&
                importBody.contains("rescueMissingRemoteMdbxPasskeyRows(") &&
                importBody.contains("rescueRemoteDeletedMdbxPasskeyRows(")
        )
        assertTrue(
            "MDBX rescue must use the repository boundary so tombstones are cleared in the vault file.",
            mdbxViewModelSource.contains("vaultStore.upsertPasswords(rowsToRescue)") &&
                mdbxViewModelSource.contains("vaultStore.upsertSecureItems(itemsToRescue)") &&
                mdbxViewModelSource.contains("vaultStore.upsertPasskeys(passkeysToRescue)") &&
                mdbxViewModelSource.contains("[MDBX][orphan-rescue]")
        )
        assertFalse(
            "Do not bring back orphan soft-delete cleanup; it deletes autofill-created/restored MDBX rows after sync.",
            mdbxViewModelSource.contains("reason = \"orphaned_remote_entry\"") ||
                mdbxViewModelSource.contains("softDeleteMdbxPasswordRows(") ||
                mdbxViewModelSource.contains("[MDBX][password-soft-delete]") ||
                importBody.contains("secureItemDao.deleteItemById(it.id)") ||
                importBody.contains("passkeyDao.deleteByRecordId(it.id)")
        )
        assertTrue(
            "Duplicate MDBX Room rows should be diagnosed and preserved, not deleted during import reconciliation.",
            mdbxViewModelSource.contains("[MDBX][duplicate-preserve] type=password") &&
                mdbxViewModelSource.contains("[MDBX][duplicate-preserve] type=secure_item")
        )
    }

    @Test
    fun autofillMdbxSavesUseMdbxRepositoryAndInitialStorageTarget() {
        val resolverSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSaveStorageResolver.kt"
        ).readText()
        val legacySource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSaveActivity.kt"
        ).readText()
        val transparentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSaveTransparentActivity.kt"
        ).readText()
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()

        assertTrue(
            "Autofill save needs a shared resolver for the current password-list MDBX target.",
            resolverSource.contains("resolveAutofillSaveInitialTarget(") &&
                resolverSource.contains("lastPasswordCategoryFilterType") &&
                resolverSource.contains("SAVED_FILTER_MDBX_DATABASE") &&
                resolverSource.contains("SAVED_FILTER_MDBX_FOLDER") &&
                resolverSource.contains("withAutofillSaveInitialTarget")
        )
        assertTrue(
            "Legacy autofill save must create the engine-aware MDBX repository and write through the MDBX-aware password repository path.",
            legacySource.contains("val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(") &&
                legacySource.contains("mdbxRepository = mdbxRepository") &&
                legacySource.contains("resolveInitialTarget()") &&
                legacySource.contains(".withAutofillSaveInitialTarget(initialTarget)") &&
                legacySource.contains("passwordRepository.insertPasswordEntries(listOf(newEntry)).singleOrNull()") &&
                legacySource.contains("[MDBX][autofill-save-complete] source=legacy")
        )
        assertTrue(
            "Transparent autofill save must pass the resolved MDBX database/folder into AddEditPasswordScreen.",
            transparentSource.contains("produceState<AutofillSaveInitialTarget?>") &&
                transparentSource.contains("val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(") &&
                transparentSource.contains("mdbxRepository = mdbxRepository") &&
                transparentSource.contains("mdbxDatabasesFallback = resolvedInitialTarget.mdbxDatabasesFallback") &&
                transparentSource.contains("initialMdbxDatabaseId = resolvedInitialTarget.mdbxDatabaseId") &&
                transparentSource.contains("initialMdbxFolderId = resolvedInitialTarget.mdbxFolderId") &&
                transparentSource.contains("[MDBX][autofill-save-complete] source=transparent")
        )
        assertTrue(
            "MDBX object ids must preserve only real password object ids; accepting bare UUID replica ids creates false orphan rows.",
            repositorySource.contains("?.takeIf { it.isMdbxPasswordObjectId() }") &&
                repositorySource.contains("startsWith(\"password:\")") &&
                !repositorySource.contains("?.takeIf { it.isNotBlank() }")
        )
        assertFalse(
            "Autofill save activities must not use a bare PasswordRepository that cannot write MDBX.",
            legacySource.contains("PasswordRepository(database.passwordEntryDao())") ||
                transparentSource.contains("PasswordRepository(database.passwordEntryDao())")
        )
    }

    @Test
    fun trashRestoreWritesMdbxItemsThroughRepositories() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TrashViewModel.kt"
        ).readText()
        val applyRestoreBody = source
            .substringAfter("private suspend fun applyLocalRestore(")
            .substringBefore("private suspend fun rollbackLocalRestore")

        assertTrue(
            "Trash restore must inject an MDBX repository, otherwise MDBX restore only flips Room flags and sync deletes it again.",
            source.contains("private val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(") &&
                source.contains("mdbxRepository = mdbxRepository") &&
                source.contains("private val secureItemRepository = SecureItemRepository(") &&
                source.contains("database.secureItemDao(),") &&
                source.contains("mdbxRepository,")
        )
        assertTrue(
            "Trash restore must update via repositories so MDBX tombstones are cleared in the vault.",
            applyRestoreBody.contains("passwordRepository.updatePasswordEntry(restoredEntry)") &&
                applyRestoreBody.contains("secureItemRepository.updateItem(restoredItem)") &&
                source.contains("[MDBX][trash-restore] type=password") &&
                source.contains("[MDBX][trash-restore] type=secure_item")
        )
        assertFalse(
            "Do not restore MDBX items by DAO-only updates; that leaves the MDBX file tombstoned.",
            applyRestoreBody.contains("database.passwordEntryDao().update") ||
                applyRestoreBody.contains("database.secureItemDao().update")
        )
    }

    @Test
    fun totpQrScanFillsAddScreenAndQuickScanUsesCurrentStorageTarget() {
        val addTotpSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditTotpScreen.kt"
        ).readText()
        val mainActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()
        val quickScanRoute = mainActivitySource
            .substringAfter("composable(Screen.QuickTotpScan.route)")
            .substringBefore("// 导出数据")

        assertTrue(
            "AddEditTotpScreen must consume raw QR results inside the form so existing rememberSaveable state is updated after returning from scanner.",
            addTotpSource.contains("pendingQrResult: String? = null") &&
                addTotpSource.contains("onConsumePendingQrResult: () -> Unit = {}") &&
                addTotpSource.contains("LaunchedEffect(pendingQrResult)") &&
                addTotpSource.contains("importTotpFromUri(qrValue)") &&
                mainActivitySource.contains("pendingQrResult = qrResult") &&
                mainActivitySource.contains("onConsumePendingQrResult = {")
        )
        assertTrue(
            "Quick TOTP scan must save to the current validator filter target instead of always creating Monica-local items.",
            quickScanRoute.contains("fun quickScanTargetsForCurrentFilter(): List<StorageTarget>") &&
                quickScanRoute.contains("is TotpCategoryFilter.MdbxDatabase -> listOf(StorageTarget.Mdbx(filter.databaseId))") &&
                quickScanRoute.contains("totpViewModel.saveTotpAcrossTargets(") &&
                quickScanRoute.contains("targets = quickScanTargetsForCurrentFilter()")
        )
        assertFalse(
            "Quick TOTP scan must not call saveTotpItem directly because that bypasses MDBX/KeePass/Bitwarden targets.",
            quickScanRoute.contains("totpViewModel.saveTotpItem(")
        )
    }

    @Test
    fun mdbxSnapshotsAndBatchCreatesStayReadableAndCoalesced() {
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val daoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordEntryDao.kt"
        ).readText()
        val saveGroupedBody = passwordViewModelSource
            .substringAfter("private suspend fun saveGroupedPasswordsInternal(")
            .substringBefore("// =============== 自定义字段相关方法 ===============")

        assertTrue(
            "The automatic snapshot button should clear automatic snapshots on demand instead of retaining the default 20 and looking like it did nothing.",
            mdbxViewModelSource.contains("vaultStore.pruneAutomaticSnapshots(databaseId, keepCount = 0)") &&
                mdbxViewModelSource.contains("strings.get(R.string.mdbx_ui_snapshot_pruned_count, deletedCount)") &&
                managerSource.contains("Text(strings.get(R.string.mdbx_ui_snapshot_clear_automatic))")
        )
        assertTrue(
            "Snapshot UI should use user-facing increment/full wording and not expose the unexplained Delta label.",
            managerSource.contains("strings.get(R.string.mdbx_ui_snapshot_full)") &&
                managerSource.contains("strings.get(R.string.mdbx_ui_snapshot_incremental)") &&
                managerSource.contains("SnapshotInfoPill(if (snapshot.isFull) strings.get(R.string.mdbx_ui_full) else strings.get(R.string.mdbx_status_delta))") &&
                managerSource.contains("mdbx_snapshot_create_when_changed") &&
                !managerSource.contains("Delta 快照") &&
                !managerSource.contains("\"Delta\"")
        )
        assertTrue(
            "Snapshot rollback must require a second confirmation because it mutates the current MDBX database.",
            managerSource.contains("var pendingRevertSnapshot by remember { mutableStateOf<MdbxSnapshotSummary?>(null) }") &&
                managerSource.contains("AlertDialog(") &&
                managerSource.contains("title = { Text(strings.get(R.string.mdbx_ui_snapshot_restore_title)) }") &&
                managerSource.contains("Text(strings.get(R.string.mdbx_ui_snapshot_restore_confirm))") &&
                managerSource.contains("onRevertSnapshot(snapshot.snapshotId)") &&
                managerSource.contains("onRevert = { pendingRevertSnapshot = snapshot }") &&
                !managerSource.contains("onRevert = { onRevertSnapshot(snapshot.snapshotId) }")
        )
        assertTrue(
            "Deleted commit details must show an object-level delete action instead of the low-level field diff `删除: 存在 -> 已删除`.",
            managerSource.contains("private fun CommitObjectChangeCard(") &&
                managerSource.contains("ObjectChangeKind.DELETED -> strings.get(R.string.mdbx_ui_history_action_deleted, objectLabel)") &&
                managerSource.contains("if (objectChangeKind() != ObjectChangeKind.MODIFIED) return emptyList()") &&
                !managerSource.contains("fieldLabel = if (currentDeleted) strings.get(R.string.delete) else strings.get(R.string.restore)") &&
                !managerSource.contains("before = if (previousDeleted == true) \"已删除\" else \"存在\"")
        )
        assertTrue(
            "Pure MDBX multi-password creates must be collected and inserted through a batch repository path so MDBX gets one shared commit.",
            saveGroupedBody.contains("val pendingMdbxCreates = mutableListOf<Pair<Int, PasswordEntry>>()") &&
                saveGroupedBody.contains("newEntry.isPureMdbxCreateTarget()") &&
                saveGroupedBody.contains("pendingMdbxCreates += index to newEntry") &&
                saveGroupedBody.contains("createMdbxPasswordEntriesBatch(pendingMdbxCreates.map { it.second })") &&
                saveGroupedBody.contains("repository.insertPasswordEntries(encryptedEntries)") &&
                saveGroupedBody.contains("deletePasswordEntriesBatch(entriesToDelete)") &&
                !saveGroupedBody.contains("toDelete.forEach")
        )
        assertTrue(
            "Room and PasswordRepository need a true batch insert API that forwards MDBX rows through upsertPasswords once.",
            daoSource.contains("suspend fun insertPasswordEntries(entries: List<PasswordEntry>): List<Long>") &&
                repositorySource.contains("suspend fun insertPasswordEntries(entries: List<PasswordEntry>): List<Long>") &&
                repositorySource.contains("passwordEntryDao.insertPasswordEntries(normalizedEntries)") &&
                repositorySource.contains("mdbxRepository?.upsertPasswords(persistedEntries.filter { it.mdbxDatabaseId != null })")
        )
    }

    @Test
    fun webDavMonicaConfigBackupIncludesSecurityAutofillAndBlacklistSettings() {
        val webDavSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()
        val settingsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/SettingsManager.kt"
        ).readText()
        val backupScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt"
        ).readText()

        assertTrue(
            "Page-adjustment backup must include the user-facing security/autofill switches, otherwise WebDAV config restore silently loses them.",
            settingsSource.contains("val securityAnalysisAutoEnabled: Boolean = false") &&
                settingsSource.contains("val passwordDetailSecurityAnalysisEnabled: Boolean = true") &&
                settingsSource.contains("val autofillAuthRequired: Boolean = true") &&
                settingsSource.contains("securityAnalysisAutoEnabled = settings.securityAnalysisAutoEnabled") &&
                settingsSource.contains("passwordDetailSecurityAnalysisEnabled = settings.passwordDetailSecurityAnalysisEnabled") &&
                settingsSource.contains("autofillAuthRequired = settings.autofillAuthRequired") &&
                settingsSource.contains("preferences[SECURITY_ANALYSIS_AUTO_ENABLED_KEY] = snapshot.securityAnalysisAutoEnabled") &&
                settingsSource.contains("preferences[PASSWORD_DETAIL_SECURITY_ANALYSIS_ENABLED_KEY]") &&
                settingsSource.contains("preferences[AUTOFILL_AUTH_REQUIRED_KEY] = snapshot.autofillAuthRequired")
        )
        assertTrue(
            "WebDAV page-adjustment JSON must pass these switch fields through both export and restore layers.",
            webDavSource.contains("val securityAnalysisAutoEnabled: Boolean = false") &&
                webDavSource.contains("val passwordDetailSecurityAnalysisEnabled: Boolean = true") &&
                webDavSource.contains("val autofillAuthRequired: Boolean = true") &&
                webDavSource.contains("pageAdjustmentSettingsSnapshot.securityAnalysisAutoEnabled") &&
                webDavSource.contains("pageAdjustmentSettingsSnapshot.passwordDetailSecurityAnalysisEnabled") &&
                webDavSource.contains("pageAdjustmentSettingsSnapshot.autofillAuthRequired") &&
                webDavSource.contains("pageAdjustmentBackup.securityAnalysisAutoEnabled") &&
                webDavSource.contains("pageAdjustmentBackup.passwordDetailSecurityAnalysisEnabled") &&
                webDavSource.contains("pageAdjustmentBackup.autofillAuthRequired")
        )
        assertTrue(
            "Autofill blacklist is distinct from save-blocked targets and must be backed up as its own Monica config file.",
            webDavSource.contains("private data class AutofillBlacklistBackupEntry(") &&
                webDavSource.contains("val enabled: Boolean = true") &&
                webDavSource.contains("val packages: List<String> = emptyList()") &&
                webDavSource.contains("val autofillBlacklistEnabled = autofillPreferences.isBlacklistEnabled.first()") &&
                webDavSource.contains("val autofillBlacklistPackages = autofillPreferences.blacklistPackages.first()") &&
                webDavSource.contains("File(monicaConfigDir, \"autofill_blacklist.json\")") &&
                webDavSource.contains("json.encodeToString(") &&
                webDavSource.contains("AutofillBlacklistBackupEntry.serializer()") &&
                webDavSource.contains("normalizedEntryName == \"monica_config/autofill_blacklist.json\"") &&
                webDavSource.contains("setBlacklistEnabled(autofillBlacklistBackup.enabled)") &&
                webDavSource.contains("setBlacklistPackages(normalizedPackages)") &&
                backupScreenSource.contains("\"autofill_blacklist.json\" -> \"自动填充黑名单\"")
        )
        assertTrue(
            "Legacy aggregate Monica config restore should understand blacklist fields when older backups carry them there.",
            webDavSource.contains("val autofillBlacklistEnabled: Boolean? = null") &&
                webDavSource.contains("val autofillBlacklistPackages: List<String>? = null") &&
                webDavSource.contains("monicaConfigBackup.autofillBlacklistEnabled != null") &&
                webDavSource.contains("monicaConfigBackup.autofillBlacklistPackages != null") &&
                webDavSource.contains("monicaConfigBackup.autofillBlacklistEnabled?.let") &&
                webDavSource.contains("normalizedPackages?.let")
        )
        assertFalse(
            "Autofill blacklist must not be collapsed into the save-blocked-targets backup; these are different settings in the UI.",
            webDavSource.contains("AutofillSaveBlockedTargetsBackupEntry(\n    val blockedTargets: List<String> = emptyList(),\n    val packages")
        )
    }

    @Test
    fun webDavBackupsUseMonicaLocalContentScope() {
        val webDavHelperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()
        val webDavScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt"
        ).readText()
        val autoBackupWorkerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/workers/AutoBackupWorker.kt"
        ).readText()

        assertTrue(
            "WebDAV helper must forward the requested backup content scope into createBackupZip.",
            webDavHelperSource.contains("contentScope: BackupContentScope = BackupContentScope.MONICA_LOCAL_ONLY") &&
                webDavHelperSource.contains("contentScope = contentScope")
        )
        assertTrue(
            "Manual WebDAV backup must use the Monica-local scope so external caches are not exported as Monica-local entries.",
            webDavScreenSource.contains("import takagi.ru.monica.utils.BackupContentScope") &&
                webDavScreenSource.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY")
        )
        assertTrue(
            "Automatic WebDAV backup must use the same Monica-local scope as manual WebDAV backup.",
            autoBackupWorkerSource.contains("import takagi.ru.monica.utils.BackupContentScope") &&
            autoBackupWorkerSource.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY")
        )
    }

    @Test
    fun localZipExportUsesAnExplicitEncryptionChoice() {
        val dataExportSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DataExportImportViewModel.kt"
        ).readText()
        val webDavHelperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()
        val exportScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/ExportDataScreen.kt"
        ).readText()
        val prepareZipBackupBody = dataExportSource.substringAfter("suspend fun prepareZipBackup(")
            .substringBefore("suspend fun writePreparedZipBackup(")
        val copyZipBody = dataExportSource.substringAfter("private suspend fun copyZipFileToOutputUri(")
            .substringBefore("private fun zipBackupExportMessage(")
        val exportZipBackupBody = dataExportSource.substringAfter("suspend fun exportZipBackup(")
            .substringBefore("suspend fun importZipBackup(")

        assertTrue(
            "Local export must pass only the user-selected backup password and must not silently inherit remote encryption.",
            prepareZipBackupBody.contains("allowBackupEncryption = !backupEncryptionPassword.isNullOrBlank()") &&
                prepareZipBackupBody.contains("backupEncryptionPassword = backupEncryptionPassword")
        )
        assertTrue(
            "Local export must validate either the generated ZIP or encrypted backup and the bytes written to the selected document before reporting success.",
            prepareZipBackupBody.contains("validatePreparedBackupFile(zipFile)") &&
                copyZipBody.contains("validatePlainZipStream") &&
                copyZipBody.contains("hasEncryptedFileHeader") &&
                copyZipBody.contains("openExportOutputStream(outputUri)") &&
                copyZipBody.contains("copiedBytes <= 0L") &&
                copyZipBody.contains("copiedBytes != expectedBytes")
        )
        assertTrue(
            "The export screen should prepare and validate the selected backup before ACTION_CREATE_DOCUMENT so a generation failure does not leave a 0B user-visible file.",
            exportScreenSource.contains("var pendingPreparedZipBackup") &&
                exportScreenSource.contains("onPrepareZip(backupPreferences, backupPassword)") &&
                exportScreenSource.contains("pendingPreparedZipBackup = backup") &&
                exportScreenSource.contains("onWritePreparedZip(safeUri, preparedZipBackup.first, preparedZipBackup.second)") &&
                exportScreenSource.indexOf("onPrepareZip(backupPreferences, backupPassword)") <
                    exportScreenSource.lastIndexOf("launchCreateDocument()")
        )
        assertTrue(
            "The legacy one-step export API should clean up its prepared temp ZIP after copying.",
            exportZipBackupBody.contains("prepareZipBackup(") &&
                exportZipBackupBody.contains("writePreparedZipBackup(outputUri, zipFile, message)") &&
                exportZipBackupBody.contains("preparedFile?.delete()")
        )
        assertTrue(
            "Backup ZIP creation should only return an encrypted .enc.zip when the caller allows backup encryption and a resolved password exists.",
            webDavHelperSource.contains("allowBackupEncryption: Boolean = true") &&
                webDavHelperSource.contains("val backupEncryptPassword = BackupEncryptionPolicy.resolvePassword(") &&
                webDavHelperSource.contains("val shouldEncryptBackup = backupEncryptPassword != null") &&
                webDavHelperSource.contains("val finalFile = if (shouldEncryptBackup)")
        )
        assertFalse(
            "Returning .enc.zip solely because the persisted WebDAV encryption switch is on breaks local .zip export.",
            webDavHelperSource.contains("val finalFile = if (enableEncryption)")
        )
    }

    @Test
    fun trashScopeSelectorUsesUnifiedChipMenuInsteadOfLegacyBottomSheet() {
        val timelineSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/TimelineScreen.kt"
        ).readText()

        assertFalse(
            "Trash/category scope selection should use the compact chip menu like other pages, not the legacy bottom sheet.",
            timelineSource.contains("UnifiedCategoryFilterBottomSheet")
        )
        assertTrue(
            "The history/trash folder buttons should anchor the compact category chip menu next to the top-bar action.",
            timelineSource.contains("UnifiedCategoryFilterChipMenuDropdown") &&
                timelineSource.contains("UnifiedCategoryFilterChipMenu(") &&
                timelineSource.contains("private fun TrashScopeFilterChipMenu(") &&
                timelineSource.contains("scopeMenu: @Composable () -> Unit = {}")
        )
    }

    @Test
    fun webDavBackupWorkerSharesCoordinatorQueue() {
        val autoBackupWorkerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/workers/AutoBackupWorker.kt"
        ).readText()

        assertTrue(
            "WebDAV manual and scheduled backup workers must share SyncTaskRunner so two WorkManager entries cannot run two real backups at once.",
            autoBackupWorkerSource.contains("SyncTarget.Backup(SyncBackupProvider.WEBDAV)") &&
                autoBackupWorkerSource.contains("SyncTaskRunner.requestAndAwait(request)") &&
                autoBackupWorkerSource.contains("networkPolicy = SyncNetworkPolicy.REQUIRED") &&
                autoBackupWorkerSource.contains("SyncTrigger.MANUAL") &&
                autoBackupWorkerSource.contains("SyncTrigger.BACKUP_SCHEDULE") &&
                autoBackupWorkerSource.contains("is SyncTaskAwaitResult.Merged") &&
                autoBackupWorkerSource.contains("merged_with_running_backup")
        )
    }

    @Test
    fun webDavBackupScreenManualCreateSharesCoordinatorQueue() {
        val webDavScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt"
        ).readText()
        val manualCreateBody = webDavScreenSource
            .substringAfter("val backupTarget = SyncTarget.Backup(SyncBackupProvider.WEBDAV)")
            .substringBefore("Text(stringResource(R.string.webdav_create_new_backup))")

        assertTrue(
            "WebDAV screen manual create must share the same backup:webdav coordinator queue as AutoBackupWorker.",
            webDavScreenSource.contains("val backupTarget = SyncTarget.Backup(SyncBackupProvider.WEBDAV)") &&
                manualCreateBody.contains("SyncTaskRunner.requestAndAwait(") &&
                manualCreateBody.contains("trigger = SyncTrigger.MANUAL") &&
                manualCreateBody.contains("networkPolicy = SyncNetworkPolicy.REQUIRED") &&
                manualCreateBody.contains("WEBDAV_SCREEN_MANUAL") &&
                manualCreateBody.contains("merged_with_running_backup")
        )
        assertTrue(
            "WebDAV screen manual create should keep existing permanent Monica-local backup behavior while moving scheduling into the coordinator.",
            manualCreateBody.contains("isPermanent = true") &&
                manualCreateBody.contains("isManualTrigger = true") &&
                manualCreateBody.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY") &&
                manualCreateBody.contains(".getOrThrow()")
        )
        assertTrue(
            "WebDAV screen manual create must release UI loading state even when coordinator skips, blocks, or fails.",
            manualCreateBody.contains("finally") &&
                manualCreateBody.contains("isLoading = false") &&
                manualCreateBody.contains("isBackupInProgress = false")
        )
    }

    @Test
    fun webDavManualBackupWorkerDoesNotReplaceRunningWorker() {
        val autoBackupManagerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/AutoBackupManager.kt"
        ).readText()
        val triggerBody = autoBackupManagerSource.substringAfter("fun triggerBackupNow(): Boolean")
            .substringBefore("fun getLastBackupStatus()")

        assertTrue(
            "Manual WebDAV backup must not use REPLACE because it can cancel a running Worker while SyncTaskRunner owns the actual backup.",
            triggerBody.contains("ExistingWorkPolicy.KEEP")
        )
        assertFalse(
            "Do not bring back REPLACE for manual WebDAV backup; duplicate taps should coalesce, not cancel the running backup.",
            triggerBody.contains("ExistingWorkPolicy.REPLACE")
        )
    }

    @Test
    fun oneDriveBackupScreenManualCreateUsesCoordinatorQueue() {
        val oneDriveScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/OneDriveBackupScreen.kt"
        ).readText()
        val manualCreateBody = oneDriveScreenSource
            .substringAfter("val backupTarget = SyncTarget.Backup(SyncBackupProvider.ONEDRIVE)")
            .substringBefore("Text(if (creatingBackup) stringResource(R.string.webdav_backup_in_progress)")

        assertTrue(
            "OneDrive screen manual backup must be represented as backup:onedrive work in SyncTaskRunner.",
            oneDriveScreenSource.contains("val backupTarget = SyncTarget.Backup(SyncBackupProvider.ONEDRIVE)") &&
                manualCreateBody.contains("SyncTaskRunner.requestAndAwait(") &&
                manualCreateBody.contains("trigger = SyncTrigger.MANUAL") &&
                manualCreateBody.contains("networkPolicy = SyncNetworkPolicy.REQUIRED") &&
                manualCreateBody.contains("ONEDRIVE_SCREEN_MANUAL") &&
                manualCreateBody.contains("merged_with_running_backup")
        )
        assertTrue(
            "OneDrive backup must use the same Monica-local backup scope as WebDAV, so cached external database rows are not included.",
            manualCreateBody.contains("val localPasswords = passwordRepository.getAllLocalPasswordEntries()") &&
                manualCreateBody.contains("val localSecureItems = secureItemRepository.getAllLocalItems()") &&
                manualCreateBody.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY") &&
                !manualCreateBody.contains("contentScope = BackupContentScope.ALL_OFFLINE") &&
                manualCreateBody.contains("backupHelper.uploadBackup(file, isPermanent = true).getOrThrow()") &&
                manualCreateBody.contains("file.delete()")
        )
        assertTrue(
            "OneDrive screen manual backup must release its loading state after completed, skipped, blocked, canceled, or failed coordinator outcomes.",
            manualCreateBody.contains("finally") &&
                manualCreateBody.contains("creatingBackup = false")
        )
        assertTrue(
            "OneDrive backup counts must match the Monica-local backup scope rather than counting external database cache rows.",
            oneDriveScreenSource.contains("passwordCount = passwordRepository.getLocalEntriesCount()") &&
                oneDriveScreenSource.contains("authenticatorCount = secureItemRepository.getLocalItemCountByType") &&
                oneDriveScreenSource.contains("passkeyDao().getLocalPasskeyCount()")
        )
    }

    @Test
    fun webDavBackupContentCountsMatchMonicaLocalBackupScope() {
        val webDavScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt"
        ).readText()
        val launchedEffectBody = webDavScreenSource.substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("Scaffold(")

        assertTrue(
            "WebDAV backup count labels must reflect the same Monica-local dataset that WebDAV actually backs up.",
            launchedEffectBody.contains("passwordCount = passwordRepository.getLocalEntriesCount()") &&
                launchedEffectBody.contains("authenticatorCount = secureItemRepository.getLocalItemCountByType") &&
                launchedEffectBody.contains("documentCount = secureItemRepository.getLocalItemCountByType") &&
                launchedEffectBody.contains("bankCardCount = secureItemRepository.getLocalItemCountByType") &&
                launchedEffectBody.contains("noteCount = secureItemRepository.getLocalItemCountByType") &&
                !launchedEffectBody.contains("passwordRepository.getAllPasswordEntries().first()") &&
                !launchedEffectBody.contains("secureItemRepository.getAllItems().first()")
        )
    }

    @Test
    fun webDavReplaceRestoreClearsLocalDataOnlyAfterBackupIsParsedAndValidated() {
        val webDavHelperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()
        val restoreBody = webDavHelperSource.substringAfter("suspend fun restoreFromBackupFile(")
            .substringBefore("/**\n     * 下载并恢复备份")
        val beforeZipScan = restoreBody.substringBefore("ZipInputStream(FileInputStream(zipFile)).use")
        val afterRestoreCounts = restoreBody.substringAfter("val restoredCounts = ItemCounts(")
            .substringBefore("val report = RestoreReport(")

        assertFalse(
            "Replace-local restore must not clear existing local data before the backup zip has been parsed. A corrupt or empty WebDAV backup could otherwise erase user data.",
            beforeZipScan.contains("deleteAllLocalPasswordEntries()") ||
                beforeZipScan.contains("deleteAllLocalItemsByType") ||
                beforeZipScan.contains("deleteAllLocalPasskeys()")
        )
        assertTrue(
            "Replace-local restore must clear local data only after parse succeeds and the backup contains core restorable data.",
            webDavHelperSource.contains("private suspend fun clearLocalDataForOverwriteRestore") &&
                afterRestoreCounts.contains("val hasRestorableCoreData") &&
                afterRestoreCounts.contains("failedItems.isNotEmpty()") &&
                afterRestoreCounts.contains("!hasRestorableCoreData") &&
                afterRestoreCounts.contains("clearLocalDataForOverwriteRestore(backupFile.name)") &&
                afterRestoreCounts.indexOf("failedItems.isNotEmpty()") <
                    afterRestoreCounts.indexOf("clearLocalDataForOverwriteRestore(backupFile.name)") &&
                afterRestoreCounts.indexOf("!hasRestorableCoreData") <
                    afterRestoreCounts.indexOf("clearLocalDataForOverwriteRestore(backupFile.name)")
        )
    }

    @Test
    fun webDavUploadBlocksIncompleteBackupReportsBeforeRemoteOverwrite() {
        val webDavHelperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()
        val uploadBody = webDavHelperSource.substringAfter("suspend fun createAndUploadBackup(")
            .substringBefore("/**\n     * 导出密码到CSV文件")
        val afterCreateResult = uploadBody.substringAfter("val (backupFile, report) = createResult.getOrThrow()")
        val beforeUpload = afterCreateResult.substringBefore("val uploadResult = uploadBackup(backupFile, isPermanent)")

        assertTrue(
            "WebDAV must never upload an incomplete backup over the remote backup. Failed serialization can otherwise turn a good full backup into a tiny partial one.",
            beforeUpload.contains("!report.success || report.failedItems.isNotEmpty()") &&
                beforeUpload.contains("Backup upload blocked because generated backup is incomplete") &&
                beforeUpload.contains("备份文件不完整，已阻止上传覆盖远端备份")
        )
    }

    @Test
    fun bitwardenFullSyncRawLogUsesLightweightSummaryInsteadOfFullVaultJson() {
        val syncServiceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenSyncService.kt"
        ).readText()
        val successFullSyncCapture = syncServiceSource.substringAfter("val syncResponse = response.body()")
            .substringBefore("runCatching {\n                BitwardenSyncForensicsLogger.captureSyncCipherSnapshots")

        assertTrue(
            "Successful Bitwarden full-sync raw logging must use a lightweight summary; re-encoding the full vault JSON causes large-object GC storms during rapid page changes.",
            successFullSyncCapture.contains("val rawForensicsEnabled = runCatching") &&
                successFullSyncCapture.contains("BitwardenSyncForensicsLogger.isRawCaptureEnabled(context)") &&
                successFullSyncCapture.contains("if (rawForensicsEnabled)") &&
                successFullSyncCapture.contains("responseBody = buildSyncFullRawSummary(syncResponse)") &&
                syncServiceSource.contains("private fun buildSyncFullRawSummary(response: SyncResponse): String") &&
                syncServiceSource.contains("data class SyncFullRawSummary") &&
                syncServiceSource.contains("rawResponseOmitted: Boolean = true") &&
                syncServiceSource.contains("per-cipher snapshots are captured separately")
        )
        assertTrue(
            "The raw full-sync summary must only be built after the raw forensics gate is open.",
            successFullSyncCapture.indexOf("if (rawForensicsEnabled)") <
                successFullSyncCapture.indexOf("buildSyncFullRawSummary(syncResponse)")
        )
        assertFalse(
            "Do not bring back json.encodeToString(syncResponse) in the sync_full success raw log path.",
            successFullSyncCapture.contains("json.encodeToString(syncResponse)")
        )
    }

    @Test
    fun bitwardenPerCipherRawSnapshotsAreGatedAndBounded() {
        val forensicsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenSyncForensicsLogger.kt"
        ).readText()
        val snapshotBody = forensicsSource.substringAfter("suspend fun captureSyncCipherSnapshots(")
            .substringBefore("fun exportPersistedLogs")

        assertTrue(
            "Per-cipher raw snapshots must be behind the raw forensics switches; otherwise normal full sync can allocate and encrypt hundreds of large JSON payloads.",
            forensicsSource.contains("suspend fun isRawCaptureEnabled(context: Context)") &&
                forensicsSource.contains("settings.bitwardenSyncForensicsEnabled && settings.bitwardenSyncForensicsRawCaptureEnabled") &&
            snapshotBody.contains("settings.bitwardenSyncForensicsEnabled") &&
                snapshotBody.contains("settings.bitwardenSyncForensicsRawCaptureEnabled") &&
                snapshotBody.contains("return@withContext")
        )
        assertTrue(
            "Per-cipher raw snapshots must be bounded per sync run to prevent GC storms when a large vault is refreshed.",
            forensicsSource.contains("MAX_SYNC_CIPHER_SNAPSHOTS_PER_RUN") &&
                snapshotBody.contains(".take(MAX_SYNC_CIPHER_SNAPSHOTS_PER_RUN)")
        )
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, startIndex = index + needle.length)
        }
        return count
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
