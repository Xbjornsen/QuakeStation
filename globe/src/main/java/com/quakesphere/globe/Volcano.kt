package com.quakesphere.globe

/**
 * Public data type for a volcano marker on the globe.
 *
 * Hand-curated from the Smithsonian Global Volcanism Program's Holocene
 * Volcano List (https://volcano.si.edu). "Active" here means erupted within
 * the last ~12,000 years (Holocene), not necessarily currently erupting —
 * that's a separate live-alerts dataset (see Phase 2: USGS Volcano Hazards).
 *
 * Position on the globe is derived from [lat]/[lon] inside the renderer;
 * consumers don't need to know about the sphere mesh.
 */
data class Volcano(
    val name: String,
    val country: String,
    /** Smithsonian volcano "type" classification (Stratovolcano, Shield, Caldera, …). May be blank. */
    val type: String,
    /** Summit elevation in metres above sea level. Negative for submarine volcanoes. */
    val elevM: Int,
    val lat: Float,
    val lon: Float
)
