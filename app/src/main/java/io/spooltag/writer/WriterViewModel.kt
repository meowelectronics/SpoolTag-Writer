package io.spooltag.writer

import android.app.Application
import android.net.Uri
import android.nfc.FormatException
import android.nfc.Tag
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

sealed interface ScanMode {
    data object Idle : ScanMode
    data object Read : ScanMode
    data class Write(val payload: PayloadFile, val consent: WriteConsent? = null) : ScanMode
    data class Verify(val payload: PayloadFile, val uid: String) : ScanMode
}

data class Confirmation(val payload: PayloadFile, val result: TagResult.Confirm)

class WriterViewModel(application: Application) : AndroidViewModel(application) {
    var files by mutableStateOf<List<PayloadFile>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("Import your OpenSpool ZIP or JSON files to begin.")
        private set
    var error by mutableStateOf(false)
        private set
    var busy by mutableStateOf(true)
        private set
    var mode by mutableStateOf<ScanMode>(ScanMode.Idle)
        private set
    var confirmation by mutableStateOf<Confirmation?>(null)
        private set
    var readResult by mutableStateOf<String?>(null)
        private set
    private val store = AtomicFile(File(application.filesDir, "payload-library.json"))
    private var libraryHealthy = true
    val selected: PayloadFile? get() = files.find { it.id == selectedId }

