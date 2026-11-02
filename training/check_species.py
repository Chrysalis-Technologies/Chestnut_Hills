#!/usr/bin/env python3
"""Sanity-check species/nys_trees.json (run in CI and before training).

Checks: required fields, unique keys, valid key format, lookalikes point at real
classes, exactly one catch-all class, and (optionally) that labels.txt in the
app's assets only names classes from the guide.
"""
from __future__ import annotations

import json
import os
import re
import sys

REQUIRED = ("key", "common_name", "scientific_name", "family", "group", "status", "inat_taxa",
            "features", "lookalikes")
GROUPS = {"conifer", "broadleaf", "other"}


def main(path: str = "species/nys_trees.json", labels: str = "android/app/src/main/assets/labels.txt") -> int:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    classes = data["classes"]
    errors: list[str] = []
    keys = [c.get("key", "") for c in classes]
    key_set = set(keys)

    if len(key_set) != len(keys):
        dupes = sorted({k for k in keys if keys.count(k) > 1})
        errors.append(f"duplicate keys: {dupes}")
    taxa_seen: dict[str, str] = {}
    for c in classes:
        k = c.get("key", "?")
        for field in REQUIRED:
            if field not in c:
                errors.append(f"{k}: missing '{field}'")
        if not re.fullmatch(r"_?[a-z][a-z0-9_]*", k):
            errors.append(f"{k}: keys must be lower_snake_case")
        if c.get("group") not in GROUPS:
            errors.append(f"{k}: group must be one of {sorted(GROUPS)}")
        if c.get("group") != "other":
            if not c.get("inat_taxa"):
                errors.append(f"{k}: needs at least one iNaturalist taxon")
            if len(c.get("features", [])) < 2:
                errors.append(f"{k}: give at least two identification features")
        for other in c.get("lookalikes", []):
            if other not in key_set:
                errors.append(f"{k}: lookalike '{other}' is not a class key")
            if other == k:
                errors.append(f"{k}: lists itself as a lookalike")
        for name in c.get("inat_taxa", []):
            if name in taxa_seen:
                errors.append(f"{k}: taxon '{name}' already used by {taxa_seen[name]}")
            taxa_seen[name] = k
    specials = [c["key"] for c in classes if c.get("special") == "other_plants"]
    if len(specials) != 1:
        errors.append(f"expected exactly one 'other_plants' class, found {specials}")

    if os.path.exists(labels):
        with open(labels, encoding="utf-8") as f:
            for line in f:
                label = line.strip()
                if label and label not in key_set:
                    errors.append(f"labels.txt names '{label}', which is not in the species file")

    for e in errors:
        print(f"ERROR: {e}")
    trees = sum(1 for c in classes if c.get("group") != "other")
    print(f"{len(classes)} classes ({trees} trees): {'OK' if not errors else f'{len(errors)} problem(s)'}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
