package com.kylecorry.trail_sense.navigation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.navigation.domain.Beacon
import com.kylecorry.trail_sense.navigation.infrastructure.BeaconDB
import com.kylecorry.trail_sense.shared.Coordinate
import com.kylecorry.trail_sense.shared.doTransaction
import com.kylecorry.trail_sense.shared.sensors.gps.GPS


class PlaceBeaconFragment(private val beaconDB: BeaconDB, private val gps: GPS) : Fragment() {

    private lateinit var beaconName: EditText
    private lateinit var beaconLat: EditText
    private lateinit var beaconLng: EditText
    private lateinit var useCurrentLocationBtn: Button
    private lateinit var doneBtn: FloatingActionButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val view = inflater.inflate(R.layout.fragment_create_beacon, container, false)

        beaconName = view.findViewById(R.id.beacon_name)
        beaconLat = view.findViewById(R.id.beacon_latitude)
        beaconLng = view.findViewById(R.id.beacon_longitude)
        useCurrentLocationBtn = view.findViewById(R.id.current_location_btn)
        doneBtn = view.findViewById(R.id.place_beacon_btn)

        doneBtn.setOnClickListener {
            val name = beaconName.text.toString()
            val lat = beaconLat.text.toString()
            val lng = beaconLng.text.toString()

            val coordinate = getCoordinate(lat, lng)

            if (name.isNotBlank() && coordinate != null) {
                val beacon = Beacon(name, coordinate)
                beaconDB.create(beacon)
                fragmentManager?.doTransaction {
                    this.replace(
                        R.id.fragment_holder,
                        BeaconListFragment(
                            beaconDB,
                            gps
                        )
                    )
                }
            }
        }


        useCurrentLocationBtn.setOnClickListener {
            gps.updateLocation {
                gps.updateLocation { location ->
                    if (location != null) {
                        beaconLat.setText(location.latitude.toString())
                        beaconLng.setText(location.longitude.toString())
                    }
                }
            }
        }

        return view
    }

    private fun getCoordinate(lat: String, lon: String): Coordinate? {
        val decimalLat = lat.toDoubleOrNull()
        val decimalLon = lon.toDoubleOrNull()
        if (decimalLat != null && decimalLon != null) {
            return Coordinate(decimalLat, decimalLon)
        }

        val dms = Coordinate.degreeMinutesSeconds(lat, lon)
        if (dms != null) {
            return dms
        }

        val ddm = Coordinate.degreeDecimalMinutes(lat, lon)
        if (ddm != null) {
            return dms
        }

        return null
    }

}