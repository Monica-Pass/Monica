package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class AutoTypeRetentionTest {
    @Test fun emptyAssociationElementsSurviveBothEncryptedFormats() {
        val credentials=Credentials.from(EncryptedValue.fromString("synthetic-autotype-retention"))
        for(v3 in listOf(true,false)) {
            val base=if(v3) KeePassDatabase.Ver3x.create("Root",Meta(),credentials)
                else KeePassDatabase.Ver4x.create("Root",Meta(),credentials).let {
                    it.copy(header=it.header.copy(kdfParameters=KdfParameters.Aes(10u,it.header.masterSeed)))
                }
            val data=AutoTypeData(enabled=true,items=listOf(AutoTypeItem("Window",""),
                AutoTypeItem("","{USERNAME}"),AutoTypeItem("",""),AutoTypeItem("Normal","{PASSWORD}")))
            val e=Entry(UUID.randomUUID(),autoType=data)
            val db=base.modifyContent { copy(group=group.copy(entries=listOf(e.copy(history=listOf(e))))) }
            val bytes=ByteArrayOutputStream().also { db.encode(it) }.toByteArray()
            val decoded=KeePassDatabase.decode(bytes.inputStream(),credentials).content.group.entries.single()
            assertEquals(data,decoded.autoType)
            assertEquals(data,decoded.history.single().autoType)
        }
    }
}
