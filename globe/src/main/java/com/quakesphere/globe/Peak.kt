package com.quakesphere.globe

/**
 * Public data type for a mountain-peak marker on the globe.
 *
 * Hand-curated set of ~60 globally significant peaks: the Seven Summits,
 * all 14 eight-thousanders, plus iconic peaks across each continent.
 * See res/raw/peaks.json for the source notes and full list.
 *
 * Position on the globe is derived from [lat]/[lon] inside the renderer;
 * consumers don't need to know about the sphere mesh.
 */
data class Peak(
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
