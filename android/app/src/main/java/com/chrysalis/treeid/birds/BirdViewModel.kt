package com.chrysalis.treeid.birds

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrysalis.treeid.data.sightings.GpsLocationReader
import com.chrysalis.treeid.data.sightings.SightingLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import java.io.File

enum class BirdStage { IDLE, RECORDING, ANALYZING, SAVING }
data class BirdClip(val samples: ShortArray, val predictions: List<BirdPrediction>, val time: Long,
    val location: SightingLocation?, val requestedGps: Boolean, val preview: File)

class BirdViewModel(app: Application) : AndroidViewModel(app) {
    val catalog = BirdCatalog.load(app)
    private val repository = BirdRepository(BirdDatabase.get(app).birds(),
        BirdRecordings(File(app.noBackupFilesDir, "bird-recordings")), catalog)
    var error by mutableStateOf<String?>(null); private set
    var stage by mutableStateOf(BirdStage.IDLE); private set
    var elapsed by mutableIntStateOf(0); private set
    var clip by mutableStateOf<BirdClip?>(null); private set
    var editing by mutableStateOf(false); private set
    val observations = repository.observeAll().catch { error = "Couldn't load bird observations: ${it.message}"; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private var capture: Job? = null

    init {
        viewModelScope.launch {
            try { repository.cleanOrphans() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't clean unused recordings: ${e.message}" }
        }
        // Unsaved previews from a killed process are disposable and never backed up.
        File(app.cacheDir, "bird-previews").listFiles()?.forEach { it.delete() }
    }

    fun microphoneDenied() { error = "Microphone permission is required to record. You can allow it in Android app settings." }

    fun start(includeGps: Boolean) {
        if (stage != BirdStage.IDLE) return
        discard()
        stage = BirdStage.RECORDING; elapsed = 0; error = null
        capture = viewModelScope.launch {
            var preview: File? = null
            try {
                val time = System.currentTimeMillis()
                coroutineScope {
                    val gps = async { if (includeGps) GpsLocationReader(getApplication()).current() else null }
                    val samples = BirdRecorder().record { seconds -> withContext(Dispatchers.Main) { elapsed = seconds } }
                    stage = BirdStage.ANALYZING
                    val predictions = withContext(Dispatchers.Default) {
                        BirdClassifier.load(getApplication(), catalog).use { it.classify(samples) }
                    }
                    val location = withTimeoutOrNull(2_000) { gps.await() }
                    gps.cancel()
                    withContext(Dispatchers.IO) {
                        val directory = File(getApplication<Application>().cacheDir, "bird-previews")
                        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
                        val file = File.createTempFile("call-", ".wav", directory)
                        preview = file
                        BirdAudio.writeWav(file, samples)
                    }
                    ensureActive()
                    clip = BirdClip(samples, predictions, time, location, includeGps, preview!!)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't identify the recording: ${e.message}" }
            finally {
                if (clip?.preview != preview) preview?.delete()
                stage = BirdStage.IDLE
            }
        }
    }

    fun cancelCapture() { capture?.cancel() }
    fun discard() {
        if (stage != BirdStage.IDLE) return
        clip?.preview?.delete(); clip = null; error = null
    }

    fun save(key: String?, notes: String, done: (Long) -> Unit) {
        val pending = clip ?: return
        if (stage != BirdStage.IDLE) return
        // A key is selected by the user, never silently taken from the highest score.
        if (key != null && pending.predictions.none { it.label.key == key }) return
        stage = BirdStage.SAVING; error = null
        viewModelScope.launch {
            try {
                val id = repository.save(pending.samples, key, pending.predictions.find { it.label.key == key }?.score,
                    pending.time, pending.location, notes)
                pending.preview.delete(); clip = null
                done(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't save recording: ${e.message}" }
            finally { stage = BirdStage.IDLE }
        }
    }

    fun audio(name: String): File = repository.recordings.file(name)
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
    override fun onCleared() { capture?.cancel(); clip?.preview?.delete() }
}
