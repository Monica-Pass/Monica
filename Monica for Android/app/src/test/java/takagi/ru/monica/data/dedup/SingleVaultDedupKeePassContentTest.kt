package takagi.ru.monica.data.dedup

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.keepass.*
import takagi.ru.monica.data.model.ProjectCredentialGroup

class SingleVaultDedupKeePassContentTest {
    private val instant = Instant.parse("2026-11-06T00:00:00Z")
    private fun entry(title: String = "Synthetic", uuid: UUID = UUID.randomUUID()) = Entry(uuid,
        fields = EntryFields.of("Title" to EntryValue.Plain(title), "UserName" to EntryValue.Plain("user"),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("password")), "URL" to EntryValue.Plain("https://example.invalid"), "Notes" to EntryValue.Plain("")),
        times = TimeData(instant, instant, instant, instant, null))
    private fun database(vararg entries: Entry): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Synthetic", meta = Meta(recycleBinEnabled = true), credentials = Credentials.from(EncryptedValue.fromString("test-only")))
        .modifyParentGroup { copy(entries = entries.toList()) }
    private fun session(db: KeePassDatabase) = KeePassNativeSessionBuilder.build(1, KeePassSourceRevision("synthetic", 1), db) { parent, name ->
        if (parent == null) name else "$parent/$name"
    }

    @Test fun nativeEntryCodecCoversEveryPropertyAndPreservesAllValues() {
        val history = entry().copy(autoType = AutoTypeData(true, AutoTypeObfuscation.UseClipboard, "{USERNAME}{TAB}{PASSWORD}", listOf(AutoTypeItem("window", "sequence"))))
        val original = entry().copy(customIconUuid = UUID.randomUUID(), foregroundColor = "#102030", backgroundColor = "#405060", overrideUrl = "cmd://original",
            history = listOf(history), tags = listOf("tag:one", "tag-two"), customData = mapOf("extension" to CustomDataValue("\u0001\r\n", instant)),
            previousParentGroup = UUID.randomUUID(), qualityCheck = false)
        val encoded = SingleVaultDedupKeePassContent.entry(original)
        assertTrue(KeePassLosslessTransfer.entriesEquivalent(original, SingleVaultDedupKeePassContent.decodeEntry(encoded)))
        val properties = Entry::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        assertEquals(properties, encoded.keys)
    }

    @Test fun payloadRoundTripPreservesBinaryBytesProtectionCompressionIconsAndHistory() {
        val binary = BinaryData.Uncompressed(true, byteArrayOf(1, 0, -1, 4)).toCompressed()
        val iconId = UUID.randomUUID(); val icon = CustomIcon(byteArrayOf(9, 8, 7), "Icon", instant)
        val original = entry().copy(customIconUuid = iconId, binaries = listOf(BinaryReference(binary.hash, "same-name")),
            history = listOf(entry().copy(binaries = listOf(BinaryReference(binary.hash, "historic-name")))))
        val db = database(original).modifyBinaries { mapOf(binary.hash to binary) }.modifyCustomIcons { mapOf(iconId to icon) }
        val encoded = SingleVaultDedupKeePassContent.payload(db, original.uuid)
        assertTrue(SingleVaultDedupKeePassContent.equivalentPayload(KeePassLosslessTransfer.captureEntry(db, original.uuid), SingleVaultDedupKeePassContent.decodePayload(encoded)))
    }

    @Test fun nativeCustomFieldSubsetIsComparedWithoutRoomProjection() {
        val a = entry().copy(fields = entry().fields + ("extension" to EntryValue.Encrypted(EncryptedValue.fromString("full"))))
        val b = entry()
        val projects = SingleVaultDedupKeePassContent.projects(session(database(a, b)), false, true) { }
        assertEquals(2, projects.size)
        assertEquals("password:2", SingleVaultDedupComparator.analyze(projects).candidates.single().candidateKey)
        val different = b.copy(fields = b.fields + ("extension" to EntryValue.Plain("full")))
        assertTrue(SingleVaultDedupComparator.analyze(SingleVaultDedupKeePassContent.projects(session(database(a, different)), false, true) { }).candidates.isEmpty())
    }

    @Test fun nativePasskeyTypeFidoFieldsAndHistoricalCredentialsNeverBecomeCandidates() {
        val ordinary = entry()
        val passkeyFields = listOf(
            ordinary.fields + ("MonicaItemType" to EntryValue.Plain("PASSKEY")),
            ordinary.fields + ("FIDO2_CREDENTIAL_ID" to EntryValue.Plain("synthetic-id")),
            ordinary.fields + ("KPEX_PASSKEY_PRIVATE_KEY_PEM" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-key"))))
        for (fields in passkeyFields) {
            val keyEntry = ordinary.copy(fields = fields)
            for (current in listOf(keyEntry, ordinary.copy(history = listOf(keyEntry)))) {
                val native = database(current, current.copy(uuid = UUID.randomUUID()))
                val before = native.content.group.entries.map(SingleVaultDedupKeePassContent::entry)
                val projects = SingleVaultDedupKeePassContent.projects(session(native), false, true) { }
                assertEquals(2, projects.size)
                assertTrue(projects.all { SingleVaultDedupBlockReason.PASSKEY_PRESERVED in it.blockers })
                assertTrue(SingleVaultDedupComparator.analyze(projects).candidates.isEmpty())
                assertEquals(before, native.content.group.entries.map(SingleVaultDedupKeePassContent::entry))
            }
        }
    }

    @Test fun uniqueHistoryOrAttachmentBytesPreventNativeDeletion() {
        val a = entry().copy(history = listOf(entry("Old one"))); val b = entry().copy(history = listOf(entry("Old two")))
        assertTrue(SingleVaultDedupComparator.analyze(SingleVaultDedupKeePassContent.projects(session(database(a, b)), false, true) { }).candidates.isEmpty())
        val one = BinaryData.Uncompressed(true, byteArrayOf(1)); val two = BinaryData.Uncompressed(true, byteArrayOf(2))
        val db = database(a.copy(binaries = listOf(BinaryReference(one.hash, "same"))), b.copy(history = emptyList(), binaries = listOf(BinaryReference(two.hash, "same"))))
            .modifyBinaries { mapOf(one.hash to one, two.hash to two) }
        assertTrue(SingleVaultDedupComparator.analyze(SingleVaultDedupKeePassContent.projects(session(db), false, true) { }).candidates.isEmpty())
    }

    @Test fun missingNativeResourcesUnknownXmlAndInboundReferencesAreNotComplete() {
        val a = entry(); val b = entry().copy(fields = entry().fields + ("reference" to EntryValue.Plain("{REF:P@T:Synthetic}")))
        val projects = SingleVaultDedupKeePassContent.projects(session(database(a, b)), false, true) { }
        assertTrue(projects.all { SingleVaultDedupBlockReason.EXTERNAL_REFERENCE in it.blockers })
        assertTrue(SingleVaultDedupKeePassContent.projects(session(database(a)), false, false) { }.single().blockers.contains(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT))
        val missing = a.copy(customIconUuid = UUID.randomUUID())
        assertTrue(SingleVaultDedupKeePassContent.projects(session(database(missing)), false, true) { }.single().blockers.contains(SingleVaultDedupBlockReason.UNREADABLE_CONTENT))
    }

    @Test fun recycleAndSelectiveRecoveryPreserveUnrelatedLaterEditsAndAreIdempotent() {
        val a = entry(); val unrelated = entry("Unrelated")
        val originalDb = database(a, unrelated)
        val original = KeePassLosslessTransfer.captureEntry(originalDb, a.uuid)
        val recycled = KeePassNativeManagement.deleteEntries(originalDb, setOf(a.uuid), KeePassNativeDeleteMode.RECYCLE_BIN)
        val retired = KeePassLosslessTransfer.captureEntry(recycled, a.uuid)
        val changedUnrelated = unrelated.copy(fields = unrelated.fields + ("Notes" to EntryValue.Plain("later edit")))
        val edited = recycled.modifyParentGroup { copy(entries = listOf(changedUnrelated)) }
        val restored = SingleVaultDedupKeePassAdapter.restoreSelected(edited, listOf(original), listOf(retired))
        assertTrue(SingleVaultDedupKeePassContent.equivalentPayload(original, KeePassLosslessTransfer.captureEntry(restored, a.uuid)))
        assertTrue(KeePassLosslessTransfer.entriesEquivalent(changedUnrelated, KeePassLosslessTransfer.captureEntry(restored, unrelated.uuid).entry))
        assertSame(restored, SingleVaultDedupKeePassAdapter.restoreSelected(restored, listOf(original), listOf(retired)))
    }

    @Test fun recoveryRefusesChangedRecycledEntryOrMissingOriginalParent() {
        val a = entry(); val originalDb = database(a)
        val original = KeePassLosslessTransfer.captureEntry(originalDb, a.uuid)
        val recycled = KeePassNativeManagement.deleteEntries(originalDb, setOf(a.uuid), KeePassNativeDeleteMode.RECYCLE_BIN)
        val retired = KeePassLosslessTransfer.captureEntry(recycled, a.uuid)
        val edited = recycled.modifyParentGroup { copy(groups = groups.map { group -> group.copy(entries = group.entries.map {
            it.copy(fields = it.fields + ("Notes" to EntryValue.Plain("changed after cleanup")))
        }) }) }
        assertThrows(IllegalArgumentException::class.java) { SingleVaultDedupKeePassAdapter.restoreSelected(edited, listOf(original), listOf(retired)) }
        val deleted = KeePassLosslessTransfer.removeEntry(recycled, a.uuid)
        assertThrows(IllegalArgumentException::class.java) { SingleVaultDedupKeePassAdapter.restoreSelected(deleted, listOf(original), listOf(retired)) }
        val reordered = recycled.modifyParentGroup { copy(groups = groups.map { group -> group.copy(entries = group.entries.map {
            it.copy(fields = EntryFields(it.fields.entries.reversed().associate { field -> field.toPair() }))
        }) }) }
        assertThrows(IllegalArgumentException::class.java) { SingleVaultDedupKeePassAdapter.restoreSelected(reordered, listOf(original), listOf(retired)) }
    }

    @Test fun xmlAuditRejectsUnknownNodesAttributesAndRepeatedFields() {
        fun xml(extra: String = "") = """<KeePassFile><Meta/><Root><Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Entry><UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID><String><Key>Title</Key><Value>title</Value></String><String><Key>UserName</Key><Value>user</Value></String><String><Key>Password</Key><Value Protected="True">c2VjcmV0</Value></String><String><Key>URL</Key><Value>url</Value></String><String><Key>Notes</Key><Value/></String>$extra</Entry></Group></Root></KeePassFile>""".toByteArray()
        assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml()))
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(xml("<PluginSecret>x</PluginSecret>")))
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(xml("<String><Key>Password</Key><Value>different</Value></String>")))
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(String(xml()).replace("<Value/>", "<Value Future=\"true\"/>").toByteArray()))
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(xml("<QualityCheck>unknown</QualityCheck>")))
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(String(xml()).replace("</Root>", "<Group><UUID>AgAAAAAAAAAAAAAAAAAAAA==</UUID></Group></Root>").toByteArray()))
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(String(xml()).replace("<Value/>", "<Value ProtectInMemory=\"True\"/>").toByteArray()))
    }

    @Test fun inheritedReferencesAndUuidBehaviorBlockAndLocalPlaceholderFieldsStayWhole() {
        val a = entry(); val b = entry()
        for (sequence in listOf("{REF:P@T:Synthetic}", "{UUID}")) {
            val db = database(a, b).modifyParentGroup { copy(defaultAutoTypeSequence = sequence) }
            assertTrue(SingleVaultDedupKeePassContent.projects(session(db), false, true) { }.all { SingleVaultDedupBlockReason.EXTERNAL_REFERENCE in it.blockers })
        }
        val extra = b.copy(fields = b.fields + ("extra" to EntryValue.Plain("value")))
        val dynamic = database(a, extra).modifyParentGroup { copy(defaultAutoTypeSequence = "{S:extra}") }
        assertTrue(SingleVaultDedupComparator.analyze(SingleVaultDedupKeePassContent.projects(session(dynamic), false, true) { }).candidates.isEmpty())
    }

    @Test fun fullRoundTripProofIncludesUnselectedGroupsAndEntries() {
        val db = database(entry(), entry("Unselected")).modifyParentGroup { copy(tags = listOf("group-tag")) }
        assertTrue(SingleVaultDedupKeePassContent.roundTripEquivalent(db, db))
        assertFalse(SingleVaultDedupKeePassContent.roundTripEquivalent(db, db.modifyParentGroup { copy(tags = emptyList()) }))
        assertFalse(SingleVaultDedupKeePassContent.roundTripEquivalent(db, db.modifyParentGroup { copy(entries = entries.dropLast(1)) }))
    }

    @Test fun xmlAuditRejectsTruncatedOptionalUuidsAndBinaryTimestamps() {
        fun xml(meta: String = "", group: String = "") = "<KeePassFile><Meta>$meta</Meta><Root><Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>$group</Group></Root></KeePassFile>".toByteArray()
        val uuid = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        val longUuid = java.util.Base64.getEncoder().encodeToString(ByteArray(17))
        for (tag in listOf("RecycleBinUUID", "EntryTemplatesGroup", "LastSelectedGroup", "LastTopVisibleGroup")) {
            assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml(meta = "<$tag/>")))
            assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml(meta = "<$tag>$uuid</$tag>")))
            assertFalse(SingleVaultDedupKeePassXmlAudit.audit(xml(meta = "<$tag>$longUuid</$tag>")))
        }
        for (tag in listOf("CustomIconUUID", "PreviousParentGroup", "LastTopVisibleEntry")) {
            assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml(group = "<$tag/>")))
            assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml(group = "<$tag>$uuid</$tag>")))
            assertFalse(SingleVaultDedupKeePassXmlAudit.audit(xml(group = "<$tag>$longUuid</$tag>")))
        }
        for (time in listOf("AAAAAAAAAAA=", "2026-11-06T00:00:00Z", "")) {
            assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml(group = "<Times><CreationTime>$time</CreationTime></Times>")))
        }
        for (time in listOf("AAAAAAAAAAAA", "AAAAAAAAAAAAAA==", "2026-11-06T00:00:00.100Z", "invalid")) {
            assertFalse(SingleVaultDedupKeePassXmlAudit.audit(xml(group = "<Times><CreationTime>$time</CreationTime></Times>")))
        }
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(String(xml()).replace("<UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>", "<UUID/>").toByteArray()))
    }

    @Test fun actualNativeWriterOptionalNullUuidsRemainCompleteWithoutAcceptingMalformedIdentity() {
        val source = database(entry()) as KeePassDatabase.Ver4x
        val context = XmlContext.Encode(source.header.version,
            EncryptionSaltGenerator.create(source.innerHeader.randomStreamId, source.innerHeader.randomStreamKey), source.binaries)
        val xml = KeePassXmlContentParser.marshalContent(context, source.content, true)
        assertTrue("The normal writer must emit its nullable metadata references", xml.contains("<EntryTemplatesGroup"))
        assertTrue(SingleVaultDedupKeePassXmlAudit.audit(xml.toByteArray()))
        val malformed = Regex("<EntryTemplatesGroup(?:\\s*/>|>\\s*</EntryTemplatesGroup>)").replace(xml,
            "<EntryTemplatesGroup>AAAAAAAAAAAAAAAAAAAAAAA=</EntryTemplatesGroup>")
        assertNotEquals(xml, malformed)
        assertFalse(SingleVaultDedupKeePassXmlAudit.audit(malformed.toByteArray()))
    }

    @Test fun permanentlyDeletedOriginalRestoresOneStableCopyWithoutChangingKeeperOrTombstones() {
        val removed = entry(); val keeper = entry("Keeper"); val source = database(removed, keeper)
        val original = KeePassLosslessTransfer.captureEntry(source, removed.uuid)
        val recycled = KeePassNativeManagement.deleteEntries(source, setOf(removed.uuid), KeePassNativeDeleteMode.RECYCLE_BIN)
        val retired = KeePassLosslessTransfer.captureEntry(recycled, removed.uuid)
        val deleted = KeePassLosslessTransfer.removeEntry(recycled, removed.uuid)
        val copyId = SingleVaultDedupKeePassAdapter.recoveryCopyId(UUID.randomUUID().toString(), removed.uuid)
        val ids = mapOf(removed.uuid to copyId)
        val plan = SingleVaultDedupKeePassAdapter.restorePlan(deleted, listOf(original), listOf(retired), ids)
        assertEquals(1, plan.copyCount)
        assertEquals(deleted.content.deletedObjects, plan.database.content.deletedObjects)
        assertTrue(plan.database.content.deletedObjects.any { it.id == removed.uuid })
        assertTrue(SingleVaultDedupKeePassContent.equivalentPayload(original.copy(entry = original.entry.copy(uuid = copyId)), KeePassLosslessTransfer.captureEntry(plan.database, copyId)))
        assertEquals(SingleVaultDedupKeePassContent.payload(source, keeper.uuid), SingleVaultDedupKeePassContent.payload(plan.database, keeper.uuid))
        assertSame(plan.database, SingleVaultDedupKeePassAdapter.restoreSelected(plan.database, listOf(original), listOf(retired), ids))
        val changedCopy = plan.database.modifyParentGroup { copy(entries = entries.map { if (it.uuid == copyId) it.copy(fields = it.fields + ("Notes" to EntryValue.Plain("later"))) else it }) }
        assertThrows(IllegalArgumentException::class.java) { SingleVaultDedupKeePassAdapter.restoreSelected(changedCopy, listOf(original), listOf(retired), ids) }
        val deletedCopy = KeePassLosslessTransfer.removeEntry(plan.database, copyId)
        assertThrows(IllegalArgumentException::class.java) { SingleVaultDedupKeePassAdapter.restoreSelected(deletedCopy, listOf(original), listOf(retired), ids) }
    }

    @Test fun credentialMetadataJsonDoesNotActAsANativeTemplateButRealFieldTemplatesStillDo() {
        fun project(): List<Entry> {
            val projectId = UUID.randomUUID().toString()
            return ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "user",
                passwords = listOf(ProjectCredentialGroup.Password(value = "one"), ProjectCredentialGroup.Password(value = "two"))))).map { row ->
                entry().let { it.copy(fields = it.fields + mapOf("Password" to EntryValue.Plain(row.password.value),
                    "MonicaPasswordGroupId" to EntryValue.Plain(projectId), ProjectCredentialGroup.FIELD to EntryValue.Plain(row.metadata.forProject(projectId).raw.toString()))) }
            }
        }
        val entries = project() + project()
        val projects = SingleVaultDedupKeePassContent.projects(session(database(*entries.toTypedArray())), false, true) { }
        assertEquals(2, projects.size)
        assertEquals(1, SingleVaultDedupComparator.analyze(projects).candidates.size)
        val dynamic = entries.map { it.copy(fields = it.fields + ("template" to EntryValue.Plain("{S:MonicaPasswordGroupId}"))) }
        assertTrue(SingleVaultDedupComparator.analyze(SingleVaultDedupKeePassContent.projects(session(database(*dynamic.toTypedArray())), false, true) { }).candidates.isEmpty())
    }
}
