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
`android/app/build.gradle.kts`, then push an annotated tag such as `v0.2.0`. The tag
message becomes the release notes, and the workflow publishes the release.

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
2. ⏭️ Save sightings with GPS location and notes (offline), browse them on a list or map
3. More accurate model: full fine-tune on a GPU (`training/finetune_colab.ipynb`)

## Credits

Training photos: iNaturalist contributors, via the iNaturalist Open Dataset (CC0, CC BY,
CC BY-NC). The per-photo attribution is in `training/data/manifest.csv.gz`. Identifications
are suggestions; never eat or use a plant based on this app.