    init {
        viewModelScope.launch {
            try {
                files = withContext(Dispatchers.IO) {
                    if (!store.baseFile.exists() && !File("${store.baseFile.path}.bak").exists()) emptyList()
                    else store.openRead().use {
                        Json.decodeFromString<List<PayloadFile>>(it.readBytes().toString(Charsets.UTF_8)).also { saved ->
                            saved.forEach { entry -> Payloads.parse(entry.name, entry.json.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
                selectedId = files.firstOrNull()?.id
                if (files.isNotEmpty()) status = "Restored ${files.size} saved JSON file(s)."
            } catch (e: IOException) {
                libraryHealthy = false
                failure("Could not load saved library. Existing data was preserved.", e)
            } catch (e: SerializationException) {
                libraryHealthy = false
                failure("Saved library is invalid. Existing data was preserved.", e)
            } finally {
                busy = false
            }
        }
    }

    fun select(id: String) {
        if (busy) return
        cancel()
        selectedId = id
    }

    fun importFiles(uris: List<Uri>, zip: Boolean, replace: Boolean = false) {
        if (busy || uris.isEmpty()) return
        if (!libraryHealthy) {
            status = "Import blocked to protect an unreadable saved library. Clear app storage only after keeping your source files."
            error = true
            return
        }
        cancel()
        busy = true
        viewModelScope.launch {
            try {
                val newLibrary = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    if (uris.size > Payloads.MAX_FILES) throw PayloadException("Select at most ${Payloads.MAX_FILES} files.")
                    val imported = uris.flatMap { uri ->
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: if (zip) "import.zip" else "import.json"
                        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $name.")
                        input.use { stream ->
                            if (zip) Payloads.readZip(stream) else listOf(Payloads.readJson(name, stream))
                        }
                    }
                    val merged = Payloads.updateLibrary(files, imported, replace)
                    val bytes = Json.encodeToString(merged).toByteArray(Charsets.UTF_8)
                    val output = store.startWrite()
                    try {
                        output.write(bytes)
                        store.finishWrite(output)
                    } catch (e: IOException) {
                        store.failWrite(output)
                        throw e
                    }
                    merged.toList()
                }
                files = newLibrary
                selectedId = selectedId?.takeIf { id -> files.any { it.id == id } } ?: files.firstOrNull()?.id
                status = if (replace) "Refreshed and saved ${files.size} JSON file(s). Old library entries were replaced."
                else "Import saved. ${files.size} JSON file(s) in your library."
                error = false
            } catch (e: IOException) {
                failure("Import failed; your previous library is unchanged.", e)
            } catch (e: SecurityException) {
                failure("File access denied. Choose the file again using the picker.", e)
            } finally {
                busy = false
            }
        }
    }

    fun armWrite() {
        if (busy) return
        selected?.let {
            mode = ScanMode.Write(it)
            status = "Hold a tag to the phone. Writing replaces its NDEF message."
            error = false
        }
    }

    fun armRead() {
        if (busy) return
        cancel()
        mode = ScanMode.Read
        status = "Hold a tag to the phone to read it. Nothing will be written."
    }

    fun confirmOverwrite() {
        confirmation?.let {
            mode = ScanMode.Write(it.payload, it.result.consent)
            status = "Remove the tag, then tap the SAME tag again to confirm writing."
            error = false
        }
        confirmation = null
    }

    fun dismissRead() { readResult = null }

    fun setBrand(brand: String) {
        if (busy || brand.isBlank() || mode != ScanMode.Idle) return
        val current = selected ?: return
        val updated = current.withBrand(brand)
        busy = true
        viewModelScope.launch {
            try {
                val next = files.map { if (it.id == current.id) updated else it }
                withContext(Dispatchers.IO) {
                    val output = store.startWrite()
                    try {
                        output.write(Json.encodeToString(next).toByteArray(Charsets.UTF_8))
                        store.finishWrite(output)
                    } catch (e: IOException) {
                        store.failWrite(output)
                        throw e
                    }
                }
                files = next
                status = "Manufacturer saved as ${brand.trim()}. Rewrite existing tags to update their brand."
                error = false
            } catch (e: IOException) {
                failure("Could not save manufacturer. Previous payload is unchanged.", e)
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        mode = ScanMode.Idle
        confirmation = null
        if (!busy) {
            status = "Ready. Choose Write tag or Read tag."
            error = false
        }
    }

    fun paused() {
        if (!busy && (mode != ScanMode.Idle || confirmation != null)) {
            cancel()
            status = "Scan cancelled because the app left the foreground. Start again."
        }
    }

    fun nfcError(e: Exception) = failure("Could not enable NFC reader mode. Check NFC settings and reopen the app.", e)

    fun scanned(tag: Tag) {
        viewModelScope.launch {
            val request = mode
            if (busy || request == ScanMode.Idle) return@launch
            busy = true
            mode = ScanMode.Idle
            try {
                when (request) {
                    is ScanMode.Write -> {
                        val result = withContext(Dispatchers.IO) { TagWriter.write(tag, request.payload, request.consent) }
                        handleWriteResult(request.payload, result)
                    }
                    is ScanMode.Verify -> {
                        val result = withContext(Dispatchers.IO) { TagWriter.verify(tag, request.payload, request.uid) }
                        handleWriteResult(request.payload, result)
                    }
                    ScanMode.Read -> {
                        readResult = withContext(Dispatchers.IO) { TagWriter.read(tag) }
                        status = "Tag read successfully. Nothing was written."
                        error = false
                    }
                    ScanMode.Idle -> Unit
                }
            } catch (e: IOException) {
                failure("NFC operation failed. If writing had started, read the tag before retrying.", e)
            } catch (e: FormatException) {
                failure("NDEF format error. Read the tag before retrying.", e)
            } catch (e: SecurityException) {
                failure("NFC access denied. Remove the tag and start again.", e)
            } finally {
                busy = false
            }
        }
    }

    private fun handleWriteResult(payload: PayloadFile, result: TagResult) {
        error = false
        when (result) {
            is TagResult.Confirm -> {
                confirmation = Confirmation(payload, result)
                status = "Confirmation required; nothing was written."
            }
            is TagResult.RetapToVerify -> {
                mode = ScanMode.Verify(payload, result.uid)
                status = "${result.reason}\nNOT verified yet. Keep this screen open, remove and retap the SAME tag. The next scan only verifies; it does not rewrite."
            }
            is TagResult.Written -> {
                status = "VERIFIED: ${payload.name}, ${result.bytes} NDEF bytes, tag ${result.uid}. Safe to remove."
            }
        }
    }

    private fun failure(message: String, exception: Exception) {
        Log.e("SpoolTagWriter", message, exception)
        status = "$message\n${exception.message.orEmpty()}"
        error = true
    }
}
