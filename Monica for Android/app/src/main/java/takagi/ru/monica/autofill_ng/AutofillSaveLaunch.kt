package takagi.ru.monica.autofill_ng

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Only the system SaveCallback or our visible compatibility window holds this capability. */
internal object AutofillSaveLaunch {
    const val EXTRA_REQUEST_ID = "monica_autofill_save_request_id"
    const val EXTRA_COMPATIBILITY_MODE = "monica_autofill_save_compatibility_mode"

    fun pendingIntent(context: Context, saveIntent: Intent, requestId: String, compatibilityMode: Boolean = false): PendingIntent {
        require(saveIntent.component == ComponentName(context, AutofillSaveTransparentActivity::class.java))
        val launch = Intent(saveIntent).apply {
            // A submitted login activity may already be finishing. Do not depend
            // on it providing a usable task or on the ROM adding NEW_TASK for us.
            if (compatibilityMode) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Extras do not participate in PendingIntent identity. Keep simultaneous
            // save requests separate without using credential values as identity.
            action = "${context.packageName}.autofill.SAVE.$requestId"
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_COMPATIBILITY_MODE, compatibilityMode)
        }
        val options = if (compatibilityMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                // Grant only this explicit, immutable, one-use capability handed
                // to Autofill. This does not override a ROM's background UI policy.
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        } else null
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            options,
        )
    }
}
