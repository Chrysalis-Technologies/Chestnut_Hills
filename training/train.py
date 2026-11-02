#!/usr/bin/env python3
"""Train the New York State tree classifier and export it for the Android app.

Modes
-----
  --mode features   Freeze an ImageNet backbone, cache its embeddings, and train a
                    classifier head on top. Fast (~30 min on a CPU) but a weak
                    baseline: ImageNet features alone don't separate tree species well.
  --mode partial    Also fine-tune the backbone's last stage. Early layers run once
                    and are cached, so this is practical on a CPU (~2 h). Much more
                    accurate than `features`.
  --mode finetune   Fine-tune the whole network end to end with augmentation.
                    Needs a GPU (a free Google Colab T4 is fine, ~1 h). Noticeably
                    more accurate; this is what the shipped model should use.

Input contract (must match the Android app, see TreeClassifier.kt)
------------------------------------------------------------------
  * RGB image, centre-cropped to a square, resized (bilinear) to SIZE x SIZE
  * float32 pixel values in [0, 255]; normalisation happens inside the model
  * output: softmax probabilities, one per line of labels.txt

Outputs (in --out-dir)
----------------------
  tree_model.tflite  labels.txt  model_info.json  eval_report.md  eval_report.json

Usage
-----
  python training/train.py --mode partial  --images data/images --out-dir build/model
  python training/train.py --mode finetune --images data/images --out-dir build/model
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import os
import sys
import tempfile
import time
from collections import Counter, defaultdict

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402
import tensorflow as tf  # noqa: E402
import keras  # noqa: E402
from keras import layers  # noqa: E402

AUTOTUNE = tf.data.AUTOTUNE

BACKBONES = {
    # name: (constructor, default input size)
    "efficientnetv2b0": (keras.applications.EfficientNetV2B0, 224),
    "efficientnetv2b1": (keras.applications.EfficientNetV2B1, 240),
    "efficientnetv2b2": (keras.applications.EfficientNetV2B2, 260),
    "mobilenetv3large": (keras.applications.MobileNetV3Large, 224),
}


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# --------------------------------------------------------------------------- data

def load_dataset(manifest: str, images: str, species_path: str, min_train: int):
    with open(species_path, encoding="utf-8") as f:
        species = json.load(f)
    order = [c["key"] for c in species["classes"]]

    opener = gzip.open if manifest.endswith(".gz") else open
    rows = []
    missing = 0
    with opener(manifest, "rt", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if r["class_key"] not in order:
                continue
            path = os.path.join(images, r["class_key"], f"{r['photo_id']}.jpg")
            if not os.path.exists(path):
                missing += 1
                continue
            rows.append((r["split"], r["class_key"], path))
    if missing:
        log(f"note: {missing:,} manifest photos not found on disk (skipped)")

    train_counts = Counter(k for s, k, _ in rows if s == "train")
    labels = [k for k in order if train_counts[k] >= min_train]
    dropped = [k for k in order if k not in labels]
    if dropped:
        log(f"WARNING: dropping classes with < {min_train} training photos: {', '.join(dropped)}")
    index = {k: i for i, k in enumerate(labels)}

    splits: dict[str, list[tuple[str, int]]] = {"train": [], "val": [], "test": []}
    for split, key, path in rows:
        if key in index:
            splits[split].append((path, index[key]))
    for s in splits:
        log(f"{s}: {len(splits[s]):,} photos")
    return species, labels, splits


def decode(path):
    data = tf.io.read_file(path)
    # INTEGER_ACCURATE matches Android's (and Pillow's) JPEG decoder; TensorFlow's default fast
    # decoder differs by a pixel level here and there, enough to flip borderline predictions.
    img = tf.io.decode_jpeg(data, channels=3, dct_method="INTEGER_ACCURATE",
                            try_recover_truncated=True, acceptable_fraction=0.5)
    return tf.cast(img, tf.float32)


def center_crop(img, size: int):
    """Square centre crop + bilinear resize. The Android app does exactly this."""
    shape = tf.shape(img)
    h, w = shape[0], shape[1]
    s = tf.minimum(h, w)
    img = tf.image.crop_to_bounding_box(img, (h - s) // 2, (w - s) // 2, s, s)
    return tf.image.resize(img, [size, size], method="bilinear", antialias=True)


def random_crop(img, size: int):
    begin, crop, _ = tf.image.sample_distorted_bounding_box(
        tf.shape(img),
        bounding_boxes=tf.constant([0.0, 0.0, 1.0, 1.0], shape=[1, 1, 4]),
        min_object_covered=0.25,
        aspect_ratio_range=(0.75, 1.33),
        area_range=(0.25, 1.0),
        max_attempts=20,
        use_image_if_no_bounding_boxes=True,
    )
    img = tf.slice(img, begin, crop)
    img = tf.image.resize(img, [size, size], method="bilinear", antialias=True)
    img = tf.image.random_flip_left_right(img)
    img = tf.image.random_brightness(img, 25.0)
    img = tf.image.random_contrast(img, 0.8, 1.2)
    img = tf.image.random_saturation(img, 0.8, 1.2)
    return tf.clip_by_value(img, 0.0, 255.0)


def make_ds(samples, size: int, augment: bool, batch: int, shuffle: bool, num_classes: int,
            weights: np.ndarray | None = None):
    paths = [p for p, _ in samples]
    labels = np.array([l for _, l in samples], dtype=np.int32)
    ds = tf.data.Dataset.from_tensor_slices((paths, labels))
    if shuffle:
        ds = ds.shuffle(len(paths), reshuffle_each_iteration=True)
    fn = random_crop if augment else center_crop

    def load(p, l):
        x = fn(decode(p), size)
        y = tf.one_hot(l, num_classes)
        if weights is None:
            return x, y
        return x, y, tf.gather(tf.constant(weights, tf.float32), l)

    ds = ds.map(load, num_parallel_calls=AUTOTUNE).ignore_errors()
    return ds.batch(batch).prefetch(AUTOTUNE)


def class_weights(samples, num_classes: int) -> np.ndarray:
    counts = np.bincount([l for _, l in samples], minlength=num_classes).astype(np.float64)
    w = np.sqrt(counts.mean() / np.maximum(counts, 1))
    return np.clip(w, 0.5, 3.0).astype(np.float32)


# -------------------------------------------------------------------------- model

def make_backbone(name: str, size: int, weights="imagenet"):
    ctor, _ = BACKBONES[name]
    return ctor(include_top=False, weights=weights, input_shape=(size, size, 3), pooling="avg",
                include_preprocessing=True)


def make_head(feat_dim: int, num_classes: int, hidden: int, dropout: float, name="head"):
    inp = keras.Input((feat_dim,))
    x = layers.Dropout(dropout)(inp)
    if hidden:
        x = layers.Dense(hidden, activation="relu")(x)
        x = layers.Dropout(dropout)(x)
    out = layers.Dense(num_classes, activation="softmax", dtype="float32",
                       kernel_regularizer=keras.regularizers.l2(1e-5))(x)
    return keras.Model(inp, out, name=name)


def assemble(backbone, head, size: int):
    inp = keras.Input((size, size, 3), name="image")
    feats = backbone(inp, training=False)  # keeps BatchNorm in inference mode while fine-tuning
    return keras.Model(inp, head(feats), name="nys_tree_classifier")


def compile_model(model, lr):
    model.compile(
        optimizer=keras.optimizers.Adam(lr),
        loss=keras.losses.CategoricalCrossentropy(label_smoothing=0.1),
        metrics=[keras.metrics.CategoricalAccuracy(name="acc"),
                 keras.metrics.TopKCategoricalAccuracy(k=3, name="top3")],
    )


# ----------------------------------------------------------------- features mode

def extract_features(backbone, samples, size, augment, batch, cache_path):
    paths = [p for p, _ in samples]
    digest = hashlib.sha1("\n".join(paths).encode()).hexdigest()
    if cache_path and os.path.exists(cache_path):
        z = np.load(cache_path)
        if str(z["digest"]) == digest:
            log(f"  using cached features {os.path.basename(cache_path)}")
            return z["x"], z["y"]

    infer = tf.function(lambda x: backbone(x, training=False), reduce_retracing=True)
    fn = random_crop if augment else center_crop
    ds = tf.data.Dataset.from_tensor_slices((paths, np.array([l for _, l in samples], np.int32)))
    ds = ds.map(lambda p, l: (fn(decode(p), size), l), num_parallel_calls=AUTOTUNE).ignore_errors()
    ds = ds.batch(batch).prefetch(AUTOTUNE)
    xs, ys, done, t0 = [], [], 0, time.time()
    for x, y in ds:
        xs.append(infer(x).numpy().astype(np.float32))
        ys.append(y.numpy())
        done += len(y)
        if len(xs) % 50 == 0:
            log(f"  {done:,}/{len(paths):,} images ({done / (time.time() - t0):.0f}/s)")
    x, y = np.concatenate(xs), np.concatenate(ys)
    if cache_path:
        np.savez(cache_path, x=x, y=y, digest=digest)
    return x, y


def train_features(args, labels, splits, size):
    n = len(labels)
    backbone = make_backbone(args.backbone, size)
    os.makedirs(args.work_dir, exist_ok=True)
    tag = f"{args.backbone}_{size}"

    log("extracting validation features")
    xv, yv = extract_features(backbone, splits["val"], size, False, args.batch,
                              os.path.join(args.work_dir, f"feat_{tag}_val.npz"))
    xtr, ytr = [], []
    for view in range(args.views):
        log(f"extracting training features, view {view + 1}/{args.views}"
            + (" (centre crop)" if view == 0 else " (augmented)"))
        x, y = extract_features(backbone, splits["train"], size, view > 0, args.batch,
                                os.path.join(args.work_dir, f"feat_{tag}_train_v{view}.npz"))
        xtr.append(x)
        ytr.append(y)
    xtr, ytr = np.concatenate(xtr), np.concatenate(ytr)

    w = class_weights(splits["train"], n)
    head = make_head(xtr.shape[1], n, args.head_hidden, args.dropout)
    compile_model(head, args.lr)
    head.fit(
        xtr, keras.utils.to_categorical(ytr, n), sample_weight=w[ytr],
        validation_data=(xv, keras.utils.to_categorical(yv, n)),
        epochs=args.epochs, batch_size=256, verbose=2,
        callbacks=[keras.callbacks.EarlyStopping(monitor="val_acc", mode="max", patience=8,
                                                 restore_best_weights=True)],
    )
    return assemble(backbone, head, size)


# ------------------------------------------------------------------ partial mode
# Fine-tune only the last stage of the backbone. The frozen early layers run once per
# photo and their output is cached on disk, so training is affordable on a CPU.

PARTIAL_CUT = {"efficientnetv2b0": "block5e_add"}


def extract_maps(trunk, samples, size, augment, batch, path):
    """Run the frozen trunk over the photos once; cache activations as float16 .npy."""
    paths = [p for p, _ in samples]
    digest = hashlib.sha1(("\n".join(paths) + f"|{augment}|{size}").encode()).hexdigest()
    meta_path = path + ".json"
    if os.path.exists(meta_path):
        with open(meta_path) as f:
            meta = json.load(f)
        if meta.get("digest") == digest:
            log(f"  using cached activations {os.path.basename(path)}")
            return np.load(path, mmap_mode="r")[: meta["n"]], np.load(path + ".labels.npy")

    infer = tf.function(lambda x: trunk(x, training=False), reduce_retracing=True)
    fn = random_crop if augment else center_crop
    ds = tf.data.Dataset.from_tensor_slices((paths, np.array([l for _, l in samples], np.int32)))
    ds = ds.map(lambda p, l: (fn(decode(p), size), l), num_parallel_calls=AUTOTUNE).ignore_errors()
    ds = ds.batch(batch).prefetch(AUTOTUNE)
    shape = (len(paths),) + tuple(trunk.output.shape[1:])
    out = np.lib.format.open_memmap(path, mode="w+", dtype=np.float16, shape=shape)
    ys, n, t0 = [], 0, time.time()
    for x, y in ds:
        a = infer(x).numpy()
        out[n:n + len(a)] = a.astype(np.float16)
        ys.append(y.numpy())
        n += len(a)
        if (n // batch) % 50 == 0:
            log(f"  {n:,}/{len(paths):,} images ({n / (time.time() - t0):.0f}/s)")
    out.flush()
    del out
    labels = np.concatenate(ys)
    np.save(path + ".labels.npy", labels)
    with open(meta_path, "w") as f:
        json.dump({"digest": digest, "n": n}, f)
    return np.load(path, mmap_mode="r")[:n], labels


def map_batches(views, labels, num_classes, batch, shuffle, weights=None, seed=0):
    """tf.data over cached activations; each epoch uses one random view per photo."""
    n = len(labels)
    shape = views[0].shape[1:]
    rng = np.random.default_rng(seed)

    def gen():
        order = rng.permutation(n) if shuffle else np.arange(n)
        for i in range(0, n, batch):
            idx = np.sort(order[i:i + batch])
            if len(views) > 1:
                pick = rng.integers(0, len(views), len(idx))
                x = np.empty((len(idx),) + shape, np.float32)
                for v, arr in enumerate(views):
                    m = pick == v
                    if m.any():
                        x[m] = arr[idx[m]]
            else:
                x = views[0][idx].astype(np.float32)
            y = np.eye(num_classes, dtype=np.float32)[labels[idx]]
            if weights is None:
                yield x, y
            else:
                yield x, y, weights[labels[idx]]

    sig = [tf.TensorSpec((None,) + shape, tf.float32), tf.TensorSpec((None, num_classes), tf.float32)]
    if weights is not None:
        sig.append(tf.TensorSpec((None,), tf.float32))
    return tf.data.Dataset.from_generator(gen, output_signature=tuple(sig)).prefetch(4)


def train_partial(args, labels, splits, size):
    n = len(labels)
    cut_name = args.cut_layer or PARTIAL_CUT.get(args.backbone)
    if not cut_name:
        raise SystemExit(f"--mode partial needs --cut-layer for {args.backbone}")
    backbone = make_backbone(args.backbone, size)
    cut = backbone.get_layer(cut_name)
    trunk = keras.Model(backbone.input, cut.output)
    tail = keras.Model(cut.output, backbone.output)  # shares its layers with `backbone`
    log(f"fine-tuning layers after {cut_name}: {tail.count_params():,} of "
        f"{backbone.count_params():,} backbone parameters")
    os.makedirs(args.work_dir, exist_ok=True)
    tag = f"{args.backbone}_{size}_{cut_name}"

    log("caching validation activations")
    xv, yv = extract_maps(trunk, splits["val"], size, False, args.batch,
                          os.path.join(args.work_dir, f"maps_{tag}_val.npy"))
    views, ytr = [], None
    for view in range(args.views):
        log(f"caching training activations, view {view + 1}/{args.views}"
            + (" (centre crop)" if view == 0 else " (augmented)"))
        x, y = extract_maps(trunk, splits["train"], size, view > 0, args.batch,
                            os.path.join(args.work_dir, f"maps_{tag}_train_v{view}.npy"))
        if ytr is None:
            ytr = y
        elif len(y) != len(ytr) or not np.array_equal(y, ytr):
            log("WARNING: views disagree (unreadable photos?); using the first view only")
            break
        views.append(x)

    w = class_weights(splits["train"], n)
    head = make_head(backbone.output_shape[-1], n, args.head_hidden, args.dropout)
    inp = keras.Input(xv.shape[1:])
    model = keras.Model(inp, head(tail(inp, training=False)))
    train_ds = map_batches(views, ytr, n, args.batch, True, w, seed=1)
    val_ds = map_batches([xv], yv, n, 256, False)
    steps = -(-len(ytr) // args.batch)

    log("stage 1: one epoch training the classifier head only")
    tail.trainable = False
    compile_model(model, 1e-3)
    model.fit(train_ds, validation_data=val_ds, epochs=1, verbose=2)

    log(f"stage 2: fine-tuning the last stage + head for up to {args.epochs} epochs")
    tail.trainable = True
    ckpt = os.path.join(args.work_dir, "partial_best.weights.h5")
    compile_model(model, keras.optimizers.schedules.CosineDecay(args.lr, args.epochs * steps, alpha=0.05))
    model.fit(
        train_ds, validation_data=val_ds, epochs=args.epochs, verbose=2,
        callbacks=[
            keras.callbacks.ModelCheckpoint(ckpt, monitor="val_acc", mode="max", save_best_only=True,
                                            save_weights_only=True),
            keras.callbacks.EarlyStopping(monitor="val_acc", mode="max", patience=2),
        ],
    )
    model.load_weights(ckpt)  # also updates `backbone`, which shares the tail's layers
    return assemble(backbone, head, size)


# ----------------------------------------------------------------- finetune mode

def train_finetune(args, labels, splits, size):
    n = len(labels)
    if tf.config.list_physical_devices("GPU"):
        keras.mixed_precision.set_global_policy("mixed_float16")
        log("GPU found: using mixed precision")
    else:
        log("WARNING: no GPU found; fine-tuning on CPU will be very slow")

    backbone = make_backbone(args.backbone, size)
    head = make_head(backbone.output_shape[-1], n, args.head_hidden, args.dropout)
    model = assemble(backbone, head, size)
    w = class_weights(splits["train"], n)
    train_ds = make_ds(splits["train"], size, True, args.batch, True, n, w)
    val_ds = make_ds(splits["val"], size, False, args.batch, False, n)
    ckpt = os.path.join(args.work_dir, "finetune_best.weights.h5")
    os.makedirs(args.work_dir, exist_ok=True)

    log(f"stage 1: training the head for {args.head_epochs} epochs (backbone frozen)")
    backbone.trainable = False
    compile_model(model, 1e-3)
    model.fit(train_ds, validation_data=val_ds, epochs=args.head_epochs, verbose=2)

    log(f"stage 2: fine-tuning everything for up to {args.epochs} epochs")
    backbone.trainable = True
    steps = args.epochs * max(1, len(splits["train"]) // args.batch)
    compile_model(model, keras.optimizers.schedules.CosineDecay(args.lr, steps, alpha=0.02))
    model.fit(
        train_ds, validation_data=val_ds, epochs=args.epochs, verbose=2,
        callbacks=[
            keras.callbacks.ModelCheckpoint(ckpt, monitor="val_acc", mode="max", save_best_only=True,
                                            save_weights_only=True),
            keras.callbacks.EarlyStopping(monitor="val_acc", mode="max", patience=4),
        ],
    )
    model.load_weights(ckpt)

    # Rebuild in plain float32 so the exported model has no mixed-precision casts.
    keras.mixed_precision.set_global_policy("float32")
    b32 = make_backbone(args.backbone, size, weights=None)
    h32 = make_head(b32.output_shape[-1], n, args.head_hidden, args.dropout)
    m32 = assemble(b32, h32, size)
    m32.set_weights(model.get_weights())
    return m32


# ------------------------------------------------------------- evaluate + export

def predict(model, samples, size, batch, tta: bool):
    ds = make_ds(samples, size, False, batch, False, 1)
    probs, ys = [], []
    infer = tf.function(lambda x: model(x, training=False), reduce_retracing=True)
    for x, _ in ds:
        p = infer(x).numpy()
        if tta:
            p = (p + infer(tf.image.flip_left_right(x)).numpy()) / 2
        probs.append(p)
    labels = np.array([l for _, l in samples])
    probs = np.concatenate(probs)
    if len(probs) != len(labels):  # a few unreadable files were skipped
        raise SystemExit("evaluation set has unreadable images; re-run download_images.py")
    return probs, labels


def evaluate(model, samples, labels, size, batch, species):
    probs, y = predict(model, samples, size, batch, tta=True)
    top = np.argsort(-probs, axis=1)
    top1 = float(np.mean(top[:, 0] == y))
    top3 = float(np.mean(np.any(top[:, :3] == y[:, None], axis=1)))
    names = {c["key"]: c["common_name"] for c in species["classes"]}
    per_class = {}
    confusions = Counter()
    for i, key in enumerate(labels):
        m = y == i
        if m.sum():
            per_class[key] = {"n": int(m.sum()), "top1": float(np.mean(top[m, 0] == i)),
                              "top3": float(np.mean(np.any(top[m, :3] == i, axis=1)))}
    for t, p in zip(y, top[:, 0]):
        if t != p:
            confusions[(labels[t], labels[p])] += 1
    conf = probs[np.arange(len(y)), top[:, 0]]
    calib = {}
    for thr in (0.3, 0.5, 0.7, 0.9):
        m = conf >= thr
        calib[str(thr)] = {"coverage": float(m.mean()),
                           "top1_when_confident": float(np.mean(top[m, 0] == y[m])) if m.any() else None}
    report = {
        "n": int(len(y)), "top1": top1, "top3": top3, "per_class": per_class,
        "confidence_thresholds": calib,
        "most_confused": [{"true": a, "predicted": b, "count": c} for (a, b), c in confusions.most_common(25)],
    }
    lines = [
        "# Test-set evaluation", "",
        f"Photos: {len(y):,} · **Top-1: {top1:.1%}** · **Top-3: {top3:.1%}**", "",
        "## How often the top answer is right at each confidence level", "",
        "| Confidence ≥ | Share of photos | Top-1 accuracy |", "|---|---|---|",
    ]
    for thr, v in calib.items():
        acc = f"{v['top1_when_confident']:.1%}" if v["top1_when_confident"] is not None else "–"
        lines.append(f"| {float(thr):.0%} | {v['coverage']:.1%} | {acc} |")
    lines += ["", "## Per class (worst first)", "", "| Class | Photos | Top-1 | Top-3 |", "|---|---|---|---|"]
    for key, v in sorted(per_class.items(), key=lambda kv: kv[1]["top1"]):
        lines.append(f"| {names.get(key, key)} | {v['n']} | {v['top1']:.0%} | {v['top3']:.0%} |")
    lines += ["", "## Most common mix-ups", "", "| Actual | Predicted as | Count |", "|---|---|---|"]
    for item in report["most_confused"]:
        lines.append(f"| {names.get(item['true'])} | {names.get(item['predicted'])} | {item['count']} |")
    return report, "\n".join(lines) + "\n"


def export_tflite(model, path: str, quantize: str) -> None:
    with tempfile.TemporaryDirectory() as d:
        model.export(d, format="tf_saved_model", verbose=False)
        conv = tf.lite.TFLiteConverter.from_saved_model(d)
        if quantize == "fp16":
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
            conv.target_spec.supported_types = [tf.float16]
        elif quantize == "dynamic":
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
        data = conv.convert()
    with open(path, "wb") as f:
        f.write(data)
    log(f"wrote {path} ({len(data) / 1e6:.1f} MB)")


def check_tflite(path: str, model, samples, size: int, n: int = 64) -> float:
    """Return top-1 agreement between the TFLite file and the Keras model."""
    try:  # newer TensorFlow releases drop tf.lite.Interpreter in favour of LiteRT
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        Interpreter = tf.lite.Interpreter
    interp = Interpreter(model_path=path)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    agree = 0
    xs = [center_crop(decode(p), size).numpy() for p, _ in samples[:n]]
    for x in xs:
        interp.set_tensor(inp["index"], x[None].astype(np.float32))
        interp.invoke()
        a = int(np.argmax(interp.get_tensor(out["index"])[0]))
        b = int(np.argmax(model(x[None], training=False).numpy()[0]))
        agree += a == b
    return agree / max(len(xs), 1)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=["features", "partial", "finetune"], default="partial")
    ap.add_argument("--manifest", default="training/data/manifest.csv.gz")
    ap.add_argument("--species", default="species/nys_trees.json")
    ap.add_argument("--images", default="data/images")
    ap.add_argument("--out-dir", default="build/model")
    ap.add_argument("--work-dir", default="build/work", help="feature caches and checkpoints")
    ap.add_argument("--backbone", choices=sorted(BACKBONES), default="efficientnetv2b0")
    ap.add_argument("--size", type=int, default=0, help="input size (default: backbone's native size)")
    ap.add_argument("--epochs", type=int, default=0, help="default: 80 features / 5 partial / 15 finetune")
    ap.add_argument("--head-epochs", type=int, default=2, help="finetune: epochs with frozen backbone first")
    ap.add_argument("--lr", type=float, default=0.0, help="default: 1e-3 features / 2e-4 partial / 3e-4 finetune")
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--views", type=int, default=2,
                    help="features/partial: cached views per training photo (1 = centre crop only)")
    ap.add_argument("--cut-layer", default="", help="partial: fine-tune every layer after this one")
    ap.add_argument("--head-hidden", type=int, default=0, help="hidden units in the classifier head (0 = linear)")
    ap.add_argument("--dropout", type=float, default=0.3)
    ap.add_argument("--min-train", type=int, default=30, help="drop classes with fewer training photos")
    ap.add_argument("--quantize", choices=["fp16", "dynamic", "none"], default="fp16")
    args = ap.parse_args()

    size = args.size or BACKBONES[args.backbone][1]
    args.epochs = args.epochs or {"features": 80, "partial": 5, "finetune": 15}[args.mode]
    args.lr = args.lr or {"features": 1e-3, "partial": 2e-4, "finetune": 3e-4}[args.mode]
    keras.utils.set_random_seed(42)

    species, labels, splits = load_dataset(args.manifest, args.images, args.species, args.min_train)
    if not splits["train"] or not splits["val"]:
        raise SystemExit("no training data found: run build_manifest.py and download_images.py first")

    t0 = time.time()
    trainer = {"features": train_features, "partial": train_partial, "finetune": train_finetune}[args.mode]
    model = trainer(args, labels, splits, size)
    log(f"training finished in {(time.time() - t0) / 60:.0f} min")

    os.makedirs(args.out_dir, exist_ok=True)
    test = splits["test"] or splits["val"]
    log(f"evaluating on {len(test):,} held-out photos")
    report, md = evaluate(model, test, labels, size, args.batch, species)
    log(f"test top-1 {report['top1']:.1%}, top-3 {report['top3']:.1%}")

    tfl = os.path.join(args.out_dir, "tree_model.tflite")
    export_tflite(model, tfl, args.quantize)
    agreement = check_tflite(tfl, model, test, size)
    log(f"TFLite vs Keras top-1 agreement on sample: {agreement:.0%}")
    if agreement < 0.95:
        sys.exit("TFLite output does not match the Keras model; not exporting")

    with open(os.path.join(args.out_dir, "labels.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(labels) + "\n")
    info = {
        "format_version": 1,
        "model_version": time.strftime("%Y%m%d-%H%M"),
        "mode": args.mode,
        "backbone": args.backbone,
        "input_size": size,
        "input_range": [0, 255],
        "input_layout": "NHWC RGB, centre square crop, bilinear resize",
        "num_classes": len(labels),
        "test_top1": round(report["top1"], 4),
        "test_top3": round(report["top3"], 4),
        "test_photos": report["n"],
        "train_photos": len(splits["train"]),
        "quantization": args.quantize,
        "trained_on": "iNaturalist Open Dataset research-grade photos (CC0 / CC-BY / CC-BY-NC)",
    }
    with open(os.path.join(args.out_dir, "model_info.json"), "w") as f:
        json.dump(info, f, indent=1)
    with open(os.path.join(args.out_dir, "eval_report.json"), "w") as f:
        json.dump(report, f, indent=1)
    with open(os.path.join(args.out_dir, "eval_report.md"), "w") as f:
        f.write(md)
    log(f"done: copy {args.out_dir}/tree_model.tflite, labels.txt and model_info.json "
        "into android/app/src/main/assets/")


if __name__ == "__main__":
    main()
