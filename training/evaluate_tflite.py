#!/usr/bin/env python3
"""Evaluate an exported tree_model.tflite exactly the way the Android app runs it.

Uses a Python port of the app's preprocessing (Images.centerSquarePixels: centre square
crop + anti-aliased bilinear resize) and the app's mirror averaging, on every test photo
in the manifest. Use it to check a new model before shipping and to choose the confidence
cut-offs in ResultScreen.kt.

Usage:
  python training/evaluate_tflite.py --model-dir android/app/src/main/assets --images data/images
"""
from __future__ import annotations

import argparse
import csv
import gzip
import os

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    import tensorflow as tf
    Interpreter = tf.lite.Interpreter

_matrices: dict[tuple[int, int], np.ndarray] = {}


def resize_matrix(src: int, dst: int) -> np.ndarray:
    """Same weights as ResizeKernel in Images.kt (= tf.image.resize bilinear, antialias=True)."""
    if (src, dst) not in _matrices:
        scale = src / dst
        support = max(scale, 1.0)
        m = np.zeros((dst, src), np.float32)
        for i in range(dst):
            center = (i + 0.5) * scale
            lo = max(0, int(np.floor(center - support)))
            hi = min(src, int(np.ceil(center + support)))
            w = np.maximum(0.0, 1.0 - np.abs((np.arange(lo, hi) + 0.5 - center) / support))
            m[i, lo:hi] = w / w.sum()
        _matrices[(src, dst)] = m
    return _matrices[(src, dst)]


def app_preprocess(path: str, size: int) -> np.ndarray:
    a = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)
    h, w = a.shape[:2]
    s = min(h, w)
    a = a[(h - s) // 2:(h - s) // 2 + s, (w - s) // 2:(w - s) // 2 + s]
    m = resize_matrix(s, size)
    return np.stack([m @ a[:, :, c] @ m.T for c in range(3)], axis=-1)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", default="android/app/src/main/assets")
    ap.add_argument("--images", default="data/images")
    ap.add_argument("--manifest", default="training/data/manifest.csv.gz")
    ap.add_argument("--split", default="test")
    ap.add_argument("--limit", type=int, default=0, help="evaluate a random subset of this size")
    args = ap.parse_args()

    labels = [l.strip() for l in open(os.path.join(args.model_dir, "labels.txt")) if l.strip()]
    index = {k: i for i, k in enumerate(labels)}
    interp = Interpreter(model_path=os.path.join(args.model_dir, "tree_model.tflite"), num_threads=4)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    size = int(inp["shape"][1])

    def predict(x: np.ndarray) -> np.ndarray:
        probs = []
        for arr in (x, x[:, ::-1]):
            interp.set_tensor(inp["index"], np.ascontiguousarray(arr[None], dtype=np.float32))
            interp.invoke()
            probs.append(interp.get_tensor(out["index"])[0].copy())
        return (probs[0] + probs[1]) / 2

    rows = []
    with gzip.open(args.manifest, "rt", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            path = os.path.join(args.images, r["class_key"], f"{r['photo_id']}.jpg")
            if r["split"] == args.split and r["class_key"] in index and os.path.exists(path):
                rows.append((path, index[r["class_key"]]))
    if args.limit:
        rng = np.random.default_rng(0)
        rows = [rows[i] for i in rng.permutation(len(rows))[: args.limit]]
    if not rows:
        raise SystemExit("no photos found; check --images and --split")

    probs = np.stack([predict(app_preprocess(p, size)) for p, _ in rows])
    y = np.array([l for _, l in rows])
    top = np.argsort(-probs, axis=1)
    conf = probs[np.arange(len(y)), top[:, 0]]
    hit = lambda k, m=slice(None): np.mean(np.any(top[m, :k] == y[m, None], axis=1))  # noqa: E731

    print(f"{len(y):,} {args.split} photos: top-1 {hit(1):.1%}, top-3 {hit(3):.1%}, top-5 {hit(5):.1%}")
    print("\nConfidence band   photos   top-1   top-3")
    for lo, hi in ((0.0, 0.2), (0.2, 0.4), (0.4, 0.6), (0.6, 0.8), (0.8, 1.01)):
        m = (conf >= lo) & (conf < hi)
        if m.any():
            print(f"  {lo:.0%}-{min(hi, 1):.0%}   {m.mean():8.1%}  {hit(1, m):6.1%}  {hit(3, m):6.1%}")
    if "_other" in index:
        other = index["_other"]
        trees, plants = y != other, y == other
        print(f"\nTree photos wrongly called 'not a tree in this guide': {np.mean(top[trees, 0] == other):.1%}")
        if plants.any():
            print(f"Other-plant photos correctly flagged: {np.mean(top[plants, 0] == other):.1%}")


if __name__ == "__main__":
    main()
