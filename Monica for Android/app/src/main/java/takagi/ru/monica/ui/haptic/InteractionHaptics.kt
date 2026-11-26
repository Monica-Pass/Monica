package takagi.ru.monica.ui.haptic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/** A submitted request is not proof that a device's physical motor responded. */
enum class InteractionHapticResult { REQUESTED, DISABLED, UNAVAILABLE }

internal interface InteractionHapticDevice {
    fun viewFeedback(strong: Boolean): Boolean
    fun vibrate(strong: Boolean): Boolean
}

internal fun dispatchInteractionHaptic(
    enabled: Boolean,
    strength: Int,
    device: InteractionHapticDevice,
): InteractionHapticResult {
    if (!enabled) return InteractionHapticResult.DISABLED
    val strong = strength.coerceIn(1, 2) == 2
    if (device.viewFeedback(strong)) return InteractionHapticResult.REQUESTED
    return if (device.vibrate(strong)) InteractionHapticResult.REQUESTED
    else InteractionHapticResult.UNAVAILABLE
}

/**
 * Follow ImageToolbox's app-controlled CONTEXT_CLICK/LONG_PRESS feedback, including
 * its public global-setting override. Do not change the user's system settings.
 * Use these broadly supported effects for slider steps too: TEXT_HANDLE_MOVE and
 * CLOCK_TICK can return true and still be discarded asynchronously as unsupported.
 * REQUESTED means submitted, not a physical motor receipt. Never double-submit.
 */
internal class AndroidInteractionHapticDevice(
    private val context: Context,
    private val view: View?,
) : InteractionHapticDevice {
    @Suppress("DEPRECATION") // Same public opt-in used by ImageToolbox; OEM policy can still restrict it.
    override fun viewFeedback(strong: Boolean): Boolean {
        val target = view ?: return false
        if (!target.isAttachedToWindow || !target.isShown) return false
        val previous = target.isHapticFeedbackEnabled
        return try {
            target.isHapticFeedbackEnabled = true
            val constant = if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CONTEXT_CLICK
            target.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        } catch (_: RuntimeException) {
            false
        } finally {
            target.isHapticFeedbackEnabled = previous
        }
    }

    override fun vibrate(strong: Boolean): Boolean {
        // Only try this when the host rejected the request; Android may still apply device policy.
        if (view?.isAttachedToWindow != true || !view.isShown) return false
        return runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } ?: return false
            if (!vibrator.hasVibrator()) return false
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(if (strong) 20 else 10,
                    if (vibrator.hasAmplitudeControl()) { if (strong) 110 else 50 }
                    else VibrationEffect.DEFAULT_AMPLITUDE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            true
        }.getOrDefault(false)
    }
}
