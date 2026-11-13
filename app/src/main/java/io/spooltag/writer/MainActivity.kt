package io.spooltag.writer

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val model: WriterViewModel by viewModels()
    private var adapter: NfcAdapter? = null
    private var nfcEnabled by mutableStateOf(false)
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = NfcAdapter.getDefaultAdapter(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                WriterScreen(model, adapter != null, nfcEnabled) {
                    startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enableTagCapture()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed) enableTagCapture()
    }

    private fun enableTagCapture() {
        nfcEnabled = adapter?.isEnabled == true
        if (nfcEnabled) {
            try {
                // Reader callbacks avoid the activity pause/relaunch caused by intent dispatch.
                // Keep NDEF detection enabled: skipping it makes Ndef.get(tag) unavailable.
                adapter?.enableReaderMode(
                    this,
                    { tag -> model.scanned(tag) },
                    READER_FLAGS,
                    Bundle().apply {
                        putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
                    },
                )
            } catch (e: IllegalStateException) {
                model.nfcError(e)
            } catch (e: SecurityException) {
                model.nfcError(e)
            }
        }
    }

    override fun onPause() {
        resumed = false
        adapter?.disableReaderMode(this)
        model.paused()
        super.onPause()
    }

    companion object {
        internal const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
    }
}

@Composable
private fun WriterScreen(model: WriterViewModel, hasNfc: Boolean, nfcEnabled: Boolean, settings: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var editBrand by remember { mutableStateOf(false) }
    var brand by remember { mutableStateOf("") }
    var refreshUri by rememberSaveable { mutableStateOf<String?>(null) }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        model.importFiles(it, zip = false)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.importFiles(listOf(it), zip = true)
    }
    val refreshPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        refreshUri = it?.toString()
    }
    val selected = model.selected
    val enabled = !model.busy && model.mode == ScanMode.Idle && model.confirmation == null
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SpoolTag Writer", style = MaterialTheme.typography.headlineMedium)
            Text("v1.0.5 | NDEF MIME: application/json", style = MaterialTheme.typography.bodyMedium)
            if (!hasNfc) {
                Text("This device has no NFC reader.", color = MaterialTheme.colorScheme.error)
            } else if (!nfcEnabled) {
                OutlinedButton(onClick = settings) { Text("Enable NFC in Settings") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = enabled,
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                ) { Text("Open ZIP") }
                OutlinedButton(
                    enabled = enabled,
                    onClick = {
                        refreshPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    },
                ) { Text("Refresh ZIP") }
                OutlinedButton(
                    enabled = enabled,
                    onClick = { jsonPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                ) { Text("Choose JSON") }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search filename, material or color hex") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val filtered = model.files.filter {
                it.name.contains(search, ignoreCase = true) || it.summary().contains(search, ignoreCase = true)
            }
            LazyColumn(
                modifier = Modifier.heightIn(min = 100.dp, max = 260.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (model.files.isEmpty()) {
                    item { Text("No files imported. Open your OpenSpool payload ZIP or choose JSON files.") }
                } else if (filtered.isEmpty()) {
                    item { Text("No matching files.") }
                }
                items(filtered, key = { it.id }) { file ->
                    Card(
                        onClick = { model.select(file.id) },
                        enabled = enabled,
                        colors = CardDefaults.cardColors(
                            containerColor = if (file.id == selected?.id) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text(file.summary())
                        }
                    }
                }
            }
            selected?.let {
                Text("Selected: ${it.name}", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    enabled = enabled,
                    onClick = { brand = it.brand(); editBrand = true },
                ) { Text("Manufacturer: ${it.brand()} — edit") }
                Text(
                    "${TagWriter.message(it).toByteArray().size} NDEF bytes. Use NTAG 215/216 for OpenSpool.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (model.error) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    model.status,
                    modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                )
            }
            if (model.busy) CircularProgressIndicator()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = model::armWrite,
                    enabled = enabled && selected != null && hasNfc && nfcEnabled,
                ) { Text("Write tag") }
                OutlinedButton(
                    onClick = model::armRead,
                    enabled = enabled && hasNfc && nfcEnabled,
                ) { Text("Read tag") }
                TextButton(onClick = { preview = true }, enabled = selected != null) { Text("JSON") }
            }
            if (model.mode != ScanMode.Idle && !model.busy) {
                OutlinedButton(onClick = model::cancel) { Text("Cancel scan") }
            }
        }
    }
    model.confirmation?.let {
        AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text(if (it.result.consent.format) "Format this tag?" else "Replace tag contents?") },
            text = { Text("${it.result.description}\n\nNew payload: ${it.payload.name}\nRemove the tag before confirming; then retap it.") },
            confirmButton = { TextButton(onClick = model::confirmOverwrite) { Text("Confirm and retap") } },
            dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel") } },
        )
    }
    refreshUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { refreshUri = null },
            title = { Text("Refresh entire library?") },
            text = {
                Text("Replace all ${model.files.size} saved entries with the JSON files in the selected ZIP, including new filenames and brands. Local manufacturer edits will be replaced. Physical tags are not changed. If import fails, your current library stays intact.")
            },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    search = ""
                    model.importFiles(listOf(Uri.parse(uri)), zip = true, replace = true)
                    refreshUri = null
                }) { Text("Refresh and replace") }
            },
            dismissButton = { TextButton(onClick = { refreshUri = null }) { Text("Cancel") } },
        )
    }
    if (editBrand) {
        AlertDialog(
            onDismissRequest = { editBrand = false },
            title = { Text("Actual manufacturer") },
            text = {
                Column {
                    Text("For your U1, enter Aurapol or Devil Design. Material and temperatures are unchanged. This does not install a slicer profile.")
                    OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("Manufacturer") })
                }
            },
            confirmButton = {
                TextButton(enabled = brand.isNotBlank(), onClick = { model.setBrand(brand); editBrand = false }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { editBrand = false }) { Text("Cancel") } },
        )
    }
    if (preview && selected != null) {
        ContentDialog("JSON payload: ${selected.name}", selected.json) { preview = false }
    }
    model.readResult?.let { ContentDialog("Tag contents", it, model::dismissRead) }
}

@Composable
private fun ContentDialog(title: String, text: String, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}
