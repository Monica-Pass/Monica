package takagi.ru.monica.ui.screens

import android.net.Uri
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassNativeEntryPresentationUpdate
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.keepass.KeePassTemplateEngine
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

internal data class KeePassNativeEntrySaveOutcome(
    val savedEntry: KeePassNativeEntryRecord? = null,
    val failure: Throwable? = null,
)

internal suspend fun saveKeePassNativeManagerEntry(
    viewModel: LocalKeePassViewModel,
    databaseId: Long,
    editingEntry: KeePassNativeEntryRecord?,
    creatingParent: KeePassNativeGroupIdentity?,
    templateMode: Boolean,
    fields: List<KeePassFieldChange>,
    presentation: KeePassNativeEntryPresentationUpdate?,
    pendingAttachments: List<Uri>,
    revisionToken: String,
): KeePassNativeEntrySaveOutcome {
    if (editingEntry == null && creatingParent == null) {
        return KeePassNativeEntrySaveOutcome(failure = IllegalStateException("KeePass parent group is unavailable"))
    }
    return viewModel.saveNativeEntryDraft(
        databaseId = databaseId,
        entryUuid = editingEntry?.identity?.entryUuid,
        parentGroupUuid = creatingParent?.groupUuid,
        fields = fields.withTemplateMarkerIfNeeded(templateMode),
        presentation = presentation,
        sourceUris = pendingAttachments,
        expectedRevisionToken = revisionToken,
    ).fold(
        onSuccess = { saved -> KeePassNativeEntrySaveOutcome(savedEntry = saved) },
        onFailure = { failure -> KeePassNativeEntrySaveOutcome(failure = failure) },
    )
}

internal suspend fun advanceKeePassNativeManagerHotp(
    viewModel: LocalKeePassViewModel,
    entry: KeePassNativeEntryRecord,
    data: TotpData,
    revisionToken: String,
): Result<KeePassNativeBrowserSnapshot> = runCatching {
    val fields = advanceNativeHotpFields(
        entry.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) }, data,
    )
    viewModel.replaceNativeEntryFields(
        entry.identity.databaseId, entry.identity.entryUuid, fields, revisionToken,
    ).getOrThrow()
    viewModel.openNativeBrowser(entry.identity.databaseId).getOrThrow()
}

private fun List<KeePassFieldChange>.withTemplateMarkerIfNeeded(
    templateMode: Boolean,
): List<KeePassFieldChange> {
    if (!templateMode) return this
    val markerExists = any { field ->
        field.name.equals(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD, ignoreCase = true)
    }
    if (markerExists) return this
    return this + KeePassFieldChange(
        name = KeePassTemplateEngine.TEMPLATE_MARKER_FIELD,
        value = KeePassTemplateEngine.TEMPLATE_MARKER_VALUE,
    )
}
