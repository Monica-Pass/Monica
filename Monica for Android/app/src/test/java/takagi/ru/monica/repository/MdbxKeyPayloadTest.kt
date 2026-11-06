package takagi.ru.monica.repository

import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class MdbxKeyPayloadTest {
    @Test fun physicalIdentityMatchesPortableMd5Vector() {
        assertEquals("48f94648-e2a0-3d3d-91b8-77ff9895b136", mdbx2PhysicalEntryId("11111111-1111-1111-1111-111111111111", "password:22222222-2222-2222-2222-222222222222"))
    }
    @Test fun missingAndNullPreserveButExplicitEmptyClears() {
        assertEquals("existing", JSONObject().readMdbxSshKeyData("existing"))
        assertEquals("existing", JSONObject().put("ssh_key_data", JSONObject.NULL).readMdbxSshKeyData("existing"))
        assertEquals("", JSONObject().put("ssh_key_data", "").readMdbxSshKeyData("existing"))
    }
    @Test fun aliasesObjectsAndPrecedence() {
        assertEquals("camel", JSONObject().put("sshKeyData", "camel").readMdbxSshKeyData())
        assertEquals("snake", JSONObject().put("sshKeyData", "camel").put("ssh_key_data", "snake").readMdbxSshKeyData())
        assertEquals("RSA", JSONObject(JSONObject().put("ssh_key_data", JSONObject().put("algorithm", "RSA")).readMdbxSshKeyData()).getString("algorithm"))
        assertTrue(runCatching { JSONObject().put("ssh_key_data", 42).readMdbxSshKeyData() }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun editRetainsSchemaExtensionsAndPrivateNewline() {
        val original = Json.parseToJsonElement("""{"algorithm":"ED25519","schema":"future-schema","privateKeyOpenSsh":"synthetic\n","extension":{"nested":[1,true]}}""").jsonObject
        val decoded = checkNotNull(SshKeyDataCodec.decode(original.toString()))
        val edited = Json.parseToJsonElement(SshKeyDataCodec.encode(decoded.copy(comment = "edited"))).jsonObject
        assertEquals(original["extension"], edited["extension"])
        assertEquals(original["schema"], edited["schema"])
        assertEquals("synthetic\n", checkNotNull(SshKeyDataCodec.decode(edited.toString())).privateKeyOpenSsh)
        assertEquals(SshKeyData.SCHEMA_V1, checkNotNull(SshKeyDataCodec.decode("""{"algorithm":"RSA"}""")).schema)
    }
    @Test fun generatedEd25519AndRsaAreReadableByOpenSsh() {
        val executable = java.io.File("C:/Windows/System32/OpenSSH/ssh-keygen.exe")
        org.junit.Assume.assumeTrue("Requires installed OpenSSH", executable.isFile)
        val directory = java.nio.file.Files.createTempDirectory("monica-ssh-interop").toFile()
        fun run(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            check(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { "OpenSSH timeout" }
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        }
        try {
            for (request in listOf(takagi.ru.monica.utils.SshKeyGenerator.Request.Ed25519, takagi.ru.monica.utils.SshKeyGenerator.Request.Rsa(2048))) {
                val key = takagi.ru.monica.utils.SshKeyGenerator.generate(request, "interop")
                val privateFile = java.io.File(directory, "key")
                val publicFile = java.io.File(directory, "key.pub")
                privateFile.writeText(key.privateKeyOpenSsh)
                publicFile.writeText(key.publicKeyOpenSsh + "\n")
                val account = System.getenv("USERDOMAIN") + "\\" + System.getenv("USERNAME")
                assertEquals(0, run("icacls", privateFile.absolutePath, "/inheritance:r", "/grant:r", "$account:F").first)
                val publicResult = run(executable.absolutePath, "-y", "-f", privateFile.absolutePath)
                assertEquals("OpenSSH must load generated private key", 0, publicResult.first)
                assertEquals(key.publicKeyOpenSsh.split(" ")[1], publicResult.second.trim().split(" ")[1])
                val fingerprint = run(executable.absolutePath, "-E", "sha256", "-lf", publicFile.absolutePath)
                assertEquals(0, fingerprint.first)
                assertTrue(fingerprint.second.contains(key.fingerprintSha256))
            }
        } finally { directory.deleteRecursively() }
    }
}
