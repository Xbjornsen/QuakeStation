package com.quakesphere.globe

/**
 * Bundled list of major peaks — internal-only as of the v0.1.9 rework.
 * The user-facing "peak markers" layer was removed (read too icon-like),
 * but the data file is still loaded by [com.quakesphere.globe.internal.PeaksLoader]
 * and consumed by [com.quakesphere.globe.internal.ElevationGenerator] to
 * drive the Topographic Relief displacement.
 */
internal data class Peak(
    val name: String,
    /** Mountain range (e.g. "Himalayas", "Andes"). May be blank. */
    val range: String,
    val country: String,
    /** Summit elevation in metres above sea level. */
    val elevM: Int,
    val lat: Float,
    val lon: Float,
    /**
     * Optional one-line tagline (e.g. "Seven Summits · 8000er",
     * "highest in contiguous USA"). May be blank.
     */
    val prominence: String = ""
)
