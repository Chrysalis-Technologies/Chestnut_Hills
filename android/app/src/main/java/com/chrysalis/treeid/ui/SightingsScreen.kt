package com.chrysalis.treeid.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel
import com.chrysalis.treeid.data.sightings.Sighting
import com.chrysalis.treeid.data.sightings.SightingExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun SightingsScreen(vm: TreeViewModel) {
    val sightings by vm.sightings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BackTopBar("Sightings") { vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Button(
                    onClick = { exportMenu = true },
                    enabled = !sightings.isNullOrEmpty() && !exporting,
                ) { Text(if (exporting) "Preparing export…" else "Export") }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    SightingExportFormat.entries.forEach { format ->
                        DropdownMenuItem(
                            text = { Text(if (format == SightingExportFormat.CSV) "CSV" else "GeoJSON") },
                            onClick = {
                                exportMenu = false
                                exporting = true
                                scope.launch {
                                    try {
                                        val file = vm.exportSightings(format)
                                        val uri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.sighting-exports", file,
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = format.mimeType
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newRawUri("Sightings", uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Export sightings"))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        exportError = "Couldn't share sightings: ${e.message}"
                                    } finally {
                                        exporting = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
            vm.sightingsError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            when {
                sightings == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                sightings.isNullOrEmpty() -> Text(
                    "No sightings yet. Identify a tree, then choose Save sighting.", Modifier.padding(16.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(sightings.orEmpty(), key = { it.id }) { sighting ->
                        ListItem(
                            headlineContent = {
                                Text(sighting.treeKey?.let { vm.catalog[it]?.commonName } ?: "Unknown tree")
                            },
                            supportingContent = {
                                Text("${sightingDate(sighting.observedAt)}\n${sightingPlace(sighting)}")
                            },
                            leadingContent = {
                                SightingPhoto(vm.sightingPhoto(sighting.photoFileName), Modifier.size(64.dp), 160)
                            },
                            modifier = Modifier.clickable { vm.navigate(Screen.SightingDetail(sighting.id)) },
                        )
                    }
                }
            }
        }
    }
    exportError?.let { error ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text("Export failed") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK") } },
        )
    }
}

@Composable
internal fun SightingPhoto(file: File, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f), maxSide: Int = 1024) {
    val bitmap by produceState<Bitmap?>(null, file.path, maxSide) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= maxSide) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
        }
    }
    val photo = bitmap
    if (photo == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Photo unavailable", style = MaterialTheme.typography.labelSmall) }
    } else {
        Image(
            bitmap = remember(photo) { photo.asImageBitmap() }, contentDescription = "Sighting photo",
            contentScale = ContentScale.Fit, modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    }
}

internal fun sightingDate(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

internal fun sightingPlace(sighting: Sighting): String {
    val lat = sighting.latitude ?: return "No location saved"
    val lon = sighting.longitude ?: return "No location saved"
    val coordinates = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    val accuracy = sighting.accuracyMeters?.let { String.format(Locale.getDefault(), " · ±%.0f m", it) }.orEmpty()
    val source = if (sighting.locationSource == "photo") "Photo GPS" else "Device GPS"
    return "$coordinates$accuracy · $source"
}
