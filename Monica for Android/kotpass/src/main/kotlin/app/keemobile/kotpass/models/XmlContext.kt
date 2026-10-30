package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import okio.ByteString

sealed class XmlContext {
    abstract val version: FormatVersion

    class Encode(
        override val version: FormatVersion,
        val encryption: EncryptionSaltGenerator,
        val binaries: Map<ByteString, BinaryData>,
        val isXmlExport: Boolean = false
    ) : XmlContext()

    class Decode(
        override val version: FormatVersion,
        val encryption: EncryptionSaltGenerator,
        val binaries: Map<ByteString, BinaryData>,
        val untitledLabel: String = Defaults.UntitledLabel
    ) : XmlContext()
}
