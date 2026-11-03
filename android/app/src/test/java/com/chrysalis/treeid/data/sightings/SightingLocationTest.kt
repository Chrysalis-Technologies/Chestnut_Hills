package com.chrysalis.treeid.data.sightings

import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SightingLocationTest {
    private val now = 240_000_000_000L
    private fun fix(ageNanos: Long = 0, provider: String = LocationManager.GPS_PROVIDER) = Location(provider).apply {
        latitude = 44.25
        longitude = -75.5
        accuracy = 7f
        elapsedRealtimeNanos = now - ageNanos
    }

    @Test fun acceptsGpsFixStrictlyYoungerThanTwoMinutes() {
        val location = recentGpsFix(fix(119_999_999_999L), now)!!
        assertEquals(44.25, location.latitude, 0.0)
        assertEquals(-75.5, location.longitude, 0.0)
        assertEquals(7f, location.accuracyMeters)
        assertEquals("gps", location.source)
        assertNull(recentGpsFix(fix(120_000_000_000L), now))
        assertNull(recentGpsFix(fix(-1), now))
    }

    @Test fun neverAcceptsNetworkProviderOrInvalidCoordinates() {
        assertNull(recentGpsFix(fix(provider = LocationManager.NETWORK_PROVIDER), now))
        assertNull(recentGpsFix(fix().apply { latitude = 100.0 }, now))
        assertNull(recentGpsFix(fix().apply { longitude = Double.NaN }, now))
    }

    @Test fun wallClockChangesDoNotAffectGpsFreshness() {
        assertNotNull(recentGpsFix(fix().apply { time = 0 }, now))
    }

    @Test fun deniedLocationPermissionReturnsNoLocationImmediately() = runBlocking {
        assertNull(GpsLocationReader(RuntimeEnvironment.getApplication()).current())
    }
}
