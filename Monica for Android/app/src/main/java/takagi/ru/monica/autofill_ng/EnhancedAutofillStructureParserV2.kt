package takagi.ru.monica.autofill_ng

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.autofill.HintConstants
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.core.safeTextOrNull
import android.util.Log
import java.net.URL
import java.util.Locale

class EnhancedAutofillStructureParserV2(
    // Independent implementation switches for compatibility comparison/rollback.
    private val browserHintTokensEnabled: Boolean = true,
    private val htmlFormScopesEnabled: Boolean = true,
    private val formPurposeEnabled: Boolean = true,
) {
    private companion object {
        private val PACKAGE_NAME_REGEX =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private val LOGIN_ALTERNATIVES_REGEX =
            Regex("\\s+(?:or|oder|ou|o|или|hoặc)\\s+|[/／、,，|]|或者?|または|또는")
    }

    data class ParsedStructure(
        val applicationId: String? = null,
        val webScheme: String? = null,
        val webDomain: String? = null,
        val webView: Boolean = false,
        val items: List<ParsedItem>,
        // Includes unclassified nodes, but only from the selected window/origin.
        // Compatibility fallbacks must not re-add nodes from another form scope.
        val scopeAutofillIds: Set<AutofillId> = emptySet(),
        val formPurpose: AutofillFormPurpose = AutofillFormPurpose.UNKNOWN,
    )

    data class ParsedItem(
        val id: AutofillId,
        val hint: FieldHint,
        val accuracy: Accuracy,
        val value: String? = null,
        val isFocused: Boolean = false,
        val isVisible: Boolean = true,
        val parentWebViewNodeId: Int? = null,
        val traversalIndex: Int = 0,
        // Browser semantics stay intact through picker authentication.
        val semanticHint: String? = null,

    )

    enum class FieldHint {
        USERNAME,
        PASSWORD,
        NEW_PASSWORD,
        EMAIL_ADDRESS,
        PHONE_NUMBER,
        SEARCH_FIELD,
        CREDIT_CARD_NUMBER,
        CREDIT_CARD_EXPIRATION_DATE,
        CREDIT_CARD_EXPIRATION_MONTH,
        CREDIT_CARD_EXPIRATION_YEAR,
        CREDIT_CARD_SECURITY_CODE,
        CREDIT_CARD_HOLDER_NAME,
        POSTAL_ADDRESS,
        POSTAL_CODE,
        PERSON_NAME,
        PERSON_FIRST_NAME,
        PERSON_LAST_NAME,
        ADDRESS_CITY,
        ADDRESS_REGION,
        ADDRESS_COUNTRY,
        COMPANY_NAME,
        IDENTITY_NUMBER,
        OTP_CODE,
        UNKNOWN,
    }

    enum class Accuracy(val score: Float) {
        LOWEST(0.3f),
        LOW(0.7f),
        MEDIUM(1.5f),
        HIGH(4f),
        HIGHEST(10f),
    }

    private enum class InternalHint {
        USERNAME,
        PASSWORD,
        NEW_PASSWORD,
        EMAIL_ADDRESS,
        PHONE_NUMBER,
        CREDIT_CARD_NUMBER,
        CREDIT_CARD_EXPIRATION_DATE,
        CREDIT_CARD_EXPIRATION_MONTH,
        CREDIT_CARD_EXPIRATION_YEAR,
        CREDIT_CARD_EXPIRATION_DAY,
        CREDIT_CARD_SECURITY_CODE,
        CREDIT_CARD_HOLDER_NAME,
        POSTAL_ADDRESS,
        POSTAL_CODE,
        PERSON_NAME,
        PERSON_FIRST_NAME,
        PERSON_LAST_NAME,
        ADDRESS_CITY,
        ADDRESS_REGION,
        ADDRESS_COUNTRY,
        COMPANY_NAME,
        IDENTITY_NUMBER,
        OTP_CODE,
        OFF,
        UNKNOWN,
    }

    private data class RawParsedStructure(
        val webScheme: String? = null,
        val webDomain: String? = null,
        val webView: Boolean = false,
        val items: List<RawParsedItem>,
        val hasFocus: Boolean = false,
        val focusedWebViewNodeId: Int? = null,
        val focusedAutofillId: AutofillId? = null,
        val nodeIds: Set<AutofillId> = emptySet(),
    )

    private data class RawParsedItem(
        val id: AutofillId,
        val accuracy: Accuracy,
        val hint: InternalHint,
        val value: String? = null,
        val reason: String? = null,
        val parentWebViewNodeId: Int? = null,
        val isFocused: Boolean = false,
        val isVisible: Boolean = true,
        val traversalIndex: Int = 0,
        val hasPasswordTerm: Boolean = false,
        val hasLoginTerm: Boolean = false,
    )

    private data class ParsedItemBuilder(
        val accuracy: Accuracy,
        val hint: InternalHint,
        val value: String? = null,
        val reason: String? = null,
    )

    private data class HintScore(
        val score: Float,
        val hint: InternalHint,
        val value: String?,
        val accuracy: Accuracy,
        val isFocused: Boolean,
        val isVisible: Boolean,
        val parentWebViewNodeId: Int?,
        val traversalIndex: Int,
    )

    private data class FormCue(val webScope: Int?, val formId: Int?, val text: String)

    private data class ParseContext(
        var traversalIndex: Int = 0,
        var nodesWithAutofillId: Int = 0,
        var nodesWithoutAutofillId: Int = 0,
        var totalNodesVisited: Int = 0,
        val webScopes: MutableMap<Int, WebScope> = mutableMapOf(),
        val nodeScopes: MutableMap<AutofillId, Int?> = mutableMapOf(),
        val htmlForms: AutofillHtmlFormScopes<AutofillId> = AutofillHtmlFormScopes(),
        val htmlTokens: MutableMap<AutofillId, AutofillHtmlHintTokens.Detail> = mutableMapOf(),
        val formCues: MutableList<FormCue> = mutableListOf(),
        val explicitCurrentIds: MutableSet<AutofillId> = mutableSetOf(),
        val allowFocusedUnknown: Boolean = false,
        val checkCancelled: () -> Unit = {},
    )

    private data class WebScope(var domain: String?, var scheme: String?)

    private class AutofillHintMatcher(
        val hint: InternalHint,
        val target: String,
        val partly: Boolean = false,
    ) {
        val accuracy = if (partly) Accuracy.MEDIUM else Accuracy.HIGH

        fun matches(value: String): Boolean = if (partly) {
            value.contains(target, ignoreCase = true)
        } else {
            value.equals(target, ignoreCase = true)
        }
    }

    private val autofillLabelPasswordTranslations = listOf(
        "password",
        "парол",
        "parol",
        "passwort",
        "passe",
        "密码",
        "密碼",
    )

    private val autofillLabelLoginTranslations = listOf(
        "username",
        "login",
        "account",
        "用户名",
        "用戶名",
        "账号",
        "帳號",
        "账户",
        "賬戶",
        "登录",
        "登錄",
        "логин",
        "логін",
        "identifiant",
        "utilisateur",
        "kullanıcı",
    )

    private val autofillLabel2faTranslations = listOf(
        "totp",
        "otp",
        "2fa",
    )

    private val autofillLabelEmailTranslations = listOf(
        "email",
        "e-mail",
        "почта",
        "пошта",
        "мейл",
        "мэйл",
        "майл",
        "电子邮箱",
        "電子郵箱",
    )

    private val autofillLabelCreditCardNumberTranslations = listOf(
        ".*(credit|debit|card)+.*number.*".toRegex(),
        ".*(cc|card)[-_ ]?(no|num|number).*".toRegex(),
        ".*银行卡号.*".toRegex(),
        ".*信用卡号.*".toRegex(),
        ".*卡号.*".toRegex(),
    )

    private val autofillLabelCreditCardSecurityCodeTranslations = listOf(
        "cvv",
        "cvc",
        "security code",
        "security-code",
        "card code",
        "card-code",
        "安全码",
        "验证码",
    )

    private val autofillLabelCreditCardExpirationTranslations = listOf(
        "expiry",
        "expiration",
        "exp date",
        "exp-date",
        "valid thru",
        "valid-thru",
        "有效期",
        "到期",
    )

    private val autofillLabelCreditCardHolderTranslations = listOf(
        "cardholder",
        "card holder",
        "card-holder",
        "holder name",
        "holder-name",
        "name on card",
        "持卡人",
        "持有人",
    )

    private val autofillLabelPersonFirstNameTranslations = listOf(
        "first name",
        "given name",
        "名",
    )

    private val autofillLabelPersonLastNameTranslations = listOf(
        "last name",
        "family name",
        "surname",
        "姓",
    )

    private val autofillLabelPostalAddressTranslations = listOf(
        "address",
        "street",
        "地址",
    )

    private val autofillLabelIdentityNumberTranslations = listOf(
        "passport",
        "license",
        "document number",
        "identity",
        "证件",
        "身份证",
        "护照",
        "驾照",
        "社保",
        "ssn",
    )

    private val autofillHintMatchers = listOf(
        AutofillHintMatcher(
            hint = InternalHint.EMAIL_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_EMAIL_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = InternalHint.EMAIL_ADDRESS,
            target = "email",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.USERNAME,
            target = HintConstants.AUTOFILL_HINT_USERNAME,
        ),
        AutofillHintMatcher(
            hint = InternalHint.USERNAME,
            target = "nickname",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PASSWORD,
            target = HintConstants.AUTOFILL_HINT_PASSWORD,
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "newPassword",
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "new_password",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PASSWORD,
            target = "password",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PHONE_NUMBER,
            target = HintConstants.AUTOFILL_HINT_PHONE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PHONE_NUMBER,
            target = HintConstants.AUTOFILL_HINT_PHONE_NUMBER,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PHONE_NUMBER,
            target = "phone",
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "new-password",
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "new password",
        ),
        AutofillHintMatcher(
            hint = InternalHint.USERNAME,
            target = "new-username",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_NUMBER,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_NUMBER,
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_NUMBER,
            target = "cc-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_NUMBER,
            target = "credit_card_number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            target = "cc-csc",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            target = "credit_card_csv",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            target = "cc-exp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_MONTH,
            target = "cc-exp-month",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_YEAR,
            target = "cc-exp-year",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_CODE,
            target = HintConstants.AUTOFILL_HINT_POSTAL_CODE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_NAME,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_NAME,
            target = HintConstants.AUTOFILL_HINT_NAME,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_FIRST_NAME,
            target = "given-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_FIRST_NAME,
            target = "first-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_LAST_NAME,
            target = "family-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_LAST_NAME,
            target = "last-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = "street-address",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = "address-line1",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = "address-line2",
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_CITY,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_LOCALITY,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_CITY,
            target = "address-level2",
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_CITY,
            target = "city",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_REGION,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = "address-level1",
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = "state",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = "province",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_COUNTRY,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_COUNTRY,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_COUNTRY,
            target = "country",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.COMPANY_NAME,
            target = "organization",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.COMPANY_NAME,
            target = "company",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "passport-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "document-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "identity-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "license-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "driver-license",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "ssn",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "one-time-code",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "sms-otp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "email-otp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "totp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "2fa",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OFF,
            target = "chrome-off",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OFF,
            target = "off",
        ),
    )

    fun parse(
        structure: AssistStructure,
        respectAutofillOff: Boolean = true,
        allowWeakTargets: Boolean = false,
        requireExplicitWeakLoginSignal: Boolean = false,
        checkCancelled: () -> Unit = {},
    ): ParsedStructure {
        var applicationId: String? = structure.activityComponent?.packageName
        var rawStructure: RawParsedStructure? = null
        val parseContext = ParseContext(allowFocusedUnknown = allowWeakTargets && !requireExplicitWeakLoginSignal, checkCancelled = checkCancelled)

        // The last window is the topmost one. Prefer its focused field over
        // a login form left behind a dialog; do not combine different windows.
        for (i in structure.windowNodeCount - 1 downTo 0) {
            val windowNode = structure.getWindowNodeAt(i)
            val appIdCandidate = windowNode.title?.toString()?.split("/")?.firstOrNull()
            if (!appIdCandidate.isNullOrBlank()) {
                if (appIdCandidate.contains(":")) {
                    continue
                }
                val normalizedCandidate = appIdCandidate.trim()
                if (isLikelyAndroidPackageName(normalizedCandidate)) {
                    applicationId = normalizedCandidate
                }
            }

            val nodeStructure = parseViewNode(
                node = windowNode.rootViewNode,
                context = parseContext,
            )
            if (rawStructure == null) {
                rawStructure = nodeStructure
            }
            val hasItems = nodeStructure.items.any { it.hint != InternalHint.OFF }
            if (nodeStructure.hasFocus || (hasItems && rawStructure?.items?.none { it.hint != InternalHint.OFF } == true)) {
                rawStructure = nodeStructure
            }
            if (nodeStructure.hasFocus) {
                break
            }
        }

        val selectedScopeId = if (rawStructure?.hasFocus == true) {
            rawStructure.focusedWebViewNodeId
        } else {
            rawStructure?.items?.firstOrNull { it.hint != InternalHint.OFF }?.parentWebViewNodeId
        }
        val selectedWebScope = selectedScopeId?.let(parseContext.webScopes::get)
        val scopeItems = rawStructure?.items.orEmpty().filter { it.parentWebViewNodeId == selectedScopeId }
        val selectedForm = parseContext.htmlForms.owner(rawStructure?.focusedAutofillId
            ?: scopeItems.firstOrNull { it.hint != InternalHint.OFF }?.id)
        val focusedId = rawStructure?.focusedAutofillId ?: scopeItems.firstOrNull { it.hint != InternalHint.OFF }?.id
        val focusedToken = parseContext.htmlTokens[focusedId]
        fun inSelectedForm(id: AutofillId): Boolean {
            if (!htmlFormScopesEnabled) return true
            if (parseContext.htmlForms.owner(id) != selectedForm) return false
            val token = parseContext.htmlTokens[id]
            return if (focusedToken != null) focusedToken.sameSection(token)
            else token == null || token.sameSection(null)
        }
        val repeatedBlock = if (htmlFormScopesEnabled && selectedScopeId != null && focusedId != null) {
            AutofillRepeatedFieldScope.select(scopeItems.filter { inSelectedForm(it.id) }
                .distinctBy { it.id }.mapNotNull { item -> parseContext.htmlTokens[item.id]?.let { item.id to it.fillHint } }, focusedId)
        } else null
        fun inSelectedBlock(id: AutofillId) = inSelectedForm(id) && (repeatedBlock == null || id in repeatedBlock)
        val scopeAutofillIds = rawStructure?.nodeIds.orEmpty()
            .filterTo(mutableSetOf()) { parseContext.nodeScopes[it] == selectedScopeId && inSelectedBlock(it) }
        val candidateItems = scopeItems.filter { inSelectedBlock(it.id) }
        rawStructure = rawStructure?.copy(
            webView = selectedScopeId != null,
            webDomain = selectedWebScope?.domain,
            webScheme = selectedWebScope?.scheme,
        )

        val confidenceFilteredItems = candidateItems.let { list ->
            if (allowWeakTargets) return@let list
            val onlyLowAccuracy = list.all {
                it.accuracy.score <= Accuracy.LOW.score || it.hint == InternalHint.OFF
            }
            if (!onlyLowAccuracy) {
                return@let list
            }

            // 全 LOW 精度时，只要有密码框（即使账号框精度低/缺失）就保留所有字段，
            // 登录场景的关键判定信号是密码框而非账号框。纯搜索框/备注等（无密码框）
            // 仍返回空，不会误弹密码建议（如 QQ 搜索框）。
            val hasPasswordLike = list.any {
                val passwordLike =
                    it.hint == InternalHint.PASSWORD ||
                        it.hint == InternalHint.NEW_PASSWORD
                passwordLike && it.accuracy.score > Accuracy.LOWEST.score
            }
            if (hasPasswordLike) {
                list
            } else {
                emptyList()
            }
        }

        // 弱目标模式分为两种：
        // - 手动请求：维持原有宽松行为，允许用户主动选择任意弱字段。
        // - 自动兼容重解析：密码术语可以提升密码字段；孤立的弱账号字段只有在自身携带
        //   明确 login/account/账号等术语时才提升到 MEDIUM。普通数量、金额和搜索字段
        //   不会因为首轮无结果而获得与手动请求相同的放行权限。
        val effectiveItems = if (allowWeakTargets) {
            promotePasswordTermCandidates(confidenceFilteredItems)
        } else {
            confidenceFilteredItems
        }
        val promotedPasswordCount = effectiveItems.zip(confidenceFilteredItems).count { (after, before) ->
            (after.hint == InternalHint.PASSWORD || after.hint == InternalHint.NEW_PASSWORD) &&
                after.hint != before.hint
        }
        val hasPasswordInItems = effectiveItems.any {
            it.hint == InternalHint.PASSWORD || it.hint == InternalHint.NEW_PASSWORD
        }
        val hasLoginTypeField = effectiveItems.any {
            it.hint == InternalHint.USERNAME ||
                it.hint == InternalHint.EMAIL_ADDRESS ||
                it.hint == InternalHint.PHONE_NUMBER
        }
        val automaticWeakReparse = allowWeakTargets && requireExplicitWeakLoginSignal
        val weakLoginContext = automaticWeakReparse && !hasPasswordInItems && effectiveItems.any {
            (it.hint == InternalHint.USERNAME ||
                it.hint == InternalHint.EMAIL_ADDRESS ||
                it.hint == InternalHint.PHONE_NUMBER) &&
                it.hasLoginTerm
        }
        val loginFilteredItems = when {
            hasPasswordInItems -> effectiveItems
            allowWeakTargets && !automaticWeakReparse -> effectiveItems
            automaticWeakReparse -> effectiveItems.mapNotNull { item ->
                val accountLike = item.hint == InternalHint.USERNAME ||
                    item.hint == InternalHint.EMAIL_ADDRESS ||
                    item.hint == InternalHint.PHONE_NUMBER
                if (!accountLike) {
                    item
                } else {
                    AutofillDetectionPolicy.automaticWeakAccountAccuracy(
                        accuracy = item.accuracy,
                        hasLoginTerm = item.hasLoginTerm,
                    )?.let { admittedAccuracy ->
                        item.copy(accuracy = admittedAccuracy)
                    }
                }
            }
            else -> effectiveItems.filterNot {
                (it.hint == InternalHint.USERNAME ||
                    it.hint == InternalHint.EMAIL_ADDRESS ||
                    it.hint == InternalHint.PHONE_NUMBER) &&
                    it.accuracy.score < Accuracy.MEDIUM.score
            }
        }

        val items = mutableListOf<ParsedItem>()
        AutofillLogger.d(
            "PARSING",
            "Parser field selection",
            metadata = mapOf(
                "candidateCount" to candidateItems.size,
                "confidenceFilteredCount" to confidenceFilteredItems.size,
                "loginFilteredCount" to loginFilteredItems.size,
                "candidates" to candidateItems.joinToString { "${it.hint}:${it.accuracy.name}" },
                "allowWeakTargets" to allowWeakTargets,
                "requireExplicitWeakLoginSignal" to requireExplicitWeakLoginSignal,
                "promotedPasswordCount" to promotedPasswordCount,
                "hasPasswordInItems" to hasPasswordInItems,
                "hasLoginTypeField" to hasLoginTypeField,
                "weakLoginContext" to weakLoginContext,
            )
        )
        loginFilteredItems
            .groupBy { it.id }
            .forEach { groupedById ->
                val forceAutofillOff = groupedById.value.any {
                    it.hint == InternalHint.OFF && it.accuracy == Accuracy.HIGHEST
                }
                val isAlwaysExcluded = groupedById.value.any {
                    it.hint == InternalHint.OFF && it.reason == "url-bar"
                }
                if (AutofillDetectionPolicy.shouldSkipAutofillOffGroup(
                        respectAutofillOff = respectAutofillOff,
                        hasForcedOffSignal = forceAutofillOff,
                        isAlwaysExcluded = isAlwaysExcluded,
                    )
                ) {
                    return@forEach
                }
                var structureItems = if (forceAutofillOff) {
                    // 用户关闭“遵循 autofill-off”后，允许同组的明确字段信号覆盖
                    // importantForAutofill=NO；URL 栏仍由 isAlwaysExcluded 永久排除。
                    val nonOff = groupedById.value.filter { it.hint != InternalHint.OFF }
                    if (nonOff.isNotEmpty()) nonOff else return@forEach
                } else if (respectAutofillOff) {
                    if (groupedById.value.any { it.hint == InternalHint.OFF }) {
                        return@forEach
                    }
                    groupedById.value
                } else {
                    groupedById.value.filter { it.hint != InternalHint.OFF }
                }

                val derivesOfPassword = structureItems.any {
                    it.hint == InternalHint.CREDIT_CARD_SECURITY_CODE || it.hint == InternalHint.OTP_CODE
                }
                if (derivesOfPassword) {
                    structureItems = structureItems.filter { it.hint != InternalHint.PASSWORD }
                }

                // Explicit new-password semantics must not lose to type=password or label heuristics.
                if (structureItems.any { it.hint == InternalHint.NEW_PASSWORD && it.accuracy.score >= Accuracy.HIGH.score }) {
                    structureItems = structureItems.filter { it.hint != InternalHint.PASSWORD }
                }

                val derivesOfUsername = structureItems.any {
                    it.hint == InternalHint.CREDIT_CARD_NUMBER ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_DATE ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_MONTH ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_YEAR ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_DAY
                }
                if (derivesOfUsername) {
                    structureItems = structureItems.filter { it.hint != InternalHint.USERNAME }
                }

                val rankedItems = structureItems
                    .groupBy { it.hint }
                    .mapNotNull { groupedByHint ->
                        val score = groupedByHint.value.fold(0f) { acc, item ->
                            acc + item.accuracy.score
                        }
                        val best = groupedByHint.value
                            .maxByOrNull { it.accuracy.score }
                            ?: return@mapNotNull null
                        val value = groupedByHint.value
                            .sortedByDescending { it.accuracy.score }
                            .asSequence()
                            .mapNotNull { it.value }
                            .firstOrNull()
                        HintScore(
                            score = score,
                            hint = groupedByHint.key,
                            value = value,
                            accuracy = best.accuracy,
                            isFocused = best.isFocused,
                            isVisible = best.isVisible,
                            parentWebViewNodeId = best.parentWebViewNodeId,
                            traversalIndex = best.traversalIndex,
                        )
                    }
                val fieldRoleSelection = AutofillFieldRolePolicy.selectWithDiagnostics(
                    rankedItems.mapNotNull { item ->
                        val mappedHint = mapHint(item.hint) ?: return@mapNotNull null
                        AutofillFieldRoleCandidate(
                            value = item,
                            hint = mappedHint,
                            score = item.score,
                            strongestAccuracy = item.accuracy,
                        )
                    }
                )
                    ?: return@forEach
                val selectedItem = fieldRoleSelection.value
                if (fieldRoleSelection.resolvedExplicitAccountPasswordConflict) {
                    AutofillLogger.w(
                        "PARSING",
                        "Explicit account evidence overrode conflicting password evidence",
                        metadata = mapOf(
                            "selectedHint" to (mapHint(selectedItem.hint)?.name ?: "none"),
                            "candidateRoles" to rankedItems.joinToString(separator = ",") { item ->
                                "${mapHint(item.hint)?.name ?: "ignored"}:${item.score}:${item.accuracy.name}"
                            },
                            "focused" to selectedItem.isFocused,
                            "visible" to selectedItem.isVisible,
                            "traversalIndex" to selectedItem.traversalIndex,
                        ),
                    )
                }

                if (selectedItem.score <= Accuracy.LOWEST.score + 0.1f) {
                    val shouldSkip = rawStructure?.items.orEmpty().any {
                        it.hint == selectedItem.hint &&
                            it.accuracy.score > Accuracy.LOWEST.score
                    }
                    if (shouldSkip) {
                        return@forEach
                    }
                }

                val mappedHint = mapHint(selectedItem.hint) ?: return@forEach
                items += ParsedItem(
                    id = groupedById.key,
                    semanticHint = parseContext.htmlTokens[groupedById.key]?.fillHint,
                    hint = mappedHint,
                    value = selectedItem.value,
                    accuracy = selectedItem.accuracy,
                    isFocused = selectedItem.isFocused,
                    isVisible = selectedItem.isVisible,
                    parentWebViewNodeId = selectedItem.parentWebViewNodeId,
                    traversalIndex = selectedItem.traversalIndex,
                )
            }

        // 诊断：记录 groupBy 后每组被跳过的原因，排查 loginFilteredCount>0 但 parsedItems=0
        if (loginFilteredItems.isNotEmpty() && items.isEmpty()) {
            val groupDiagnostics = loginFilteredItems
                .groupBy { it.id }
                .map { (id, group) ->
                    val hints = group.joinToString { "${it.hint}:${it.accuracy.name}" }
                    val hasOff = group.any { it.hint == InternalHint.OFF }
                    val mappedHints = group.mapNotNull { mapHint(it.hint) }
                    "id=$id hints=[$hints] hasOff=$hasOff mappedCount=${mappedHints.size}"
                }
            AutofillLogger.w(
                "PARSING",
                "All groups skipped after groupBy despite non-empty loginFilteredItems",
                metadata = mapOf(
                    "loginFilteredCount" to loginFilteredItems.size,
                    "groupCount" to groupDiagnostics.size,
                    "respectAutofillOff" to respectAutofillOff,
                    "groups" to groupDiagnostics.joinToString(" | "),
                ),
            )
        }

        val isInSelfHostedServer = kotlin.run {
            val webDomain = rawStructure?.webDomain
            val webView = rawStructure?.webView == true
            // Hybrid apps may serve their own UI from loopback. A browser's
            // loopback page is still a website and must keep its reported origin.
            webView && !AutofillRequestContextPolicy.isKnownBrowser(
                structure.activityComponent?.packageName ?: applicationId.orEmpty()) &&
                (webDomain == "127.0.0.1" || webDomain == "localhost")
        }

        val effectiveWebDomain = rawStructure?.webDomain.takeUnless { isInSelfHostedServer }
            ?: if (rawStructure?.webView == true && !isInSelfHostedServer) extractDomainFromStructureText(structure) else null
        if (effectiveWebDomain != null && rawStructure?.webDomain == null && !isInSelfHostedServer) {
            Log.d("MonicaAutofill", "webDomain recovered from structure text: $effectiveWebDomain")
        }

        Log.d(
            "MonicaAutofill",
            "parse result: items=${items.size}, totalNodes=${parseContext.totalNodesVisited}, " +
                "withAutofillId=${parseContext.nodesWithAutofillId}, " +
                "withoutAutofillId=${parseContext.nodesWithoutAutofillId}, " +
                "webDomain=$effectiveWebDomain, webScheme=${rawStructure?.webScheme}, " +
                "itemHints=[${items.joinToString { "${it.hint}:${it.accuracy.name}" }}]"
        )

        val form = AutofillFormPurposePolicy.analyze(
            items = items.sortedBy { it.traversalIndex },
            staticCues = if (formPurposeEnabled) parseContext.formCues.filter {
                it.webScope == selectedScopeId && (!htmlFormScopesEnabled || it.formId == selectedForm)
            }.map { it.text } else emptyList(),
            explicitCurrentIds = parseContext.explicitCurrentIds.intersect(scopeAutofillIds),
        )
        return ParsedStructure(
            applicationId = applicationId,
            webDomain = effectiveWebDomain,
            webScheme = rawStructure?.webScheme.takeUnless { isInSelfHostedServer },
            webView = if (isInSelfHostedServer) false else rawStructure?.webView == true,
            items = form.items,
            scopeAutofillIds = scopeAutofillIds,
            formPurpose = form.purpose,
        )
    }

    private fun isLikelyAndroidPackageName(value: String): Boolean {
        if (value.length !in 3..255) return false
        if (!value.contains('.')) return false
        return PACKAGE_NAME_REGEX.matches(value)
    }

    /**
     * Last-resort fallback: scan AssistStructure text nodes for URL-like strings
     * and extract the domain. This helps when the browser doesn't report webDomain
     * for HTTP pages.
     */
    private fun extractDomainFromStructureText(structure: AssistStructure): String? {
        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val domain = scanNodeForUrlDomain(windowNode.rootViewNode, depth = 0)
            if (domain != null) return domain
        }
        return null
    }

    private fun scanNodeForUrlDomain(node: AssistStructure.ViewNode, depth: Int): String? {
        if (depth > 6) return null
        // Page text (including a value typed by the user) is not evidence of
        // origin. Only a browser's address-bar node can supply this fallback.
        val addressBar = node.idEntry.orEmpty().lowercase(Locale.ROOT).let {
            it in setOf("url_bar", "location_bar_edit_text", "mozac_browser_toolbar_url_view", "url_bar_title")
        }
        val text = node.text?.toString()?.trim().takeIf { addressBar }
        if (!text.isNullOrBlank()) {
            val domain = extractDomainFromUrl(text)
            if (domain != null) return domain
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            val domain = scanNodeForUrlDomain(child, depth + 1)
            if (domain != null) return domain
        }
        return null
    }

    private fun extractDomainFromUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < 4 || trimmed.length > 2048) return null
        if (!trimmed.contains(".")) return null
        // Skip obvious non-URL text (e.g. "v2.0", "3.14", placeholder text without slashes/colons)
        val looksLikeUrl = trimmed.contains("://") ||
            trimmed.startsWith("www.") ||
            (trimmed.contains(".") && trimmed.any { it == '/' || it == ':' })
        if (!looksLikeUrl) return null
        val urlStr = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = runCatching { URL(urlStr).host }.getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it.contains(".") && it.any { c -> c.isLetter() } }
        return host
    }

    private fun parseViewNode(
        node: AssistStructure.ViewNode,
        parentWebViewNodeId: Int? = null,
        parentHtmlFormId: Int? = null,
        context: ParseContext,
    ): RawParsedStructure {
        var webView = false
        val rawWebDomain = node.webDomain
        val rawWebScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.webScheme else null
        var webDomain: String? = rawWebDomain?.takeIf { it.isNotEmpty() }
        var webScheme: String? = rawWebScheme?.takeIf { it.isNotEmpty() }
        if (context.traversalIndex == 0) {
            Log.d(
                "MonicaAutofill",
                "parseViewNode root: rawWebDomain=$rawWebDomain, rawWebScheme=$rawWebScheme, " +
                    "webDomain=$webDomain, webScheme=$webScheme, " +
                    "className=${node.className}, sdk=${Build.VERSION.SDK_INT}"
            )
        }

        val out = mutableListOf<RawParsedItem>()
        webView = node.className == "android.webkit.WebView"
        val html = node.htmlInfo
        // Some providers reuse the WebView class name for virtual HTML controls.
        // Those controls belong to their ancestor's scope; they are not new documents.
        val isHtmlControl = html?.tag?.lowercase(Locale.ROOT) in setOf("input", "select", "textarea")
        val startsWebViewScope = webView && (parentWebViewNodeId == null || !isHtmlControl)
        val inheritedScope = parentWebViewNodeId?.let(context.webScopes::get)
        val changesOrigin = webDomain != null && inheritedScope?.domain != null &&
            !webDomain.equals(inheritedScope.domain, ignoreCase = true)
        // View ids are often NO_ID for multiple WebViews. Traversal identity
        // keeps their virtual fields (and cross-origin subtrees) separate.
        val webViewNodeId = if (startsWebViewScope || changesOrigin || (webDomain != null && parentWebViewNodeId == null)) {
            context.totalNodesVisited.also {
                // Some providers report the origin on an enclosing virtual node only.
                // Inherit from this ancestor, never from an unrelated sibling WebView.
                context.webScopes[it] = WebScope(webDomain ?: inheritedScope?.domain,
                    webScheme ?: inheritedScope?.scheme)
            }
        } else {
            if (inheritedScope != null && inheritedScope.domain == null && webDomain != null) {
                inheritedScope.domain = webDomain
                inheritedScope.scheme = webScheme
            }
            parentWebViewNodeId
        }
        var hasFocus = node.isFocused
        var focusedWebViewNodeId = webViewNodeId.takeIf { hasFocus }
        var focusedAutofillId = node.autofillId.takeIf { hasFocus }
        var htmlFormId = parentHtmlFormId.takeIf { webViewNodeId == parentWebViewNodeId }
        if (htmlFormScopesEnabled && webViewNodeId != null && html?.tag.equals("form", ignoreCase = true)) {
            htmlFormId = context.totalNodesVisited
            context.htmlForms.registerForm(webViewNodeId, htmlFormId,
                html?.attributes?.firstOrNull { it.first.equals("id", ignoreCase = true) }?.second)
        }
        val nodeIds = mutableSetOf<AutofillId>()
        node.autofillId?.let {
            nodeIds += it
            context.nodeScopes[it] = webViewNodeId
            if (htmlFormScopesEnabled && webViewNodeId != null) {
                val explicitForm = html?.takeIf { it.tag.equals("input", ignoreCase = true) ||
                    it.tag.equals("select", ignoreCase = true) || it.tag.equals("textarea", ignoreCase = true) }
                    ?.attributes?.firstOrNull { it.first.equals("form", ignoreCase = true) }?.second
                context.htmlForms.registerField(it, webViewNodeId, htmlFormId, explicitForm, context.totalNodesVisited)
            }
        }

        if (formPurposeEnabled) {
            val attributes = html?.attributes.orEmpty()
            fun cue(value: String?) {
                if (!value.isNullOrBlank() && context.formCues.size < 64) {
                    context.formCues += FormCue(webViewNodeId, htmlFormId, value.take(256))
                }
            }
            if (html?.tag.equals("form", ignoreCase = true)) {
                attributes.filter { it.first.lowercase(Locale.ROOT) in setOf("id", "name", "action") }
                    .forEach { cue(it.second?.substringBefore('?')) }
            }
            val isButton = node.className?.toString()?.endsWith("Button") == true ||
                html?.tag.equals("button", ignoreCase = true) || attributes.any {
                    it.first.equals("type", true) && it.second.equals("submit", true)
                }
            if (isButton) {
                cue(node.text?.toString())
                cue(node.contentDescription?.toString())
                attributes.filter { it.first.lowercase(Locale.ROOT) in setOf("value", "aria-label") }
                    .forEach { cue(it.second) }
            }
            val labels = listOfNotNull(node.hint, node.contentDescription).map { it.toString() } +
                attributes.filter { it.first.lowercase(Locale.ROOT) in setOf("label", "placeholder", "aria-label") }.map { it.second.orEmpty() }
            val hints = node.autofillHints.orEmpty().toList() + attributes.filter {
                it.first.lowercase(Locale.ROOT) in setOf("autocomplete", "ua-autofill-hints", "computed-autofill-hints")
            }.map { it.second.orEmpty() }
            if (labels.any(AutofillFormPurposePolicy::isCurrentPasswordLabel) || hints.any {
                    it.take(1024).lowercase(Locale.ROOT).split(Regex("\\s+")).contains("current-password")
                }) node.autofillId?.let(context.explicitCurrentIds::add)
        }

        if (context.totalNodesVisited % 32 == 0) context.checkCancelled()
        context.totalNodesVisited++
        if (node.autofillId != null) {
            context.nodesWithAutofillId++
        } else {
            context.nodesWithoutAutofillId++
        }

        if (node.autofillId != null) {
            val outBuilders = mutableListOf<ParsedItemBuilder>()
            val hints = node.autofillHints
            if (!hints.isNullOrEmpty()) {
                outBuilders += parseNodeByAutofillHint(node, browserHintTokensEnabled &&
                    (webViewNodeId != null || html?.tag.equals("input", ignoreCase = true)))
            }

            outBuilders += parseNodeByHtmlAttributes(node)
            val inputOut = parseNodeByAndroidInput(node)
            val labelOut = parseNodeByLabel(node)
            outBuilders += inputOut + labelOut
            if (browserHintTokensEnabled && webViewNodeId != null) {
                val attributes = node.htmlInfo?.attributes.orEmpty()
                // Chromium serializes HTML autocomplete as separate tokens in autofillHints.
                // Reassemble the grammar before treating native hints as independent aliases.
                val values = attributes.filter { it.first.equals("autocomplete", true) }.map { it.second } +
                    listOf(node.autofillHints.orEmpty().joinToString(" ")) +
                    attributes.filter { it.first.lowercase(Locale.ROOT) in setOf("ua-autofill-hints", "computed-autofill-hints") }.map { it.second } +
                    node.autofillHints.orEmpty()
                values.firstNotNullOfOrNull { AutofillHtmlHintTokens.detail(it) }?.let { detail ->
                    context.htmlTokens[node.autofillId!!] = detail
                    val coarse = AutofillHtmlHintTokens.coarseHint(detail.field)!!
                    if (outBuilders.none { it.hint == InternalHint.OFF }) {
                        outBuilders.clear()
                        outBuilders += ParsedItemBuilder(Accuracy.HIGHEST, InternalHint.valueOf(coarse.name), reason = "html-semantic-token")
                    }
                }
            }
            if (formPurposeEnabled && AutofillFormPurposePolicy.isNewPasswordLabel(node.contentDescription?.toString().orEmpty())) {
                outBuilders += ParsedItemBuilder(Accuracy.HIGH, InternalHint.NEW_PASSWORD, reason = "new-password-label")
            }

            // An explicit system "Autofill" request can open the picker for
            // an unlabelled account field. Automatic reparsing stays conservative.
            if (outBuilders.isEmpty() && context.allowFocusedUnknown && node.isFocused && node.isEnabled &&
                node.autofillType == View.AUTOFILL_TYPE_TEXT &&
                node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                node.inputType and InputType.TYPE_MASK_VARIATION in setOf(
                    InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                )
            ) {
                outBuilders += ParsedItemBuilder(Accuracy.LOWEST, InternalHint.USERNAME, reason = "manual-focused-text")
            }

            // 搜索框识别与排除：搜索框不应作为登录凭据字段。否则在「页面存在密码框即整页按登录
            // 上下文处理」的策略下，聚焦搜索框也会弹出密码条目（Edge 访问 GitHub 时顶部搜索框误弹）。
            // 命中则清空凭据候选，使该节点不进入 ParsedStructure.items，从而不触发填充建议。
            if (outBuilders.isNotEmpty() && isSearchField(node)) {
                AutofillLogger.d(
                    "PARSING",
                    "Search field excluded from credential fill",
                    metadata = mapOf(
                        "htmlTag" to (node.htmlInfo?.tag ?: "none"),
                        "ariaLabel" to (node.htmlInfo?.attributes
                            ?.firstOrNull { it.first.equals("aria-label", ignoreCase = true) }?.second ?: "none"),
                        "placeholder" to (node.htmlInfo?.attributes
                            ?.firstOrNull { it.first.equals("placeholder", ignoreCase = true) }?.second ?: "none"),
                        "isFocused" to node.isFocused,
                    )
                )
                outBuilders.clear()
            }

            if (node.visibility == View.VISIBLE || shouldIncludeHiddenCredentialNode(outBuilders)) {
                val nodeHasPasswordTerm = nodeHasPasswordTermMatch(node)
                val nodeHasLoginTerm = nodeHasLoginTermMatch(node)
                // 诊断：当节点被识别为 LOWEST 精度的 USERNAME（纯 text fallback 路径）时，
                // 记录其原始信号，用于排查电影猎手等 App 的账号框为何被弱推断、
                // 以及 bitwarden 等为何能识别（对比 autofillHints/idEntry/hint/inputType）。
                if (outBuilders.any { it.hint == InternalHint.USERNAME &&
                    it.accuracy == Accuracy.LOWEST }) {
                    AutofillLogger.d(
                        "PARSING",
                        "LOWEST username node signals",
                        metadata = mapOf(
                            "className" to (node.className ?: "none"),
                            "inputType" to node.inputType.toString(),
                            "autofillHints" to (node.autofillHints?.joinToString(",") ?: "none"),
                            "idEntry" to (node.idEntry ?: "none"),
                            "hintLabel" to (node.hint?.toString() ?: "none"),
                            "hasPasswordTerm" to nodeHasPasswordTerm,
                            "hasLoginTerm" to nodeHasLoginTerm,
                            "htmlTag" to (node.htmlInfo?.tag ?: "none"),
                            "htmlAttrNames" to (node.htmlInfo?.attributes
                                ?.map { it.first }
                                ?.distinct()
                                ?.joinToString(",") ?: "none"),
                            "isVisible" to (node.visibility == View.VISIBLE),
                            "isFocused" to node.isFocused,
                        ),
                    )
                }
                out += outBuilders.map { builder ->
                    context.traversalIndex += 1
                    RawParsedItem(
                        id = node.autofillId!!,
                        accuracy = builder.accuracy,
                        hint = builder.hint,
                        value = builder.value ?: node.autofillValue.safeTextOrNull(
                            tag = "EnhancedParserV2",
                            fieldDescription = builder.hint.name,
                        ),
                        reason = builder.reason,
                        parentWebViewNodeId = webViewNodeId,
                        isFocused = node.isFocused,
                        isVisible = node.visibility == View.VISIBLE,
                        traversalIndex = context.traversalIndex,
                        hasPasswordTerm = nodeHasPasswordTerm,
                        hasLoginTerm = nodeHasLoginTerm,
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val childStructure = parseViewNode(
                node = node.getChildAt(i),
                parentWebViewNodeId = webViewNodeId,
                parentHtmlFormId = htmlFormId,
                context = context,
            )
            if (childStructure.webView) {
                webView = true
            }
            webDomain = webDomain ?: childStructure.webDomain
            webScheme = webScheme ?: childStructure.webScheme
            out += childStructure.items
            nodeIds += childStructure.nodeIds
            if (childStructure.hasFocus) {
                hasFocus = true
                focusedWebViewNodeId = childStructure.focusedWebViewNodeId
                focusedAutofillId = childStructure.focusedAutofillId
            }
        }

        return RawParsedStructure(
            webScheme = webScheme,
            webDomain = webDomain,
            webView = webView,
            items = out,
            hasFocus = hasFocus,
            focusedWebViewNodeId = focusedWebViewNodeId,
            focusedAutofillId = focusedAutofillId,
            nodeIds = nodeIds,
        )
    }

    private fun parseNodeByAutofillHint(
        node: AssistStructure.ViewNode,
        useHtmlTokens: Boolean = false,
    ): List<ParsedItemBuilder> = kotlin.run {
        val out = mutableListOf<ParsedItemBuilder>()
        node.autofillHints?.forEach { value ->
            // Chromium also exposes the autocomplete token list through native autofillHints.
            // Section names must not reintroduce a role that HTML token parsing excluded.
            val matchers = if (useHtmlTokens) matchHtmlHint(value)
                else autofillHintMatchers.filter { matcher -> matcher.matches(value) }
            matchers.forEach { matcher ->
                out += ParsedItemBuilder(
                    accuracy = matcher.accuracy,
                    hint = matcher.hint,
                    reason = if (useHtmlTokens) "html-field-token" else "autofill-hint",
                )
            }
        }
        out
    }

    private fun matchHtmlHint(value: String): List<AutofillHintMatcher> {
        val token = AutofillHtmlHintTokens.fieldToken(value) ?: return emptyList()
        return autofillHintMatchers.filter { it.target.equals(token, ignoreCase = true) }.distinctBy { it.hint }
    }

    private fun parseNodeByHtmlAttributes(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> = kotlin.run {
        val out = mutableListOf<ParsedItemBuilder>()
        val nodeHtml = node.htmlInfo
        when (nodeHtml?.tag?.lowercase(Locale.ENGLISH)) {
            "input" -> {
                val attributes = kotlin.run {
                    nodeHtml.attributes
                        ?.map { it.first to it.second }
                        ?.takeUnless { it.isEmpty() } ?: kotlin.runCatching {
                        val values = nodeHtml.javaClass.getDeclaredField("mValues")
                            .apply { isAccessible = true }
                            .get(nodeHtml)
                        val names = nodeHtml.javaClass.getDeclaredField("mNames")
                            .apply { isAccessible = true }
                            .get(nodeHtml)
                        if (values is Array<*> && names is Array<*>) {
                            val count = minOf(values.size, names.size)
                            (0 until count).map { i ->
                                names[i]?.toString().orEmpty() to values[i]?.toString().orEmpty()
                            }
                        } else if (values is Collection<*> && names is Collection<*>) {
                            val valuesList = values.toList()
                            val namesList = names.toList()
                            val count = minOf(valuesList.size, namesList.size)
                            (0 until count).map { i ->
                                namesList[i]?.toString().orEmpty() to valuesList[i]?.toString().orEmpty()
                            }
                        } else {
                            null
                        }
                    }.getOrNull()
                }

                attributes?.forEach { attribute ->
                    val key = attribute.first.lowercase(Locale.ENGLISH)
                    when (key) {
                        "autocomplete",
                        "ua-autofill-hints",
                        "computed-autofill-hints",
                        -> {
                            val value = attribute.second?.lowercase(Locale.ENGLISH).orEmpty()
                            val matchers = if (browserHintTokensEnabled) matchHtmlHint(value)
                            else if (key != "computed-autofill-hints") {
                                autofillHintMatchers.filter { it.matches(value) }
                            } else emptyList()
                            matchers.forEach { matcher ->
                                out += ParsedItemBuilder(
                                    accuracy = matcher.accuracy,
                                    hint = matcher.hint,
                                    reason = if (browserHintTokensEnabled) "html-field-token" else key,
                                )
                            }
                        }

                        "type" -> {
                            val type = attribute.second.orEmpty()
                            extractOfType(type).let(out::addAll)
                        }

                        "inputmode" -> {
                            if (attribute.second.equals("tel", ignoreCase = true)) {
                                out += ParsedItemBuilder(
                                    accuracy = Accuracy.MEDIUM,
                                    hint = InternalHint.PHONE_NUMBER,
                                    reason = "inputmode",
                                )
                            }
                        }

                        "name" -> {
                            val type = attribute.second.orEmpty()
                            extractOfId(type).let(out::addAll)
                        }

                        "id" -> {
                            val type = attribute.second.orEmpty()
                            extractOfId(type).let(out::addAll)
                        }

                        "label" -> {
                            val label = attribute.second.orEmpty()
                            extractOfLabel(label).let(out::addAll)
                        }

                        "placeholder",
                        "aria-label",
                        -> {
                            val label = attribute.second.orEmpty()
                            extractOfLabel(label).let(out::addAll)
                        }
                    }
                }
            }
        }
        out
    }

    private fun parseNodeByLabel(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> {
        val hint = node.hint ?: return emptyList()
        return extractOfLabel(hint)
    }

    private fun extractOfType(
        value: String,
    ): List<ParsedItemBuilder> = when (value.lowercase(Locale.ENGLISH)) {
        "tel" -> ParsedItemBuilder(
            accuracy = Accuracy.MEDIUM,
            hint = InternalHint.PHONE_NUMBER,
            reason = "type",
        )

        "email" -> ParsedItemBuilder(
            accuracy = Accuracy.MEDIUM,
            hint = InternalHint.EMAIL_ADDRESS,
            reason = "type",
        )

        "username" -> ParsedItemBuilder(
            accuracy = Accuracy.MEDIUM,
            hint = InternalHint.USERNAME,
            reason = "type",
        )

        "text" -> ParsedItemBuilder(
            accuracy = Accuracy.LOWEST,
            hint = InternalHint.USERNAME,
            reason = "type",
        )

        "password" -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.PASSWORD,
            reason = "type",
        )

        "totp",
        "twofa",
        "2fa",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.OTP_CODE,
            reason = "type",
        )

        "expdate" -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            reason = "type",
        )

        "cc-number",
        "cc_number",
        "credit-card-number",
        "credit_card_number",
        "card-number",
        "card_number",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_NUMBER,
            reason = "type",
        )

        "cc-csc",
        "cc_csc",
        "cc-cvv",
        "cc_cvv",
        "cvv",
        "cvc",
        "security-code",
        "security_code",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            reason = "type",
        )

        "cc-exp-month",
        "cc_exp_month",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_MONTH,
            reason = "type",
        )

        "cc-exp-year",
        "cc_exp_year",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_YEAR,
            reason = "type",
        )

        "cc-exp",
        "cc_exp",
        "cc-exp-date",
        "cc_exp_date",
        "credit-card-expiration",
        "credit_card_expiration",
        "expiry",
        "expiration",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            reason = "type",
        )

        else -> null
    }.let { listOfNotNull(it) }

    private fun extractOfId(
        value: String,
    ): List<ParsedItemBuilder> = kotlin.run {
        val id = value.lowercase(Locale.ENGLISH).replace('-', '_')
        when {
            formPurposeEnabled && AutofillFormPurposePolicy.isNewPasswordLabel(value) ->
                ParsedItemBuilder(Accuracy.HIGH, InternalHint.NEW_PASSWORD, reason = "new-password-id")

            "email" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.EMAIL_ADDRESS,
                reason = "id",
            )

            AutofillDetectionPolicy.matchesPhoneFieldName(id) -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.PHONE_NUMBER,
                reason = "id",
            )

            "username" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.USERNAME,
                reason = "id",
            )

            "password" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.PASSWORD,
                reason = "id",
            )

            "cc_number" in id || "ccnum" in id || "card_number" in id || "cardnumber" in id ||
                "card_no" in id || "cardno" in id || "credit_card_number" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_NUMBER,
                reason = "id",
            )

            "cardholder" in id || "holder_name" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_HOLDER_NAME,
                reason = "id",
            )

            "cc_name" in id || "card_name" in id || "name_on_card" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_HOLDER_NAME,
                reason = "id",
            )

            "cc_csc" in id || "cc_cvv" in id || "cvv" in id || "cvc" in id ||
                "security_code" in id || "securitycode" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
                reason = "id",
            )

            "exp_month" in id || "expiry_month" in id || "expiration_month" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_EXPIRATION_MONTH,
                reason = "id",
            )

            "exp_year" in id || "expiry_year" in id || "expiration_year" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_EXPIRATION_YEAR,
                reason = "id",
            )

            "cc_exp" in id || "exp_date" in id || "expiry" in id || "expiration" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
                reason = "id",
            )

            "passport" in id || "license" in id || "document" in id || "identity" in id || "ssn" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.IDENTITY_NUMBER,
                reason = "id",
            )

            "first_name" in id || "given_name" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.PERSON_FIRST_NAME,
                reason = "id",
            )

            "last_name" in id || "family_name" in id || "surname" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.PERSON_LAST_NAME,
                reason = "id",
            )

            "street" in id || "address" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.POSTAL_ADDRESS,
                reason = "id",
            )

            "city" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.ADDRESS_CITY,
                reason = "id",
            )

            "state" in id || "province" in id || "region" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.ADDRESS_REGION,
                reason = "id",
            )

            "country" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.ADDRESS_COUNTRY,
                reason = "id",
            )

            "company" in id || "organization" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.COMPANY_NAME,
                reason = "id",
            )

            "totp" in id || "twofa" in id || "2fa" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.OTP_CODE,
                reason = "id",
            )

            else -> null
        }.let { listOfNotNull(it) }
    }

    private fun extractOfLabel(
        value: String,
    ): List<ParsedItemBuilder> {
        val hint = value.lowercase(Locale.ENGLISH).trim()
        if (hint.isBlank()) {
            return emptyList()
        }

        val out = when {
            AutofillDetectionPolicy.matchesOtpField(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.HIGH,
                    hint = InternalHint.OTP_CODE,
                    reason = "label:$hint",
                )

            formPurposeEnabled && AutofillFormPurposePolicy.isNewPasswordLabel(hint) ->
                ParsedItemBuilder(Accuracy.HIGH, InternalHint.NEW_PASSWORD, reason = "new-password-label")

            // Compound labels such as "account password" describe a password,
            // even when a nonstandard form exposes it as plain text.
            AutofillDetectionPolicy.matchesPasswordField(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PASSWORD,
                    reason = "label:$hint",
                )

            isMixedLoginIdentifierLabel(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.USERNAME,
                    reason = "mixed-login-label",
                )

            autofillLabelEmailTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.EMAIL_ADDRESS,
                    reason = "label:$hint",
                )

            AutofillDetectionPolicy.matchesPhoneFieldName(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PHONE_NUMBER,
                    reason = "label:$hint",
                )

            AutofillDetectionPolicy.matchesUsernameLabel(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.USERNAME,
                    reason = "label:$hint",
                )

            autofillLabel2faTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.OTP_CODE,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardNumberTranslations.any { it.matches(hint) } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_NUMBER,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardSecurityCodeTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardExpirationTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardHolderTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_HOLDER_NAME,
                    reason = "label:$hint",
                )

            autofillLabelPersonFirstNameTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PERSON_FIRST_NAME,
                    reason = "label:$hint",
                )

            autofillLabelPersonLastNameTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PERSON_LAST_NAME,
                    reason = "label:$hint",
                )

            autofillLabelPostalAddressTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.POSTAL_ADDRESS,
                    reason = "label:$hint",
                )

            autofillLabelIdentityNumberTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.IDENTITY_NUMBER,
                    reason = "label:$hint",
                )

            else -> null
        }
        return listOfNotNull(out)
    }

    private fun isMixedLoginIdentifierLabel(label: String): Boolean {
        // A single "account email" is still an email field. Only alternatives
        // between different identifier types describe a general login account.
        val alternatives = label.split(LOGIN_ALTERNATIVES_REGEX)
        if (alternatives.size < 2) return false
        return alternatives.mapNotNull { option ->
            when {
                autofillLabelEmailTranslations.any { it in option } -> InternalHint.EMAIL_ADDRESS
                AutofillDetectionPolicy.matchesPhoneFieldName(option) -> InternalHint.PHONE_NUMBER
                AutofillDetectionPolicy.matchesUsernameLabel(option) -> InternalHint.USERNAME
                else -> null
            }
        }.distinct().size > 1
    }

    private fun parseNodeByAndroidInput(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> {
        val out = mutableListOf<ParsedItemBuilder>()

        if (node.idType.orEmpty().equals("id", ignoreCase = true)) {
            val idEntry = node.idEntry.orEmpty()
            if (
                idEntry.contains("url", ignoreCase = true) ||
                idEntry.contentEquals(other = "location_bar_edit_text", ignoreCase = true)
            ) {
                out += ParsedItemBuilder(
                    accuracy = Accuracy.HIGHEST,
                    hint = InternalHint.OFF,
                    reason = "url-bar",
                )
                return out
            }
        }

        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.importantForAutofill
        } else {
            View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        if (importance == View.IMPORTANT_FOR_AUTOFILL_NO) {
            out += ParsedItemBuilder(
                accuracy = Accuracy.HIGHEST,
                hint = InternalHint.OFF,
                reason = "important-for-autofill-no",
            )
            return out
        }

        val inputType = node.inputType
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> {
                when {
                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.HIGH,
                            hint = InternalHint.EMAIL_ADDRESS,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.LOW,
                            hint = InternalHint.PERSON_NAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_NORMAL,
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                    ) -> {
                        // 对齐 bitwarden：纯 text 变体不再无条件 fallback 为 USERNAME:LOWEST
                        // （bitwarden 的 toAutofillView 对纯 text 返回 Unused）。
                        // 仅当 idEntry / idType 含 username 术语时才产出 USERNAME（由
                        // extractOfId/extractOfType 按术语定 MEDIUM 精度），避免把
                        // 搜索框/备注等纯文本框误判为登录账号（QQ 搜索框误弹修复）。
                        // WEB_EDIT_TEXT 变体（WebView 内可编辑文本）保留 LOWEST fallback，
                        // 因 WebView 登录框常用该变体且无标准 hint。
                        if (inputIsVariationType(inputType, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
                            out += ParsedItemBuilder(
                                accuracy = Accuracy.LOWEST,
                                hint = InternalHint.USERNAME,
                            )
                        }
                        extractOfType(node.idType.orEmpty()).let(out::addAll)
                        extractOfId(node.idEntry.orEmpty()).let(out::addAll)
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    ) -> {
                        // 可见密码变体一定是密码框（如"显示密码"切换），
                        // 原逻辑要求同节点已存在 username 才判定为 password，
                        // 但账号/密码通常是独立节点，导致密码框被误判为 username。
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.LOW,
                            hint = InternalHint.PASSWORD,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.HIGH,
                            hint = InternalHint.PASSWORD,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
                        InputType.TYPE_TEXT_VARIATION_FILTER,
                        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                        InputType.TYPE_TEXT_VARIATION_PHONETIC,
                        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                        InputType.TYPE_TEXT_VARIATION_URI,
                    ) -> {
                    }

                    else -> {
                    }
                }
            }

            InputType.TYPE_CLASS_NUMBER -> {
                when {
                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_NUMBER_VARIATION_NORMAL,
                    ) -> {
                        extractOfType(node.idType.orEmpty()).let(out::addAll)
                        extractOfId(node.idEntry.orEmpty()).let(out::addAll)
                        out += ParsedItemBuilder(
                            accuracy = AutofillDetectionPolicy.genericNumberFallbackAccuracy(),
                            hint = InternalHint.USERNAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.LOW,
                            hint = InternalHint.PASSWORD,
                        )
                    }

                    else -> {
                    }
                }
            }
        }

        return out
    }

    private fun shouldIncludeHiddenCredentialNode(
        builders: List<ParsedItemBuilder>,
    ): Boolean {
        return builders.any { builder ->
            val mappedHint = mapHint(builder.hint) ?: return@any false
            AutofillDetectionPolicy.shouldIncludeHiddenCredential(
                hint = mappedHint,
                accuracy = builder.accuracy,
            )
        }
    }

    /**
     * 判断节点的文本信号（idEntry / label hint / autofillHints / html placeholder / className）
     * 是否含 password 术语。用于弱目标模式下把非标准 inputType 的密码框从 USERNAME:LOWEST
     * 提升为 PASSWORD（借鉴 bitwarden 的 updateForMissingPasswordFields）。
     */
    private fun nodeHasPasswordTermMatch(node: AssistStructure.ViewNode): Boolean {
        val candidates = buildList {
            node.idEntry?.let { add(it) }
            node.hint?.toString()?.let { add(it) }
            node.autofillHints?.forEach { add(it) }
            node.htmlInfo?.attributes?.forEach { attr ->
                val k = attr.first
                if (k.equals("placeholder", ignoreCase = true) ||
                    k.equals("name", ignoreCase = true) ||
                    k.equals("id", ignoreCase = true)
                ) {
                    attr.second?.let { add(it) }
                }
            }
            node.className?.let { add(it) }
        }
        return candidates.any { value ->
            autofillLabelPasswordTranslations.any { term -> value.contains(term, ignoreCase = true) }
        }
    }

    /**
     * 判断节点的文本信号是否含 login 术语（username/login/账号/用户名/password/密码 等）。
     * 用于弱目标模式下的「login 上下文门」：无密码框时，若屏幕上任意节点含 login 术语，
     * 放行低精度账号字段（对齐 bitwarden「有 Login 字段就 Fillable」门槛）；
     * 纯搜索/备注场景无 login 术语，不放行，缓解误弹。
     */
    private fun nodeHasLoginTermMatch(node: AssistStructure.ViewNode): Boolean {
        val candidates = buildList {
            node.idEntry?.let { add(it) }
            node.hint?.toString()?.let { add(it) }
            node.autofillHints?.forEach { add(it) }
            node.htmlInfo?.attributes?.forEach { attr ->
                val k = attr.first
                if (k.equals("placeholder", ignoreCase = true) ||
                    k.equals("name", ignoreCase = true) ||
                    k.equals("id", ignoreCase = true)
                ) {
                    attr.second?.let { add(it) }
                }
            }
        }
        return candidates.any { value ->
            AutofillDetectionPolicy.matchesExplicitAccountField(value) ||
                AutofillDetectionPolicy.matchesPhoneFieldName(value) ||
                autofillLabelLoginTranslations.any { term -> value.contains(term, ignoreCase = true) }
        }
    }

    /**
     * 判断节点是否为搜索框。搜索框不应被当作登录凭据字段——否则在「页面存在密码框即整页按登录
     * 上下文处理」的策略下，聚焦搜索框也会弹出密码条目（Edge 访问 GitHub 时顶部搜索框误弹）。
     *
     * 命中任一信号即判为搜索框：
     * - HTML type=search
     * - HTML autocomplete 含 "search"
     * - HTML role 含 "search"
     * - HTML inputmode=search
     * - aria-label / placeholder / title / name / id / 节点 hint(label) 含搜索相关词
     *   （search / 搜索 / 查询 / 查找 / recherche / buscar / suche 等）
     */
    internal fun isSearchField(node: AssistStructure.ViewNode): Boolean {
        if (AutofillDetectionPolicy.matchesSearchField(node.hint.orEmpty()) ||
            AutofillDetectionPolicy.matchesSearchField(node.idEntry.orEmpty())
        ) return true
        val html = node.htmlInfo ?: return false
        val tag = html.tag?.lowercase(Locale.ENGLISH).orEmpty()
        if (tag != "input" && tag != "search" && tag != "textarea") return false

        val attrs = html.attributes
            ?.map { it.first.lowercase(Locale.ENGLISH) to (it.second ?: "") }
            ?.toMap()
            .orEmpty()

        val type = attrs["type"].orEmpty().lowercase(Locale.ENGLISH)
        val autocomplete = attrs["autocomplete"].orEmpty().lowercase(Locale.ENGLISH)
        val role = attrs["role"].orEmpty().lowercase(Locale.ENGLISH)
        val inputMode = attrs["inputmode"].orEmpty().lowercase(Locale.ENGLISH)

        if (type == "search") return true
        if ("search" in autocomplete) return true
        if ("search" in role) return true
        if (inputMode == "search") return true

        val searchTerms = listOf(
            "search", "搜索", "查询", "查找",
            "recherche", "buscar", "suche", "searchbox",
        )
        val textSignals = listOf(
            attrs["aria-label"].orEmpty(),
            attrs["placeholder"].orEmpty(),
            attrs["title"].orEmpty(),
            attrs["name"].orEmpty(),
            attrs["id"].orEmpty(),
            node.hint?.toString().orEmpty(),
        ).map { it.lowercase(Locale.ENGLISH) }
        if (textSignals.any { t -> searchTerms.any { term -> term in t } }) return true

        return false
    }

    /**
     * 弱目标模式下的密码字段提升（借鉴 bitwarden updateForMissingPasswordFields）：
     * 当候选里无 PASSWORD 时，把含 password 术语但被识别为其它 hint（通常是 USERNAME:LOWEST，
     * 因 inputType 非标准所致）的候选提升为 PASSWORD:LOW。提升后 hasPasswordInItems=true，
     * 低精度账号字段自然保留并弹窗；无 password 术语的（如 QQ 搜索框）不受影响，仍按原逻辑过滤。
     * 仅在 allowWeakTargets=true 时调用。
     */
    private fun promotePasswordTermCandidates(items: List<RawParsedItem>): List<RawParsedItem> {
        val hasPassword = items.any {
            it.hint == InternalHint.PASSWORD || it.hint == InternalHint.NEW_PASSWORD
        }
        if (hasPassword) return items
        val promotableHints = setOf(
            InternalHint.USERNAME, InternalHint.EMAIL_ADDRESS,
            InternalHint.PHONE_NUMBER, InternalHint.UNKNOWN,
        )
        val anyPromotable = items.any { it.hasPasswordTerm && it.hint in promotableHints }
        if (!anyPromotable) return items
        return items.map { item ->
            if (item.hasPasswordTerm && item.hint in promotableHints) {
                item.copy(hint = InternalHint.PASSWORD, accuracy = Accuracy.LOW)
            } else {
                item
            }
        }
    }

    private fun isEditableTextLikeNode(node: AssistStructure.ViewNode): Boolean {
        if (node.visibility != View.VISIBLE) return false
        if (node.autofillId == null) return false

        if (node.autofillType == View.AUTOFILL_TYPE_TEXT) return true
        if (node.inputType != 0) return true

        val className = node.className?.lowercase(Locale.ENGLISH).orEmpty()
        if (
            className.contains("edittext") ||
            className.contains("textinput") ||
            className.contains("textfield") ||
            className.contains("autocompletetextview")
        ) {
            return true
        }

        val htmlTag = node.htmlInfo?.tag?.lowercase(Locale.ENGLISH).orEmpty()
        if (htmlTag == "input" || htmlTag == "textarea") return true

        val htmlType = node.htmlInfo?.attributes
            ?.firstOrNull { it.first.equals("type", ignoreCase = true) }
            ?.second
            ?.lowercase(Locale.ENGLISH)
            .orEmpty()
        return htmlType == "text" ||
            htmlType == "email" ||
            htmlType == "tel" ||
            htmlType == "password"
    }

    private fun mapHint(hint: InternalHint): FieldHint? = when (hint) {
        InternalHint.USERNAME -> FieldHint.USERNAME
        InternalHint.PASSWORD -> FieldHint.PASSWORD
        InternalHint.NEW_PASSWORD -> FieldHint.NEW_PASSWORD
        InternalHint.EMAIL_ADDRESS -> FieldHint.EMAIL_ADDRESS
        InternalHint.PHONE_NUMBER -> FieldHint.PHONE_NUMBER
        InternalHint.CREDIT_CARD_NUMBER -> FieldHint.CREDIT_CARD_NUMBER
        InternalHint.CREDIT_CARD_EXPIRATION_DATE,
        InternalHint.CREDIT_CARD_EXPIRATION_DAY,
        -> FieldHint.CREDIT_CARD_EXPIRATION_DATE
        InternalHint.CREDIT_CARD_EXPIRATION_MONTH -> FieldHint.CREDIT_CARD_EXPIRATION_MONTH
        InternalHint.CREDIT_CARD_EXPIRATION_YEAR -> FieldHint.CREDIT_CARD_EXPIRATION_YEAR
        InternalHint.CREDIT_CARD_SECURITY_CODE -> FieldHint.CREDIT_CARD_SECURITY_CODE
        InternalHint.CREDIT_CARD_HOLDER_NAME -> FieldHint.CREDIT_CARD_HOLDER_NAME
        InternalHint.POSTAL_ADDRESS -> FieldHint.POSTAL_ADDRESS
        InternalHint.POSTAL_CODE -> FieldHint.POSTAL_CODE
        InternalHint.PERSON_NAME -> FieldHint.PERSON_NAME
        InternalHint.PERSON_FIRST_NAME -> FieldHint.PERSON_FIRST_NAME
        InternalHint.PERSON_LAST_NAME -> FieldHint.PERSON_LAST_NAME
        InternalHint.ADDRESS_CITY -> FieldHint.ADDRESS_CITY
        InternalHint.ADDRESS_REGION -> FieldHint.ADDRESS_REGION
        InternalHint.ADDRESS_COUNTRY -> FieldHint.ADDRESS_COUNTRY
        InternalHint.COMPANY_NAME -> FieldHint.COMPANY_NAME
        InternalHint.IDENTITY_NUMBER -> FieldHint.IDENTITY_NUMBER
        InternalHint.OTP_CODE -> FieldHint.OTP_CODE
        InternalHint.OFF -> null
        InternalHint.UNKNOWN -> FieldHint.UNKNOWN
    }

    private fun inputIsVariationType(inputType: Int, vararg type: Int): Boolean {
        type.forEach {
            if (inputType and InputType.TYPE_MASK_VARIATION == it) {
                return true
            }
        }
        return false
    }
}

