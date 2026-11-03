# NY Tree ID

An Android app that identifies **New York State trees from a photo, completely offline**.
Point the camera at a leaf, needles, bark, flowers, or fruit. A model running on the phone
suggests the most likely trees, and a built-in field guide shows what to check to confirm.

* **99 trees** common in New York (native, naturalized, and street/yard trees), plus a
  "not a tree in this guide" class for other plants
* **No internet needed, ever.** The app doesn't request the internet permission, so photos
  never leave the phone.
* The model is trained on community-verified, openly licensed iNaturalist photos,
  preferring photos taken in New York.

## Install on a phone

1. On the phone, open the repo's **Releases** page and download the latest
   `nys-tree-id-vX.Y.Z.apk`. (Every push also builds an APK, available as the
   `nys-tree-id-apk` artifact on that run in the **Actions** tab.)
2. Open the downloaded file. Android asks once to allow installs from your browser or
   Files app.
3. Requires Android 8.0 or newer.

To publish a new version, bump `versionCode` and `versionName` in
`android/app/build.gradle.kts` and write `android/release-notes/vX.Y.Z.md`. Then either push
a `vX.Y.Z` tag, or open **Actions → Build Android app → Run workflow** and tick
"Publish a GitHub Release".

Every build is signed with the same test key (`android/app/debug.keystore`), so a new build
installs over the old one. Before publishing to an app store, switch to a private signing key
kept out of git.

To build locally instead: open `android/` in Android Studio, or run `./gradlew assembleRelease`
in `android/` (JDK 17+ and the Android SDK required).

## How accurate is it?

Measured on 7,278 photos the model never saw during training
([full report](training/reports/eval_report.md)):

| | |
|---|---|
| Right tree as the first answer | **55%** |
| Right tree among the top 3 answers | **74%** |
| When the app says "Most likely" (confidence ≥ 60%) | right **92%** of the time (about a third of photos) |
| Real trees wrongly dismissed as "not a tree in this guide" | under 1% |

Those test photos are a mix of leaves, bark, flowers, fruit, and whole trees from iNaturalist.
A deliberate close-up of a leaf usually does better than the average. The usual mistakes are
genuine look-alikes: scarlet vs. pin oak, the spruces, the two larches, pignut vs. mockernut
hickory, sycamore vs. London planetree. The weakest trees are common ones that look very
different from season to season, like red maple, northern red oak, white ash, and American elm.

This first model was trained on a CPU, fine-tuning only the last part of the network.
Fine-tuning the whole network on a GPU (`training/finetune_colab.ipynb`, free on Google
Colab) is the next accuracy step.

## Getting good results

* Fill the on-screen square with **one leaf** (or one cluster of needles), in good light.
  Leaves are by far the most reliable. Bark, flowers, fruit, and cones help too. Whole trees
  from a distance usually don't work.
* Read the confidence wording. "Most likely" is right about 9 times in 10. "Possibly" means
  compare the listed features and the other matches. "Not sure" means take a closer photo.
* Similar species (red vs. black oak, white vs. green ash, the spruces) are the hardest.
  The result screen lists what to check and the usual look-alikes.

## Sightings (v0.2.0)

After identification, tap a candidate (or **Unknown tree**) and choose **Save sighting**.
Location permission is requested only when saving. The app prefers GPS embedded in a selected
original photo; otherwise it uses the device GPS, waits up to 20 seconds, and still saves if
permission is denied or no fix arrives. **Save without location** skips the wait. Approximate-only
permission cannot provide a GPS fix. Photos opens the local document picker to retain original
photo metadata where the provider allows it.

Open **Sightings** beside Photos/Guide to browse saved photos, edit notes, change the tree,
or delete a sighting. Photos are copied to app-private storage, limited to 1024 pixels on the
long side. **Export** offers CSV and GeoJSON through the Android share sheet. Only the selected
export is shared; unlocated sightings have blank CSV coordinates and null GeoJSON geometry.
CSV dates use UTC, coordinates are decimal degrees, and accuracy is in meters. Spreadsheet-like
formulas in text fields are prefixed with an apostrophe; GeoJSON keeps the original notes.

## Illustrated guide and sightings map (v0.3.0)

