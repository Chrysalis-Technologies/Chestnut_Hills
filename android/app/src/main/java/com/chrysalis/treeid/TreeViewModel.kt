package com.chrysalis.treeid

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrysalis.treeid.data.SpeciesCatalog
import com.chrysalis.treeid.data.IllustratedGuide
import com.chrysalis.treeid.data.maps.OfflineMapStore
import com.chrysalis.treeid.data.maps.MapInfo
import com.chrysalis.treeid.data.sightings.GpsLocationReader
import com.chrysalis.treeid.data.sightings.PhotoLocation
import com.chrysalis.treeid.data.sightings.PrivateSightingPhotos
import com.chrysalis.treeid.data.sightings.Sighting
import com.chrysalis.treeid.data.sightings.SightingExportFormat
import com.chrysalis.treeid.data.sightings.SightingExports
import com.chrysalis.treeid.data.sightings.SightingLocation
import com.chrysalis.treeid.data.sightings.SightingsDatabase
import com.chrysalis.treeid.data.sightings.SightingsRepository
import com.chrysalis.treeid.ml.TreeClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface Screen {
    data object Camera : Screen
    data object Identify : Screen
    data object Guide : Screen
    data class SpeciesDetail(val key: String) : Screen
    data object About : Screen
    data object Sightings : Screen
    data class SightingMap(val focusId: Long? = null) : Screen
    data class SightingDetail(val id: Long) : Screen
}

/** A photo and, once the model has run, what it thinks the photo shows. */
data class Identification(
    val photo: Bitmap,
    val predictions: List<TreeClassifier.Prediction>? = null,
    val error: String? = null,
    val sourceUri: Uri? = null,
)

enum class SightingSaveState { IDLE, PERMISSION, LOCATING, SAVING, SAVED }

class TreeViewModel(app: Application) : AndroidViewModel(app) {

