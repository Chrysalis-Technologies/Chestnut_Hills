package com.chrysalis.treeid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel

@Composable
fun GuideScreen(vm: TreeViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val trees = vm.catalog.trees
    val filtered = remember(query) { trees.filter { it.matches(query) } }
    val sections = listOf("conifer" to "Conifers", "broadleaf" to "Broadleaf trees")

    Scaffold(topBar = { BackTopBar("Field guide") { vm.back() } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search ${trees.size} trees") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                for ((group, title) in sections) {
                    val list = filtered.filter { it.group == group }
                    if (list.isEmpty()) continue
                    item(key = "header-$group") {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.key }) { sp ->
                        ListItem(
                            headlineContent = { Text(sp.commonName) },
                            supportingContent = { Text(sp.scientificName, fontStyle = FontStyle.Italic) },
                            trailingContent = { Text(sp.family, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickable { vm.navigate(Screen.SpeciesDetail(sp.key)) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeciesScreen(vm: TreeViewModel, key: String) {
    val species = vm.catalog[key]
    Scaffold(topBar = { BackTopBar(species?.commonName ?: "Unknown") { vm.back() } }) { padding ->
        if (species == null) {
            Text("Not in the guide", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (species.scientificName.isNotEmpty()) {
                Text(species.scientificName, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic)
            }
            val facts = listOfNotNull(
                species.family.takeIf { it.isNotEmpty() }?.let { "Family: $it" },
                species.status.takeIf { it.isNotEmpty() }?.let { "In New York: $it" },
            )
            facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!species.isOther && vm.modelLabels.isNotEmpty() && key !in vm.modelLabels) {
                MessageCard(
                    "Guide only",
                    "The camera model in this version wasn't trained on this tree, so it can't suggest it.",
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Key features", style = MaterialTheme.typography.titleMedium)
            species.features.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }

            vm.illustratedGuide.entries[key]?.let { IllustratedGuideContent(it) }

            val lookalikes = species.lookalikes.mapNotNull { vm.catalog[it] }
            if (lookalikes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Easily confused with", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lookalikes.forEach { other ->
                        AssistChip(
                            onClick = { vm.navigate(Screen.SpeciesDetail(other.key)) },
                            label = { Text(other.commonName) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