Each tree's **Field guide** page includes reference images of bark, leaves/needles,
branches/buds, fruit/nuts/cones and seasonal changes, plus four seasonal identification
notes. Tap an image credit to read its license and source offline. Grouped entries show
named example species; they do not imply every species or cultivar looks identical.
Seasonal timing varies with weather, elevation and location within New York.
The collection includes eight labeled botanical illustrations. The weeping-willow
fruit card explicitly shows a related hybrid comparison; see the content notes below.

Open **Sightings → Map of sightings**, or **Show on map** from an individual sighting.
Numbered pins correspond to the identification list below the map. Tap a pin to open
its saved identification; nearby or overlapping sightings can be selected separately.
**Locate** centers a listed sighting, and **Fit sightings** frames all saved coordinates.
Sightings without a location remain available in the normal sightings list.

The bundled map is a **New York boundary overview**, without streets or aerial imagery.
For local detail, use **Import map** and select a raster **MBTiles** file prepared on a
computer (PNG, JPEG or WebP tiles in the standard Web Mercator/TMS layout, up to 1 GiB).
The app copies it to private storage; allow enough free space for the new copy alongside
any previous map during replacement. Vector PBF MBTiles are unsupported. A blank background
means the file has no tiles at the current place/zoom; the sighting pins still work.
The map's embedded attribution is displayed. Use map data you have permission to copy.
**Remove map** removes only the app's copy, keeping the original file and all sightings.
There are no downloads, map servers or additional location requests in this screen.

Guide imagery and seasonal text are supplemental assets; the model, labels, original
species list and image preprocessing are unchanged. See [content provenance](docs/field-guide-content.md).

## Bird calls (v0.4.0)

Open **Bird calls** from the camera screen to record twelve seconds and identify calls
entirely on the phone with bundled **BirdNET 2.4**. Microphone permission is requested
when you tap **Record bird call**. GPS is optional; it uses the device GPS during capture.
Denied location permission or no fix still allows an unlocated observation. Recording
cancels when you leave the screen or lock the phone; there is no background listening.

Replay the clip, select a suggested species or **Unknown call**, then **Confirm and save**.
**Saved bird observations** lets you replay recordings, edit notes, correct a species,
and delete observations. Bird pins appear alongside trees on **Map of sightings**.
Green pins are trees, blue pins are bird calls, and gray pins have an unknown identification.

Scores rank suggestions and are not calibrated probabilities. Check what you heard and
the species' range; some other animal calls are included, with no regional filter.
The model is bundled: there are no downloads, cloud identification, or uploads.
Recordings and the bird database are private and excluded from backup. Automatic Android
backup/device transfer is disabled for app data; clearing data or uninstalling removes
saved records. Unsaved clips do not survive process exit. Existing CSV/GeoJSON exports
remain **tree sightings only**.

BirdNET model and labels: **CC BY-NC-SA 4.0**, for noncommercial use unless separately
licensed. Credits, source, hashes and the complete license are bundled and readable from
**Bird calls → credits and license**. See the [implementation and test checklist](docs/tasks/offline-birdnet.md).

## How it's built

| Path | What's there |
|---|---|
| `species/nys_trees.json` | The tree list: names, iNaturalist taxa, field-guide notes, look-alikes. Used by both training and the app. |
| `training/` | Dataset builder, photo downloader, training/export and evaluation scripts, Colab notebook. See [training/README.md](training/README.md). |
| `training/data/manifest.csv.gz` | The exact photos the model was trained on (with licence and photographer). |
| `android/` | Kotlin + Jetpack Compose + CameraX app; runs the model with TensorFlow Lite. |
| `android/app/src/main/assets/` | The trained model (`tree_model.tflite`), `labels.txt`, `model_info.json`. |

## Roadmap

1. ✅ Offline identification + field guide
2. ✅ Save sightings with GPS location and notes (offline), browse/edit/delete, export CSV/GeoJSON
3. ✅ Illustrated seasonal field guide and offline sightings map, with raster MBTiles import
4. ✅ Offline BirdNET call identification, confirmed observations, local recordings and map pins
5. Field-test v0.4.0 on the XCover: real calls, GPS, interruption handling, accuracy and battery; finish v0.3 guide/map acceptance
6. More accurate tree model: full fine-tune on a GPU (`training/finetune_colab.ipynb`)

## Credits

Training photos: iNaturalist contributors, via the iNaturalist Open Dataset (CC0, CC BY,
CC BY-NC). The per-photo attribution is in `training/data/manifest.csv.gz`. Identifications
are suggestions; never eat or use a plant based on this app.
