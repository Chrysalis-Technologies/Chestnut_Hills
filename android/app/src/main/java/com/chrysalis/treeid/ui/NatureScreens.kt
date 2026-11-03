package com.chrysalis.treeid.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel
import com.chrysalis.treeid.nature.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun NatureResultScreen(vm: TreeViewModel, nature: NatureViewModel) {
    val result = nature.identification
    var key by rememberSaveable(result?.time) { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(result?.time) { mutableStateOf(false) }
    var notes by rememberSaveable(result?.time) { mutableStateOf("") }
    var includeGps by rememberSaveable { mutableStateOf(true) }
    var showCoverage by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    val finishSave: (Long) -> Unit = { id -> vm.back(); vm.navigate(Screen.NatureDetail(id)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nature.saveAfterPermission(finishSave) }
    fun back() { if (nature.saveStage != NatureSaveStage.SAVING) { nature.cancelIdentification(); vm.back() } }
    BackHandler { back() }
    Scaffold(topBar = { BackTopBar(result?.kind?.title ?: "Photo identification", ::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null) Text("Choose a plant or mushroom photo from the camera screen.")
            else {
                NatureCaution(result.kind)
                Image(result.photo.asImageBitmap(), "Photo being identified", Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Fit)
                Text("${nature.catalog.supported(result.kind).size} supported taxa; ${nature.catalog.supported(result.kind, true).size} have New York records in the bundled snapshot. Species outside this catalog may be misidentified.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showCoverage = true }) { Text("See supported species") }
                if (result.analyzing) { CircularProgressIndicator(); Text("Identifying on this phone…") }
                else {
                    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Possible matches", style = MaterialTheme.typography.titleMedium)
                    Text(result.modelVersion, style = MaterialTheme.typography.bodySmall)
                    Text("Model scores rank suggestions; they are not a measured chance of being correct. Compare the whole specimen before choosing a label.", style = MaterialTheme.typography.bodySmall)
                    if (result.predictions.isEmpty()) Text("No clear match in this category. Try a closer photo with the subject centered, or save as Unknown.")
                    val selectable = nature.saveStage == NatureSaveStage.IDLE
                    result.predictions.forEach { prediction ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = selectable) { key = prediction.taxon.key; chosen = true }) {
                            RadioButton(selected = chosen && key == prediction.taxon.key, enabled = selectable,
                                onClick = { key = prediction.taxon.key; chosen = true })
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(prediction.taxon.commonName)
                                Text("${prediction.taxon.scientificName} · score ${natureScore(prediction.score)}", style = MaterialTheme.typography.bodySmall)
                                Text(nyRecordLabel(prediction.taxon), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = selectable) { key = null; chosen = true }) {
                        RadioButton(chosen && key == null, onClick = { key = null; chosen = true }, enabled = selectable)
                        Text("Unknown ${result.kind.singular.lowercase()}", Modifier.padding(top = 12.dp))
                    }
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, enabled = selectable, modifier = Modifier.fillMaxWidth())
                    Row {
                        Checkbox(includeGps, onCheckedChange = { includeGps = it }, enabled = selectable)
                        Text("Include GPS if available", Modifier.padding(top = 12.dp))
                    }
                    Text("Uses photo GPS when available, otherwise the device GPS. You can save without location.", style = MaterialTheme.typography.bodySmall)
                    when (nature.saveStage) {
                        NatureSaveStage.IDLE -> Button(enabled = chosen, onClick = {
                            if (nature.prepareSave(key, notes, includeGps)) {
                                if (!includeGps) nature.saveAfterPermission(finishSave)
                                else permissions.launch(buildList {
                                    add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    if (result.source != null && Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                                }.toTypedArray())
                            }
                        }) { Text("Save chosen identification") }
                        NatureSaveStage.PERMISSION -> Text("Waiting for location permission…")
                        NatureSaveStage.LOCATING -> { Text("Waiting for GPS (up to 20 seconds)…"); TextButton(onClick = nature::skipLocation) { Text("Save without location") } }
                        NatureSaveStage.SAVING -> { CircularProgressIndicator(); Text("Saving locally…") }
                    }
                }
                nature.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.navigate(Screen.NatureObservations) }, enabled = nature.saveStage == NatureSaveStage.IDLE) { Text("Saved plants and mushrooms") }
                TextButton(onClick = { showLicense = true }) { Text("Model credits and coverage") }
            }
        }
    }
    if (showCoverage && result != null) NatureTaxonPicker(nature.catalog, result.kind, null) { showCoverage = false }
    if (showLicense) NatureLicense { showLicense = false }
}

@Composable
fun NatureObservationsScreen(vm: TreeViewModel, nature: NatureViewModel) {
    val records by nature.observations.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BackTopBar("Plants & mushrooms") { vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            (listOf<NatureKind?>(null) + NatureKind.entries).chunked(3).forEach { kinds ->
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { kind -> FilterChip(filter == kind?.key, onClick = { filter = kind?.key }, label = { Text(kind?.title ?: "All") }) }
            }
            }
            TextButton(onClick = { vm.navigate(Screen.SightingMap()) }) { Text("Map of sightings") }
            (nature.observationError ?: nature.error)?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            val visible = records.orEmpty().filter { filter == null || it.kind == filter }
            if (records == null) Text("Loading…", Modifier.padding(16.dp))
            else if (visible.isEmpty()) Text("No saved observations in this category yet. Choose a category on the camera screen.", Modifier.padding(16.dp))
            LazyColumn {
                items(visible, key = { it.id }) { row ->
                    ListItem(headlineContent = { Text(nature.catalog[row.taxonKey]?.commonName ?: "Unknown ${NatureKind.fromKey(row.kind).singular.lowercase()}") },
                        supportingContent = { Text("${NatureKind.fromKey(row.kind).singular} · ${sightingDate(row.observedAt)} · ${if (row.latitude != null) "GPS" else "No location"}") },
                        modifier = Modifier.clickable { vm.navigate(Screen.NatureDetail(row.id)) })
                }
            }
        }
    }
}

