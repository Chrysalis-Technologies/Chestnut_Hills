package com.chrysalis.treeid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chrysalis.treeid.Identification
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.SightingSaveState
import com.chrysalis.treeid.TreeViewModel
import com.chrysalis.treeid.data.Species
import com.chrysalis.treeid.ml.TreeClassifier.Prediction
import kotlin.math.roundToInt

/**
 * Confidence cut-offs for the wording of a result, calibrated on 7,278 held-out test photos
 * (training/reports/eval_report.md): at >= 60% the top answer was right 92% of the time; between
 * 35% and 60% it was right about 60% of the time but the answer was in the top 3 nearly 90% of
 * the time; below that, a closer photo is the best advice.
 */
private const val LIKELY = 0.60f
private const val POSSIBLE = 0.35f

/** Smallest probability worth listing as another possibility. */
private const val MIN_LISTED = 0.03f

@Composable
fun ResultScreen(vm: TreeViewModel) {
    val ident = vm.identification
    Scaffold(topBar = { BackTopBar("Identification") { vm.back() } }) { padding ->
        if (ident == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No photo yet")
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PhotoPreview(ident) }
            when {
                ident.error != null -> item { MessageCard("Something went wrong", ident.error) }
                ident.predictions == null -> item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(16.dp))
                        Text("Identifying…")
                    }
                }
                else -> results(vm, ident.predictions)
            }
            if (ident.predictions != null || ident.error != null) {
                item { SaveSightingCard(vm, ident) }
            }
            item {
                Button(
                    onClick = { vm.backToCamera() },
                    enabled = vm.sightingSaveState in listOf(SightingSaveState.IDLE, SightingSaveState.SAVED),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Take another photo")
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(vm: TreeViewModel, predictions: List<Prediction>) {
    val catalog = vm.catalog
    val top = predictions.first()
    val topIsOther = catalog[top.key]?.isOther == true
    val trees = predictions.filter { p -> catalog[p.key]?.let { !it.isOther } ?: false }
    val best = trees.firstOrNull()

    if (topIsOther && top.confidence >= POSSIBLE) {
        item {
            MessageCard(
                "Not one of the trees in this guide",
                "This looks more like another plant than any of the ${catalog.trees.size} trees the " +
                    "app knows. If it is a tree, try a closer photo of a single leaf or the bark.",
            )
        }
        val maybes = trees.filter { it.confidence >= 0.10f }
        if (maybes.isNotEmpty()) {
            item { SectionTitle("If it is a tree, it might be") }
            items(maybes, key = { it.key }) { p -> CandidateRow(vm, p) }
        }
        return
    }
    if (best == null) {
        item { MessageCard("No match", "Try another photo.") }
        return
    }

    val species = catalog[best.key]
    val verdict = when {
        best.confidence >= LIKELY -> "Most likely"
        best.confidence >= POSSIBLE -> "Possibly — compare with the other matches below"
        else -> "Not sure — best guess"
    }
    item {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth().clickable { vm.selectSightingTree(best.key) },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(verdict, style = MaterialTheme.typography.labelLarge)
                Text(
                    species?.commonName ?: best.key,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (species != null && species.scientificName.isNotEmpty()) {
                    Text(species.scientificName, fontStyle = FontStyle.Italic)
                }
                Spacer(Modifier.height(8.dp))
                ConfidenceBar(best.confidence)
                if (species != null && species.features.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Check for", style = MaterialTheme.typography.titleSmall)
                    species.features.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                val lookalikes = species?.lookalikes?.mapNotNull { catalog[it] }.orEmpty()
                if (lookalikes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Easily confused with " + lookalikes.joinToString { it.commonName.lowercase() },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (vm.selectedTreeKey == best.key) "Selected for sighting" else "Tap to select for sighting",
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { vm.navigate(Screen.SpeciesDetail(best.key)) }) {
                    Text("Full guide entry")
                }
            }
        }
    }

    if (best.confidence < POSSIBLE) {
        item {
            MessageCard(
                "Tip for a better match",
                "Fill the square with a single leaf (or needle cluster) in good light, ideally " +
                    "against a plain background. Bark, flowers, and fruit help too.",
            )
        }
    }

    val others = trees.drop(1).filter { it.confidence >= MIN_LISTED }
    if (others.isNotEmpty()) {
        item { SectionTitle("Other possibilities") }
        items(others, key = { it.key }) { p -> CandidateRow(vm, p) }
    }
}

@Composable
private fun PhotoPreview(ident: Identification) {
    val image = remember(ident.photo) { ident.photo.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = "Your photo",
        contentScale = ContentScale.Crop, // shows the centre square, which is what the model saw
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun CandidateRow(vm: TreeViewModel, p: Prediction) {
    val species: Species? = vm.catalog[p.key]
    Card(Modifier.fillMaxWidth().clickable { vm.selectSightingTree(p.key) }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(species?.commonName ?: p.key, style = MaterialTheme.typography.titleMedium)
            if (species != null && species.scientificName.isNotEmpty()) {
                Text(species.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            ConfidenceBar(p.confidence)
            Text(
                if (vm.selectedTreeKey == p.key) "Selected for sighting" else "Tap to select for sighting",
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = { vm.navigate(Screen.SpeciesDetail(p.key)) }) { Text("Full guide entry") }
        }
    }
}

@Composable
private fun SaveSightingCard(vm: TreeViewModel, ident: Identification) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.saveSightingAfterPermission() }
    val state = vm.sightingSaveState
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Sighting: " + (vm.selectedTreeKey?.let { vm.catalog[it]?.commonName } ?: "Unknown tree"),
            style = MaterialTheme.typography.titleMedium,
        )
        FilterChip(
            selected = vm.selectedTreeKey == null,
            onClick = { vm.selectSightingTree(null) },
            enabled = state == SightingSaveState.IDLE,
            label = { Text("Unknown tree") },
        )
        when (state) {
            SightingSaveState.IDLE -> {
                Text("Location is optional. You can deny access and still save this photo.")
                Button(onClick = {
                    if (vm.prepareSightingSave()) {
                        val permissions = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                        if (Build.VERSION.SDK_INT >= 29 && ident.sourceUri != null) {
                            permissions += Manifest.permission.ACCESS_MEDIA_LOCATION
                        }
                        if (permissions.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }) vm.saveSightingAfterPermission()
                        else permissionLauncher.launch(permissions.toTypedArray())
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save sighting") }
            }
            SightingSaveState.PERMISSION -> Text("Waiting for permission…")
            SightingSaveState.LOCATING -> {
                Text("Getting location…")
                TextButton(onClick = vm::saveWithoutLocation) { Text("Save without location") }
            }
            SightingSaveState.SAVING -> Text("Saving sighting…")
            SightingSaveState.SAVED -> Button(onClick = {
                vm.savedSightingId?.let { vm.navigate(Screen.SightingDetail(it)) }
            }) { Text("View saved sighting") }
        }
        vm.sightingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ConfidenceBar(confidence: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { confidence.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text("${(confidence * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun MessageCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
