package tech.dmconsulting.dixi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** English (res/values) and French (res/values-fr) define exactly the same texts (DMC-463). */
class TranslationsTest {
    private val res = File("src/main/res")

    /** "string:name", "string-array:name" and "plurals:name" of every resource file in [folder]. */
    private fun keys(folder: String): Set<String> {
        val files = File(res, folder).listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
        return files.flatMap { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            listOf("string", "string-array", "plurals").flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map { "$tag:" + (nodes.item(it) as Element).getAttribute("name") }
            }
        }.toSet()
    }

    @Test
    fun englishAndFrenchHaveTheSameKeys() {
        val english = keys("values")
        val french = keys("values-fr")
        assertTrue("no texts found in ${res.absolutePath}", english.size > 100)
        assertEquals("only in values/", emptySet<String>(), english - french)
        assertEquals("only in values-fr/", emptySet<String>(), french - english)
    }

    @Test
    fun englishIsTheDefaultLanguage() {
        assertEquals("unqualifiedResLocale=en", File(res, "resources.properties").readText().trim())
    }
}
