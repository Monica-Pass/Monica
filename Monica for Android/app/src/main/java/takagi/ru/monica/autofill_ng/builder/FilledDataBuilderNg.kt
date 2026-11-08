package takagi.ru.monica.autofill_ng.builder

import android.content.Context
import android.view.autofill.AutofillValue
import android.widget.inline.InlinePresentationSpec
import takagi.ru.monica.autofill_ng.AccountFillPolicy
import takagi.ru.monica.autofill_ng.AutofillPasswordSource
import takagi.ru.monica.autofill_ng.NativeAutofillSelection
import takagi.ru.monica.autofill_ng.model.AutofillCipher
import takagi.ru.monica.autofill_ng.model.AutofillRequest
import takagi.ru.monica.autofill_ng.model.AutofillPartition
import takagi.ru.monica.autofill_ng.model.AutofillView
import takagi.ru.monica.autofill_ng.model.FilledData
import takagi.ru.monica.autofill_ng.model.FilledItem
import takagi.ru.monica.autofill_ng.model.FilledPartition
import takagi.ru.monica.autofill_ng.model.toAutofillCipherLogin
import takagi.ru.monica.autofill_ng.AutofillSecretResolver
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.passwordProjectKey
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// 不在此处截断条目数量，让系统键盘自行控制横向滚动显示所有条目。
private const val MAX_FILLED_PARTITIONS_COUNT = Int.MAX_VALUE
private const val MAX_INLINE_SUGGESTION_COUNT = Int.MAX_VALUE

