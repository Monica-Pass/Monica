package takagi.ru.monica.ui.haptic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** A submitted request is not proof that a device's physical motor responded. */
enum class InteractionHapticResult { REQUESTED, DISABLED, SYSTEM_DISABLED, UNAVAILABLE }

internal interface InteractionHapticDevice {
    fun systemEnabled(): Boolean
    fun viewFeedback(strong: Boolean, tick: Boolean): Boolean
    fun fallback(strong: Boolean, tick: Boolean): Boolean
}

internal fun dispatchInteractionHaptic(
    enabled: Boolean,
    strength: Int,
    tick: Boolean,
    device: InteractionHapticDevice,
): InteractionHapticResult {
    if (!enabled) return InteractionHapticResult.DISABLED
    if (!device.systemEnabled()) return InteractionHapticResult.SYSTEM_DISABLED
    val strong = strength.coerceIn(1, 2) == 2
    if (device.viewFeedback(strong, tick)) return InteractionHapticResult.REQUESTED
    return if (device.fallback(strong, tick)) InteractionHapticResult.REQUESTED
    else InteractionHapticResult.UNAVAILABLE
}

/**
 * ImageToolbox maps strength 1 to CONTEXT_CLICK and 2 to LONG_PRESS.
 * Enable the host's feedback for this request, then restore its state. Unlike
 * ImageToolbox's legacy global-setting override, keep the system preference.
 * Some hosts/OEMs reject View feedback: use one touch-attributed motor request
 * only in that case, never after an accepted request or when feedback is off.
 */
internal class AndroidInteractionHapticDevice(
    private val context: Context,
    private val view: View?,
) : InteractionHapticDevice {
    override fun systemEnabled(): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(false)

    override fun viewFeedback(strong: Boolean, tick: Boolean): Boolean {
        val target = view ?: return false
        if (!target.isAttachedToWindow || !target.isShown) return false
        val previous = target.isHapticFeedbackEnabled
        return try {
            target.isHapticFeedbackEnabled = true
            val constant = when {
                tick && !strong && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
                tick -> HapticFeedbackConstants.CLOCK_TICK
                strong -> HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.CONTEXT_CLICK
            }
            target.performHapticFeedback(constant)
        } catch (_: RuntimeException) {
            false
        } finally {
            target.isHapticFeedbackEnabled = previous
        }
    }

    override fun fallback(strong: Boolean, tick: Boolean): Boolean {
        // Never vibrate for a detached/dismissed screen or after the system setting changed.
        if (view?.isAttachedToWindow != true || !view.isShown || !systemEnabled()) return false
        return runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } ?: return false
            if (!vibrator.hasVibrator()) return false
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(when {
                    strong && !tick -> VibrationEffect.EFFECT_HEAVY_CLICK
                    strong -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_TICK
                })
            } else {
                VibrationEffect.createOneShot(if (strong) 20 else 10, if (strong) 110 else 50)
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
