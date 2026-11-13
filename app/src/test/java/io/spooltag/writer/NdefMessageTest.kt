package io.spooltag.writer

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NdefMessageTest {
    private val payload = Payloads.parse("test.json", """{"type":"PLA","name":"Bílá"}""".toByteArray())

    @Test fun wireMessageIsExactlyOneMimeRecordWithUtf8Json() {
        val decoded = NdefMessage(TagWriter.message(payload).toByteArray())
        assertEquals(1, decoded.records.size)
        val record = decoded.records.single()
        assertEquals(NdefRecord.TNF_MIME_MEDIA, record.tnf)
        assertEquals("application/json", String(record.type, Charsets.US_ASCII))
        assertEquals(payload.json, String(record.payload, Charsets.UTF_8))
        assertTrue(TagWriter.matches(decoded, payload))
    }

    @Test fun verificationRejectsTextRecordsDifferentPayloadsAndExtraRecords() {
        assertFalse(TagWriter.matches(null, payload))
        assertFalse(TagWriter.matches(NdefMessage(arrayOf(NdefRecord.createTextRecord("en", payload.json))), payload))
        assertFalse(TagWriter.matches(NdefMessage(arrayOf(NdefRecord.createMime("application/json", "{}".toByteArray()))), payload))
        val record = TagWriter.message(payload).records.single()
        assertFalse(TagWriter.matches(NdefMessage(arrayOf(record, record)), payload))
    }
}
