# Offline BirdNET integration — v0.4.0

## Scope and architecture

Bird calls are a separate capture and storage path beside the existing tree classifier.
Record twelve seconds of mono PCM16 at 48 kHz using Android AudioRecord, while the screen
is visible. Analyze four three-second windows using the bundled BirdNET 2.4 FP16 acoustic
model. Convert PCM by dividing by 32768, apply one sigmoid to model logits, retain each
class's highest window score, and show up to five suggestions above 0.25. Silence below
RMS 0.0001 is skipped. The threshold is a UI heuristic, not measured field accuracy.

No default species selection: a user must confirm a candidate or Unknown call. Record
the capture start time and optional GPS obtained during capture via LocationManager.
Fine location is optional; denied permission or no timely fix produces an unlocated
record. No network location, range service, analytics, uploads, background recorder,
or runtime asset downloads. The upstream model includes some non-bird animals, and
there is no regional species filter. Bird labels never enter the tree classifier.

Bird observations use a separate Room database in noBackupFilesDir; WAV recordings
also live there. Existing tree database version and contents are untouched. A failed
insert removes its new audio file. Row deletion precedes file deletion; startup
cleanup removes orphan recordings after crashes or interrupted deletion. Unknown
records have no score; correcting species clears its model score. Draft previews live
in cache and are removed on discard, save, ViewModel teardown, or next startup.
Automatic Android backup and device transfer are explicitly excluded for app data.

The map merges typed, namespaced markers (`tree:id`, `bird:id`) and opens the matching
detail screen, including overlapping points. Missing/invalid coordinates do not create
pins. Existing exports remain explicitly tree-only in this version.

## Model provenance and redistribution

Unmodified acoustic model and en_us labels from
[BirdNET 2.4 on Zenodo](https://doi.org/10.5281/zenodo.15050749), FP16 archive.
[BirdNET model documentation](https://birdnet-team.github.io/BirdNET-Analyzer/stable/models.html)
and the archive referenced by the official `birdnet` Python package identify this model.
Pinned hashes and complete offline attribution are in `assets/birds/NOTICE.txt`.
The model and labels remain CC BY-NC-SA 4.0; commercial distribution/use requires
separate permission. No Merlin assets or proprietary API are used.

## Required verification

- Unit tests: PCM conversion, WAV format, window boundaries, silence gate, sigmoid and
  score ranking, non-event exclusion, unknown/no-GPS persistence, correction, reopen,
  file rollback/deletion/orphan recovery, marker identity, pinned model and manifest.
- Before every push: `python training/check_species.py` and `python -m py_compile training/*.py`.
- GitHub Actions: `testReleaseUnitTest assembleRelease assembleDebugAndroidTest`, then
  assert the merged APK never requests INTERNET. Local Gradle may not be reported as
  passing when it is blocked by the host's loopback connection error.
- Device tests (synthetic audio, no microphone): install the release and test APKs with
  the shared test signature, then run
  `adb shell am instrument -w com.chrysalis.treeid.test/android.test.InstrumentationTestRunner`.
  This checks native inference, private audio/database persistence, and installed permissions.

## Manual acceptance checklist

1. Upgrade an installed v0.2/v0.3 build without clearing data; existing tree sightings,
   guide content and imported maps remain available. Use airplane mode throughout.
2. Open Bird calls, deny microphone permission, and verify a useful error and no recording.
   Grant it on a later attempt; record an outdoor call and hear the saved twelve-second clip.
3. Cancel midway, switch apps, lock the phone, and navigate back while recording.
   The microphone indicator must turn off, with no partial observation saved.
4. Test quiet audio, wind, several birds and a recognizable call. Check suggestions,
   explicit confirmation, Unknown call, and the score explanation. Check memory,
   latency and battery in repeated recordings on the XCover.
5. Test precise GPS, approximate-only, denied permission, GPS disabled, and no fix.
   Each can save; only a real fix produces coordinates. The location is captured
   during the recording, not invented or looked up over the network.
6. Save a suggestion and an unknown call with notes; restart the app. Replay, edit
   notes, correct species (old model score clears), and delete with confirmation.
   A deleted record and its audio must be gone; other tree/bird records stay intact.
7. On the map check tree and bird pins, unknown pins, overlapping points, Locate,
   Fit sightings, per-record Show on map, and imported MBTiles while offline.
8. Open BirdNET credits offline. Verify the license and noncommercial restriction
   are readable. Tree identification and tree CSV/GeoJSON exports still work.

Device smoke tests do not establish field identification accuracy or GPS/microphone
behavior. The PR must distinguish checks actually performed from the checklist above.
