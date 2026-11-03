package com.chrysalis.treeid.ui

import android.Manifest
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel
import com.chrysalis.treeid.birds.*
import java.io.File
import java.util.Locale

@Composable
fun BirdRecordScreen(vm: TreeViewModel, birds: BirdViewModel) {
    var includeGps by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable(birds.clip?.time) { mutableStateOf("") }
    var selected by rememberSaveable(birds.clip?.time) { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable(birds.clip?.time) { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) birds.start(includeGps) else birds.microphoneDenied()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) birds.cancelCapture() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); birds.cancelCapture() }
    }
    BackHandler(birds.stage == BirdStage.SAVING) { }
    Scaffold(topBar = { BackTopBar("Bird calls") { if (birds.stage != BirdStage.SAVING) vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Listen, then confirm", style = MaterialTheme.typography.headlineSmall)
            Text("Record 12 seconds near a calling bird. BirdNET runs entirely on this phone. Recordings and GPS stay local; there are no uploads or downloads.")
            TextButton(onClick = { vm.navigate(Screen.BirdObservations) }, enabled = birds.stage == BirdStage.IDLE) { Text("Saved bird observations") }
            when (birds.stage) {
                BirdStage.RECORDING -> {
                    Text("Recording ${birds.elapsed} / ${BirdAudio.SECONDS} seconds…")
                    LinearProgressIndicator(progress = { birds.elapsed.toFloat() / BirdAudio.SECONDS }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = birds::cancelCapture) { Text("Cancel recording") }
                }
                BirdStage.ANALYZING -> { CircularProgressIndicator(); Text("Identifying on this phone…"); TextButton(onClick = birds::cancelCapture) { Text("Cancel") } }
                BirdStage.SAVING -> { CircularProgressIndicator(); Text("Saving locally…") }
                BirdStage.IDLE -> {
                    val clip = birds.clip
                    if (clip == null) {
                        Row { Checkbox(checked = includeGps, onCheckedChange = { includeGps = it }); Text("Include GPS if available", Modifier.padding(top = 12.dp)) }
                        Text("Location is optional. Denying GPS still allows recording. Leaving this screen or locking the phone cancels capture.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            permissions.launch(if (includeGps) arrayOf(Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            else arrayOf(Manifest.permission.RECORD_AUDIO))
                        }) { Text("Record bird call") }
                    } else {
                        BirdPlayback(clip.preview)
                        Text(if (clip.location != null) "GPS captured with this recording." else if (clip.requestedGps)
                            "GPS unavailable or permission denied. This observation will have no map pin." else "GPS was off for this recording.")
                        Text("Possible matches", style = MaterialTheme.typography.titleMedium)
                        Text("Scores rank suggestions; they are not a measured chance of being correct. Check what you heard and the species' range. Some other animal calls are included.", style = MaterialTheme.typography.bodySmall)
                        if (clip.predictions.isEmpty()) Text("No clear match. Try a closer recording, or save as an unknown call.")
                        clip.predictions.forEach { prediction ->
                            Row(Modifier.fillMaxWidth().clickable { selected = prediction.label.key; confirmed = true }) {
                                RadioButton(selected = confirmed && selected == prediction.label.key, onClick = { selected = prediction.label.key; confirmed = true })
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(prediction.label.commonName)
                                    Text("${prediction.label.key} · score ${birdScore(prediction.score)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable { selected = null; confirmed = true }) {
                            RadioButton(selected = confirmed && selected == null, onClick = { selected = null; confirmed = true })
                            Text("Unknown call", Modifier.padding(top = 12.dp))
                        }
                        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { birds.save(selected, notes) { id -> vm.back(); vm.navigate(Screen.BirdDetail(id)) } }, enabled = confirmed) { Text("Confirm and save") }
                        OutlinedButton(onClick = birds::discard) { Text("Discard and record again") }
                    }
                }
            }
            birds.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { showLicense = true }) { Text("BirdNET 2.4 · credits and license") }
            Text("For noncommercial use. All processing is offline. No background listening.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showLicense) BirdLicense { showLicense = false }
}

@Composable
fun BirdObservationsScreen(vm: TreeViewModel, birds: BirdViewModel) {
    val records by birds.observations.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Bird observations") { vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { vm.navigate(Screen.BirdRecord) }) { Text("Record a call") }
                TextButton(onClick = { vm.navigate(Screen.SightingMap()) }) { Text("Map of sightings") }
            }
            birds.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (records == null) Text("Loading…", Modifier.padding(16.dp))
            else if (records!!.isEmpty()) Text("No saved bird observations yet.", Modifier.padding(16.dp))
            LazyColumn {
                items(records.orEmpty(), key = { it.id }) { row ->
                    ListItem(headlineContent = { Text(birds.catalog[row.speciesKey]?.commonName ?: "Unknown call") },
                        supportingContent = { Text("${sightingDate(row.observedAt)} · ${if (row.latitude != null) "GPS" else "No location"}") },
                        modifier = Modifier.clickable { vm.navigate(Screen.BirdDetail(row.id)) })
                }
            }
        }
    }
}

