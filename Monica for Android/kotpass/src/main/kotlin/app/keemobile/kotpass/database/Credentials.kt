package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.errors.KeyfileError
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.sha256
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.io.encodeHex
import app.keemobile.kotpass.xml.KeyfileXml
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.PrintOptions
import org.redundent.kotlin.xml.XmlVersion
import org.redundent.kotlin.xml.parse
import org.redundent.kotlin.xml.xml
import java.io.ByteArrayInputStream

private const val XmlEncoding = "utf-8"
private const val DefaultVersion = "2.0"

private val SpacesPattern = Regex("\\s+")

class Credentials private constructor(
    val passphrase: EncryptedValue?,
    val key: EncryptedValue?
) {
    companion object {
        fun from(passphrase: EncryptedValue) = Credentials(
            passphrase = EncryptedValue.fromBinary(passphrase.getHash()),
            key = null
        )

        fun from(keyData: ByteArray) = Credentials(
            passphrase = null,
            key = EncryptedValue.fromBinary(parseKeyfile(keyData))
        )

        fun from(passphrase: EncryptedValue, keyData: ByteArray) = Credentials(
            passphrase = EncryptedValue.fromBinary(passphrase.getHash()),
            key = EncryptedValue.fromBinary(parseKeyfile(keyData))
        )

        fun createKeyfile(key: ByteArray): String {
            val hash = key.sha256()
                .sliceArray(0 until 4)
                .encodeHex()
                .uppercase()

            return xml(KeyfileXml.Tags.Document, XmlEncoding, XmlVersion.V10) {
                KeyfileXml.Tags.Meta {
                    KeyfileXml.Tags.Version {
                        text(DefaultVersion)
                    }
                }
                KeyfileXml.Tags.Key {
                    KeyfileXml.Tags.Data {
                        attribute(KeyfileXml.Attributes.Hash, hash)
                        text(key.encodeHex().uppercase())
                    }
                }
            }.toString(PrintOptions(singleLineTextElements = true))
        }

        private fun parseKeyfile(keyData: ByteArray): ByteArray {
            return when (keyData.size) {
                32 -> keyData
                64 -> {
                    keyData
                        .toString(Charsets.UTF_8)
                        .lowercase()
                        .decodeHexToArray()
                }
                else -> {
                    parseXmlKeyfile(keyData)
                        ?.let(::findXmlKeyData)
                        ?: keyData.sha256() // Use raw binary data as keyfile
                }
            }
        }

        private fun parseXmlKeyfile(keyData: ByteArray): Node? = try {
            parse(ByteArrayInputStream(keyData))
        } catch (e: Exception) {
            null
        }

        private fun findXmlKeyData(node: Node): ByteArray {
            val version = node
                .firstOrNull(KeyfileXml.Tags.Meta)
                ?.firstOrNull(KeyfileXml.Tags.Version)
                ?.getText()
                ?.toFloatOrNull()
                ?: throw KeyfileError.InvalidVersion()
            val dataNode = node
                .firstOrNull(KeyfileXml.Tags.Key)
                ?.firstOrNull(KeyfileXml.Tags.Data)
                ?: throw KeyfileError.NoKeyData()

            return when (version) {
                1.0f -> {
                    dataNode.getText()
                        ?.decodeBase64ToArray()
                        ?: throw KeyfileError.NoKeyData()
                }
                2.0f -> {
                    val hash = dataNode.get<String?>(KeyfileXml.Attributes.Hash)
                        ?.decodeHexToArray()
                        ?: throw KeyfileError.InvalidHash()
                    dataNode.getText()
                        ?.replace(SpacesPattern, "")
                        ?.decodeHexToArray()
                        ?.also { data ->
                            if (!data.sha256().sliceArray(0 until 4).contentEquals(hash)) {
                                throw KeyfileError.InvalidHash()
                            }
                        }
                        ?: throw KeyfileError.NoKeyData()
                }
                else -> throw KeyfileError.InvalidVersion()
            }
        }
    }
}
