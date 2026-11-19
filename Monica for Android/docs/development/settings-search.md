# Settings definitions and search

`app/src/main/java/takagi/ru/monica/ui/screens/Setting.kt` is the single source of static setting titles, descriptions and destinations. `SettingsSearchCatalog.kt` enumerates its definitions automatically; do not add a second search list.

To add a setting:

1. Declare its title resource, static description and real destination in `Setting`. Use existing localized resources or supply translations for every supported language.
2. Pass that definition to `SettingsItem`, `SettingsPanelRow`, `SettingsSubpageRow`, `CustomizationToggle`, `CustomizationSection`, `BottomNavConfigRow` or the typed wrappers. These APIs require a definition. Custom layouts must also obtain their static labels from the definition. Choice and reorder controls require their containing setting.
3. For a new page, use its actual route and add it to the navigation coverage test. For a protected dialog or a conditionally hidden row, target the visible parent entrance; searching must never authenticate, change a preference, grant permission or launch an action automatically.
4. Run `SettingsSearchInstrumentedTest`. It checks every active definition in all 15 supported languages, Plus visibility, title/description matching without preference/database access, navigation targets, cancellation and authenticated HyperOS entry. Every new definition joins the checks automatically.

Keep aliases optional. Titles/descriptions are shared with the UI, and parent results inherit descendant labels using their breadcrumb resources. `relatedText` is only for a shared entry available from more than one parent.

`SettingsContentRow` renders status, user data or values within an already defined setting. It does not register searchable data. Do not use it to bypass the definition required for a new setting. Never put account names, vault names, passwords, preference values or user-defined field names in definitions or search keywords.

`legacyOnly` is reserved for retained code from the retired autofill page. New settings are searchable by default. Plus-only settings remain filtered. F-Droid excludes OneDrive rather than advertising an unavailable destination.

The scope is application settings. A record editor or a particular vault's data is not a settings-search source. Completely hand-written new controls must follow the same definition contract; Compose cannot infer a safe route or permission boundary from arbitrary text.
