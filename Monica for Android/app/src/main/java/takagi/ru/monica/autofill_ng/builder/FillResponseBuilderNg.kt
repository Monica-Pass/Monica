package takagi.ru.monica.autofill_ng.builder

import takagi.ru.monica.utils.AppLocaleStringResolver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import takagi.ru.monica.autofill_ng.AutofillCipherCallbackActivity
import takagi.ru.monica.autofill_ng.AutofillPickerActivityV2
import takagi.ru.monica.autofill_ng.PasswordSuggestionActivity
import takagi.ru.monica.autofill_ng.auth.AutofillGrantContext
import takagi.ru.monica.autofill_ng.builder.AutofillDatasetBuilder
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.model.AutofillRequest
import takagi.ru.monica.autofill_ng.model.AutofillView
import takagi.ru.monica.autofill_ng.model.FilledData
import takagi.ru.monica.autofill_ng.model.FilledPartition
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.utils.PasswordGenerator
import kotlin.random.Random

class FillResponseBuilderNg(
    private val context: Context,
) {
    private val strings = AppLocaleStringResolver(context)

    private companion object {
        private const val TAG = "MonicaAutofillBwCompat"
        private const val MANUAL_PLACEHOLDER_VALUE = "PLACEHOLDER"
    }

    fun build(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        passwordSuggestionEnabled: Boolean = true,
        requireAuthentication: Boolean = true,
        matchedPasswords: List<PasswordEntry> = emptyList(),
        postFillOtpPasswordIds: Set<Long> = emptySet(),
    ): FillResponse? {
        val fillableAutofillIds = filledData.fillableAutofillIds
        if (fillableAutofillIds.isEmpty()) {
            android.util.Log.w(TAG, "build skipped: no fillableAutofillIds")
            AutofillLogger.w("FILLING", "Build skipped: no fillableAutofillIds")
            return null
        }

        // Offer metadata-only authenticated datasets immediately. The chosen entry,
        // or the full picker when there is no match, owns the single system prompt.
        val responseBuilder = FillResponse.Builder()
        var cipherDatasetCount = 0
        var failedCipherDatasetCount = 0
        var callbackCipherDatasetCount = 0
        filledData.filledPartitions.forEachIndexed { index, partition ->
            if (partition.filledItems.isEmpty()) return@forEachIndexed
            runCatching {
                responseBuilder.addDataset(
                    buildCipherDataset(
                        request = request,
                        partition = partition,
                        index = index,
                        postFillOtpPasswordIds = postFillOtpPasswordIds,
                    )
                )
                cipherDatasetCount++
                if (partition.requiresAuthentication ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        partition.inlinePresentationSpec != null
                    )
                ) {
                    callbackCipherDatasetCount++
                }
            }.onFailure { error ->
                failedCipherDatasetCount++
                android.util.Log.w(TAG, "Failed to build cipher dataset index=$index", error)
                AutofillLogger.w(
                    "FILLING",
                    "Failed to build cipher dataset",
                    metadata = mapOf(
                        "index" to index,
                        "error" to (error.message ?: error::class.java.simpleName)
                    )
                )
            }
        }

        val strongPasswordDataset = if (passwordSuggestionEnabled) {
            buildStrongPasswordSuggestionDataset(
                request = request
            )
        } else {
            null
        }
        if (strongPasswordDataset != null) {
            responseBuilder.addDataset(strongPasswordDataset)
        }

        responseBuilder.addDataset(
            buildVaultItemDataset(
                request = request,
                filledData = filledData,
                fillableAutofillIds = fillableAutofillIds
            )
        )

        if (filledData.ignoreAutofillIds.isNotEmpty()) {
            responseBuilder.setIgnoredIds(*filledData.ignoreAutofillIds.toTypedArray())
        }

        attachSaveInfoIfNeeded(
            responseBuilder = responseBuilder,
            request = request
        )

        android.util.Log.i(
            TAG,
            "build result: cipherDatasets=$cipherDatasetCount, " +
                "failedCipherDatasets=$failedCipherDatasetCount, " +
                "strongPasswordDataset=${if (strongPasswordDataset != null) 1 else 0}, " +
                "vaultDataset=1, fillableIds=${fillableAutofillIds.size}, " +
                "suggestedIds=${filledData.filledPartitions.count { it.autofillCipher.cipherId != null }}, " +
                "authRequired=$requireAuthentication, sdk=${Build.VERSION.SDK_INT}, " +
                "callbackCipherDatasets=$callbackCipherDatasetCount"
        )
        AutofillLogger.i(
            "FILLING",
            "FillResponse build result",
            metadata = mapOf(
                "cipherDatasets" to cipherDatasetCount,
                "failedCipherDatasets" to failedCipherDatasetCount,
                "strongPasswordDataset" to (strongPasswordDataset != null),
                "vaultDataset" to true,
                "fillableIds" to fillableAutofillIds.size,
                "suggestedIds" to filledData.filledPartitions.count { it.autofillCipher.cipherId != null },
                "authRequired" to requireAuthentication,
                "sdk" to Build.VERSION.SDK_INT,
                "callbackCipherDatasets" to callbackCipherDatasetCount,
            )
        )
        return responseBuilder.build()
    }

    private fun buildCipherDataset(
        request: AutofillRequest.Fillable,
        partition: FilledPartition,
        index: Int,
        postFillOtpPasswordIds: Set<Long>,
    ): android.service.autofill.Dataset {
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordEntry(
            context = context,
            title = partition.autofillCipher.name,
            username = partition.autofillCipher.subtitle
        )

        val hasInlinePresentation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            partition.inlinePresentationSpec != null
        // Only real password fills opt into a selection callback. Username-only,
        // empty, OTP-target, and ordinary direct datasets retain their original behavior.
        val passwordIds = request.partition.views.filterIsInstance<AutofillView.Login.Password>()
            .filter { it.data.hint == FieldHint.PASSWORD }
            .mapTo(hashSetOf()) { it.data.autofillId }
        val needsOtpCallback = !partition.requiresAuthentication &&
            partition.autofillCipher.cipherId?.toLongOrNull() in postFillOtpPasswordIds &&
            request.partition.views.none { it.data.hint == FieldHint.OTP_CODE } &&
            partition.filledItems.any {
                it.autofillId in passwordIds && it.value?.isText == true &&
                    !it.value.textValue.isNullOrBlank()
            }
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        partition.filledItems.forEach { filledItem ->
            fields[filledItem.autofillId] = AutofillDatasetBuilder.FieldData(
                value = filledItem.value,
                presentation = menuPresentation
            )
        }
        // Return these exact IDs/values; do not parse a possibly changed AssistStructure
        // or re-decrypt/reselect a password merely to perform optional OTP actions.
        val directDataset = if (needsOtpCallback) {
            AutofillDatasetBuilder.create(menuPresentation = menuPresentation, fields = fields) { null }.build()
        } else null
        val callbackTargets = buildLoginCallbackTargets(request.partition.views)
        val authPendingIntent = if (partition.requiresAuthentication || hasInlinePresentation || needsOtpCallback) {
            runCatching { createCipherAuthPendingIntent(
                request = request,
                partition = partition,
                callbackTargets = callbackTargets,
                requireAuthentication = partition.requiresAuthentication,
                directDataset = directDataset,
            ) }.getOrElse { error ->
                if (partition.requiresAuthentication) throw error
                // Optional OTP/inline presentation cannot remove an otherwise valid fill.
                null
            }
        } else {
            null
        }
        if (partition.requiresAuthentication && authPendingIntent == null) {
            throw IllegalStateException("Authentication required but cipher callback pending intent is unavailable")
        }

        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val spec = partition.inlinePresentationSpec ?: return@create null
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = index,
                    pendingIntent = authPendingIntent ?: return@create null,
                    title = partition.autofillCipher.name,
                    subtitle = partition.autofillCipher.subtitle,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = partition.autofillCipher.name
                )
            } else {
                null
            }
        }
        if ((partition.requiresAuthentication || needsOtpCallback) && authPendingIntent != null) {
            datasetBuilder.setAuthentication(authPendingIntent.intentSender)
        }
        return datasetBuilder.build()
    }

    private fun buildStrongPasswordSuggestionDataset(
        request: AutofillRequest.Fillable,
    ): android.service.autofill.Dataset? {
        val passwordHints = request.partition.views
            .filterIsInstance<AutofillView.Login.Password>()
            .map { it.data.hint }
        if (!StrongPasswordSuggestionPolicy.shouldOffer(passwordHints)) {
            return null
        }

        val newPasswordIds = request.partition.views
            .filterIsInstance<AutofillView.Login.Password>()
            .filter { it.data.hint == FieldHint.NEW_PASSWORD }
            .map { it.data.autofillId }
            .distinct()
            .ifEmpty { return null }

        val pendingIntent = createStrongPasswordSuggestionPendingIntent(
            request = request,
            passwordFieldIds = newPasswordIds,
        )
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordSuggestion(context)
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        newPasswordIds.forEach { autofillId ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                value = AutofillValue.forText(MANUAL_PLACEHOLDER_VALUE),
                presentation = menuPresentation,
            )
        }

        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val spec = request.inlinePresentationSpecs?.firstOrNull() ?: return@create null
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = 0,
                    pendingIntent = pendingIntent,
                    title = strings.get(R.string.password_suggestion_title),
                    subtitle = strings.get(R.string.password_suggestion_subtitle),
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = strings.get(R.string.password_suggestion_title)
                )
            } else {
                null
            }
        }
        datasetBuilder.setAuthentication(pendingIntent.intentSender)
        return datasetBuilder.build()
    }

    private fun createStrongPasswordSuggestionPendingIntent(
        request: AutofillRequest.Fillable,
        passwordFieldIds: List<AutofillId>,
    ): PendingIntent {
        val webDomain = extractWebDomain(request.uri)
        val username = request.partition.views
            .filterIsInstance<AutofillView.Login.Username>()
            .firstOrNull { !it.data.textValue.isNullOrBlank() }
            ?.data
            ?.textValue
            .orEmpty()
        val generatedPassword = PasswordGenerator().generatePassword(
            PasswordGenerator.PasswordOptions(
                length = 16,
                includeUppercase = true,
                includeLowercase = true,
                includeNumbers = true,
                includeSymbols = true,
                excludeSimilar = true,
            )
        )

        val intent = Intent(context, PasswordSuggestionActivity::class.java).apply {
            putExtra(PasswordSuggestionActivity.EXTRA_USERNAME, username)
            putExtra(PasswordSuggestionActivity.EXTRA_GENERATED_PASSWORD, generatedPassword)
            putExtra(PasswordSuggestionActivity.EXTRA_PACKAGE_NAME, request.packageName)
            putExtra(PasswordSuggestionActivity.EXTRA_WEB_DOMAIN, webDomain)
            putParcelableArrayListExtra(
                PasswordSuggestionActivity.EXTRA_PASSWORD_FIELD_IDS,
                ArrayList(passwordFieldIds)
            )
            // Authentication is started for a result by Android. NEW_TASK would
            // cancel that result before the generated password can return.
        }
        return PendingIntent.getActivity(
            context,
            Random.nextInt(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
    }

    private fun createCipherAuthPendingIntent(
        request: AutofillRequest.Fillable,
        partition: FilledPartition,
        callbackTargets: List<AutofillCallbackTarget>,
        requireAuthentication: Boolean,
        directDataset: android.service.autofill.Dataset? = null,
    ): PendingIntent? {
        val passwordId = partition.autofillCipher.cipherId?.toLongOrNull() ?: return null
        val targets = callbackTargets.ifEmpty {
            partition.filledItems
                .map { AutofillCallbackTarget(it.autofillId, "") }
                .distinctBy { it.autofillId.toString() }
        }
        if (targets.isEmpty()) return null

        val args = AutofillCipherCallbackActivity.Args(
            passwordId = passwordId,
            applicationId = request.packageName,
            webDomain = extractWebDomain(request.uri),
            interactionIdentifier = request.interactionIdentifier,
            interactionIdentifierAliases = ArrayList(request.interactionIdentifierAliases),
            autofillIds = ArrayList(targets.map { it.autofillId }),
            autofillHints = ArrayList(targets.map { it.hintName }),
            fieldSignatureKey = request.fieldSignatureKey,
            rememberLastFilled = true,
            requireAuthentication = requireAuthentication,
        )
        val pickerIntent = AutofillCipherCallbackActivity.getIntent(context, args).apply {
            if (directDataset != null) {
                putExtra(AutofillCipherCallbackActivity.EXTRA_DIRECT_DATASET, directDataset)
            }
        }
        return PendingIntent.getActivity(
            context,
            Random.nextInt(),
            pickerIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
    }

    private fun buildVaultItemDataset(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        fillableAutofillIds: List<AutofillId>,
    ): android.service.autofill.Dataset {
        val targetIds = fillableAutofillIds.distinct()
        val manualEntry = buildManualEntryArtifacts(
            request = request,
            filledData = filledData,
            fillableAutofillIds = targetIds,
        )
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        targetIds.forEach { autofillId ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                // Bitwarden-compatible approach:
                // keep one authenticated manual dataset anchored to all fillable fields
                // with a placeholder value so framework keeps entry visible consistently.
                value = AutofillValue.forText(MANUAL_PLACEHOLDER_VALUE),
                presentation = manualEntry.menuPresentation
            )
        }
        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = manualEntry.menuPresentation,
            fields = fields
        ) { manualEntry.inlinePresentation }
        datasetBuilder.setAuthentication(manualEntry.pendingIntent.intentSender)
        return datasetBuilder.build()
    }

    private fun buildManualEntryArtifacts(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        fillableAutofillIds: List<AutofillId>,
    ): ManualEntryArtifacts {
        val webDomain = extractWebDomain(request.uri)
        val autofillHints = buildAutofillHintNames(request.partition.views)
        val suggestedPasswordIds = filledData.filledPartitions
            .mapNotNull { it.autofillCipher.cipherId?.toLongOrNull() }
            .distinct()
            .toLongArray()
        val args = AutofillPickerActivityV2.Args(
            applicationId = request.packageName,
            webDomain = webDomain,
            interactionIdentifier = request.interactionIdentifier,
            interactionIdentifierAliases = ArrayList(request.interactionIdentifierAliases),
            autofillIds = ArrayList(fillableAutofillIds),
            autofillHints = ArrayList(autofillHints),
            suggestedPasswordIds = suggestedPasswordIds,
            isSaveMode = false,
            fieldSignatureKey = request.fieldSignatureKey,
            responseAuthMode = false,
            saveEnabled = request.saveEnabled,
            saveCompatMode = request.isCompatMode,
            rememberLastFilled = true,
        )
        val pickerIntent = AutofillPickerActivityV2.getIntent(context, args)
        val pendingIntent = PendingIntent.getActivity(
            context,
            Random.nextInt(),
            pickerIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )

        val menuPresentation = if (filledData.isVaultLocked) {
            AutofillDatasetBuilder.RemoteViewsFactory.createUnlockPrompt(
                context = context,
                message = strings.get(R.string.autofill_manual_entry_title)
            )
        } else {
            AutofillDatasetBuilder.RemoteViewsFactory.createManualSelection(
                context = context,
                domain = webDomain,
                packageName = request.packageName
            )
        }

        val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            filledData.vaultItemInlinePresentationSpec?.let { spec ->
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = request.inlinePresentationSpecs?.indexOf(spec) ?: 0,
                    pendingIntent = pendingIntent,
                    title = strings.get(R.string.autofill_manual_entry_title),
                    subtitle = webDomain?.takeIf { it.isNotBlank() } ?: request.packageName,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = strings.get(R.string.autofill_manual_entry_title)
                )
            }
        } else {
            null
        }

        return ManualEntryArtifacts(
            pendingIntent = pendingIntent,
            menuPresentation = menuPresentation,
            inlinePresentation = inlinePresentation,
        )
    }

    private fun extractWebDomain(uri: String?): String? =
        uri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeUnless { it.scheme.equals("androidapp", ignoreCase = true) }
            ?.host
            ?.takeIf { it.isNotBlank() }

    private fun attachSaveInfoIfNeeded(
        responseBuilder: FillResponse.Builder,
        request: AutofillRequest.Fillable,
    ) {
        // Bitwarden-compatible: skip save for login fields in compat mode because password
        // values can be masked and lead to low-quality save prompts.
        takagi.ru.monica.autofill_ng.AutofillSaveInfoPolicy.build(
            request.partition.views.map { it.data.autofillId to it.data.hint },
            enabled = request.saveEnabled, compatMode = request.isCompatMode,
            saveOnAllViewsInvisible = request.uri.orEmpty().startsWith("https://", ignoreCase = true) ||
                request.uri.orEmpty().startsWith("http://", ignoreCase = true),
        )?.let(responseBuilder::setSaveInfo)
    }
}

private data class ManualEntryArtifacts(
    val pendingIntent: PendingIntent,
    val menuPresentation: android.widget.RemoteViews,
    val inlinePresentation: InlinePresentation?,
)

private data class AutofillCallbackTarget(
    val autofillId: AutofillId,
    val hintName: String,
)

private fun buildLoginCallbackTargets(views: List<AutofillView>): List<AutofillCallbackTarget> {
    val seenIds = mutableSetOf<String>()
    return views.mapNotNull { view ->
        val target = when (view) {
            is AutofillView.Login.Username -> AutofillCallbackTarget(view.data.autofillId, view.data.semanticHint ?: view.data.hint.name)
            is AutofillView.Login.Password -> if (view.data.hint == FieldHint.NEW_PASSWORD) null
                else AutofillCallbackTarget(view.data.autofillId, view.data.hint.name)
            is AutofillView.Field -> null
        }
        target?.takeIf { seenIds.add(it.autofillId.toString()) }
    }
}

private fun buildAutofillHintNames(views: List<AutofillView>): List<String> =
    views.map { it.data.semanticHint ?: it.data.hint.name }

private val FilledData.fillableAutofillIds: List<AutofillId>
    get() = originalPartition.views.map { it.data.autofillId }

