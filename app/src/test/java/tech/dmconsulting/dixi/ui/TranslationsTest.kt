package tech.dmconsulting.dixi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English (res/values) is the default; French (DMC-463) and the 11 languages of the Play notes
 * (DMC-776) define the same texts, with the same placeholders.
 */
class TranslationsTest {
    private val res = File("src/main/res")

    /** Every translated folder: the 12 store languages other than English. */
    private val languages = listOf(
        "values-fr", "values-de", "values-es", "values-it", "values-nl", "values-pt-rBR",
        "values-ru", "values-ar", "values-hi", "values-ja", "values-ko", "values-zh-rCN",
    )

    /** Kept in English only (tools:ignore="MissingTranslation" in values/strings.xml). */
    private val untranslated = setOf("string:app_name", "string:demo_raw_text", "string-array:debug_sample_dictations")

    /** Text of every "string:name", "string-array:name" and "plurals:name" in [folder], as written in the XML. */
    private fun texts(folder: String): Map<String, String> {
        val files = File(res, folder).listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
        return files.flatMap { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            listOf("string", "string-array", "plurals").flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map {
                    val node = nodes.item(it) as Element
                    "$tag:" + node.getAttribute("name") to node.textContent
                }
            }
        }.toMap()
    }

    private fun placeholders(text: String): List<String> = Regex("""%(\d+\$)?[sdf]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageFolderIsChecked() {
        val folders = res.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").exists() }.orEmpty()
        assertEquals(languages.toSet(), folders.map { it.name }.toSet())
    }

    @Test
    fun everyLanguageHasTheSameKeysAsEnglish() {
        val english = texts("values").keys
        assertTrue("no texts found in ${res.absolutePath}", english.size > 100)
        for (folder in languages) {
            val translated = texts(folder).keys
            assertEquals("missing in $folder/", emptySet<String>(), english - translated - untranslated)
            assertEquals("only in $folder/", emptySet<String>(), translated - english)
        }
    }

    @Test
    fun everyTranslationKeepsThePlaceholders() {
        val english = texts("values")
        for (folder in languages) {
            for ((key, text) in texts(folder)) {
                assertEquals("$folder/ $key", placeholders(english.getValue(key)), placeholders(text))
            }
        }
    }

    @Test
    fun historyDatePatternsAreValidJavaTimePatterns() {
        val date = LocalDateTime.of(2026, 11, 2, 9, 12)
        for (folder in listOf("values") + languages) {
            val locale = Locale.forLanguageTag(folder.removePrefix("values").removePrefix("-").replace("-r", "-").ifEmpty { "en" })
            for (name in listOf("history_date_day_month", "history_date_day_month_year", "history_date_time")) {
                // aapt turns \' into the quote java.time uses around literal text.
                val pattern = texts(folder).getValue("string:$name").replace("\\'", "'")
                assertTrue("$folder/ $name", date.format(DateTimeFormatter.ofPattern(pattern, locale)).isNotBlank())
            }
        }
    }

    /** Each Play store language (src/main/play/release-notes) finds its texts in the app. */
    @Test
    fun everyPlayLanguageHasItsTexts() {
        val playLocales = File("src/main/play/release-notes").listFiles { f -> f.isDirectory }.orEmpty().map { it.name }
        assertEquals(13, playLocales.size)
        for (locale in playLocales) {
            val language = locale.substringBefore("-")
            val folder = when {
                language == "en" -> "values"
                File(res, "values-$language-r${locale.substringAfter("-")}").exists() -> "values-$language-r${locale.substringAfter("-")}"
                else -> "values-$language"
            }
            assertTrue("$locale: no $folder/strings.xml", File(res, "$folder/strings.xml").exists())
        }
    }

    @Test
    fun englishIsTheDefaultLanguage() {
        assertEquals("unqualifiedResLocale=en", File(res, "resources.properties").readText().trim())
    }
}
