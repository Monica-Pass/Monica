package takagi.ru.monica.ui

import takagi.ru.monica.ui.common.layout.AdaptiveListDetailLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.managementRecordIdOrNull
import takagi.ru.monica.ui.screens.PasskeyListScreen
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

@Composable
internal fun PasskeyPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    passkeyViewModel: PasskeyViewModel,
    passwordViewModel: PasswordViewModel,
    onNavigateToPasswordDetail: (Long) -> Unit,
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onPasskeyOpen: (PasskeyEntry) -> Unit,
    selectedPasskey: PasskeyEntry?,
    passkeyTotalCount: Int,
    passkeyBoundCount: Int,
    resolvePasswordTitle: (Long) -> String?,
    onOpenPasswordDetail: (Long) -> Unit,
    onUnbindPasskey: (PasskeyEntry) -> Unit,
    onDeletePasskey: (PasskeyEntry) -> Unit,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    onScanFidoQr: () -> Unit,
    onNavigateToAuthenticator: () -> Unit
) {
    val hasAdaptiveDetail = selectedPasskey != null
    AdaptiveListDetailLayout(
        compact = isCompactWidth,
        listWidth = wideListPaneWidth,
        hasDetail = hasAdaptiveDetail,
        list = { PasskeyListScreen(
                    viewModel = passkeyViewModel,
                    passwordViewModel = passwordViewModel,
                    onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                    onPasskeyClick = { item -> if (isCompactWidth) item.managementRecordIdOrNull()?.let(onNavigateToPasskeyDetail) else onPasskeyOpen(item) },
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    onScanFidoQr = onScanFidoQr,
                    onNavigateToAuthenticator = onNavigateToAuthenticator
                ) },
        detail = { PasskeyDetailPaneContent(
                    selectedPasskey = selectedPasskey,
                    passkeyTotalCount = passkeyTotalCount,
                    passkeyBoundCount = passkeyBoundCount,
                    resolvePasswordTitle = resolvePasswordTitle,
                    onOpenPasswordDetail = onOpenPasswordDetail,
                    onUnbindPasskey = onUnbindPasskey,
                    onDeletePasskey = onDeletePasskey
                ) }
    )
}

@Composable
internal fun PasskeyDetailPaneContent(
    selectedPasskey: PasskeyEntry?,
    passkeyTotalCount: Int,
    passkeyBoundCount: Int,
    resolvePasswordTitle: (Long) -> String?,
    onOpenPasswordDetail: (Long) -> Unit,
    onUnbindPasskey: (PasskeyEntry) -> Unit,
    onDeletePasskey: (PasskeyEntry) -> Unit
) {
    val passkey = selectedPasskey
    if (passkey == null) {
        PasskeyOverviewPane(
            totalPasskeys = passkeyTotalCount,
            boundPasskeys = passkeyBoundCount,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        val boundPasswordTitle = passkey.boundPasswordId?.let(resolvePasswordTitle)
        PasskeyDetailPane(
            passkey = passkey,
            boundPasswordTitle = boundPasswordTitle,
            onOpenBoundPassword = passkey.boundPasswordId?.let { boundId ->
                { onOpenPasswordDetail(boundId) }
            },
            onUnbindPassword = if (passkey.boundPasswordId != null) {
                { onUnbindPasskey(passkey) }
            } else {
                null
            },
            onDeletePasskey = { onDeletePasskey(passkey) },
            modifier = Modifier.fillMaxSize()
        )
    }
}
