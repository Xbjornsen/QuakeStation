package com.quakesphere.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quakesphere.work.EarthquakeSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Enums ────────────────────────────────────────────────────────────────────

enum class SyncInterval(val label: String, val minutes: Int) {
    FIFTEEN("15 min", 15),
    THIRTY("30 min", 30),
    SIXTY("1 hr", 60)
}

enum class TimeRange(val label: String, val hours: Int) {
    HOUR_1("1h",  1),
    HOUR_6("6h",  6),
    DAY_1("24h",  24),
    DAYS_7("7d",  168),
    DAYS_30("30d", 720)
}

enum class DepthFilter(val label: String) {
    ALL("All"), SHALLOW("Shallow"), INTERMEDIATE("Mid"), DEEP("Deep");

    /** True if an earthquake at [depthKm] passes this depth filter. */
    fun matches(depthKm: Double): Boolean = when (this) {
        ALL          -> true
        SHALLOW      -> depthKm < 70.0
        INTERMEDIATE -> depthKm in 70.0..300.0
        DEEP         -> depthKm > 300.0
    }
}

enum class MarkerColorMode(val label: String, val key: String) {
    DEPTH("Depth", "depth"),
    MAGNITUDE("Magnitude", "magnitude")
}

enum class DistanceUnit(val label: String, val key: String) {
    KM("km", "km"),
    MILES("miles", "miles")
}

// ── UI State ─────────────────────────────────────────────────────────────────

