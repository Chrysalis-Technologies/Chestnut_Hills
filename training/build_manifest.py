#!/usr/bin/env python3
"""Build the training-photo manifest from the iNaturalist Open Dataset.

Streams the public iNaturalist open-data metadata tables from AWS S3 (no API
key, no rate limits) and picks research-grade photos for every class in
species/nys_trees.json. Observations from New York are preferred, then the
surrounding Northeast, then anywhere else.

Output: a gzipped CSV (one row per photo) plus a JSON summary. Photos
themselves are fetched later by download_images.py.

The two big tables (observations ~14 GB, photos ~21 GB compressed) are
streamed, never written to disk. Expect roughly 30-60 minutes depending on
bandwidth. Use --cache-dir to keep the small tables between runs.

Usage:
  python training/build_manifest.py \
      --species species/nys_trees.json \
      --out training/data/manifest.csv.gz
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import random
import shutil
import subprocess
import sys
import time
import urllib.request
from collections import defaultdict

OPEN_DATA = "https://inaturalist-open-data.s3.amazonaws.com"
PLANTAE_ID = "47126"

# Rough bounding boxes (lat_min, lat_max, lon_min, lon_max).
NY_BOX = (40.47, 45.03, -79.78, -71.84)
NORTHEAST_BOX = (37.5, 48.0, -84.0, -66.0)
TIER_NAMES = ("ny", "northeast", "elsewhere")


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def in_box(lat: float, lon: float, box) -> bool:
    return box[0] <= lat <= box[1] and box[2] <= lon <= box[3]


def tier_for(lat_s: str, lon_s: str) -> int:
    try:
        lat, lon = float(lat_s), float(lon_s)
    except ValueError:
        return 2
    if in_box(lat, lon, NY_BOX):
        return 0
    if in_box(lat, lon, NORTHEAST_BOX):
        return 1
    return 2


def open_remote_tsv(name: str, cache_dir: str | None):
    """Yield parsed rows (lists) of an open-data table, header first.

    Small tables are cached when cache_dir is given; big ones are streamed
    through `curl | gzip -dc` so decompression runs in parallel with parsing.
    """
    url = f"{OPEN_DATA}/{name}.csv.gz"
    if cache_dir:
        path = os.path.join(cache_dir, f"{name}.csv.gz")
        if not os.path.exists(path):
            log(f"downloading {url}")
            tmp = path + ".part"
            with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, length=1 << 20)
            os.replace(tmp, path)
        with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
            for line in f:
                yield line.rstrip("\n").split("\t")
        return

    if shutil.which("curl") and shutil.which("gzip"):
        cmd = f"curl -sSfL --retry 5 --retry-delay 5 '{url}' | gzip -dc"
        proc = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, bufsize=1 << 20)
        assert proc.stdout is not None
        stream = io.TextIOWrapper(proc.stdout, encoding="utf-8", newline="")
        for line in stream:
            yield line.rstrip("\n").split("\t")
        if proc.wait() != 0:
            raise RuntimeError(f"streaming {url} failed (exit {proc.returncode})")
    else:  # pure-Python fallback
        with urllib.request.urlopen(url) as r:
            with gzip.open(r, "rt", encoding="utf-8", newline="") as f:
                for line in f:
                    yield line.rstrip("\n").split("\t")


def load_plant_taxa(cache_dir: str | None):
    """Return {taxon_id: (ancestor_ids, rank, rank_level, name, active)} for Plantae."""
    rows = open_remote_tsv("taxa", cache_dir)
    header = next(rows)
    idx = {h: i for i, h in enumerate(header)}
    taxa = {}
    for r in rows:
        if len(r) < len(header):
            continue
        ancestry = r[idx["ancestry"]].split("/") if r[idx["ancestry"]] else []
        if PLANTAE_ID not in ancestry and r[idx["taxon_id"]] != PLANTAE_ID:
            continue
        try:
            level = float(r[idx["rank_level"]])
        except ValueError:
            level = 0.0
        taxa[r[idx["taxon_id"]]] = (
            ancestry,
            r[idx["rank"]],
            level,
            r[idx["name"]],
            r[idx["active"]].lower() == "true",
        )
    log(f"loaded {len(taxa):,} plant taxa")
    return taxa


def resolve_name(name: str, taxa) -> str:
    candidates = [tid for tid, t in taxa.items() if t[3] == name and t[4]]
    if not candidates:
        raise SystemExit(f"ERROR: no active iNaturalist plant taxon named '{name}'")
    if len(candidates) == 1:
        return candidates[0]
    want_genus = len(name.split()) == 1
    preferred = [
        tid for tid in candidates
        if (taxa[tid][1] == "genus") == want_genus and (want_genus or taxa[tid][2] == 10)
    ]
    if len(preferred) == 1:
        return preferred[0]
    raise SystemExit(
        f"ERROR: '{name}' is ambiguous: "
        + ", ".join(f"{tid} ({taxa[tid][1]})" for tid in candidates)
    )


def genus_of(tid: str, taxa) -> str | None:
    t = taxa.get(tid)
    if not t:
        return None
    if t[1] == "genus":
        return tid
    for anc in reversed(t[0]):
        a = taxa.get(anc)
        if a and a[1] == "genus":
            return anc
    return None


def split_for(observation_uuid: str, val_pct: int, test_pct: int) -> str:
    h = int(hashlib.sha1(observation_uuid.encode()).hexdigest()[:8], 16) % 100
    if h < test_pct:
        return "test"
    if h < test_pct + val_pct:
        return "val"
    return "train"


class Reservoir:
    def __init__(self, size: int, rng: random.Random):
        self.size, self.rng, self.seen, self.items = size, rng, 0, []

    def add(self, item) -> None:
        self.seen += 1
        if len(self.items) < self.size:
            self.items.append(item)
        else:
            j = self.rng.randrange(self.seen)
            if j < self.size:
                self.items[j] = item


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--species", default="species/nys_trees.json")
    ap.add_argument("--out", default="training/data/manifest.csv.gz")
    ap.add_argument("--cache-dir", default=None, help="keep the small taxa/observers tables here")
    ap.add_argument("--max-obs-per-class", type=int, default=500)
    ap.add_argument("--photos-per-obs", type=int, default=2)
    ap.add_argument("--max-obs-per-observer", type=int, default=25,
                    help="cap per observer per class, so no single person dominates a class")
    ap.add_argument("--other-obs", type=int, default=1500,
                    help="observations for the '_other' (not a tree in this guide) class")
    ap.add_argument("--val-pct", type=int, default=10)
    ap.add_argument("--test-pct", type=int, default=10)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--max-rows", type=int, default=0, help="debug: stop after N rows of each big table")
    ap.add_argument("--resolve-only", action="store_true",
                    help="only check that every name in the species file resolves, then exit")
    args = ap.parse_args()

    if args.cache_dir:
        os.makedirs(args.cache_dir, exist_ok=True)
    rng = random.Random(args.seed)

    with open(args.species, encoding="utf-8") as f:
        species = json.load(f)
    classes = species["classes"]
    keys = [c["key"] for c in classes]
    if len(set(keys)) != len(keys):
        raise SystemExit("ERROR: duplicate class keys in species file")
    other_key = next((c["key"] for c in classes if c.get("special") == "other_plants"), None)

    # 1. Resolve names -> taxon ids.
    taxa = load_plant_taxa(args.cache_dir)
    root_to_class: dict[str, str] = {}
    resolved = {}
    for c in classes:
        for name in c.get("inat_taxa", []):
            tid = resolve_name(name, taxa)
            if tid in root_to_class:
                raise SystemExit(f"ERROR: taxon {name} is used by two classes")
            root_to_class[tid] = c["key"]
            resolved.setdefault(c["key"], []).append({"name": name, "taxon_id": int(tid), "rank": taxa[tid][1]})
    log(f"resolved {len(root_to_class)} taxa for {len(resolved)} classes")
    if args.resolve_only:
        for key, items in resolved.items():
            print(f"  {key:28s} " + ", ".join(f"{i['name']} ({i['rank']} {i['taxon_id']})" for i in items))
        return

    # 2. Map every plant taxon to a class (itself or nearest listed ancestor).
    taxon_to_class: dict[str, str] = {}
    for tid, t in taxa.items():
        hits = [root_to_class[a] for a in t[0] + [tid] if a in root_to_class]
        if hits:
            if len(set(hits)) > 1:
                raise SystemExit(f"ERROR: taxon {t[3]} falls under several classes: {set(hits)}")
            taxon_to_class[tid] = hits[0]

    # '_other': species-level plants outside every genus used by a class.
    other_ok: set[str] = set()
    if other_key:
        excluded_genera = {genus_of(tid, taxa) for tid in root_to_class} - {None}
        for tid, t in taxa.items():
            if tid in taxon_to_class or t[2] > 10:
                continue
            g = genus_of(tid, taxa)
            if g is not None and g not in excluded_genera:
                other_ok.add(tid)
        log(f"'_other' pool: {len(other_ok):,} plant taxa outside {len(excluded_genera)} excluded genera")

    # 3. Stream observations, keeping a random reservoir per (class, tier).
    pool = args.max_obs_per_class * 3
    reservoirs: dict[tuple[str, int], Reservoir] = {}
    rows = open_remote_tsv("observations", None)
    header = next(rows)
    oi = {h: i for i, h in enumerate(header)}
    n = kept = 0
    t0 = time.time()
    for r in rows:
        n += 1
        if n % 10_000_000 == 0:
            log(f"observations: {n:,} rows scanned, {kept:,} candidates ({n / (time.time() - t0):,.0f} rows/s)")
        if args.max_rows and n > args.max_rows:
            break
        if len(r) < 7 or r[oi["quality_grade"]] != "research":
            continue
        tid = r[oi["taxon_id"]]
        key = taxon_to_class.get(tid)
        tier = tier_for(r[oi["latitude"]], r[oi["longitude"]])
        if key is None:
            if tid in other_ok and tier == 0:
                key = other_key
            else:
                continue
        res = reservoirs.get((key, tier))
        if res is None:
            size = args.other_obs * 3 if key == other_key else pool
            res = reservoirs[(key, tier)] = Reservoir(size, rng)
        res.add((r[oi["observation_uuid"]], r[oi["observer_id"]], tid, tier))
        kept += 1
    log(f"observations done: {n:,} rows, {kept:,} candidates")

    # 4. Choose observations: NY first, then Northeast, then anywhere; cap per observer.
    chosen: dict[str, tuple[str, str, str, int]] = {}  # obs uuid -> (class, observer, taxon, tier)
    counts = defaultdict(lambda: [0, 0, 0])
    available = defaultdict(lambda: [0, 0, 0])
    for c in classes:
        key = c["key"]
        limit = args.other_obs if key == other_key else args.max_obs_per_class
        per_observer = defaultdict(int)
        taken = 0
        for tier in range(3):
            res = reservoirs.get((key, tier))
            if not res:
                continue
            available[key][tier] = res.seen
            items = list(res.items)
            rng.shuffle(items)
            for obs_uuid, observer, tid, t in items:
                if taken >= limit:
                    break
                if per_observer[observer] >= args.max_obs_per_observer:
                    continue
                per_observer[observer] += 1
                chosen[obs_uuid] = (key, observer, tid, t)
                counts[key][t] += 1
                taken += 1
    log(f"chose {len(chosen):,} observations")

    # 5. Stream photos for the chosen observations.
    photos_by_obs: dict[str, list[tuple[int, str, str, str]]] = defaultdict(list)
    rows = open_remote_tsv("photos", None)
    header = next(rows)
    pi = {h: i for i, h in enumerate(header)}
    n = 0
    t0 = time.time()
    for r in rows:
        n += 1
        if n % 20_000_000 == 0:
            log(f"photos: {n:,} rows scanned ({n / (time.time() - t0):,.0f} rows/s)")
        if args.max_rows and n > args.max_rows:
            break
        if len(r) < len(header):
            continue
        obs = r[pi["observation_uuid"]]
        if obs not in chosen:
            continue
        try:
            pos = int(r[pi["position"]] or 0)
        except ValueError:
            pos = 0
        photos_by_obs[obs].append((pos, r[pi["photo_id"]], r[pi["extension"]], r[pi["license"]]))
    log(f"photos done: {n:,} rows; found photos for {len(photos_by_obs):,} observations")

    # 6. Attribution names.
    observers = {}
    rows = open_remote_tsv("observers", args.cache_dir)
    header = next(rows)
    wi = {h: i for i, h in enumerate(header)}
    needed = {v[1] for v in chosen.values()}
    for r in rows:
        if len(r) >= 2 and r[wi["observer_id"]] in needed:
            name = r[wi["name"]] if len(r) > wi["name"] and r[wi["name"]] else ""
            observers[r[wi["observer_id"]]] = name or r[wi["login"]]

    # 7. Write manifest.
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    photo_counts = defaultdict(lambda: defaultdict(int))
    total = 0
    with gzip.open(args.out, "wt", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["class_key", "split", "observation_uuid", "photo_id", "extension",
                    "license", "observer", "taxon_id", "region"])
        for obs_uuid in sorted(chosen, key=lambda u: (chosen[u][0], u)):
            key, observer, tid, tier = chosen[obs_uuid]
            split = split_for(obs_uuid, args.val_pct, args.test_pct)
            for pos, photo_id, ext, lic in sorted(photos_by_obs.get(obs_uuid, []))[: args.photos_per_obs]:
                w.writerow([key, split, obs_uuid, photo_id, ext, lic,
                            observers.get(observer, observer), tid, TIER_NAMES[tier]])
                photo_counts[key][split] += 1
                total += 1
    log(f"wrote {total:,} photos to {args.out}")

    summary = {
        "source": "iNaturalist Open Dataset (research-grade observations)",
        "built": time.strftime("%Y-%m-%d"),
        "params": {k: v for k, v in vars(args).items() if k not in ("out", "cache_dir")},
        "resolved_taxa": resolved,
        "classes": {
            c["key"]: {
                "observations_by_region": dict(zip(TIER_NAMES, counts[c["key"]])),
                "research_grade_available": dict(zip(TIER_NAMES, available[c["key"]])),
                "photos": dict(photo_counts[c["key"]]),
            }
            for c in classes
        },
        "total_photos": total,
    }
    summary_path = os.path.splitext(os.path.splitext(args.out)[0])[0] + "_summary.json"
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=1, ensure_ascii=False)
    log(f"wrote {summary_path}")

    thin = [k for k in keys if sum(photo_counts[k].values()) < 100]
    if thin:
        log("WARNING: classes with fewer than 100 photos: " + ", ".join(thin))


if __name__ == "__main__":
    main()
