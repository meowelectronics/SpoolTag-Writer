package io.spooltag.writer

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream

class PayloadException(message: String) : IOException(message)

@Serializable
data class PayloadFile(val id: String, val name: String, val json: String) {
    fun brand(): String = ((Json.parseToJsonElement(json) as JsonObject)["brand"] as? JsonPrimitive)?.content.orEmpty()

    fun withBrand(brand: String): PayloadFile {
        require(brand.isNotBlank()) { "Manufacturer cannot be empty." }
        val obj = Json.parseToJsonElement(json) as JsonObject
        return copy(json = JsonObject(obj + ("brand" to JsonPrimitive(brand.trim()))).toString())
    }

    fun summary(): String {
        val obj = Json.parseToJsonElement(json) as JsonObject
        return listOf("brand", "type", "color_hex").mapNotNull { key ->
            (obj[key] as? JsonPrimitive)?.content
        }.joinToString(" | ").ifEmpty { "JSON object" }
    }
}

object Payloads {
    fun updateLibrary(current: List<PayloadFile>, imported: List<PayloadFile>, replace: Boolean): List<PayloadFile> {
        val merged = if (replace) mutableListOf() else current.toMutableList()
        for (entry in imported) {
            val index = merged.indexOfFirst { it.name == entry.name }
            if (index >= 0) merged[index] = entry.copy(id = merged[index].id) else merged += entry
        }
        if (merged.size > MAX_FILES) throw PayloadException("Library limit: $MAX_FILES JSON files.")
        return merged.toList()
    }

    const val MAX_JSON_BYTES = 64 * 1024
    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 256
    const val MAX_FILES = 100

    fun parse(name: String, bytes: ByteArray): PayloadFile {
        if (bytes.size > MAX_JSON_BYTES) throw PayloadException("$name exceeds the 64 KiB JSON limit.")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            throw PayloadException("$name is not valid UTF-8.")
        }
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw PayloadException("$name must contain a JSON object.")
        } catch (e: SerializationException) {
            throw PayloadException("$name contains invalid JSON: ${e.message}")
        }
        fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (string("protocol") == "openspool") {
            val required = listOf("protocol", "version", "type", "color_hex", "brand", "min_temp", "max_temp")
            for (key in required) {
                if (string(key).isNullOrBlank()) {
                    throw PayloadException("$name: OpenSpool requires a nonempty string '$key'.")
                }
            }
            if (string("version") != "1.0") throw PayloadException("$name: unsupported OpenSpool version.")
            if (!Regex("[0-9A-Fa-f]{6}").matches(string("color_hex").orEmpty())) {
                throw PayloadException("$name: color_hex must have six hex digits, without #.")
            }
            val min = string("min_temp")?.toIntOrNull()
            val max = string("max_temp")?.toIntOrNull()
            if (min == null || max == null || min !in 0..500 || max !in min..500) {
                throw PayloadException("$name: invalid OpenSpool hotend temperature range.")
            }
        }
        // Compact serialization preserves JSON values without adding NDEF text-record metadata.
        return PayloadFile(UUID.randomUUID().toString(), name, obj.toString())
    }

    fun readJson(name: String, input: InputStream): PayloadFile =
        parse(name, readLimited(input, MAX_JSON_BYTES, name))

    fun readZip(input: InputStream): List<PayloadFile> {
        val result = mutableListOf<PayloadFile>()
        var total = 0
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw PayloadException("ZIP has more than $MAX_ENTRIES entries.")
                val bytes = readLimited(zip, MAX_IMPORT_BYTES - total, "ZIP expanded content")
                total += bytes.size
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    if (result.size >= MAX_FILES) throw PayloadException("Import at most $MAX_FILES JSON files.")
                    val name = entry.name.replace('\\', '/').substringAfterLast('/')
                    result += parse(name, bytes)
                }
                zip.closeEntry()
            }
        }
        if (result.isEmpty()) throw PayloadException("ZIP contains no JSON files.")
        return result
    }

    private fun readLimited(input: InputStream, limit: Int, name: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw PayloadException("$name exceeds the import size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
