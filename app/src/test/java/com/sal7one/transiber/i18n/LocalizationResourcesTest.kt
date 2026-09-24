package com.sal7one.transiber.i18n

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class LocalizationResourcesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private fun strings(folder: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        File(res, folder).listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name").nodeValue
                assertNull("Duplicate $folder/$name", result.put(name, node.textContent))
            }
        }
        return result
    }
    @Test fun everyInterfaceResourceHasBothTranslations() {
        val english = strings("values")
        assertTrue("Default resources must not be empty", english.isNotEmpty())
        for (folder in listOf("values-ar", "values-zh")) {
            val translated = strings(folder)
            assertEquals("Missing or extra translations in $folder", english.keys, translated.keys)
            translated.forEach { (key, value) -> assertTrue("Blank $folder/$key", value.isNotBlank()) }
        }
    }
    @Test fun translatedFormatArgumentsArePreservedAndAllMessagesFormat() {
        val english = strings("values")
        val placeholders = Regex("%([0-9]+)\\\$([sd])")
        for (folder in listOf("values", "values-ar", "values-zh")) {
            val locale = Locale.forLanguageTag(folder.substringAfter('-', "en"))
            strings(folder).forEach { (key, value) ->
                val expected = placeholders.findAll(english.getValue(key)).map { it.value }.sorted().toList()
                val actual = placeholders.findAll(value).map { it.value }.sorted().toList()
                assertEquals("Placeholder mismatch $folder/$key", expected, actual)
                if (actual.isNotEmpty()) {
                    val count = placeholders.findAll(value).maxOf { it.groupValues[1].toInt() }
                    val types = placeholders.findAll(value).associate { it.groupValues[1].toInt() to it.groupValues[2] }
                    val arguments = Array<Any>(count) { index ->
                        if (types[index + 1] == "d") 777 + index else "argument-${index + 1}"
                    }
                    val formatted = String.format(locale, value, *arguments)
                    for (index in 1..count) {
                        val expectedValue = if (types[index] == "d") String.format(locale, "%d", arguments[index - 1])
                            else arguments[index - 1].toString()
                        assertTrue("Lost argument $folder/$key", formatted.contains(expectedValue))
                    }
                }
            }
        }
    }
    @Test fun deferredMessagesRemainUsableWithoutAndroidOrAnActivity() {
        val child = UiMessage(2, "model %1\$s", listOf("Qwen"))
        val parent = UiMessage(1, "%1\$s · %2\$s", listOf(child, "ready"))
        assertEquals("model Qwen · ready", parent.toString())
    }
}
