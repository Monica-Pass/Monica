package app.keemobile.kotpass.constants

import app.keemobile.kotpass.xml.FormatXml

enum class MemoryProtectionFlag(val value: String) {
    Title(FormatXml.Tags.Meta.MemoryProtection.ProtectTitle),
    UserName(FormatXml.Tags.Meta.MemoryProtection.ProtectUserName),
    Password(FormatXml.Tags.Meta.MemoryProtection.ProtectPassword),
    Url(FormatXml.Tags.Meta.MemoryProtection.ProtectUrl),
    Notes(FormatXml.Tags.Meta.MemoryProtection.ProtectNotes)
}
