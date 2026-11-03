---
license: cc-by-4.0
pipeline_tag: image-classification
tags:
  - image-classification
  - image-feature-extraction
  - plant-identification
  - plants
  - biodiversity
  - tflite
  - on-device
  - mobile
  - knowledge-distillation
  - fastvit
  - mobilenetv3
metrics:
  - accuracy
model-index:
  - name: Iris 10
    results:
      - task:
          type: image-classification
          name: Plant species classification, indoor plants
        dataset:
          name: Iris benchmark, indoor slice (2026-09, 1,127 images)
          type: custom
        metrics:
          - type: accuracy
            name: Top-1, fused, indoor mask
            value: 0.8651
      - task:
          type: image-classification
          name: Plant species classification, outdoor plants
        dataset:
          name: Iris benchmark, outdoor slice (2026-09, 2,000 images)
          type: custom
        metrics:
          - type: accuracy
            name: Top-1, fused, outdoor mask
            value: 0.8570
---

# Iris 10

An offline plant species identifier for phones. Two TensorFlow Lite networks
look at the same photograph, and their answers are fused: Iris 9, a softmax
classifier, and a new 1,024-d botanical encoder distilled from BioCLIP 2.5,
whose vector is compared with reference vectors for each species. 1,567
species, 37 MB in all, about 55 ms of inference per photograph on a recent
phone, no network call.

On the same benchmark, the fusion answers **86.5 %** of indoor plant photos
correctly (Iris 9 alone: 81.2 %) and **85.7 %** of outdoor ones (Iris 9: 76.2 %).

