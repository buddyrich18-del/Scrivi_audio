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
                var model by remember { mutableStateOf(store.model) }
                var lang by remember { mutableStateOf(store.language) }
                var ready by remember { mutableStateOf(Transcriber.isModelReady(this@MainActivity, model)) }
                var modelMenu by remember { mutableStateOf(false) }
                var langMenu by remember { mutableStateOf(false) }
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            OutlinedButton(enabled = progress == null, onClick = { modelMenu = true }) {
                                Text(model.label.substringBefore(" ("))
                            }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                Models.all.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.label) },
                                        onClick = {
                                            modelMenu = false
                                            model = m
                                            store.model = m
                                            ready = Transcriber.isModelReady(this@MainActivity, m)
                                            err = null
                                        }
                                    )
                                }
                            }
                        }
                        Box {
                            OutlinedButton(onClick = { langMenu = true }) { Text(lang.name) }
                            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                                Languages.all.forEach { l ->
                                    DropdownMenuItem(
                                        text = { Text(l.name) },
                                        onClick = {
                                            langMenu = false
                                            lang = l
                                            store.language = l
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (ready) {
                        Text("Modello pronto", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Button(
                            enabled = progress == null,
                            onClick = {
                                val target = model
                                err = null
                                progress = 0f
                                scope.launch {
                                    runCatching {
                                        Transcriber.downloadModel(this@MainActivity, target) { progress = it }
                                    }.onSuccess { if (model == target) ready = true }
                                        .onFailure { err = it.message }
                                    progress = null
                                }
                            }
                        ) { Text("Scarica modello") }
                        progress?.let {
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
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