class FilledDataBuilderNg(
    private val context: Context,
    private val securityManager: SecurityManager,
) {

    private fun resolveAutoLockTimeoutForAutofill(): Int {
        return runCatching {
            val settingsManager = SettingsManager(context.applicationContext)
            val autoLockMinutes = runBlocking {
                settingsManager.settingsFlow.first().autoLockMinutes
            }
            SessionManager.updateAutoLockTimeout(autoLockMinutes)
            autoLockMinutes
        }.onFailure { error ->
            android.util.Log.w(
                "FilledDataBuilderNg",
                "Failed to sync auto-lock timeout for autofill: ${error.message}"
            )
        }.getOrDefault(5)
    }

    fun build(
        request: AutofillRequest.Fillable,
        passwords: List<PasswordEntry>,
        requireAuthentication: Boolean = true,
        checkCancelled: () -> Unit = {},
        autoLockTimeoutMinutes: Int? = null,
        nativeSelections: Map<Long, NativeAutofillSelection> = emptyMap(),
    ): FilledData {
        val autoLockMinutes = autoLockTimeoutMinutes ?: resolveAutoLockTimeoutForAutofill()
        // “永不过期”允许会话跨进程生命周期恢复，但显式锁定仍必须立即生效。
        // 因此始终同时检查会话状态和可用密钥材料，不能仅凭 Keystore 材料可读
        // 就绕过 SessionManager。
        // A verified request or scoped grant already authorized this response.
        // Consulting the unrelated main-app timer here could clear its freshly unlocked key.
        val isVaultLocked = requireAuthentication &&
            !securityManager.canAccessVaultNowStrict(context, autoLockMinutes)
        val maxCipherInlineSuggestionsCount = (request.maxInlineSuggestionsCount - 1)
            .coerceAtMost(MAX_INLINE_SUGGESTION_COUNT)

        var inlineSuggestionsAdded = 0

        fun getCipherInlinePresentationOrNull(): InlinePresentationSpec? =
            if (inlineSuggestionsAdded < maxCipherInlineSuggestionsCount) {
                request.inlinePresentationSpecs?.getOrLastOrNull(inlineSuggestionsAdded)
            } else {
                null
            }?.also { inlineSuggestionsAdded += 1 }

        val loginViews = when (val partition = request.partition) {
            is AutofillPartition.Login -> partition.views
            is AutofillPartition.Generic -> partition.views.filterIsInstance<AutofillView.Login>()
        }

        val storedViews = loginViews.filter { it.data.hint != takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD }
        val needsPassword = storedViews.any { it is AutofillView.Login.Password }
        val filledPartitions = if (storedViews.isEmpty()) {
            emptyList()
        } else {
            // Presentation only: keep each real row ID and never combine credentials.
            val resolved = passwords.mapNotNull { entry ->
                checkCancelled()
                val native = AutofillPasswordSource.isNative(entry)
                val selection = nativeSelections[entry.id]
                // Native values are resolved at selection time. Never cache them in a framework dataset.
                if (native && selection == null) return@mapNotNull null
                buildCipherForResponse(
                    entry = entry,
                    fallbackWebsite = request.uri.orEmpty(),
                    requireAuthentication = requireAuthentication || native,
                    isVaultLocked = isVaultLocked,
                    needsPassword = needsPassword,
                )?.let { entry to it.copy(nativeSelection = selection) }
            }
            // Legacy usernames may be encrypted with different nonces. Group the resolved
            // account name, without decrypting credentials a second time on the suggestion path.
            val numbers = resolved.groupBy { (entry, cipher) -> entry.passwordProjectKey() to cipher.username }.values
                .filter { it.size > 1 }.flatMap { group -> group.sortedBy { it.first.id }
                    .mapIndexed { index, row -> row.first.id to index + 1 } }.toMap()
            val ciphers = resolved.map { (entry, cipher) ->
                numbers[entry.id]?.let { number ->
                    cipher.copy(subtitle = listOf(cipher.subtitle, context.getString(takagi.ru.monica.R.string.project_credential_password_number, number))
                        .filter { it.isNotBlank() }.joinToString(" · "))
                } ?: cipher
            }

            ciphers
                .map { autofillCipher ->
                    checkCancelled()
                    fillLoginPartition(
                        autofillCipher = autofillCipher,
                        autofillViews = loginViews,
                        inlinePresentationSpec = getCipherInlinePresentationOrNull(),
                        requiresAuthentication = requireAuthentication || autofillCipher.nativeSelection != null
                    )
                }
                .filter { it.filledItems.isNotEmpty() }
                .take(MAX_FILLED_PARTITIONS_COUNT)
        }

        val vaultItemInlinePresentationSpec = request
            .inlinePresentationSpecs
            ?.getOrLastOrNull(inlineSuggestionsAdded)

        return FilledData(
            filledPartitions = filledPartitions,
            ignoreAutofillIds = request.ignoreAutofillIds,
            originalPartition = request.partition,
            uri = request.uri,
            vaultItemInlinePresentationSpec = vaultItemInlinePresentationSpec,
            isVaultLocked = isVaultLocked
        )
    }

    private fun fillLoginPartition(
        autofillCipher: AutofillCipher.Login,
        autofillViews: List<AutofillView.Login>,
        inlinePresentationSpec: InlinePresentationSpec?,
        requiresAuthentication: Boolean,
    ): FilledPartition {
        val storedCredentialViews = autofillViews.filter {
            it.data.hint != takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD
        }
        val filledItems = if (requiresAuthentication) {
            storedCredentialViews.map { autofillView ->
                FilledItem(
                    autofillId = autofillView.data.autofillId,
                    value = null
                )
            }
        } else {
            storedCredentialViews.mapNotNull { autofillView ->
                val value = when (autofillView) {
                    is AutofillView.Login.Username -> takagi.ru.monica.autofill_ng.AutofillAccountValues.resolve(
                        autofillView.data.semanticHint ?: autofillView.data.hint.name,
                        autofillCipher.username, AccountFillPolicy.shouldFillEmailWithAccount(context) || autofillCipher.username.contains("@"))
                    is AutofillView.Login.Password -> autofillCipher.password
                }
                autofillView.buildFilledItemOrNull(value = value)
            }
        }

        return FilledPartition(
            autofillCipher = autofillCipher,
            filledItems = filledItems,
            inlinePresentationSpec = inlinePresentationSpec,
            requiresAuthentication = requiresAuthentication
        )
    }

    private fun AutofillView.Login.buildFilledItemOrNull(value: String?): FilledItem? {
        if (value.isNullOrBlank()) return null
        return FilledItem(
            autofillId = data.autofillId,
            value = AutofillValue.forText(value)
        )
    }

    private fun buildCipherForResponse(
        entry: PasswordEntry,
        fallbackWebsite: String,
        requireAuthentication: Boolean,
        isVaultLocked: Boolean,
        needsPassword: Boolean = true,
    ): AutofillCipher.Login? {
        if (requireAuthentication) {
            // Never decrypt the password before selection. Account labels can use
            // an already-unlocked key; otherwise show existing non-secret metadata.
            val subtitleValue = (if (isVaultLocked) AccountFillPolicy.resolveAccountIdentifierForDisplay(entry)
                else AccountFillPolicy.resolveAccountIdentifier(entry, securityManager))
                .takeIf { it.isNotBlank() }
                ?: entry.website.takeIf { it.isNotBlank() }
                ?: fallbackWebsite.takeIf { it.isNotBlank() }
                ?: entry.title
            val websiteValue = entry.website.takeIf { it.isNotBlank() } ?: fallbackWebsite
            val titleValue = entry.title
                .takeIf { it.isNotBlank() }
                ?: subtitleValue.takeIf { it.isNotBlank() }
                ?: websiteValue.takeIf { it.isNotBlank() }
                ?: "Credential"
            return AutofillCipher.Login(
                cipherId = entry.id.toString(),
                name = titleValue,
                subtitle = subtitleValue,
                username = "",
                password = "",
                website = websiteValue,
                appPackageName = entry.appPackageName.takeIf { it.isNotBlank() }
            )
        }

        val usernameValue = decryptForAutofill(entry.username)
        val passwordValue = if (needsPassword) decryptForAutofill(entry.password) else ""
        if (usernameValue.isNullOrBlank() && passwordValue.isNullOrBlank()) {
            return null
        }
        return entry.toAutofillCipherLogin(
            fallbackWebsite = fallbackWebsite,
            usernameValue = usernameValue.orEmpty(),
            passwordValue = passwordValue.orEmpty()
        )
    }

    private fun decryptForAutofill(value: String): String? {
        if (value.isBlank()) return ""
        return AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = value,
            logTag = "FilledDataBuilderNg",
        )
    }
}

private fun <T> List<T>.getOrLastOrNull(index: Int): T? =
    getOrNull(index) ?: lastOrNull()
