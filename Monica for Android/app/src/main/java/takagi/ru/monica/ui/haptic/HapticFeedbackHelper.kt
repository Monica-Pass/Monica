package takagi.ru.monica.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.util.VibrationPatterns

/**
 * Phase 9: 触觉反馈工具类
 * 
 * 提供统一的触觉反馈接口，提升用户交互体验
 * 支持不同强度的震动反馈
 * 
 * ## 反馈类型
 * - **轻量点击**: 按钮、开关、选择
 * - **长按**: 长按操作确认
 * - **成功**: 操作成功提示
 * - **警告**: 警告操作（如删除）
 * - **错误**: 错误操作提示
 * - **拒绝**: 操作被拒绝
 * 
 * ## 使用示例
 * ```kotlin
 * @Composable
 * fun MyButton() {
 *     val haptic = rememberHapticFeedback()
 *     
 *     Button(onClick = {
 *         haptic.performLightClick()
 *         // 执行操作
 *     }) {
 *         Text("点击")
 *     }
 * }
 * ```
 * 
 * ## 权限要求
 * 需要在 AndroidManifest.xml 中添加：
 * ```xml
 * <uses-permission android:name="android.permission.VIBRATE" />
 * ```
 */
class HapticFeedbackHelper(
    private val context: Context,
    private val view: View? = null,
    private val enabled: Boolean = true,
    private val strength: Int = 1
) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    
    /**
     * 检查触觉反馈当前是否可用：设备支持且用户未在设置中关闭。
     */
    val isAvailable: Boolean
        get() = enabled && vibrator?.hasVibrator() == true

    /**
     * View 层触觉反馈出口。与振动出口共用同一开关，
     * 否则 API 30+ 的 performHapticFeedback 路径会绕过设置继续触发。
     */
    private fun performViewHaptic(constant: Int): Boolean {
        if (!enabled) return true
        val target = view ?: return false
        target.performHapticFeedback(constant)
        return true
    }
    
    // ==================== 标准触觉反馈 ====================

    /** UI feedback has a bounded compatibility path when the host rejects it. */
    fun performSliderTick(): InteractionHapticResult = dispatchInteractionHaptic(
        enabled, strength, tick = true, AndroidInteractionHapticDevice(context, view))

    fun performSwitchToggle(): InteractionHapticResult = dispatchInteractionHaptic(
        enabled, strength, tick = false, AndroidInteractionHapticDevice(context, view))

    /**
     * 轻量点击反馈
     * 
     * 适用场景：
     * - 按钮点击
     * - 开关切换
     * - 选项选择
     * - 列表项点击
     */
    fun performLightClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performViewHaptic(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            performCustomVibration(10)  // 10ms 短震动
        }
    }

    /**
     * 下拉手势达到操作阈值时的反馈。
     *
     * 直接使用振动器，确保在不响应 View 层 CLOCK_TICK 的设备上，
     * Steam 下拉搜索仍与其他列表页面保持一致的触觉反馈。
     */
    fun performPullThreshold(isSyncStage: Boolean = false) {
        if (!isAvailable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isSyncStage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                )
            } else {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(VibrationPatterns.TICK, -1)
                )
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(if (isSyncStage) 36 else 20)
        }
    }

    /**
     * 验证器倒计时临近到期时的提示。
     *
     * 与下拉阈值一样直接走振动器：部分设备不响应 View 层 CLOCK_TICK，
     * 而倒计时提示需要在列表无焦点时也能触发。
     */
    fun performCountdownTick() {
        if (!isAvailable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(VibrationPatterns.TICK, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationPatterns.TICK, -1)
        }
    }

    /**
     * 中断正在进行的振动。
     *
     * 不经过 [isAvailable]：开关关闭后仍需要能停下已经开始的振动。
     */
    fun cancel() {
        vibrator?.cancel()
    }

    /**
     * 标准点击反馈
     * 
     * 适用场景：
     * - 重要按钮点击
     * - 确认操作
     * - Tab 切换
     */
    fun performClick() {
        if (!performViewHaptic(HapticFeedbackConstants.KEYBOARD_TAP)) {
            performCustomVibration(20)  // 20ms 震动
        }
    }
    
    /**
     * 长按反馈
     * 
     * 适用场景：
     * - 长按操作
     * - 拖拽开始
     * - 上下文菜单打开
     */
    fun performLongPress() {
        if (!performViewHaptic(HapticFeedbackConstants.LONG_PRESS)) {
            performCustomVibration(50)  // 50ms 震动
        }
    }
    
    // ==================== 状态反馈 ====================
    
    /**
     * 成功反馈
     * 
     * 适用场景：
     * - 操作成功
     * - 保存完成
     * - 验证通过
     * 
     * 震动模式：短-停-短 (表示肯定)
     */
    fun performSuccess() {
        if (!isAvailable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            vibrator?.vibrate(effect)
        } else {
            performCustomVibration(
                pattern = longArrayOf(0, 30, 50, 30),  // 短-停-短
                amplitudes = intArrayOf(0, 100, 0, 100)
            )
        }
    }
    
    /**
     * 警告反馈
     * 
     * 适用场景：
     * - 删除确认
     * - 警告提示
     * - 危险操作
     * 
     * 震动模式：长震 (表示警告)
     */
    fun performWarning() {
        if (!isAvailable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            vibrator?.vibrate(effect)
        } else {
            performCustomVibration(80)  // 80ms 长震动
        }
    }
    
    /**
     * 错误反馈
     * 
     * 适用场景：
     * - 操作失败
     * - 验证错误
     * - 输入无效
     * 
     * 震动模式：短-短-短 (表示否定)
     */
    fun performError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performViewHaptic(HapticFeedbackConstants.REJECT)
        } else {
            performCustomVibration(
                pattern = longArrayOf(0, 30, 30, 30, 30, 30),  // 短-短-短
                amplitudes = intArrayOf(0, 100, 0, 100, 0, 100)
            )
        }
    }
    
    /**
     * 拒绝反馈
     * 
     * 适用场景：
     * - 操作被拒绝
     * - 权限不足
     * - 限制触发
     */
    fun performReject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performViewHaptic(HapticFeedbackConstants.REJECT)
        } else {
            performCustomVibration(100)  // 100ms 拒绝震动
        }
    }
    
    // ==================== 特殊反馈 ====================
    
    /**
     * 生物识别成功反馈
     * 
     * 适用场景：
     * - 指纹识别成功
     * - 面部识别成功
     * 
     * 震动模式：渐强震动
     */
    fun performBiometricSuccess() {
        if (!isAvailable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            } else {
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator?.vibrate(effect)
        } else {
            performCustomVibration(
                pattern = longArrayOf(0, 20, 20, 40),
                amplitudes = intArrayOf(0, 80, 0, 255)
            )
        }
    }
    
    /**
     * 生物识别失败反馈
     * 
     * 适用场景：
     * - 指纹识别失败
     * - 面部识别失败
     * 
     * 震动模式：快速抖动
     */
    fun performBiometricError() {
        performCustomVibration(
            pattern = longArrayOf(0, 20, 20, 20, 20, 20),
            amplitudes = intArrayOf(0, 150, 0, 150, 0, 150)
        )
    }
    
    /**
     * 侧滑反馈
     * 
     * 适用场景：
     * - 列表项侧滑
     * - 页面滑动
     */
    fun performSwipe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performViewHaptic(HapticFeedbackConstants.GESTURE_START)
        } else {
            performCustomVibration(15)  // 15ms 轻微震动
        }
    }
    
    /**
     * 刷新反馈
     * 
     * 适用场景：
     * - 下拉刷新
     * - 数据重载
     */
    fun performRefresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performViewHaptic(HapticFeedbackConstants.CONFIRM)
        } else {
            performCustomVibration(25)  // 25ms 震动
        }
    }
    
    // ==================== 自定义震动 ====================
    
    /**
     * 自定义震动（单次）
     * 
     * @param duration 震动时长（毫秒）
     * @param amplitude 震动强度（1-255），-1表示默认
     */
    private fun performCustomVibration(
        duration: Long,
        amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE
    ) {
        if (!isAvailable) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(duration, amplitude)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        } catch (e: Exception) {
            android.util.Log.e("HapticFeedback", "Vibration failed", e)
        }
    }
    
    /**
     * 自定义震动（模式）
     * 
     * @param pattern 震动模式 [延迟, 震动, 停止, 震动, ...]
     * @param amplitudes 震动强度数组（需要与pattern长度匹配）
     */
    private fun performCustomVibration(
        pattern: LongArray,
        amplitudes: IntArray
    ) {
        if (!isAvailable) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            android.util.Log.e("HapticFeedback", "Pattern vibration failed", e)
        }
    }
}

/**
 * Compose 辅助函数：记住触觉反馈实例
 * 
 * @return HapticFeedbackHelper
 */
@Composable
fun rememberHapticFeedback(): HapticFeedbackHelper {
    val context = LocalContext.current
    val view = LocalView.current
    val enabled = LocalHapticFeedbackEnabled.current

    val strength = takagi.ru.monica.ui.LocalHapticFeedbackStrength.current
    return remember(context, view, enabled, strength) {
        HapticFeedbackHelper(context, view, enabled, strength)
    }
}
