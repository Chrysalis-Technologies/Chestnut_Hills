# Offline flowers and mushrooms — v0.5.0

Camera and local photo-picker identification use the separate iNaturalist Small 2
model and taxonomy from [official release v25.01.15](https://github.com/inaturalist/model-files/releases/tag/v25.01.15).
The repository's MIT license, source attribution and pinned model digest are bundled
under `assets/nature`. The existing tree and bird model paths are unchanged.

This public model covers 507 taxa across many groups, of which **174 are flowering
plants** (including trees/shrubs) and **18 are mushroom/fungus taxa**. Three lichens
are excluded from mushroom mode. It is not the full Seek/iNaturalist model. A visible,
searchable supported-species list gives exact coverage. `tools/prepare_nature_assets.py`
reproduces the catalog from the release's taxonomy and common-name archive.

The independent preprocessing path follows the official Android Small 2 implementation:
center square, Android bilinear resize to 299x299, column-first RGB floats 0..255.
The 507 output scores already sum to one. Only show suggestions if the overall highest
class is in the selected group and scores at least 0.25; then show up to five in-group
scores of at least 0.10 without renormalizing. This avoids forcing a category match,
but does not reliably detect unsupported species. The thresholds are uncalibrated.

Users explicitly select a suggestion or Unknown. Mushroom results and saved details
always state that the app cannot determine edibility. There are no edible/safe badges.
Photo ID must not guide consumption; see [National Poisons Information Centre guidance](https://poisons.ie/garden-shed/).

Observations use a separate Room database and private JPEG copies in noBackupFilesDir.
The shared photo-copy utility limits stored photos to 1024 pixels, without changing the
classifier input. Original picker files are never modified. GPS is optional, using
photo metadata when available or the existing LocationManager-only reader; timeout,
denial and skip can save without coordinates. Observation time is the time the app
received the photo, not a claim about its original EXIF capture date. Corrections clear
the model score. File rollback and orphan cleanup preserve storage consistency.

The map combines typed/namespaced markers and routes each pin to its matching record.
Existing tree CSV/GeoJSON export scope is unchanged. No network dependencies or downloads
are added to the app. No new permissions, data migration, automatic release or merge.

## Verification and manual acceptance

- Unit tests cover input color/crop/order, group filtering and score preservation,
  catalog/model integrity, save/correction/delete/failure recovery, and map identity.
- Mandatory pre-push Python checks and GitHub's Android build/no-INTERNET check apply.
- New instrumented tests use two attributed photographs and an isolated private database:
  `adb shell am instrument -w -e class com.chrysalis.treeid.nature.NatureDeviceTest com.chrysalis.treeid.test/androidx.test.runner.AndroidJUnitRunner`.
  These are compatibility/regression fixtures; training-set overlap is unknown and they
  do not establish general accuracy. The CI artifact is `android-device-tests`.

Manual checklist (report which steps were actually completed in the PR):

1. Upgrade v0.4 without clearing data; check existing tree/bird observations and maps.
2. In airplane mode, select Flowers and Mushrooms, take photos and choose local photos.
   Check portrait/landscape orientation, center crop, cancellation and unreadable files.
3. Confirm species coverage and mushroom caution are readable; no choice is preselected.
   Try supported, ambiguous and unsupported specimens, and clearly unrelated objects.
   Check results against independent expert identifications before assessing accuracy.
4. Save named and unknown records with notes; test denied/approximate/off GPS, no fix,
   photo GPS, current GPS and Save without location. Restart the app and compare records.
5. Edit notes, correct species within the right category, and verify old scores clear.
   Delete a test observation and confirm its photo copy is gone while originals remain.
6. Check flower/mushroom/unknown pins alongside tree/bird pins, overlapping points,
   Locate/Fit/Show on map and MBTiles offline. Unlocated observations stay in their lists.
7. Check tree and bird capture still work, and evaluate speed/memory on repeated captures.

Do not claim outdoor GPS, mushroom identification accuracy, or consumption safety from
synthetic data or the two reference photographs. Publish no release without Paul's request.
