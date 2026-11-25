package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.PendingIntent
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import takagi.ru.monica.autofill_ng.builder.FillResponseBuilderNg
import takagi.ru.monica.autofill_ng.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutofillPickerRefreshContractTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun pickerArgs(hint: FieldHint, webView: Boolean = false, scheme: String? = null): AutofillPickerActivityV2.Args {
        val id = EditText(context).autofillId
        val data = AutofillView.Data(id, 1, true, null, null, hint)
        val view = when (hint) {
            FieldHint.PASSWORD, FieldHint.NEW_PASSWORD -> AutofillView.Login.Password(data)
            FieldHint.USERNAME -> AutofillView.Login.Username(data)
            else -> AutofillView.Field(hint, data)
        }
        val partition = AutofillPartition.Generic(listOf(view))
        val request = AutofillRequest.Fillable(emptyList(), null, 0, false, "com.example.app", partition,
            "androidapp://com.example.app", isWebView = webView, webScheme = scheme)
        val filled = FilledData(emptyList(), emptyList(), partition, request.uri, null, true)
        val builder = FillResponseBuilderNg(context)
        val method = builder.javaClass.getDeclaredMethod("buildManualEntryArtifacts",
            AutofillRequest.Fillable::class.java, FilledData::class.java, List::class.java).apply { isAccessible = true }
        val artifacts = method.invoke(builder, request, filled, listOf(id))
        val field = artifacts.javaClass.getDeclaredField("pendingIntent").apply { isAccessible = true }
        val intent = shadowOf(field.get(artifacts) as PendingIntent).savedIntent
        return requireNotNull(AutofillPickerActivityV2.readArgs(intent))
    }

    @Test fun builderEnablesRefreshForLoginEvenWithNoReadableFrameworkCandidates() {
        val args = pickerArgs(FieldHint.PASSWORD, webView = true, scheme = "file")
        assertTrue(args.refreshLoginSuggestions)
        assertTrue(args.isWebView)
        assertEquals("file", args.webScheme)
        assertEquals(0, args.suggestedPasswordIds?.size)
    }

    @Test fun newPasswordAndStructuredRequestsRetainAuthoritativeEmptyLists() {
        assertFalse(pickerArgs(FieldHint.NEW_PASSWORD).refreshLoginSuggestions)
        assertFalse(pickerArgs(FieldHint.CREDIT_CARD_NUMBER).refreshLoginSuggestions)
        assertTrue(pickerArgs(FieldHint.USERNAME).refreshLoginSuggestions)
    }

    @Test fun newContextChangesRequestIdentityAndRoundTripsThroughBundle() {
        val args = AutofillPickerActivityV2.Args(applicationId = "com.example.app", isWebView = true,
            webScheme = "file", refreshLoginSuggestions = true)
        assertTrue(args.isSameRequestAs(requireNotNull(AutofillPickerActivityV2.readArgs(AutofillPickerActivityV2.getIntent(context, args)))))
        assertFalse(args.isSameRequestAs(args.copy(isWebView = false)))
        assertFalse(args.isSameRequestAs(args.copy(refreshLoginSuggestions = false)))
    }
}
