package takagi.ru.monica.ui.vaultv2

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
import takagi.ru.monica.data.*
import takagi.ru.monica.rustcore.RustVaultOverviewCore

@RunWith(AndroidJUnit4::class)
class VaultOverviewNativeTest {
    @Test fun realNativeProjectionCountsProjectsAndKeepsSecondaryPin() {
        val entries = (1L..602L).map { id -> PasswordEntry(id = id, title = "Project ${id / 2}",
            username = "synthetic", password = "", website = "", passwordGroupId = "group-${(id - 1) / 2}",
            bitwardenVaultId = if ((id - 1) / 2 % 2 == 0L) null else 2L, isFavorite = id < 5) }
        val rows = buildVaultV2PasswordItems(entries)
        val sources = listOf(VaultOverviewSource("local", "Local", "Monica"), VaultOverviewSource("bitwarden:2", "Work", "Bitwarden"))
        val prepared = prepareVaultOverview(rows, sources, VaultOverviewConfig(pinnedItems = listOf(rows[1].overviewIdentity())),
            emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList())
        for (scope in listOf("all", "local", "bitwarden:2")) {
            val expected = projectVaultOverview(prepared, scope, aggregate = ::aggregateVaultOverviewKotlin)
            val actual = projectVaultOverview(prepared, scope, aggregate = this::native)
            assertEquals(expected, actual)
            assertEquals(if (scope == "all") 301 else if (scope == "local") 151 else 150, actual.items.size)
        }
        assertEquals("password:2", projectVaultOverview(prepared, "local", aggregate = this::native).frequentItems.first().key)
    }

    @Test fun nativePickerSearchesSecondaryAccountWithinOneProjectRow() {
        val rows = buildVaultV2PasswordItems((1L..1204L).map { id -> PasswordEntry(id = id,
            title = "Project ${(id - 1) / 2}", username = "account-$id", password = "", website = "",
            passwordGroupId = "project-${(id - 1) / 2}") })
        prepareOverviewPicker(rows, listOf(VaultOverviewSource("local", "Local", "Monica")), cards = false).use { picker ->
            assertTrue(picker.usesNative)
            assertEquals(602, picker.rows.size)
            for (query in listOf("account-2", "account-1204", "project", "missing")) {
                assertEquals(picker.filterKotlin(query, "all"), picker.filter(query, "all"))
            }
            assertEquals("password:1203", picker.filter("account-1204", "local").single().item.key)
        }
    }

    private fun batch(size: Int, random: Random, selected: Int = -1, recommendations: Int = 3): LongArray {
        val data = LongArray(OVERVIEW_HEADER + size * OVERVIEW_ROW_WIDTH)
        longArrayOf(1, 4, 32, selected.toLong(), recommendations.toLong(), 7).copyInto(data)
        repeat(size) { row ->
            val type = random.nextInt(7)
            val values = longArrayOf(random.nextInt(-1, 4).toLong(), type.toLong(), random.nextInt(-1, 32).toLong(),
                random.nextInt(2).toLong(), if (row % 23 == 0) row.toLong() else -1, random.nextInt(100).toLong(),
                random.nextLong(10000), if (type >= 4) 1 else 0)
            values.copyInto(data, OVERVIEW_HEADER + row * OVERVIEW_ROW_WIDTH)
        }
        return data
    }

    private fun native(data: LongArray): VaultOverviewAggregation = requireNotNull(
        decodeVaultOverviewAggregation(requireNotNull(RustVaultOverviewCore.project(data)), data))

    private fun assertSame(expected: VaultOverviewAggregation, actual: VaultOverviewAggregation) {
        assertArrayEquals(expected.visible, actual.visible)
        assertArrayEquals(expected.favorites, actual.favorites)
        assertArrayEquals(expected.cards, actual.cards)
        assertArrayEquals(expected.items, actual.items)
        assertArrayEquals(expected.typeCounts, actual.typeCounts)
        assertArrayEquals(expected.sourceCounts, actual.sourceCounts)
        assertArrayEquals(expected.folderCounts, actual.folderCounts)
    }

    @Test fun realJniMatchesKotlinForScopesPinsCountsLocksAndIndependentRecommendations() {
        val random = Random(311)
        repeat(60) { iteration ->
            val data = batch(random.nextInt(3000), random, selected = iteration % 5 - 1, recommendations = iteration % 4)
            assertSame(aggregateVaultOverviewKotlin(data), native(data))
        }
        assertSame(aggregateVaultOverviewKotlin(batch(0, random)), native(batch(0, random)))
    }

