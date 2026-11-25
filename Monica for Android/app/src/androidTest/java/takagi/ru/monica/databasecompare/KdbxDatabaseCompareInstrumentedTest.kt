package takagi.ru.monica.databasecompare

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class KdbxDatabaseCompareInstrumentedTest {
    @get:Rule val temporary = TemporaryFolder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir)
    private val password = "comparison-synthetic"
    private val credentials get() = Credentials.from(EncryptedValue.fromString(password))
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private fun entry(id: UUID = UUID.randomUUID(), title: String = "Same title", secret: String = "one") = Entry(id,
        fields = EntryFields.of("Title" to EntryValue.Plain(title), "Password" to EntryValue.Encrypted(EncryptedValue.fromString(secret)), "Whitespace" to EntryValue.Plain(" \r\n\t ")),
        times = TimeData(time,time,time,time,null))
    private fun database(version3: Boolean = false): KeePassDatabase {
        if(version3) return KeePassDatabase.Ver3x.create("Root",Meta(),credentials)
        val db=KeePassDatabase.Ver4x.create("Root",Meta(),credentials)
        return db.copy(header=db.header.copy(kdfParameters=KdfParameters.Aes(10u,db.header.masterSeed)))
    }
    private fun save(name: String, db: KeePassDatabase): File = File(temporary.root,name).also { f -> f.outputStream().use { db.encode(it,contentParser=KdbxComparisonXml()) } }
    private fun input(f: File) = CompareInput(f,f.name,password.toCharArray())
    private fun read(f: File, pass: String = "result-synthetic") = f.inputStream().use { KeePassDatabase.decode(it,Credentials.from(EncryptedValue.fromString(pass)),contentParser=KdbxComparisonXml()) }
    private fun choose(session: NativeDatabaseComparison, overrides: Map<String,CompareChoice> = emptyMap()) = session.plan.rows.associate { it.id to (overrides[it.id] ?: it.defaultChoice ?: CompareChoice.LEFT) }

    @Test fun matchingIdentityRetainsBothHistoriesProtectionAttachmentsAndInputs() {
        for(v3 in listOf(false,true)) {
            val base=database(v3);val old=entry(secret="older");val leftEntry=old.copy(history=listOf(old.copy(fields=old.fields + ("Password" to EntryValue.Encrypted(EncryptedValue.fromString("historic"))))))
            val blob=BinaryData.Uncompressed(false,byteArrayOf(1,2,3,4))
            val new=old.copy(fields=old.fields + ("Password" to EntryValue.Encrypted(EncryptedValue.fromString("newer"))),binaries=listOf(BinaryReference(blob.hash,"document.bin")))
            val a=save("left-$v3.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(leftEntry))) })
            val b=save("right-$v3.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(new))) }.modifyBinaries { mapOf(blob.hash to blob) })
            val beforeA=a.readBytes();val beforeB=b.readBytes();val result=File(temporary.root,"result-$v3.kdbx")
            KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
                assertTrue(session.plan.blockers.isEmpty())
                assertEquals(CompareChange.CHANGED,session.plan.rows.single { it.id=="entry:${old.uuid}" }.change)
                session.writeResult(result,choose(session,mapOf("entry:${old.uuid}" to CompareChoice.RIGHT)),"result-synthetic".toCharArray())
            }
            val db=read(result);val actual=db.content.group.entries.single()
            assertEquals("newer",actual.fields.password!!.content)
            assertTrue(actual.fields.password is EntryValue.Encrypted)
            assertEquals(setOf("older","historic"),actual.history.map { it.fields.password!!.content }.toSet())
            assertEquals(" \r\n\t ",actual.fields.getValue("Whitespace").content)
            assertEquals(listOf(BinaryReference(blob.hash,"document.bin")),actual.binaries)
            assertArrayEquals(beforeA,a.readBytes());assertArrayEquals(beforeB,b.readBytes())
        }
    }

    @Test fun unrelatedSameTitlesRemainIndependentAndUnknownCustomDataSurvives() {
        val ea=entry();val eb=entry(secret="two").copy(customData=mapOf("future.plugin" to CustomDataValue("{\"opaque\":null}",time)))
        val a=save("a.kdbx",database().modifyContent { copy(group=group.copy(entries=listOf(ea))) })
        val b=save("b.kdbx",database().modifyContent { copy(group=group.copy(entries=listOf(eb))) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertEquals(2,s.plan.rows.count { it.kind=="entry" });s.writeResult(out,choose(s),"result-synthetic".toCharArray())
        }
        val db=read(out);assertEquals(ea.uuid,db.content.group.entries.single().uuid)
        val imported=db.content.group.groups.single().entries.single();assertEquals(eb.uuid,imported.uuid);assertEquals(eb.customData,imported.customData)
    }

    @Test fun sameTimestampDifferentHistoryValuesAreBothRetained() {
        val base=database();val e=entry();val h1=e.copy(fields=e.fields + ("Password" to EntryValue.Plain("h1")));val h2=e.copy(fields=e.fields + ("Password" to EntryValue.Plain("h2")))
        val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(history=listOf(h1))))) })
        val b=save("b.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(history=listOf(h2))))) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { it.writeResult(out,choose(it),"result-synthetic".toCharArray()) }
        assertEquals(setOf("h1","h2"),read(out).content.group.entries.single().history.map { it.fields.password!!.content }.toSet())
    }

    @Test fun staleSourceAndClosedSessionNeverPublish() {
        val base=database();val a=save("a.kdbx",base);val b=save("b.kdbx",base);val out=File(temporary.root,"result.kdbx")
        val s=KdbxDatabaseCompare.open(input(a),input(b),temporary.root);val selections=choose(s)
        b.appendBytes(byteArrayOf(1));assertTrue(runCatching { s.writeResult(out,selections,"result-synthetic".toCharArray()) }.isFailure);assertFalse(out.exists())
        s.close();assertTrue(runCatching { s.plan }.isFailure);assertTrue(runCatching { s.writeResult(out,selections,"result-synthetic".toCharArray()) }.isFailure)
    }

    @Test fun cancelDuringResultBuildKeepsBothSourcesAndNoOutput() {
        val base=database();val a=save("a.kdbx",base);val b=save("b.kdbx",base);val out=File(temporary.root,"result.kdbx")
        val oldA=a.readBytes();val oldB=b.readBytes()
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            var checks=0
            assertTrue(runCatching { s.writeResult(out,choose(s),"result-synthetic".toCharArray()) { if(++checks>=3) throw IllegalStateException("cancel") } }.isFailure)
        }
        assertFalse(out.exists());assertArrayEquals(oldA,a.readBytes());assertArrayEquals(oldB,b.readBytes());assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".compare-") })
    }

    @Test fun groupMoveCycleCannotPublish() {
        val base=database();val x=Group(UUID.randomUUID(),"X");val y=Group(UUID.randomUUID(),"Y")
        val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(groups=listOf(x.copy(groups=listOf(y))))) })
        val b=save("b.kdbx",base.modifyContent { copy(group=group.copy(groups=listOf(y.copy(groups=listOf(x))))) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertTrue(runCatching { s.writeResult(out,choose(s,mapOf("group:${x.uuid}" to CompareChoice.RIGHT,"group:${y.uuid}" to CompareChoice.LEFT)),"result-synthetic".toCharArray()) }.isFailure)
        };assertFalse(out.exists())
    }

    @Test fun deletingReferenceTargetOrIntroducingAmbiguousTitleReferenceIsRejected() {
        val base=database();val target=entry(title="Referenced");val ref=entry(title="Alias").copy(fields=EntryFields.of("Title" to EntryValue.Plain("Alias"),"Password" to EntryValue.Plain("{REF:P@T:Referenced}")))
        val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(target,ref))) })
        val b=save("b.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(target,ref,entry(title="Other")))) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertFalse(s.plan.rows.single { it.id=="entry:${ref.uuid}" }.allowedChoices.contains(CompareChoice.BOTH))
            s.writeResult(out,choose(s),"result-synthetic".toCharArray())
        };assertTrue(out.isFile)
        val ambiguous=save("ambiguous.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(target,ref,entry(title="Referenced")))) })
        KdbxDatabaseCompare.open(input(a),input(ambiguous),temporary.root).use { assertTrue(CompareProblem.UNRESOLVED_REFERENCE in it.plan.blockers) }
    }

    @Test fun malformedOrFutureXmlIsRejectedAndConventionalForceOnceFalseAccepted() {
        val parser=KdbxComparisonXml()
        val base="<KeePassFile><Meta><MasterKeyChangeForceOnce>False</MasterKeyChangeForceOnce></Meta><Root><Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name>Root</Name></Group><DeletedObjects/></Root></KeePassFile>"
        parser.validate(base.toByteArray())
        listOf(base.replace("False","True"),base.replace("<Name>Root</Name>","<Name>Root</Name><PluginSecret>hidden</PluginSecret>"),"<!DOCTYPE KeePassFile [<!ENTITY x SYSTEM 'file:///not-read'>]>$base").forEach { xml -> assertTrue(runCatching { parser.validate(xml.toByteArray()) }.isFailure) }
        val duplicated=base.replace("<Name>Root</Name>","<Name>Root</Name><Name>Second</Name>")
        assertTrue(runCatching { parser.validate(duplicated.toByteArray()) }.isFailure)
    }

    @Test fun missingAttachmentAndUnrelatedIdentityCollisionAreNotSilentlyDropped() {
        val base=database();val e=entry();val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e))) })
        val b=save("b.kdbx",database().modifyContent { copy(group=group.copy(entries=listOf(e))) })
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { assertTrue(CompareProblem.IDENTITY_CONFLICT in it.plan.blockers) }
        val fake=BinaryData.Uncompressed(false,byteArrayOf(9))
        val broken=base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(binaries=listOf(BinaryReference(fake.hash,"missing")))))) }
        assertTrue(runCatching { save("broken.kdbx",broken) }.isFailure)
    }

    @Test fun explicitDeletionRequiresConsistentChoiceAndDoesNotInferFromAbsence() {
        val base=database();val e=entry();val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e))) })
        val b=save("b.kdbx",base.modifyContent { copy(deletedObjects=listOf(DeletedObject(e.uuid,time))) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertNull(s.plan.rows.single { it.id=="deletion:${e.uuid}" }.defaultChoice)
            assertTrue(runCatching { s.writeResult(out,choose(s,mapOf("deletion:${e.uuid}" to CompareChoice.RIGHT)),"result-synthetic".toCharArray()) }.isFailure)
            s.writeResult(out,choose(s,mapOf("entry:${e.uuid}" to CompareChoice.RIGHT,"deletion:${e.uuid}" to CompareChoice.RIGHT)),"result-synthetic".toCharArray())
        }
        assertTrue(read(out).content.group.entries.isEmpty());assertEquals(e.uuid,read(out).content.deletedObjects.single().id)
        val noDeletion=save("without-deletion.kdbx",base)
        KdbxDatabaseCompare.open(input(a),input(noDeletion),temporary.root).use { s ->
            assertEquals(CompareChange.ONLY_LEFT,s.plan.rows.single { it.id=="entry:${e.uuid}" }.change)
            assertEquals(CompareChoice.LEFT,s.plan.rows.single { it.id=="entry:${e.uuid}" }.defaultChoice)
        }
    }

    @Test fun independentlyKeyedInputsCreateResultUsingOnlyNewPassword() {
        val key=ByteArray(32) { it.toByte() };val base=database() as KeePassDatabase.Ver4x
        val a=save("a.kdbx",base.copy(credentials=Credentials.from(EncryptedValue.fromString(password),key.copyOf())))
        val b=save("b.kdbx",base.copy(credentials=Credentials.from(EncryptedValue.fromString("other-password"))))
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a).copy(keyFile=key),input(b).copy(password="other-password".toCharArray()),temporary.root).use { s ->
            assertArrayEquals(ByteArray(32) { it.toByte() },key)
            s.writeResult(out,choose(s),"result-synthetic".toCharArray())
        }
        assertNotNull(read(out));assertTrue(runCatching { read(out,password) }.isFailure)
    }

    @Test fun safeBothCreatesDistinctIdentityButPasskeyCannotBeDuplicated() {
        val base=database();val e=entry();val changed=e.copy(fields=e.fields + ("Password" to EntryValue.Plain("other")))
        val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e))) })
        val b=save("b.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(changed))) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertTrue(CompareChoice.BOTH in s.plan.rows.single { it.id=="entry:${e.uuid}" }.allowedChoices)
            s.writeResult(out,choose(s,mapOf("entry:${e.uuid}" to CompareChoice.BOTH)),"result-synthetic".toCharArray())
        }
        val entries=read(out).content.group.entries;assertEquals(2,entries.map { it.uuid }.distinct().size);assertEquals(setOf("one","other"),entries.map { it.fields.password!!.content }.toSet())
        val passkeyEntry = changed.copy(fields = changed.fields +
            ("KPEX_PASSKEY_PRIVATE_KEY_PEM" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-key"))))
        val passkey=save("passkey.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(passkeyEntry))) })
        KdbxDatabaseCompare.open(input(a),input(passkey),temporary.root).use { s -> assertFalse(CompareChoice.BOTH in s.plan.rows.single { it.id=="entry:${e.uuid}" }.allowedChoices) }
    }

    @Test fun nestedCompressedAttachmentIsBoundedBeforeNativeModelConstruction() {
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bytes).use { gzip ->
            val chunk=ByteArray(8192) { 7 }
            repeat(KdbxComparisonXml.MAX_XML_BYTES / chunk.size + 1) { gzip.write(chunk) }
        }
        val binary=BinaryData.Compressed(false,bytes.toByteArray())
        assertTrue(binary.rawContent.size < 128 * 1024)
        val base=database(true)
        val a=save("a.kdbx",base)
        val b=save("compressed.kdbx",base.modifyBinaries { mapOf(binary.hash to binary) })
        val before=b.readBytes()
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertTrue(CompareProblem.UNSUPPORTED_CONTENT in s.plan.blockers)
        }
        assertArrayEquals(before,b.readBytes())
        val small=BinaryData.Uncompressed(false,byteArrayOf(1,2,3)).toCompressed()
        val c=save("small.kdbx",base.modifyBinaries { mapOf(small.hash to small) })
        val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(c),temporary.root).use { s -> s.writeResult(out,choose(s),"result-synthetic".toCharArray()) }
        assertArrayEquals(small.rawContent,read(out).binaries.getValue(small.hash).rawContent)
    }

    @Test fun customFieldNamesRemainDistinctFromGeneratedLabels() {
        val e=entry().copy(fields=EntryFields.of("Title" to EntryValue.Plain("Test"),"History" to EntryValue.Plain("custom"),"Version" to EntryValue.Plain("custom-version")))
        val left=e
        val right=e.copy(fields=e.fields + ("History" to EntryValue.Encrypted(EncryptedValue.fromString("new"))),history=listOf(e))
        val base=database();val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(left))) });val b=save("b.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(right))) })
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            val fields=s.plan.rows.single { it.id=="entry:${e.uuid}" }.fields
            assertTrue(fields.any { it.name=="History" && !it.generatedLabel && !it.protectedFlag && it.historyIndex==null })
            assertTrue(fields.any { it.name=="History" && !it.generatedLabel && it.protectedFlag })
            assertTrue(fields.any { it.name=="History" && it.generatedLabel && it.historyIndex==null })
            assertTrue(fields.any { it.name=="Version" && !it.generatedLabel && it.historyIndex==1 })
            assertTrue(fields.any { it.name=="Version" && it.generatedLabel && it.historyIndex==1 })
        }
    }

    @Test fun encodedUnsupportedXmlAndMissingBinaryReferenceBlockWholePlan() {
        val base=database();val e=entry();val blob=BinaryData.Uncompressed(false,byteArrayOf(7,8))
        val source=base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(binaries=listOf(BinaryReference(blob.hash,"test")))))) }.modifyBinaries { mapOf(blob.hash to blob) }
        val a=save("a.kdbx",source)
        val delegate=KdbxComparisonXml()
        for((label,transform) in listOf<Pair<String,(String)->String>>(
            "extension" to { xml -> xml.replace("</Entry>","<FutureSecret>synthetic</FutureSecret></Entry>") },
            "missing" to { xml -> xml.replace("Ref=\"0\"","Ref=\"999\"") }
        )) {
            val parser=object : app.keemobile.kotpass.xml.XmlContentParser by delegate {
                override fun marshalContent(context: XmlContext.Encode,content: DatabaseContent,pretty: Boolean) = transform(delegate.marshalContent(context,content,pretty))
            }
            val b=File(temporary.root,"$label.kdbx");b.outputStream().use { source.encode(it,contentParser=parser) }
            KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s -> assertTrue(label,CompareProblem.UNSUPPORTED_CONTENT in s.plan.blockers) }
        }
    }

    @Test fun excludedEntryDoesNotLeakAttachmentAndOriginalOrphanIsExplicit() {
        val base=database();val e=entry();val attached=BinaryData.Uncompressed(false,byteArrayOf(3,2,1));val orphan=BinaryData.Uncompressed(false,byteArrayOf(8,8,8))
        val a=save("a.kdbx",base)
        val withAttachment=base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(binaries=listOf(BinaryReference(attached.hash,"private")))))) }.modifyBinaries { mapOf(attached.hash to attached,orphan.hash to orphan) }
        val b=save("b.kdbx",withAttachment);val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertEquals(1,s.plan.rows.count { it.kind=="attachment" })
            s.writeResult(out,choose(s,mapOf("entry:${e.uuid}" to CompareChoice.LEFT)),"result-synthetic".toCharArray())
        }
        val result=read(out);assertTrue(result.content.group.entries.isEmpty())
        assertEquals(setOf(orphan.hash),result.binaries.keys)
    }

    @Test fun sourceConsistentLiveAndTombstoneCoexistenceIsPreserved() {
        val e=entry();val base=database().modifyContent { copy(group=group.copy(entries=listOf(e)),deletedObjects=listOf(DeletedObject(e.uuid,time))) }
        val a=save("a.kdbx",base);val b=save("b.kdbx",base);val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s -> s.writeResult(out,choose(s),"result-synthetic".toCharArray()) }
        val result=read(out);assertEquals(e.uuid,result.content.group.entries.single().uuid);assertEquals(e.uuid,result.content.deletedObjects.single().id)
    }

    @Test fun emptyAutoTypeAssociationSequenceSurvivesEncryptedMerge() {
      for (v3 in listOf(false, true)) {
        val base=database(v3)
        val e=entry().copy(autoType=AutoTypeData(enabled=true, items=listOf(
            AutoTypeItem("Synthetic window", ""), AutoTypeItem("", "{USERNAME}{TAB}{PASSWORD}"), AutoTypeItem("", ""))))
        val historic=e.copy(fields=e.fields + ("Title" to EntryValue.Plain("Old version")))
        val source=base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(history=listOf(historic))))) }
        val a=save("autotype-a-$v3.kdbx",source);val b=save("autotype-b-$v3.kdbx",source)
        val originalA=a.readBytes();val originalB=b.readBytes();val out=File(temporary.root,"autotype-result-$v3.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
            assertTrue(session.plan.blockers.isEmpty())
            session.writeResult(out,choose(session),"result-synthetic".toCharArray())
        }
        assertEquals("Empty sequences inherit defaults and must not remove the window association",
            e.autoType,read(out).content.group.entries.single().autoType)
        assertEquals(historic.autoType,read(out).content.group.entries.single().history.single().autoType)
        assertArrayEquals(originalA,a.readBytes());assertArrayEquals(originalB,b.readBytes())
      }
    }

    @Test fun kdbx3SubsecondHistoryVersionsAndCurrentChangesRemainDistinct() {
        val instant=Instant.parse("2026-11-23T10:00:00.100Z")
        val e=entry().run { copy(times=times!!.copy(lastModificationTime=instant)) }
        val later=e.copy(times=e.times!!.copy(lastModificationTime=instant.plusNanos(100_000_000)))
        val base=database(version3=true)
        val a=save("time-a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e))) })
        val b=save("time-b.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(later))) })
        val out=File(temporary.root,"time-result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
            assertEquals(CompareChange.CHANGED,session.plan.rows.single { it.id=="entry:${e.uuid}" }.change)
            session.writeResult(out,choose(session,mapOf("entry:${e.uuid}" to CompareChoice.RIGHT)),"result-synthetic".toCharArray())
        }
        val result=read(out).content.group.entries.single()
        assertEquals(later.times!!.lastModificationTime,result.times!!.lastModificationTime)
        assertTrue(result.history.any { it.times?.lastModificationTime==instant })
    }

    @Test fun invalidBooleanContentCannotSilentlyChangeNativePolicy() {
        val source=database().modifyContent { copy(group=group.copy(entries=listOf(entry()))) }
        val a=save("boolean-good.kdbx",source)
        val delegate=KdbxComparisonXml()
        val parser=object : app.keemobile.kotpass.xml.XmlContentParser by delegate {
            override fun marshalContent(context:XmlContext.Encode,content:DatabaseContent,pretty:Boolean):String {
                val xml=delegate.marshalContent(context,content,pretty)
                check(xml.contains("<ProtectPassword>True</ProtectPassword>"))
                return xml.replace("<ProtectPassword>True</ProtectPassword>","<ProtectPassword>future-policy</ProtectPassword>")
            }
        }
        val b=File(temporary.root,"boolean-invalid.kdbx");b.outputStream().use { source.encode(it,contentParser=parser) }
        val original=b.readBytes()
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
            assertTrue("Unknown memory-protection policy must block comparison",CompareProblem.UNSUPPORTED_CONTENT in session.plan.blockers)
        }
        assertArrayEquals(original,b.readBytes())
    }

    @Test fun malformedPolicyAndIncompleteAssociationsBlockEncryptedOutput() {
        val e=entry().copy(autoType=AutoTypeData(enabled=true,items=listOf(AutoTypeItem("Window","{PASSWORD}"))))
        val source=database().modifyContent { copy(group=group.copy(entries=listOf(e))) }
        val a=save("valid-policy.kdbx",source)
        val delegate=KdbxComparisonXml()
        val mutations=buildList<Pair<String,(String)->String>> {
            for(tag in listOf("ProtectTitle","ProtectUserName","ProtectPassword","ProtectURL","ProtectNotes",
                "RecycleBinEnabled","IsExpanded","QualityCheck","Expires","Enabled","EnableAutoType","EnableSearching")) {
                for(value in listOf("future-policy","", "1")) add("$tag-$value" to { xml ->
                    val pattern=Regex("<$tag>[^<]*</$tag>")
                    check(pattern.containsMatchIn(xml)) { "Missing synthetic $tag" }
                    xml.replace(pattern,"<$tag>$value</$tag>")
                })
            }
            add("missing-window" to { it.replace("<Window>Window</Window>","") })
            add("missing-sequence" to { it.replace("<KeystrokeSequence>{PASSWORD}</KeystrokeSequence>","") })
        }
        for((index,mutation) in mutations.withIndex()) {
            val (label,transform)=mutation
            val parser=object : app.keemobile.kotpass.xml.XmlContentParser by delegate {
                override fun marshalContent(context:XmlContext.Encode,content:DatabaseContent,pretty:Boolean):String {
                    val xml=delegate.marshalContent(context,content,pretty)
                    return transform(xml).also { check(it!=xml) { label } }
                }
            }
            val b=File(temporary.root,"invalid-$index.kdbx");b.outputStream().use { source.encode(it,contentParser=parser) }
            val before=b.readBytes();val out=File(temporary.root,"invalid-output-$index.kdbx")
            KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
                assertTrue(label,CompareProblem.UNSUPPORTED_CONTENT in session.plan.blockers)
                assertTrue(label,runCatching { session.writeResult(out,emptyMap(),"result-synthetic".toCharArray()) }.isFailure)
            }
            assertFalse(label,out.exists());assertArrayEquals(before,b.readBytes())
        }
    }

    @Test fun validBooleanCaseAndInheritedGroupPolicyRoundTrip() {
        val e=entry().copy(autoType=AutoTypeData(enabled=true))
        val source=database().modifyContent { copy(group=group.copy(entries=listOf(e))) }
        val delegate=KdbxComparisonXml()
        val parser=object : app.keemobile.kotpass.xml.XmlContentParser by delegate {
            override fun marshalContent(context:XmlContext.Encode,content:DatabaseContent,pretty:Boolean)=
                delegate.marshalContent(context,content,pretty).replace(">True<",">tRuE<").replace(">False<",">fAlSe<").replace(">null<",">NuLl<")
        }
        val a=save("policy-a.kdbx",source)
        val b=File(temporary.root,"policy-b.kdbx");b.outputStream().use { source.encode(it,contentParser=parser) }
        val out=File(temporary.root,"policy-result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
            assertTrue(session.plan.blockers.isEmpty())
            assertTrue(session.plan.rows.all { it.change==CompareChange.UNCHANGED })
            session.writeResult(out,choose(session),"result-synthetic".toCharArray())
        }
        assertEquals(e.autoType,read(out).content.group.entries.single().autoType)
    }

    @Test fun kdbx3FractionalMetadataAndMultipleHistoryTimesSurvive() {
        val t=time.plusNanos(123_456_789)
        val e=entry()
        val h1=e.copy(times=e.times!!.copy(lastModificationTime=t))
        val h2=h1.copy(times=h1.times!!.copy(lastModificationTime=t.plusNanos(1)))
        val base=database(true)
        val a=save("fraction-a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(history=listOf(h1))))) })
        val b=save("fraction-b.kdbx",base.modifyContent { copy(meta=meta.copy(nameChanged=t),group=group.copy(entries=listOf(e.copy(history=listOf(h2))))) })
        val out=File(temporary.root,"fraction-result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { session ->
            assertEquals(CompareChange.CHANGED,session.plan.rows.single { it.id=="metadata" }.change)
            session.writeResult(out,choose(session,mapOf("metadata" to CompareChoice.RIGHT)),"result-synthetic".toCharArray())
        }
        val result=read(out)
        assertEquals(t,result.content.meta.nameChanged)
        assertEquals(listOf(t,t.plusNanos(1)),result.content.group.entries.single().history.map { it.times!!.lastModificationTime })
    }

    @Test fun mixedChangesCannotCreateReferenceCycle() {
        val base=database();val x=entry(title="X");val y=entry(title="Y")
        val xr=x.copy(fields=x.fields + ("Password" to EntryValue.Plain("{REF:P@T:Y}")))
        val yr=y.copy(fields=y.fields + ("Password" to EntryValue.Plain("{REF:P@T:X}")))
        val a=save("a.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(xr,y))) })
        val b=save("b.kdbx",base.modifyContent { copy(group=group.copy(entries=listOf(x,yr))) });val out=File(temporary.root,"result.kdbx")
        KdbxDatabaseCompare.open(input(a),input(b),temporary.root).use { s ->
            assertTrue(s.plan.blockers.isEmpty())
            assertTrue(runCatching { s.writeResult(out,choose(s,mapOf("entry:${x.uuid}" to CompareChoice.LEFT,"entry:${y.uuid}" to CompareChoice.RIGHT)),"result-synthetic".toCharArray()) }.isFailure)
        };assertFalse(out.exists())
    }
}
