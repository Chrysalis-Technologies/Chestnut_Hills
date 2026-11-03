package com.chrysalis.treeid.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chrysalis.treeid.data.GuidePhoto
import com.chrysalis.treeid.data.SeasonalGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun IllustratedGuideContent(guide: SeasonalGuide) {
    val initialSeason = when (LocalDate.now().monthValue) {
        in 3..5 -> "Spring"; in 6..8 -> "Summer"; in 9..11 -> "Autumn"; else -> "Winter"
    }
    var season by rememberSaveable { mutableStateOf(initialSeason) }
    var creditPhoto by remember { mutableStateOf<GuidePhoto?>(null) }
    Text("Through the seasons", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(guide.seasons.keys.toList()) { name ->
            FilterChip(selected = season == name, onClick = { season = name }, label = { Text(name) })
        }
    }
    Text(guide.seasons.getValue(season))
    Text("New York timing varies with weather and elevation. Young trees, cultivars and male/female trees may differ.",
        style = MaterialTheme.typography.bodySmall)
    guide.photos.groupBy { it.part }.forEach { (part, photos) ->
        Text(part, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(photos, key = { it.asset }) { photo ->
                Card(Modifier.width(300.dp)) {
                    GuideImage(photo, Modifier.fillMaxWidth().height(225.dp))
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(photo.caption, style = MaterialTheme.typography.bodyMedium)
                        Text(photo.taxon, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { creditPhoto = photo }) {
                            Text("${photo.credit} · ${photo.license}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    Text("Identification sources", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    guide.sources.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    creditPhoto?.let { photo ->
        AlertDialog(onDismissRequest = { creditPhoto = null }, title = { Text("Image credit") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(photo.sourceTitle); Text(photo.credit); Text(photo.license); Text(photo.licenseUrl); Text(photo.sourceUrl)
                Text("Resized for the offline guide; no crop or botanical alterations. Each image retains its stated license.")
            }
        }, confirmButton = { TextButton(onClick = { creditPhoto = null }) { Text("Close") } })
    }
}

@Composable
private fun GuideImage(photo: GuidePhoto, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(photo.asset).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), "${photo.taxon}: ${photo.caption}", modifier, contentScale = ContentScale.Fit) }
        ?: Box(modifier) { Text("Image unavailable") }
}
