package com.kylecorry.trail_sense.weather.infrastructure.subsystem

import android.annotation.SuppressLint
import android.content.Context
import com.kylecorry.andromeda.core.cache.MemoryCachedValue
import com.kylecorry.andromeda.core.topics.ITopic
import com.kylecorry.andromeda.core.topics.Topic
import com.kylecorry.andromeda.core.topics.generic.distinct
import com.kylecorry.andromeda.csv.CSVConvert
import com.kylecorry.andromeda.preferences.Preferences
import com.kylecorry.sol.math.Vector2
import com.kylecorry.sol.science.meteorology.Weather
import com.kylecorry.sol.units.Reading
import com.kylecorry.sol.units.Temperature
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FeatureState
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.data.DataUtils
import com.kylecorry.trail_sense.shared.extensions.ifDebug
import com.kylecorry.trail_sense.shared.extensions.onIO
import com.kylecorry.trail_sense.shared.io.Files
import com.kylecorry.trail_sense.weather.domain.RawWeatherObservation
import com.kylecorry.trail_sense.weather.domain.WeatherService
import com.kylecorry.trail_sense.weather.domain.sealevel.SeaLevelCalibrationFactory
import com.kylecorry.trail_sense.weather.infrastructure.*
import com.kylecorry.trail_sense.weather.infrastructure.commands.MonitorWeatherCommand
import com.kylecorry.trail_sense.weather.infrastructure.persistence.WeatherRepo
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.*


class WeatherSubsystem private constructor(private val context: Context) : IWeatherSubsystem {

    private val weatherRepo by lazy { WeatherRepo.getInstance(context) }
    private val prefs by lazy { UserPreferences(context) }
    private val sharedPrefs by lazy { Preferences(context) }

    private lateinit var weatherService: WeatherService

    private var cachedValue = MemoryCachedValue<CurrentWeather>()
    private var validLock = Object()
    private var isValid = false

    private val _weatherChanged = Topic()
    override val weatherChanged: ITopic = _weatherChanged

    private val _weatherMonitorState =
        com.kylecorry.andromeda.core.topics.generic.Topic(
            defaultValue = Optional.of(
                calculateWeatherMonitorState()
            )
        )
    override val weatherMonitorState: com.kylecorry.andromeda.core.topics.generic.ITopic<FeatureState>
        get() = _weatherMonitorState.distinct()

    private val _weatherMonitorFrequency =
        com.kylecorry.andromeda.core.topics.generic.Topic(
            defaultValue = Optional.of(
                calculateWeatherMonitorFrequency()
            )
        )
    override val weatherMonitorFrequency: com.kylecorry.andromeda.core.topics.generic.ITopic<Duration>
        get() = _weatherMonitorFrequency.distinct()

    private val invalidationPrefKeys = listOf(
        R.string.pref_use_sea_level_pressure,
        R.string.pref_barometer_altitude_outlier,
        R.string.pref_barometer_pressure_smoothing,
        R.string.pref_barometer_altitude_smoothing,
        R.string.pref_adjust_for_temperature,
        R.string.pref_forecast_sensitivity,
        R.string.pref_storm_alert_sensitivity,
        R.string.pref_altimeter_calibration_mode,
        R.string.pref_pressure_history
    ).map { context.getString(it) }

    private val weatherMonitorStatePrefKeys = listOf(
        R.string.pref_monitor_weather,
        R.string.pref_low_power_mode_weather,
        R.string.pref_low_power_mode
    ).map { context.getString(it) }

    private val weatherMonitorFrequencyPrefKeys = listOf(
        R.string.pref_weather_update_frequency
    ).map { context.getString(it) }

    init {
        // Keep them up to date
        weatherMonitorFrequency.subscribe { true }
        weatherMonitorState.subscribe { true }

        sharedPrefs.onChange.subscribe { key ->
            if (key in invalidationPrefKeys) {
                invalidate()
            }

            if (key in weatherMonitorStatePrefKeys) {
                val state = calculateWeatherMonitorState()
                _weatherMonitorState.publish(state)
            }

            if (key in weatherMonitorFrequencyPrefKeys) {
                val frequency = calculateWeatherMonitorFrequency()
                _weatherMonitorFrequency.publish(frequency)
            }

            true
        }
        weatherRepo.readingsChanged.subscribe {
            invalidate()
            true
        }

        resetWeatherService()
    }

    override suspend fun getWeather(): CurrentWeather = onIO {
        if (!isValid) {
            refresh()
        }
        cachedValue.getOrPut { populateCache() }
    }

