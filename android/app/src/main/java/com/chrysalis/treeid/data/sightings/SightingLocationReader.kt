package com.chrysalis.treeid.data.sightings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

object PhotoLocation {
    /** Read the original selected file separately; image decoding/preprocessing is unchanged. */
    suspend fun read(context: Context, uri: Uri): SightingLocation? = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_MEDIA_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Documents providers and MediaStore use different URI forms. Not every provider
            // supports an original MediaStore URI, so also try the explicitly selected document.
            runCatching {
                val mediaUri = MediaStore.getMediaUri(context, uri) ?: uri
                candidates += MediaStore.setRequireOriginal(mediaUri)
            }
        }
        candidates += uri
        candidates.distinct().firstNotNullOfOrNull { candidate ->
            runCatching {
                context.contentResolver.openInputStream(candidate)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exif.latLong?.let { coordinates ->
                        val accuracy = exif.getAttributeDouble(
                            ExifInterface.TAG_GPS_H_POSITIONING_ERROR, Double.NaN,
                        ).toFloat().takeIf { it.isFinite() && it >= 0 }
                        SightingLocation(coordinates[0], coordinates[1], accuracy, "photo")
                    }
                }
            }.getOrNull()
        }
    }
}

class GpsLocationReader(private val context: Context) {
    @SuppressLint("MissingPermission") // Checked immediately before the calls; revocation is caught.
    suspend fun current(): SightingLocation? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return@withContext null // Coarse-only permission must not fall back to network location.
        val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        try {
            if (LocationManager.GPS_PROVIDER !in manager.allProviders) return@withContext null
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location ->
                recentGpsFix(location, SystemClock.elapsedRealtimeNanos())?.let { return@withContext it }
            }
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return@withContext null
            withTimeoutOrNull(20_000L) {
                val result = CompletableDeferred<Location?>()
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    val executor = Executor { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) }
                    try {
                        manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, executor) {
                            result.complete(it)
                        }
                        result.await()?.let { recentGpsFix(it, SystemClock.elapsedRealtimeNanos()) }
                    } finally {
                        signal.cancel()
                    }
                } else {
                    legacyFix(manager, result)
                }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null // GPS hardware/provider may disappear while the request is starting.
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun legacyFix(
        manager: LocationManager,
        result: CompletableDeferred<Location?>,
    ): SightingLocation? {
        val thread = HandlerThread("sighting-gps").apply { start() }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { result.complete(location) }
            override fun onProviderDisabled(provider: String) { result.complete(null) }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, thread.looper)
            return result.await()?.let { recentGpsFix(it, SystemClock.elapsedRealtimeNanos()) }
        } finally {
            try {
                manager.removeUpdates(listener)
            } finally {
                thread.quitSafely()
            }
        }
    }
}

/** Use the monotonic clock so changing the phone's date cannot make an old fix look fresh. */
internal fun recentGpsFix(location: Location, nowNanos: Long): SightingLocation? {
    if (location.provider != LocationManager.GPS_PROVIDER) return null
    val ageNanos = nowNanos - location.elapsedRealtimeNanos
    if (ageNanos !in 0 until 120_000_000_000L) return null
    return runCatching {
        SightingLocation(
            location.latitude, location.longitude,
            location.accuracy.takeIf { location.hasAccuracy() }, "gps",
        )
    }.getOrNull()
}
