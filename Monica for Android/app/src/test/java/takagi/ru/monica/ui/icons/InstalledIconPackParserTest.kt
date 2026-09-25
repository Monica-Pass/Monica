package takagi.ru.monica.ui.icons

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory

class InstalledIconPackParserTest {
    @Test
    fun readsDrawableCatalogAndIgnoresCategoriesAndDuplicates() {
        assertEquals(linkedSetOf("gift_card", "bank"), parse("""
            <resources>
                <category title="General" />
                <item drawable="gift_card" />
                <item drawable="@drawable/bank" />
                <item drawable="gift_card" />
            </resources>
        """))
    }

    @Test
    fun readsAppFilterFallbackWithoutTreatingDecorationAsAnIcon() {
        assertEquals(setOf("camera", "mail"), parse("""
            <resources>
                <iconback img1="background" />
                <item component="ComponentInfo{example/Camera}" drawable="camera" />
                <item component="ComponentInfo{example/Mail}" drawable="@mipmap/mail" />
                <item component="ComponentInfo{example/Other}" />
            </resources>
        """))
    }

    @Test
    fun rejectsPathsAndCrossPackageResourceReferences() {
        listOf(null, "", "../icon", "other.package:drawable/icon", "@android:drawable/ic_menu_camera", "folder/icon").forEach {
            assertNull(normalizeIconDrawableName(it))
        }
        assertEquals("gift_card", normalizeIconDrawableName("  gift_card  "))
    }

    @Test(expected = org.xmlpull.v1.XmlPullParserException::class)
    fun malformedCatalogDoesNotReturnAPartialSuccess() {
        parse("<resources><item drawable='camera'></resources>")
    }

    private fun parse(xml: String): Set<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        return readIconPackDrawableNames(parser)
    }
}
