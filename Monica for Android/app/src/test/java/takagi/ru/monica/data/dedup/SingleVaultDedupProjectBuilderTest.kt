package takagi.ru.monica.data.dedup

import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.*

class SingleVaultDedupProjectBuilderTest {
    private fun entry(id: Long = 1) = PasswordEntry(id = id, title = "Synthetic", website = "https://example.invalid", username = "user", password = "secret")
    private fun fields(id: Long, drafts: List<CustomFieldDraft>) = drafts.mapIndexed { index, it -> CustomField(index + 1L, id, it.title, it.value, it.isProtected, index) }
    private fun build(entries: List<PasswordEntry>, fields: Map<Long, List<CustomField>> = emptyMap(),
        evidence: SingleVaultDedupEvidence = SingleVaultDedupEvidence(true, entries.map { it.id }.toSet()),
        decrypt: (String) -> String = { it }) = SingleVaultDedupProjectBuilder.build("local", entries, fields, decrypt, evidence)
    private fun analyze(entries: List<PasswordEntry>, fields: Map<Long, List<CustomField>> = emptyMap()) = SingleVaultDedupComparator.analyze(build(entries, fields))
    private fun grouped(firstId: Long, passwords: List<String>, username: String = "user"): Pair<List<PasswordEntry>, Map<Long, List<CustomField>>> {
        val project = UUID.randomUUID().toString()
        val group = ProjectCredentialGroup.Group(username = username, passwords = passwords.map { ProjectCredentialGroup.Password(value = it) })
        val rows = ProjectCredentialGroup.rows(listOf(group))
        return rows.mapIndexed { index, row -> entry(firstId + index).copy(passwordGroupId = project, password = row.password.value, username = row.username) } to
            rows.mapIndexed { index, row -> firstId + index to fields(firstId + index, listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = row.metadata.forProject(project).raw.toString()))) }.toMap()
    }

    @Test fun explicitPasswordGroupsImplementAbcAndNeverGroupByTitleAlone() {
        val a = grouped(1, listOf("1", "2", "3")); val b = grouped(10, listOf("1", "2")); val c = grouped(20, listOf("2", "3", "4"))
        val projects = build(a.first + b.first + c.first, a.second + b.second + c.second)
        val result = SingleVaultDedupComparator.analyze(projects)
        assertEquals(3, projects.size)
        assertEquals(1, result.candidates.size)
        assertEquals(setOf(10L, 11L), projects.single { it.summary.key == result.candidates.single().candidateKey }.summary.memberIds.toSet())
        assertEquals(setOf(1L, 2L, 3L), projects.single { it.summary.key == result.candidates.single().keeperKey }.summary.memberIds.toSet())
        assertEquals(2, build(listOf(entry(50), entry(51))).size)
    }

    @Test fun passkeyBindingsAndInboundEvidenceRetainEveryMemberOfTheProject() {
        val grouped = grouped(1, listOf("one", "two"))
        val direct = grouped.first.map { if (it.id == 2L) it.copy(passkeyBindings = "unknown-or-legacy-binding") else it }
        val project = build(direct, grouped.second).single()
        assertEquals(listOf(1L, 2L), project.summary.memberIds)
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_REFERENCE in project.blockers)
        val inbound = build(grouped.first, grouped.second, SingleVaultDedupEvidence(true, setOf(1L, 2L),
            mapOf(2L to setOf(SingleVaultDedupBlockReason.PASSKEY_REFERENCE)))).single()
        assertEquals(listOf(1L, 2L), inbound.summary.memberIds)
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_REFERENCE in inbound.blockers)
        assertTrue(SingleVaultDedupComparator.analyze(listOf(project, inbound)).candidates.isEmpty())
        for (title in listOf("MonicaPasskeyData", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "FIDO2_CREDENTIAL_ID")) {
            val opaque = build(listOf(entry()), mapOf(1L to fields(1L, listOf(CustomFieldDraft(title = title, value = "synthetic"))))).single()
            assertTrue(SingleVaultDedupBlockReason.PASSKEY_PRESERVED in opaque.blockers)
        }
    }

    @Test fun nestedWalletCredentialLabelProtectsWholeGroupWhileOrdinaryLabelRemainsEligible() {
        val project = grouped(1, listOf("one", "two"))
        fun walletFields(label: String): Map<Long, List<CustomField>> {
            val item = SecureItem(itemType = ItemType.NOTE, title = "Embedded note", itemData = buildJsonObject {
                put("content", "Notes may mention Passkey without becoming a credential.")
                put("customFields", buildJsonArray { add(buildJsonObject {
                    put("label", label); put("value", "synthetic-preserved-secret"); put("type", "HIDDEN")
                }) })
            }.toString())
            val field = EmbeddedWalletContent.put(emptyList(), EmbeddedWalletContent.create(item)).single()
            return project.second + (2L to (project.second.getValue(2L) + CustomField(999, 2,
                field.title, field.value, field.isProtected, 10)))
        }
        val nested = walletFields("encrypted-credential-label")
        val before = nested.mapValues { it.value.toList() }
        val retained = build(project.first, nested, decrypt = { if (it == "encrypted-credential-label") "Passkey" else it }).single()
        assertEquals(listOf(1L, 2L), retained.summary.memberIds)
        assertTrue(SingleVaultDedupBlockReason.PASSKEY_PRESERVED in retained.blockers)
        assertEquals(before, nested)
        val ordinary = build(project.first, walletFields("姓名")).single()
        assertTrue(ordinary.blockers.toString(), ordinary.blockers.isEmpty())
        assertFalse(SingleVaultDedupComparator.covers(ordinary, retained))
    }

    @Test fun usernamePasswordOrderOtpNoteAndContextChangesPreventContainment() {
        val baseline = entry()
        val variants = listOf(baseline.copy(id = 2, username = "User"), baseline.copy(id = 2, password = "Secret"),
            baseline.copy(id = 2, title = "Other"), baseline.copy(id = 2, categoryId = 3), baseline.copy(id = 2, isFavorite = true),
            baseline.copy(id = 2, authenticatorKey = "otp-a"))
        variants.forEach { changed ->
            assertFalse(SingleVaultDedupComparator.covers(build(listOf(baseline)).single(), build(listOf(changed)).single()))
        }
        val a = grouped(1, listOf("one", "two")); val b = grouped(3, listOf("two", "one"))
        assertTrue(analyze(a.first + b.first, a.second + b.second).candidates.isEmpty())
    }

    @Test fun allEntityPropertiesRequireExplicitCoverageClassification() {
        val actual = PasswordEntry::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        assertEquals(actual, SingleVaultDedupProjectBuilder.classifiedPasswordProperties)
    }

    @Test fun duplicateFieldsRetainMultiplicityProtectionNameAndOrder() {
        val one = entry(); val two = entry(2)
        val a = listOf(CustomFieldDraft(title = "Name", value = "v"), CustomFieldDraft(title = "Name", value = "v"))
        val b = listOf(CustomFieldDraft(title = "Name", value = "v"))
        val result = analyze(listOf(one, two), mapOf(1L to fields(1, a), 2L to fields(2, b)))
        assertEquals("password:2", result.candidates.single().candidateKey)
        for (different in listOf(b.single().copy(title = "name"), b.single().copy(value = "v "), b.single().copy(isProtected = true))) {
            assertTrue(analyze(listOf(one, two), mapOf(1L to fields(1, b), 2L to fields(2, listOf(different)))).candidates.isEmpty())
        }
    }

    @Test fun incompleteUnreadableUnknownAndExternalContentAreBlocked() {
        assertTrue(build(listOf(entry()), evidence = SingleVaultDedupEvidence()).single().blockers.contains(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT))
        assertTrue(build(listOf(entry()), decrypt = { throw IllegalArgumentException() }).single().blockers.contains(SingleVaultDedupBlockReason.UNREADABLE_CONTENT))
        for (changed in listOf(entry().copy(boundNoteId = 2), entry().copy(ssoRefEntryId = 2), entry().copy(passkeyBindings = "[]"),
            entry().copy(replicaGroupId = "replica"), entry().copy(customIconType = "UPLOADED", customIconValue = "image"))) {
            assertTrue(build(listOf(changed)).single().blockers.contains(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE))
        }
        val unknown = fields(1, listOf(CustomFieldDraft(title = "monica.future.link", value = "2")))
        assertTrue(build(listOf(entry()), mapOf(1L to unknown)).single().blockers.contains(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT))
    }

    @Test fun emptyProjectsDoNotQualifyAndExcludedMembersNeverShrinkProject() {
        val empty = entry().copy(website = "", username = "", password = "")
        assertTrue(build(listOf(empty)).single().blockers.contains(SingleVaultDedupBlockReason.EMPTY_CONTENT))
        val grouped = grouped(1, listOf("one", "two"))
        val partiallyDeleted = grouped.first.map { if (it.id == 2L) it.copy(isDeleted = true) else it }
        assertTrue(build(partiallyDeleted, grouped.second).single().blockers.contains(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT))
        val partiallyArchived = grouped.first.map { if (it.id == 2L) it.copy(isArchived = true) else it }
        assertTrue(build(partiallyArchived, grouped.second).single().blockers.contains(SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT))
    }

    @Test fun identicalMetadataDuplicatesAreAcceptedButUnknownConflictingAndDanglingMetadataBlocked() {
        val grouped = grouped(1, listOf("one")); val original = grouped.second.getValue(1).single()
        assertTrue(build(grouped.first, mapOf(1L to listOf(original, original.copy(id = 2)))).single().blockers.isEmpty())
        val raw = Json.parseToJsonElement(original.value).jsonObject
        for (patched in listOf(JsonObject(raw + ("future" to JsonPrimitive("x"))), JsonObject(raw + ("passwordOrder" to JsonPrimitive(2))))) {
            assertTrue(build(grouped.first, mapOf(1L to listOf(original, original.copy(id = 2, value = patched.toString())))).single().blockers.isNotEmpty())
        }
        val dangling = CustomField(4, 1, EntryContentFields.ORDER, "CREDENTIAL:${UUID.randomUUID()}")
        assertTrue(build(grouped.first, mapOf(1L to listOf(original, dangling))).single().blockers.contains(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT))
    }

    @Test fun blocksUseVerifiedCompletePayloadAndNormalizeOnlyInternalIds() {
        val a = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY).edited("Key", mapOf("key" to "value"))
        val b = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY).edited("Key", mapOf("key" to "value"))
        fun stored(id: Long, block: PasswordContentBlocks.Block) = fields(id, EntryContentFields.withOrder(PasswordContentBlocks.put(emptyList(), block), listOf(PasswordContentBlocks.token(block.id))))
        assertEquals(1, analyze(listOf(entry(), entry(2)), mapOf(1L to stored(1, a), 2L to stored(2, b))).candidates.size)
        val changed = b.edited("Key", mapOf("key" to "other"))
        assertTrue(analyze(listOf(entry(), entry(2)), mapOf(1L to stored(1, a), 2L to stored(2, changed))).candidates.isEmpty())
        val broken = stored(1, a).map { if (it.title.endsWith(".0000")) it.copy(value = "broken") else it }
        assertTrue(build(listOf(entry()), mapOf(1L to broken)).single().blockers.contains(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT))
        assertTrue(build(listOf(entry()), mapOf(1L to (stored(1, a) + stored(1, a).first()))).single().blockers.isNotEmpty())
    }

    @Test fun qrTemplateResolvedBehaviorMustAlsoBeCovered() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("", mapOf("content" to "%EMAIL%", "mode" to "template", "templateVersion" to "1"))
        val stored = PasswordContentBlocks.put(emptyList(), block)
        assertTrue(analyze(listOf(entry().copy(email = "a"), entry(2).copy(email = "b")), mapOf(1L to fields(1, stored), 2L to fields(2, stored))).candidates.isEmpty())
        assertTrue(analyze(listOf(entry(), entry(2).copy(email = "b")), mapOf(1L to fields(1, stored), 2L to fields(2, stored))).candidates.isEmpty())
    }

    @Test fun walletExtensionsAndNestedCustomFieldsAreCompleteAndAssetsBlock() {
        val item = SecureItem(itemType = ItemType.NOTE, title = "Note", itemData = """{"content":"hello","tags":["one","one"],"isMarkdown":true,"customFields":[{"label":"x","value":"y","type":"HIDDEN"}]}""")
        val a = EmbeddedWalletContent.create(item)
        val b = EmbeddedWalletContent.create(item)
        fun stored(id: Long, snapshot: EmbeddedWalletContent.Snapshot) = fields(id, EmbeddedWalletContent.put(emptyList(), snapshot))
        assertEquals(1, analyze(listOf(entry(), entry(2)), mapOf(1L to stored(1, a), 2L to stored(2, b))).candidates.size)
        val unknown = a.edited(a.title, a.notes, JsonObject(a.itemData + ("future" to JsonPrimitive("value"))))
        assertTrue(build(listOf(entry()), mapOf(1L to stored(1, unknown))).single().blockers.contains(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT))
        val asset = EmbeddedWalletContent.Asset("wallet-test", "x", "image/png", EmbeddedWalletContent.AssetRole.INLINE_IMAGE, 3, "a".repeat(64))
        assertTrue(build(listOf(entry()), mapOf(1L to stored(1, a.withAssets(listOf(asset))))).single().blockers.contains(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE))
    }

    @Test fun jsonPreservesTypesNullMultiplicityAndRejectsDuplicateMemberNames() {
        val parse = SingleVaultDedupProjectBuilder::strictJson
        assertEquals(parse("""{"b":2,"a":1}"""), parse("""{"a":1,"b":2}"""))
        for (raw in listOf("""{"a":1,"a":2}""", """{"a":{"x":1,"x":1}}""", """{"a":1,"\u0061":1}""")) {
            try { parse(raw); fail("Duplicate member accepted") } catch (_: IllegalArgumentException) { }
        }
        val node = SingleVaultDedupProjectBuilder.exactJsonNode("json", parse("""{"unknown":[false,0,null,"0",""]}"""))
        assertFalse(node.toString().contains("unknown"))
    }

    @Test fun cancellationIsNotAnUnreadableContentResult() {
        try {
            SingleVaultDedupProjectBuilder.build("local", listOf(entry()), emptyMap(), { it }, SingleVaultDedupEvidence(true, setOf(1)), checkActive = { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