    val catalog: SpeciesCatalog = SpeciesCatalog.load(app)
    val illustratedGuide = IllustratedGuide.load(app)
    val offlineMaps = OfflineMapStore(app)
    var mapInfo by mutableStateOf<MapInfo?>(null)
        private set
    var mapError by mutableStateOf<String?>(null)
        private set
    var importingMap by mutableStateOf(false)
        private set
    var mapRevision by mutableStateOf(0)
        private set
    private val sightingRepository = SightingsRepository(
        SightingsDatabase.get(app).sightings(),
        PrivateSightingPhotos(File(app.filesDir, "sightings")),
        catalog.trees.map { it.key }.toSet(),
    )
    var sightingsError by mutableStateOf<String?>(null)
        private set
    val sightings: StateFlow<List<Sighting>?> = sightingRepository.observeAll()
        .catch { sightingsError = "Couldn't load sightings: ${it.message}"; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var selectedTreeKey by mutableStateOf<String?>(null)
        private set
    var sightingSaveState by mutableStateOf(SightingSaveState.IDLE)
        private set
    var savedSightingId by mutableStateOf<Long?>(null)
        private set
    var editingSighting by mutableStateOf(false)
        private set
    private var saveJob: Job? = null
    private var locationRequest: Deferred<SightingLocation?>? = null
    private var pendingSighting: PendingSighting? = null

    private data class PendingSighting(val identification: Identification, val treeKey: String?, val time: Long)

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
            try { mapInfo = withContext(Dispatchers.IO) { offlineMaps.openSaved() }; mapRevision++ }
            catch (e: Exception) { mapError = "Couldn't open saved map: ${e.message}" }
        }
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
        if (current == Screen.Identify && sightingSaveState in listOf(
                SightingSaveState.PERMISSION, SightingSaveState.LOCATING, SightingSaveState.SAVING,
            )) return
        if (current != screen) backStack.add(screen)
    }

    fun back(): Boolean {
        if (editingSighting || sightingSaveState == SightingSaveState.SAVING) return true
        if (current == Screen.Identify) cancelSightingSave()
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun backToCamera() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun identify(photo: Bitmap, sourceUri: Uri? = null) {
        cancelSightingSave()
        sightingSaveState = SightingSaveState.IDLE
        savedSightingId = null
        selectedTreeKey = null
        sightingsError = null
        val pending = Identification(photo, sourceUri = sourceUri)
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
                selectedTreeKey = result.getOrNull()?.firstOrNull { catalog[it.key]?.isOther == false }?.key
                identification = result.fold(
                    onSuccess = { pending.copy(predictions = it) },
                    onFailure = { pending.copy(error = it.message ?: "Identification failed.") },
                )
            }
        }
    }

    fun selectSightingTree(key: String?) {
        if (sightingSaveState == SightingSaveState.IDLE) selectedTreeKey = key
    }

    /** Capture the selected candidate and timestamp before the permission dialog or GPS wait. */
    fun prepareSightingSave(): Boolean {
        val photo = identification ?: return false
        if (sightingSaveState != SightingSaveState.IDLE) return false
        pendingSighting = PendingSighting(photo, selectedTreeKey, System.currentTimeMillis())
        sightingsError = null
        sightingSaveState = SightingSaveState.PERMISSION
        return true
    }

    fun saveSightingAfterPermission() {
        val pending = pendingSighting ?: return
        if (sightingSaveState != SightingSaveState.PERMISSION) return
        saveJob = viewModelScope.launch {
            try {
                sightingSaveState = SightingSaveState.LOCATING
                val location = coroutineScope {
                    val request = async {
                        pending.identification.sourceUri?.let { PhotoLocation.read(getApplication(), it) }
                            ?: GpsLocationReader(getApplication()).current()
                    }
                    locationRequest = request
                    try {
                        request.await()
                    } catch (e: CancellationException) {
                        coroutineContext.ensureActive() // Only the explicit skip cancels the child alone.
                        null
                    } finally {
                        locationRequest = null
                    }
                }
                sightingSaveState = SightingSaveState.SAVING
                val id = sightingRepository.save(
                    pending.identification.photo, pending.treeKey,
                    pending.identification.predictions?.firstOrNull { it.key == pending.treeKey }?.confidence,
                    pending.time, location,
                )
                savedSightingId = id
                sightingSaveState = SightingSaveState.SAVED
                navigate(Screen.SightingDetail(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sightingsError = "Couldn't save sighting: ${e.message}"
                sightingSaveState = SightingSaveState.IDLE
            } finally {
                pendingSighting = null
            }
        }
    }

    fun saveWithoutLocation() { locationRequest?.cancel() }

    private fun cancelSightingSave() {
        saveJob?.cancel()
        pendingSighting = null
        if (sightingSaveState != SightingSaveState.SAVED) sightingSaveState = SightingSaveState.IDLE
    }

    fun sightingPhoto(name: String): File = sightingRepository.photos.file(name)

    fun updateSighting(id: Long, treeKey: String?, notes: String) {
        editSighting { sightingRepository.update(id, treeKey, notes) }
    }

    fun deleteSighting(id: Long) {
        editSighting {
            sightingRepository.delete(id)
            if (savedSightingId == id) {
                savedSightingId = null
                sightingSaveState = SightingSaveState.IDLE
            }
            backToCamera()
            navigate(Screen.Sightings)
        }
    }

    private fun editSighting(action: suspend () -> Unit) {
        if (editingSighting) return
        editingSighting = true
        sightingsError = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sightingsError = "Couldn't update sighting: ${e.message}"
            } finally {
                editingSighting = false
            }
        }
    }

    suspend fun exportSightings(format: SightingExportFormat): File = withContext(Dispatchers.IO) {
        val snapshot = sightingRepository.all()
        val text = when (format) {
            SightingExportFormat.CSV -> SightingExports.csv(snapshot, catalog)
            SightingExportFormat.GEOJSON -> SightingExports.geoJson(snapshot, catalog)
        }
        val directory = File(getApplication<Application>().cacheDir, "sighting-exports")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create export directory" }
        File(directory, "sightings-${UUID.randomUUID()}.${format.extension}").apply { writeText(text, Charsets.UTF_8) }
    }

    override fun onCleared() {
        loadedClassifier?.close()
        offlineMaps.close()
    }

    fun importMap(uri: Uri) {
        if (importingMap) return
        importingMap = true; mapError = null
        viewModelScope.launch {
            try { mapInfo = offlineMaps.import(uri); mapRevision++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mapError = "Couldn't import map: ${e.message}. The previous map is unchanged." }
            finally { importingMap = false }
        }
    }

    fun removeImportedMap() {
        if (importingMap) return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { offlineMaps.useOverview() }; mapInfo = null; mapRevision++; mapError = null }
            catch (e: Exception) { mapError = "Couldn't remove map: ${e.message}" }
        }
    }
}
