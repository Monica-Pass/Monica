package takagi.ru.monica.autofill_ng.model

import android.view.autofill.AutofillId
import android.widget.inline.InlinePresentationSpec

/**
 * Bitwarden-compatible parsed request model.
 */
sealed class AutofillRequest {
    data class Fillable(
        val ignoreAutofillIds: List<AutofillId>,
        val inlinePresentationSpecs: List<InlinePresentationSpec>?,
        val maxInlineSuggestionsCount: Int,
        val isCompatMode: Boolean,
        val packageName: String,
        val partition: AutofillPartition,
        val uri: String?,
        val fieldSignatureKey: String? = null,
        val interactionIdentifier: String? = null,
        val interactionIdentifierAliases: List<String> = emptyList(),
        val preferDirectAutoFill: Boolean = false,
        val saveEnabled: Boolean = true,
        val isWebView: Boolean = false,
        val webScheme: String? = null,
    ) : AutofillRequest()

    data object Unfillable : AutofillRequest()
}
