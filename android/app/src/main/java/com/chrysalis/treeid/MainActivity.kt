package com.chrysalis.treeid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrysalis.treeid.ui.AboutScreen
import com.chrysalis.treeid.ui.CameraScreen
import com.chrysalis.treeid.ui.GuideScreen
import com.chrysalis.treeid.ui.ResultScreen
import com.chrysalis.treeid.ui.SpeciesScreen
import com.chrysalis.treeid.ui.SightingDetailScreen
import com.chrysalis.treeid.ui.SightingsScreen
import com.chrysalis.treeid.ui.SightingsMapScreen
import com.chrysalis.treeid.ui.TreeIdTheme
import com.chrysalis.treeid.birds.BirdViewModel
import com.chrysalis.treeid.ui.BirdRecordScreen
import com.chrysalis.treeid.ui.BirdObservationsScreen
import com.chrysalis.treeid.ui.BirdDetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TreeIdTheme {
                TreeIdApp(viewModel())
            }
        }
    }
}

@Composable
fun TreeIdApp(vm: TreeViewModel) {
    val birds: BirdViewModel = viewModel()
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
    val stateHolder = rememberSaveableStateHolder()
    val screen = vm.current
    stateHolder.SaveableStateProvider(screen.toString()) {
        when (screen) {
            Screen.Camera -> CameraScreen(vm)
            Screen.Identify -> ResultScreen(vm)
            Screen.Guide -> GuideScreen(vm)
            is Screen.SpeciesDetail -> SpeciesScreen(vm, screen.key)
            Screen.About -> AboutScreen(vm)
            Screen.Sightings -> SightingsScreen(vm)
            is Screen.SightingMap -> SightingsMapScreen(vm, birds, screen.focusId, screen.focusBirdId)
            is Screen.SightingDetail -> SightingDetailScreen(vm, screen.id)
            Screen.BirdRecord -> BirdRecordScreen(vm, birds)
            Screen.BirdObservations -> BirdObservationsScreen(vm, birds)
            is Screen.BirdDetail -> BirdDetailScreen(vm, birds, screen.id)
        }
    }
}
