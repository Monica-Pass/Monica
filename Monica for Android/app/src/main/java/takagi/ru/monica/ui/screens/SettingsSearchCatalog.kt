package takagi.ru.monica.ui.screens

import android.content.Context

/** Automatically indexes every UI setting definition, with no page rendering or preference reads. */
internal fun settingsSearchEntries(context: Context, isPlusActivated: Boolean = false): List<SettingsSearchEntry> =
    Setting.entries.filter { !it.legacyOnly && (!it.plusOnly || isPlusActivated) }.map { setting ->
        SettingsSearchEntry(
            id = context.resources.getResourceEntryName(setting.titleRes),
            title = context.getString(setting.titleRes),
            summary = if (setting.summaryRes == 0) "" else context.getString(setting.summaryRes),
            path = setting.path.distinct().joinToString(" › ") { context.getString(it) },
            route = setting.route,
            focusTitleRes = setting.focusTitleRes,
            keywords = (listOf(setting.keywords) + setting.relatedText.map { context.getString(it) } +
                Setting.entries.filter { child -> child != setting && !child.legacyOnly && setting.titleRes in child.path &&
                    (!child.plusOnly || isPlusActivated) }.flatMap { child ->
                    listOf(context.getString(child.titleRes),
                        if (child.summaryRes == 0) "" else context.getString(child.summaryRes))
                }).joinToString(" "),
        )
    }
