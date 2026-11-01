package takagi.ru.monica.lansync

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Packaged JNI, real Noise/TCP, existing clock/merge/deletion policy. Synthetic stores only. */
class NearbyIncrementalTransportTest {
    @Test fun oneEditedRecordSendsOnlyDifferencesAndKeepsOtherRecords() = verify(false)
    @Test fun incrementalDeletionStillRequiresExistingPreviewAndOmitPolicy() = verify(true)

    private fun verify(deletion: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "incremental-fixture-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val replicas = mutableListOf<Long>(); val transfers = mutableListOf<Long>()
        fun request(operation: String, configure: JSONObject.() -> Unit = {}) = RustLanSync.request(JSONObject().put("operation", operation).apply(configure))
        fun open(id: String): Long = request("replica_open") {
            put("path",File(root,"$id.replica").absolutePath); put("device_id",id); put("key","A".repeat(43))
        }.getLong("handle").also(replicas::add)
        fun scan(handle: Long, changed: Boolean) {
            request("replica_scan_begin") { put("handle",handle); put("scopes",JSONArray().put("passwords")) }
            for(start in listOf(0,256)) {
                val entries = JSONArray()
                for(index in start until minOf(start+256,300)) {
                    val updated = changed && index == 299
                    entries.put(JSONObject().put("local_id","local-$index").put("global_id","record-$index").put("scope","passwords")
                        .put("guard",if(updated) "v2" else "v1").put("deleted",updated && deletion)
                        .put("data",JSONObject().put("title","Synthetic $index").put("password",if(updated) "changed" else "original")))
                }
                request("replica_scan_batch") { put("handle",handle); put("entries",entries) }
            }
            request("replica_scan_end") { put("handle",handle) }
        }
        fun export(handle: Long, name: String): Pair<File,String> {
            val directory = File(root,name).also { check(it.mkdirs()) }
            val result = request("replica_export") { put("handle",handle); put("path",File(directory,"changes.mdbxsync").absolutePath); put("skip_nonzero_passkeys",true) }
            return directory to result.getString("content_key")
        }
        try {
            val source = open("source"); val target = open("target")
            scan(source,false); val initial = export(source,"initial")
            request("replica_merge") { put("handle",target); put("path",File(initial.first,"changes.mdbxsync").absolutePath); put("content_key",initial.second) }
            scan(target,false); scan(source,true)
            val outgoing = export(source,"source-out"); val baseline = export(target,"target-out")
            fun device(id: String) = JSONObject().put("device_id",id).put("name",id).put("pair_id","fixture-pair")
                .put("content_type","vault").put("vault_content_version",2).put("incremental_sync",1)
            val targetIn = File(root,"target-in"); val sourceIn = File(root,"source-in")
            val host = request("start") {
                put("protocol",2); put("listen_address","127.0.0.1"); put("vault_id","fixture"); put("device",device("source"))
                put("outgoing_directory",outgoing.first.absolutePath); put("incoming_directory",sourceIn.absolutePath); put("content_key",outgoing.second)
                put("trusted_key","A".repeat(43)); put("expected_device_id","target")
            }.also { transfers += it.getLong("handle") }
            request("connect") {
                put("protocol",2); put("vault_id","fixture"); put("device",device("target")); put("expected_device_id","source")
                put("invitation","monica-lan:2:127.0.0.1:${host.getInt("port")}:${"A".repeat(43)}")
                put("outgoing_directory",baseline.first.absolutePath); put("incoming_directory",targetIn.absolutePath); put("content_key",baseline.second)
            }.also { transfers += it.getLong("handle") }
            val deadline = System.nanoTime()+30_000_000_000L; val acknowledged = mutableSetOf<Long>()
            while(true) {
                val states = transfers.map { handle -> request("progress") { put("handle",handle) } }
                for((index,state) in states.withIndex()) {
                    assertNotEquals(state.optString("error"),"failed",state.getString("phase"))
                    if(state.getString("phase")=="received" && acknowledged.add(transfers[index])) {
                        val receiving = if (index == 0) source else target
                        val receivedFile = File(if (index == 0) sourceIn else targetIn,"changes.mdbxsync")
                        val receivedKey = if (index == 0) baseline.second else outgoing.second
                        val preview = request("replica_merge_preview") { put("handle",receiving); put("path",receivedFile.absolutePath); put("content_key",receivedKey) }
                        assertEquals(if(index == 1 && deletion) 1 else 0,preview.getInt("deletions"))
                        request("replica_merge") {
                            put("handle",receiving); put("path",receivedFile.absolutePath); put("content_key",receivedKey)
                            put("review_revision",preview.getString("review_revision")); put("deletion_policy",if(index == 1 && deletion) "omit" else "include")
                        }
                        request("acknowledge") { put("handle",transfers[index]); put("success",true) }
                    }
                }
                if(states.all { it.getString("phase")=="completed" }) {
                    assertTrue("delta should be smaller than 300-record snapshots",states.first().getLong("transferred") < 5000)
                    break
                }
                check(System.nanoTime()<deadline); Thread.sleep(10)
            }
            val pending = request("replica_pending") { put("handle",target) }.getJSONArray("items")
            assertEquals(if(deletion) 0 else 1,pending.length())
            val originals = request("replica_local") { put("handle",target); put("local_ids",JSONArray((0 until 256).map { "local-$it" })) }.getJSONArray("items")
            // Query only a bounded batch; absent delta records never become tombstones.
            assertEquals(256,originals.length())
            for(index in 0 until originals.length()) assertFalse(originals.getJSONObject(index).getBoolean("deleted"))
        } finally {
            transfers.forEach { handle -> runCatching { request("cancel") { put("handle",handle) } } }
            var released = true
            transfers.forEach { handle ->
                val deadline = System.nanoTime() + 5_000_000_000L
                var terminal = false
                while (System.nanoTime() < deadline) {
                    val phase = runCatching { request("progress") { put("handle",handle) }.getString("phase") }.getOrNull()
                    if(phase in listOf("completed","failed","cancelled")) { terminal = runCatching { request("release") { put("handle",handle) } }.isSuccess; break }
                    Thread.sleep(10)
                }
                released = released && terminal
            }
            replicas.forEach { handle -> runCatching { request("replica_close") { put("handle",handle) } } }
            check(released) { "Preserving encrypted fixture until native workers retire" }
            check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }
}
