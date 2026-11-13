package io.spooltag.writer

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException
import java.security.MessageDigest

data class WriteConsent(val uid: String, val previousHash: String?, val format: Boolean)

sealed interface TagResult {
    data class Confirm(val consent: WriteConsent, val description: String) : TagResult
    data class Written(val uid: String, val bytes: Int) : TagResult
    data class RetapToVerify(val uid: String, val reason: String) : TagResult
}

object TagWriter {
    const val MIME = "application/json"

    fun message(payload: PayloadFile): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createMime(MIME, payload.json.toByteArray(Charsets.UTF_8))))

    fun matches(message: NdefMessage?, payload: PayloadFile): Boolean {
        val records = message?.records ?: return false
        if (records.size != 1) return false
        val record = records.single()
        return record.tnf == NdefRecord.TNF_MIME_MEDIA &&
            record.type.contentEquals(MIME.toByteArray(Charsets.US_ASCII)) &&
            record.payload.contentEquals(payload.json.toByteArray(Charsets.UTF_8))
    }

    @Throws(IOException::class, FormatException::class)
    fun write(tag: Tag, payload: PayloadFile, consent: WriteConsent?): TagResult {
        val uid = uid(tag)
        if (consent != null && consent.uid != uid) {
            throw IOException("Different tag detected. Nothing was written. Select Write tag to start again.")
        }
        val message = message(payload)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                if (!it.isWritable) throw IOException("This tag is read-only. Nothing was written.")
                val size = message.toByteArray().size
                if (size > it.maxSize) {
                    throw IOException("Tag is too small: message $size bytes, capacity ${it.maxSize} bytes.")
                }
                val old = it.ndefMessage
                if (matches(old, payload)) return TagResult.Written(uid, size)
                val hash = old?.toByteArray()?.let(::hash)
                if (consent != null && (consent.format || consent.previousHash != hash)) {
                    throw IOException("The tag content changed after confirmation. Nothing was written.")
                }
                if (old != null && old.records.isNotEmpty() && consent == null) {
                    return TagResult.Confirm(
                        WriteConsent(uid, hash, false),
                        "Tag $uid contains ${old.records.size} NDEF record(s). Writing will replace ALL of them.",
                    )
                }
                try {
                    it.writeNdefMessage(message)
                    if (!matches(it.ndefMessage, payload)) {
                        return TagResult.RetapToVerify(uid, "Write returned, but immediate read-back did not match.")
                    }
                } catch (e: IOException) {
                    android.util.Log.w("SpoolTagWriter", "Write/read-back outcome uncertain; require same-tag verification.", e)
                    return TagResult.RetapToVerify(uid, "Write/read-back connection failed: ${e.message.orEmpty()}")
                }
                return TagResult.Written(uid, size)
            }
        }
        val formatable = NdefFormatable.get(tag)
            ?: throw IOException("This tag does not support Android NDEF writing/formatting. Use an NTAG 215 or 216.")
        if (consent == null) {
            return TagResult.Confirm(
                WriteConsent(uid, null, true),
                "Tag $uid is not NDEF formatted. Formatting may replace existing data. Continue only with a blank tag.",
            )
        }
        if (!consent.format) throw IOException("The tag technology changed. Nothing was written.")
        formatable.use {
            it.connect()
            it.format(message)
        }
        // Android may expose Ndef only after the newly formatted tag is rediscovered.
        return TagResult.RetapToVerify(uid, "Tag was formatted and written.")
    }

    @Throws(IOException::class, FormatException::class)
    fun verify(tag: Tag, payload: PayloadFile, expectedUid: String): TagResult.Written {
        if (uid(tag) != expectedUid) throw IOException("Scan the SAME tag to verify the format/write.")
        val ndef = Ndef.get(tag) ?: throw IOException("Tag is still not readable as NDEF. Write is NOT verified.")
        ndef.use {
            it.connect()
            if (!matches(it.ndefMessage, payload)) throw IOException("Verification failed. Tag content does not match the selected JSON.")
        }
        return TagResult.Written(expectedUid, message(payload).toByteArray().size)
    }

    @Throws(IOException::class, FormatException::class)
    fun read(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: throw IOException("This tag is not NDEF readable.")
        ndef.use {
            it.connect()
            val message = it.ndefMessage ?: return "Tag ${uid(tag)} has no NDEF message."
            return buildString {
                appendLine("Tag ${uid(tag)}")
                appendLine("${message.records.size} record(s), capacity ${it.maxSize} bytes")
                for ((index, record) in message.records.withIndex()) {
                    appendLine()
                    appendLine("Record ${index + 1}: TNF ${record.tnf}, type ${String(record.type, Charsets.US_ASCII)}")
                    if (record.tnf == NdefRecord.TNF_MIME_MEDIA &&
                        record.type.contentEquals(MIME.toByteArray(Charsets.US_ASCII))
                    ) {
                        appendLine(String(record.payload, Charsets.UTF_8))
                    } else {
                        appendLine("Payload: ${record.payload.size} bytes (not application/json)")
                    }
                }
            }
        }
    }

    private fun uid(tag: Tag): String = tag.id.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
