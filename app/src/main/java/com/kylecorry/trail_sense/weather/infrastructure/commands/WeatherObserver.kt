package com.kylecorry.trail_sense.weather.infrastructure.commands

import android.content.Context
import com.kylecorry.andromeda.core.tryOrLog
import com.kylecorry.andromeda.location.IGPS
import com.kylecorry.sol.units.Reading
import com.kylecorry.trail_sense.shared.extensions.onDefault
import com.kylecorry.trail_sense.shared.sensors.SensorService
import com.kylecorry.trail_sense.shared.sensors.altimeter.GaussianAltimeter
import com.kylecorry.trail_sense.weather.domain.RawWeatherObservation
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

internal class WeatherObserver(
    private val context: Context,
    private val background: Boolean = true,
    private val timeout: Duration = Duration.ofSeconds(10)
) : IWeatherObserver {

    private val sensorService by lazy { SensorService(context) }
    private val altimeter by lazy { sensorService.getAltimeter(background, preferGPS = true) }
    private val altimeterAsGPS by lazy { sensorService.getGPSFromAltimeter(altimeter) }
    private val gps: IGPS by lazy {
        altimeterAsGPS ?: sensorService.getGPS(background)
    }
    private val barometer by lazy { sensorService.getBarometer() }
    private val thermometer by lazy { sensorService.getThermometer() }
    private val hygrometer by lazy { sensorService.getHygrometer() }

    override suspend fun getWeatherObservation(): Reading<RawWeatherObservation>? = onDefault {
        try {
            withTimeoutOrNull(timeout.toMillis()) {
                val jobs = mutableListOf<Job>()
                jobs.add(launch { altimeter.read() })

                // If the base altimeter is the GPS, its readings are already being updated by the line above
                if (altimeterAsGPS != gps) {
                    jobs.add(launch { gps.read() })
                }
                jobs.add(launch { barometer.read() })
                jobs.add(launch { thermometer.read() })
                jobs.add(launch { hygrometer.read() })

                jobs.joinAll()
            }
        } finally {
            forceStopSensors()
        }
        if (barometer.pressure == 0f) {
            return@onDefault null
        }

        Reading(
            RawWeatherObservation(
                0,
                barometer.pressure,
                altimeter.altitude,
                if (thermometer.temperature.isNaN()) 16f else thermometer.temperature,
                if (altimeter is GaussianAltimeter) (altimeter as GaussianAltimeter).altitudeAccuracy else 0f,
                hygrometer.humidity,
                gps.location
            ),
            Instant.now()
        )
    }

    private fun forceStopSensors() {
        // This shouldn't be needed, but for some reason the GPS got stuck on at one point (may want to revisit this)
        val sensors = listOf(altimeter, gps, barometer, thermometer, hygrometer)
        sensors.forEach {
            tryOrLog {
                it.stop(null)
            }
        }
    }

}