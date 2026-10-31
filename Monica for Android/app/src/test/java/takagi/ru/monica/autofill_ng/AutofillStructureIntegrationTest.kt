package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.text.InputType
import android.util.Pair
import android.view.View
import android.view.ViewStructure
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class AutofillStructureIntegrationTest {
    private val parser = EnhancedAutofillStructureParserV2()

    @Before fun installSignaturePermission() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    private fun node(hint: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT,
                     focused: Boolean = false, hints: Array<String>? = null,
                     html: Map<String, String>? = null, children: Array<AssistStructure.ViewNode>? = null,
                     domain: String? = null, htmlTag: String = "input"): AssistStructure.ViewNode {
        val node = ReflectionHelpers.newInstance(AssistStructure.ViewNode::class.java)
        fun set(name: String, value: Any?) = ReflectionHelpers.setField(node, name, value)
        set("mAutofillId", EditText(RuntimeEnvironment.getApplication()).autofillId)
        set("mAutofillType", View.AUTOFILL_TYPE_TEXT)
        set("mInputType", if (children != null) 0 else inputType)
        set("mClassName", if (domain != null) "android.webkit.WebView" else if (children != null) "android.widget.LinearLayout" else "android.widget.EditText")
        if (hint != null) {
            val text = Class.forName("android.app.assist.AssistStructure\$ViewNodeText").getDeclaredConstructor()
                .apply { isAccessible = true }.newInstance()
            ReflectionHelpers.setField(text,"mHint",hint)
            set("mText", text)
        }
        if (focused) set("mFlags", ReflectionHelpers.getStaticField<Int>(AssistStructure.ViewNode::class.java,"FLAGS_FOCUSED"))
        if (hints != null) set("mAutofillHints",hints)
        if (children != null) set("mChildren",children)
        if (domain != null) { set("mWebDomain",domain); set("mWebScheme","https") }
        if (html != null) set("mHtmlInfo",object: ViewStructure.HtmlInfo() {
            override fun getTag() = htmlTag
            override fun getAttributes(): MutableList<Pair<String,String>> = html.map { Pair(it.key,it.value) }.toMutableList()
        })
        return node
    }

    private fun structure(vararg roots: AssistStructure.ViewNode): AssistStructure {
        val structure = AssistStructure()
        ReflectionHelpers.setField(structure,"mActivityComponent",ComponentName("audit.enterprise","audit.enterprise.Login"))
        val windows = roots.map { root ->
            ReflectionHelpers.newInstance(AssistStructure.WindowNode::class.java).also {
                ReflectionHelpers.setField(it,"mRoot",root)
                ReflectionHelpers.setField(it,"mTitle","audit.enterprise/Login")
            }
        }
        ReflectionHelpers.setField(structure,"mWindowNodes",ArrayList(windows))
        return structure
    }

    private fun parse(vararg roots: AssistStructure.ViewNode) = parser.parse(structure(*roots))

    @Test fun mixedLoginIdentifierAndPasswordRemainAPairFromEitherFocus() {
        for (focusPassword in listOf(false, true)) {
            val account = node(html=mapOf("id" to "loginInput", "type" to "text",
                "placeholder" to "用户名、邮箱或手机号"), focused=!focusPassword)
            val password = node(html=mapOf("id" to "passwordInput", "type" to "password",
                "placeholder" to "请输入密码"), focused=focusPassword)
            val parsed = parse(node(domain="account.example.test",children=arrayOf(account,password)))
            assertEquals("focusPassword=$focusPassword", mapOf(account.autofillId to FieldHint.USERNAME,
                password.autofillId to FieldHint.PASSWORD), parsed.items.associate { it.id to it.hint })
        }
    }

    @Test fun genericWebViewInputClassesDoNotSplitTheLoginPair() {
        for (focusPassword in listOf(false, true)) {
            val account = node(html=mapOf("type" to "text", "placeholder" to "Username"), focused=!focusPassword)
            val password = node(html=mapOf("type" to "password"), focused=focusPassword)
            listOf(account, password).forEach {
                ReflectionHelpers.setField(it,"mClassName","android.webkit.WebView")
            }
            val parsed = parse(node(domain="account.example.test",children=arrayOf(account,password)))
            assertEquals("focusPassword=$focusPassword",setOf(account.autofillId,password.autofillId),parsed.items.map { it.id }.toSet())
        }
    }

    @Test fun mixedAccountLabelsWorkInNativeAndHtmlFields() {
        for (label in listOf("用户名、邮箱或手机号", "Username or email", "Email / phone", "用戶名或電郵")) {
            for (htmlField in listOf(false, true)) {
                val account = if (htmlField) node(html=mapOf("type" to "text", "placeholder" to label),focused=true)
                    else node(hint=label,focused=true)
                val password = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                val parsed = parse(node(children=arrayOf(account,password)))
                assertEquals("$label html=$htmlField",FieldHint.USERNAME,parsed.items.single { it.id == account.autofillId }.hint)
            }
        }
    }

    @Test fun dedicatedContactFieldsAndExplicitHtmlSemanticsKeepTheirRoles() {
        for ((label, role) in listOf("Account email" to FieldHint.EMAIL_ADDRESS,
            "Email address" to FieldHint.EMAIL_ADDRESS, "手机号" to FieldHint.PHONE_NUMBER,
            "Username or email password" to FieldHint.PASSWORD, "邮箱或手机验证码" to FieldHint.OTP_CODE)) {
            assertEquals(label,role,parse(node(hint=label,focused=true)).items.single().hint)
        }
        val explicit = node(html=mapOf("type" to "email", "autocomplete" to "email",
            "placeholder" to "Username or email"),focused=true)
        assertEquals(FieldHint.EMAIL_ADDRESS,parse(node(domain="account.example.test",children=arrayOf(explicit))).items.single().hint)
    }

    @Test fun virtualWebViewInputsKeepHtmlFormAndOriginBoundaries() {
        for (otherOrigin in listOf(false, true)) {
            val account = node(html=mapOf("type" to "text", "placeholder" to "Username"),focused=true)
            val password = node(html=mapOf("type" to "password"))
            listOf(account,password).forEach { ReflectionHelpers.setField(it,"mClassName","android.webkit.WebView") }
            val roots = if (otherOrigin) {
                ReflectionHelpers.setField(password,"mWebDomain","other.example.test")
                arrayOf(account,password)
            } else arrayOf(node(html=mapOf("id" to "login"),htmlTag="form",children=arrayOf(account)),
                node(html=mapOf("id" to "other"),htmlTag="form",children=arrayOf(password)))
            val parsed = parse(node(domain="account.example.test",children=roots))
            assertEquals(listOf(account.autofillId),parsed.items.map { it.id })
            assertFalse(password.autofillId in parsed.scopeAutofillIds)
        }
    }

    @Test fun longFormTraversalCooperatesWithCancellation() {
        val root=node(children=Array(200) { node(hint="Username") })
        var checks=0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            parser.parse(structure(root),checkCancelled={ if (++checks == 2) throw java.util.concurrent.CancellationException() })
        }
        assertEquals(2,checks)
    }

    @Test fun standardLoginRemainsRecognized() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val pass = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(setOf(user.autofillId,pass.autofillId),parse(node(children=arrayOf(user,pass))).items.map { it.id }.toSet())
    }

    @Test fun nestedWebViewKeepsItsReportedAncestorOriginForBoundSuggestions() {
        val user = node(hints = arrayOf(View.AUTOFILL_HINT_USERNAME), focused = true)
        val inner = node(children = arrayOf(user))
        ReflectionHelpers.setField(inner, "mClassName", "android.webkit.WebView")
        val parsed = parse(node(domain = "accounts.example.com", children = arrayOf(inner)))
        assertEquals("accounts.example.com", parsed.webDomain)
        val bound = takagi.ru.monica.data.PasswordEntry(id = 167, title = "Bound account",
            website = "https://accounts.example.com/login", username = "synthetic", password = "synthetic")
        assertEquals(listOf(167L), BitwardenLikeAutofillMatcherNg().match(listOf(bound),
            parsed.applicationId.orEmpty(), parsed.webDomain).map { it.id })
    }

    @Test fun originlessSiblingWebViewCannotBorrowAnotherSitesOrigin() {
        val user = node(hints = arrayOf(View.AUTOFILL_HINT_USERNAME), focused = true)
        val unknown = node(children = arrayOf(user))
        ReflectionHelpers.setField(unknown, "mClassName", "android.webkit.WebView")
        val parsed = parse(node(children = arrayOf(
            node(domain = "accounts.example.com", children = arrayOf(node(hint = "Password"))), unknown)))
        assertNull(parsed.webDomain)
        assertEquals(listOf(user.autofillId), parsed.items.map { it.id })
    }

    @Test fun dynamicCardExpirySelectIdsKeepSeparateMonthAndYearRoles() {
        val number = node(hints = arrayOf("cc-number"))
        val month = node(html = mapOf("type" to "select-one", "id" to "cc-expiry-month"), focused = true)
        val year = node(html = mapOf("type" to "select-one", "id" to "cc-expiry-year"))
        for (field in listOf(month, year)) ReflectionHelpers.setField(field, "mAutofillType", View.AUTOFILL_TYPE_LIST)
        val parsed = parse(node(domain = "shop.example.com", children = arrayOf(number, month, year)))
        assertEquals(FieldHint.CREDIT_CARD_EXPIRATION_MONTH, parsed.items.single { it.id == month.autofillId }.hint)
        assertEquals(FieldHint.CREDIT_CARD_EXPIRATION_YEAR, parsed.items.single { it.id == year.autofillId }.hint)
    }

    @Test fun enterpriseIdentifiersAreRecognizedWithoutStandardHints() {
        listOf("请输入工号","学号","員工編號","账号","帳號").forEach { hint ->
            val field = node(hint=hint,focused=true)
            assertEquals(hint,listOf(FieldHint.USERNAME),parse(node(children=arrayOf(field))).items.map { it.hint })
        }
    }

    @Test fun browserLoopbackPagesKeepTheirOriginForMatching() {
        for (domain in listOf("127.0.0.1", "localhost")) {
            val field = node(hints = arrayOf(View.AUTOFILL_HINT_USERNAME), focused = true)
            val input = structure(node(domain = domain, children = arrayOf(field)))
            ReflectionHelpers.setField(input, "mActivityComponent", ComponentName("com.android.chrome", "com.android.chrome.Main"))
            val parsed = parser.parse(input)
            assertEquals(domain, parsed.webDomain)
            assertTrue(parsed.webView)
        }
    }

    @Test fun nativeHybridLoopbackUiRetainsPackageMatchingCompatibility() {
        val field = node(hints = arrayOf(View.AUTOFILL_HINT_USERNAME), focused = true)
        val parsed = parse(node(domain = "localhost", children = arrayOf(field)))
        assertNull(parsed.webDomain)
        assertFalse(parsed.webView)
        assertEquals(listOf(field.autofillId), parsed.items.map { it.id })
    }

    @Test fun nativeLoginIsNotDroppedBecauseThePageHasAnUnrelatedWebView() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val pass = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val web = node(domain="help.example.com",children=emptyArray())
        val parsed = parse(node(children=arrayOf(user,pass,web)))
        assertEquals(setOf(user.autofillId,pass.autofillId),parsed.items.map { it.id }.toSet())
        assertNull(parsed.webDomain)
    }

    @Test fun focusedDialogIsPreferredToTheFormBehindIt() {
        val background = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val active = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,focused=true)
        val parsed = parse(node(children=arrayOf(background)),node(children=arrayOf(active)))
        assertEquals(listOf(active.autofillId),parsed.items.map { it.id })
    }

    @Test fun phoneVerificationCodeIsNeverAnAccount() {
        val otp = node(hint="手机验证码",inputType=InputType.TYPE_CLASS_NUMBER,focused=true)
        val parsed = parse(node(children=arrayOf(otp)))
        assertEquals(listOf(FieldHint.OTP_CODE),parsed.items.map { it.hint })
    }

    @Test fun accountPasswordLabelsRemainPasswordTargets() {
        listOf("账号密码", "登录密码", "Account password", "Email password", "Mot de passe", "Passe").forEach { hint ->
            val password = node(hint=hint, focused=true)
            assertEquals(hint, listOf(FieldHint.PASSWORD), parse(password).items.map { it.hint })
        }
    }

    @Test fun compatibilityModeDoesNotPromoteOneTimePasswordsToSavedPasswords() {
        listOf("One-time password", "动态密码", "一次性密码").forEach { hint ->
            val otp = node(hint=hint, focused=true)
            val input = structure(otp)
            listOf(false, true).forEach { weak ->
                assertEquals("$hint weak=$weak", listOf(FieldHint.OTP_CODE),
                    parser.parse(input, allowWeakTargets=weak).items.map { it.hint })
            }
        }
    }

    @Test fun nativeSearchIsExcludedEvenBesideAPasswordField() {
        val search = node(hint="搜索工号",inputType=InputType.TYPE_CLASS_NUMBER,focused=true)
        val pass = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(parse(node(children=arrayOf(search,pass))).items.any { it.id == search.autofillId })
    }

    @Test fun htmlAutocompleteAcceptsSectionTokens() {
        val pass = node(html=mapOf("type" to "text","autocomplete" to "section-login current-password"),focused=true)
        val web = node(domain="login.example.com",children=arrayOf(pass))
        assertEquals(listOf(FieldHint.PASSWORD),parse(node(children=arrayOf(web))).items.map { it.hint })
    }

    @Test fun focusedWebViewKeepsItsOwnOriginAndFields() {
        val first = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val second = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val firstWeb = node(domain="first.example.com",children=arrayOf(first))
        val secondWeb = node(domain="second.example.com",children=arrayOf(second))
        ReflectionHelpers.setField(firstWeb,"mId",101)
        ReflectionHelpers.setField(secondWeb,"mId",102)
        val parsed = parse(node(children=arrayOf(firstWeb,secondWeb)))
        assertEquals("second.example.com",parsed.webDomain)
        assertEquals(listOf(second.autofillId),parsed.items.map { it.id })
    }

    @Test fun webViewsWithoutAndroidViewIdsStillHaveSeparateScopes() {
        val first = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val second = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val parsed = parse(node(children=arrayOf(
            node(domain="first.example.com",children=arrayOf(first)),
            node(domain="second.example.com",children=arrayOf(second)))))
        assertEquals("second.example.com",parsed.webDomain)
        assertEquals(listOf(second.autofillId),parsed.items.map { it.id })
        assertFalse(first.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun focusedCrossOriginSubtreeCannotReceiveTheOuterSitesFields() {
        val outer = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val inner = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val frame = node(children=arrayOf(inner))
        ReflectionHelpers.setField(frame,"mWebDomain","sso.example.org")
        ReflectionHelpers.setField(frame,"mWebScheme","https")
        val parsed = parse(node(domain="outer.example.com",children=arrayOf(outer,frame)))
        assertEquals("sso.example.org",parsed.webDomain)
        assertEquals(listOf(inner.autofillId),parsed.items.map { it.id })
        assertFalse(outer.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun parserSupportsPhoneAndNumericStudentAccountSteps() {
        listOf("手机号" to InputType.TYPE_CLASS_PHONE,"学号" to InputType.TYPE_CLASS_NUMBER).forEach { (hint,type) ->
            val parsed = parse(node(hint=hint,inputType=type,focused=true))
            assertTrue(hint,parsed.items.any { it.hint == FieldHint.USERNAME || it.hint == FieldHint.PHONE_NUMBER })
        }
    }

    @Test fun autocompleteOffRetainsTheExistingPreference() {
        val field = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            html=mapOf("type" to "password","autocomplete" to "off"),focused=true)
        val structure = structure(node(domain="login.example.com",children=arrayOf(field)))
        assertTrue(parser.parse(structure,respectAutofillOff=true).items.isEmpty())
        assertEquals(listOf(FieldHint.PASSWORD),parser.parse(structure,respectAutofillOff=false).items.map { it.hint })
    }

    @Test fun syntheticFocusFallbackDoesNotUndoSearchOrOtpExclusion() {
        val service = org.robolectric.Robolectric.buildService(MonicaAutofillServiceNg::class.java).get()
        val infer = MonicaAutofillServiceNg::class.java.getDeclaredMethod("inferFocusedFieldHint",
            AssistStructure.ViewNode::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        listOf("搜索工号","手机验证码").forEach { hint ->
            assertNull(hint,infer.invoke(service,node(hint=hint,inputType=InputType.TYPE_CLASS_NUMBER,focused=true),true))
        }
    }

    @Test fun syntheticFallbackCannotAddFieldsFromAnotherWindow() {
        val field = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val other = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,focused=true)
        val structure = structure(node(children=arrayOf(other)),node(children=arrayOf(field)))
        val parsed = parser.parse(structure)
        val service = org.robolectric.Robolectric.buildService(MonicaAutofillServiceNg::class.java).get()
        val method = MonicaAutofillServiceNg::class.java.getDeclaredMethod("buildFocusedSyntheticItems",
            AssistStructure::class.java,List::class.java,Boolean::class.javaPrimitiveType,Set::class.java).apply { isAccessible=true }
        val synthetic = method.invoke(service,structure,parsed.items,true,parsed.scopeAutofillIds) as List<*>
        assertTrue(synthetic.isEmpty())
    }

    @Test fun manualRequestCanSelectAnUnlabelledFocusedTextField() {
        val field = node(focused=true)
        val unrelated = node()
        val structure = structure(node(children=arrayOf(field,unrelated)))
        assertTrue(parser.parse(structure).items.isEmpty())
        val manual = parser.parse(structure,allowWeakTargets=true)
        assertEquals(listOf(field.autofillId),manual.items.map { it.id })
        assertEquals(listOf(FieldHint.USERNAME),manual.items.map { it.hint })
        assertTrue(parser.parse(structure,allowWeakTargets=true,requireExplicitWeakLoginSignal=true).items.isEmpty())
    }
    @Test fun browserComputedHintsRecognizeLoginAndOtpRoles() {
        mapOf("username" to FieldHint.USERNAME, "current-password" to FieldHint.PASSWORD,
            "newPassword" to FieldHint.NEW_PASSWORD, "one-time-code" to FieldHint.OTP_CODE,
            "2faAppOTPCode" to FieldHint.OTP_CODE, "smsOTPCode" to FieldHint.OTP_CODE,
            "emailOTPCode" to FieldHint.OTP_CODE).forEach { (hint, expected) ->
            val input = node(html=mapOf("computed-autofill-hints" to hint), focused=true)
            assertEquals(hint, listOf(expected), parse(node(domain="login.example.com", children=arrayOf(input))).items.map { it.hint })
        }
    }

    @Test fun htmlSectionNamesDoNotBecomeFieldRoles() {
        val input = node(html=mapOf("autocomplete" to "section-email current-password"), focused=true)
        assertEquals(listOf(FieldHint.PASSWORD), parse(input).items.map { it.hint })
    }

    @Test fun browserNativeHintArraysAlsoIgnoreSectionNames() {
        val input = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            hints=arrayOf("section-email", "current-password"),
            html=mapOf("ua-autofill-hints" to "section-email current-password"), focused=true)
        assertEquals(listOf(FieldHint.PASSWORD),
            parse(node(domain="example.test", children=arrayOf(input))).items.map { it.hint })
        val newPassword = node(hints=arrayOf("section-email", "new-password"), focused=true,
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(listOf(FieldHint.NEW_PASSWORD),
            parse(node(domain="example.test", children=arrayOf(newPassword))).items.map { it.hint })
    }

    @Test fun browserNewPasswordIsNotDowngradedByPasswordInputType() {
        val input = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            html=mapOf("autocomplete" to "section-signup new-password"), focused=true)
        assertEquals(listOf(FieldHint.NEW_PASSWORD), parse(input).items.map { it.hint })
    }

    @Test fun computedOtpOnAMaskedFieldIsNeverAnAccountOrSavedPassword() {
        val input = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            html=mapOf("computed-autofill-hints" to "smsOTPCode"), focused=true)
        for (weak in listOf(false,true)) assertEquals(listOf(FieldHint.OTP_CODE),
            parser.parse(structure(input), allowWeakTargets=weak).items.map { it.hint })
    }

    @Test fun computedHintsStillRespectOffAndSearchExclusion() {
        val off = node(html=mapOf("autocomplete" to "off", "computed-autofill-hints" to "password"), focused=true)
        assertTrue(parser.parse(structure(off), respectAutofillOff=true).items.isEmpty())
        assertEquals(listOf(FieldHint.PASSWORD), parser.parse(structure(off), respectAutofillOff=false).items.map { it.hint })
        val search = node(html=mapOf("type" to "search", "computed-autofill-hints" to "username"), focused=true)
        assertTrue(parse(search).items.isEmpty())
    }

    private fun form(id: String, vararg fields: AssistStructure.ViewNode) =
        node(html=mapOf("id" to id), htmlTag="form", children=arrayOf(*fields))

    @Test fun onlyTheFocusedHtmlFormContributesFillAndFallbackTargets() {
        val newsletter = node(hints=arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS))
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME), focused=true)
        val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val unrelated = node()
        val parsed = parse(node(domain="login.example.com", children=arrayOf(
            form("newsletter", newsletter), form("login", user, password), unrelated)))
        assertEquals(setOf(user.autofillId,password.autofillId), parsed.items.map { it.id }.toSet())
        assertFalse(newsletter.autofillId in parsed.scopeAutofillIds)
        assertFalse(unrelated.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun focusingTheOtherFormDropsTheLoginTargets() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val newsletter = node(hints=arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS), focused=true)
        val parsed = parse(node(domain="login.example.com", children=arrayOf(
            form("login", user,password), form("newsletter",newsletter))))
        assertEquals(listOf(newsletter.autofillId),parsed.items.map { it.id })
        assertFalse(password.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun htmlFormAttributeCanAssociateAnOutsideFieldWithItsForm() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME), focused=true, html=mapOf("form" to "login"))
        val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val newsletter = node(hints=arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS))
        val parsed = parse(node(domain="login.example.com",children=arrayOf(user,
            form("newsletter",newsletter), form("login",password))))
        assertEquals(setOf(user.autofillId,password.autofillId),parsed.items.map { it.id }.toSet())
    }

    @Test fun repeatedHtmlFormNamesDoNotCombineIndependentForms() {
        val first = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val second = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME), focused=true)
        val parsed = parse(node(domain="login.example.com",children=arrayOf(form("login",first),form("login",second))))
        assertEquals(listOf(second.autofillId),parsed.items.map { it.id })
    }

    @Test fun absentFormMarkupPreservesTheExistingWebViewScope() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME), focused=true)
        val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val parsed = parse(node(domain="login.example.com",children=arrayOf(user,password)))
        assertEquals(setOf(user.autofillId,password.autofillId),parsed.items.map { it.id }.toSet())
    }

    @Test fun exactHtmlRolesPreserveExistingContactAndWalletSupport() {
        mapOf("email" to FieldHint.EMAIL_ADDRESS, "organization" to FieldHint.COMPANY_NAME,
            "country" to FieldHint.ADDRESS_COUNTRY, "tel" to FieldHint.PHONE_NUMBER,
            "cc-number" to FieldHint.CREDIT_CARD_NUMBER, "given-name" to FieldHint.PERSON_FIRST_NAME,
            "street-address" to FieldHint.POSTAL_ADDRESS).forEach { (token, role) ->
            val field = node(html=mapOf("autocomplete" to "section-profile billing $token"), focused=true)
            assertEquals(token, listOf(role), parse(field).items.map { it.hint })
        }
    }

    @Test fun unknownAndAmbiguousHtmlHintsDoNotMatchBySubstring() {
        listOf("not-a-password", "email password", "section-password", "username nonsense").forEach { hint ->
            assertTrue(hint, parse(node(html=mapOf("computed-autofill-hints" to hint), focused=true)).items.isEmpty())
        }
    }

    @Test fun missingAndAmbiguousExplicitFormIdsCannotJoinOtherFields() {
        for (duplicate in listOf(false, true)) {
            val external = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME), focused=true, html=mapOf("form" to "login"))
            val otherExternal = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD), html=mapOf("form" to "login"))
            val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
            val forms = if (duplicate) arrayOf(form("login", password), form("login")) else arrayOf(form("other", password))
            val parsed = parse(node(domain="example.test", children=arrayOf(external, otherExternal, *forms)))
            assertEquals(listOf(external.autofillId), parsed.items.map { it.id })
            assertFalse(password.autofillId in parsed.scopeAutofillIds)
            assertFalse(otherExternal.autofillId in parsed.scopeAutofillIds)
        }
    }

    @Test fun explicitFormReferencesCannotCrossWebOrigins() {
        val external = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME), focused=true, html=mapOf("form" to "login"))
        val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val parsed = parse(node(domain="a.example.test", children=arrayOf(external,
            node(domain="b.example.test", children=arrayOf(form("login", password))))))
        assertEquals(listOf(external.autofillId), parsed.items.map { it.id })
        assertFalse(password.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun compatibilitySwitchesCanBeRolledBackIndependently() {
        val user = node(html=mapOf("computed-autofill-hints" to "username"), focused=true)
        val password = node(hints=arrayOf(View.AUTOFILL_HINT_PASSWORD))
        val input = structure(node(domain="example.test", children=arrayOf(form("login", user), form("other", password))))
        assertEquals(listOf(user.autofillId), parser.parse(input).items.map { it.id })
        assertTrue(EnhancedAutofillStructureParserV2(browserHintTokensEnabled=false).parse(input).items.isEmpty())
        assertEquals(setOf(user.autofillId, password.autofillId),
            EnhancedAutofillStructureParserV2(htmlFormScopesEnabled=false).parse(input).items.map { it.id }.toSet())
        assertEquals(listOf(password.autofillId),
            EnhancedAutofillStructureParserV2(false, false).parse(input).items.map { it.id })
    }

    @Test fun nativeChangePasswordLabelsKeepCurrentAndNewRolesSeparate() {
        val old = node(hint="当前密码", inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val new = node(hint="新密码", inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, focused=true)
        val confirmation = node(hint="Confirm password", inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val parsed = parse(node(children=arrayOf(old,new,confirmation)))
        assertEquals(listOf(FieldHint.PASSWORD,FieldHint.NEW_PASSWORD,FieldHint.NEW_PASSWORD),parsed.items.map { it.hint })
        assertEquals(AutofillFormPurpose.CHANGE_PASSWORD,parsed.formPurpose)
    }

    @Test fun explicitRegistrationFormPromotesGenericPasswordFields() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val password = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, focused=true)
        val parsed = parse(node(domain="example.test",children=arrayOf(form("register",user,password))))
        assertEquals(FieldHint.NEW_PASSWORD,parsed.items.first { it.id == password.autofillId }.hint)
        assertEquals(AutofillFormPurpose.SIGN_UP,parsed.formPurpose)
    }

    @Test fun adjacentRegistrationFormCannotReclassifyTheLogin() {
        val password = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, focused=true)
        val parsed = parse(node(domain="example.test",children=arrayOf(form("login",password),form("register",node(hint="Password")))))
        assertEquals(listOf(FieldHint.PASSWORD),parsed.items.map { it.hint })
        assertEquals(AutofillFormPurpose.PASSWORD_STEP,parsed.formPurpose)
    }

    @Test fun explicitCurrentPasswordPreventsRegistrationCueFromChangingItsRole() {
        val password = node(html=mapOf("autocomplete" to "current-password"),focused=true)
        val parsed = parse(node(domain="example.test",children=arrayOf(form("register",password))))
        assertEquals(listOf(FieldHint.PASSWORD),parsed.items.map { it.hint })
    }

    @Test fun ambiguousLoginAndRegistrationCuesKeepLegacyRoles() {
        val password = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, focused=true)
        val parsed = parse(node(domain="example.test",children=arrayOf(form("login-register",password))))
        assertEquals(listOf(FieldHint.PASSWORD),parsed.items.map { it.hint })
    }

    @Test fun formPurposeUsesFieldSemanticsForStepAndOtpPages() {
        assertEquals(AutofillFormPurpose.USERNAME_STEP,parse(node(hint="Username",focused=true)).formPurpose)
        assertEquals(AutofillFormPurpose.OTP,parse(node(html=mapOf("autocomplete" to "one-time-code"),focused=true)).formPurpose)
        assertEquals(AutofillFormPurpose.UNKNOWN,parse(node(html=mapOf("type" to "search"),focused=true)).formPurpose)
        assertEquals(AutofillFormPurpose.RESET_PASSWORD,parse(node(hint="New password",focused=true)).formPurpose)
    }

    @Test fun explicitProfileHintsKeepTheirMeaningAndBillingScope() {
        val email = node(html=mapOf("autocomplete" to "shipping email"), focused=true)
        val phone = node(html=mapOf("autocomplete" to "shipping tel-national"))
        val name = node(html=mapOf("autocomplete" to "shipping additional-name"))
        val other = node(html=mapOf("autocomplete" to "billing email"))
        val result = parse(node(domain="example.test", children=arrayOf(email,phone,name,other)))
        assertEquals(setOf(email.autofillId, phone.autofillId, name.autofillId), result.items.map { it.id }.toSet())
        assertEquals("email", result.items.single { it.id == email.autofillId }.semanticHint)
        assertEquals("tel-national", result.items.single { it.id == phone.autofillId }.semanticHint)
        assertEquals(EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS, result.items.single { it.id == email.autofillId }.hint)
    }

    @Test fun repeatedUnmarkedCardBlocksStaySeparate() {
        val first = node(html=mapOf("autocomplete" to "cc-name"))
        val firstNumber = node(html=mapOf("autocomplete" to "cc-number"))
        val second = node(html=mapOf("autocomplete" to "cc-name"),focused=true)
        val secondNumber = node(html=mapOf("autocomplete" to "cc-number"))
        val result = parse(node(domain="example.test",children=arrayOf(first,firstNumber,second,secondNumber)))
        assertEquals(setOf(second.autofillId,secondNumber.autofillId),result.items.map { it.id }.toSet())
    }

    @Test fun chromeTokenArraysPreserveContactAndSectionQualifiers() {
        val work = node(hints=arrayOf("work", "email"), focused=true)
        val workPhone = node(hints=arrayOf("work", "tel"))
        val home = node(hints=arrayOf("home", "email"))
        val unrelated = node(hints=arrayOf("section-my", "street-address"))
        val result = parse(node(domain="example.test", children=arrayOf(work,workPhone,home,unrelated)))
        assertEquals(setOf(work.autofillId,workPhone.autofillId),result.items.map { it.id }.toSet())
        assertEquals("work email",result.items.single { it.id==work.autofillId }.semanticHint)
        assertEquals("work phone",result.items.single { it.id==workPhone.autofillId }.semanticHint)
    }

}
