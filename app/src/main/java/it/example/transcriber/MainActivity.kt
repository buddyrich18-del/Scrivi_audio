package it.example.transcriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var ready by remember { mutableStateOf(Transcriber.isModelReady(this@MainActivity)) }
                var progress by remember { mutableStateOf<Float?>(null) }
                var err by remember { mutableStateOf<String?>(null) }
                var history by remember { mutableStateOf(store.load()) }
                var open by remember { mutableStateOf<Entry?>(null) }

                Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding()) {
                    Text("Transcriber", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Su WhatsApp: tieni premuto sul vocale → Condividi → Trascrivi",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))

                    if (ready) {
                        Text("Modello offline italiano: pronto", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Button(
                            enabled = progress == null,
                            onClick = {
                                err = null
                                progress = 0f
                                scope.launch {
                                    runCatching {
                                        Transcriber.downloadModel(this@MainActivity) { progress = it }
                                    }.onSuccess { ready = true }
                                        .onFailure { err = it.message }
                                    progress = null
                                }
                            }
                        ) { Text("Scarica modello offline (~50 MB)") }
                        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
                        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Cronologia", style = MaterialTheme.typography.titleMedium)

                    LazyColumn(Modifier.weight(1f)) {
                        items(history, key = { it.time }) { e ->
                            Column(Modifier.fillMaxWidth().clickable { open = e }.padding(vertical = 10.dp)) {
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(e.time)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(e.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                }

                open?.let { e ->
                    AlertDialog(
                        onDismissRequest = { open = null },
                        text = { Text(e.text) },
                        confirmButton = { TextButton(onClick = { open = null }) { Text("Chiudi") } },
                        dismissButton = {
                            TextButton(onClick = {
                                store.delete(e); history = store.load(); open = null
                            }) { Text("Elimina") }
                        }
                    )
                }
            }
        }
    }
}
