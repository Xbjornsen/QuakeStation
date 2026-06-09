package com.quakesphere.globe.internal

import android.content.Context
import com.google.gson.JsonParser
import com.quakesphere.globe.R
import com.quakesphere.globe.Volcano

/**
 * Loads the bundled Holocene-volcano list (hand-curated subset of the
 * Smithsonian GVP list — see the JSON's _source field for provenance) and
 * returns pairs of (public [Volcano] data, world-space XYZ position) so the
 * renderer can both draw the triangle and surface taps back to consumers.
 */
internal object VolcanoesLoader {

    data class Entry(val volcano: Volcano, val pos: FloatArray)

    /** Sphere radius for volcano markers — sit just over the surface. */
    private const val VOLCANO_RADIUS = 1.012f

    fun load(context: Context): List<Entry> {
        val text = context.resources.openRawResource(R.raw.volcanoes_holocene)
            .bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(text).asJsonObject
        val arr  = root.getAsJsonArray("volcanoes")
        val out  = ArrayList<Entry>(arr.size())
        for (el in arr) {
            val o = el.asJsonObject
            val lat = o.get("lat").asFloat
            val lon = o.get("lon").asFloat
            val volcano = Volcano(
                name    = o.get("name").asString,
                country = o.get("country").asString,
                type    = o.get("type")?.asString ?: "",
                elevM   = o.get("elevM")?.asInt ?: 0,
                lat     = lat,
                lon     = lon
                // `lastEruption` field in the JSON is ignored — live activity
                // feed is now the source of truth (see VolcanicActivityRepository).
            )
            out.add(Entry(volcano, latLonToXYZ(lat, lon, VOLCANO_RADIUS)))
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
        // Match the sphere mesh convention used elsewhere (see appendXYZ in
        // TectonicPlatesLoader): X is negated so the projection lines up with
        // the bundled GeoJSON layers.
        return floatArrayOf(-r * cosLat * cosLon, r * sinLat, r * cosLat * sinLon)
    }
}
