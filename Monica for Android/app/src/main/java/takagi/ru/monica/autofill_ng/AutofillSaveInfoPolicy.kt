package takagi.ru.monica.autofill_ng

import android.os.Build
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal object AutofillSaveInfoPolicy {
    fun build(fields: List<Pair<AutofillId, FieldHint>>, enabled: Boolean = true, compatMode: Boolean = false,
              saveOnAllViewsInvisible: Boolean = false): SaveInfo? {
        if (!enabled || compatMode) return null
        val accounts = fields.filter { it.second in setOf(FieldHint.USERNAME, FieldHint.EMAIL_ADDRESS, FieldHint.PHONE_NUMBER) }
            .map { it.first }.distinct()
        val new = fields.filter { it.second == FieldHint.NEW_PASSWORD }
        val passwords = new.ifEmpty { fields.filter { it.second == FieldHint.PASSWORD } }.map { it.first }.distinct()
        if (passwords.isEmpty()) {
            // Ask Android to retain the account step within this autofill session.
            // The service itself does not cache typed usernames across pages.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || accounts.isEmpty() || fields.any { it.second == FieldHint.OTP_CODE }) return null
            return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, accounts.toTypedArray())
                .setFlags(SaveInfo.FLAG_DELAY_SAVE).build()
        }
        return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, passwords.toTypedArray()).apply {
            // Web sign-in/sign-up commonly replaces the form without finishing
            // the browser Activity or explicitly committing its Autofill session.
            if (saveOnAllViewsInvisible) setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            val optional = accounts.filterNot { it in passwords }
            if (optional.isNotEmpty()) setOptionalIds(optional.toTypedArray())
        }.build()
    }
}
