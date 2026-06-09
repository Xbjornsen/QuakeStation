package com.quakesphere.globe.internal

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.quakesphere.globe.Marker
import com.quakesphere.globe.MarkerStack
import com.quakesphere.globe.RippleSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class GlobeRenderer(private val appContext: android.content.Context) : GLSurfaceView.Renderer {

    // ── Matrices ────────────────────────────────────────────────────────────
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix       = FloatArray(16)
    private val modelMatrix      = FloatArray(16)
    private val mvpMatrix        = FloatArray(16)
    private val mvMatrix         = FloatArray(16)
    private val normalMatrix     = FloatArray(16)

    // ── Globe rotation / zoom ────────────────────────────────────────────────
    // The public values are what the renderer actually uses each frame.
    // The `target*` shadows hold what the user has gestured towards; we
    // lerp the public values toward them every frame so the camera glides
    // rather than snaps. Result: same input feels much smoother on-screen.
    var rotationX = 0f
        private set
    var rotationY = 0f
        private set
    var zoom      = 1.0f
        private set

    @Volatile private var targetRotationX = 0f
    @Volatile private var targetRotationY = 0f
    @Volatile private var targetZoom      = 1.0f

    private val MIN_ZOOM = 0.5f
    private val MAX_ZOOM = 3.5f

    /** Per-frame interpolation factor — higher = snappier, lower = more glide. */
    private val ROTATION_LERP = 0.18f
    private val ZOOM_LERP     = 0.20f

    // ── GL programs ──────────────────────────────────────────────────────────
    private var globeProgram   = 0
    private var markerProgram  = 0
    private var starProgram    = 0
    private var lineProgram    = 0
    private var fillProgram    = 0
    private var heatmapProgram = 0

    // ── Globe mesh ───────────────────────────────────────────────────────────
    private var sphereVertexBuffer: FloatBuffer? = null
    private var sphereIndexBuffer: ShortBuffer?  = null
    private var sphereIndexCount = 0

    // ── Star field ───────────────────────────────────────────────────────────
    private var starVertexBuffer: FloatBuffer? = null
    private var starCount = 0

    // ── Continent lines ──────────────────────────────────────────────────────
    private var continentLineBuffer: FloatBuffer? = null
    private var continentLineVertexCount = 0

    // ── Continent fills ──────────────────────────────────────────────────────
    private var continentFillBuffer: FloatBuffer? = null
    private var continentFillVertexCount = 0

    // ── Tectonic plates ──────────────────────────────────────────────────────
    private var plateLineBuffer: FloatBuffer? = null
    private var plateLineVertexCount = 0

    // ── Historic heatmap (single-channel density texture overlaid on sphere) ─
    // The grid is generated on a background thread the first time the renderer
    // surfaces, then uploaded to GL on the next draw frame after it's ready.
    // That keeps the ~1-3 s GeoJSON parse + Gaussian splat off the critical
    // path to first frame — the globe paints immediately, heatmap appears a
    // beat later when the user has enabled the toggle.
    private var heatmapTextureId = 0
    @Volatile private var heatmapPixelsReady: ByteArray? = null
    @Volatile private var heatmapBuildStarted = false

    @Volatile var lastMvpMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // ── Markers (the flat marker layer) ──────────────────────────────────────
    @Volatile private var markers: List<Marker>        = emptyList()
    @Volatile private var markerPositions: List<FloatArray> = emptyList()
    private var selectedMarkerId: String? = null

    // ── Marker stacks (radial spines, e.g. swarms) ───────────────────────────
    @Volatile private var stacks: List<MarkerStack> = emptyList()
    @Volatile private var stackPositions: List<FloatArray> = emptyList()

    // ── Ripples (animated expanding rings) ───────────────────────────────────
    @Volatile private var ripples: List<RippleSpec> = emptyList()

    // ── Volcanoes (bundled Holocene set; rendered as small 3D cones) ─────────
    // Populated by GlobeView synchronously before the GL surface even exists,
    // so we never need to load on the GL thread (and the consumer can read
    // the count immediately for header text etc).
    @Volatile var volcanoes: List<com.quakesphere.globe.Volcano> = emptyList()
    @Volatile var volcanoPositions: List<FloatArray> = emptyList()

    // ── Peaks (bundled set of major peaks; flat white triangle markers) ──────
    @Volatile var peaks: List<com.quakesphere.globe.Peak> = emptyList()
    @Volatile var peakPositions: List<FloatArray> = emptyList()

    // ── Settings ─────────────────────────────────────────────────────────────
    @Volatile var showContinentLines = true
    @Volatile var showStars          = true
    @Volatile var autoRotate         = false
    @Volatile var showTectonicPlates = false
    @Volatile var showHistoricTrends = false
    @Volatile var showEquator        = false
    @Volatile var showVolcanoes      = false
    @Volatile var showPeaks          = false

    // ── Interaction tracking (used to pause auto-rotate after touches) ───────
    @Volatile private var lastInteractionMs = 0L
    private val AUTOROTATE_RESUME_DELAY_MS = 3000L

    // ── Tap callback ─────────────────────────────────────────────────────────
    var onMarkerTapped:  ((Marker) -> Unit)? = null
    var onStackTapped:   ((MarkerStack) -> Unit)? = null
    var onVolcanoTapped: ((com.quakesphere.globe.Volcano) -> Unit)? = null
    var onPeakTapped:    ((com.quakesphere.globe.Peak) -> Unit)? = null

    // ── Viewport ─────────────────────────────────────────────────────────────
    private var viewportWidth  = 1
    private var viewportHeight = 1
    private var aspectRatio    = 1f

    // ── Animation ────────────────────────────────────────────────────────────
    private var pulseTime = 0f

    // ════════════════════════════════════════════════════════════════════════
    // GLSL Shaders
    // ════════════════════════════════════════════════════════════════════════

    private val GLOBE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uMVMatrix;
        uniform mat4 uNormalMatrix;
        attribute vec4 aPosition;
        attribute vec3 aNormal;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        varying vec3 vNormal;
        varying vec3 vWorldPos;
        void main() {
            vec4 worldPos = uMVMatrix * aPosition;
            vWorldPos = worldPos.xyz;
            vNormal = normalize(mat3(uNormalMatrix) * aNormal);
            vTexCoord = aTexCoord;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val GLOBE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3 uSunDir;       // view light (fixed behind camera, drives diffuse)
        uniform vec3 uUtcSunDir;    // real subsolar point (drives night darkening)
        varying vec2 vTexCoord;
        varying vec3 vNormal;
        varying vec3 vWorldPos;

        void main() {
            // Pure deep-ocean base – continent fills rendered as separate geometry
            vec3 color = vec3(0.008, 0.030, 0.165);

            vec3 lightDir = normalize(uSunDir);
            vec3 n        = normalize(vNormal);
            float sunDot  = dot(n, lightDir);
            float diffuse = max(sunDot, 0.0);

            // Ocean diffuse — generous ambient floor so the limb still reads as ocean
            color *= (0.30 + diffuse * 0.70);

            // Subtle specular shimmer (reduced from before)
            vec3 viewDir    = normalize(-vWorldPos);
            vec3 reflectDir = reflect(-lightDir, n);
            float spec = pow(max(dot(reflectDir, viewDir), 0.0), 55.0);
            color += spec * 0.14 * vec3(0.5, 0.75, 1.0);

            // Atmosphere rim glow
            float rim = 1.0 - abs(dot(n, viewDir));
            rim = pow(rim, 2.8);
            color += rim * vec3(0.10, 0.35, 1.0) * 0.65;

            // Real-time UTC night darkening — only dims surfaces where the sun
            // is well below the horizon (deep night), and even then only by
            // ~22%. Continents stay readable everywhere; the terminator is
            // a subtle hint, not a hard divider.
            float utcSunDot = dot(n, normalize(uUtcSunDir));
            float nightFactor = smoothstep(-0.10, -0.40, utcSunDot);
            color *= mix(1.0, 0.78, nightFactor);

            gl_FragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    private val MARKER_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform float uSize;
        attribute vec4 aPosition;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() { vUV = aUV; gl_Position = uMVPMatrix * aPosition; }
    """.trimIndent()

    private val MARKER_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vUV;
        uniform vec3 uColor;
        uniform float uAlpha;
        void main() {
            float dist = length(vUV);
            if (dist > 1.0) discard;
            float core = smoothstep(0.3, 0.0, dist);
            float glow = smoothstep(1.0, 0.0, dist) * 0.6;
            float alpha = (core + glow) * uAlpha;
            vec3 color = mix(uColor * 1.5, uColor, dist);
            gl_FragColor = vec4(color, alpha);
        }
    """.trimIndent()

    private val STAR_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute float aBrightness;
        varying float vBrightness;
        void main() {
            vBrightness = aBrightness;
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = 2.0;
        }
    """.trimIndent()

    private val STAR_FRAGMENT_SHADER = """
        precision mediump float;
        varying float vBrightness;
        void main() {
            gl_FragColor = vec4(vBrightness, vBrightness, vBrightness + 0.1, 1.0);
        }
    """.trimIndent()

    // Lit fill shader – used for continent polygon fills
    private val FILL_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        varying vec3 vModelPos;
        void main() {
            vModelPos = aPosition.xyz;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val FILL_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3  uSunDirModel;       // view light in model space (drives diffuse)
        uniform vec3  uUtcSunDirModel;    // UTC sun in model space (drives night dim)
        uniform vec4  uFillColor;
        varying vec3  vModelPos;
        void main() {
            // Model-space normal == normalised position on unit sphere
            vec3 n = normalize(vModelPos);
            float sunDot  = dot(n, normalize(uSunDirModel));
            float diffuse = max(sunDot, 0.0);
            vec3 color = uFillColor.rgb * (0.25 + diffuse * 0.75);

            // Real-time UTC night darkening — only deep night gets a subtle dim.
            float utcSunDot = dot(n, normalize(uUtcSunDirModel));
            float nightFactor = smoothstep(-0.10, -0.40, utcSunDot);
            color *= mix(1.0, 0.78, nightFactor);

            gl_FragColor = vec4(color, uFillColor.a);
        }
    """.trimIndent()

    // Simple colour line shader (continent outlines + swarm spines)
    private val LINE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        void main() { gl_Position = uMVPMatrix * aPosition; }
    """.trimIndent()

    private val LINE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 uLineColor;
        void main() { gl_FragColor = uLineColor; }
    """.trimIndent()

    /**
     * Heatmap pass reuses the sphere mesh and its (u,v) coords.
     * v: pole-to-pole 0..1, u: 0..1 around the equator.
     */
    private val HEATMAP_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            // Push the heatmap mesh fractionally outside the globe sphere
            // (radius 1.005 vs 1.0). Without this every heatmap fragment has
            // the same depth as the just-rendered globe and GL_LESS rejects
            // 100% of them — net result: the layer never appeared.
            gl_Position = uMVPMatrix * vec4(aPosition.xyz * 1.005, 1.0);
        }
    """.trimIndent()

    /**
     * Samples the density texture, maps the 0..1 intensity through a warm
     * heatmap colour ramp, and outputs with alpha proportional to intensity
     * so faint zones stay subtle and the busy ones glow.
     */
    private val HEATMAP_FRAGMENT_SHADER = """
        precision mediump float;
        uniform sampler2D uHeatmap;
        varying vec2 vTexCoord;
        void main() {
            float v = texture2D(uHeatmap, vTexCoord).r;
            if (v < 0.04) discard;
            // Three-stop warm ramp: cool yellow → orange → red.
            vec3 c1 = vec3(1.00, 0.90, 0.30);   // low  – pale yellow
            vec3 c2 = vec3(1.00, 0.50, 0.10);   // mid  – orange
            vec3 c3 = vec3(1.00, 0.10, 0.05);   // high – red
            vec3 color = v < 0.5
                ? mix(c1, c2, v * 2.0)
                : mix(c2, c3, (v - 0.5) * 2.0);
            // Alpha climbs from 0.18 at the threshold to 0.85 at the peak.
            float a = 0.18 + v * 0.67;
            gl_FragColor = vec4(color, a);
        }
    """.trimIndent()

    // ════════════════════════════════════════════════════════════════════════
    // GLSurfaceView.Renderer
    // ════════════════════════════════════════════════════════════════════════

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.04f, 0.10f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        globeProgram  = createProgram(GLOBE_VERTEX_SHADER,  GLOBE_FRAGMENT_SHADER)
        markerProgram = createProgram(MARKER_VERTEX_SHADER, MARKER_FRAGMENT_SHADER)
        starProgram   = createProgram(STAR_VERTEX_SHADER,   STAR_FRAGMENT_SHADER)
        lineProgram   = createProgram(LINE_VERTEX_SHADER,   LINE_FRAGMENT_SHADER)
        fillProgram   = createProgram(FILL_VERTEX_SHADER,   FILL_FRAGMENT_SHADER)
        heatmapProgram = createProgram(HEATMAP_VERTEX_SHADER, HEATMAP_FRAGMENT_SHADER)

        setupGlobe()
        setupStarField()
        setupContinents()
        setupTectonicPlates()
        // Volcanoes are loaded by GlobeView before the GL surface comes up,
        // so the data is already on `volcanoes` / `volcanoPositions` by here.
        // Heatmap is built on a background thread; the texture upload happens
        // in onDrawFrame when the pixels are ready. See [maybeUploadHeatmap].
        startHeatmapBuild()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth  = width
        viewportHeight = height
        aspectRatio    = width.toFloat() / height.toFloat()
        // Projection is rebuilt every frame in onDrawFrame so the near plane
        // can adapt to the current zoom — see the `Matrix.frustumM` call below.
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        pulseTime += 0.05f
        // Auto-rotate, but politely yield to the user — pause for 3 s after
        // any drag / pinch / tap so they can study what they're looking at.
        // Advances the *target* so the rotation lerp follows along smoothly.
        if (autoRotate && System.currentTimeMillis() - lastInteractionMs > AUTOROTATE_RESUME_DELAY_MS) {
            targetRotationY += 0.018f
        }

        // Smooth interpolation toward gesture targets — this is what makes
        // pinch-zoom and drag-rotate feel like a gliding camera rather than
        // jumping in discrete steps once per touch event.
        rotationX += (targetRotationX - rotationX) * ROTATION_LERP
        rotationY += (targetRotationY - rotationY) * ROTATION_LERP
        zoom      += (targetZoom      - zoom     ) * ZOOM_LERP

        val camDist = 4.8f / zoom
        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, camDist,        // base distance; user controls `zoom` via pinch
            0f, 0f, 0f,
            0f, 1f, 0f
        )

        // ── Dynamic projection ──────────────────────────────────────────────
        // Keep the near plane just in front of the closest part of the globe
        // (sphere radius 1.0) so pinch-zoom never clips the surface — earlier
        // versions used a fixed near=2 which made the front cap of the globe
        // disappear past ~zoom 1.7, revealing the back hemisphere through a
        // circular "hole". The frustum width/height scale with `near` so the
        // effective FOV stays constant regardless of zoom.
        val near = (camDist - 1.05f).coerceAtLeast(0.05f)
        val far  = camDist + 80f
        val nScale = near / 2.0f   // original near was 2 with extents ±aspect, ±1
        Matrix.frustumM(
            projectionMatrix, 0,
            -aspectRatio * nScale, aspectRatio * nScale,
            -1f * nScale, 1f * nScale,
            near, far
        )

        if (showStars) drawStarField()

        // Build globe MVP with current rotation
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        Matrix.invertM(normalMatrix, 0, mvMatrix, 0)
        transposeMatrix(normalMatrix)

        lastMvpMatrix = mvpMatrix.copyOf()

        drawGlobe()
        if (showHistoricTrends) {
            maybeUploadHeatmap()
            drawHeatmap()                                       // sit over ocean, under land
        }
        drawContinentFills()                                    // filled land before outlines
        if (showContinentLines) drawContinentLines()
        if (showTectonicPlates) drawTectonicPlates()
        if (showEquator)        drawEquator()
        drawPoleAxisLine()
        drawPoleIndicators()
        drawRipples()
        drawStacks()
        drawMarkers()
        // Volcanoes drawn LAST so they sit on top of the quake-marker layer.
        // With 500+ markers visible the tiny triangles get painted over
        // otherwise and become invisible.
        if (showVolcanoes)      drawVolcanoes()
        if (showPeaks)          drawPeaks()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupGlobe() {
        val (vertices, indices) = generateSphere(32, 64)
        sphereIndexCount = indices.size

        sphereVertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(vertices); position(0) }

        sphereIndexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(indices); position(0) }
    }

    private fun generateSphere(stacks: Int, slices: Int): Pair<FloatArray, ShortArray> {
        val vertices = mutableListOf<Float>()
        val indices  = mutableListOf<Short>()
        for (i in 0..stacks) {
            val lat    = (PI * i / stacks - PI / 2).toFloat()
            val cosLat = cos(lat); val sinLat = sin(lat)
            for (j in 0..slices) {
                val lon    = (2.0 * PI * j / slices).toFloat()
                val cosLon = cos(lon); val sinLon = sin(lon)
                val x = cosLat * cosLon; val y = sinLat; val z = cosLat * sinLon
                vertices.addAll(listOf(x, y, z, x, y, z,
                    j.toFloat() / slices, i.toFloat() / stacks))
            }
        }
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val first  = (i * (slices + 1) + j).toShort()
                val second = (first + slices + 1).toShort()
                indices.addAll(listOf(first, second, (first + 1).toShort(),
                    second, (second + 1).toShort(), (first + 1).toShort()))
            }
        }
        return Pair(vertices.toFloatArray(), indices.toShortArray())
    }

    private fun setupStarField() {
        starCount = 1000
        val stars = FloatArray(starCount * 4)
        val r = java.util.Random(42L)
        for (i in 0 until starCount) {
            val theta  = (r.nextFloat() * 2.0 * PI).toFloat()
            val phi    = acos(1.0 - 2.0 * r.nextFloat()).toFloat()
            val radius = 50f
            stars[i*4]   = radius * sin(phi) * cos(theta)
            stars[i*4+1] = radius * sin(phi) * sin(theta)
            stars[i*4+2] = radius * cos(phi)
            stars[i*4+3] = 0.3f + r.nextFloat() * 0.7f
        }
        starVertexBuffer = ByteBuffer.allocateDirect(stars.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(stars); position(0) }
    }

    /**
     * Builds the continent fill mesh and outline mesh from Natural Earth GeoJSON.
     * Polygons are triangulated with [Earcut] (handles concave shapes correctly)
     * then projected onto the sphere.
     */
    private fun setupContinents() {
        val geometry = NaturalEarthLoader.load(appContext)

        continentFillVertexCount = geometry.fillVertices.size / 3
        continentFillBuffer = ByteBuffer.allocateDirect(geometry.fillVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(geometry.fillVertices); position(0) }

        continentLineVertexCount = geometry.lineVertices.size / 3
        continentLineBuffer = ByteBuffer.allocateDirect(geometry.lineVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(geometry.lineVertices); position(0) }
    }

    /**
     * Load the PB2002 tectonic plate boundaries (Bird 2003) as a single line-
     * list buffer. Reuses the line shader the continent outlines already use.
     */
    private fun setupTectonicPlates() {
        val verts = TectonicPlatesLoader.load(appContext)
        plateLineVertexCount = verts.size / 3
        plateLineBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }
    }

    /**
     * Kick off the density-grid build on a low-priority background thread.
     * When [HeatmapGenerator.build] returns we stash the bytes; the GL thread
     * picks them up in [maybeUploadHeatmap] on the next draw and creates the
     * texture there. This keeps the GL surface-create path snappy (otherwise
     * the user sees a blank screen for several seconds before the globe
     * appears).
     */
    private fun startHeatmapBuild() {
        if (heatmapBuildStarted) return
        heatmapBuildStarted = true
        Thread({
            try { heatmapPixelsReady = HeatmapGenerator.build(appContext) }
            catch (_: Throwable) { /* leave null — heatmap simply won't draw */ }
        }, "quakesphere-heatmap").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    /**
     * Called from the GL thread once per frame while the heatmap is enabled.
     * If the background build has finished and we haven't uploaded yet, create
     * the GL texture and consume the pixel buffer. Cheap no-op after that.
     */
    private fun maybeUploadHeatmap() {
        if (heatmapTextureId != 0) return
        val pixels = heatmapPixelsReady ?: return
        heatmapPixelsReady = null

        val buf = ByteBuffer.allocateDirect(pixels.size)
            .order(ByteOrder.nativeOrder())
            .apply { put(pixels); position(0) }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        heatmapTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, heatmapTextureId)

        // Single-channel grayscale density. GL ES 2 doesn't have GL_RED, so we
        // upload as GL_LUMINANCE which exposes the value in the .r channel.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0,
            GLES20.GL_LUMINANCE,
            HeatmapGenerator.WIDTH, HeatmapGenerator.HEIGHT, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Wrap horizontally so seam at lon=±180 doesn't show.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Draw methods
    // ════════════════════════════════════════════════════════════════════════

    private fun drawStarField() {
        GLES20.glUseProgram(starProgram)
        val starMVP = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        val tmpMV = FloatArray(16)
        Matrix.multiplyMM(tmpMV, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(starMVP, 0, projectionMatrix, 0, tmpMV, 0)

        val mvpH  = GLES20.glGetUniformLocation(starProgram, "uMVPMatrix")
        val posH  = GLES20.glGetAttribLocation(starProgram, "aPosition")
        val brigH = GLES20.glGetAttribLocation(starProgram, "aBrightness")
        GLES20.glUniformMatrix4fv(mvpH, 1, false, starMVP, 0)

        val STRIDE = 16
        starVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, STRIDE, starVertexBuffer)
        GLES20.glEnableVertexAttribArray(posH)
        starVertexBuffer?.position(3)
        GLES20.glVertexAttribPointer(brigH, 1, GLES20.GL_FLOAT, false, STRIDE, starVertexBuffer)
        GLES20.glEnableVertexAttribArray(brigH)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
        GLES20.glDisableVertexAttribArray(posH)
        GLES20.glDisableVertexAttribArray(brigH)

        // Restore modelMatrix for globe
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
    }

    /**
     * Paint the precomputed seismic-density heatmap over the sphere. Reuses
     * the sphere mesh's UVs so it stays perfectly aligned with the ocean
     * underneath. Blended additively so it tints rather than replaces.
     */
    private fun drawHeatmap() {
        if (heatmapTextureId == 0) return
        GLES20.glUseProgram(heatmapProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Avoid z-fighting with the ocean — draw with depth-test on but
        // depth-write off so subsequent fills (continents) still win.
        GLES20.glDepthMask(false)

        val mvpH = GLES20.glGetUniformLocation(heatmapProgram, "uMVPMatrix")
        val texU = GLES20.glGetUniformLocation(heatmapProgram, "uHeatmap")
        val posH = GLES20.glGetAttribLocation(heatmapProgram,  "aPosition")
        val texA = GLES20.glGetAttribLocation(heatmapProgram,  "aTexCoord")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, heatmapTextureId)
        GLES20.glUniform1i(texU, 0)
        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)

        val STRIDE = 32 // matches sphere vertex layout
        sphereVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, STRIDE, sphereVertexBuffer)
        GLES20.glEnableVertexAttribArray(posH)
        sphereVertexBuffer?.position(6)
        GLES20.glVertexAttribPointer(texA, 2, GLES20.GL_FLOAT, false, STRIDE, sphereVertexBuffer)
        GLES20.glEnableVertexAttribArray(texA)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount,
            GLES20.GL_UNSIGNED_SHORT, sphereIndexBuffer)

        GLES20.glDisableVertexAttribArray(posH)
        GLES20.glDisableVertexAttribArray(texA)
        GLES20.glDepthMask(true)
    }

    private fun drawGlobe() {
        GLES20.glUseProgram(globeProgram)
        val mvpH    = GLES20.glGetUniformLocation(globeProgram, "uMVPMatrix")
        val mvH     = GLES20.glGetUniformLocation(globeProgram, "uMVMatrix")
        val normH   = GLES20.glGetUniformLocation(globeProgram, "uNormalMatrix")
        val sunH    = GLES20.glGetUniformLocation(globeProgram, "uSunDir")
        val utcSunH = GLES20.glGetUniformLocation(globeProgram, "uUtcSunDir")
        val posH    = GLES20.glGetAttribLocation(globeProgram, "aPosition")
        val normalH = GLES20.glGetAttribLocation(globeProgram, "aNormal")
        val texH    = GLES20.glGetAttribLocation(globeProgram, "aTexCoord")

        val viewLight = computeViewLightDirection()
        val utcSun    = computeUtcSunDirection()
        GLES20.glUniformMatrix4fv(mvpH,  1, false, mvpMatrix,    0)
        GLES20.glUniformMatrix4fv(mvH,   1, false, mvMatrix,     0)
        GLES20.glUniformMatrix4fv(normH, 1, false, normalMatrix, 0)
        GLES20.glUniform3f(sunH,    viewLight[0], viewLight[1], viewLight[2])
        GLES20.glUniform3f(utcSunH, utcSun[0],    utcSun[1],    utcSun[2])

        val STRIDE = 32 // 8 floats × 4 bytes
        sphereVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(posH,    3, GLES20.GL_FLOAT, false, STRIDE, sphereVertexBuffer)
        GLES20.glEnableVertexAttribArray(posH)
        sphereVertexBuffer?.position(3)
        GLES20.glVertexAttribPointer(normalH, 3, GLES20.GL_FLOAT, false, STRIDE, sphereVertexBuffer)
        GLES20.glEnableVertexAttribArray(normalH)
        sphereVertexBuffer?.position(6)
        GLES20.glVertexAttribPointer(texH,    2, GLES20.GL_FLOAT, false, STRIDE, sphereVertexBuffer)
        GLES20.glEnableVertexAttribArray(texH)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount,
            GLES20.GL_UNSIGNED_SHORT, sphereIndexBuffer)

        GLES20.glDisableVertexAttribArray(posH)
        GLES20.glDisableVertexAttribArray(normalH)
        GLES20.glDisableVertexAttribArray(texH)
    }

    private fun drawContinentLines() {
        val buf = continentLineBuffer ?: return
        GLES20.glUseProgram(lineProgram)

        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")

        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        // Soft blue-white continent lines
        GLES20.glUniform4f(colorH, 0.55f, 0.72f, 0.92f, 0.40f)

        buf.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, continentLineVertexCount)
        GLES20.glDisableVertexAttribArray(posH)
    }

    private fun drawTectonicPlates() {
        val buf = plateLineBuffer ?: return
        if (plateLineVertexCount == 0) return
        GLES20.glUseProgram(lineProgram)

        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")

        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        // Warm orange — distinguishable from cool-blue continent outlines and
        // from the yellow swarm spines.
        GLES20.glUniform4f(colorH, 1.00f, 0.55f, 0.20f, 0.75f)

        buf.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glLineWidth(2.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, plateLineVertexCount)
        GLES20.glDisableVertexAttribArray(posH)
    }

    private fun drawContinentFills() {
        val buf = continentFillBuffer ?: return
        GLES20.glUseProgram(fillProgram)

        val posH      = GLES20.glGetAttribLocation(fillProgram,  "aPosition")
        val mvpH      = GLES20.glGetUniformLocation(fillProgram, "uMVPMatrix")
        val sunH      = GLES20.glGetUniformLocation(fillProgram, "uSunDirModel")
        val utcSunH   = GLES20.glGetUniformLocation(fillProgram, "uUtcSunDirModel")
        val colorH    = GLES20.glGetUniformLocation(fillProgram, "uFillColor")

        // Both lights are world-space; transform them into model space because
        // the fill shader uses model-space normals (vertex position on unit sphere).
        val invModel       = FloatArray(16)
        Matrix.invertM(invModel, 0, modelMatrix, 0)
        val viewLightModel = transformDir(invModel, computeViewLightDirection())
        val utcSunModel    = transformDir(invModel, computeUtcSunDirection())

        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(sunH,    viewLightModel[0], viewLightModel[1], viewLightModel[2])
        GLES20.glUniform3f(utcSunH, utcSunModel[0],    utcSunModel[1],    utcSunModel[2])
        GLES20.glUniform4f(colorH, 0.20f, 0.36f, 0.12f, 1.0f)

        buf.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, continentFillVertexCount)
        GLES20.glDisableVertexAttribArray(posH)
    }

    private fun drawPoleAxisLine() {
        // Rotation axis: north pole (0,1.5,0) → south pole (0,-1.5,0) in model space
        val north = floatArrayOf(0f,  1.5f, 0f)
        val south = floatArrayOf(0f, -1.5f, 0f)
        drawLineSegment(north, south, 0.70f, 0.88f, 1.0f, 0.35f)
    }

    /**
     * Equator: a thin reference circle around the globe at latitude = 0.
     * Drawn slightly above the surface (r = 1.005) so it's not z-fought by
     * the sphere mesh, in a soft cyan that reads against both ocean and land.
     */
    private val equatorBuffer: java.nio.FloatBuffer by lazy {
        val segments = 180
        val r = 1.005f
        val verts = FloatArray(segments * 3)
        for (i in 0 until segments) {
            val a = (2.0 * PI * i / segments).toFloat()
            verts[i * 3]     = r * cos(a)
            verts[i * 3 + 1] = 0f
            verts[i * 3 + 2] = r * sin(a)
        }
        ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }
    }

    /**
     * Draw each volcano as a small 3D cone (3-sided pyramid) sticking out
     * of the globe's surface along the local normal. Per-face shading is
     * computed CPU-side from a fixed view-light direction so the cones
     * read as 3D even though we're using a flat-color shader: the face
     * pointing toward the light is bright orange, the back faces darker.
     *
     * Cone geometry per volcano:
     *   - base: 3 verts on the tangent plane at pos, on a small circle of
     *     radius BASE_R around the surface normal
     *   - apex: pos + n * HEIGHT
     *   - 3 side triangles (b_i, b_(i+1), apex)
     *
     * Cost: ~70 volcanoes × 3 faces = 210 tiny draw calls. Fine — the
     * marker layer already does similar numbers and there's no GPU
     * pressure at this scale.
     */
    private fun drawVolcanoes() {
        val positions = volcanoPositions
        if (positions.isEmpty()) return

        // Small enough to not clutter a busy hemisphere; tall enough to read
        // as a mountain rather than a triangle.
        val baseRadius = 0.013f
        val height     = 0.030f

        // Fixed light direction in *world* space (matches diffuse light used
        // by continent fills). The visible hemisphere is therefore always lit.
        val viewLight  = computeViewLightDirection()           // world space
        val invModel   = FloatArray(16).also { Matrix.invertM(it, 0, modelMatrix, 0) }
        val lightModel = normalize3(transformDir(invModel, viewLight))

        GLES20.glUseProgram(lineProgram)
        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")
        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Backface culling: face winding is CCW outward; cull the inside
        // walls of the cone so the lit/unlit faces don't overdraw.
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glFrontFace(GLES20.GL_CCW)

        // Base colour: warm orange. Per-face shading tints between BASE_R/G/B
        // (~brightness 1.0) at the lit face down to about 30% on the dark face.
        val baseR = 0.95f; val baseG = 0.42f; val baseB = 0.08f

        // Reusable scratch buffer — one triangle = 9 floats = 36 bytes.
        val triBytes = ByteBuffer.allocateDirect(36)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val triArr = FloatArray(9)

        for (pos in positions) {
            val n = normalize3(pos)
            val (t1, t2) = tangentFrame(n)

            val apex = floatArrayOf(
                pos[0] + n[0] * height,
                pos[1] + n[1] * height,
                pos[2] + n[2] * height
            )

            // 3 base verts at 0°, 120°, 240° around the cone axis.
            val cos120 = -0.5f
            val sin120 =  0.8660254f
            val cos240 = -0.5f
            val sin240 = -0.8660254f
            val b = arrayOf(
                floatArrayOf(
                    pos[0] + t1[0] * baseRadius,
                    pos[1] + t1[1] * baseRadius,
                    pos[2] + t1[2] * baseRadius
                ),
                floatArrayOf(
                    pos[0] + (t1[0] * cos120 + t2[0] * sin120) * baseRadius,
                    pos[1] + (t1[1] * cos120 + t2[1] * sin120) * baseRadius,
                    pos[2] + (t1[2] * cos120 + t2[2] * sin120) * baseRadius
                ),
                floatArrayOf(
                    pos[0] + (t1[0] * cos240 + t2[0] * sin240) * baseRadius,
                    pos[1] + (t1[1] * cos240 + t2[1] * sin240) * baseRadius,
                    pos[2] + (t1[2] * cos240 + t2[2] * sin240) * baseRadius
                )
            )

            for (i in 0 until 3) {
                val b1 = b[i]
                val b2 = b[(i + 1) % 3]
                // Face normal (CCW: b1 → b2 → apex).
                val e1x = b2[0] - b1[0]; val e1y = b2[1] - b1[1]; val e1z = b2[2] - b1[2]
                val e2x = apex[0] - b1[0]; val e2y = apex[1] - b1[1]; val e2z = apex[2] - b1[2]
                val faceN = normalize3(floatArrayOf(
                    e1y * e2z - e1z * e2y,
                    e1z * e2x - e1x * e2z,
                    e1x * e2y - e1y * e2x
                ))
                val dot = faceN[0] * lightModel[0] + faceN[1] * lightModel[1] + faceN[2] * lightModel[2]
                // Map [-1,1] → [0.3, 1.0] so even the dark side stays visible.
                val brightness = 0.65f + 0.35f * dot
                val r = (baseR * brightness).coerceIn(0f, 1f)
                val g = (baseG * brightness).coerceIn(0f, 1f)
                val bC = (baseB * brightness).coerceIn(0f, 1f)
                GLES20.glUniform4f(colorH, r, g, bC, 1.0f)

                triArr[0] = b1[0]; triArr[1] = b1[1]; triArr[2] = b1[2]
                triArr[3] = b2[0]; triArr[4] = b2[1]; triArr[5] = b2[2]
                triArr[6] = apex[0]; triArr[7] = apex[1]; triArr[8] = apex[2]
                triBytes.position(0); triBytes.put(triArr); triBytes.position(0)
                GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, triBytes)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            }
        }

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(posH)
    }

    /**
     * Draw each peak as a small camera-facing filled triangle in cool white
     * with a dark edge. Distinct from volcano cones (filled orange 3D) by
     * colour + flat geometry — both are mountains but at a glance you can
     * tell which is which.
     *
     * Implementation mirrors the original flat-triangle volcano draw before
     * we upgraded to cones — billboards always face the camera so they read
     * as triangles from every angle. Far-side peaks are occluded by the
     * sphere's depth buffer; no explicit cull needed.
     */
    private fun drawPeaks() {
        val positions = peakPositions
        if (positions.isEmpty()) return

        val right = floatArrayOf(mvMatrix[0], mvMatrix[4], mvMatrix[8])
        val up    = floatArrayOf(mvMatrix[1], mvMatrix[5], mvMatrix[9])
        val size  = 0.040f   // smaller than volcanoes (0.060)

        val fillVerts = ArrayList<Float>(positions.size * 9)
        val edgeVerts = ArrayList<Float>(positions.size * 18)
        for (pos in positions) {
            val tx = pos[0] + up[0] * size
            val ty = pos[1] + up[1] * size
            val tz = pos[2] + up[2] * size
            val blx = pos[0] + (-right[0] - up[0] * 0.5f) * size
            val bly = pos[1] + (-right[1] - up[1] * 0.5f) * size
            val blz = pos[2] + (-right[2] - up[2] * 0.5f) * size
            val brx = pos[0] + ( right[0] - up[0] * 0.5f) * size
            val bry = pos[1] + ( right[1] - up[1] * 0.5f) * size
            val brz = pos[2] + ( right[2] - up[2] * 0.5f) * size

            fillVerts.add(tx); fillVerts.add(ty); fillVerts.add(tz)
            fillVerts.add(blx); fillVerts.add(bly); fillVerts.add(blz)
            fillVerts.add(brx); fillVerts.add(bry); fillVerts.add(brz)

            edgeVerts.add(tx);  edgeVerts.add(ty);  edgeVerts.add(tz);  edgeVerts.add(brx); edgeVerts.add(bry); edgeVerts.add(brz)
            edgeVerts.add(brx); edgeVerts.add(bry); edgeVerts.add(brz); edgeVerts.add(blx); edgeVerts.add(bly); edgeVerts.add(blz)
            edgeVerts.add(blx); edgeVerts.add(bly); edgeVerts.add(blz); edgeVerts.add(tx);  edgeVerts.add(ty);  edgeVerts.add(tz)
        }
        if (fillVerts.isEmpty()) return

        GLES20.glUseProgram(lineProgram)
        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")
        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Fill: cool blue-white (snowcap).
        val fillArr = FloatArray(fillVerts.size).also { for (i in it.indices) it[i] = fillVerts[i] }
        val fillBuf = ByteBuffer.allocateDirect(fillArr.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(fillArr); position(0) }
        GLES20.glUniform4f(colorH, 0.92f, 0.96f, 1.00f, 0.95f)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, fillBuf)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, fillArr.size / 3)

        // Edge: dark slate for contrast.
        val edgeArr = FloatArray(edgeVerts.size).also { for (i in it.indices) it[i] = edgeVerts[i] }
        val edgeBuf = ByteBuffer.allocateDirect(edgeArr.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(edgeArr); position(0) }
        GLES20.glUniform4f(colorH, 0.05f, 0.10f, 0.18f, 0.95f)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, edgeBuf)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, edgeArr.size / 3)

        GLES20.glDisableVertexAttribArray(posH)
    }

    private fun drawEquator() {
        GLES20.glUseProgram(lineProgram)
        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")

        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        // Soft cyan — distinct from continent (blue-white) and plate (orange).
        GLES20.glUniform4f(colorH, 0.45f, 0.90f, 1.00f, 0.55f)

        equatorBuffer.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, equatorBuffer)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glLineWidth(1.8f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 180)
        GLES20.glDisableVertexAttribArray(posH)
    }

    private fun drawMarkers() {
        if (markerPositions.isEmpty()) return
        GLES20.glUseProgram(markerProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        val mvpH    = GLES20.glGetUniformLocation(markerProgram, "uMVPMatrix")
        val colorH  = GLES20.glGetUniformLocation(markerProgram, "uColor")
        val alphaH  = GLES20.glGetUniformLocation(markerProgram, "uAlpha")
        val sizeH   = GLES20.glGetUniformLocation(markerProgram, "uSize")
        val posH    = GLES20.glGetAttribLocation(markerProgram,  "aPosition")
        val uvH     = GLES20.glGetAttribLocation(markerProgram,  "aUV")

        val currentMarkers = markers
        val selectedId     = selectedMarkerId

        currentMarkers.forEachIndexed { index, marker ->
            if (index >= markerPositions.size) return@forEachIndexed

            val pos  = markerPositions[index]
            val size = (0.045f * marker.sizeHint).coerceIn(0.025f, 0.16f)
            val alpha = if (marker.pulsing || marker.id == selectedId)
                0.9f + 0.1f * sin(pulseTime * 4f) else 0.75f

            val (r, g, b) = unpackArgb(marker.color)
            drawBillboardMarker(mvpH, colorH, alphaH, sizeH, posH, uvH, pos, size, r, g, b, alpha)
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawStacks() {
        val currentStacks = stacks
        if (currentStacks.isEmpty()) return

        for (stack in currentStacks) {
            val base = latLonToXYZ(stack.centre.lat.toFloat(), stack.centre.lon.toFloat(), 1.02f)
            val normal = normalize3(base)
            val spineLen = stack.markers.size * 0.042f
            val tipR = 1.02f + spineLen
            val tip  = floatArrayOf(normal[0]*tipR, normal[1]*tipR, normal[2]*tipR)

            // Spine line – warm gold
            drawLineSegment(base, tip, 1.0f, 0.75f, 0.1f, 0.85f)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glUseProgram(markerProgram)

            val mvpH   = GLES20.glGetUniformLocation(markerProgram, "uMVPMatrix")
            val colorH = GLES20.glGetUniformLocation(markerProgram, "uColor")
            val alphaH = GLES20.glGetUniformLocation(markerProgram, "uAlpha")
            val sizeH  = GLES20.glGetUniformLocation(markerProgram, "uSize")
            val posH   = GLES20.glGetAttribLocation(markerProgram,  "aPosition")
            val uvH    = GLES20.glGetAttribLocation(markerProgram,  "aUV")

            for ((i, marker) in stack.markers.withIndex()) {
                val r    = 1.02f + i * 0.042f
                val pos  = floatArrayOf(normal[0]*r, normal[1]*r, normal[2]*r)
                val size = (0.050f * marker.sizeHint).coerceIn(0.030f, 0.090f)
                val (er, eg, eb) = unpackArgb(marker.color)
                val alpha = if (i == 0 || marker.pulsing) 0.9f + 0.1f * sin(pulseTime * 3f) else 0.85f
                drawBillboardMarker(mvpH, colorH, alphaH, sizeH, posH, uvH, pos, size, er, eg, eb, alpha)
            }
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    /** Draw a billboard marker at a pre-computed 3D world position. */
    private fun drawBillboardMarker(
        mvpH: Int, colorH: Int, alphaH: Int, sizeH: Int, posH: Int, uvH: Int,
        pos: FloatArray, size: Float, r: Float, g: Float, b: Float, alpha: Float
    ) {
        val right = floatArrayOf(mvMatrix[0], mvMatrix[4], mvMatrix[8])
        val up    = floatArrayOf(mvMatrix[1], mvMatrix[5], mvMatrix[9])
        val s = size

        val bverts = floatArrayOf(
            pos[0]+(-right[0]-up[0])*s, pos[1]+(-right[1]-up[1])*s, pos[2]+(-right[2]-up[2])*s, -1f,-1f,
            pos[0]+( right[0]-up[0])*s, pos[1]+( right[1]-up[1])*s, pos[2]+( right[2]-up[2])*s,  1f,-1f,
            pos[0]+(-right[0]+up[0])*s, pos[1]+(-right[1]+up[1])*s, pos[2]+(-right[2]+up[2])*s, -1f, 1f,
            pos[0]+( right[0]+up[0])*s, pos[1]+( right[1]+up[1])*s, pos[2]+( right[2]+up[2])*s,  1f, 1f
        )

        val bb = ByteBuffer.allocateDirect(bverts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(bverts); position(0) }

        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(colorH, r, g, b)
        GLES20.glUniform1f(alphaH, alpha)
        GLES20.glUniform1f(sizeH, size)

        val STRIDE = 20
        bb.position(0)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, STRIDE, bb)
        GLES20.glEnableVertexAttribArray(posH)
        bb.position(3)
        GLES20.glVertexAttribPointer(uvH, 2, GLES20.GL_FLOAT, false, STRIDE, bb)
        GLES20.glEnableVertexAttribArray(uvH)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posH)
        GLES20.glDisableVertexAttribArray(uvH)
    }

    /** Draw a single GL_LINES segment using the line shader. */
    private fun drawLineSegment(p0: FloatArray, p1: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glUseProgram(lineProgram)
        val verts = floatArrayOf(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2])
        val buf = ByteBuffer.allocateDirect(24)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }

        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")

        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorH, r, g, b, a)

        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glLineWidth(2.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glDisableVertexAttribArray(posH)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Pole indicators
    // ════════════════════════════════════════════════════════════════════════

    private fun drawPoleIndicators() {
        // North pole ring – cool white
        drawRingOnSphere(floatArrayOf(0f, 1.035f, 0f), 0.028f, 0.88f, 0.96f, 1.00f, 0.85f)
        // South pole ring – icy blue
        drawRingOnSphere(floatArrayOf(0f, -1.035f, 0f), 0.022f, 0.60f, 0.82f, 1.00f, 0.70f)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Ripples — expanding rings around RippleSpec centres
    // ════════════════════════════════════════════════════════════════════════

    private fun drawRipples() {
        val currentRipples = ripples
        if (currentRipples.isEmpty()) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)  // additive glow

        for (ripple in currentRipples) {
            val center = latLonToXYZ(ripple.centre.lat.toFloat(), ripple.centre.lon.toFloat(), 1.026f)
            val normal = normalize3(center)
            val (t1, t2) = tangentFrame(normal)
            val (r, g, b) = unpackArgb(ripple.color)
            val peakA = ((ripple.color ushr 24) and 0xFF) / 255f

            // Per-ripple shape, with hard safety clamps.
            val rings    = ripple.ringCount.coerceIn(1, 12)
            val speedMul = ripple.speed.coerceAtLeast(0.05f)
            val radMax   = ripple.maxRadius.coerceIn(0.02f, 0.6f)

            // Base pulse cadence (0.18f) is intentionally slower than the
            // previous fixed 0.30f — calibrated so a default M5 ripple feels
            // alive, not frantic. Caller's `speed` multiplies on top.
            val cadence = 0.18f * speedMul

            for (ring in 0 until rings) {
                val phase  = ((pulseTime * cadence) + ring.toFloat() / rings) % 1.0f
                val radius = phase * radMax
                val alpha  = (1.0f - phase) * peakA
                if (alpha < 0.04f) continue
                drawRingOnSphere(center, radius, r, g, b, alpha,
                    t1Override = t1, t2Override = t2)
            }
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /**
     * Draw a GL_LINE_LOOP circle on the sphere surface.
     * [center] is the 3D world-space centre point (model space).
     * [radius] is the ring radius in GL units.
     * If t1Override/t2Override are null the tangent frame is auto-computed.
     */
    private fun drawRingOnSphere(
        center: FloatArray, radius: Float,
        r: Float, g: Float, b: Float, a: Float,
        t1Override: FloatArray? = null, t2Override: FloatArray? = null
    ) {
        val normal = normalize3(center)
        val (t1, t2) = if (t1Override != null && t2Override != null)
            Pair(t1Override, t2Override) else tangentFrame(normal)

        val segments = 40
        val verts = FloatArray(segments * 3)
        for (i in 0 until segments) {
            val angle = 2f * PI.toFloat() * i / segments
            verts[i*3]   = center[0] + t1[0]*cos(angle)*radius + t2[0]*sin(angle)*radius
            verts[i*3+1] = center[1] + t1[1]*cos(angle)*radius + t2[1]*sin(angle)*radius
            verts[i*3+2] = center[2] + t1[2]*cos(angle)*radius + t2[2]*sin(angle)*radius
        }
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }

        GLES20.glUseProgram(lineProgram)
        val posH   = GLES20.glGetAttribLocation(lineProgram,  "aPosition")
        val mvpH   = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorH = GLES20.glGetUniformLocation(lineProgram, "uLineColor")
        GLES20.glUniformMatrix4fv(mvpH, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorH, r, g, b, a)
        GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glEnableVertexAttribArray(posH)
        GLES20.glLineWidth(2.0f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, segments)
        GLES20.glDisableVertexAttribArray(posH)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    fun updateMarkers(list: List<Marker>) {
        markers = list
        markerPositions = list.map {
            latLonToXYZ(it.coord.lat.toFloat(), it.coord.lon.toFloat(), 1.02f)
        }
    }

    fun updateStacks(list: List<MarkerStack>) {
        stacks = list
        stackPositions = list.map {
            // Stack hit-test uses the spine BASE (where the swarm sits on the
            // ground) — easier to tap than the floating dots above.
            latLonToXYZ(it.centre.lat.toFloat(), it.centre.lon.toFloat(), 1.02f)
        }
    }

    fun updateRipples(list: List<RippleSpec>) { ripples = list }

    fun setSelectedMarker(id: String?) { selectedMarkerId = id }

    /**
     * Smoothly rotates the globe so [lat] / [lon] lands at the visible centre.
     * The actual movement runs through the same easing as drag-rotate, so the
     * camera glides rather than snaps. Picks the shortest angular path so we
     * don't spin the long way round when the user has already rotated.
     */
    fun flyTo(lat: Float, lon: Float) {
        // Match the formulas baked into latLonToXYZ:
        //   default rotationY=0 puts lon=90°E at the front, so to bring lon=L
        //   to the front we want rotationY = 90 − L.
        //   After that, rotationX=lat tilts the chosen lat line to the equator.
        val rawTargetY = 90f - lon
        val rawTargetX = lat

        // Shortest-path delta around the circle so we don't sweep 350° to
        // change by 10°.
        var deltaY = (rawTargetY - targetRotationY) % 360f
        if (deltaY > 180f)  deltaY -= 360f
        if (deltaY < -180f) deltaY += 360f

        targetRotationY += deltaY
        targetRotationX  = rawTargetX.coerceIn(-90f, 90f)

        // Pretend the user just touched, so auto-rotate doesn't immediately
        // overwrite the destination we're gliding toward.
        lastInteractionMs = System.currentTimeMillis()
    }

    fun setRotation(dx: Float, dy: Float) {
        // Scale by 1/zoom^1.5 so a 1 cm finger drag spins through roughly the
        // same *visible* arc on screen regardless of camera distance. The 1.5
        // exponent (rather than linear 1/zoom) gives noticeably more damping
        // at the high end of pinch-in where input felt twitchy.
        val s = 0.30f / Math.pow(zoom.toDouble(), 1.5).toFloat()
        targetRotationY += dx * s
        targetRotationX  = (targetRotationX + dy * s).coerceIn(-90f, 90f)
        lastInteractionMs = System.currentTimeMillis()
    }

    fun setZoom(factor: Float) {
        targetZoom = (targetZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        lastInteractionMs = System.currentTimeMillis()
    }

    fun handleTap(normalizedX: Float, normalizedY: Float): Marker? {
        lastInteractionMs = System.currentTimeMillis()
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)
        val invVP = FloatArray(16)
        Matrix.invertM(invVP, 0, vp, 0)

        val nearClip = floatArrayOf(normalizedX, normalizedY, -1f, 1f)
        val farClip  = floatArrayOf(normalizedX, normalizedY,  1f, 1f)
        val nearW    = FloatArray(4); val farW = FloatArray(4)
        Matrix.multiplyMV(nearW, 0, invVP, 0, nearClip, 0)
        Matrix.multiplyMV(farW,  0, invVP, 0, farClip,  0)

        fun dehom(v: FloatArray) { if (v[3] != 0f) { v[0]/=v[3]; v[1]/=v[3]; v[2]/=v[3] } }
        dehom(nearW); dehom(farW)

        val rayDir = normalize3(floatArrayOf(farW[0]-nearW[0], farW[1]-nearW[1], farW[2]-nearW[2]))
        val invModel = FloatArray(16)
        Matrix.invertM(invModel, 0, modelMatrix, 0)
        val ro = transformPoint(invModel, floatArrayOf(nearW[0], nearW[1], nearW[2]))
        val rd = transformDir(invModel, rayDir)

        // 1. Stacks win over individual markers — a swarm spine should always
        // take the tap over any marker sitting at its base. Slightly larger
        // hit radius (0.10 vs 0.07) since stacks visually stick up from the
        // surface and tapping near the top should still register.
        var bestStackIdx = -1; var bestStackDist = Float.MAX_VALUE
        stackPositions.forEachIndexed { i, pos ->
            val d = rayPointDistance(ro, rd, pos)
            if (d < 0.10f && d < bestStackDist) { bestStackDist = d; bestStackIdx = i }
        }
        if (bestStackIdx >= 0) {
            val stack = stacks.getOrNull(bestStackIdx)
            if (stack != null) {
                onStackTapped?.invoke(stack)
                return null
            }
        }

        // 1a. Peaks — visible-only, slightly smaller hit radius than volcanoes
        // because the markers are smaller.
        if (showPeaks && peakPositions.isNotEmpty()) {
            var bestPeak = -1; var bestPeakDist = Float.MAX_VALUE
            peakPositions.forEachIndexed { i, pos ->
                val d = rayPointDistance(ro, rd, pos)
                if (d < 0.06f && d < bestPeakDist) { bestPeakDist = d; bestPeak = i }
            }
            if (bestPeak >= 0) {
                val p = peaks.getOrNull(bestPeak)
                if (p != null) {
                    onPeakTapped?.invoke(p)
                    return null
                }
            }
        }

        // 1b. Volcanoes (only when the layer is visible). Same hit radius as
        // markers — triangles are about the same screen size.
        if (showVolcanoes && volcanoPositions.isNotEmpty()) {
            var bestVolc = -1; var bestVolcDist = Float.MAX_VALUE
            volcanoPositions.forEachIndexed { i, pos ->
                val d = rayPointDistance(ro, rd, pos)
                if (d < 0.09f && d < bestVolcDist) { bestVolcDist = d; bestVolc = i }
            }
            if (bestVolc >= 0) {
                val v = volcanoes.getOrNull(bestVolc)
                if (v != null) {
                    onVolcanoTapped?.invoke(v)
                    return null
                }
            }
        }

        // 2. Otherwise fall through to the flat marker layer.
        var bestIndex = -1; var bestDist = Float.MAX_VALUE
        markerPositions.forEachIndexed { i, pos ->
            val d = rayPointDistance(ro, rd, pos)
            if (d < 0.07f && d < bestDist) { bestDist = d; bestIndex = i }
        }

        val hit = if (bestIndex >= 0) markers.getOrNull(bestIndex) else null
        selectedMarkerId = hit?.id
        hit?.let { onMarkerTapped?.invoke(it) }
        return hit
    }

    // ════════════════════════════════════════════════════════════════════════
    // Colour helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Unpacks a 0xAARRGGBB packed integer to three 0..1 floats for GL. */
    private fun unpackArgb(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr  8) and 0xFF) / 255f
        val b = ( argb         and 0xFF) / 255f
        return Triple(r, g, b)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Math helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Lat/lon → 3D position on a sphere, with axes:
     *   +Y = north pole, +Z = lon 90°E equator (camera-facing default),
     *   +X = lon 180° equator (screen-right when centred on lon 90°E).
     * The −cos(lon) on X makes increasing longitude move EASTWARD (rightward
     * when viewing from outside, with north up).
     */
    private fun latLonToXYZ(lat: Float, lon: Float, radius: Float = 1.02f): FloatArray {
        val latR = Math.toRadians(lat.toDouble()).toFloat()
        val lonR = Math.toRadians(lon.toDouble()).toFloat()
        return floatArrayOf(
            -radius * cos(latR) * cos(lonR),
            radius * sin(latR),
            radius * cos(latR) * sin(lonR)
        )
    }

    private fun transposeMatrix(m: FloatArray) {
        val t = m.copyOf()
        for (i in 0..3) for (j in 0..3) m[i*4+j] = t[j*4+i]
    }

    private fun normalize3(v: FloatArray): FloatArray {
        val len = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        return if (len > 0.0001f) floatArrayOf(v[0]/len, v[1]/len, v[2]/len)
        else floatArrayOf(0f, 0f, 1f)
    }

    private fun transformPoint(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0]*p[0]+m[4]*p[1]+m[8]*p[2]+m[12],
        m[1]*p[0]+m[5]*p[1]+m[9]*p[2]+m[13],
        m[2]*p[0]+m[6]*p[1]+m[10]*p[2]+m[14]
    )

    private fun transformDir(m: FloatArray, d: FloatArray) = floatArrayOf(
        m[0]*d[0]+m[4]*d[1]+m[8]*d[2],
        m[1]*d[0]+m[5]*d[1]+m[9]*d[2],
        m[2]*d[0]+m[6]*d[1]+m[10]*d[2]
    )

    private fun rayPointDistance(origin: FloatArray, dir: FloatArray, point: FloatArray): Float {
        val w = floatArrayOf(point[0]-origin[0], point[1]-origin[1], point[2]-origin[2])
        val t = w[0]*dir[0]+w[1]*dir[1]+w[2]*dir[2]
        val proj = floatArrayOf(origin[0]+t*dir[0], origin[1]+t*dir[1], origin[2]+t*dir[2])
        val dx=point[0]-proj[0]; val dy=point[1]-proj[1]; val dz=point[2]-proj[2]
        return sqrt(dx*dx+dy*dy+dz*dz)
    }

    /**
     * Camera-relative diffuse light — fixed in world space behind the camera
     * so the visible hemisphere is always lit no matter how the user rotates.
     * Slightly above (+Y) so there's a faint top-down highlight.
     */
    private fun computeViewLightDirection(): FloatArray {
        val len = sqrt(0.35f * 0.35f + 1f)
        return floatArrayOf(0f, 0.35f / len, 1f / len)
    }

    /**
     * Real subsolar direction in world space, computed from current UTC.
     * Drives the *night-darkening overlay* only — not the diffuse — so the
     * visible side stays readable even on the UTC night hemisphere, but you
     * can still see where the terminator actually is on Earth right now.
     */
    private fun computeUtcSunDirection(): FloatArray {
        val cal  = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val utcH = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0
        val doy  = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val sunLon = Math.toRadians((12.0 - utcH) * 15.0)
        val sunLat = Math.toRadians(-23.45 * cos(2.0 * Math.PI * (doy + 10) / 365.25))
        // Same axis convention as latLonToXYZ (negated X) so subsolar lat/lon
        // lines up with the corresponding model-space point.
        return floatArrayOf(
            (-cos(sunLat) * cos(sunLon)).toFloat(),
            sin(sunLat).toFloat(),
            (cos(sunLat) * sin(sunLon)).toFloat()
        )
    }

    /** Returns an orthonormal tangent frame (t1, t2) perpendicular to [normal]. */
    private fun tangentFrame(normal: FloatArray): Pair<FloatArray, FloatArray> {
        val ref = if (abs(normal[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        val t1  = normalize3(crossProduct(ref, normal))
        val t2  = normalize3(crossProduct(normal, t1))
        return Pair(t1, t2)
    }

    private fun crossProduct(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1]*b[2] - a[2]*b[1],
        a[2]*b[0] - a[0]*b[2],
        a[0]*b[1] - a[1]*b[0]
    )

    // ════════════════════════════════════════════════════════════════════════
    // Shader compilation
    // ════════════════════════════════════════════════════════════════════════

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER,   vertSrc)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v); GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(v);     GLES20.glDeleteShader(f)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
}
