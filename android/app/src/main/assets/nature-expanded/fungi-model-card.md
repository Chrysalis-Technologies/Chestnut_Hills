---
license: other
license_name: dinov3-license
license_link: https://github.com/facebookresearch/dinov3/blob/main/LICENSE.md
base_model: timm/vit_small_patch16_dinov3.lvd1689m
pipeline_tag: image-classification
library_name: timm
tags:
  - image-classification
  - fine-grained-classification
  - fungi
  - mushrooms
  - dinov3
  - vit
  - tflite
  - litert
  - android
  - biodiversity
  - arxiv:2408.13632
metrics:
  - accuracy
  - f1
model-index:
  - name: fungitastic-dinov3-vits16-384
    results:
      - task:
          type: image-classification
          name: Fine-grained fungi species classification
        dataset:
          name: FungiTastic (full, closed-set)
          type: fungitastic
          split: test
        metrics:
          - type: accuracy
            name: Top-1
            value: 0.7263
          - type: accuracy
            name: Top-3
            value: 0.8484
          - type: f1
            name: Macro F1
            value: 0.4593
---

# FungiTastic DINOv3 ViT-S/16 @ 384 px

A fine-grained fungi classifier over **2,827 species**, built by fine-tuning the DINOv3 ViT-S/16 backbone on the
**FungiTastic** closed-set benchmark. It ships as a PyTorch checkpoint and as a TensorFlow Lite / LiteRT model whose
graph includes preprocessing, so mobile apps can feed raw camera pixels.

At 22.7M parameters it reaches **72.6 % top-1** on the full test set, within 2.7 points of the dataset paper's best
baseline (BEiT-Base/16 at 384 px, 75.3 %), while being about four times smaller, and it beats that baseline's macro
F1 (45.9 vs 44.5).

## Results

Full FungiTastic closed-set **test** split, 91,832 images, 2,336 species present, single image, no test-time augmentation.

| Metric | Value |
|---|---|
| Top-1 | 0.7263 |
| Top-3 | 0.8484 |
| Macro F1 | 0.4593 |
| Cross-entropy loss | 1.432 |

Validation (fixed 15,000-image subset) at the selected epoch: top-1 0.7327, top-3 0.855, macro F1 0.482.

Accuracy depends strongly on how many training images a species has, which is the main limitation of this model:

| Training images per species | Species | Test top-1 |
|---|---|---|
| < 20 | 325 | 0.272 |
| 20–50 | 594 | 0.404 |
| 50–100 | 439 | 0.534 |
| 100–500 | 749 | 0.711 |
| > 500 | 227 | 0.829 |

Example of a well-represented species, *Imleria badia* (bay bolete, 1,437 training images, 417 test images):
precision 0.882, recall 0.861, F1 0.871.

## Files

| File | Description |
|---|---|
| `mushroom_model_dynamic.tflite` | TFLite / LiteRT model, 91 MB float32, input `[1,384,384,3]`, output `[1,2827]` |
| `mushroom_model_fp16.tflite` | Same graph with float16 weights (+ `DEQUANTIZE`), 46 MB; logits within 0.01 of the float32 file |
| `labels.txt` | 2,827 species names, one per line, index-aligned with the output |
| `export_info.json` | Export metadata: source checkpoint, converter versions, tensor shapes, fp16-safety calibration scales |
| `converter/` | The PyTorch → TFLite converter that produced these files: source snapshot, pinned `requirements.txt`, `config.yaml`, `calibration.json`, `convert.sh` |

The PyTorch checkpoint (`best.pt`, 91 MB) stores `model_state`, `model_name`, `img_size`, `num_classes` and `labels`.

## Usage

### PyTorch / timm

```python
import timm, torch
from PIL import Image

ckpt = torch.load("best.pt", map_location="cpu", weights_only=False)
labels = ckpt["labels"]
model = timm.create_model(ckpt["model_name"], pretrained=False,
                          num_classes=ckpt["num_classes"], img_size=ckpt["img_size"])
model.load_state_dict(ckpt["model_state"])
model.eval()

cfg = timm.data.resolve_model_data_config(model)
tf = timm.data.create_transform(**cfg, is_training=False)   # resize 439, center-crop 384, ImageNet mean/std
x = tf(Image.open("mushroom.jpg").convert("RGB")).unsqueeze(0)

with torch.no_grad():
    probs = model(x).softmax(-1)[0]
top = probs.topk(3)
print([(labels[i], round(p.item(), 3)) for p, i in zip(top.values, top.indices)])
```

### TFLite / LiteRT

Preprocessing is **baked into the graph**. Feed raw pixels in `[0, 255]`, NHWC, float32; the model does the
normalisation internally. The output is **logits**, so apply softmax yourself.

```python
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

it = Interpreter(model_path="mushroom_model_dynamic.tflite", num_threads=4)
it.allocate_tensors()
inp, out = it.get_input_details()[0], it.get_output_details()[0]

img = Image.open("mushroom.jpg").convert("RGB").resize((384, 384), Image.BILINEAR)
it.set_tensor(inp["index"], np.asarray(img, dtype=np.float32)[None])   # 0..255, no normalisation
it.invoke()

logits = it.get_tensor(out["index"])[0]
probs = np.exp(logits - logits.max()); probs /= probs.sum()
labels = open("labels.txt").read().splitlines()
print(sorted(zip(probs, labels))[-3:][::-1])
```

