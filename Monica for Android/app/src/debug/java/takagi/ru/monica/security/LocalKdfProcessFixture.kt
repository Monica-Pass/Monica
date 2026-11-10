package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Debug-only real-disk fixture shared by two app processes; never points at the user's store. */
internal class LocalKdfProcessFixture(private val base: Context, val id: String) {
    init { require(UUID.fromString(id).toString() == id) }
    val prefix = "kdf-process-$id-"
    val root = File(base.cacheDir, "kdf-process-$id")
    private val logicalStore = "monica_recovered_$id"
    private val alias = "monica_recovered_key_$id"
    private val aliasPrefix = "kdf-process-$id-"
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name.matches(Regex("[A-Za-z0-9_-]+")))
            // Android ContextWrapper does not relocate ContextImpl's preference directory.
            // These links map only owned names to the real XML, so production fresh() reads
            // actual disk bytes instead of the other process's stale SharedPreferences cache.
            val links = File(root, "shared_prefs").apply { check(isDirectory || mkdirs()) }
            for (suffix in listOf(".xml", ".xml.bak")) {
                val link = File(links, name + suffix)
                val target = File(base.applicationInfo.dataDir, "shared_prefs/$prefix$name$suffix")
                check(target.parentFile!!.canonicalFile == File(base.applicationInfo.dataDir, "shared_prefs").canonicalFile &&
                    target.name.startsWith(prefix) && link.parentFile!!.canonicalFile == links.canonicalFile)
                if (runCatching { Os.lstat(link.path) }.isFailure) {
                    runCatching { Os.symlink(target.path, link.path) }.getOrElse {
                        check(Os.readlink(link.path) == target.path)
                    }
                }
                check(Os.readlink(link.path) == target.path)
            }
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    val password = "Synthetic cross-process 密码🔑327"
    private val mdk = ByteArray(32) { (it + 0x10).toByte() }
    val recordFile get() = File(root, "record.ciphertext")
    val unknownValue = "Synthetic unknown protected value"

    fun seedLegacy() {
        check(!root.exists()) { "Never overwrite a fixture" }
        check(root.mkdirs())
        val directory = File(root, "files/local-vault-recovery").apply { check(mkdirs()) }
        File(directory, "active-store.json").writeText(JSONObject().put("id", id).toString())
        val opened = SecurePreferencesStore.openRaw(context, logicalStore, alias)
        val salt = ByteArray(16) { (it + 0x30).toByte() }
        val wrapSalt = ByteArray(32) { (it + 0x50).toByte() }
        fun derive(saltBytes: ByteArray): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), saltBytes, 100000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
        val hash = derive(salt)
        val wrappingKey = derive(wrapSalt)
        try {
            check(opened.preferences.edit().putString("master_password_hash", hex(hash))
                .putString("master_password_salt", hex(salt))
                .putString("mdk_password_blob", seal(wrappingKey, mdk, 0x70))
                .putString("mdk_password_salt", hex(wrapSalt)).putBoolean("mdk_ready", true)
                .putString("fixture_unknown", unknownValue).commit())
            recordFile.writeText("MDK|" + seal(mdk, "Synthetic cross-process record".toByteArray(), 0x90))
        } finally { hash.fill(0); wrappingKey.fill(0) }
    }

    fun freshManager(): SecurityManager {
        check(root.isDirectory && recordFile.isFile)
        SecurityManager.clearRuntimeUnlockCache()
        PreparedSecureStore.invalidate()
        return SecurityManager.openFresh(context).also { manager ->
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) {
                SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }
                    .set(manager, aliasPrefix + field)
            }
        }
    }

    fun verifyContents(manager: SecurityManager): Boolean =
        manager.getProtectedString("fixture_unknown") == unknownValue &&
            manager.decryptData(recordFile.readText()) == "Synthetic cross-process record"

    fun waitForOtherWriter(role: String) {
        require(role == "main" || role == "ime")
        File(root, "ready-$role").writeText("ready")
        val other = File(root, "ready-" + if (role == "main") "ime" else "main")
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!other.isFile) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Other synthetic writer did not reach barrier" }
            Thread.sleep(20)
        }
    }

    fun cleanup() {
        SecurityManager.clearRuntimeUnlockCache()
        PreparedSecureStore.invalidate()
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(alias, aliasPrefix + "KEY_ALIAS_DATA", aliasPrefix + "KEY_ALIAS_DATA_COMPAT").forEach {
            if (keystore.containsAlias(it)) keystore.deleteEntry(it)
        }
        File(base.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }
            .forEach { base.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
        // Remove owned links themselves; do not recursively traverse their targets.
        File(root, "shared_prefs").listFiles().orEmpty().forEach { check(it.delete()) }
        check(root.parentFile == base.cacheDir && root.name == "kdf-process-$id")
        root.deleteRecursively()
        mdk.fill(0)
    }

    private fun seal(key: ByteArray, bytes: ByteArray, start: Int): String {
        val nonce = ByteArray(12) { (start + it).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + cipher.doFinal(bytes), Base64.NO_WRAP)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
