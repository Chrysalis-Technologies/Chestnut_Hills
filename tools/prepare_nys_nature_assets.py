"""Package pinned offline models and a dated NY observation snapshot.

Usage: python tools/prepare_nys_nature_assets.py /path/to/model-downloads
Expects iris/ and fungi/ downloads; never changes the existing model packs.
No network calls are made by this script or by the Android app.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / 'tools/data/ny-nature-observations-2026-11-03.json'
PACKS = {
    'iris': ('plants.tflite', 1569, 'a919ae6a24f7e7717a36d5213a800a3410f7cee0f694f5014ba8eb07f0a6caa3'),
    'fungi': ('mushroom_model_fp16.tflite', 2827, 'b3c129430abb62d22853781cef3302f301ef7e348e453793c0e05c04ad937610'),
}
# Ferns and fern allies. These tags select an existing trained output, never add one.
FERN_GENERA = set('Adiantum Asplenium Athyrium Blechnum Cyrtomium Davallia Dicksonia Dryopteris Equisetum Lycopodium Matteuccia Microsorum Nephrolepis Onoclea Osmunda Osmundastrum Phlebodium Platycerium Polypodium Polystichum Pteridium Pteris Selaginella Thelypteris'.split())
# Exact climbing/trailing species; shrub honeysuckles are deliberately excluded.
# Botanical references: University of Maryland Extension, Vines for Maryland Gardens;
# Clemson HGIC, Vine Selections for Landscaping; NY occurrence comes from SNAPSHOT.
VINES = set('''Actinidia chinensis
Akebia quinata
Calystegia sepium
Campsis grandiflora
Campsis radicans
Celastrus orbiculatus
Clematis armandi
Clematis flammula
Clematis montana
Clematis vitalba
Clematis viticella
Convolvulus arvensis
Dioscorea elephantipes
Hedera canariensis
Hedera helix
Humulus lupulus
Ipomoea batatas
Ipomoea indica
Ipomoea purpurea
Lonicera caprifolium
Lonicera implexa
Lonicera japonica
Lonicera periclymenum
Parthenocissus inserta
Parthenocissus quinquefolia
Parthenocissus tricuspidata
Passiflora caerulea
Passiflora edulis
Smilax aspera
Solanum dulcamara
Toxicodendron radicans
Vicia cracca
Vicia sativa
Vitis vinifera
Wisteria floribunda
Wisteria sinensis'''.splitlines())


def build(source: Path) -> None:
    destination = ROOT / 'android/app/src/main/assets/nature-expanded'
    destination.mkdir(parents=True, exist_ok=True)
    legacy = json.loads((ROOT / 'android/app/src/main/assets/nature/catalog.json').read_text(encoding='utf-8'))
    old_names = {row['scientificName']: row for row in legacy}
    snapshot = json.loads(SNAPSHOT.read_text(encoding='utf-8'))
    ny = {row['name']: row for row in snapshot['taxa']}

    def decorate(name, index, kind, fallback_key, expanded=False):
        record = ny.get(name)
        old = old_names.get(name)
        if expanded and not record and not (old and old['kind'] is not None):
            kind = None  # Keep every model output competing, expose NY expansion only.
        if kind == 'mushroom' and ((record and 54743 in record['ancestorIds']) or
                                   (old and old['kind'] is None)):
            kind = None  # Lichens retain outputs but are not mushroom suggestions.
        return {
            'index': index,
            'key': old['key'] if old else str(record['taxonId']) if record else fallback_key,
            'scientificName': name,
            'commonName': record['commonName'] if record else old['commonName'] if old else name,
            'kind': kind,
            'fern': name.split()[0] in FERN_GENERA and record is not None,
            'vine': name in VINES and (record is not None or bool(old and old['kind'] == 'flower')),
            'nyObservations': record['observations'] if record else None,
        }

    result = {'snapshotDate': snapshot['retrievedOn'], 'legacy': [
        decorate(r['scientificName'], r['index'], r['kind'], r['key']) for r in legacy]}
    for pack, (filename, size, expected) in PACKS.items():
        folder = source / pack
        digest = hashlib.sha256((folder / filename).read_bytes()).hexdigest()
        if digest != expected:
            raise ValueError(f'{pack}: model hash mismatch')
        labels = (folder / 'labels.txt').read_text(encoding='utf-8').splitlines()
        if len(labels) != size or len(set(labels)) != size:
            raise ValueError(f'{pack}: invalid label order/length')
        metadata = json.loads((folder / 'model.json').read_text()) if pack == 'iris' else None
        names = [metadata['species'][label] for label in labels] if metadata else labels
        result[pack] = [decorate(name, i, 'flower' if pack == 'iris' else 'mushroom',
                                 f'{pack}:{label}', expanded=True) for i, (name, label) in enumerate(zip(names, labels))]
        assert len({r['key'] for r in result[pack]}) == size
        shutil.copyfile(folder / filename, destination / filename)
        shutil.copyfile(folder / 'labels.txt', destination / (pack + '-labels.txt'))
        meta_file = 'model.json' if pack == 'iris' else 'export_info.json'
        shutil.copyfile(folder / meta_file, destination / (pack + '-' + meta_file))
    (destination / 'catalog.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    unique = {r['key']: r for section in ['legacy', 'iris', 'fungi'] for r in result[section]}
    counts = {
        'plants': lambda r: r['kind'] == 'flower' or r['fern'],
        'mushrooms': lambda r: r['kind'] == 'mushroom',
        'ferns_and_allies': lambda r: r['fern'],
        'vines': lambda r: r['vine'],
    }
    print(json.dumps({k: {'total': sum(test(r) for r in unique.values()),
                         'ny': sum(test(r) and r['nyObservations'] is not None for r in unique.values())}
                      for k, test in counts.items()}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    build(parser.parse_args().source)
