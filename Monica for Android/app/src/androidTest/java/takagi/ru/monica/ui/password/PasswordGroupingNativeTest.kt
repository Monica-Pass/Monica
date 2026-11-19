package takagi.ru.monica.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.passwordProjectKey
import takagi.ru.monica.rustcore.RustPasswordGroupingCore
import takagi.ru.monica.ui.password.StackCardMode

@RunWith(AndroidJUnit4::class)
class PasswordGroupingNativeTest {
    @Test fun expandedNativeAndFallbackPreserveProjectsAndSourceBoundaries() {
        val entries = (0 until 600).map { index ->
            val pair = index / 2
            PasswordEntry(id = index + 1L, title = "Same title", username = "same-account",
                password = "unused", website = "https://example.invalid", sortOrder = index,
                passwordGroupId = "project-${pair / 2}",
                bitwardenVaultId = if (pair % 2 == 0) null else 7L)
        } + listOf(PasswordEntry(id = 601, title = "Same title", username = "same-account", password = "unused", website = ""),
            PasswordEntry(id = 602, title = "Same title", username = "same-account", password = "unused", website = ""))
        for (mode in listOf("smart", "title", "website", "folder", "note", "app")) {
            val config = config(mode).copy(effectiveStackCardMode = StackCardMode.ALWAYS_EXPANDED,
                effectiveNoStackEntryIds = setOf(1L, 4L), effectiveManualStackGroupByEntryId = mapOf(2L to "manual"))
            val fallback = buildKotlinPasswordGroups(entries, config)
            val native = requireNotNull(tryBuildNativePasswordGroups(entries, config))
            assertEquals(snapshot(fallback), snapshot(native))
            assertEquals(snapshot(native), snapshot(buildGroupedPasswordsForEntries(entries, config)))
            assertEquals(302, native.size)
            assertEquals(entries.map { it.id }.toSet(), native.values.flatten().map { it.id }.toSet())
            assertTrue(native.values.all { group -> group.map { it.passwordProjectKey() }.distinct().size == 1 })
            assertEquals(300, native.values.count { it.size == 2 })
        }
        val local = buildGroupedPasswordsForEntries(entries, config("smart").copy(isLocalOnlyView = true))
        assertEquals(302, local.size)
        assertEquals(600, local.values.filter { it.size == 2 }.sumOf { it.size })
    }

    @Test fun nativeAndFallbackKeepTheOriginalGroupingAndOrder() {
        for (count in listOf(1, 64, 513)) {
            val entries = fixture(count)
            for (mode in listOf("title", "smart", "website", "folder", "note", "app")) {
                for (stack in StackCardMode.entries) {
                    for (match in listOf("strict", "relaxed")) {
                        val config = config(mode).copy(effectiveStackCardMode = stack, websiteStackMatchMode = match,
                            effectiveNoStackEntryIds = entries.filter { it.id % 11 == 0L }.map { it.id }.toSet(),
                            effectiveManualStackGroupByEntryId = entries.filter { it.id % 5 == 0L }.associate { it.id to "group-${it.id % 3}" })
                        val expected = snapshot(buildLegacyPasswordGroups(entries, config))
                        val native = tryBuildNativePasswordGroups(entries, config)
                        assertNotNull("Native projection unavailable: $count/$mode/$stack/$match", native)
                        assertEquals("Native order: $count/$mode/$stack/$match", expected, snapshot(native!!))
                        assertEquals("Fallback order", expected, snapshot(buildKotlinPasswordGroups(entries, config)))
                        assertEquals("Integrated order", expected, snapshot(buildGroupedPasswordsForEntries(entries, config)))
                    }
                }
            }
            val local = config("title").copy(isLocalOnlyView = true)
            assertEquals(snapshot(buildLegacyPasswordGroups(entries, local)), snapshot(buildGroupedPasswordsForEntries(entries, local)))
        }
    }

