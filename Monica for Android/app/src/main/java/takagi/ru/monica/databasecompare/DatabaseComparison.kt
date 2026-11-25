package takagi.ru.monica.databasecompare

import app.keemobile.kotpass.database.Credentials
import java.io.Closeable
import java.io.File

/** Native formats share a review model, never a lossy intermediary storage format. */
internal enum class CompareFormat(val extension: String) { KDBX("kdbx"), MDBX("mdbx") }

internal enum class CompareChange { ONLY_LEFT, ONLY_RIGHT, CHANGED, UNCHANGED, DELETION, BLOCKED }
internal enum class CompareChoice { LEFT, RIGHT, BOTH }
internal enum class CompareProblem {
    UNSUPPORTED_CONTENT, INCOMPLETE_CONTENT, IDENTITY_CONFLICT, UNRESOLVED_REFERENCE,
    HISTORY_INCOMPATIBLE, POLICY_RESTRICTED, SOURCE_CHANGED, TOO_LARGE, INVALID_SELECTION
}

/** Values are transient, hidden by default, and must never enter saved instance state or logs. */
internal data class CompareField(
    val name: String,
    val left: String?,
    val right: String?,
    val sensitive: Boolean = true,
    val generatedLabel: Boolean = true,
    val historyIndex: Int? = null,
    val protectedFlag: Boolean = false
)

internal data class CompareRow(
    val id: String,
    val title: String,
    val path: String,
    val kind: String,
    val change: CompareChange,
    val fields: List<CompareField> = emptyList(),
    val allowedChoices: Set<CompareChoice> = setOf(CompareChoice.LEFT, CompareChoice.RIGHT),
    val defaultChoice: CompareChoice? = null,
    val problem: CompareProblem? = null,
    val note: CompareProblem? = null
)

internal data class ComparePlan(
    val format: CompareFormat,
    val leftName: String,
    val rightName: String,
    val rows: List<CompareRow>,
    val blockers: Set<CompareProblem> = emptySet()
) {
    fun validateChoices(choices: Map<String, CompareChoice>) {
        require(blockers.isEmpty()) { "Comparison contains unsupported content" }
        require(rows.map { it.id }.distinct().size == rows.size) { "Duplicate comparison row" }
        require(choices.keys.all { id -> rows.any { it.id == id } }) { "Unknown comparison selection" }
        rows.forEach { row ->
            require(row.problem == null && row.change != CompareChange.BLOCKED) { "Blocked comparison row" }
            require((choices[row.id] ?: row.defaultChoice) in row.allowedChoices) { "Unresolved comparison selection" }
        }
    }
}

/** Files must be coherent encrypted snapshots, not live MDBX SQLite files without their WAL. */
internal data class CompareInput(
    val file: File,
    val label: String,
    val password: CharArray,
    val keyFile: ByteArray? = null,
    val snapshotId: String? = null,
    var authenticatedCredentials: (() -> Credentials)? = null
) {
    fun clearCredentials() {
        authenticatedCredentials = null
        password.fill('\u0000')
        keyFile?.fill(0)
    }
}

/** Invoke on a bounded background dispatcher; closing invalidates every later read/write. */
internal interface NativeDatabaseComparison : Closeable {
    val plan: ComparePlan

    /** Creates and reopens an encrypted result; never mutates either input. */
    fun writeResult(
        output: File,
        choices: Map<String, CompareChoice>,
        password: CharArray,
        checkActive: () -> Unit = {}
    )
}
