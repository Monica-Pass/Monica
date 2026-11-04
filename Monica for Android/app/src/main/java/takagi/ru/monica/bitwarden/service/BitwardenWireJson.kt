package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Validate duplicate keys before JsonObject can silently discard an earlier field. */
internal object BitwardenWireJson {
    fun parseObject(source: String, limit: Int = 8 * 1024 * 1024): JsonObject {
        require(source.length <= limit) { "Wire document exceeds limit" }
        Scanner(source).validate()
        return Json.parseToJsonElement(source).jsonObject
    }

    private class Scanner(private val source: String) {
        private var offset = 0
        fun validate() {
            value(0)
            spaces()
            require(offset == source.length) { "Unexpected trailing wire data" }
        }
        private fun value(depth: Int) {
            require(depth <= 64) { "Wire document exceeds nesting limit" }
            spaces()
            require(offset < source.length) { "Incomplete wire document" }
            when (source[offset]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                else -> {
                    val start = offset
                    while (offset < source.length && source[offset] !in ",]} \t\r\n") offset++
                    require(offset > start) { "Invalid wire value" }
                }
            }
        }
        private fun objectValue(depth: Int) {
            offset++
            spaces()
            if (take('}')) return
            val keys = HashSet<String>()
            do {
                spaces()
                val key = stringValue()
                require(keys.add(key)) { "Duplicate wire JSON key" }
                spaces()
                require(take(':')) { "Missing wire field separator" }
                value(depth)
                spaces()
                if (take('}')) return
                require(take(',')) { "Missing wire object separator" }
            } while (true)
        }
        private fun arrayValue(depth: Int) {
            offset++
            spaces()
            if (take(']')) return
            do {
                value(depth)
                spaces()
                if (take(']')) return
                require(take(',')) { "Missing wire array separator" }
            } while (true)
        }
        private fun stringValue(): String {
            require(take('"')) { "Wire field name must be a string" }
            val start = offset - 1
            var escaped = false
            while (offset < source.length) {
                val char = source[offset++]
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') return Json.decodeFromString<String>(source.substring(start, offset))
            }
            error("Incomplete wire JSON string")
        }
        private fun spaces() {
            while (offset < source.length && source[offset] in " \t\r\n") offset++
        }
        private fun take(char: Char): Boolean = if (offset < source.length && source[offset] == char) {
            offset++
            true
        } else false
    }
}
