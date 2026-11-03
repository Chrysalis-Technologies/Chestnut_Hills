# Illustrated field-guide content (v0.3.0)

The supplemental asset `android/app/src/main/assets/guide/guide.json` covers the
99 tree entries, keyed by the existing species IDs. It contains four New York
seasonal observation notes per entry and 495 image placements across five topics:
bark, leaves/needles, branches/buds, fruit/nuts/cones, and seasonal changes.
There are 463 distinct image files (about 67 MiB), including eight botanical
illustrations. Images that illustrate more than one topic share the same file.
Nothing in this content changes the classifier, species list or preprocessing.

## Sources and scope

Seasonal prose combines the original bundled identification features with
[NC State Extension's Plant Toolbox](https://plants.ces.ncsu.edu/) and
[New York State Parks' winter identification guidance](https://nystateparks.blog/2015/01/27/winter-tree-identification-part-i-deciduous-trees/).
Each entry lists its applicable source references in the app. Observation windows
are broad seasons, not promised flowering dates: elevation, weather, age, sex and
cultivar affect the features present. Photographs taken outside New York illustrate
structures, not New York calendar dates. These additions do not improve model accuracy.

Images were selected from NC State Extension and Wikimedia Commons and visually
checked for the illustrated organ. This is not an independent botanical validation.
Some images show young foliage, whole trunks, pollen cones or historical drawings;
captions distinguish these when needed. No generated plant images are included.

The existing grouped entries use named examples: flowering cherries (Prunus
serrulata and Prunus × yedoensis), apples/crabapples (Malus domestica), hawthorns
(Crataegus monogyna), serviceberries (Amelanchier canadensis), mountain-ashes
(Sorbus americana), magnolias (Magnolia stellata), catalpas (Catalpa speciosa),
and mulberries (Morus rubra). They are not illustrations of every member or cultivar.

**Specific image limitation:** the weeping-willow fruit image shows the related
hybrid **Salix × sepulcralis**, explicitly labeled on its card and in the seasonal
notes. It is a comparison image, not a photograph verified as pure S. babylonica.
Bigtooth-aspen bark is shown in a whole-stand view of young trunks, not a bark macro.

## Attribution and redistribution

The JSON is the per-image attribution manifest: local asset path, original source
title, creator/credit, source page, license/version and license URL, modification
notice, and SHA-256 of the bundled file. The in-app **Image credit** dialog exposes
the title, credit, license, public source URL and resizing notice without a network
request. Follow the original source link outside the app for the source-resolution
file. Preserve each image's attribution and license when redistributing it; image
licenses are independent of the application's source-code license.

Eligible licenses are CC0/public domain, CC BY and CC BY-SA. The Ohio Department of
Natural Resources slippery-elm fruit image has an attribution-only permission
[documented on its source page](https://commons.wikimedia.org/wiki/File:Ulmus_rubra_seeds.jpg):
reuse, modification and commercial redistribution are permitted with attribution.
That image retains its specific permission rather than being relabeled Creative Commons.
ShareAlike images and the resized versions retain their respective ShareAlike licenses.

Files were orientation-corrected, resized to at most 800 pixels on the long side,
and encoded as JPEG. They were not cropped or botanically altered. Original image
metadata is not required at runtime; no signed download URLs or network loaders are
bundled. Image/source identities and licenses were retrieved on 2026-11-03.

## Offline map

`maps/new_york.geojson` is the New York feature extracted from Natural Earth's
1:10m admin-1 states/provinces dataset. Natural Earth data is
[public domain](https://www.naturalearthdata.com/about/terms-of-use/).
This is a boundary overview, not a street map, survey or navigation product.

Detailed backgrounds are user-imported raster MBTiles. The reader follows the
[MBTiles 1.3 specification](https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md):
Web Mercator tiles, TMS tile rows, PNG/JPEG/WebP, zoom levels 0–22 in the file.
The UI supports zoom 2–20. Imports are limited to 1 GiB and raster images to
1024 pixels per side and 8 MiB compressed per tile. Metadata attribution is shown
below the map. There is no download service, network SDK or INTERNET permission.

An import is validated before replacing the previous private copy. Corrupt,
empty and unsupported vector maps retain the previous selection. Removing the
map deletes only the private copy, not the user's original or saved sightings.

## Verification and device checklist

Automated tests cover guide completeness and decodable local assets, projection,
coordinate filtering, fit/pan/zoom, TMS orientation, real SQLite map imports,
restart persistence, replacement, rejected imports, and removal behavior.
CI also checks the built APK for absence of the INTERNET permission.

The following manual checks have **not been run on a device**:

1. Install over v0.2.0; confirm existing sightings, photos and notes remain.
2. In airplane mode, browse the guide, switch all four seasons, scroll images and
   open credits; check readability on the XCover and large-font settings.
3. Check an empty map, known/unknown identifications, missing coordinates, GPS and
   photo-EXIF locations, and distant/overlapping pins; open the correct identification.
4. Pan, pinch, use zoom buttons, Fit sightings, New York and Locate; rotate the phone.
5. Edit an identification or delete it, then confirm the map reflects the change.
6. Import a known raster MBTiles map; confirm alignment at a known coordinate,
   attribution, covered/uncovered zooms, rotation and persistence after restart.
7. Try corrupt, empty and vector MBTiles; confirm the previous map still works.
8. Replace/remove a map; confirm the original file and sightings remain intact.
9. Recheck existing CSV/GeoJSON exports and saving with GPS permission denied.
