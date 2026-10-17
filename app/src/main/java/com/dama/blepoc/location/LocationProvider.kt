package com.dama.blepoc.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocationProvider(context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(timeoutSeconds: Long = 10): Location {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter(locationManager::isProviderEnabled)

        if (providers.isEmpty()) {
            throw IllegalStateException("No enabled location providers")
        }

        val result = AtomicReference<Location?>()
        val errors = AtomicReference<String>()
        val latch = CountDownLatch(1)
        val signal = CancellationSignal()

        for (provider in providers) {
            locationManager.getCurrentLocation(
                provider,
                signal,
                executor
            ) { location ->
                if (location == null) {
                    errors.set("Provider $provider returned null location")
                } else {
                    result.set(location)
                }
                latch.countDown()
            }
            if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                break
            }
        }

        signal.cancel()
        return result.get() ?: throw IllegalStateException(
            errors.get() ?: "Location request timed out after ${timeoutSeconds}s"
        )
    }

    fun close() {
        executor.shutdownNow()
    }
}
