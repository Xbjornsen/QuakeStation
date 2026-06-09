package com.quakesphere.globe

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.quakesphere.globe.internal.GlobeRenderer

/**
 * A `View` that renders a 3D globe with markers, marker-stacks, and animated
 * ripples. The library handles geometry, lighting, touch-rotation, and tap
 * picking — consumers describe **what** to show via three setters and
 * receive a typed callback when a marker is tapped.
 *
 * Usage from XML or programmatically; from Compose use
 * `AndroidView(factory = { GlobeView(it) })` as you would any view.
 *
 * ```kotlin
 * val globe = GlobeView(context).apply {
 *     setMarkers(listOf(Marker(id = "m1", coord = GeoCoord(-33.0, 151.0),
 *                              color = 0xFFFF8800.toInt())))
 *     onMarkerClick = { hit -> Log.d("globe", "tapped ${hit.id}") }
 *     displaySettings = GlobeDisplaySettings(autoRotate = true)
 * }
 * ```
 *
 * The view supports two-finger pinch-to-zoom and single-finger drag-to-rotate
 * out of the box. Zoom is clamped to a sensible range inside the renderer so
 * callers can't pinch the globe into a single pixel or fly it past the camera.
 */
class GlobeView(context: Context) : GLSurfaceView(context) {

    private val renderer = GlobeRenderer(context.applicationContext)

    // ── Public callback ──────────────────────────────────────────────────────

    /** Invoked on the main thread when the user taps a [Marker]. */
    var onMarkerClick: ((Marker) -> Unit)? = null

    /**
     * Invoked on the main thread when the user taps a [MarkerStack] (e.g.
     * a swarm spine). Takes precedence over [onMarkerClick] when the tap
     * is closer to a stack centre than to any marker.
     */
    var onStackClick: ((MarkerStack) -> Unit)? = null

    /**
     * Invoked on the main thread when the user taps a [Volcano] triangle
     * (only fires when the volcano layer is enabled via [GlobeDisplaySettings.showVolcanoes]).
     */
    var onVolcanoClick: ((Volcano) -> Unit)? = null

    /**
     * Invoked on the main thread when the user taps a [Peak] marker (only
     * fires when the peaks layer is enabled via [GlobeDisplaySettings.showPeaks]).
     */
    var onPeakClick: ((Peak) -> Unit)? = null

    // ── Public data setters ──────────────────────────────────────────────────

    /** Replaces the flat marker layer. Markers in [markers] that share a
     *  position with a [MarkerStack] are *not* automatically suppressed — pass
     *  the lists you want drawn. */
    fun setMarkers(markers: List<Marker>) { renderer.updateMarkers(markers) }

    /** Replaces the stack layer (radial spines of grouped markers). */
    fun setStacks(stacks: List<MarkerStack>) { renderer.updateStacks(stacks) }

    /** Replaces the ripple layer (animated expanding rings). */
    fun setRipples(ripples: List<RippleSpec>) { renderer.updateRipples(ripples) }

    /** Marks one marker as "selected" (causes a soft pulse). Pass null to clear. */
    fun setSelectedMarker(id: String?) { renderer.setSelectedMarker(id) }

    /**
     * Smoothly rotates the globe so [coord] lands at the visible centre.
     * Uses the same easing as drag-rotate, so the camera glides over ~1 s
     * rather than snapping. Picks the shortest angular path automatically.
     */
    fun flyTo(coord: GeoCoord) {
        renderer.flyTo(coord.lat.toFloat(), coord.lon.toFloat())
    }

    // ── Display settings ─────────────────────────────────────────────────────

    /**
     * Pure-data toggle bundle. Set this whenever any of the contained flags
     * change; equality is checked field-by-field internally, so re-assigning
     * an identical instance is cheap.
     */
    var displaySettings: GlobeDisplaySettings = GlobeDisplaySettings()
        set(value) {
            field = value
            renderer.showContinentLines = value.showContinentLines
            renderer.showStars          = value.showStars
            renderer.autoRotate         = value.autoRotate
            renderer.showTectonicPlates = value.showTectonicPlates
            renderer.showHistoricTrends = value.showHistoricTrends
            renderer.showEquator        = value.showEquator
            renderer.showVolcanoes      = value.showVolcanoes
            renderer.showPeaks          = value.showPeaks
        }

    // ── Touch handling ───────────────────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.setZoom(detector.scaleFactor)
                requestRender()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                renderer.setRotation(-distanceX, -distanceY)
                requestRender()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val nx = (2.0f * e.x / width) - 1.0f
                val ny = 1.0f - (2.0f * e.y / height)
                queueEvent {
                    val hit = renderer.handleTap(nx, ny)
                    if (hit != null) post { onMarkerClick?.invoke(hit) }
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

    /**
     * Public read-only count of bundled Holocene volcanoes. Available
     * immediately after construction (loaded synchronously here, before
     * the GL surface comes up), so headers etc. can show "N volcanoes"
     * without waiting for a frame.
     */
    val volcanoCount: Int

    /**
     * Public read-only list of bundled Holocene volcanoes (full Volcano data
     * incl. lastEruption). Available immediately after construction. Consumers
     * use this for headers like "most recent eruption" pills, list screens, etc.
     */
    val volcanoes: List<Volcano>

    /** Public read-only list of bundled major peaks. Same lifetime / pattern as [volcanoes]. */
    val peaks: List<Peak>
    val peakCount: Int

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        // Load the bundled Holocene volcanoes on the main thread before the
        // GL surface exists. Tiny dataset (~70 entries / ~10 KB JSON), so
        // synchronous parse is well under a frame and lets consumers read
        // the count immediately.
        val entries = com.quakesphere.globe.internal.VolcanoesLoader.load(context)
        val list = entries.map { it.volcano }
        renderer.volcanoes        = list
        renderer.volcanoPositions = entries.map { it.pos }
        volcanoes                 = list
        volcanoCount              = list.size

        // Same pattern for peaks: synchronous load on the main thread before
        // the GL surface exists. ~60 entries / ~5 KB JSON, no perceptible cost.
        val peakEntries = com.quakesphere.globe.internal.PeaksLoader.load(context)
        val peakList = peakEntries.map { it.peak }
        renderer.peaks         = peakList
        renderer.peakPositions = peakEntries.map { it.pos }
        peaks                  = peakList
        peakCount              = peakList.size

        // Forward stack taps from the GL thread up to the main thread, where
        // consumers' Compose / View state lives.
        renderer.onStackTapped = { stack ->
            post { onStackClick?.invoke(stack) }
        }
        renderer.onVolcanoTapped = { volcano ->
            post { onVolcanoClick?.invoke(volcano) }
        }
        renderer.onPeakTapped = { peak ->
            post { onPeakClick?.invoke(peak) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        // Suppress single-finger rotation while a pinch is mid-gesture so
        // the globe doesn't simultaneously spin and zoom unpredictably.
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}
