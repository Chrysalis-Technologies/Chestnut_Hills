package com.chrysalis.treeid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel
import kotlin.math.roundToInt

@Composable
fun SightingDetailScreen(vm: TreeViewModel, id: Long) {
    val sightings by vm.sightings.collectAsStateWithLifecycle()
    val sighting = sightings?.firstOrNull { it.id == id }
    Scaffold(topBar = { BackTopBar("Sighting") { vm.back() } }) { padding ->
        if (sighting == null) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                if (sightings == null) CircularProgressIndicator() else Text("This sighting is no longer available.")
                vm.sightingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            return@Scaffold
        }
        var notes by rememberSaveable(id) { mutableStateOf(sighting.notes) }
        var treeKey by rememberSaveable(id) { mutableStateOf(sighting.treeKey) }
        var pickingTree by rememberSaveable(id) { mutableStateOf(false) }
        var confirmingDelete by rememberSaveable(id) { mutableStateOf(false) }
        val dirty = notes != sighting.notes || treeKey != sighting.treeKey
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SightingPhoto(vm.sightingPhoto(sighting.photoFileName)) }
            item {
                val tree = treeKey?.let { vm.catalog[it] }
                if (tree == null) Text("Unknown tree", style = MaterialTheme.typography.headlineSmall)
                else TextButton(onClick = { vm.navigate(Screen.SpeciesDetail(tree.key)) }) {
                    Text("${tree.commonName} · Field guide", style = MaterialTheme.typography.titleLarge)
                }
                if (treeKey == sighting.treeKey) sighting.confidence?.let {
                    Text("Model confidence when saved: ${(it * 100).roundToInt()}%")
                }
                Text(sightingDate(sighting.observedAt))
                Text(sightingPlace(sighting))
                if (sighting.latitude != null && sighting.longitude != null) {
                    TextButton(onClick = { vm.navigate(Screen.SightingMap(sighting.id)) }) { Text("Show on map") }
                }
            }
            item {
                TextButton(onClick = { pickingTree = true }, enabled = !vm.editingSighting) { Text("Change tree") }
            }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
                    minLines = 3, enabled = !vm.editingSighting, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = { vm.updateSighting(id, treeKey, notes) }, enabled = dirty && !vm.editingSighting) {
                    Text(if (vm.editingSighting) "Saving…" else "Save changes")
                }
                if (!dirty && !vm.editingSighting) Text("Changes saved", style = MaterialTheme.typography.labelSmall)
                vm.sightingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                TextButton(onClick = { confirmingDelete = true }, enabled = !vm.editingSighting) {
                    Text("Delete sighting", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (pickingTree) TreePicker(vm, onDismiss = { pickingTree = false }) {
            treeKey = it
            pickingTree = false
        }
        if (confirmingDelete) AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete sighting?") },
            text = { Text("This deletes the saved sighting and its private photo copy.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; vm.deleteSighting(id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TreePicker(vm: TreeViewModel, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val trees = remember(query) { vm.catalog.trees.filter { it.matches(query) } }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Change tree") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Search the guide") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { TextButton(onClick = { onSelect(null) }) { Text("Unknown tree") } }
                    items(trees, key = { it.key }) { tree ->
                        ListItem(
                            headlineContent = { Text(tree.commonName) },
                            supportingContent = { Text(tree.scientificName) },
                            modifier = Modifier.clickable { onSelect(tree.key) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
