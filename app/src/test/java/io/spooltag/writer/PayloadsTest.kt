package io.spooltag.writer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PayloadsTest {
    @Test fun refreshReplacesOldNumericNamesAndBrandsRatherThanAppending() {
        val old = Payloads.parse("PLA123.json", """{"brand":"Generic"}""".toByteArray())
        val fresh = Payloads.parse("Aurapol - PLA - White.json", """{"brand":"Aurapol"}""".toByteArray())
        val result = Payloads.updateLibrary(listOf(old), listOf(fresh), replace = true)
        assertEquals(listOf(fresh), result)
        assertEquals("Aurapol", result.single().brand())
        assertEquals(2, Payloads.updateLibrary(listOf(old), listOf(fresh), replace = false).size)
    }

    @Test fun ordinaryImportStillReplacesMatchingFilenameAndPreservesId() {
        val old = Payloads.parse("same.json", """{"brand":"Generic"}""".toByteArray())
        val fresh = Payloads.parse("same.json", """{"brand":"Devil Design"}""".toByteArray())
        val result = Payloads.updateLibrary(listOf(old), listOf(fresh), replace = false).single()
        assertEquals(old.id, result.id)
        assertEquals("Devil Design", result.brand())
    }

    @Test fun changingManufacturerPreservesOtherProfileFields() {
        val original = Payloads.parse("devil.json", openSpool.toByteArray())
        val updated = original.withBrand(" Devil Design ")
        assertEquals("Devil Design", updated.brand())
        val before = Json.parseToJsonElement(original.json) as kotlinx.serialization.json.JsonObject
        val after = Json.parseToJsonElement(updated.json) as kotlinx.serialization.json.JsonObject
        assertEquals(before - "brand", after - "brand")
        assertEquals(original.id, updated.id)
        assertThrows(IllegalArgumentException::class.java) { original.withBrand(" ") }
    }

    private val openSpool = """
        {
          "protocol": "openspool",
          "version": "1.0",
          "type": "PETG",
          "brand": "Generic",
          "color_hex": "00AABB",
          "min_temp": "245",
          "max_temp": "255"
        }
    """.trimIndent()

    @Test fun compactJsonPreservesValuesAndUtf8() {
        val source = """{ "name": "Bílá", "n": 1.75, "transparent": true, "nested": [null, 3] }"""
        val payload = Payloads.parse("test.json", source.toByteArray())
        assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(payload.json))
        assertTrue(payload.json.contains("Bílá"))
    }

    @Test fun openSpoolFieldsAndSummary() {
        val file = Payloads.parse("PETG684685.json", openSpool.toByteArray())
        assertEquals("Generic | PETG | 00AABB", file.summary())
        assertEquals(Json.parseToJsonElement(openSpool), Json.parseToJsonElement(file.json))
    }

    @Test fun utf8BomIsAccepted() {
        assertEquals("{}", Payloads.parse("bom.json", "\uFEFF{}".toByteArray()).json)
    }

    @Test fun invalidUtf8IsRejected() {
        assertThrows(PayloadException::class.java) {
            Payloads.parse("bad.json", byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test fun invalidJsonAndNonObjectAreRejected() {
        for (text in listOf("", "{", "[]", "true", "{} trailing")) {
            assertThrows(PayloadException::class.java) { Payloads.parse("bad.json", text.toByteArray()) }
        }
    }

    @Test fun invalidOpenSpoolFieldsAreRejected() {
        for (text in listOf(
            openSpool.replace("\"255\"", "\"200\""),
            openSpool.replace("\"245\"", "245"),
            openSpool.replace("\"00AABB\"", "\"#00AABB\""),
            openSpool.replace("\"1.0\"", "\"2.0\""),
            openSpool.replace("\"Generic\"", "\"\""),
        )) {
            assertThrows(PayloadException::class.java) { Payloads.parse("bad.json", text.toByteArray()) }
        }
    }

    @Test fun jsonSizeIsBounded() {
        assertThrows(PayloadException::class.java) {
            Payloads.parse("large.json", ByteArray(Payloads.MAX_JSON_BYTES + 1))
        }
    }

    @Test fun zipLoadsOnlyJsonWithoutExtractingPaths() {
        val entries = listOf("../nested/one.JSON" to openSpool.toByteArray(), "datasheet.pdf" to ByteArray(100))
        val files = Payloads.readZip(ByteArrayInputStream(zip(entries)))
        assertEquals(1, files.size)
        assertEquals("one.JSON", files.single().name)
    }

    @Test fun zipExpandedSizeIsBoundedIncludingNonJson() {
        assertThrows(PayloadException::class.java) {
            Payloads.readZip(ByteArrayInputStream(zip(listOf("large.pdf" to ByteArray(Payloads.MAX_IMPORT_BYTES + 1)))))
        }
    }

    @Test fun zipMustContainValidJson() {
        assertThrows(PayloadException::class.java) {
            Payloads.readZip(ByteArrayInputStream(zip(listOf("readme.txt" to byteArrayOf()))))
        }
        assertThrows(PayloadException::class.java) {
            Payloads.readZip(ByteArrayInputStream(zip(listOf("bad.json" to "{".toByteArray()))))
        }
    }

    @Test fun zipFileCountIsBounded() {
        val entries = (0..Payloads.MAX_FILES).map { "$it.json" to "{}".toByteArray() }
        assertThrows(PayloadException::class.java) { Payloads.readZip(ByteArrayInputStream(zip(entries))) }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
