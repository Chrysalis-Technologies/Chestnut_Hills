# Task: save sightings with GPS (v0.2.0)

Read `AGENTS.md` first, especially the invariants: no internet permission, ever.

## Goal

After identifying a tree, the user can save a **sighting**: which tree, where, and when, plus a
photo and notes. They can browse, edit, delete, and export their sightings. Everything works
with no signal.

## Requirements

**Saving (result screen)**
- Add a "Save sighting" button to `ResultScreen`. It saves the top tree by default; tapping
  another candidate first lets the user save that one instead. Also allow "Unknown tree".
- Store: tree key, the confidence shown, timestamp, latitude/longitude/accuracy in meters
  (if available), notes (empty at first), and a photo copy (JPEG, long side ≤ 1024 px, quality
  ~85) in app-private storage (`filesDir/sightings/`).
- Location: request `ACCESS_FINE_LOCATION` (with `ACCESS_COARSE_LOCATION`) the first time the
  user saves, not at app start. Use `LocationManager` with `GPS_PROVIDER` (works offline;
  no Play Services). On API 30+ use `getCurrentLocation`; on 26–29 use a single-update
  request. Wait up to ~20 s, accept a recent last-known fix (< 2 min old) immediately, and
  save without a location if none arrives or permission is denied. Show
  "Getting location…" while waiting, with a "Save without location" option.
- If the photo has EXIF GPS (gallery photos), prefer that location over the device's
  current one.

**Browsing**
- A "Sightings" entry point on the camera screen (next to Photos/Guide) opens a list, newest
  first: thumbnail, tree name, date, and a short place/coordinates line.
- Detail screen: the photo, tree (tap → guide entry), date/time, coordinates with accuracy,
  editable notes, a "Change tree" picker (search over the guide), and delete with confirm.
- No map in this task. Offline map tiles are a separate, larger decision.

**Export**
- "Export" on the list screen shares a CSV (date, tree common/scientific name, lat, lon,
  accuracy, notes) and a GeoJSON file via the Android share sheet (`ACTION_SEND` with a
  `FileProvider` URI). Sharing hands the file to another app; this app still needs no internet.

## Implementation notes

- Use Room for storage (`androidx.room`, with KSP) in a `data/sightings` package; keep the
  UI on the existing `TreeViewModel` + `Screen` navigation pattern (or split out a
  `SightingsViewModel`).
- Don't block the main thread: database, image encoding, and location work happen off it.
- Keep the photo the user saw (the one in `Identification.photo`).
- Bump `versionCode` to 2 and `versionName` to `0.2.0`; add `android/release-notes/v0.2.0.md`.
- Update README's Roadmap (mark GPS sightings done).

## Done when

- CI is green and the APK installs over v0.1.0 without uninstalling. Data from the old version
  doesn't exist, so there's no migration.
- Unit tests cover CSV/GeoJSON export formatting and the repository layer (JVM tests,
  Robolectric if needed).
- The manual test steps below are listed in the PR, with which ones were actually run on a
  device:
  1. Save with location granted (outdoors), with location denied, and in airplane mode.
  2. Save from a gallery photo that has EXIF GPS.
  3. Edit notes, change the tree, delete; restart the app and confirm the data persists.
  4. Export, then open the CSV in a spreadsheet app.
