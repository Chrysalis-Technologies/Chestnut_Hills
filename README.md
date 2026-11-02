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

The APK is built by GitHub Actions on every push:

1. Open the repo's **Actions** tab → latest **Build Android app** run → download the
   `nys-tree-id-apk` artifact and unzip it.
2. Copy the `.apk` to the phone and open it. Android asks once to allow installs from that
   app (Files, Chrome, or Drive).
3. Requires Android 8.0 or newer.

Every build is signed with the same test key (`android/app/debug.keystore`), so a new build
installs over the old one. Before publishing to an app store, switch to a private signing key
kept out of git.

To build locally instead: open `android/` in Android Studio, or run `./gradlew assembleRelease`
in `android/` (JDK 17+ and the Android SDK required).

## Getting good results

* Fill the on-screen square with **one leaf** (or one cluster of needles), in good light.
  Leaves are by far the most reliable. Bark, flowers, fruit, and cones help too. Whole trees
  from a distance usually don't work.
* Read the confidence wording. "Most likely" is usually right, "Possibly" means check the
  listed features, and "Not sure" means take a closer photo.
* Similar species (red vs. black oak, white vs. green ash, the spruces) are the hardest.
  The result screen lists what to check and the usual look-alikes.

## How it's built

| Path | What's there |
|---|---|
| `species/nys_trees.json` | The tree list: names, iNaturalist taxa, field-guide notes, look-alikes. Used by both training and the app. |
| `training/` | Dataset builder, photo downloader, training/export script, Colab notebook. See [training/README.md](training/README.md). |
| `training/data/manifest.csv.gz` | The exact photos the model was trained on (with licence and photographer). |
| `android/` | Kotlin + Jetpack Compose + CameraX app; runs the model with TensorFlow Lite. |
| `android/app/src/main/assets/` | The trained model (`tree_model.tflite`), `labels.txt`, `model_info.json`. |

## Roadmap

1. ✅ Offline identification + field guide
2. ⏭️ Save sightings with GPS location and notes (offline), browse them on a list or map
3. Fine-tuned (more accurate) model trained on a GPU: `training/finetune_colab.ipynb`

## Credits

Training photos: iNaturalist contributors, via the iNaturalist Open Dataset (CC0, CC BY,
CC BY-NC). The per-photo attribution is in `training/data/manifest.csv.gz`. Identifications
are suggestions; never eat or use a plant based on this app.
