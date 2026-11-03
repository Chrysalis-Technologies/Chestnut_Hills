# v0.6.0: New York ferns, vines and fungi, fully offline

## Scope and coverage

Expand the v0.5 photo workflow with bundled models and a dated New York catalog.
Keep the original tree classifier, preprocessing, species list, model assets,
BirdNET, existing saved records and offline map intact. Never add INTERNET.

| Group | Total selectable taxa | With NY research-grade records |
|---|---:|---:|
| Plants | 808 | 806 |
| Ferns & allies (subset of Plants) | 14 | 14 |
| Vines (subset of Plants) | 28 | 28 |
| Mushrooms/fungi | 900 | 900 |

Examples include Christmas fern, sensitive fern, ostrich fern, common bracken,
field horsetail, poison ivy, Virginia creeper and Japanese honeysuckle. Fern
allies include horsetails and clubmosses. Shrub honeysuckles are not tagged vines.
Some taxa have only one or two NY records; inclusion is not an assertion of
nativity, abundance or identification quality. This is an incomplete model-backed
catalog, not a complete checklist of New York plants and fungi.

## Models, provenance and packaging

- [Iris 9](https://huggingface.co/brunopaiva15/iris), revision
  `0d5bd2131f62c7ccbb2af2dbf772e391397ab8b7`: 1,569 outputs, 9,005,584 bytes,
  float16 weights, float32 RGB 0..255 input. Center square, area-reduce to 448 if
  larger, bilinear resize to 366, center crop 320. Output is already softmax.
  CC BY 4.0 weights/metadata; the publisher discloses a missing training-image
  attribution manifest in its bundled model card.
- [FungiTastic DINOv3 ViT-S/16](https://huggingface.co/paulbauriegel/fungitastic-dinov3-vits16-384),
  revision `4043bd34b3704e4bd0b10c5ca1b150ffe1ee39ca`: 2,827 outputs, 45,944,968 bytes,
  float16 weights, full-image resize to 384 and float32 RGB 0..255 input. Stable
  softmax is applied to logits. Denmark/Europe training bias, no measured NY accuracy.
  Conservative license scope: CC BY-NC-SA 4.0 plus DINOv3 terms (noncommercial).

Hashes, full licenses and upstream model cards are in `nature-expanded/` and
readable from the results screen. Both weights are unmodified, memory-mapped
and uncompressed. The pack adds approximately 55 MB of weights to the APK.
No source training-photo collection is redistributed with the model pack.

`tools/data/ny-nature-observations-2026-11-03.json` contains exact scientific-name
matches against iNaturalist research-grade aggregate species counts for place
48, New York, US, retrieved November 3, 2026. Queries use
`/v1/observations/species_counts?place_id=48&quality_grade=research` with
`iconic_taxa=Plantae` or `Fungi`, paginated at 500 results. Only names, counts,
taxon IDs and ancestor IDs are retained; no photos or observation coordinates.
The source query results are not an accuracy evaluation or an authoritative
native-species list. No runtime iNaturalist API calls exist.

To reproduce the generated catalog, place the pinned publisher downloads in
`iris/` (`plants.tflite`, `labels.txt`, `model.json`) and `fungi/`
(`mushroom_model_fp16.tflite`, `labels.txt`, `export_info.json`), then run:

```sh
python tools/prepare_nys_nature_assets.py /path/to/model-downloads
```

The script verifies hashes and preserves every output in order, including hidden
non-NY competitors. Original v0.5 labels/IDs remain valid. A Lecanoromycetes
ancestor (iNaturalist 54743), or an existing excluded fungus label, prevents a
lichen from becoming a mushroom suggestion. Group references are recorded in
the bundled NOTICE; occurrence and morphology are separate checks.

## Identification and storage rules

- Iris primary acceptance: top >= 0.70, top-to-second margin >= 0.25 (publisher rule).
- Fungi primary acceptance: top >= 0.50, margin >= 0.20 (uncalibrated heuristic).
- Compare across all outputs first. A confident out-of-catalog/category winner
  returns Unknown. Never force a NY answer or renormalize filtered scores.
- An uncertain primary may fall back to unchanged Small 2 scores and its
  existing thresholds (global top >= 0.25, candidates >= 0.10). Do not blend scores.
- Display/save the actual model version; never describe scores as measured accuracy.
- Keep stored `flower` keys for old observations; display them as Plants.
  New `fern` and `vine` kinds reuse the existing schema. Corrections validate group
  membership, and label changes clear the old score.
- Keep photos private, preserve optional GPS/denied-permission behavior, use
  existing map routing, and retain the mushroom caution on results and details.
- Existing CSV/GeoJSON export remains tree sightings only.

## Verification

Required pre-push checks: `python training/check_species.py`, compilation of all
`training/*.py`, syntax-check the new packaging helper, and `git diff --check`.
The hosted **Build Android app** workflow runs unit tests, builds the release
and instrumentation APKs, and rejects INTERNET in the final APK. Local Gradle
is blocked by `java.io.IOException: Unable to establish loopback connection`;
hosted CI is the build gate, not a claimed local build.

Unit tests cover model hashes/output order, legacy identifiers, catalog membership,
NY annotations, both new input contracts, stable softmax, thresholds, unsupported
winners, raw-score preservation, new-kind persistence, corrections and map routing.

Desktop inference smoke tests produced red clover from Iris and fly agaric from
FungiTastic using the existing attributed test fixtures. These are compatibility
smoke checks only; training overlap is unknown. Two `ExpandedNatureDeviceTest`
instrumentation cases exercise the same models on Android. The XCover was
disconnected during this work, so these and the manual steps below have **not
been run on a device yet**. Earlier v0.5 device results do not validate v0.6.

When the device is available, install matching app/test APKs and run:

```sh
adb shell am instrument -w -r -e class com.chrysalis.treeid.nature.ExpandedNatureDeviceTest com.chrysalis.treeid.test/androidx.test.runner.AndroidJUnitRunner
```

## Manual device acceptance (not yet run)

1. Upgrade from v0.5 without clearing data. Confirm old tree, bird, plant and
   mushroom records/photos/notes still open; old flower records display as Plants.
2. Enable airplane mode. In Plants, Ferns & allies, Vines and Mushrooms, capture
   and pick photos, including portrait/landscape images. Verify suggestions or
   Unknown, visible model source, responsive cancellation and no network prompt.
3. Browse/search each supported list. Check NY filtering and counts, fern/vine
   membership, source date and offline licenses. Uncheck NY-only to see retained
   legacy entries without NY records.
4. Try unrelated objects, unsupported species and ambiguous photos. Confirm
   Unknown can be selected; do not treat a confident suggestion as verification.
   Review the mushroom caution on results and saved details.
5. Save a fern, vine and fungus with notes and GPS outdoors. Repeat with location
   denied, no fix, and Save without location. Check original-photo GPS where
   available; permission failure must still allow unlocated records.
6. Restart the app. Filter saved records by kind, edit notes, correct a species,
   choose Unknown and delete a test record. A changed label must clear its score;
   old records and original gallery photos must remain intact.
7. Open the offline map. Verify distinct fern/vine/fungus pins at saved coordinates,
   tap through to the correct detail, and confirm unlocated records create no pins.
8. Recheck Trees and Bird calls in airplane mode. Record model latency, memory use,
   battery impact and failures on the XCover; compare identifications with expert
   verified NY specimens before treating broader coverage as validated accuracy.

Open a PR into main after green CI. Do not merge or publish a v0.6.0 release as
part of this task.
