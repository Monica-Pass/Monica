package app.keemobile.kotpass.constants

internal enum class HeaderFieldId {
    EndOfHeader,

    @Deprecated("No longer supported.")
    Comment,
    CipherId,
    Compression,
    MasterSeed,
    TransformSeed,
    TransformRounds,
    EncryptionIV,
    InnerRandomStreamKey,
    StreamStartBytes,
    InnerRandomStreamId,
    KdfParameters,
    PublicCustomData
}
