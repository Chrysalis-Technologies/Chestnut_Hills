package com.chrysalis.treeid

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrysalis.treeid.data.SpeciesCatalog
import com.chrysalis.treeid.ml.TreeClassifier
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Camera : Screen
    data object Identify : Screen
    data object Guide : Screen
    data class SpeciesDetail(val key: String) : Screen
    data object About : Screen
}

/** A photo and, once the model has run, what it thinks the photo shows. */
data class Identification(
    val photo: Bitmap,
    val predictions: List<TreeClassifier.Prediction>? = null,
    val error: String? = null,
)

class TreeViewModel(app: Application) : AndroidViewModel(app) {

    val catalog: SpeciesCatalog = SpeciesCatalog.load(app)

    private var loadedClassifier: TreeClassifier? = null
    private val classifier: Deferred<Result<TreeClassifier?>> =
        viewModelScope.async(Dispatchers.Default) {
            runCatching { TreeClassifier.load(app) }.onSuccess { loadedClassifier = it }
        }

    var modelInfo by mutableStateOf<TreeClassifier.ModelInfo?>(null)
        private set
    var modelLabels by mutableStateOf<Set<String>>(emptySet())
        private set
    var modelProblem by mutableStateOf<String?>(null)
        private set

    val backStack = mutableStateListOf<Screen>(Screen.Camera)
    val current: Screen get() = backStack.last()

    var identification by mutableStateOf<Identification?>(null)
        private set

    init {
        viewModelScope.launch {
            classifier.await()
                .onSuccess { c ->
                    if (c == null) {
                        modelProblem = "This build doesn't include a tree model yet. " +
                            "The field guide still works."
                    } else {
                        modelInfo = c.info
                        modelLabels = c.labels.toSet()
                    }
                }
                .onFailure { modelProblem = "The tree model couldn't be loaded: ${it.message}" }
        }
    }

    fun navigate(screen: Screen) {
        if (current != screen) backStack.add(screen)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun backToCamera() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun identify(photo: Bitmap) {
        val pending = Identification(photo)
        identification = pending
        backToCamera()
        navigate(Screen.Identify)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val c = classifier.await().getOrThrow()
                        ?: error("This build doesn't include a tree model yet.")
                    c.classify(photo)
                }
            }
            if (identification === pending) {
                identification = result.fold(
                    onSuccess = { pending.copy(predictions = it) },
                    onFailure = { pending.copy(error = it.message ?: "Identification failed.") },
                )
            }
        }
    }

    override fun onCleared() {
        loadedClassifier?.close()
    }
}
