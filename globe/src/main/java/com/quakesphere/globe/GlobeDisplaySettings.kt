package com.quakesphere.globe

/**
 * Cosmetic / display toggles for the globe. Pure data — pass to
 * [GlobeView.displaySettings] whenever any of these change.
 *
 * Defaults give a "Pacific-rim-quake-tracker style" look:
 * continents outlined, stars on, no auto-rotation.
 */
data class GlobeDisplaySettings(
    val showContinentLines: Boolean = true,
    val showStars:          Boolean = true,
    val autoRotate:         Boolean = false,
    /**
     * When true, the Bird (2003) PB2002 tectonic plate boundaries are
     * drawn over the globe in warm orange. Useful for relating quake
     * clusters to plate interactions.
     */
    val showTectonicPlates: Boolean = false,
    /**
     * When true, overlay a historic seismic-density heatmap on the ocean.
     * Bright yellow→red zones are where significant quakes cluster
     * geologically. Computed at first run from plate-boundary proximity.
     */
    val showHistoricTrends: Boolean = false,
    /**
     * When true, draw a thin reference circle around the globe at latitude 0
     * — the equator. Helpful for orientation when the globe is tilted.
     */
    val showEquator:        Boolean = false,
    /**
     * When true, show ~70 globally significant Holocene-active volcanoes
     * as small orange triangles. Tap to surface name / country / elevation.
     */
    val showVolcanoes:      Boolean = false,
    /**
     * When true, displace the globe sphere and continent fills outward by
     * a per-vertex elevation derived from the bundled peaks list (Gaussian
     * splat, max 3% of globe radius at Everest). The Himalayas / Andes /
     * Rockies bulge visibly off the surface. Continent outlines, plate
     * boundaries, the heatmap shell and markers still trace the un-displaced
     * sphere for now — small visual mismatch we'll close in a follow-up.
     */
    val showTopography:     Boolean = false
)