    @Test fun invalidNativeResultsAndOversizedInputsUseTheFallback() {
        val entries = fixture(3)
        val keys = listOf("a", "b", "c")
        for (invalid in listOf(intArrayOf(), intArrayOf(1, 0), intArrayOf(1, 1, 0, 3, 0, 0, 2),
            intArrayOf(1, 1, 0, 3, 0, 1, 3), intArrayOf(1, 1, 4, 3, 0, 1, 2))) {
            assertNull(decodePasswordGrouping(invalid, entries, keys, false))
        }
        var called = false
        val oversized = object : AbstractList<PasswordEntry>() {
            override val size = RustPasswordGroupingCore.MAX_ENTRIES + 1
            override fun get(index: Int): PasswordEntry = error("Oversized data must not be read or packed")
        }
        assertNull(tryBuildNativePasswordGroups(oversized, config("title")) { called = true; null })
        assertFalse(called)
        assertNull(RustPasswordGroupingCore.project(intArrayOf(1, Int.MAX_VALUE, 0, 0)))
        assertNull(tryBuildNativePasswordGroups(entries, config("title")) { null })
    }

    @Test fun measureCompleteGroupingCallsIncludingPackingAndMapping() {
        val results = JSONArray()
        for (count in listOf(256, 1_000, 10_000)) {
            val entries = fixture(count)
            val config = config("title")
            val implementations = listOf<() -> Map<String, List<PasswordEntry>>>(
                { buildLegacyPasswordGroups(entries, config) },
                { buildKotlinPasswordGroups(entries, config) },
                { checkNotNull(tryBuildNativePasswordGroups(entries, config)) },
            )
            val expected = snapshot(implementations[0]())
            repeat(3) { implementations.forEach { assertEquals(expected, snapshot(it())) } }
            val samples = Array(3) { mutableListOf<Double>() }
            repeat(9) { round ->
                for (slot in 0..2) {
                    val index = (slot + round) % 3
                    lateinit var result: Map<String, List<PasswordEntry>>
                    val ns = measureNanoTime { result = implementations[index]() }
                    assertEquals(expected, snapshot(result))
                    samples[index] += ns / 1_000_000.0
                }
            }
            val row = JSONObject().put("count", count)
            listOf("legacy_ms", "kotlin_ms", "rust_full_ms").forEachIndexed { i, label -> row.put(label, JSONArray(samples[i])) }
            results.put(row)
            Log.i("DockGroupingValidation", "rows=$count medianMs=" + samples.joinToString { it.sorted()[4].toString() })
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "dedup-validation").apply { mkdirs() }
            .resolve("password-grouping-performance.json").writeText(results.toString(2))
    }

    private fun config(mode: String) = PasswordGroupingConfig(false, StackCardMode.AUTO, mode, "strict", emptySet(), emptyMap(), "Untitled")
    private fun snapshot(groups: Map<String, List<PasswordEntry>>) = groups.map { (key, values) -> key to values.map { it.id } }

    private fun fixture(count: Int): List<PasswordEntry> = (0 until count).map { index ->
        val pair = index / 2
        PasswordEntry(id = index + 1L, title = if (pair % 17 == 0) "" else "Site ${pair % 97} 中文 🐈",
            username = "user-${pair % 61}", website = "https://s${pair % 11}.example.co.uk/Path${pair % 7}",
            password = "synthetic-unused-password", notes = if (pair % 4 == 0) "note${pair % 19}\nSecond line" else "",
            sortOrder = (pair % 5) - 2, isFavorite = pair % 13 == 0,
            categoryId = (pair % 3).toLong(), appName = "App ${pair % 4}",
            loginType = if (pair % 29 == 0) "API_TOKEN" else "PASSWORD",
            keepassDatabaseId = if (pair % 3 == 0) 1 else null,
            keepassGroupPath = if (pair % 3 == 0) "folder${pair % 7}" else null,
            bitwardenVaultId = if (pair % 3 == 1) 2 else null,
            bitwardenCipherId = if (pair % 3 == 1) "cipher-$pair" else null)
    }.shuffled(Random(312))
}
