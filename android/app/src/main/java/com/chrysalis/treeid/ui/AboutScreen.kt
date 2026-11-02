package com.chrysalis.treeid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chrysalis.treeid.BuildConfig
import com.chrysalis.treeid.TreeViewModel
import kotlin.math.roundToInt

@Composable
fun AboutScreen(vm: TreeViewModel) {
    val info = vm.modelInfo
    Scaffold(topBar = { BackTopBar("About") { vm.back() } }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("NY Tree ID ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Identifies ${vm.catalog.trees.size} trees of ${vm.catalog.region} from a photo. " +
                    "Everything runs on your phone: the app has no internet permission, so photos " +
                    "never leave the device.",
            )

            Spacer(Modifier.height(8.dp))
            Text("Model", style = MaterialTheme.typography.titleMedium)
            if (info == null) {
                Text(vm.modelProblem ?: "Loading…")
            } else {
                Text("Version ${info.version} · ${info.numClasses} classes")
                info.testTop1?.let { top1 ->
                    val top3 = info.testTop3?.let { " · right answer in the top 3: ${(it * 100).roundToInt()}%" } ?: ""
                    Text("Test accuracy: ${(top1 * 100).roundToInt()}% first guess$top3")
                }
                info.trainPhotos?.let { Text("Trained on ${"%,d".format(it)} photos") }
            }

            Spacer(Modifier.height(8.dp))
            Text("Use with care", style = MaterialTheme.typography.titleMedium)
            Text(
                "Identifications are suggestions, not certainties. Confirm with the key features " +
                    "in the guide or a regional field guide, and never eat or use any plant " +
                    "based on this app.",
            )

            Spacer(Modifier.height(8.dp))
            Text("Credits", style = MaterialTheme.typography.titleMedium)
            Text(
                "The model was trained on research-grade observations shared by the iNaturalist " +
                    "community under Creative Commons licences (CC0, CC BY, and CC BY-NC), from the " +
                    "iNaturalist Open Dataset. Thank you to everyone who contributes observations.",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
