package takagi.ru.monica.ime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonicaImeRegressionGuardTest {

    @Test
    fun multiPackageAppBindingsMatchCurrentInputPackage() {
        assertTrue(
            imeEntryMatchesPackage(
                entryPackageName = "com.example.old|com.github.android",
                website = "",
                title = "",
                activePackageName = "com.github.android"
            )
        )
    }

    @Test
    fun androidAppUriPackageBindingsMatchCurrentInputPackage() {
        assertTrue(
            imeEntryMatchesPackage(
                entryPackageName = "androidapp://com.github.android",
                website = "",
                title = "",
                activePackageName = "com.github.android"
            )
        )
    }

    @Test
    fun unrelatedPackageDoesNotMatchWithoutFallbackSignals() {
        assertFalse(
            imeEntryMatchesPackage(
                entryPackageName = "com.example.other",
                website = "https://example.com",
                title = "Example",
                activePackageName = "com.github.android"
            )
        )
    }

    @Test
    fun imePasswordRowsResolveEncryptedUsernameBeforeFiltering() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()

        assertTrue(
            "IME must decrypt username before deciding whether a password row is fillable.",
            source.contains("val decryptedUsername = resolveFillableField(username)")
        )
        assertTrue(
            "IME must expose the decrypted username to the keyboard UI.",
            source.contains("username = decryptedUsername.orEmpty()")
        )
        assertFalse(
            "IME must not drop rows by checking the raw stored username, which may be encrypted.",
            source.contains("if (username.isBlank() && decryptedPassword.isNullOrBlank())")
        )
    }

    @Test
    fun imePasswordRowsResolvePasswordsOnlyWhenTheUserFills() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()
        val projectionBlock = source
            .substringAfter("private fun ImePasswordRow.toImeEntryOrNull(")
            .substringBefore("private fun resolveSourceLabel(")
        val smartFillBlock = source
            .substringAfter("private fun handleSmartFillPassword(")
            .substringBefore("private fun handleSmartFillCardWallet(")

        assertFalse(
            "Building the keyboard list must not decrypt every stored password.",
            projectionBlock.contains("resolveFillableField(password)")
        )
        assertTrue(
            "The keyboard row must retain the stored value for on-demand fill resolution.",
            projectionBlock.contains("password = password")
        )
        assertTrue(
            "Smart fill must resolve the stored password immediately before committing it.",
            smartFillBlock.contains("val password = resolveFillableField(entry.password).orEmpty()")
        )
    }

    @Test
    fun imeSearchAndPanelRefreshReuseTheUnlockedVaultSnapshot() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()

        assertTrue(source.contains("private var vaultSourceCache: ImeVaultSourceCache? = null"))
        assertTrue(source.contains("val sources = cachedSources ?: loadImeVaultSources("))
        assertTrue(source.contains("if (force) invalidateVaultSourceCache()"))
        assertTrue(source.contains("private var totpSourceCache: List<SecureItem>? = null"))
        assertTrue(source.contains("private var cardWalletSourceCache: List<SecureItem>? = null"))
        assertTrue(
            "Typing a query should filter the cached projection instead of forcing another Room snapshot.",
            source.contains("requestRefreshVaultEntries(debounceMillis = ImeSearchRefreshDebounceMs)")
        )
    }

    @Test
    fun imeDatabaseScopesTrackMdbxAsItsOwnSource() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()
        val uiSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodUi.kt"
        ).readText()

        assertTrue(
            "IME must load MDBX database options instead of treating MDBX rows as Monica-local rows.",
            serviceSource.contains("database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot()")
        )
        assertTrue(
            "IME local scope checks need the MDBX owner id.",
            serviceSource.contains("entry.mdbxDatabaseId")
        )
        assertTrue(
            "IME UI needs an MDBX database scope for keyboard filtering.",
            uiSource.contains("data class Mdbx(val databaseId: Long) : MonicaImeDatabaseScope")
        )
    }

    @Test
    fun imePasswordPanelShowsLoadingBeforeEmptyState() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()
        val uiSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodUi.kt"
        ).readText()

        assertTrue(
            "IME UI state needs an explicit loading flag so an empty list is not treated as no matches while refresh is running.",
            uiSource.contains("val isAutofillLoading: Boolean = false")
        )
        val loadingBranchIndex = uiSource.indexOf("if (showAutofillLoading)")
        val loadingStateCallIndex = uiSource.indexOf("AutofillLoadingState()", loadingBranchIndex)
        val emptyStateCallIndex = uiSource.indexOf("EmptyVaultState(query = uiState.query)", loadingBranchIndex)
        val loadingStateBody = uiSource
            .substringAfter("private fun AutofillLoadingState()")
            .substringBefore("@Composable\nprivate fun EmptyVaultState")
        assertTrue(
            "Password panel must render the same loading indicator as the password list before the empty-state card.",
            loadingBranchIndex >= 0 &&
                loadingStateCallIndex > loadingBranchIndex &&
                emptyStateCallIndex > loadingStateCallIndex &&
                loadingStateBody.contains("PasswordListInitialLoadingIndicator()")
        )
        assertFalse(
            "IME must not bring back its own hand-drawn loading indicator instead of the password-list loading style.",
            uiSource.contains("MonicaImeMorphingLoadingIndicator(") ||
                uiSource.contains("ime_autofill_loading_morph")
        )
        assertTrue(
            "Password panel should keep showing loading while database filter options are still initializing.",
            uiSource.contains("uiState.databaseOptions.isEmpty()")
        )
        assertTrue(
            "IME refresh should set loading while the unlocked password panel is fetching entries.",
            serviceSource.contains("it.copy(isAutofillLoading = true)")
        )
        assertTrue(
            "IME refresh completion must clear loading so a real empty result still shows the empty state.",
            serviceSource.contains("isAutofillLoading = false")
        )
        assertTrue(
            "Cancelling a stale IME refresh is normal and must not be surfaced as an error like 'Q0 was cancelled'.",
            serviceSource.contains("catch (e: CancellationException)") &&
                serviceSource.indexOf("catch (e: CancellationException)") < serviceSource.indexOf("catch (e: Exception)")
        )
    }

    @Test
    fun imeAuthenticatorAndCardPanelsShowLoadingBeforeEmptyState() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()
        val uiSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodUi.kt"
        ).readText()
        val authenticatorPane = uiSource
            .substringAfter("private fun AuthenticatorPane(")
            .substringBefore("@Composable\nprivate fun CardWalletPane")
        val cardWalletPane = uiSource
            .substringAfter("private fun CardWalletPane(")
            .substringBefore("@Composable\nprivate fun ImeEmptyState")

        assertTrue(
            "Authenticator loading must win over an empty-state card during the initial refresh.",
            authenticatorPane.indexOf("if (uiState.isAutofillLoading)") >= 0 &&
                authenticatorPane.indexOf("AutofillLoadingState()") <
                    authenticatorPane.indexOf("ime_empty_authenticator_title")
        )
        assertTrue(
            "Card-wallet loading must win over an empty-state card during the initial refresh.",
            cardWalletPane.indexOf("if (uiState.isAutofillLoading)") >= 0 &&
                cardWalletPane.indexOf("AutofillLoadingState()") <
                    cardWalletPane.indexOf("ime_empty_card_wallet_title")
        )
        assertTrue(
            "Unlocking directly into a secondary vault panel must not render its empty state before data arrives.",
            serviceSource.contains("isAutofillLoading = targetPanel.requiresInitialVaultLoading()")
        )
        assertTrue(
            "Restoring the IME with an authenticator or card panel open must retain the loading state during refresh.",
            serviceSource.contains("previousState.activePanel.requiresInitialVaultLoading()")
        )
        assertTrue(
            "The refresh entry point must protect every content panel, including observer-driven refreshes.",
            serviceSource.contains("currentState.activePanel.requiresInitialVaultLoading()")
        )
        assertTrue(
            "Switching back to an already loaded keyboard panel must retain its content instead of showing a spinner again.",
            serviceSource.contains("private val loadedVaultPanels = mutableMapOf<MonicaImePanel, ImeVaultPresentation>()") &&
                serviceSource.contains("this !in loadedVaultPanels") &&
                serviceSource.contains("loadedVaultPanels[currentState.activePanel] = currentState")
        )
        assertTrue(
            "Already loaded panels should refresh only when their query, source, or ordering changed.",
            serviceSource.contains("if (requiresInitialLoad || needsPresentationRefresh)") &&
                serviceSource.contains("loadedVaultPanels[panel] != nextState.vaultPresentation()")
        )
        assertTrue(
            "A vault data change must clear panel readiness so the next result is never stale.",
            serviceSource.contains("loadedVaultPanels.clear()")
        )
    }

    @Test
    fun imePasswordSearchUsesMonicaKeyboardWithoutWritingIntoTargetField() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()
        val uiSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodUi.kt"
        ).readText()

        assertTrue(
            "IME search needs an explicit editing state so Monica keyboard keys can target the query.",
            uiSource.contains("val isSearchEditing: Boolean = false")
        )
        assertTrue(
            "IME search keys must update the internal query before external text commit is considered.",
            serviceSource.indexOf("if (currentState.isSearchEditing)") in 0 until
                serviceSource.indexOf("commitExternalText(text)")
        )
        assertTrue(
            "Password panel needs a visible search control and a dedicated editing toolbar.",
            uiSource.contains("ImeVaultControls(") &&
                uiSource.contains("ImeSearchToolbar(")
        )
        assertFalse(
            "Password refresh must not force its query back to an empty string.",
            serviceSource.contains(
                "if (currentState.activePanel == MonicaImePanel.PASSWORDS) {\n            \"\""
            )
        )
    }

    @Test
    fun imeSecondaryVaultListsUseTheSameNavigationBarAsPasswords() {
        val uiSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodUi.kt"
        ).readText()
        val authenticatorPane = uiSource
            .substringAfter("private fun AuthenticatorPane(")
            .substringBefore("@Composable\nprivate fun CardWalletPane")
        val cardWalletPane = uiSource
            .substringAfter("private fun CardWalletPane(")
            .substringBefore("@Composable\nprivate fun ImeEmptyState")
        val sharedList = uiSource
            .substringAfter("private fun <T> ImeVaultList(")
            .substringBefore("@Composable\nprivate fun UnlockedVaultPane")

        assertTrue(
            "Authenticator IME list should keep the same right-side navigation bar behavior as the password list.",
            authenticatorPane.contains("ImeVaultList(") &&
                authenticatorPane.contains("title = ::imeAuthenticatorAlphabeticalLabel")
        )
        assertTrue(
            "Card wallet IME list should keep the same right-side navigation bar behavior as the password list.",
            cardWalletPane.contains("ImeVaultList(") &&
                cardWalletPane.contains("title = ::imeCardWalletAlphabeticalLabel")
        )
        assertTrue(sharedList.contains("rememberLazyListState()") && sharedList.contains("VelocityScrollBar("))
    }

    @Test
    fun imePickerDoesNotDependOnExternalKeyboardSwitcher() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt"
        ).readText()
        val pickerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt"
        ).readText()
        val preferencesSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPreferences.kt"
        ).readText()
        val settingsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AutofillSettingsV2Screen.kt"
        ).readText()
        val manifestSource = projectFile("app/src/main/AndroidManifest.xml").readText()
        val pickerLaunchSource = serviceSource
            .substringAfter("private fun openAutofillPickerPage()")
            .substringBefore("private fun requestRefreshVaultEntries(")

        assertTrue(
            "The full picker must still open directly from Monica Keyboard.",
            pickerLaunchSource.contains("startActivity(") &&
                pickerLaunchSource.contains("EXTRA_IME_MODE")
        )
        assertFalse(
            "The picker must not require an external app, ADB-granted permission, or a return session.",
            pickerLaunchSource.contains("KeyboardSwitcherCompat") ||
                pickerLaunchSource.contains("EXTRA_RETURN_TO_MONICA_IME") ||
                pickerSource.contains("KeyboardSwitcherCompat") ||
                preferencesSource.contains("KEY_IME_KEYBOARD_SWITCHER") ||
                settingsSource.contains("keyboardSwitcher") ||
                manifestSource.contains("com.kunzisoft.keyboard.switcher") ||
                pickerLaunchSource.contains("trySwitchToPreviousInputMethod()") ||
                pickerLaunchSource.contains("delay(120L)")
        )
    }

    @Test
    fun imeDatabaseSelectorStaysInsideTheKeyboardSurface() {
        val uiSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodUi.kt"
        ).readText()
        val selectorSource = uiSource
            .substringAfter("private fun ImeDatabaseScopeSelector(")
            .substringBefore("private enum class ConnectedToolbarPosition")

        assertFalse(
            "The database selector must not use a popup that can escape the IME window.",
            selectorSource.contains("DropdownMenu(")
        )
        assertTrue(
            "The database choices must be rendered as a scrollable layer inside UnlockedVaultPane.",
            uiSource.contains("private fun ImeDatabaseScopeMenu(") &&
                uiSource.contains("databaseMenuExpanded") &&
                uiSource.contains("LazyColumn(")
        )
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
