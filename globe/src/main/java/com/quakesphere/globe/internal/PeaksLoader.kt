package com.quakesphere.globe.internal

import android.content.Context
import com.google.gson.JsonParser
import com.quakesphere.globe.Peak
import com.quakesphere.globe.R

/**
 * Loads the bundled major-peaks list (curated subset — see the JSON's
 * `_source` field) and returns pairs of (public [Peak] data,
 * world-space XYZ position) so the renderer can draw the marker and
 * also surface taps back to consumers.
 */
internal object PeaksLoader {

    data class Entry(val peak: Peak, val pos: FloatArray)

    /** Sphere radius for peak markers — sit just over the surface, slightly
     *  inside the volcano-marker radius (1.012) so volcanoes win the z-fight
     *  when both share a location (e.g. Mt Fuji which is both). */
    private const val PEAK_RADIUS = 1.010f

    fun load(context: Context): List<Entry> {
        val text = context.resources.openRawResource(R.raw.peaks)
            .bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(text).asJsonObject
        val arr  = root.getAsJsonArray("peaks")
        val out  = ArrayList<Entry>(arr.size())
        for (el in arr) {
            val o = el.asJsonObject
            val lat = o.get("lat").asFloat
            val lon = o.get("lon").asFloat
            val peak = Peak(
                name       = o.get("name").asString,
                range      = o.get("range")?.takeIf { !it.isJsonNull }?.asString ?: "",
                country    = o.get("country").asString,
                elevM      = o.get("elevM").asInt,
                lat        = lat,
                lon        = lon,
                prominence = o.get("prominence")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
            out.add(Entry(peak, latLonToXYZ(lat, lon, PEAK_RADIUS)))
        }
        return out
    }

    private fun latLonToXYZ(lat: Float, lon: Float, r: Float): FloatArray {
        val latR = Math.toRadians(lat.toDouble()).toFloat()
        val lonR = Math.toRadians(lon.toDouble()).toFloat()
        val cosLat = kotlin.math.cos(latR)
        val sinLat = kotlin.math.sin(latR)
        val cosLon = kotlin.math.cos(lonR)
        val sinLon = kotlin.math.sin(lonR)
        return floatArrayOf(-r * cosLat * cosLon, r * sinLat, r * cosLat * sinLon)
    }
}