@Composable
fun BirdDetailScreen(vm: TreeViewModel, birds: BirdViewModel, id: Long) {
    val records by birds.observations.collectAsStateWithLifecycle()
    val record = records?.find { it.id == id }
    var notes by rememberSaveable(id, record?.notes) { mutableStateOf(record?.notes.orEmpty()) }
    var selected by rememberSaveable(id, record?.speciesKey) { mutableStateOf(record?.speciesKey) }
    var changingSpecies by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(birds.editing) { }
    Scaffold(topBar = { BackTopBar("Bird observation") { if (!birds.editing) vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (record == null) Text(if (records == null) "Loading…" else "Observation not found.")
            else {
                Text(birds.catalog[selected]?.commonName ?: "Unknown call", style = MaterialTheme.typography.headlineSmall)
                selected?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(sightingDate(record.observedAt))
                Text("${record.durationSeconds} seconds · ${record.modelVersion}")
                if (selected == record.speciesKey) record.score?.let { Text("BirdNET score ${birdScore(it)} · identification confirmed by you") }
                TextButton(onClick = { changingSpecies = true }, enabled = !birds.editing) { Text("Change identification") }
                BirdPlayback(birds.audio(record.audioFileName))
                if (record.latitude != null && record.longitude != null) {
                    Text(String.format(Locale.US, "%.5f, %.5f%s", record.latitude, record.longitude,
                        record.accuracyMeters?.let { " · GPS accuracy ±${it.toInt()} m" } ?: ""))
                    TextButton(onClick = { vm.navigate(Screen.SightingMap(focusBirdId = id)) }) { Text("Show on map") }
                } else Text("No location saved")
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), enabled = !birds.editing)
                Button(onClick = { birds.update(id, selected, notes) { vm.back() } }, enabled = !birds.editing) { Text("Save changes") }
                TextButton(onClick = { confirmDelete = true }, enabled = !birds.editing) { Text("Delete observation") }
            }
            birds.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (changingSpecies) BirdSpeciesPicker(birds.catalog, { selected = it; changingSpecies = false }, { changingSpecies = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete bird observation?") },
        text = { Text("This permanently deletes this observation and its local recording.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; birds.delete(id) { vm.back(); if (vm.current != Screen.BirdObservations) vm.navigate(Screen.BirdObservations) } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun BirdSpeciesPicker(catalog: BirdCatalog, choose: (String?) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { catalog.labels.filter { it.key !in BirdCatalog.NON_EVENTS &&
        (it.commonName.contains(query, true) || it.key.contains(query, true)) }.take(50) }
    AlertDialog(onDismissRequest = close, title = { Text("Correct identification") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search species") })
            TextButton(onClick = { choose(null) }) { Text("Unknown call") }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(matches, key = { it.key }) { label -> TextButton(onClick = { choose(label.key) }) { Text("${label.commonName}\n${label.key}") } }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun BirdPlayback(file: File) {
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun stop() { player?.release(); player = null }
    DisposableEffect(file, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    OutlinedButton(onClick = {
        if (player != null) stop()
        else {
            failure = null
            val next = MediaPlayer()
            try {
                next.setDataSource(file.absolutePath)
                next.setOnCompletionListener { stop() }
                next.setOnErrorListener { _, _, _ -> failure = "Couldn't play this recording."; stop(); true }
                next.prepare(); player = next; next.start()
            } catch (_: Exception) { next.release(); player = null; failure = "Couldn't open this recording." }
        }
    }) { Text(if (player == null) "Play recording" else "Stop playback") }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun BirdLicense(close: () -> Unit) {
    val context = LocalContext.current
    val text = remember { listOf("birds/NOTICE.txt", "birds/LICENSE-CC-BY-NC-SA-4.0.txt").joinToString("\n\n") { name ->
        context.assets.open(name).bufferedReader().use { it.readText() }
    } }
    AlertDialog(onDismissRequest = close, title = { Text("BirdNET credits") }, text = {
        Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

private fun birdScore(score: Float): String = String.format(Locale.US, "%.2f", score)
