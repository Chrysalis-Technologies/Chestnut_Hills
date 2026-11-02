# Training the tree model

Everything here turns `species/nys_trees.json` into the three files the app needs
(`tree_model.tflite`, `labels.txt`, `model_info.json`).

```
species/nys_trees.json ──► build_manifest.py ──► data/manifest.csv.gz   (which photos to use; committed)
                                                        │
                           download_images.py ◄─────────┘               (~72k photos, ~10 GB, not committed)
                                   │
                              train.py ──► tree_model.tflite + labels.txt + model_info.json + eval_report.md
                                                        │
                                   android/app/src/main/assets/ ◄┘
```

## Where the photos come from

Research-grade observations from the [iNaturalist Open Dataset](https://github.com/inaturalist/inaturalist-open-data)
on AWS: community-verified photos released under CC0, CC BY, or CC BY-NC. No API key and
no rate limits. For each tree, `build_manifest.py` picks up to 500 observations (2 photos
each), preferring New York, then the surrounding Northeast, then anywhere. It caps how many
come from any one person. Photos are split 80/10/10 into train/validation/test **by
observation**, so two photos of the same tree never land on both sides.

The `_other` class ("not a tree in this guide") is 1,500 random New York plant observations
from genera that aren't in the guide. It lets the app say "that's not one of my trees"
instead of forcing a wrong answer.

## Quick start

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r training/requirements.txt

# 1. (Only after editing the species list) rebuild the manifest. Streams ~35 GB, ~20-40 min.
python training/build_manifest.py --resolve-only        # quick check that every name resolves
python training/build_manifest.py --cache-dir .cache/inat

# 2. Download the photos (~10-30 min, resumable)
python training/download_images.py --out-dir data/images --workers 48

# 3a. CPU: fine-tune the network's last stage (~2 h on 4 cores). This made the shipped model.
python training/train.py --mode partial --images data/images --out-dir build/model

# 3b. GPU: fine-tune the whole network (~1 h on a free Colab T4); the next accuracy step.
#     See finetune_colab.ipynb
python training/train.py --mode finetune --images data/images --out-dir build/model

# 4. Check the exported file the way the app will run it, then ship it
python training/evaluate_tflite.py --model-dir build/model --images data/images
cp build/model/{tree_model.tflite,labels.txt,model_info.json} android/app/src/main/assets/
cp build/model/eval_report.md training/reports/
python training/check_species.py
```

`build/model/eval_report.md` shows test-set accuracy per tree, the most common mix-ups, and
how accurate the top answer is at each confidence level. The app's "Most likely" and
"Possibly" wording thresholds (`ResultScreen.kt`) are chosen from that table.

## Training modes, compared

| Mode | What trains | Hardware | Notes |
|---|---|---|---|
| `features` | classifier head only (backbone frozen) | CPU, ~30 min | test top-1 39.9% / top-3 58.5%: ImageNet features alone don't separate tree species |
| `partial` | last backbone stage (4.5M of 5.9M parameters) + head; early layers cached | CPU, ~2 h | test top-1 **55.4%** / top-3 **74.4%**: the shipped model |
| `finetune` | everything, with fresh augmentation each epoch | GPU, ~1 h | not run yet; expected to be the most accurate |

## The app/model contract

`train.py` and `TreeClassifier.kt` must agree on this:

* RGB, centre square crop, bilinear resize to the model's input size (224 px for EfficientNetV2-B0)
* float32 pixel values 0–255; the model normalises internally
* output: softmax over the classes in `labels.txt`, in that order

`train.py` checks that the exported `.tflite` makes the same predictions as the Keras model
before writing anything. `evaluate_tflite.py` re-implements the app's preprocessing in
Python. Its numbers matched training's (55.4% top-1) on all 7,278 test photos.

## Adding or changing trees

1. Edit `species/nys_trees.json`. `inat_taxa` takes iNaturalist scientific names; a single
   word means a whole genus. Put look-alikes in `lookalikes`.
2. `python training/check_species.py` and `python training/build_manifest.py --resolve-only`
3. Rebuild the manifest, download, and retrain (steps 1-4 above).

The field guide in the app updates from the same file automatically. A tree that's in the guide
but not in `labels.txt` shows a "guide only" note instead of being suggested by the camera.