@Composable
fun NatureDetailScreen(vm: TreeViewModel, nature: NatureViewModel, id: Long) {
    val records by nature.observations.collectAsStateWithLifecycle()
    val row = records?.find { it.id == id }
    var notes by rememberSaveable(id, row?.notes) { mutableStateOf(row?.notes.orEmpty()) }
    var key by rememberSaveable(id, row?.taxonKey) { mutableStateOf(row?.taxonKey) }
    var pickTaxon by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, row?.photoFileName) {
        value = row?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(nature.photo(it.photoFileName).absolutePath) } }
    }
    BackHandler(nature.editing) { }
    Scaffold(topBar = { BackTopBar("Saved observation") { if (!nature.editing) vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (row == null) Text(if (records == null) "Loading…" else "Observation not found.")
            else {
                val kind = NatureKind.fromKey(row.kind)
                NatureCaution(kind)
                Text(nature.catalog[key]?.commonName ?: "Unknown ${kind.singular.lowercase()}", style = MaterialTheme.typography.headlineSmall)
                nature.catalog[key]?.let { Text(it.scientificName); Text(nyRecordLabel(it), style = MaterialTheme.typography.bodySmall) }
                Text(sightingDate(row.observedAt))
                bitmap?.let { Image(it.asImageBitmap(), "Saved observation photo", Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Fit) }
                    ?: Text("Photo unavailable")
                Text(row.modelVersion, style = MaterialTheme.typography.bodySmall)
                if (key == row.taxonKey) row.score?.let { Text("Original model score ${natureScore(it)} · label chosen by you", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { pickTaxon = true }, enabled = !nature.editing) { Text("Change identification") }
                if (row.latitude != null && row.longitude != null) {
                    Text(String.format(Locale.US, "%.5f, %.5f · %s", row.latitude, row.longitude, row.locationSource ?: "GPS"))
                    TextButton(onClick = { vm.navigate(Screen.SightingMap(focusNatureId = id)) }) { Text("Show on map") }
                } else Text("No location saved")
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), enabled = !nature.editing)
                Button(onClick = { nature.update(id, key, notes) { vm.back() } }, enabled = !nature.editing) { Text("Save changes") }
                TextButton(onClick = { confirmDelete = true }, enabled = !nature.editing) { Text("Delete observation") }
            }
            nature.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (pickTaxon && row != null) NatureTaxonPicker(nature.catalog, NatureKind.fromKey(row.kind), { key = it; pickTaxon = false }) { pickTaxon = false }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete observation?") },
        text = { Text("Permanently delete this record and the app's photo copy. The original photo is kept.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; nature.delete(id) { vm.back(); if (vm.current != Screen.NatureObservations) vm.navigate(Screen.NatureObservations) } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun NatureCaution(kind: NatureKind) {
    if (kind == NatureKind.MUSHROOM) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text("Never eat a mushroom based on this app. Photo suggestions can be wrong; this app cannot determine edibility.", Modifier.padding(12.dp))
    } else Text("Photo suggestions need checking. Do not eat or use a plant based on this app.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NatureTaxonPicker(catalog: NatureCatalog, kind: NatureKind, choose: ((String?) -> Unit)?, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var newYorkOnly by remember { mutableStateOf(true) }
    val matches = remember(query, kind, newYorkOnly) { catalog.supported(kind, newYorkOnly).filter { it.commonName.contains(query, true) || it.scientificName.contains(query, true) }.sortedBy { it.commonName } }
    AlertDialog(onDismissRequest = close, title = { Text("Supported ${kind.title.lowercase()}") }, text = {
        Column {
            Text("${catalog.supported(kind, true).size} taxa with New York records; ${catalog.supported(kind).size} supported in total.", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(newYorkOnly, { newYorkOnly = it }); Text("NY records only", Modifier.padding(top = 12.dp)) }
            Text("iNaturalist research-grade records, ${catalog.snapshotDate}. Includes introduced and occasional plants. These records do not establish native status, local presence, or model accuracy.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, label = { Text("Search names") })
            if (choose != null) TextButton(onClick = { choose(null) }) { Text("Unknown ${kind.singular.lowercase()}") }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(matches, key = { it.key }) { taxon ->
                    if (choose != null) TextButton(onClick = { choose(taxon.key) }) { Text("${taxon.commonName}\n${taxon.scientificName}\n${nyRecordLabel(taxon)}") }
                    else Text("${taxon.commonName}\n${taxon.scientificName}\n${nyRecordLabel(taxon)}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
private fun NatureLicense(close: () -> Unit) {
    val context = LocalContext.current
    val text = remember { listOf("nature-expanded/NOTICE.txt", "nature-expanded/LICENSE-CC-BY-4.0.txt",
        "nature-expanded/LICENSE-DINOv3.md", "nature-expanded/LICENSE-CC-BY-NC-SA-4.0.txt",
        "nature/NOTICE.txt", "nature/LICENSE.txt").joinToString("\n\n") { name -> context.assets.open(name).bufferedReader().use { it.readText() } } }
    AlertDialog(onDismissRequest = close, title = { Text("Model credits") }, text = {
        Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

private fun natureScore(score: Float) = String.format(Locale.US, "%.2f", score)
private fun nyRecordLabel(taxon: NatureTaxon) = taxon.nyObservations?.let { "NY observation records: $it" } ?: "No NY record in bundled snapshot"
