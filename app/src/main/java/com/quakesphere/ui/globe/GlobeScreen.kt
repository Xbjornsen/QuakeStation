package com.quakesphere.ui.globe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.quakesphere.domain.model.DepthCategory
import com.quakesphere.domain.model.Earthquake
import com.quakesphere.globe.GlobeView
import com.quakesphere.ui.theme.DepthDeep
import com.quakesphere.ui.theme.DepthIntermediate
import com.quakesphere.ui.theme.DepthShallow
import com.quakesphere.ui.theme.ElectricBlue
import com.quakesphere.ui.theme.MagGreat
import com.quakesphere.ui.theme.MagMajor
import com.quakesphere.ui.theme.MagMinor
import com.quakesphere.ui.theme.MagModerate
import com.quakesphere.ui.theme.MagStrong
import com.quakesphere.ui.theme.SurfaceCard
import com.quakesphere.ui.theme.SurfaceVariant
import com.quakesphere.ui.theme.TextPrimary
import com.quakesphere.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobeScreen(
    onNavigateToList: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: GlobeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    // We hold onto the GlobeView so the "Latest" pill (and any other
    // imperative UI affordance) can call flyTo on it directly.
    var globeViewRef by remember { mutableStateOf<GlobeView?>(null) }

    // Replay: each time the index advances, fly the camera to that quake.
    LaunchedEffect(uiState.replay.isActive, uiState.replay.index) {
        if (!uiState.replay.isActive) return@LaunchedEffect
        val chrono = uiState.earthquakes.sortedBy { it.time }
        val q = chrono.getOrNull(uiState.replay.index) ?: return@LaunchedEffect
        globeViewRef?.flyTo(com.quakesphere.globe.GeoCoord(q.lat, q.lon))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Full-screen Globe — consumes the :globe library's public API only.
        AndroidView(
            factory = { context ->
                GlobeView(context).apply {
                    onMarkerClick  = { marker -> viewModel.selectEarthquakeById(marker.id) }
                    onStackClick   = { stack  -> viewModel.selectSwarm(stack.id) }
                    onVolcanoClick = { v ->
                        // Phone-friendly toast: name + country + summit elev. Tapping a
                        // volcano shouldn't fight the earthquake selection UI, so we
                        // just surface a snackbar — full bottom card can land later
                        // alongside the USGS live-alerts pass.
                        val elev = if (v.elevM > 0) " · ${v.elevM} m" else if (v.elevM < 0) " · submarine" else ""
                        viewModel.showTransientMessage("🌋 ${v.name} (${v.country})$elev")
                    }
                    globeViewRef = this
                }
            },
            update = { view ->
                val swarmIds = uiState.swarms
                    .flatMap { s -> s.events.map { it.id } }
                    .toSet()
                val colorByMag = uiState.displaySettings.markerColorByMagnitude

                // During replay we feed the renderer only the quakes that have
                // "happened" so far in the time-lapse — cumulative oldest-first
                // reveal. Swarms and ripples follow the same trimmed list so
                // the geographic story builds up coherently.
                val replay = uiState.replay
                val chronological = uiState.earthquakes.sortedBy { it.time }
                val activeQuakes = if (replay.isActive) {
                    chronological.take((replay.index + 1).coerceAtMost(chronological.size))
                } else uiState.earthquakes

                view.setMarkers(EarthquakeMapper.toMarkers(
                    earthquakes = activeQuakes,
                    swarmEventIds = swarmIds,
                    colorByMagnitude = colorByMag
                ))
                view.setStacks(EarthquakeMapper.toStacks(
                    if (replay.isActive) emptyList() else uiState.swarms, colorByMag
                ))
                view.setRipples(EarthquakeMapper.toRipples(activeQuakes))
                view.setSelectedMarker(uiState.selectedEarthquake?.id)

                view.displaySettings = com.quakesphere.globe.GlobeDisplaySettings(
                    showContinentLines = uiState.displaySettings.showContinentLines,
                    showStars          = uiState.displaySettings.showStars,
                    // Auto-rotate forced off during replay — it would fight
                    // every flyTo() and confuse the time-lapse.
                    autoRotate         = uiState.displaySettings.autoRotate && !replay.isActive,
                    showTectonicPlates = uiState.displaySettings.showTectonicPlates,
                    showHistoricTrends = uiState.displaySettings.showHistoricTrends,
                    showEquator        = uiState.displaySettings.showEquator,
                    showVolcanoes      = uiState.displaySettings.showVolcanoes
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top overlay column (header + highlight pill) ─────────────────────
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {

            // ── App header (status-bar-aware) ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()                          // ← fixes OnePlus 15 cut-off
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Title block
                Column {
                    Text(
                        text = "QuakeStation",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.earthquakes.size} quakes",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        if (uiState.swarms.isNotEmpty()) {
                            Text(text = "·", color = TextSecondary, fontSize = 12.sp)
                            Text(
                                text = "${uiState.swarms.size} swarm${if (uiState.swarms.size > 1) "s" else ""}",
                                color = Color(0xFFFFBB33),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier  = Modifier.size(20.dp),
                            color     = ElectricBlue,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // Play / Stop replay
                    IconButton(onClick = {
                        if (uiState.replay.isActive) viewModel.stopReplay()
                        else viewModel.startReplay()
                    }) {
                        Icon(
                            imageVector = if (uiState.replay.isActive)
                                Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.replay.isActive) "Stop replay" else "Replay quakes",
                            tint = if (uiState.replay.isActive) MagStrong else ElectricBlue
                        )
                    }
                    IconButton(onClick = { viewModel.syncEarthquakes() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ElectricBlue)
                    }
                    BadgedBox(badge = {
                        if (uiState.earthquakes.isNotEmpty()) {
                            Badge(containerColor = MagStrong) {
                                Text(
                                    text  = uiState.earthquakes.size.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }) {
                        IconButton(onClick = onNavigateToList) {
                            Icon(Icons.Default.List, contentDescription = "List", tint = TextPrimary)
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                    }
                }
            }

            // ── Highlight pill (alternates LATEST ↔ BIGGEST) ───────────────
            // Same physical pill swaps its mode every 6 seconds so the user
            // sees both the most recent quake and the most significant one
            // in the current filter window without any extra UI noise.
            val latest  = uiState.earthquakes.maxByOrNull { it.time }
            val biggest = uiState.earthquakes.maxByOrNull { it.mag }
            // Only bother alternating if the two are different — a single quake
            // is both "latest" and "biggest" simultaneously.
            val showAlternating = latest != null && biggest != null && latest.id != biggest.id
            var showBiggest by remember { mutableStateOf(false) }
            LaunchedEffect(showAlternating) {
                if (!showAlternating) { showBiggest = false; return@LaunchedEffect }
                while (true) {
                    kotlinx.coroutines.delay(6_000L)
                    showBiggest = !showBiggest
                }
            }
            val highlight = when {
                showBiggest && biggest != null -> HighlightQuake(biggest, HighlightKind.BIGGEST)
                latest  != null               -> HighlightQuake(latest,  HighlightKind.LATEST)
                biggest != null               -> HighlightQuake(biggest, HighlightKind.BIGGEST)
                else                          -> null
            }
            highlight?.let { hl ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    HighlightQuakePill(
                        earthquake = hl.quake,
                        kind       = hl.kind,
                        onClick    = {
                            globeViewRef?.flyTo(
                                com.quakesphere.globe.GeoCoord(hl.quake.lat, hl.quake.lon)
                            )
                            viewModel.selectEarthquakeById(hl.quake.id)
                        }
                    )
                }
            }

            // ── North indicator (stays just under the header pill) ─────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                PoleLabel("N", Color(0xFFCCE8FF))
            }
        }

        // ── South indicator (anchored to the bottom independently of any
        //    bottom-sheet card, so opening the detail/swarm popup does not
        //    push it up into the middle of the globe). ────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            PoleLabel("S", Color(0xFFAAD4FF))
        }

        // ── Legend (anchored bottom-start, independent of bottom sheets) ──
        MagnitudeLegend(
            colorByMagnitude = uiState.displaySettings.markerColorByMagnitude,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 40.dp)
        )

        // ── Selected earthquake bottom sheet ─────────────────────────────────
        AnimatedVisibility(
            visible = uiState.selectedEarthquake != null,
            enter   = slideInVertically(initialOffsetY = { it }),
            exit    = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            uiState.selectedEarthquake?.let { quake ->
                SelectedEarthquakeCard(
                    earthquake  = quake,
                    useMiles    = uiState.displaySettings.useMiles,
                    onViewDetails = { onNavigateToDetail(quake.id) },
                    onDismiss   = { viewModel.clearSelection() },
                    modifier    = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // ── Selected swarm popup ─────────────────────────────────────────────
        val selectedSwarm = uiState.swarms.firstOrNull { it.id == uiState.selectedSwarmId }
        AnimatedVisibility(
            visible = selectedSwarm != null,
            enter   = slideInVertically(initialOffsetY = { it }),
            exit    = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedSwarm?.let { sw ->
                SwarmInfoCard(
                    swarm    = sw,
                    onEventClick = { id -> viewModel.selectSwarm(null); viewModel.selectEarthquakeById(id) },
                    onDismiss = { viewModel.selectSwarm(null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // ── Replay progress strip ────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.replay.isActive,
            enter   = slideInVertically(initialOffsetY = { it }),
            exit    = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReplayStrip(
                replay        = uiState.replay,
                currentQuake  = uiState.earthquakes.sortedBy { it.time }
                    .getOrNull(uiState.replay.index),
                onPauseToggle = { viewModel.togglePauseReplay() },
                onStop        = { viewModel.stopReplay() },
                modifier      = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun ReplayStrip(
    replay: ReplayState,
    currentQuake: Earthquake?,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier  = modifier.fillMaxWidth(),
        colors    = androidx.compose.material3.CardDefaults.cardColors(containerColor = SurfaceCard),
        shape     = RoundedCornerShape(16.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = if (replay.isPaused) "PAUSED" else "REPLAYING",
                    color      = if (replay.isPaused) Color(0xFFFFBB33) else ElectricBlue,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${replay.index + 1} / ${replay.total}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPauseToggle) {
                    Icon(
                        imageVector = if (replay.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (replay.isPaused) "Resume" else "Pause",
                        tint = ElectricBlue
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop replay", tint = MagStrong)
                }
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (replay.index + 1).toFloat() / replay.total.coerceAtLeast(1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = ElectricBlue,
                trackColor = SurfaceVariant
            )
            currentQuake?.let { q ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "M${String.format("%.1f", q.mag)}",
                        color = magnitudeColor(q.mag),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text     = q.place,
                        color    = TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text     = formatTimeAgo(q.time),
                        color    = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// ── Legend ─────────────────────────────────────────────────────────────────

@Composable
fun MagnitudeLegend(
    colorByMagnitude: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.clickable { expanded = !expanded },
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.70f)),
        shape    = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (colorByMagnitude) "MAGNITUDE" else "DEPTH",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse legend" else "Expand legend",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    if (colorByMagnitude) {
                        LegendItem(color = MagMinor,    label = "< M5   Minor")
                        LegendItem(color = MagModerate, label = "M5-6  Moderate")
                        LegendItem(color = MagStrong,   label = "M6-7  Strong")
                        LegendItem(color = MagMajor,    label = "M7-8  Major")
                        LegendItem(color = MagGreat,    label = "M8+   Mega")
                    } else {
                        LegendItem(color = DepthShallow,      label = "Shallow  <70 km")
                        LegendItem(color = DepthIntermediate, label = "Mid  70-300 km")
                        LegendItem(color = DepthDeep,         label = "Deep  >300 km")
                    }
                }
            }
        }
    }
}

@Composable
fun SwarmInfoCard(
    swarm: com.quakesphere.domain.model.EarthquakeSwarm,
    onEventClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier  = modifier.fillMaxWidth(),
        colors    = androidx.compose.material3.CardDefaults.cardColors(containerColor = SurfaceCard),
        shape     = RoundedCornerShape(16.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SWARM",
                        color = Color(0xFFFFBB33),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text       = swarm.location,
                        color      = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        maxLines   = 2
                    )
                    Text(
                        text     = "${swarm.eventCount} events · ${swarm.durationHours}h duration · " +
                                   "started ${formatTimeAgo(swarm.startTime)}",
                        color    = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text     = "✕",
                    color    = TextSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            // Sort toggle: magnitude (default) ↔ time.
            var sortByTime by remember(swarm.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sort:",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(8.dp))
                SortChip(
                    label = "Magnitude",
                    icon = Icons.Default.TrendingUp,
                    selected = !sortByTime,
                    onClick = { sortByTime = false }
                )
                Spacer(Modifier.width(6.dp))
                SortChip(
                    label = "Time",
                    icon = Icons.Default.AccessTime,
                    selected = sortByTime,
                    onClick = { sortByTime = true }
                )
            }
            val orderedEvents = if (sortByTime)
                swarm.events.sortedByDescending { it.time }
            else
                swarm.events.sortedByDescending { it.mag }
            // Mini-table: tap a row to focus that quake. Scrollable so big
            // swarms (30+ events) don't truncate at an arbitrary cap; we
            // bound the height so the card never eats the whole screen.
            val listScroll = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(listScroll)
            ) {
            orderedEvents.forEach { event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEventClick(event.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(magnitudeColor(event.mag)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text       = String.format("%.1f", event.mag),
                            color      = magnitudeTextColor(event.mag),
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text       = event.place,
                        color      = TextPrimary,
                        fontSize   = 12.sp,
                        modifier   = Modifier.weight(1f),
                        maxLines   = 1
                    )
                    Text(
                        text     = formatTimeAgo(event.time),
                        color    = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            }  // end scrollable Column
        }
    }
}

enum class HighlightKind(val label: String, val tint: Color, val dotColor: Color) {
    LATEST ("LATEST",  Color(0xFFAAD4FF), Color.White),
    BIGGEST("BIGGEST", Color(0xFFFFC04D), Color(0xFFFFE082))
}

private data class HighlightQuake(val quake: Earthquake, val kind: HighlightKind)

@Composable
fun HighlightQuakePill(
    earthquake: Earthquake,
    kind: HighlightKind,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(kind.dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = kind.label,
            color = kind.tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "M${String.format("%.1f", earthquake.mag)}",
            color = magnitudeColor(earthquake.mag),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = earthquake.place,
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 180.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "· ${formatTimeAgo(earthquake.time)}",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun PoleLabel(label: String, tint: Color) {
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.50f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = tint,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun SortChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) ElectricBlue.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f)
    val fg = if (selected) ElectricBlue else TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = TextPrimary, fontSize = 11.sp)
    }
}

// ── Selected earthquake card ────────────────────────────────────────────────

@Composable
fun SelectedEarthquakeCard(
    earthquake: Earthquake,
    useMiles: Boolean,
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MagnitudeBadgeLarge(magnitude = earthquake.mag)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text         = earthquake.place,
                            color        = TextPrimary,
                            fontWeight   = FontWeight.SemiBold,
                            fontSize     = 15.sp
                        )
                        Text(
                            text     = formatTimeAgo(earthquake.time),
                            color    = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                Text(
                    text     = "✕",
                    color    = TextSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoChip(
                    label = "Depth",
                    value = formatDepth(earthquake.depth, useMiles),
                    color = when (earthquake.depthCategory) {
                        DepthCategory.SHALLOW      -> DepthShallow
                        DepthCategory.INTERMEDIATE -> DepthIntermediate
                        DepthCategory.DEEP         -> DepthDeep
                    }
                )
                InfoChip(
                    label = "Coords",
                    value = "${String.format("%.1f", earthquake.lat)}°, ${String.format("%.1f", earthquake.lon)}°",
                    color = ElectricBlue
                )
                if (earthquake.tsunami == 1) {
                    InfoChip(label = "Tsunami", value = "WARNING", color = MagGreat)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick  = onViewDetails,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                Text("View Full Details", color = Color.White)
            }
        }
    }
}

@Composable
fun MagnitudeBadgeLarge(magnitude: Double) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(magnitudeColor(magnitude)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = String.format("%.1f", magnitude),
            color      = magnitudeTextColor(magnitude),
            fontWeight = FontWeight.Bold,
            fontSize   = 16.sp
        )
    }
}

@Composable
fun InfoChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextSecondary, fontSize = 10.sp)
        Text(text = value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Label projection ─────────────────────────────────────────────────────────

/**
 * Projects a lat/lon point on the globe into screen-pixel coordinates.
 * Returns null if the point is behind the camera or off-screen.
 */
fun projectToScreen(
    lat: Float, lon: Float,
    mvp: FloatArray,
    screenW: Float, screenH: Float
): Pair<Float, Float>? {
    val latR = Math.toRadians(lat.toDouble()).toFloat()
    val lonR = Math.toRadians(lon.toDouble()).toFloat()
    val r = 1.05f  // slightly above surface so labels don't clip into the globe
    val x = r * kotlin.math.cos(latR) * kotlin.math.cos(lonR)
    val y = r * kotlin.math.sin(latR)
    val z = r * kotlin.math.cos(latR) * kotlin.math.sin(lonR)

    // Clip space: mvp * vec4(x,y,z,1)
    val cx = mvp[0]*x + mvp[4]*y + mvp[8]*z  + mvp[12]
    val cy = mvp[1]*x + mvp[5]*y + mvp[9]*z  + mvp[13]
    val cw = mvp[3]*x + mvp[7]*y + mvp[11]*z + mvp[15]
    if (cw <= 0.01f) return null   // behind camera

    val ndcX =  cx / cw
    val ndcY =  cy / cw
    if (ndcX < -0.92f || ndcX > 0.92f || ndcY < -0.92f || ndcY > 0.92f) return null

    val sx = (ndcX + 1f) / 2f * screenW
    val sy = (1f - ndcY) / 2f * screenH
    return Pair(sx, sy)
}

// ── Shared utilities ────────────────────────────────────────────────────────

fun magnitudeColor(mag: Double): Color = when {
    mag < 5.0 -> MagMinor
    mag < 6.0 -> MagModerate
    mag < 7.0 -> MagStrong
    mag < 8.0 -> MagMajor
    else       -> MagGreat
}

/**
 * Readable text colour for a filled magnitude badge.
 * Green (minor) and yellow (moderate) backgrounds are light → need dark text;
 * orange/red backgrounds are dark enough for white text.
 */
fun magnitudeTextColor(mag: Double): Color =
    if (mag < 6.0) Color(0xFF10151F) else Color.White

/** Formats a depth (always stored in km) honouring the user's distance-unit setting. */
fun formatDepth(depthKm: Double, useMiles: Boolean): String =
    if (useMiles) "${(depthKm * 0.621371).toInt()} mi" else "${depthKm.toInt()} km"

fun formatTimeAgo(timestamp: Long): String {
    val diff    = System.currentTimeMillis() - timestamp
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours   = TimeUnit.MILLISECONDS.toHours(diff)
    val days    = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 60 -> "${minutes}m ago"
        hours   < 24 -> "${hours}h ago"
        days    <  7 -> "${days}d ago"
        else         -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}