data class SettingsUiState(
    // Filter
    val minMagnitude: Float      = 5.0f,
    val timeRange: TimeRange     = TimeRange.DAYS_7,
    val depthFilter: DepthFilter = DepthFilter.ALL,
    // Notifications
    val notificationsEnabled: Boolean = true,
    val notificationThreshold: Float  = 6.0f,
    // Globe display
    val showContinentLines:     Boolean         = true,
    val showStars:              Boolean         = true,
    val autoRotate:             Boolean         = false,
    val showTectonicPlates:     Boolean         = false,
    val showHistoricTrends:     Boolean         = false,
    val showEquator:            Boolean         = false,
    val showVolcanoes:          Boolean         = false,
    val showTopography:         Boolean         = false,
    val markerColorMode:        MarkerColorMode = MarkerColorMode.DEPTH,
    // Sync
    val syncInterval: SyncInterval = SyncInterval.THIRTY,
    // Swarms
    val swarmMinEvents: Int = 3,
    // Units
    val distanceUnit: DistanceUnit = DistanceUnit.KM
)

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        val KEY_MIN_MAG              = floatPreferencesKey("min_magnitude")
        val KEY_TIME_RANGE           = stringPreferencesKey("time_range")
        val KEY_DEPTH_FILTER         = stringPreferencesKey("depth_filter")
        val KEY_NOTIF_ENABLED        = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIF_THRESHOLD      = floatPreferencesKey("notification_threshold")
        val KEY_SHOW_CONTINENT_LINES = booleanPreferencesKey("show_continent_lines")
        val KEY_SHOW_STARS           = booleanPreferencesKey("show_stars")
        val KEY_AUTO_ROTATE          = booleanPreferencesKey("auto_rotate")
        val KEY_SHOW_TECTONIC_PLATES = booleanPreferencesKey("show_tectonic_plates")
        val KEY_SHOW_HISTORIC_TRENDS = booleanPreferencesKey("show_historic_trends")
        val KEY_SHOW_EQUATOR         = booleanPreferencesKey("show_equator")
        val KEY_SHOW_VOLCANOES       = booleanPreferencesKey("show_volcanoes")
        val KEY_SHOW_TOPOGRAPHY      = booleanPreferencesKey("show_topography")
        val KEY_MARKER_COLOR_MODE    = stringPreferencesKey("marker_color_mode")
        val KEY_SYNC_INTERVAL        = intPreferencesKey("sync_interval_minutes")
        val KEY_SWARM_MIN_EVENTS     = intPreferencesKey("swarm_min_events")
        val KEY_DISTANCE_UNIT        = stringPreferencesKey("distance_unit")
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .map { p ->
                    SettingsUiState(
                        minMagnitude = p[KEY_MIN_MAG] ?: 5.0f,
                        timeRange    = TimeRange.values().firstOrNull {
                            it.name == (p[KEY_TIME_RANGE] ?: "DAYS_7")
                        } ?: TimeRange.DAYS_7,
                        depthFilter  = DepthFilter.values().firstOrNull {
                            it.name == (p[KEY_DEPTH_FILTER] ?: "ALL")
                        } ?: DepthFilter.ALL,
                        notificationsEnabled   = p[KEY_NOTIF_ENABLED]   ?: true,
                        notificationThreshold  = p[KEY_NOTIF_THRESHOLD] ?: 6.0f,
                        showContinentLines     = p[KEY_SHOW_CONTINENT_LINES] ?: true,
                        showStars              = p[KEY_SHOW_STARS]           ?: true,
                        autoRotate             = p[KEY_AUTO_ROTATE]          ?: false,
                        showTectonicPlates     = p[KEY_SHOW_TECTONIC_PLATES] ?: false,
                        showHistoricTrends     = p[KEY_SHOW_HISTORIC_TRENDS] ?: false,
                        showEquator            = p[KEY_SHOW_EQUATOR]         ?: false,
                        showVolcanoes          = p[KEY_SHOW_VOLCANOES]       ?: false,
                        showTopography         = p[KEY_SHOW_TOPOGRAPHY]      ?: false,
                        markerColorMode        = MarkerColorMode.values().firstOrNull {
                            it.key == (p[KEY_MARKER_COLOR_MODE] ?: "depth")
                        } ?: MarkerColorMode.DEPTH,
                        syncInterval = when (p[KEY_SYNC_INTERVAL] ?: 30) {
                            15   -> SyncInterval.FIFTEEN
                            60   -> SyncInterval.SIXTY
                            else -> SyncInterval.THIRTY
                        },
                        swarmMinEvents = p[KEY_SWARM_MIN_EVENTS] ?: 3,
                        distanceUnit   = DistanceUnit.values().firstOrNull {
                            it.key == (p[KEY_DISTANCE_UNIT] ?: "km")
                        } ?: DistanceUnit.KM
                    )
                }
                .collect { _uiState.value = it }
        }
    }

    fun setMinMagnitude(v: Float)          = save { it[KEY_MIN_MAG] = v }
    fun setTimeRange(v: TimeRange)         = save { it[KEY_TIME_RANGE] = v.name }
    fun setDepthFilter(v: DepthFilter)     = save { it[KEY_DEPTH_FILTER] = v.name }
    fun setNotificationsEnabled(v: Boolean)= save { it[KEY_NOTIF_ENABLED] = v }
    fun setNotificationThreshold(v: Float) = save { it[KEY_NOTIF_THRESHOLD] = v }
    fun setShowContinentLines(v: Boolean)  = save { it[KEY_SHOW_CONTINENT_LINES] = v }
    fun setShowStars(v: Boolean)           = save { it[KEY_SHOW_STARS] = v }
    fun setAutoRotate(v: Boolean)          = save { it[KEY_AUTO_ROTATE] = v }
    fun setShowTectonicPlates(v: Boolean)  = save { it[KEY_SHOW_TECTONIC_PLATES] = v }
    fun setShowHistoricTrends(v: Boolean)  = save { it[KEY_SHOW_HISTORIC_TRENDS] = v }
    fun setShowEquator(v: Boolean)         = save { it[KEY_SHOW_EQUATOR] = v }
    fun setShowVolcanoes(v: Boolean)       = save { it[KEY_SHOW_VOLCANOES] = v }
    fun setShowTopography(v: Boolean)      = save { it[KEY_SHOW_TOPOGRAPHY] = v }
    fun setMarkerColorMode(v: MarkerColorMode) = save { it[KEY_MARKER_COLOR_MODE] = v.key }
    fun setSyncInterval(v: SyncInterval) {
        save { it[KEY_SYNC_INTERVAL] = v.minutes }
        // Reschedule the periodic background sync to honour the new interval
        EarthquakeSyncWorker.schedule(context, v.minutes.toLong())
    }
    fun setSwarmMinEvents(v: Int)          = save { it[KEY_SWARM_MIN_EVENTS] = v }
    fun setDistanceUnit(v: DistanceUnit)   = save { it[KEY_DISTANCE_UNIT] = v.key }

    private fun save(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch { dataStore.edit { block(it) } }
    }
}
