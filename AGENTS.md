# Notes for coding agents

NY Tree ID: an offline Android app that identifies New York State trees from a photo with an
on-device TensorFlow Lite model. See README.md for the user-facing overview and
training/README.md for the model pipeline.

## Layout

| Path | What |
|---|---|
| `species/nys_trees.json` | Single source of truth: the 100 classes (99 trees + `_other`), iNaturalist taxa, field-guide text. Bundled into the app as an asset. |
| `android/` | Kotlin + Jetpack Compose + CameraX app (`com.chrysalis.treeid`). Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, CameraX 1.4.1, `org.tensorflow:tensorflow-lite:2.17.0`, minSdk 26, target/compile 35. |
| `android/app/src/main/assets/` | `tree_model.tflite`, `labels.txt` (class order), `model_info.json` |
| `training/` | Python pipeline: `build_manifest.py` → `download_images.py` → `train.py` → `evaluate_tflite.py`; `check_species.py` validates the species file |
| `.github/workflows/android.yml` | CI: validates species + builds APK on every push; publishes GitHub Releases |

## Checks before pushing

- `python training/check_species.py` and `python -m py_compile training/*.py`
- `cd android && ./gradlew assembleRelease` (needs JDK 17+ and the Android SDK). If you
  can't build locally, push to a branch and make sure the **Build Android app** workflow is green.
- Keep PRs small and focused; work on a branch and open a PR into `main`.
- In the PR description, say what was verified on a real device (or that it wasn't).

## Invariants (don't break these)

1. **Offline only.** Never add the `INTERNET` permission or any network dependency.
   Location must come from the device GPS (`LocationManager`), not Play Services' network
   location.
2. **Preprocessing contract.** `Images.centerSquarePixels` (Kotlin) must stay identical to
   `center_crop` in `training/train.py`: centre square crop, then
   `tf.image.resize(bilinear, antialias=True)`, float 0–255 RGB; training decodes JPEGs
   with `dct_method="INTEGER_ACCURATE"`. If either side changes, re-check with
   `training/evaluate_tflite.py`; it ports the app's preprocessing to Python.
3. **Classes.** `labels.txt` order = model output order; every label must exist in
   `species/nys_trees.json` (`check_species.py` enforces it). The field guide reads the same JSON.
4. **Model files are memory-mapped.** Keep `androidResources.noCompress += "tflite"`.
5. **Confidence wording.** `LIKELY`/`POSSIBLE` in `ui/ResultScreen.kt` are calibrated from the
   test-set confidence table (60% → right 92% of the time). Recalibrate with
   `evaluate_tflite.py` whenever the model changes.
6. **Signing.** `android/app/debug.keystore` is a deliberately shared *test* key so CI builds
   install over each other. Never commit a real release key; use GitHub secrets.

## Releasing

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`, add
`android/release-notes/vX.Y.Z.md`, then push a `vX.Y.Z` tag or run the workflow manually
with "Publish a GitHub Release" ticked. v0.x releases are marked pre-release.

## Current state (v0.1.0)

- Model: EfficientNetV2-B0, last stage fine-tuned on CPU (`train.py --mode partial`) on
  57,712 iNaturalist photos. Test set: 55.4% top-1, 74.4% top-3.
- App: camera + photo picker → identification screen (top matches, features to check,
  look-alikes) → field guide; About screen. Not yet field-tested on a device.

## Backlog (in priority order)

1. Fixes from the first field test (to be filed as issues).
2. **GPS sightings**: see `docs/tasks/gps-sightings.md`.
3. More accurate model: run `training/finetune_colab.ipynb` on a GPU, then replace the
   assets, recalibrate the thresholds, update `training/reports/eval_report.md`, and release.
4. Before any Play Store upload: swap `tensorflow-lite` 2.17.0 for LiteRT
   (`com.google.ai.edge.litert`) for 16 KB page-size support, add a private signing key, and
   build an `.aab`.
5. CI hygiene: move `actions/*` and `gradle/actions` to their Node 24 major versions
   (GitHub warns that Node 20 is deprecated).
