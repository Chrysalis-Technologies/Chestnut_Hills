#!/usr/bin/env python3
"""Download the photos listed in a manifest from the iNaturalist Open Dataset.

Photos are saved as <out-dir>/<class_key>/<photo_id>.jpg. Files that already
exist are skipped, so the script can be re-run to resume. Non-JPEG photos are
converted to JPEG so the training pipeline only has to handle one format.

Usage:
  python training/download_images.py \
      --manifest training/data/manifest.csv.gz --out-dir data/images
"""
from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

PHOTO_URL = "https://inaturalist-open-data.s3.amazonaws.com/photos/{photo_id}/{size}.{ext}"
JPEG_MAGIC = b"\xff\xd8\xff"


def read_manifest(path: str):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", newline="") as f:
        yield from csv.DictReader(f)


def fetch(url: str, retries: int = 4) -> bytes:
    delay = 2.0
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "nys-tree-id-training/1.0"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (403, 404):
                raise
            err = e
        except Exception as e:  # network hiccup
            err = e
        time.sleep(delay + random.random())
        delay *= 2
    raise err  # type: ignore[possibly-undefined]


def to_jpeg(data: bytes) -> bytes:
    """Return an RGB/greyscale JPEG (what TensorFlow's decoder handles)."""
    try:
        from PIL import Image
    except ImportError:  # without Pillow, keep JPEGs as-is and reject the rest
        if data.startswith(JPEG_MAGIC):
            return data
        raise ValueError("non-JPEG photo and Pillow is not installed")

    img = Image.open(io.BytesIO(data))  # reads the header only
    if data.startswith(JPEG_MAGIC) and img.mode in ("RGB", "L"):
        return data
    img = img.convert("RGB")
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=92)
    return buf.getvalue()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", default="training/data/manifest.csv.gz")
    ap.add_argument("--out-dir", default="data/images")
    ap.add_argument("--size", default="medium", choices=["small", "medium", "large"],
                    help="medium = up to 500 px on the long side (plenty for a 224-260 px model)")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--max-per-class", type=int, default=0, help="only download the first N photos per class")
    args = ap.parse_args()

    rows = list(read_manifest(args.manifest))
    if args.max_per_class:
        per = defaultdict(int)
        kept = []
        for r in rows:
            if per[r["class_key"]] < args.max_per_class:
                per[r["class_key"]] += 1
                kept.append(r)
        rows = kept

    todo = []
    for r in rows:
        dest = os.path.join(args.out_dir, r["class_key"], f"{r['photo_id']}.jpg")
        if not (os.path.exists(dest) and os.path.getsize(dest) > 0):
            todo.append((r, dest))
    print(f"{len(rows):,} photos in manifest, {len(rows) - len(todo):,} already downloaded, {len(todo):,} to fetch",
          flush=True)

    lock = threading.Lock()
    stats = {"ok": 0, "failed": 0, "bytes": 0}
    failures = []
    t0 = time.time()

    def work(item):
        r, dest = item
        url = PHOTO_URL.format(photo_id=r["photo_id"], size=args.size, ext=r["extension"])
        data = to_jpeg(fetch(url))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        tmp = dest + ".part"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, dest)
        return len(data)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(work, item): item for item in todo}
        for i, fut in enumerate(as_completed(futures), 1):
            try:
                size = fut.result()
                with lock:
                    stats["ok"] += 1
                    stats["bytes"] += size
            except Exception as e:  # keep going; report at the end
                r, _ = futures[fut]
                with lock:
                    stats["failed"] += 1
                    failures.append({"photo_id": r["photo_id"], "class_key": r["class_key"], "error": str(e)[:200]})
            if i % 2000 == 0 or i == len(todo):
                rate = i / max(time.time() - t0, 1e-6)
                print(f"  {i:,}/{len(todo):,}  ok={stats['ok']:,} failed={stats['failed']:,} "
                      f"{stats['bytes'] / 1e9:.2f} GB  {rate:.0f}/s", flush=True)

    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "download_report.json"), "w") as f:
        json.dump({"stats": stats, "failures": failures[:1000]}, f, indent=1)
    print(f"done: {stats['ok']:,} downloaded, {stats['failed']:,} failed")
    if stats["failed"] > 0.05 * max(len(todo), 1):
        sys.exit("more than 5% of downloads failed; re-run to retry")


if __name__ == "__main__":
    main()
