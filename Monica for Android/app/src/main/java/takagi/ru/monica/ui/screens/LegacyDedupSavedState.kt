package takagi.ru.monica.ui.screens

/**
 * Binary names and constants used by the retired screen's rememberSaveable state.
 * Android may deserialize these before navigation can discard the retired entry.
 * Keep only the inert types so an upgrade can restore and leave that entry safely.
 */
@Suppress("unused")
internal enum class DedupSheet {
    SOURCES, POLICY, TARGET, PREVIEW, WARNINGS, RETAINED_SOURCES, FAILURES
}

@Suppress("unused")
internal enum class DedupPreviewFilter {
    ALL, WRITE, CONFLICT, SKIP
}
