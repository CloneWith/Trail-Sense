package com.kylecorry.trail_sense.weather.ui.fields

import android.content.Context
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.core.system.Resources
import com.kylecorry.ceres.list.ListItem
import com.kylecorry.ceres.list.ResourceListIcon
import com.kylecorry.sol.units.Temperature
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.colors.AppColor

class LowTemperatureWeatherField(private val temperature: Temperature?) : WeatherField {
    override fun getListItem(context: Context): ListItem? {
        temperature ?: return null
        val formatter = FormatService(context)
        val units = UserPreferences(context).temperatureUnits
        val value = formatter.formatTemperature(
            temperature.convertTo(units)
        )

        val color = when {
            temperature.temperature <= 5f -> {
                AppColor.Blue.color
            }
            temperature.temperature >= 32.5f -> {
                AppColor.Red.color
            }
            else -> {
                Resources.androidTextColorSecondary(context)
            }
        }

        return ListItem(
            9,
            context.getString(R.string.low),
            subtitle = context.getString(R.string.historic),
            icon = ResourceListIcon(R.drawable.ic_thermometer_low, color),
            trailingText = value
        ) {
            Alerts.dialog(
                context,
                context.getString(R.string.historical_temperature_disclaimer),
                cancelText = null
            )
        }
    }
}