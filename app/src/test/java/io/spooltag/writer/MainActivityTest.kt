package io.spooltag.writer

import android.nfc.NfcAdapter
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityTest {
    @Test fun readerCaptureRestoresOnFocusAndResumeButStopsOnPause() {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        val adapter = NfcAdapter.getDefaultAdapter(RuntimeEnvironment.getApplication())
        val shadow = shadowOf(adapter)
        shadow.setEnabled(true)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            assertTrue(shadow.isInReaderMode)
            assertNull(shadow.intent)
            assertEquals(0, MainActivity.READER_FLAGS and NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK)
            assertTrue(MainActivity.READER_FLAGS and NfcAdapter.FLAG_READER_NFC_A != 0)
            val model = ViewModelProvider(activity)[WriterViewModel::class.java]
            ShadowLooper.idleMainLooper()
            adapter.disableReaderMode(activity)
            assertFalse(shadow.isInReaderMode)
            activity.onWindowFocusChanged(true)
            assertTrue(shadow.isInReaderMode)
            controller.pause()
            assertFalse(shadow.isInReaderMode)
            assertEquals(ScanMode.Idle, model.mode)
            activity.onWindowFocusChanged(true)
            assertFalse(shadow.isInReaderMode)
            controller.resume()
            assertTrue(shadow.isInReaderMode)
            assertTrue(adapter.isEnabled)
        }
    }

    @Test fun tagCallbackDoesNotLaunchAnActivityOrPauseReaderCapture() {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        val shadow = shadowOf(NfcAdapter.getDefaultAdapter(RuntimeEnvironment.getApplication()))
        shadow.setEnabled(true)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            val model = ViewModelProvider(activity)[WriterViewModel::class.java]
            awaitReady(model)
            model.armRead()
            assertEquals(ScanMode.Read, model.mode)
            shadow.dispatchTagDiscovered(ShadowNfcAdapter.createMockTag())
            ShadowLooper.idleMainLooper()
            awaitReady(model)
            assertTrue(shadow.isInReaderMode)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(ScanMode.Idle, model.mode)
            assertFalse(model.status.contains("Scan cancelled"))
            assertTrue(model.status.contains("This tag is not NDEF readable"))
        }
    }

    private fun awaitReady(model: WriterViewModel) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (model.busy && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertFalse("ViewModel operation did not finish", model.busy)
    }

    @Test fun android16ActivitySurvivesCreatePauseAndResume() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            val model = ViewModelProvider(activity)[WriterViewModel::class.java]
            assertEquals(ScanMode.Idle, model.mode)
            controller.pause().resume()
            assertEquals(ScanMode.Idle, model.mode)
        }
    }
}
