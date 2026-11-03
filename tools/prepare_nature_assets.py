"""Package iNaturalist Small 2 assets downloaded separately from release v25.01.15.

Usage: python tools/prepare_nature_assets.py /path/to/downloads
This build-time script has no network calls and never modifies the tree assets.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil
import tarfile

MODEL_SHA256 = "eae277b24efa629b998d5f9c091da0576162cb1aad498786087acd001dc86d2c"


def build(source: Path, destination: Path) -> None:
    model = source / "INatVision_Small_2_fact256_8bit.tflite"
    digest = hashlib.sha256(model.read_bytes()).hexdigest()
    if digest != MODEL_SHA256:
        raise ValueError("Unexpected iNaturalist model; do not silently change model versions")
    rows = list(csv.DictReader((source / "taxonomy.csv").open(encoding="utf-8")))
    by_id = {r["taxon_id"]: r for r in rows}
    names = {}
    with tarfile.open(source / "commonNames.tar.gz") as archive:
        for member in archive.getmembers():
            if not member.isfile() or not member.name.endswith(".js"):
                continue
            text = archive.extractfile(member).read().decode("utf-8")
            entries, _ = json.JSONDecoder().raw_decode(text[text.index("["):])
            names.update({str(e["i"]): e["n"] for e in entries if e["l"] == "en"})
    labels = []
    for row in rows:
        if not row["leaf_class_id"]:
            continue
        lineage = set()
        node = row
        while node:
            if node["taxon_id"] in lineage:
                raise ValueError("Taxonomy cycle")
            lineage.add(node["taxon_id"])
            node = by_id.get(node["parent_taxon_id"])
        ancestor_names = {by_id[key]["name"] for key in lineage}
        kind = None
        if "Angiospermae" in ancestor_names:
            kind = "flower"
        elif "Fungi" in ancestor_names and "Lecanoromycetes" not in ancestor_names:
            kind = "mushroom"
        labels.append({"index": int(row["leaf_class_id"]), "key": row["taxon_id"],
                       "scientificName": row["name"], "commonName": names.get(row["taxon_id"], row["name"]), "kind": kind})
    labels.sort(key=lambda label: label["index"])
    assert [label["index"] for label in labels] == list(range(507))
    assert sum(label["kind"] == "flower" for label in labels) == 174
    assert sum(label["kind"] == "mushroom" for label in labels) == 18
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, destination / "inat-small-2.tflite")
    shutil.copyfile(source / "taxonomy.csv", destination / "taxonomy.csv")
    (destination / "catalog.json").write_text(json.dumps(labels, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Packaged 507 outputs: 174 flowering plants and 18 mushroom/fungus taxa. Model SHA256: {digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    build(args.source, Path(__file__).resolve().parents[1] / "android/app/src/main/assets/nature")
