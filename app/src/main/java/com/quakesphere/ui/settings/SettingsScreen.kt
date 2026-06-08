package com.quakesphere.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.quakesphere.ui.theme.ElectricBlue
import com.quakesphere.ui.theme.MagStrong
import com.quakesphere.ui.theme.SpaceBlack
import com.quakesphere.ui.theme.SurfaceCard
import com.quakesphere.ui.theme.SurfaceVariant
import com.quakesphere.ui.theme.TextPrimary
import com.quakesphere.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpaceBlack)
            )
        },
        containerColor = SpaceBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── DATA FILTERS ─────────────────────────────────────────────────
            SettingsSection("Data Filters") {
                // Minimum magnitude
                SettingRow(
                    title    = "Minimum Magnitude",
                    subtitle = "Show earthquakes at or above this level",
                    trailing = { Text("M ${String.format("%.1f", s.minMagnitude)}",
                        color = ElectricBlue, fontWeight = FontWeight.Bold) }
                )
                Slider(
                    value         = s.minMagnitude,
                    onValueChange = { viewModel.setMinMagnitude(it) },
                    valueRange    = 2.5f..8.0f,
                    steps         = 10,
                    colors        = SliderDefaults.colors(
                        thumbColor        = ElectricBlue,
                        activeTrackColor  = ElectricBlue,
                        inactiveTrackColor = SurfaceVariant
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("M 2.5", color = TextSecondary, fontSize = 11.sp)
                    Text("M 8.0", color = TextSecondary, fontSize = 11.sp)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = SurfaceVariant)
                Spacer(Modifier.height(8.dp))

                // Time range
                Text("Time Range", color = TextPrimary, fontSize = 15.sp)
                Text("Fetch earthquakes from the past…", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    TimeRange.values().forEach { tr ->
                        SettingsChip(
                            label    = tr.label,
                            selected = s.timeRange == tr,
                            onClick  = { viewModel.setTimeRange(tr) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = SurfaceVariant)
                Spacer(Modifier.height(8.dp))

                // Depth filter
                Text("Depth Filter", color = TextPrimary, fontSize = 15.sp)
                Text("Limit by earthquake focal depth", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    DepthFilter.values().forEach { df ->
                        SettingsChip(
                            label    = df.label,
                            selected = s.depthFilter == df,
                            onClick  = { viewModel.setDepthFilter(df) }
                        )
                    }
                }
            }

            // ── GLOBE DISPLAY ────────────────────────────────────────────────
            SettingsSection("Globe Display") {
                ToggleRow(
                    title    = "Continent Lines",
                    subtitle = "Overlay simplified coastlines on the globe",
                    checked  = s.showContinentLines,
                    onCheckedChange = { viewModel.setShowContinentLines(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                ToggleRow(
                    title    = "Starfield",
                    subtitle = "Show the star background behind the globe",
                    checked  = s.showStars,
                    onCheckedChange = { viewModel.setShowStars(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                ToggleRow(
                    title    = "Auto-Rotate",
                    subtitle = "Slowly spin the globe automatically",
                    checked  = s.autoRotate,
                    onCheckedChange = { viewModel.setAutoRotate(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                ToggleRow(
                    title    = "Tectonic Plates",
                    subtitle = "Overlay PB2002 plate boundaries (Bird 2003)",
                    checked  = s.showTectonicPlates,
                    onCheckedChange = { viewModel.setShowTectonicPlates(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                ToggleRow(
                    title    = "Historic Trends (beta)",
                    subtitle = "Density of significant quakes over the last 30 years",
                    checked  = s.showHistoricTrends,
                    onCheckedChange = { viewModel.setShowHistoricTrends(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                ToggleRow(
                    title    = "Equator Line",
                    subtitle = "Draw a thin reference circle at latitude 0",
                    checked  = s.showEquator,
                    onCheckedChange = { viewModel.setShowEquator(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                ToggleRow(
                    title    = "Active Volcanoes",
                    subtitle = "Holocene volcanoes from the Smithsonian GVP list",
                    checked  = s.showVolcanoes,
                    onCheckedChange = { viewModel.setShowVolcanoes(it) }
                )
                HorizontalDivider(color = SurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("Marker Colour Mode", color = TextPrimary, fontSize = 15.sp)
                Text("What property drives dot colour", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    MarkerColorMode.values().forEach { mode ->
                        SettingsChip(
                            label    = mode.label,
                            selected = s.markerColorMode == mode,
                            onClick  = { viewModel.setMarkerColorMode(mode) }
                        )
                    }
                }
            }

            // ── NOTIFICATIONS ────────────────────────────────────────────────
            SettingsSection("Notifications") {
                ToggleRow(
                    title    = "Enable Notifications",
                    subtitle = "Get alerted when major quakes occur",
                    checked  = s.notificationsEnabled,
                    onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                )
                if (s.notificationsEnabled) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = SurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SettingRow(
                        title    = "Alert Threshold",
                        subtitle = "Notify when magnitude exceeds this value",
                        trailing = {
                            Text("M ${String.format("%.1f", s.notificationThreshold)}",
                                color = MagStrong, fontWeight = FontWeight.Bold)
                        }
                    )
                    Slider(
                        value         = s.notificationThreshold,
                        onValueChange = { viewModel.setNotificationThreshold(it) },
                        valueRange    = 5.0f..8.0f,
                        steps         = 5,
                        colors        = SliderDefaults.colors(
                            thumbColor        = MagStrong,
                            activeTrackColor  = MagStrong,
                            inactiveTrackColor = SurfaceVariant
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("M 5.0", color = TextSecondary, fontSize = 11.sp)
                        Text("M 8.0", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }

            // ── SWARMS ───────────────────────────────────────────────────────
            SettingsSection("Swarm Detection") {
                Text("Minimum Events", color = TextPrimary, fontSize = 15.sp)
                Text("How many quakes in a cluster to flag as a swarm",
                    color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    listOf(3, 5, 10).forEach { n ->
                        SettingsChip(
                            label    = "$n events",
                            selected = s.swarmMinEvents == n,
                            onClick  = { viewModel.setSwarmMinEvents(n) }
                        )
                    }
                }
            }

            // ── UNITS ────────────────────────────────────────────────────────
            SettingsSection("Units") {
                Text("Distance", color = TextPrimary, fontSize = 15.sp)
                Text("Used for depth values throughout the app",
                    color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    DistanceUnit.values().forEach { u ->
                        SettingsChip(
                            label    = u.label,
                            selected = s.distanceUnit == u,
                            onClick  = { viewModel.setDistanceUnit(u) }
                        )
                    }
                }
            }

            // ── SYNC ─────────────────────────────────────────────────────────
            SettingsSection("Background Sync") {
                Text("How often to check for new earthquakes",
                    color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ChipRow {
                    SyncInterval.values().forEach { interval ->
                        SettingsChip(
                            label    = interval.label,
                            selected = s.syncInterval == interval,
                            onClick  = { viewModel.setSyncInterval(interval) }
                        )
                    }
                }
            }

            // ── Data source info ─────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                shape  = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("DATA SOURCE", color = ElectricBlue, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "QuakeStation uses live data from the USGS Earthquake Hazards Program. " +
                        "Data is updated in near real-time and covers global seismic events.",
                        color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text         = title.uppercase(),
                color        = ElectricBlue,
                fontSize     = 11.sp,
                fontWeight   = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title,    color = TextPrimary,   fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = ElectricBlue,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceVariant
            )
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title,    color = TextPrimary,   fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun SettingsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = {
            Text(
                text     = label,
                color    = if (selected) Color.White else TextSecondary,
                fontSize = 13.sp
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ElectricBlue,
            containerColor         = SurfaceVariant
        )
    )
}