Iris is the on-device model of [Auxine](https://vergasta.ch/auxine/en/),
a plant care app. It is published here on its own so that it can be used,
measured and criticised outside that app. Inference example and the Iris 9
training code: [github.com/brunopaiva15/iris](https://github.com/brunopaiva15/iris).

## The model in one table

| | |
|---|---|
| Version | 10 |
| How it answers | `p9^0.5 · p10^0.5`, renormalised: Iris 9's softmax fused with the encoder's reference lookup |
| Species | 1,567 (Iris 9's 1,569 outputs, two pairs of synonyms merged) |
| **Iris 10 encoder** | |
| Architecture | FastViT-SA12 (11.6 M parameters) + linear projector, distilled from BioCLIP 2.5 |
| Input | 320 × 320 × 3, float32, **values in 0–1**, NHWC |
| Output | one 1,024-d unit vector |
| Weights | float16 TFLite, 23,373,892 bytes |
| SHA-256 | `5d4fc8a654722fe802fc7528f5f71349d785a419fa0780bc06f510fd5d1f4598` |
| References | 2,404 unit vectors, float16, 4,923,392 bytes — at least one per species |
| **Iris 9 classifier** | |
| Architecture | MobileNetV3-Large, softmax head |
| Input | 320 × 320 × 3, float32, **values in 0–255**, normalisation inside the graph |
| Output | 1,569 softmax probabilities |
| Weights | float16 TFLite, 9,005,584 bytes |
| SHA-256 | `a919ae6a24f7e7717a36d5213a800a3410f7cee0f694f5014ba8eb07f0a6caa3` |

`iris10.json` and `model.json` are the fact sheets the app reads; every
setting on this page comes from them. Read them rather than hardcoding a value
from here.

## Results

One frozen benchmark, drawn from the held-out split of the Iris image set by
observation group (seed 20260919): **1,127 photographs of cultivated indoor
plants and 2,000 of outdoor plants**. Each network is read the way the app
reads it — context mask applied, synonyms added up. Rules with a tuned
parameter were tuned on one half of the benchmark and read on the other.

| top-1 | indoor | outdoor |
|---|---|---|
| Iris 9 alone | 0.8119 | 0.7620 |
| Iris 10 encoder alone, no mask | 0.8429 | 0.8285 |
| Iris 10 encoder alone, context mask | 0.8598 | 0.8310 |
| **fusion, context mask** | **0.8651** | **0.8570** |

The fusion is the best rule on both sides, and its weight (0.5) was chosen
identically on both halves of the benchmark.

These numbers are measured on the exported float16 file, not on the training
weights; the two differ by 0.05 point at most. They do **not** compare with
the 0.6876 top-1 published for Iris 9 alone: that figure was measured on
30,954 images of mostly wild plants with no mask, a different benchmark.

## Files

| File | What it is |
|---|---|
| `iris10.tflite` | the Iris 10 encoder |
| `iris10-references.bin` | the reference matrix, `rows × 1024` little-endian float16, unit rows |
| `iris10.json` | input recipe, SHA-256 of both files above, `especes` (species ids), `lignes` (species of each reference row), `iris9` (species of each Iris 9 output), `synonymes`, `temperature`, `fusion`, `accept_threshold`, control vector |
| `plants.tflite` | the Iris 9 classifier |
| `labels.txt` | one internal id per Iris 9 output, in output order (`monstera-deliciosa`) |
| `model.json` | Iris 9's fact sheet: input recipe, SHA-256, metrics, threshold curve, `species` map (id → scientific name), `masks` |

Iris 9 is part of Iris 10, not a previous version left behind: the fusion
needs its output. It is also the fallback — if the encoder files are missing
or fail the control check, Iris 9 answers alone at its own threshold.

## Quick start

```bash
pip install huggingface_hub ai-edge-litert pillow numpy
```

```python
import json
import numpy as np
from PIL import Image, ImageOps
from huggingface_hub import hf_hub_download
from ai_edge_litert.interpreter import Interpreter

REPO = "brunopaiva15/iris"
get = lambda name: hf_hub_download(REPO, name)
meta9, meta10 = json.load(open(get("model.json"))), json.load(open(get("iris10.json")))
labels = open(get("labels.txt")).read().split()
refs = np.frombuffer(open(get("iris10-references.bin"), "rb").read(), "<f2")
refs = refs.astype(np.float32).reshape(-1, meta10["dim"])
rows, iris9_to = np.array(meta10["lignes"]), np.array(meta10["iris9"])

def run(path, x):
    it = Interpreter(model_path=path); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x); it.invoke()
    return it.get_tensor(it.get_output_details()[0]["index"])[0]

def square(im):
    s = min(im.size); l, t = (im.width - s) // 2, (im.height - s) // 2
    return im.crop((l, t, l + s, t + s))

im = ImageOps.exif_transpose(Image.open("photo.jpg")).convert("RGB")

# Iris 9: centre square, area-average to 448, bilinear to 366, crop 320, values 0-255
x9 = square(im)
if x9.width > 448: x9 = x9.resize((448, 448), Image.BOX)
x9 = x9.resize((366, 366), Image.BILINEAR).crop((23, 23, 343, 343))
p9 = run(get("plants.tflite"), np.asarray(x9, np.float32)[None])

# Iris 10: long side to 384 (Lanczos), centre square, bicubic to 320, values 0-1
k = 384 / max(im.size)
x10 = im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS) if k < 1 else im
x10 = square(x10).resize((320, 320), Image.BICUBIC)
v = run(get("iris10.tflite"), np.asarray(x10, np.float32)[None] / 255)
v /= np.linalg.norm(v)

# best cosine per species, softmax at temperature 100
best = np.full(len(meta10["especes"]), -np.inf); np.maximum.at(best, rows, refs @ v)
p10 = np.exp(meta10["temperature"] * (best - best.max())); p10 /= p10.sum()

# Iris 9 regrouped by species (synonyms add up), then the fusion
p9s = np.zeros_like(p10); np.add.at(p9s, iris9_to, p9)
w, floor = meta10["fusion"]["poids_iris9"], meta10["fusion"]["plancher"]
logp = w * np.log(np.maximum(p9s, floor)) + (1 - w) * np.log(np.maximum(p10, floor))
p = np.exp(logp - logp.max()); p /= p.sum()

for i in p.argsort()[::-1][:5]:
    print(f"{meta9['species'][meta10['especes'][i]]:40s} {p[i]:.4f}")
```

A fuller script — the context masks, the acceptance rule, the control check,
several photos of one plant, Iris 9 alone as a fallback — is in
[`examples/identify.py`](https://github.com/brunopaiva15/iris/blob/main/examples/identify.py).

## Preprocessing, exactly

Each network gets its own framing of the same decoded photograph, and each
framing is part of its model. Both start by honouring the EXIF orientation.

**Iris 10 encoder** — the recipe of its training images:

1. resize the long side to **384 px with Lanczos** — every training image was
   stored like this;
2. crop the largest centred square;
3. resize to **320 px, bicubic**;
4. hand over float32 values in **0–1**, NHWC.

**Iris 9 classifier:**

1. crop the largest centred square;
2. if it is wider than 448 px, area-average it down to 448;
3. bilinear resize to 366 px;
4. centre crop to 320 px;
5. hand over float32 values in **0–255**. The rescaling layer is in the graph:
   do not divide by 255, do not apply ImageNet mean/std.

The resampling filters are Pillow's. The app reimplements them in Dart and
matches Pillow 12 byte for byte; a different library's "bicubic" or
"Lanczos" is not the same filter, and it shows in the top-1. Resizing a
4,000 px phone photo straight to 320 or 366 px produces aliasing neither
network saw in training — that is what step 1 of each recipe avoids.

## References and fusion

The encoder does not return species: it returns a direction in BioCLIP's
embedding space. Each species owns one or more reference vectors in the same
space — the centroid of its training photographs, plus separate centroids for
cultivated plants (`#captive`) and, from Pl@ntNet's potted-plant photographs,
potted ones (`#pot`), where there are enough of them: 1,013 species have one reference, 554 have two to five.

1. **Lookup.** For each species, the best cosine among its references — the
   best, not the sum, so a species with many views is not favoured.
2. **Softmax.** Those cosines times the temperature (100), softmaxed. This
   turns similarities into a distribution; it is not a calibration.
3. **Regroup Iris 9.** Its 1,569 outputs are mapped to the 1,567 species with
   `iris9`; the two pairs of synonyms add up.
4. **Fuse.** `p9^0.5 · p10^0.5`, with each probability floored at 10⁻⁶ so
   that one network's zero cannot veto the other, then renormalised.

A reference is a row, not a retrained output: adding one does not take
probability mass from the others the way a new softmax class does.

### The control check

`iris10.json` carries the first 16 values of the vector the export computed
on a fixed pattern, `(i · 7919 mod 1000) / 999` for each input value `i`.
Before trusting the encoder, run it on that pattern and compare: a corrupt,
truncated or mismatched file misses by far, float16 arithmetic on another CPU
by a few thousandths (tolerance 0.01). The published file matches to
5 × 10⁻⁷ under LiteRT. If it does not match, answer with Iris 9 alone.

## Context masks

`model.json` carries two lists of Iris 9 ids — 336 indoor species, 1,424
outdoor ones. A fused species belongs to a context as soon as one of its
names does. When you know where the plant lives, keep that context's species
and renormalise by their mass.

The mask is applied **after** the fusion, and that is exact: renormalising a
geometric mean over the mask equals the geometric mean of the two masked
distributions. On the benchmark, the mask is worth 1.7 points to the encoder
alone indoors.

**Do not use a mask as a hard filter.** A species outside the context should
be demoted, never deleted — a model that cannot say the right answer will say
a wrong one with confidence.

## When to trust an answer

The app accepts the fused answer alone only when

```
best score ≥ 0.85  and  best − second ≥ 0.25
```

shows the list above 0.25, and treats anything below 0.10 as no answer at all.
The threshold is in `iris10.json` (`accept_threshold`). It is higher than Iris
9's 0.70 because the fusion is more confident at equal score: kept at 0.70, it
would have affirmed 53 % of out-of-catalogue plants on a preliminary run,
against Iris 9's 42 %.

Indoor mask, benchmark above, margin 0.25:

| | accepted | precision when accepted | out-of-catalogue plants accepted |
|---|---|---|---|
| Iris 9 at 0.70 | 78.7 % | 0.918 | 41.6 % |
| **fusion at 0.85** | **86.4 %** | **0.946** | **36.6 %** |

More answers, more of them right, and fewer unknown plants affirmed. The last
column is measured on 2,000 photographs of plants that neither network can
name: every acceptance there is an error.

**Outdoors, the app never accepts a local answer**, and Iris 10 does not
change that. Under the outdoor mask the fusion at 0.85 accepts 39.6 % of
out-of-catalogue plants against Iris 9's 33.4 %, and a garden holds far more
species outside the catalogue than a flat. The ranked list is still better
outdoors (top-1 0.857 against 0.762); the final word goes to a remote
service.

The threshold was chosen on one half of the benchmark and confirmed on the
other. A threshold does not travel from one model to another: re-measure it
if you change the references, the weight or the masks.

## Speed

Measured on an iPhone 16 Pro, TensorFlow Lite 2.12, `invoke()` only, median
of 30 calls:

| | threads | median |
|---|---|---|
| Iris 10 encoder | 4 | 47.4 ms |
| Iris 9 | 2 | 8.1 ms |

The GPU delegates (Metal, Core ML) are slower for the encoder, not faster:
several of its operations are not delegated and the round trips cost more
than they save. Decoding and framing a 12-megapixel photograph costs about as
much as both networks; decode once and frame twice.

The file loads in TensorFlow Lite 2.12 and later, and in LiteRT.

## More than one photo

Averaging the distributions of two photographs of the same plant was worth
**+13.7 points of top-1** and three photographs **+22.4**, measured on Iris 7
(not re-measured on Iris 10). If your interface can ask for a second photo
when the first answer is uncertain, do it before anything else.

## Training data

### Iris 9 classifier

About 992,000 photographs of roughly 5,300 species from **GBIF**,
**iNaturalist** and **Wikimedia Commons**, with a hard licence filter applied
twice — once in the query, once on each individual image:

| Licence | Kept |
|---|---|
| CC0 1.0, Public Domain Mark | yes |
| CC BY 2.0 → 4.0 | yes |
| CC BY-SA | yes |
| CC BY-NC, ND, NC-SA, NC-ND | no |
| unknown, absent, proprietary, scraped | no |

Cleaning: readability, EXIF orientation, shortest side ≥ 320 px, aspect ratio
≤ 1:12, exact duplicates by SHA-256 and near-duplicates by 64-bit DCT
perceptual hash (Hamming ≤ 6) within a species. Splits are 80/10/10 **by
observation group**.

### Iris 10 encoder

A distilled student reads no label: it learns to reproduce the teacher's
vector for each image. Its training set is therefore the Iris 9 training split
plus openly licensed plant photographs from sources the classifier could not
use, **1,977,646 images per epoch**:

| Corpus | Images | What it brings |
|---|---|---|
| Iris image set, training split | 797,965 | the classifier's own data |
| iNaturalist plants (CC0 and CC BY only) | 596,518 | more photographs from the field |
| Pl@ntNet, indoor species | 265,894 | potted plants, the app's world and the thinnest part of the rest |
| Pl@ntNet-300K, licence-filtered, without its test split | 243,567 | user photographs taken outdoors |
| Pl@ntNet, potted plants of 186 species | 73,702 | potted plants |

The Pl@ntNet photographs are under CC BY-SA, collected with Pl@ntNet's written
permission. Every corpus was screened against the benchmark three ways — by
image id, by observation, and by perceptual hash — so that no benchmark photo
and no sister photo of the same plant entered training. Pl@ntNet-300K's own
test split was excluded.

The reference vectors are centroids computed by the encoder itself: of the
Iris image set's training split, and of the Pl@ntNet potted-plant photographs
for the `#pot` views.

### Per-image attribution is not shipped with this model

The collection pipeline writes an `ATTRIBUTIONS.md` and an `attributions.csv`
— author, licence and observation link for every kept image. **For the Iris 9
image set, those files were lost with the training machine.** What remains is
structural: the sources, the licence filter, and the collection code, which
rebuilds an equivalent manifest from scratch. For the iNaturalist corpus, the
photo id of every image is kept, which is enough to recover its author.

## Training procedure

### Iris 10 encoder

| | |
|---|---|
| Teacher | BioCLIP 2.5 (`imageomics/bioclip-2.5-vith14`), image encoder; its embeddings were cached once per image |
| Student | `fastvit_sa12` from `timm`, ImageNet-initialised, + a linear projector without bias to 1,024-d |
| Loss | cosine distance to the teacher's vector + InfoNCE (weight 0.2) |
| Schedule | 10 epochs, cosine learning-rate decay |
| Input | 320 px |
| Export | PyTorch → TFLite, float16 |

The contrastive term is what makes the space searchable: a cosine loss alone
converges while collapsing the vectors into a narrow cone, and retrieval among
thousands of references suffers. With it, the student agrees *less* with the
teacher on average and ranks species *better*.

The int8-weight export (12.2 MB) was measured and rejected: it lost 0.5 point
of top-1 indoors and its worst image fell to a cosine of 0.82 with the
training weights. The float16 export agrees with them to 0.99999 on average.

### Iris 9 classifier

MobileNetV3-Large, ImageNet backbone, top 100 layers unfrozen, global average
pooling → dropout 0.5 → softmax. 320 px, batch 64, mixed precision, 30 epochs
of fine-tuning on one RTX 2070 Super. The head was trained on every species
collected and re-sliced at export to the 1,569 the product serves.

## Limitations

- **It cannot say "not a plant."** All outputs are species. Under the indoor
  mask, the fusion at 0.85 accepts 36.6 % of photographs of plants outside
  the catalogue — confidently wrong. The benchmark has no photographs of
  non-plants (cats, furniture), so behaviour on those is not measured.
- **The references cover 1,567 species.** The encoder places any plant in
  BioCLIP's space, but only a species with a reference can be named. Adding
  one takes a centroid of its photographs, not retraining — and that
  centroid then needs its own measurement.
- **The student is not the teacher.** BioCLIP 2.5 is the ceiling; the
  encoder is a copy of it at a fraction of its size and loses images the
  teacher gets right.
- **The benchmark comes from the same collection as the training set**,
  split by observation and screened for duplicates, but drawn from the same
  sources. Accuracy on photographs from elsewhere may be lower.
- **Wild-plant photographs dominate the data**, overwhelmingly from Europe and
  North America. Potted indoor plants are better covered than in Iris 9, but
  remain the minority.
- **Cultivars are not distinguished.** A variegated *Monstera* is a
  *Monstera deliciosa*.
- **It is not a diagnostic tool.** Species identification is not edibility,
  toxicity, medicinal or legal advice, and a 0.99 score is not a
  determination. Anything that could harm a person or an animal needs a
  human expert.

## Intended use

Suggesting candidate species to a person who then chooses, on-device, offline —
in a plant app, a garden notebook, a museum kiosk, a field survey. The output
is a ranked list with scores, meant to be shown as such. The encoder's vector
can also serve for similarity search between plant photographs.

Out of scope: anything that acts on an identification without a human, and any
foraging, medical, veterinary or regulatory decision.

## Licence and attribution

The model files — `iris10.tflite`, `iris10-references.bin`, `iris10.json`,
`plants.tflite`, `labels.txt` and `model.json` — are released under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Commercial use and
derivatives are allowed, with attribution. The code in the GitHub repository
is Apache-2.0.

Suggested credit: *Iris 10 — brunopaiva15, CC BY 4.0*.

The teacher, BioCLIP 2.5, is the work of the Imageomics Institute; none of
its weights are included here, only a student trained to reproduce its
image embeddings.

The training images are not redistributed here, in any form a person can
look at. They belong to the naturalists who published them under CC0, CC BY
and CC BY-SA on GBIF, iNaturalist, Wikimedia Commons and Pl@ntNet. This model
exists because of them. The position taken when CC BY-SA images were let into
the training set is written down: a trained network is not an adaptation of
the photographs — it reproduces none of them, and never redistributes them.
That position has not been tested in court.

## Citation

```bibtex
@software{iris10_2026,
  title  = {Iris 10: an offline plant species identifier},
  author = {brunopaiva15},
  year   = {2026},
  url    = {https://huggingface.co/brunopaiva15/iris},
  note   = {FastViT-SA12 encoder distilled from BioCLIP 2.5, fused with a
            MobileNetV3-Large classifier; 1567 species; TensorFlow Lite}
}
```

## Version history

Each version is a tag of this repository: `v9` keeps pointing at Iris 9 alone.

| Version | What changed |
|---|---|
| **10** | a 1,024-d encoder distilled from BioCLIP 2.5, compared with reference vectors and fused with Iris 9; 1,567 species; accepts at 0.85 |
| 9 | one head, two context masks (indoor + outdoor) in a single file; 1,569 exposed classes; first full uninterrupted fine-tuning pass |
| Indoor | 336 indoor classes cut from an earlier head |
| 8 | 5,259 classes trained, 1,444 exposed — the version that proved width is paid for at the output, not in training |
| 7 | 320 px input, dropout 0.5, acceptance threshold back to 0.70 |
| 1–6 | MobileNetV3-Small, 78 → 1,445 classes |
