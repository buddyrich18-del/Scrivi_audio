package it.example.transcriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Riceve l'audio da "Condividi" e mostra la trascrizione. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

        val store = Store(this)

        setContent {
            MaterialTheme {
                var text by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var status by remember { mutableStateOf("Trascrizione…") }

                LaunchedEffect(uri) {
                    if (uri == null) { error = "Nessun audio ricevuto"; return@LaunchedEffect }
                    runCatching {
                        if (!Transcriber.isModelReady(this@ShareActivity)) {
                            Transcriber.downloadModel(this@ShareActivity) {
                                status = "Scarico il modello (solo la prima volta)… ${(it * 100).toInt()}%"
                            }
                            status = "Trascrizione…"
                        }
                        Transcriber.transcribe(this@ShareActivity, uri)
                    }.onSuccess { text = it; store.add(it) }
                        .onFailure { error = it.message ?: "Errore" }
                }

                Column(
                    Modifier.padding(20.dp).fillMaxWidth()
                ) {
                    when {
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        text == null -> Row {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(status)
                        }
                        else -> {
                            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                Text(text!!, style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { copy(text!!) }) { Text("Copia") }
                                OutlinedButton(onClick = { share(text!!) }) { Text("Condividi") }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { finish() }) { Text("Chiudi") }
                }
            }
        }
    }

    private fun copy(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("trascrizione", t))
    }

    private fun share(t: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t),
                null
            )
        )
    }
}