    override suspend fun getHistory(): List<WeatherObservation> = onIO {
        if (!isValid) {
            refresh()
        }
        var readings = weatherRepo.getAll()
            .asSequence()
            .sortedBy { it.time }
            .filter { it.time <= Instant.now() }
            .toList()

        val withTemps = calibrateTemperatures(readings)

        val calibrator = SeaLevelCalibrationFactory().create(prefs)
        val pressures = calibrator.calibrate(withTemps)

        val combined = pressures.map {
            val reading = withTemps.firstOrNull { r -> r.time == it.time }
            WeatherObservation(
                it.time,
                it.value,
                Temperature.celsius(
                    reading?.value?.temperature ?: 0f
                ),
                reading?.value?.humidity
            )
        }

        ifDebug {
            try {
                val header = listOf(
                    listOf(
                        "time",
                        "raw_pressure",
                        "raw_altitude",
                        "raw_sea_level",
                        "raw_temperature",
                        "raw_humidity",
                        "smooth_pressure",
                        "smooth_temperature",
                        "smooth_humidity"
                    )
                )
                val data = header + pressures.map {
                    val original = readings.firstOrNull { r -> r.time == it.time }
                    val reading = withTemps.firstOrNull { r -> r.time == it.time }
                    listOf(
                        it.time.toEpochMilli(),
                        original?.value?.pressure,
                        original?.value?.altitude,
                        original?.value?.seaLevel(useTemperature = prefs.weather.seaLevelFactorInTemp)?.pressure,
                        original?.value?.temperature,
                        original?.value?.humidity,
                        it.value.pressure,
                        reading?.value?.temperature,
                        reading?.value?.humidity
                    )
                }

                Files.debugFile(
                    context,
                    "weather.csv",
                    CSVConvert.toCSV(data)
                )
            } catch (e: Exception) {
            }
        }

        combined
    }

    override fun enableMonitor() {
        prefs.weather.shouldMonitorWeather = true
        WeatherUpdateScheduler.start(context)
    }

    override fun disableMonitor() {
        prefs.weather.shouldMonitorWeather = false
        WeatherUpdateScheduler.stop(context)
    }

    override suspend fun updateWeather(background: Boolean) {
        MonitorWeatherCommand(context, background).execute()
    }

    private fun invalidate() {
        synchronized(validLock) {
            isValid = false
        }
        _weatherChanged.publish()
    }

    private suspend fun refresh() {
        cachedValue.reset()
        delay(50)
        resetWeatherService()
        synchronized(validLock) {
            isValid = true
        }
    }

    private fun getHourlyForecast(readings: List<WeatherObservation>): Weather {
        return weatherService.getHourlyWeather(readings.map { it.pressureReading() })
    }

    private fun getDailyForecast(readings: List<WeatherObservation>): Weather {
        return weatherService.getDailyWeather(readings.map { it.pressureReading() })
    }

    private suspend fun populateCache(): CurrentWeather {
        val history = getHistory()
        val daily = getDailyForecast(history)
        val hourly = getHourlyForecast(history)
        val last = history.lastOrNull()
        val tendency = weatherService.getTendency(history.map { it.pressureReading() })
        return CurrentWeather(
            WeatherPrediction(hourly, daily),
            tendency,
            last
        )
    }

    private fun resetWeatherService() {
        weatherService = WeatherService(prefs.weather)
    }

    private fun calculateWeatherMonitorFrequency(): Duration {
        return prefs.weather.weatherUpdateFrequency
    }

    private fun calculateWeatherMonitorState(): FeatureState {
        return if (WeatherMonitorIsEnabled().isSatisfiedBy(context)) {
            FeatureState.On
        } else if (WeatherMonitorIsAvailable().not().isSatisfiedBy(context)) {
            FeatureState.Unavailable
        } else {
            FeatureState.Off
        }
    }

    private fun calibrateTemperatures(readings: List<Reading<RawWeatherObservation>>): List<Reading<RawWeatherObservation>> {
        val start = readings.firstOrNull()?.time ?: return emptyList()
        return DataUtils.smooth(
            readings,
            0.1f,
            { _, value ->
                Vector2(
                    Duration.between(start, value.time).toMillis() / 1000f,
                    weatherService.calibrateTemperature(value.value.temperature)
                )
            }
        ) { reading, smoothedValue -> reading.copy(value = reading.value.copy(temperature = smoothedValue.y)) }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: WeatherSubsystem? = null

        @Synchronized
        fun getInstance(context: Context): WeatherSubsystem {
            if (instance == null) {
                instance = WeatherSubsystem(context.applicationContext)
            }
            return instance!!
        }

    }

}