    @Test fun nativeCannotRunOrLoadOnTheAnimationThread() {
        val data = batch(1000, Random(1))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNull(RustVaultOverviewCore.project(data))
        }
        assertSame(aggregateVaultOverviewKotlin(data), native(data))
        assertNull(RustVaultOverviewCore.project(longArrayOf(1, -1, 0, -1, 3, 7)))
    }

    @Test fun removedFrequentItemsStayExcludedInRealNativeProjection() {
        val rows = buildVaultV2PasswordItems(List(OVERVIEW_NATIVE_THRESHOLD + 4) { index ->
            PasswordEntry(id = index + 1L, title = "Entry $index", website = "", username = "account",
                password = "", bitwardenVaultId = if (index % 2 == 0) 2 else null, isFavorite = index < 4)
        })
        val sources = listOf(VaultOverviewSource("local", "Local", "Monica"),
            VaultOverviewSource("bitwarden:2", "Work", "Bitwarden"))
        val removed = rows.take(3).map { it.overviewIdentity() }
        val config = VaultOverviewConfig(pinnedItems = rows.take(4).map { it.overviewIdentity() })
            .removeFrequentItems(removed)
        val usage = rows.associate { it.overviewIdentity() to VaultOverviewUsage(it.overviewIdentity(), 100, 9999) }
        val legacy = rows.associate { it.passwordEntry!!.id to PasswordQuickAccessRecord(it.passwordEntry.id, 1000, 99999) }
        val prepared = prepareVaultOverview(rows, sources, config, usage, legacy,
            emptyList(), emptyList(), emptyMap(), emptyList())
        for (scope in listOf("all", "local", "bitwarden:2")) {
            val expected = projectVaultOverview(prepared, scope, aggregate = ::aggregateVaultOverviewKotlin)
            val actual = projectVaultOverview(prepared, scope, aggregate = this::native)
            assertEquals(expected, actual)
            assertFalse(actual.frequentItems.any { it.overviewIdentity() in removed })
        }
        val all = projectVaultOverview(prepared, "all", aggregate = this::native)
        assertEquals(rows, all.items)
        assertEquals(rows.take(4), all.favorites)
    }

    @Test fun measuresJniAndFullSnapshotCostBeforeChoosingTheThreshold() {
        val measurements = JSONArray()
        var sink = 0
        for (size in listOf(64, 256, 512, 1000, 10000, 50000)) {
            val metadata = batch(size, Random(9))
            val rows = List(size) { index -> VaultV2Item(
                "password:$index", VaultV2ItemType.PASSWORD, "Entry $index", "user", index % 3 == 0, "$index", emptyList(),
                passwordEntry = PasswordEntry(id = index.toLong(), title = "Entry $index", username = "user", website = "", password = "",
                    bitwardenVaultId = if (index % 2 == 0) 1 else null)) }
            val sources = listOf(VaultOverviewSource("local", "Local", "Monica"), VaultOverviewSource("bitwarden:1", "Work", "Bitwarden"))
            val usage = rows.associate { it.overviewIdentity() to VaultOverviewUsage(it.overviewIdentity(), 20, 99) }
            val prepared = prepareVaultOverview(rows, sources, VaultOverviewConfig(), usage, emptyMap(),
                emptyList(), emptyList(), emptyMap(), emptyList())
            var cachedScope = "local"
            fun cachedSnapshot(useNative: Boolean): VaultOverviewSnapshot = projectVaultOverview(prepared, cachedScope,
                aggregate = if (useNative) this::native else ::aggregateVaultOverviewKotlin)
            fun snapshot(useNative: Boolean): VaultOverviewSnapshot = buildVaultOverviewSnapshot(
                rows, sources, "all", VaultOverviewConfig(), usage, emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(),
                aggregate = if (useNative) this::native else ::aggregateVaultOverviewKotlin)
            assertEquals(snapshot(false), snapshot(true))
            assertEquals(cachedSnapshot(false), cachedSnapshot(true))
            repeat(12) { aggregateVaultOverviewKotlin(metadata); native(metadata); snapshot(false); snapshot(true); cachedSnapshot(false); cachedSnapshot(true) }
            val kotlinTimes = mutableListOf<Double>()
            val nativeTimes = mutableListOf<Double>()
            val kotlinFull = mutableListOf<Double>()
            val nativeFull = mutableListOf<Double>()
            val kotlinCached = mutableListOf<Double>()
            val nativeCached = mutableListOf<Double>()
            repeat(17) { iteration ->
                cachedScope = if (iteration % 2 == 0) "local" else "all"
                fun kotlin() {
                    kotlinTimes += measureNanoTime { sink += aggregateVaultOverviewKotlin(metadata).visible.size } / 1_000_000.0
                    kotlinFull += measureNanoTime { sink += snapshot(false).items.size } / 1_000_000.0
                    kotlinCached += measureNanoTime { sink += cachedSnapshot(false).items.size } / 1_000_000.0
                }
                fun rust() {
                    nativeTimes += measureNanoTime { sink += native(metadata).visible.size } / 1_000_000.0
                    nativeFull += measureNanoTime { sink += snapshot(true).items.size } / 1_000_000.0
                    nativeCached += measureNanoTime { sink += cachedSnapshot(true).items.size } / 1_000_000.0
                }
                if (iteration % 2 == 0) { kotlin(); rust() } else { rust(); kotlin() }
            }
            measurements.put(JSONObject().put("rows", size)
                .put("kotlinMedianMs", kotlinTimes.sorted()[8]).put("nativeWithJniMedianMs", nativeTimes.sorted()[8])
                .put("kotlinSnapshotMedianMs", kotlinFull.sorted()[8]).put("nativeSnapshotMedianMs", nativeFull.sorted()[8])
                .put("kotlinCachedScopeMedianMs", kotlinCached.sorted()[8]).put("nativeCachedScopeMedianMs", nativeCached.sorted()[8])
                .put("kotlinSnapshotP95Ms", kotlinFull.sorted()[16]).put("nativeSnapshotP95Ms", nativeFull.sorted()[16]))
        }
        assertTrue(sink > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("overview-verification"), "projection-cost.json")
            .writeText(JSONObject().put("measurements", measurements).toString(2))
    }
}
