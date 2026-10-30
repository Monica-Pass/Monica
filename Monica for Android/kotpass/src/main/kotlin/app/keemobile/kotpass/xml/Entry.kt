package app.keemobile.kotpass.xml

import app.keemobile.kotpass.builders.MutableEntry
import app.keemobile.kotpass.builders.buildEntry
import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getBytes
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.getUuid
import app.keemobile.kotpass.extensions.toXmlString
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal fun unmarshalEntry(
    context: XmlContext.Decode,
    node: Node
): Entry {
    val untitledFields = mutableListOf<EntryValue>()
    val uuid = node
        .firstOrNull(Tags.Uuid)
        ?.getUuid()
        ?: throw FormatError.InvalidXml("Invalid entry without Uuid.")

    return buildEntry(uuid) {
        for (childNode in node.childNodes()) {
            when (childNode.nodeName) {
                Tags.Entry.IconId -> {
                    icon = childNode
                        .getText()
                        ?.toInt()
                        ?.let(PredefinedIcon.entries::getOrNull)
                        ?: PredefinedIcon.Key
                }
                Tags.Entry.CustomIconId -> {
                    customIconUuid = childNode.getUuid()
                }
                Tags.Entry.ForegroundColor -> {
                    foregroundColor = childNode.getText()
                }
                Tags.Entry.BackgroundColor -> {
                    backgroundColor = childNode.getText()
                }
                Tags.Entry.OverrideUrl -> {
                    overrideUrl = childNode.getText() ?: ""
                }
                Tags.TimeData.TagName -> {
                    times = unmarshalTimeData(childNode)
                }
                Tags.Entry.AutoType.TagName -> {
                    autoType = unmarshalAutoTypeData(childNode)
                }
                Tags.Entry.Fields.TagName -> {
                    val (name, value) = unmarshalField(context, childNode)

                    if (name != null) {
                        fields[name] = value
                    } else {
                        untitledFields += value
                    }
                }
                Tags.Entry.Tags -> {
                    childNode
                        .getText()
                        ?.split(Const.TagsSeparatorsRegex)
                        ?.forEach(tags::add)
                }
                Tags.Entry.BinaryReferences.TagName -> {
                    unmarshalBinaryReference(context, childNode)?.let(binaries::add)
                }
                Tags.Entry.History -> {
                    history = unmarshalEntries(context, childNode).toMutableList()
                }
                Tags.CustomData.TagName -> {
                    customData = CustomData.unmarshal(childNode).toMutableMap()
                }
                Tags.Entry.PreviousParentGroup -> {
                    previousParentGroup = childNode.getUuid()
                }
                Tags.Entry.QualityCheck -> {
                    qualityCheck = childNode.getText()?.toBoolean() ?: true
                }
            }
        }

        if (untitledFields.isNotEmpty()) {
            recoverUntitledFields(context, untitledFields)
        }
    }
}

/**
 * Recovers up to [UInt.MAX_VALUE] untitled fields.
 */
private fun MutableEntry.recoverUntitledFields(
    context: XmlContext.Decode,
    untitledFields: List<EntryValue>
) {
    for (value in untitledFields) {
        var n = 1U
        var name = context.untitledLabel

        while (name in fields) {
            name = "${context.untitledLabel} ($n)"
            n++

            if (n == UInt.MAX_VALUE) return
        }

        fields[name] = value
    }
}

internal fun unmarshalEntries(
    context: XmlContext.Decode,
    node: Node
): List<Entry> = node
    .childNodes()
    .filter { it.nodeName == Tags.Entry.TagName }
    .map { unmarshalEntry(context, it) }

private fun unmarshalField(
    context: XmlContext.Decode,
    node: Node
): Pair<String?, EntryValue> {
    val key = node
        .firstOrNull(Tags.Entry.Fields.ItemKey)
        ?.getText()
    val protected = node
        .firstOrNull(Tags.Entry.Fields.ItemValue)
        ?.get<String?>(FormatXml.Attributes.Protected)
        .toBoolean()

    return if (protected) {
        val bytes = node
            .firstOrNull(Tags.Entry.Fields.ItemValue)
            ?.getBytes()
            ?: ByteArray(0)
        val salt = context.encryption.getSalt(bytes.size)

        key to EntryValue.Encrypted(EncryptedValue(bytes, salt))
    } else {
        val text = node
            .firstOrNull(Tags.Entry.Fields.ItemValue)
            ?.getText()
            ?: ""

        key to EntryValue.Plain(text)
    }
}

internal fun Entry.marshal(
    context: XmlContext.Encode
): Node = node(Tags.Entry.TagName) {
    Tags.Uuid { addUuid(uuid) }
    Tags.Entry.IconId { text(icon.ordinal.toString()) }
    if (customIconUuid != null) {
        Tags.Entry.CustomIconId { addUuid(customIconUuid) }
    }
    Tags.Entry.ForegroundColor { foregroundColor?.let(::text) }
    Tags.Entry.BackgroundColor { backgroundColor?.let(::text) }
    Tags.Entry.OverrideUrl { text(overrideUrl) }
    Tags.Entry.Tags { text(tags.joinToString(Const.TagsSeparator)) }
    if (context.version.isAtLeast(4, 1)) {
        Tags.Entry.QualityCheck { addBoolean(qualityCheck) }
    }
    if (context.version.isAtLeast(4, 1) && previousParentGroup != null) {
        Tags.Entry.PreviousParentGroup { addUuid(previousParentGroup) }
    }
    if (times != null) {
        addElement(times.marshal(context))
    }
    marshalFields(context, fields).forEach {
        addElement(it)
    }
    binaries.forEach {
        addElement(it.marshal(context))
    }
    if (customData.isNotEmpty()) {
        addElement(CustomData.marshal(context, customData))
    }
    if (autoType != null) {
        addElement(autoType.marshal())
    }
    if (history.isNotEmpty()) {
        Tags.Entry.History {
            history.forEach { addElement(it.marshal(context)) }
        }
    }
}

private fun marshalFields(
    context: XmlContext.Encode,
    fields: Map<String, EntryValue>
): List<Node> {
    return fields.map { (key, value) ->
        node(Tags.Entry.Fields.TagName) {
            Tags.Entry.Fields.ItemKey { text(key) }
            Tags.Entry.Fields.ItemValue {
                val isProtected = value is EntryValue.Encrypted

                when {
                    isProtected && context.isXmlExport -> {
                        attribute(
                            FormatXml.Attributes.ProtectedInMemPlainXml,
                            isProtected.toXmlString()
                        )
                        text(value.content)
                    }
                    isProtected -> {
                        val encryptedContent = context
                            .encryption
                            .processBytes(value.content.toByteArray())

                        attribute(
                            FormatXml.Attributes.Protected,
                            isProtected.toXmlString()
                        )
                        addBytes(encryptedContent)
                    }
                    else -> {
                        text(value.content)
                    }
                }
            }
        }
    }
}