On Android, resize the bitmap to 384×384 and pass the float buffer straight to the interpreter. Measured latency on an
Apple M2 CPU with XNNPACK and 4 threads is about 0.13 s per image on an idle machine.

### Android GPU (LiteRT `CompiledModel`, `Accelerator.GPU`)

The graph is prepared for the LiteRT GPU accelerator: no tensor above 4-D, no `BROADCAST_TO`, uniform
`FULLY_CONNECTED` ranks, and LayerNorm/softmax calibrated so **fp16 activations cannot overflow**
(`export_info.json: fp16_safe`). Measured on a Pixel 7 Pro (Mali-G710, LiteRT 2.2, medians of 10 runs):

| Precision | Per image | Max logit difference vs CPU |
|---|---|---|
| `FP32` | 209 ms | 2e-5 |
| `FP16_WITH_FP32_ACCUM` (recommended) | 126 ms | 0.012 |
| `FP16` | 118 ms | 0.11 |

The stock timm graph, without these rewrites, fails to compile on this GPU and returns NaN in fp16.

## Reproducing the conversion

`converter/` holds the exact converter source and versions. With the checkpoint next to it:

```bash
cd converter && pip install -r requirements.txt && ./convert.sh ../best.pt ../reproduced
```

This regenerates both `.tflite` files byte-for-byte (the fp16-safety scales are replayed from `calibration.json`, so
the dataset is not needed). The rewrites applied at export, all exact in float32: 4-D-only attention, constant RoPE
tables and prefix tokens, LayerNorm and softmax prescales for fp16, LayerNorm/layer-scale/attention-scale folding,
`keep_num_dims` on every `FULLY_CONNECTED`, and float16 weight compression for the second file. See
`converter/fungi_dinov3/export.py`.

## Training

Backbone: [`timm/vit_small_patch16_dinov3.lvd1689m`](https://huggingface.co/timm/vit_small_patch16_dinov3.lvd1689m)
(DINOv3, 21.6M params, self-supervised on LVD-1689M).

The model was trained as a resolution ladder, each stage warm-started from the previous best checkpoint:

| Stage | Data | Resolution | Result (val top-1) |
|---|---|---|---|
| 1 | FungiTastic-Mini, 214 species | 224 px | 0.731 |
| 2 | FungiTastic full, 2,827 species (backbone reused, new head) | 320 px | 0.719 |
| 3 | FungiTastic full | 384 px | **0.733** |

Final stage: 4 epochs over 433,702 training images, AdamW, head LR 2.5e-4, backbone LR 1e-5 with layer-wise decay
0.8, cosine schedule with warmup, weight decay 0.05, label smoothing 0.1, gradient clipping 1.0, batch 16 with
gradient accumulation 2, fp32 on Apple Silicon (MPS). Augmentation: random resized crop (scale 0.5–1.0), horizontal
flip, mild brightness/contrast jitter, Mixup 0.2 / CutMix 1.0 on half the batches. Class-balanced sampling with
sqrt-inverse frequency weights for the long tail, and an exponential moving average of the weights (decay 0.9999)
evaluated each epoch; the released checkpoint is the better of the raw and EMA weights.

Training took about 19 hours per epoch on an Apple M2 laptop.

## Intended use and limitations

Intended for assisted identification of fungi in Northern and Central Europe, and as a starting point for further
fine-tuning.

- **Not safe for deciding what to eat.** Many deadly species have edible look-alikes, and the model is wrong on
  roughly one image in four. Never use it to judge edibility. Consult a qualified mycologist.
- **Long tail is weak.** 325 species with fewer than 20 training images reach only 27 % top-1, and 302 species in the
  test set are never predicted correctly. They account for 1.4 % of test images.
- **Geographic and seasonal bias.** The data comes from the Atlas of Danish Fungi, so species frequencies reflect
  Denmark. Accuracy elsewhere, especially outside Europe, will be lower.
- **Single-image predictions.** Accuracy improves when several photos of the same specimen are averaged.
- **Labels are binomials** (`genus species`). Species sharing a binomial under different authorities were merged, so
  the label space has 2,827 classes rather than the benchmark's 2,829.
- **Input must be 384×384, batch 1.** The graph has static shapes; resize before inference. (The converter can
  export other static batch sizes; batching did not improve per-image latency on a Pixel 7 Pro GPU.)

## Licensing

- **Weights** derive from DINOv3, which is covered by the [DINOv3 License](https://github.com/facebookresearch/dinov3/blob/main/LICENSE.md).
  Distribute a copy of that license with the model and pass it on to downstream users; its use restrictions apply.
- **Training data**, FungiTastic, is published under
  [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). Whether trained weights count as an adaptation
  of the images is legally unsettled; the conservative reading is that this model inherits the non-commercial and
  share-alike terms. For commercial use, obtain permission from the dataset authors.

## Citation

```bibtex
@InProceedings{Picek_2025_CVPR,
  author    = {Picek, Lukas and Janouskova, Klara and Cermak, Vojtech and Matas, Jiri},
  title     = {FungiTastic: A Multi-Modal Dataset and Benchmark for Image Categorization},
  booktitle = {Proceedings of the Computer Vision and Pattern Recognition Conference (CVPR) Workshops},
  year      = {2025},
  pages     = {2046--2056}
}

@article{simeoni2025dinov3,
  title   = {DINOv3},
  author  = {Sim{\'e}oni, Oriane and others},
  journal = {arXiv preprint arXiv:2508.10104},
  year    = {2025}
}
```
