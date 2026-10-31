package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.*
import takagi.ru.monica.autofill_ng.builder.FillResponseBuilderNg
import takagi.ru.monica.autofill_ng.model.AutofillRequest
import takagi.ru.monica.autofill_ng.model.FilledData
import takagi.ru.monica.autofill_ng.parser.AutofillParserNg

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class AutofillWebSaveRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun saveInfo(web: Boolean, newPassword: Boolean, enabled: Boolean = true,
                         compat: Boolean = false, accountOnly: Boolean = false): SaveInfo? {
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val hints = mutableListOf(FieldHint.USERNAME)
        if (!accountOnly) {
            hints += if (newPassword) FieldHint.NEW_PASSWORD else FieldHint.PASSWORD
            if (newPassword) hints += FieldHint.NEW_PASSWORD
        }
        val fields = hints.map { ParsedItem(EditText(context).autofillId, it, Accuracy.HIGH) }
        val request = AutofillParserNg().parse("fixture.browser", if (web) "https://example.test" else null,
            fields, null, isCompatMode = compat, saveEnabled = enabled) as AutofillRequest.Fillable
        val filled = FilledData(emptyList(), emptyList(), request.partition, request.uri, null, false)
        val response = requireNotNull(FillResponseBuilderNg(context).build(request, filled,
            passwordSuggestionEnabled = false, requireAuthentication = false))
        return FillResponse::class.java.getMethod("getSaveInfo").invoke(response) as SaveInfo?
    }

    private fun flags(info: SaveInfo) = SaveInfo::class.java.getMethod("getFlags").invoke(info) as Int

    @Test fun webLoginAndRegistrationTrackTheSubmittedFormDisappearing() {
        for (newPassword in listOf(false, true)) {
            assertEquals(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE,
                flags(requireNotNull(saveInfo(web = true, newPassword = newPassword))))
        }
    }

    @Test fun nativeCommitDisabledSavingCompatAndDelayedAccountStepKeepTheirPolicies() {
        assertEquals(0, flags(requireNotNull(saveInfo(web = false, newPassword = false))))
        assertNull(saveInfo(web = true, newPassword = true, enabled = false))
        assertNull(saveInfo(web = true, newPassword = true, compat = true))
        assertEquals(SaveInfo.FLAG_DELAY_SAVE,
            flags(requireNotNull(saveInfo(web = true, newPassword = false, accountOnly = true))))
    }

    @Test fun saveReadsOnlyTheRequestedIdEvenAfterItsParentBecameInvisible() {
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val target = ReflectionHelpers.newInstance(AssistStructure.ViewNode::class.java)
        val id = EditText(context).autofillId
        ReflectionHelpers.setField(target, "mAutofillId", id)
        ReflectionHelpers.setField(target, "mAutofillValue", AutofillValue.forText(" synthetic password "))
        val hidden = ReflectionHelpers.newInstance(AssistStructure.ViewNode::class.java)
        ReflectionHelpers.setField(hidden, "mFlags", View.GONE)
        ReflectionHelpers.setField(hidden, "mChildren", arrayOf(target))
        val root = ReflectionHelpers.newInstance(AssistStructure.ViewNode::class.java)
        ReflectionHelpers.setField(root, "mChildren", arrayOf(hidden))
        val window = ReflectionHelpers.newInstance(AssistStructure.WindowNode::class.java)
        ReflectionHelpers.setField(window, "mRoot", root)
        val structure = AssistStructure()
        ReflectionHelpers.setField(structure, "mWindowNodes", arrayListOf(window))
        ReflectionHelpers.setField(structure, "mActivityComponent", ComponentName("fixture.browser", "fixture.browser.Login"))
        val service = Robolectric.buildService(MonicaAutofillServiceNg::class.java).get()
        val method = MonicaAutofillServiceNg::class.java.getDeclaredMethod("extractTextFromStructure",
            AssistStructure::class.java, AutofillId::class.java).apply { isAccessible = true }
        try {
            assertEquals(" synthetic password ", method.invoke(service, structure, id))
            assertNull(method.invoke(service, structure, EditText(context).autofillId))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(service, "scope").cancel()
        }
    }
}
