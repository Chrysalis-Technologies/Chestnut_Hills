package com.chrysalis.treeid.ui

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel
import com.chrysalis.treeid.data.maps.*
import com.chrysalis.treeid.birds.BirdViewModel
import com.chrysalis.treeid.nature.NatureViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SightingsMapScreen(vm: TreeViewModel, birds: BirdViewModel, nature: NatureViewModel, focusId: Long?, focusBirdId: Long?, focusNatureId: Long?) {
    val sightings by vm.sightings.collectAsStateWithLifecycle()
    val birdObservations by birds.observations.collectAsStateWithLifecycle()
    val natureObservations by nature.observations.collectAsStateWithLifecycle()
    val markers = remember(sightings, birdObservations, natureObservations) {
        (sightings.orEmpty().map { ObservationMarker.tree(it, vm.catalog) } +
            birdObservations.orEmpty().map { ObservationMarker.bird(it, birds.catalog) } +
            natureObservations.orEmpty().map { ObservationMarker.nature(it, nature.catalog) }).sortedByDescending { it.time }
    }
    val located = remember(markers) { markers.filter { it.located } }
    val points = remember(located) { located.map { MapGeometry.project(it.latitude!!, it.longitude!!) } }
    var centerX by rememberSaveable { mutableStateOf(MapGeometry.NEW_YORK.x) }
    var centerY by rememberSaveable { mutableStateOf(MapGeometry.NEW_YORK.y) }
    var zoom by rememberSaveable { mutableStateOf(6.0) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tappedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmRemove by remember { mutableStateOf(false) }
    var tileError by remember { mutableStateOf<String?>(null) }
    val viewport = MapViewport(centerX, centerY, zoom)
    fun update(next: MapViewport) { centerX = next.x; centerY = next.y; zoom = next.zoom }
    fun fit() { update(MapGeometry.fit(points, canvasSize.width.toFloat(), canvasSize.height.toFloat())) }
    LaunchedEffect(sightings, birdObservations, natureObservations, canvasSize) {
        if (!initialized && sightings != null && birdObservations != null && natureObservations != null && canvasSize.width > 0) {
            val focusKey = when { focusNatureId != null -> "nature:$focusNatureId"; focusBirdId != null -> "bird:$focusBirdId"; else -> "tree:$focusId" }
            val focus = located.indexOfFirst { it.key == focusKey }
            if (focus >= 0) update(MapViewport(points[focus].x, points[focus].y, 16.0)) else fit()
            initialized = true
        }
    }
    val context = LocalContext.current
    val outline by produceState<List<List<MapPoint>>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val root = JSONObject(context.assets.open("maps/new_york.geojson").bufferedReader().use { it.readText() })
            val polygons = root.getJSONObject("geometry").getJSONArray("coordinates")
            buildList {
                for (p in 0 until polygons.length()) {
                    val rings = polygons.getJSONArray(p)
                    for (r in 0 until rings.length()) {
                        val ring = rings.getJSONArray(r)
                        add((0 until ring.length()).map { n -> ring.getJSONArray(n).let { MapGeometry.project(it.getDouble(1), it.getDouble(0)) } })
                    }
                }
            }
        }
    }
    val tileZoom = floor(zoom).toInt().coerceIn(0, 22)
    val tiles by produceState<List<MapTile>>(emptyList(), viewport, canvasSize, vm.mapRevision) {
        value = emptyList()
        tileError = null
        if (vm.mapInfo != null && canvasSize.width > 0) {
            try {
                val count = 2.0.pow(tileZoom)
                val halfWidth = canvasSize.width / (2 * viewport.scale)
                val halfHeight = canvasSize.height / (2 * viewport.scale)
                value = withContext(Dispatchers.IO) { vm.offlineMaps.tiles(tileZoom,
                    floor((centerX - halfWidth) * count).toInt(), floor((centerY - halfHeight) * count).toInt(),
                    floor((centerX + halfWidth) * count).toInt(), floor((centerY + halfHeight) * count).toInt()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { tileError = "Map tiles unavailable: ${e.message}" }
        }
    }
    val importMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importMap) }
    val currentViewport by rememberUpdatedState(viewport)
    val currentLocated by rememberUpdatedState(located)
    Scaffold(topBar = { BackTopBar("Sightings map") { vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { fit() }) { Text("Fit sightings") }
                TextButton(onClick = { update(MapViewport(MapGeometry.NEW_YORK.x, MapGeometry.NEW_YORK.y, 6.0)) }) { Text("New York") }
                TextButton(onClick = { importMap.launch(arrayOf("*/*")) }, enabled = !vm.importingMap) {
                    Text(if (vm.importingMap) "Importing…" else "Import map")
                }
                if (vm.mapInfo != null) TextButton(onClick = { confirmRemove = true }, enabled = !vm.importingMap) { Text("Remove map") }
                TextButton(onClick = { update(viewport.move(0.0, 0.0, 0.5)) }) { Text("−") }
                TextButton(onClick = { update(viewport.move(0.0, 0.0, 2.0)) }) { Text("+") }
            }
            Text(if (sightings == null || birdObservations == null || natureObservations == null) "Loading sightings…" else "${located.size} mapped · ${markers.size - located.size} without a location",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            Text("Green: trees · Blue: birds · Purple: plants\nTeal: ferns · Olive: vines · Brown: mushrooms · Gray: unknown", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            Text(vm.mapInfo?.name ?: "New York overview · no street detail", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium)
            (vm.mapError ?: tileError ?: vm.sightingsError ?: birds.error ?: nature.observationError)?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (vm.mapInfo != null && tiles.isEmpty() && tileError == null) {
                Text("No imported tiles in this view or zoom. Pan or change zoom; sighting pins remain visible.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f).onSizeChanged { canvasSize = it }
                .semantics { contentDescription = "Offline map with ${located.size} numbered sighting pins. The list below opens each identification." }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, factor, _ ->
                        update(currentViewport.move(pan.x.toDouble(), pan.y.toDouble(), factor.toDouble(),
                            centroid.x - size.width / 2.0, centroid.y - size.height / 2.0))
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { tap ->
                        tappedIds = currentLocated.filter { sighting ->
                            val p = currentViewport.screen(MapGeometry.project(sighting.latitude!!, sighting.longitude!!), size.width.toFloat(), size.height.toFloat())
                            hypot(p.x - tap.x, p.y - tap.y) <= 32.dp.toPx()
                        }.map { it.key }
                    }
                }) {
                drawRect(Color(0xffe4edf0))
                for (ring in outline) {
                    val path = Path()
                    ring.forEachIndexed { index, point ->
                        val p = viewport.screen(point, size.width, size.height)
                        if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
                    }
                    path.close()
                    drawPath(path, Color(0xffd5e3ce)); drawPath(path, Color(0xff6d8264), style = Stroke(1.5.dp.toPx()))
                }
                tiles.forEach { tile ->
                    val n = 2.0.pow(tile.zoom)
                    // Tile x is unwrapped, so do not wrap a second time at the date line.
                    val x = size.width / 2 + (tile.x / n - viewport.x) * viewport.scale
                    val y = size.height / 2 + (tile.y / n - viewport.y) * viewport.scale
                    val side = ceil(viewport.scale / n).toInt() + 1
                    drawImage(tile.bitmap.asImageBitmap(), dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(side, side))
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE; textSize = 12.dp.toPx(); textAlign = Paint.Align.CENTER
                }
                located.forEachIndexed { index, sighting ->
                    val point = viewport.screen(points[index], size.width, size.height)
                    if (point.x in -24.0..(size.width + 24.0) && point.y in -24.0..(size.height + 24.0)) {
                        val p = Offset(point.x.toFloat(), point.y.toFloat())
                        drawCircle(Color.White, 16.dp.toPx(), p)
                        drawCircle(if (sighting.unknown) Color(0xff686868) else Color(sighting.kind.color), 14.dp.toPx(), p)
                        drawContext.canvas.nativeCanvas.drawText("${index + 1}", p.x, p.y - (paint.ascent() + paint.descent()) / 2, paint)
                    }
                }
            }
            Text(listOfNotNull("Overview: Natural Earth · public domain", vm.mapInfo?.let {
                it.attribution.ifBlank { "Imported map: no attribution supplied in file" }
            }).joinToString("\n"), Modifier.fillMaxWidth().heightIn(max = 70.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            if (located.isEmpty()) Text("Save a tree, bird, flower or mushroom observation with GPS to add a pin. Unlocated records remain in their lists.", Modifier.padding(16.dp))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                itemsIndexed(located, key = { _, s -> s.key }) { index, sighting ->
                    ListItem(headlineContent = { Text("${index + 1}. ${sighting.name}") },
                        supportingContent = { Text("${sighting.kind.label} · ${sightingDate(sighting.time)}") },
                        trailingContent = { TextButton(onClick = { update(MapViewport(points[index].x, points[index].y, 16.0)) }) { Text("Locate") } },
                        modifier = Modifier.clickable { vm.navigate(sighting.destination) })
                }
            }
        }
    }
    val tapped = located.filter { it.key in tappedIds }
    if (tapped.isNotEmpty()) AlertDialog(onDismissRequest = { tappedIds = emptyList() },
        title = { Text(if (tapped.size == 1) "Sighting" else "Sightings at this location") }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(tapped, key = { _, s -> s.key }) { _, sighting ->
                    TextButton(onClick = { tappedIds = emptyList(); vm.navigate(sighting.destination) }) {
                        Text("${sighting.name}\n${sighting.kind.label} · ${sightingDate(sighting.time)}\n${sighting.place}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { tappedIds = emptyList() }) { Text("Close") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove imported map?") },
        text = { Text("Remove the app's copy and return to the New York overview. Your original file and sightings are kept.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeImportedMap() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}
