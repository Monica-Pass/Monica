package takagi.ru.monica.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.AppLauncherIcon
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.data.Language
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object AppLauncherIconManager {
    private const val COMPAT_MODERN_ALIAS = "takagi.ru.monica.ModernLauncherAlias"
    private const val COMPAT_CLASSIC_ALIAS = "takagi.ru.monica.LockLauncherAlias"
    private const val HOME_MODERN_ALIAS = "takagi.ru.monica.ModernHomeLauncherAlias"
    private const val HOME_CLASSIC_ALIAS = "takagi.ru.monica.ClassicHomeLauncherAlias"
    private const val VISIBLE_MODERN_PASS_ALIAS = "takagi.ru.monica.ModernVisibleLauncherAlias"
    private const val VISIBLE_CLASSIC_PASS_ALIAS = "takagi.ru.monica.ClassicVisibleLauncherAlias"
    private const val VISIBLE_MODERN_MONICA_ALIAS = "takagi.ru.monica.ModernVisibleLauncherAliasMonica"
    private const val VISIBLE_CLASSIC_MONICA_ALIAS = "takagi.ru.monica.ClassicVisibleLauncherAliasMonica"
    private const val VISIBLE_SNOW_LEOPARD_ALIAS = "takagi.ru.monica.SnowLeopardVisibleLauncherAlias"

    private data class BiometricPromptBrandingMethods(
        val logoRes: Method?,
        val logoDescription: Method?
    )

    private val biometricPromptBrandingMethods =
        ConcurrentHashMap<Class<*>, BiometricPromptBrandingMethods>()

    fun apply(context: Context, icon: AppLauncherIcon, label: AppLauncherLabel) {
        repairCompatibilityLaunchTargets(context)
        applyVisibleLauncherSelection(context, label)
    }

    fun repairLegacyDisabledComponents(context: Context) {
        repairCompatibilityLaunchTargets(context)
    }

    fun repairLaunchEntryPointsAfterUpgrade(
        context: Context,
        icon: AppLauncherIcon,
        label: AppLauncherLabel
    ) {
        repairCompatibilityLaunchTargets(context)
        applyVisibleLauncherSelection(context, label)
    }

    fun getCurrentSelection(context: Context): AppLauncherIcon {
        return AppLauncherIcon.MODERN
    }

    fun resolveBrandingIconRes(context: Context): Int {
        return R.drawable.monica_launcher
    }

    fun applyBiometricPromptBranding(context: Context, promptInfoBuilder: Any) {
        val builderClass = promptInfoBuilder.javaClass
        val methods = biometricPromptBrandingMethods.computeIfAbsent(builderClass) { clazz ->
            BiometricPromptBrandingMethods(
                logoRes = clazz.methods.firstOrNull { method ->
                    method.name == "setLogoRes" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType
                },
                logoDescription = clazz.methods.firstOrNull { method ->
                    method.name == "setLogoDescription" &&
                        method.parameterTypes.size == 1 &&
                        CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            )
        }

        methods.logoRes?.let { method ->
            runCatching { method.invoke(promptInfoBuilder, resolveBrandingIconRes(context)) }
        }
        methods.logoDescription?.let { method ->
            runCatching { method.invoke(promptInfoBuilder, context.getString(R.string.app_name)) }
        }
    }

    private fun repairCompatibilityLaunchTargets(context: Context) {
        val packageManager = context.packageManager
        val components = listOf(
            ComponentName(context, MainActivity::class.java),
            ComponentName(context, COMPAT_MODERN_ALIAS),
            ComponentName(context, COMPAT_CLASSIC_ALIAS),
            ComponentName(context, HOME_MODERN_ALIAS),
            ComponentName(context, HOME_CLASSIC_ALIAS)
        )

        components.forEach { component ->
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun applyVisibleLauncherSelection(
        context: Context,
        label: AppLauncherLabel
    ) {
        val packageManager = context.packageManager
        // The snow leopard language swaps the launcher icon for an easter egg. Exactly
        // one visible alias stays enabled; the alias that is about to take over is
        // enabled before the others are disabled so the launcher never shows an
        // empty spot for this package.
        val snowLeopardMode = StartupLanguageCache.read(context) == Language.SNOW_LEOPARD
        val enabledAlias = when {
            snowLeopardMode -> VISIBLE_SNOW_LEOPARD_ALIAS
            label == AppLauncherLabel.MONICA_PASS -> VISIBLE_MODERN_PASS_ALIAS
            else -> VISIBLE_MODERN_MONICA_ALIAS
        }
        // The alias taking over is written first; every other visible alias is
        // disabled exactly once and never overwrites the enabled entry above.
        val states = linkedMapOf(
            ComponentName(context, enabledAlias) to
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        )
        listOf(
            VISIBLE_SNOW_LEOPARD_ALIAS,
            VISIBLE_MODERN_PASS_ALIAS,
            VISIBLE_MODERN_MONICA_ALIAS
        ).filter { alias -> alias != enabledAlias }.forEach { alias ->
            states[ComponentName(context, alias)] =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        states[ComponentName(context, VISIBLE_CLASSIC_PASS_ALIAS)] =
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        states[ComponentName(context, VISIBLE_CLASSIC_MONICA_ALIAS)] =
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                states.map { (component, state) ->
                    PackageManager.ComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
            )
            return
        }

        states.forEach { (component, state) ->
            packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun componentStateFor(shouldEnable: Boolean): Int {
        return if (shouldEnable) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }
}
