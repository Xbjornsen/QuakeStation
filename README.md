# QuakeStation

A real-time earthquake, volcano and topography tracker for Android, built
around a custom 3D OpenGL globe. Pulls live event data from the USGS FDSNWS
catalog, detects swarms, animates magnitude-scaled ripples on the sphere,
and lets you replay the last 24 h / 7 d / 30 d as a time-lapse over the
globe.

> **Status:** active development. Targets Android 8.0+ (API 26), tested on
> phones from API 33 (Android 13) through API 34. Single-handed,
> portrait-first UI; no tablet layout yet.
>
> _Gradle root project (`rootProject.name`), Kotlin package
> (`com.quakesphere`), Application class (`QuakeSphereApp`) and theme
> still use the original `QuakeSphere` identifiers — code-level rename
> tracked separately._

<p align="center">
  <img src="docs/screenshots/globe.png"     alt="Live globe — LATEST/BIGGEST quake pill"        width="32%" />
  <img src="docs/screenshots/volcanoes.png" alt="Active volcanoes layer — orange 3D cones"      width="32%" />
</p>

## Install

The fastest way to get the latest build on a phone is the GitHub Releases
page — every tagged version ships a signed APK ready to sideload:

> **[→ Download the latest APK from Releases](https://github.com/Xbjornsen/QuakeStation/releases/latest)**

After the first install the **in-app updater** takes over: on every
launch the app checks GitHub for a newer release, surfaces a banner if
one exists, downloads the APK via Android's `DownloadManager`, and hands
it to the system package installer in one tap.

## Features

### Earth & data
- **Cinematic 3D globe** — custom OpenGL ES 2.0 renderer, eased camera, two-
  light shading (fixed view light + real-time UTC subsolar overlay for a
  subtle day/night terminator), star field, atmosphere rim glow.
- **Live earthquake data** — USGS FDSNWS Event API, with a Room cache so the
  app loads instantly when offline. Background sync via WorkManager.
- **Swarm detection** — greedy 200 km / 48 h clustering (`DetectSwarmsUseCase`),
  rendered as radial "spines" that grow taller with more events. Tap a
  spine for a sortable (by magnitude or by time) scrollable event list.
- **Replay** — hit ▶ in the header to play back every quake in your selected
  time window in chronological order, ~1.5 s per quake. Markers reveal
  cumulatively; the camera flies to each new event.

### Geo overlays
- **Tectonic plates** — PB2002 (Bird 2003) plate boundaries.
- **Historic-trends heatmap** — derived at first launch from plate-boundary
  proximity (Gaussian splat, σ ≈ 4.5°). A deterministic proxy for M5+
  density that doesn't need any offline data pipeline. Built on a
  background thread so it never blocks first paint.
- **Active volcanoes** — ~70 globally significant Holocene volcanoes from
  the Smithsonian GVP, rendered as small 3D orange cones with per-face
  shading. Tap for a card with name / country / type / elevation / last
  eruption year. A "most recent eruption" pill surfaces alongside the
  LATEST/BIGGEST quake pill in the header.
- **Major peaks** — ~60 iconic mountains: the Seven Summits, the 14
  eight-thousanders, plus famous peaks per continent. White triangle
  markers, tap for range / elevation / prominence.
- **Equator reference line** — thin cyan latitude-0 circle for orientation.
- **Star field**, **continent outlines**, **auto-rotate**, **marker colour
  mode** (depth vs magnitude) — all toggleable.

### Interactions & UX
- **Tap to focus** — markers, swarm spines, volcanoes, peaks, or the
  LATEST/BIGGEST header pill all fly the camera to the target and show a
  bottom card with details.
- **Notifications** — set a magnitude threshold; periodic background sync
  fires a system notification for any new event above it.
- **Settings** — magnitude floor, time range, depth filter, distance units,
  marker colour mode, sync interval, and toggles for every visual layer.

## Build from source

```bash
# from the repo root
./gradlew assembleDebug
# install the resulting APK on a connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requirements:
- JDK 17 (Eclipse Adoptium recommended)
- Android SDK with API 34 platform
- AGP 8.2.2 / Gradle 8.11 / Kotlin 1.9.22 (managed by the wrapper)

No API keys needed — USGS FDSNWS is public.

### Release pipeline

`.github/workflows/release.yml` builds a properly signed release APK,
patches `versionCode`/`versionName` from the input tag, tags the commit,
and publishes a GitHub Release with auto-generated changelog and the
APK attached. Trigger from the Actions tab or via
`gh workflow run release.yml -f version=vX.Y.Z`.

Signing requires four repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Without them the
workflow falls back to debug signing and annotates the release.

## Architecture

Clean Architecture with three layers (`domain` / `data` / `ui`), Hilt for DI,
Room for local storage, Retrofit for the USGS API and GitHub Releases,
DataStore for settings, WorkManager for background sync, Jetpack Compose
for everything except the OpenGL globe surface.

The globe lives in its own Gradle module (`:globe`) so it can be lifted
out into a standalone library later. It only knows about generic
`Marker` / `MarkerStack` / `RippleSpec` / `Volcano` / `Peak` /
`GeoCoord` types — domain models are mapped at the screen boundary.

```
app/
  ├─ domain/        pure-Kotlin models, repositories, use cases
  ├─ data/          USGS API + GitHub API + Room cache + repository impl
  ├─ ui/            Compose screens (globe, list, detail, settings)
  ├─ update/        in-app self-updater (check / download / install)
  ├─ work/          periodic sync worker
  └─ notification/  notification builder
globe/             reusable OpenGL ES 2.0 globe view + renderer
```

See [CLAUDE.md](CLAUDE.md) for design notes and non-obvious decisions
worth remembering across sessions.

## Permissions

- `INTERNET` — fetching USGS data + checking GitHub for updates
- `RECEIVE_BOOT_COMPLETED` — re-arm the periodic sync after a reboot
- `POST_NOTIFICATIONS` — requested at runtime on Android 13+. If you deny,
  the app still works; you just won't get notifications.
- `REQUEST_INSTALL_PACKAGES` — used by the in-app updater to launch the
  system package installer on a downloaded APK. The user still has to
  confirm in the installer dialog.

## Data sources & credits

- Earthquake catalog — [USGS FDSNWS Event API](https://earthquake.usgs.gov/fdsnws/event/1/)
- Continent polygons — [Natural Earth](https://www.naturalearthdata.com/) 110 m
- Tectonic plate boundaries — Bird, P. (2003) [PB2002](https://doi.org/10.1029/2001GC000252)
- Volcanoes — hand-curated subset of [Smithsonian GVP Holocene Volcano List](https://volcano.si.edu/)
- Major peaks — hand-curated (Seven Summits, the 14 eight-thousanders, iconic peaks per continent); elevations from Wikipedia
- Polygon triangulation — Kotlin port of [Mapbox earcut](https://github.com/mapbox/earcut)

## License

See [LICENSE](LICENSE) if present; otherwise treat as all-rights-reserved
until one is added.
