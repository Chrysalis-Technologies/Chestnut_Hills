package com.chrysalis.treeid.nature

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrysalis.treeid.data.sightings.GpsLocationReader
import com.chrysalis.treeid.data.sightings.PhotoLocation
import com.chrysalis.treeid.data.sightings.PrivateSightingPhotos
import com.chrysalis.treeid.data.sightings.SightingLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import java.io.File

data class NatureIdentification(val photo: Bitmap, val kind: NatureKind, val time: Long, val source: Uri?,
    val predictions: List<NaturePrediction> = emptyList(), val analyzing: Boolean = true, val error: String? = null,
    val modelVersion: String = NatureClassifier.VERSION)
enum class NatureSaveStage { IDLE, PERMISSION, LOCATING, SAVING }

class NatureViewModel(app: Application) : AndroidViewModel(app) {
    val catalog = NatureCatalog.load(app)
    private val directory = File(app.noBackupFilesDir, "nature-photos")
    private val repository = NatureRepository(NatureDatabase.get(app).nature(), PrivateSightingPhotos(directory), catalog, directory)
    var observationError by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    val observations = repository.observeAll().catch { observationError = "Couldn't load flower/mushroom observations: ${it.message}"; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var identification by mutableStateOf<NatureIdentification?>(null); private set
    var saveStage by mutableStateOf(NatureSaveStage.IDLE); private set
    var editing by mutableStateOf(false); private set
    private var identifyJob: Job? = null
    private var saveJob: Job? = null
    private var locationRequest: Deferred<SightingLocation?>? = null
    private data class Pending(val photo: NatureIdentification, val key: String?, val notes: String, val locate: Boolean)
    private var pending: Pending? = null

    init {
        viewModelScope.launch {
            try { repository.cleanOrphans() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't clean unused observation photos: ${e.message}" }
        }
    }

    fun identify(photo: Bitmap, kind: NatureKind, source: Uri? = null) {
        identifyJob?.cancel(); cancelSave(); error = null
        val request = NatureIdentification(photo, kind, System.currentTimeMillis(), source)
        identification = request
        identifyJob = viewModelScope.launch {
            try {
                val predictions = withContext(Dispatchers.Default) {
                    ExpandedNatureClassifier.identify(getApplication(), catalog, photo, kind)
                }
                if (identification === request) identification = request.copy(predictions = predictions.predictions,
                    modelVersion = predictions.modelVersion, analyzing = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (identification === request) identification = request.copy(analyzing = false, error = "Couldn't identify this photo: ${e.message}")
            }
        }
    }

    fun prepareSave(key: String?, notes: String, locate: Boolean): Boolean {
        val photo = identification ?: return false
        if (saveStage != NatureSaveStage.IDLE || photo.analyzing || (key != null && photo.predictions.none { it.taxon.key == key })) return false
        pending = Pending(photo, key, notes, locate)
        error = null; saveStage = NatureSaveStage.PERMISSION
        return true
    }

    fun saveAfterPermission(done: (Long) -> Unit) {
        val request = pending ?: return
        if (saveStage != NatureSaveStage.PERMISSION) return
        saveJob = viewModelScope.launch {
            try {
                val location = if (request.locate) {
                    saveStage = NatureSaveStage.LOCATING
                    coroutineScope {
                        val child = async { request.photo.source?.let { PhotoLocation.read(getApplication(), it) }
                            ?: GpsLocationReader(getApplication()).current() }
                        locationRequest = child
                        try { child.await() }
                        catch (e: CancellationException) { coroutineContext.ensureActive(); null }
                        finally { locationRequest = null }
                    }
                } else null
                saveStage = NatureSaveStage.SAVING
                val id = repository.save(request.photo.photo, request.photo.kind, request.key,
                    request.photo.predictions.find { it.taxon.key == request.key }?.score, request.photo.time, location, request.notes,
                    request.photo.modelVersion)
                identification = null
                done(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't save observation: ${e.message}" }
            finally { pending = null; saveStage = NatureSaveStage.IDLE }
        }
    }
    fun skipLocation() { locationRequest?.cancel() }
    fun cancelSave() {
        if (saveStage == NatureSaveStage.SAVING) return
        saveJob?.cancel(); pending = null; saveStage = NatureSaveStage.IDLE
    }
    fun cancelIdentification() { identifyJob?.cancel(); cancelSave() }
    fun photo(name: String) = repository.photos.file(name)
    fun update(id: Long, key: String?, notes: String, done: () -> Unit) = edit({ repository.update(id, key, notes) }, done)
    fun delete(id: Long, done: () -> Unit) = edit({ repository.delete(id) }, done)
    private fun edit(action: suspend () -> Unit, done: () -> Unit) {
        if (editing) return
        editing = true; error = null
        viewModelScope.launch {
            try { action(); done() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't update observation: ${e.message}" }
            finally { editing = false }
        }
    }
}
