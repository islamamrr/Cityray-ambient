# ECARX catalog builder

`carlib/src/main/assets/ecarx_catalog.json` lists every id the ECARX AdaptAPI names
(sensors, car functions, car info, sensor groups, key codes). `EcarxCatalog` loads it.

It was built from decompiled APKs that ship on, or were made for, Geely head units:

1. `com.salat.gbinder` 4.3.1 bundles compile stubs of the ECARX AdaptAPI 1.1.18.12
   (`com.ecarx.xui.adaptapi.*`). Their `static final int` constants give the authoritative
   names for `ISensor`, `ISensorGroup`, `ICarInfo`, `IVehicle` and `KeyCode`.
2. The same app keeps a table of about 975 ECARX properties for its own debug screen
   (constant name, kind, id, named values). That adds the module interfaces the stubs
   leave out (`IHvac`, `IBcm`, `ISeat`, `IPAS`, ...) and value names such as
   `IGNITION_STATE_ON`.

Only names and numbers are kept: no code and no descriptions from those apps.

To rebuild (jadx 1.5.x, Python 3):

```
jadx -d out/gbinder gbinder.apk
python3 tools/catalog/extract_gbinder.py out/gbinder/sources /tmp/gbinder_props.json
python3 tools/catalog/build_catalog.py out/gbinder/sources /tmp/gbinder_props.json carlib/src/main/assets/ecarx_catalog.json
```

`extract_gbinder.py` expects the property table in `e7/a.java`, which is where jadx put it
for gbinder 4.3.1. A newer gbinder will have different obfuscated names: search for the
class whose `toString()` prints `PropertyData(alias=`.

When two names share an id, the stub name wins and the other goes in `aka`. The gear
sensor's value names in gbinder's table are wrong (they are charging-gear levels), so they
are dropped; use `CarDecode.gear`.
